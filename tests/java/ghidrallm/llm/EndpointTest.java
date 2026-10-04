package ghidrallm.llm;

import static org.junit.Assert.*;

import org.junit.*;

import com.google.gson.*;

import ghidrallm.config.*;

/** Base URL + endpoint path handling, custom paths, migration, and the Responses API. */
public class EndpointTest {

	@Before
	public void setUp() {
		HttpTransport.clearClientCache();
	}

	@Test
	public void joinHandlesSlashesQueriesAndAbsoluteOverride() {
		assertEquals("https://h/v1/chat/completions",
			ProviderConfig.join("https://h/", "/v1/chat/completions"));
		assertEquals("https://h/v1/x", ProviderConfig.join("https://h", "v1/x"));
		assertEquals("https://h/api?x=1", ProviderConfig.join("https://h/api", "?x=1"));
		assertEquals("https://other/y", ProviderConfig.join("https://h", "https://other/y"));
		assertEquals("https://h", ProviderConfig.join("https://h///", ""));
	}

	@Test
	public void splitRecognisesKnownPathsAndPrefixes() {
		assertArrayEquals(new String[] { "https://api.llama.com/compat", "/v1/chat/completions" },
			ProviderConfig.split("https://api.llama.com/compat/v1/chat/completions",
				ProviderType.OPENAI_COMPATIBLE));
		assertArrayEquals(new String[] { "https://models.github.ai/inference", "/chat/completions" },
			ProviderConfig.split("https://models.github.ai/inference/chat/completions",
				ProviderType.OPENAI_COMPATIBLE));
		// unknown path: split after host
		assertArrayEquals(new String[] { "https://gw.corp:8443", "/llm/invoke?team=re" },
			ProviderConfig.split("https://gw.corp:8443/llm/invoke?team=re",
				ProviderType.OPENAI_COMPATIBLE));
		assertArrayEquals(new String[] { "http://localhost:11434", "/api/chat" },
			ProviderConfig.split("http://localhost:11434/api/chat", ProviderType.OLLAMA));
	}

	@Test
	public void legacySingleUrlConfigMigrates() {
		String legacy = "{\"name\":\"Old Claude\",\"type\":\"ANTHROPIC\",\"model\":\"m\"," +
			"\"endpoint\":\"https://proxy.corp/anthropic/v1/messages\"," +
			"\"modelsEndpoint\":\"https://proxy.corp/anthropic/v1/models\"}";
		ProviderConfig p = ConfigStore.gson().fromJson(legacy, ProviderConfig.class);
		assertEquals("https://proxy.corp/anthropic/v1/messages", p.getChatUrl()); // before migration
		p.clampValues();
		assertEquals("https://proxy.corp", p.baseUrl);
		assertEquals("/anthropic/v1/messages", p.chatPath);
		assertEquals("https://proxy.corp/anthropic/v1/messages", p.getChatUrl());
		assertEquals("https://proxy.corp/anthropic/v1/models", p.getModelsUrl());

		ProviderConfig gh = ConfigStore.gson().fromJson("{\"type\":\"OPENAI_COMPATIBLE\"," +
			"\"endpoint\":\"https://models.github.ai/inference/chat/completions\"}",
			ProviderConfig.class);
		gh.clampValues();
		assertEquals("https://models.github.ai/catalog/models", gh.getModelsUrl());

		ProviderConfig fresh = ConfigStore.gson().fromJson("{\"type\":\"GEMINI\"}",
			ProviderConfig.class);
		fresh.clampValues();
		assertEquals(ProviderType.GEMINI.getDefaultEndpoint(), fresh.getChatUrl());
	}

	@Test
	public void modelsUrlDefaultsFollowBaseUrl() {
		ProviderConfig p = new ProviderConfig("x", ProviderType.OPENAI_COMPATIBLE, "m");
		p.baseUrl = "https://openrouter.ai/api";
		p.chatPath = "/v1/chat/completions";
		assertEquals("https://openrouter.ai/api/v1/models", p.getModelsUrl());
		p.modelsPath = "/v1/models?type=chat";
		assertEquals("https://openrouter.ai/api/v1/models?type=chat", p.getModelsUrl());
		p.modelsPath = "https://catalog.example/list";
		assertEquals("https://catalog.example/list", p.getModelsUrl());
	}

