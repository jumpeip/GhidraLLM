package ghidrallm.llm;

import static org.junit.Assert.*;

import java.util.*;

import org.junit.*;

import com.google.gson.*;

import ghidrallm.config.*;

public class HttpAndProvidersTest {

	private final List<Long> sleeps = new ArrayList<>();
	private HttpTransport.Sleeper original;

	@Before
	public void setUp() {
		original = HttpTransport.sleeper;
		HttpTransport.sleeper = (ms, c) -> sleeps.add(ms);
		HttpTransport.clearClientCache();
	}

	@After
	public void tearDown() {
		HttpTransport.sleeper = original;
	}

	private static ProviderConfig cfg(ProviderType t, String endpoint) {
		ProviderConfig p = new ProviderConfig("test", t, "m1");
		p.setUrl(endpoint);
		p.apiKey = "k123";
		p.retryInitialDelayMs = 1000;
		p.retryMaxDelayMs = 8000;
		return p;
	}

	private static final String OPENAI_OK =
		"{\"model\":\"m1\",\"choices\":[{\"message\":{\"content\":\"hello\"},\"finish_reason\":\"stop\"}]," +
			"\"usage\":{\"prompt_tokens\":11,\"completion_tokens\":2}}";

	@Test
	public void retriesOn429And500ThenSucceeds() throws Exception {
		try (MockServer s = new MockServer()) {
			s.then(429, "{\"error\":{\"message\":\"slow down\"}}", Map.of("Retry-After", "3"))
					.then(500, "oops")
					.then(503, "busy")
					.then(200, OPENAI_OK);
			ProviderConfig p = cfg(ProviderType.OPENAI_COMPATIBLE, s.url("/v1/chat/completions"));
			p.maxRetries = 5;
			LLMResponse r = LLMClient.chat(p, LLMRequest.single("sys", "hi", false), null, null);
			assertEquals("hello", r.getText());
			assertEquals(4, r.getAttempts());
			assertEquals(3, sleeps.size());
			assertEquals(3000L, (long) sleeps.get(0)); // Retry-After honored
			assertTrue(sleeps.get(1) >= 1600 && sleeps.get(1) <= 2400); // 2s +/- 20%
			assertTrue(sleeps.get(2) >= 3200 && sleeps.get(2) <= 4800); // 4s +/- 20%
		}
	}

	@Test
	public void givesUpAfterMaxRetries() throws Exception {
		try (MockServer s = new MockServer()) {
			for (int i = 0; i < 5; i++) {
				s.then(500, "{\"error\":{\"message\":\"internal\"}}");
			}
			ProviderConfig p = cfg(ProviderType.OPENAI_COMPATIBLE, s.url("/c"));
			p.maxRetries = 2;
			try {
				LLMClient.chat(p, LLMRequest.single("", "hi", false), null, null);
				fail();
			}
			catch (LLMException e) {
				assertEquals(500, e.getStatusCode());
				assertTrue(e.getMessage().contains("internal"));
			}
			assertEquals(3, s.seen.size());
		}
	}

	@Test
	public void doesNotRetry401() throws Exception {
		try (MockServer s = new MockServer()) {
			s.then(401, "{\"error\":{\"message\":\"bad key\"}}");
			ProviderConfig p = cfg(ProviderType.OPENAI_COMPATIBLE, s.url("/c"));
			p.maxRetries = 20000;
			try {
				LLMClient.chat(p, LLMRequest.single("", "hi", false), null, null);
				fail();
			}
			catch (LLMException e) {
				assertEquals(401, e.getStatusCode());
			}
			assertEquals(1, s.seen.size());
		}
	}

	@Test
	public void retryLimitClampsAt20000() {
		ProviderConfig p = new ProviderConfig();
		p.maxRetries = 50000;
		p.clampValues();
		assertEquals(20000, p.maxRetries);
		p.maxRetries = 20000;
		p.clampValues();
		assertEquals(20000, p.maxRetries);
		// backoff never overflows even at huge attempt numbers
		p.retryInitialDelayMs = 1000;
		p.retryMaxDelayMs = 60000;
		long d = HttpTransport.backoff(p, 19999);
		assertTrue(d > 0 && d <= 60000);
	}

	@Test
	public void cancellationStopsRetrying() throws Exception {
		HttpTransport.sleeper = original; // real (interruptible) sleep
		try (MockServer s = new MockServer()) {
			for (int i = 0; i < 10; i++) {
				s.then(429, "{}");
			}
			ProviderConfig p = cfg(ProviderType.OPENAI_COMPATIBLE, s.url("/c"));
			p.maxRetries = 20000;
			p.retryInitialDelayMs = 60000;
			long start = System.currentTimeMillis();
			long[] deadline = { start + 700 };
			try {
				LLMClient.chat(p, LLMRequest.single("", "hi", false),
					() -> System.currentTimeMillis() > deadline[0], null);
				fail();
			}
			catch (LLMException e) {
				assertTrue(e.isCancelled());
			}
			assertTrue(System.currentTimeMillis() - start < 5000);
		}
	}

	@Test
	public void anthropicFormat() throws Exception {
		try (MockServer s = new MockServer()) {
			s.then(200, "{\"model\":\"claude-x\",\"content\":[{\"type\":\"thinking\",\"thinking\":\"..\"}," +
				"{\"type\":\"text\",\"text\":\"answer\"}],\"stop_reason\":\"end_turn\"," +
				"\"usage\":{\"input_tokens\":5,\"output_tokens\":1}}");
			ProviderConfig p = cfg(ProviderType.ANTHROPIC, s.url("/v1/messages"));
			p.useTemperature = true;
			p.temperature = 0.3;
			List<ChatMessage> msgs = List.of(ChatMessage.assistant("ignored lead"),
				ChatMessage.user("a"), ChatMessage.user("b"));
			LLMResponse r = LLMClient.chat(p, new LLMRequest("SYS", msgs, true), null, null);
			assertEquals("answer", r.getText());
			MockServer.Seen req = s.seen.get(0);
			assertEquals("k123", req.header("x-api-key"));
			assertEquals("2023-06-01", req.header("anthropic-version"));
			JsonObject b = JsonParser.parseString(req.body()).getAsJsonObject();
			assertEquals("SYS", b.get("system").getAsString());
			assertEquals(8192, b.get("max_tokens").getAsInt());
			assertEquals(0.3, b.get("temperature").getAsDouble(), 1e-9);
			JsonArray m = b.getAsJsonArray("messages");
			assertEquals(1, m.size()); // leading assistant dropped, consecutive users merged
			assertEquals("a\n\nb", m.get(0).getAsJsonObject().get("content").getAsString());
		}
	}

	@Test
	public void temperatureOmittedWhenDisabled() throws Exception {
		try (MockServer s = new MockServer()) {
			s.then(200, OPENAI_OK);
			ProviderConfig p = cfg(ProviderType.OPENAI_COMPATIBLE, s.url("/c"));
			p.useTemperature = false;
			LLMClient.chat(p, LLMRequest.single("", "hi", false), null, null);
			JsonObject b = JsonParser.parseString(s.seen.get(0).body()).getAsJsonObject();
			assertFalse(b.has("temperature"));
			assertEquals("Bearer k123", s.seen.get(0).header("Authorization"));
		}
	}

	@Test
	public void geminiFormat() throws Exception {
		try (MockServer s = new MockServer()) {
			s.then(200, "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"hmm\",\"thought\":true}," +
				"{\"text\":\"{\\\"a\\\":1}\"}]},\"finishReason\":\"STOP\"}]," +
				"\"usageMetadata\":{\"promptTokenCount\":9,\"candidatesTokenCount\":3}}");
			ProviderConfig p = cfg(ProviderType.GEMINI,
				s.url("/v1beta/models/{model}:generateContent"));
			p.model = "models/gemini-2.5-pro";
			p.jsonMode = true;
			LLMResponse r = LLMClient.chat(p, LLMRequest.single("SYS", "q", true), null, null);
			assertEquals("{\"a\":1}", r.getText());
			MockServer.Seen req = s.seen.get(0);
			assertEquals("/v1beta/models/gemini-2.5-pro:generateContent", req.path());
			assertEquals("k123", req.header("x-goog-api-key"));
			JsonObject b = JsonParser.parseString(req.body()).getAsJsonObject();
			assertEquals("application/json",
				b.getAsJsonObject("generationConfig").get("responseMimeType").getAsString());
			assertEquals("SYS", b.getAsJsonObject("systemInstruction").getAsJsonArray("parts")
					.get(0).getAsJsonObject().get("text").getAsString());
		}
	}

	@Test
	public void ollamaFormatWithNumCtxAndExtraBody() throws Exception {
		try (MockServer s = new MockServer()) {
			s.then(200, "{\"model\":\"q\",\"message\":{\"role\":\"assistant\",\"content\":\"yo\"}," +
				"\"done_reason\":\"stop\",\"prompt_eval_count\":4,\"eval_count\":1}");
			ProviderConfig p = cfg(ProviderType.OLLAMA, s.url("/api/chat"));
			p.apiKey = "";
			p.jsonMode = true;
			p.sendContextWindowToServer = true;
			p.contextWindowTokens = 32768;
			p.extraBodyJson = "{\"keep_alive\":\"10m\",\"options\":{\"top_k\":20}}";
			LLMResponse r = LLMClient.chat(p, LLMRequest.single("S", "q", true), null, null);
			assertEquals("yo", r.getText());
			JsonObject b = JsonParser.parseString(s.seen.get(0).body()).getAsJsonObject();
			assertEquals("json", b.get("format").getAsString());
			assertFalse(b.get("stream").getAsBoolean());
			assertEquals(32768, b.getAsJsonObject("options").get("num_ctx").getAsInt());
			assertEquals(20, b.getAsJsonObject("options").get("top_k").getAsInt());
			assertEquals("10m", b.get("keep_alive").getAsString());
			assertNull(s.seen.get(0).header("Authorization"));
		}
	}