	@Test
	public void customPathWithModelPlaceholderAndQuery() throws Exception {
		try (MockServer s = new MockServer()) {
			s.then(200, "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}");
			ProviderConfig p = new ProviderConfig("azure", ProviderType.OPENAI_COMPATIBLE,
				"my gpt deploy");
			p.baseUrl = s.url("/");
			p.chatPath = "/openai/deployments/{model}/chat/completions?api-version=2024-10-21";
			p.apiKey = "";
			p.extraHeaders = "api-key: secret";
			assertEquals("ok",
				LLMClient.chat(p, LLMRequest.single("", "hi", false), null, null).getText());
			MockServer.Seen req = s.seen.get(0);
			assertEquals("/openai/deployments/my gpt deploy/chat/completions", req.path());
			assertEquals("api-version=2024-10-21", req.query());
			assertEquals("secret", req.header("api-key"));
		}
	}

	@Test
	public void totallyCustomGatewayPath() throws Exception {
		try (MockServer s = new MockServer()) {
			s.then(200, "{\"model\":\"x\",\"message\":{\"content\":\"hey\"}}");
			ProviderConfig p = new ProviderConfig("gw", ProviderType.OLLAMA, "llama3");
			p.baseUrl = s.url("/teams/re");
			p.chatPath = "llm/v2/converse";
			LLMClient.chat(p, LLMRequest.single("", "hi", false), null, null);
			assertEquals("/teams/re/llm/v2/converse", s.seen.get(0).path());
		}
	}

	@Test
	public void responsesApiFormat() throws Exception {
		try (MockServer s = new MockServer()) {
			s.then(200, "{\"id\":\"resp_1\",\"model\":\"gpt-x\",\"status\":\"completed\"," +
				"\"output\":[{\"type\":\"reasoning\",\"summary\":[]}," +
				"{\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\"," +
				"\"text\":\"{\\\"function_name\\\":\\\"f\\\"}\"}]}]," +
				"\"usage\":{\"input_tokens\":12,\"output_tokens\":5}}");
			ProviderConfig p = new ProviderConfig("resp", ProviderType.OPENAI_RESPONSES, "gpt-x");
			p.baseUrl = s.url("");
			p.apiKey = "k";
			p.jsonMode = true;
			p.maxOutputTokens = 4000;
			LLMResponse r = LLMClient.chat(p, new LLMRequest("SYS",
				java.util.List.of(ChatMessage.user("q1"), ChatMessage.assistant("a1"),
					ChatMessage.user("q2")), true), null, null);
			assertEquals("{\"function_name\":\"f\"}", r.getText());
			assertEquals(12, r.getInputTokens());
			MockServer.Seen req = s.seen.get(0);
			assertEquals("/v1/responses", req.path());
			assertEquals("Bearer k", req.header("Authorization"));
			JsonObject b = JsonParser.parseString(req.body()).getAsJsonObject();
			assertEquals("SYS", b.get("instructions").getAsString());
			assertEquals(3, b.getAsJsonArray("input").size());
			assertEquals(4000, b.get("max_output_tokens").getAsInt());
			assertEquals("json_object",
				b.getAsJsonObject("text").getAsJsonObject("format").get("type").getAsString());
			assertFalse(b.get("store").getAsBoolean());
		}
	}

	@Test
	public void responsesApiIncompleteIsReported() throws Exception {
		try (MockServer s = new MockServer()) {
			s.then(200, "{\"status\":\"incomplete\",\"incomplete_details\":" +
				"{\"reason\":\"max_output_tokens\"},\"output\":[]}");
			ProviderConfig p = new ProviderConfig("resp", ProviderType.OPENAI_RESPONSES, "m");
			p.baseUrl = s.url("");
			try {
				LLMClient.chat(p, LLMRequest.single("", "q", false), null, null);
				fail();
			}
			catch (LLMException e) {
				assertTrue(e.getMessage().contains("max_output_tokens"));
			}
		}
	}

	@Test
	public void invalidBaseUrlGivesClearError() {
		ProviderConfig p = new ProviderConfig("bad", ProviderType.OPENAI_COMPATIBLE, "m");
		p.baseUrl = "api.example.com";
		try {
			LLMClient.chat(p, LLMRequest.single("", "q", false), null, null);
			fail();
		}
		catch (LLMException e) {
			assertTrue(e.getMessage(), e.getMessage().contains("Base URL"));
		}
	}

	@Test
	public void everyTypeHasPresets() {
		for (ProviderType t : ProviderType.values()) {
			assertFalse(t.getChatPathPresets().isEmpty());
			assertFalse(t.getModelsPathPresets().isEmpty());
			assertTrue(ProviderConfig.isAbsolute(t.getDefaultEndpoint()));
		}
	}
}