	@Test
	public void extraHeadersAndEnvKeyHint() throws Exception {
		try (MockServer s = new MockServer()) {
			s.then(200, OPENAI_OK);
			ProviderConfig p = cfg(ProviderType.OPENAI_COMPATIBLE, s.url("/c"));
			p.extraHeaders = "X-Team: re\nX-Trace:  abc ";
			LLMClient.chat(p, LLMRequest.single("", "hi", false), null, null);
			assertEquals("re", s.seen.get(0).header("X-Team"));
			assertEquals("abc", s.seen.get(0).header("X-Trace"));
		}
		ProviderConfig a = cfg(ProviderType.ANTHROPIC, "http://127.0.0.1:9/x");
		a.apiKey = "env:GHIDRALLM_DEFINITELY_UNSET_VAR";
		try {
			LLMClient.chat(a, LLMRequest.single("", "hi", false), null, null);
			fail();
		}
		catch (LLMException e) {
			assertTrue(e.getMessage().contains("GHIDRALLM_DEFINITELY_UNSET_VAR"));
		}
	}

	@Test
	public void listModelsVariants() throws Exception {
		try (MockServer s = new MockServer()) {
			s.then(200, "{\"data\":[{\"id\":\"b-model\"},{\"id\":\"a-model\"}]}");
			s.then(200, "[{\"id\":\"openai/gpt-4.1\",\"name\":\"GPT-4.1\"},{\"id\":\"meta/llama\"}]");
			s.then(200, "{\"models\":[{\"name\":\"qwen2.5-coder:14b\",\"model\":\"qwen2.5-coder:14b\"}]}");
			s.then(200, "{\"models\":[{\"name\":\"models/gemini-2.5-pro\",\"supportedGenerationMethods\":" +
				"[\"generateContent\"]},{\"name\":\"models/embedding-001\",\"supportedGenerationMethods\":" +
				"[\"embedContent\"]}]}");
			ProviderConfig o = cfg(ProviderType.OPENAI_COMPATIBLE, s.url("/v1/chat/completions"));
			assertEquals(List.of("a-model", "b-model"), LLMClient.listModels(o, null, null));
			assertEquals("/v1/models", s.seen.get(0).path());

			ProviderConfig gh = cfg(ProviderType.OPENAI_COMPATIBLE, s.url("/inference/chat/completions"));
			gh.modelsPath = s.url("/catalog/models");
			assertEquals(List.of("meta/llama", "openai/gpt-4.1"), LLMClient.listModels(gh, null, null));

			ProviderConfig ol = cfg(ProviderType.OLLAMA, s.url("/api/chat"));
			assertEquals(List.of("qwen2.5-coder:14b"), LLMClient.listModels(ol, null, null));
			assertEquals("/api/tags", s.seen.get(2).path());

			ProviderConfig ge = cfg(ProviderType.GEMINI, s.url("/v1beta/models/{model}:generateContent"));
			assertEquals(List.of("gemini-2.5-pro"), LLMClient.listModels(ge, null, null));
			assertEquals("/v1beta/models", s.seen.get(3).path());
		}
	}

	@Test
	public void defaultsMatchRequirements() {
		LLMConfig c = LLMConfig.createDefault();
		List<String> names = new ArrayList<>();
		for (ProviderConfig p : c.providers) {
			names.add(p.name.toLowerCase());
			assertEquals(1_000_000, p.contextWindowTokens);
			assertEquals(180, p.httpTimeoutSeconds);
			assertFalse(p.useTemperature);
		}
		for (String want : new String[] { "claude", "gemini", "meta", "copilot", "ollama" }) {
			assertTrue(want, names.stream().anyMatch(n -> n.contains(want)));
		}
		assertEquals(12_000, c.analysis.maxCodeChars);
		assertEquals(125_000, c.analysis.maxContextChars);
	}

	@Test
	public void configRoundTrip() throws Exception {
		java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("gllm");
		java.nio.file.Path f = dir.resolve("config.json");
		ConfigStore a = new ConfigStore(f);
		LLMConfig c = a.copy();
		ProviderConfig p = new ProviderConfig("Mine", ProviderType.OLLAMA, "x");
		p.maxRetries = 20000;
		c.providers.add(p);
		c.activeProviderId = p.id;
		a.replace(c);
		ConfigStore b = new ConfigStore(f);
		assertEquals("Mine", b.get().getActiveProvider().name);
		assertEquals(20000, b.get().getActiveProvider().maxRetries);
		java.nio.file.Files.writeString(f, "{ not json");
		ConfigStore broken = new ConfigStore(f);
		assertNotNull(broken.getLastLoadError());
		assertEquals(5, broken.get().providers.size());
	}
}
