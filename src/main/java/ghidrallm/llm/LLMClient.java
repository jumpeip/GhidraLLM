package ghidrallm.llm;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;

import com.google.gson.*;

import ghidrallm.config.ConfigStore;
import ghidrallm.config.ProviderConfig;
import ghidrallm.config.ProviderType;

/** Speaks each provider's wire format. Stateless. */
public final class LLMClient {

	private LLMClient() {
	}

	// ------------------------------------------------------------------ chat

	public static LLMResponse chat(ProviderConfig cfg, LLMRequest req, CancelCheck cancel,
			Consumer<String> log) throws LLMException {
		validate(cfg);
		return switch (cfg.type) {
			case ANTHROPIC -> anthropic(cfg, req, cancel, log);
			case GEMINI -> gemini(cfg, req, cancel, log);
			case OPENAI_COMPATIBLE -> openAi(cfg, req, cancel, log);
			case OPENAI_RESPONSES -> openAiResponses(cfg, req, cancel, log);
			case OLLAMA -> ollama(cfg, req, cancel, log);
		};
	}

	private static void validate(ProviderConfig cfg) throws LLMException {
		if (cfg == null) {
			throw new LLMException("No LLM provider is selected.");
		}
		String url = cfg.getChatUrl();
		if (!ProviderConfig.isAbsolute(url)) {
			throw new LLMException("Provider '" + cfg.name + "' has no valid endpoint URL ('" +
				url + "'). Set Base URL to http(s)://host[/prefix].");
		}
		if (cfg.model == null || cfg.model.isBlank()) {
			throw new LLMException("Provider '" + cfg.name + "' has no model selected.");
		}
		// Ollama and self-hosted OpenAI-compatible servers often need no key
		boolean keyless =
			cfg.type == ProviderType.OLLAMA || cfg.type == ProviderType.OPENAI_COMPATIBLE ||
				cfg.type == ProviderType.OPENAI_RESPONSES;
		if (!keyless && cfg.getResolvedApiKey().isBlank() && !cfg.useClientCertificate &&
			!hasAuthHeader(cfg)) {
			throw new LLMException("Provider '" + cfg.name + "' has no API key" + envHint(cfg) + ".");
		}
	}

	private static String envHint(ProviderConfig cfg) {
		String k = cfg.apiKey == null ? "" : cfg.apiKey.trim();
		return k.toLowerCase().startsWith("env:")
				? " (environment variable " + k.substring(4).trim() + " is not set)"
				: "";
	}

	private static boolean hasAuthHeader(ProviderConfig cfg) {
		for (String k : cfg.getExtraHeaderMap().keySet()) {
			String l = k.toLowerCase();
			if (l.equals("authorization") || l.equals("x-api-key") || l.equals("api-key") ||
				l.equals("x-goog-api-key")) {
				return true;
			}
		}
		return false;
	}

	private static Map<String, String> jsonHeaders() {
		Map<String, String> h = new LinkedHashMap<>();
		h.put("Content-Type", "application/json");
		h.put("Accept", "application/json");
		return h;
	}

	private static Map<String, String> withExtra(ProviderConfig cfg, Map<String, String> h) {
		h.putAll(cfg.getExtraHeaderMap()); // user-supplied headers win
		return h;
	}

	private static String bearer(ProviderConfig cfg) {
		String k = cfg.getResolvedApiKey();
		return k.isBlank() ? null : "Bearer " + k.trim();
	}

	private static void applyExtraBody(ProviderConfig cfg, JsonObject body) throws LLMException {
		if (cfg.extraBodyJson == null || cfg.extraBodyJson.isBlank()) {
			return;
		}
		JsonElement extra;
		try {
			extra = JsonParser.parseString(cfg.extraBodyJson);
		}
		catch (JsonParseException e) {
			throw new LLMException("Extra body JSON is invalid: " + e.getMessage());
		}
		if (!extra.isJsonObject()) {
			throw new LLMException("Extra body JSON must be a JSON object");
		}
		deepMerge(body, extra.getAsJsonObject());
	}

	/** Merge src into target; a JSON null in src removes the key from target. */
	static void deepMerge(JsonObject target, JsonObject src) {
		for (Map.Entry<String, JsonElement> e : src.entrySet()) {
			JsonElement cur = target.get(e.getKey());
			if (e.getValue().isJsonNull()) {
				target.remove(e.getKey());
			}
			else if (cur != null && cur.isJsonObject() && e.getValue().isJsonObject()) {
				deepMerge(cur.getAsJsonObject(), e.getValue().getAsJsonObject());
			}
			else {
				target.add(e.getKey(), e.getValue().deepCopy());
			}
		}
	}

	private static JsonElement parseAny(String body, ProviderConfig cfg) throws LLMException {
		try {
			return JsonParser.parseString(body);
		}
		catch (JsonParseException e) {
			throw new LLMException("Non-JSON response from " + cfg.name + ": " + snip(body));
		}
	}

	private static JsonObject parseJson(String body, ProviderConfig cfg) throws LLMException {
		JsonElement el = parseAny(body, cfg);
		if (!el.isJsonObject()) {
			throw new LLMException("Unexpected response from " + cfg.name + ": " + snip(body));
		}
		return el.getAsJsonObject();
	}

	private static String snip(String s) {
		if (s == null) {
			return "";
		}
		return s.length() > 400 ? s.substring(0, 400) + "..." : s;
	}

	private static String str(JsonObject o, String k) {
		JsonElement e = o == null ? null : o.get(k);
		return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
	}

	private static long num(JsonObject o, String k) {
		JsonElement e = o == null ? null : o.get(k);
		try {
			return e != null && e.isJsonPrimitive() ? e.getAsLong() : 0;
		}
		catch (NumberFormatException ex) {
			return 0;
		}
	}

	private static JsonObject obj(JsonObject o, String k) {
		JsonElement e = o == null ? null : o.get(k);
		return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
	}

	private static JsonArray roleMessages(LLMRequest req, boolean includeSystem) {
		JsonArray msgs = new JsonArray();
		if (includeSystem && !req.getSystemPrompt().isBlank()) {
			JsonObject sm = new JsonObject();
			sm.addProperty("role", "system");
			sm.addProperty("content", req.getSystemPrompt());
			msgs.add(sm);
		}
		for (ChatMessage m : req.getMessages()) {
			JsonObject jm = new JsonObject();
			jm.addProperty("role", m.getRole() == ChatMessage.Role.USER ? "user" : "assistant");
			jm.addProperty("content", m.getContent());
			msgs.add(jm);
		}
		return msgs;
	}

	// ------------------------------------------------------------------ Anthropic

	private static LLMResponse anthropic(ProviderConfig cfg, LLMRequest req, CancelCheck cancel,
			Consumer<String> log) throws LLMException {
		JsonObject body = new JsonObject();
		body.addProperty("model", cfg.model.trim());
		body.addProperty("max_tokens", cfg.maxOutputTokens > 0 ? cfg.maxOutputTokens : 8192);
		if (!req.getSystemPrompt().isBlank()) {
			body.addProperty("system", req.getSystemPrompt());
		}
		body.add("messages", roleMessages(
			new LLMRequest("", mergeConsecutive(req.getMessages()), req.isExpectJson()), false));
		if (cfg.useTemperature) {
			body.addProperty("temperature", Math.min(1.0, cfg.temperature));
		}
		applyExtraBody(cfg, body);

		Map<String, String> h = jsonHeaders();
		String key = cfg.getResolvedApiKey();
		if (!key.isBlank()) {
			h.put("x-api-key", key.trim());
		}
		h.put("anthropic-version", "2023-06-01");
		withExtra(cfg, h);

		HttpTransport.HttpResult r =
			HttpTransport.send(cfg, "POST", chatUrl(cfg), h, body.toString(), cancel, log);
		JsonObject o = parseJson(r.body(), cfg);
		StringBuilder text = new StringBuilder();
		JsonElement content = o.get("content");
		if (content != null && content.isJsonArray()) {
			for (JsonElement b : content.getAsJsonArray()) {
				if (b.isJsonObject() && "text".equals(str(b.getAsJsonObject(), "type"))) {
					text.append(str(b.getAsJsonObject(), "text"));
				}
			}
		}
		String stop = str(o, "stop_reason");
		if (text.length() == 0) {
			throw new LLMException("Empty response from " + cfg.name +
				(stop != null ? " (stop_reason=" + stop + ")" : ""));
		}
		JsonObject usage = obj(o, "usage");
		return new LLMResponse(text.toString(), str(o, "model"), num(usage, "input_tokens"),
			num(usage, "output_tokens"), stop, r.attempts());
	}

	// ------------------------------------------------------------------ Gemini

	/** The chat URL with {model} substituted (URL-encoded, ':' kept for Gemini paths). */
	static String chatUrl(ProviderConfig cfg) {
		return substituteModel(cfg.getChatUrl(), cfg);
	}

	public static String substituteModel(String url, ProviderConfig cfg) {
		if (!url.contains("{model}")) {
			return url;
		}
		String model = cfg.model == null ? "" : cfg.model.trim();
		if (cfg.type == ProviderType.GEMINI && model.startsWith("models/")) {
			model = model.substring("models/".length());
		}
		String enc = URLEncoder.encode(model, StandardCharsets.UTF_8)
				.replace("%3A", ":")
				.replace("+", "%20");
		return url.replace("{model}", enc);
	}

	private static LLMResponse gemini(ProviderConfig cfg, LLMRequest req, CancelCheck cancel,
			Consumer<String> log) throws LLMException {
		JsonObject body = new JsonObject();
		if (!req.getSystemPrompt().isBlank()) {
			JsonObject si = new JsonObject();
			si.add("parts", textParts(req.getSystemPrompt()));
			body.add("systemInstruction", si);
		}
		JsonArray contents = new JsonArray();
		for (ChatMessage m : mergeConsecutive(req.getMessages())) {
			JsonObject c = new JsonObject();
			c.addProperty("role", m.getRole() == ChatMessage.Role.USER ? "user" : "model");
			c.add("parts", textParts(m.getContent()));
			contents.add(c);
		}
		body.add("contents", contents);
		JsonObject gen = new JsonObject();
		if (cfg.maxOutputTokens > 0) {
			gen.addProperty("maxOutputTokens", cfg.maxOutputTokens);
		}
		if (cfg.useTemperature) {
			gen.addProperty("temperature", cfg.temperature);
		}
		if (req.isExpectJson() && cfg.jsonMode) {
			gen.addProperty("responseMimeType", "application/json");
		}
		body.add("generationConfig", gen);
		applyExtraBody(cfg, body);

		Map<String, String> h = jsonHeaders();
		String key = cfg.getResolvedApiKey();
		if (!key.isBlank()) {
			h.put("x-goog-api-key", key.trim());
		}
		withExtra(cfg, h);

		HttpTransport.HttpResult r =
			HttpTransport.send(cfg, "POST", chatUrl(cfg), h, body.toString(), cancel, log);
		JsonObject o = parseJson(r.body(), cfg);
		JsonElement cands = o.get("candidates");
		if (cands == null || !cands.isJsonArray() || cands.getAsJsonArray().isEmpty()) {
			JsonObject fb = obj(o, "promptFeedback");
			String reason = fb != null ? str(fb, "blockReason") : null;
			throw new LLMException("Gemini returned no candidates" +
				(reason != null ? " (blocked: " + reason + ")" : ""));
		}
		JsonObject cand = cands.getAsJsonArray().get(0).getAsJsonObject();
		StringBuilder text = new StringBuilder();
		JsonObject content = obj(cand, "content");
		if (content != null && content.has("parts") && content.get("parts").isJsonArray()) {
			for (JsonElement p : content.getAsJsonArray("parts")) {
				JsonObject po = p.getAsJsonObject();
				if (po.has("thought") && po.get("thought").getAsBoolean()) {
					continue;
				}
				String t = str(po, "text");
				if (t != null) {
					text.append(t);
				}
			}
		}
		String finish = str(cand, "finishReason");
		if (text.length() == 0) {
			throw new LLMException("Empty response from Gemini" + (finish != null
					? " (finishReason=" + finish +
						("MAX_TOKENS".equals(finish) ? " - raise Max output tokens" : "") + ")"
					: ""));
		}
		JsonObject usage = obj(o, "usageMetadata");
		return new LLMResponse(text.toString(), str(o, "modelVersion"),
			num(usage, "promptTokenCount"), num(usage, "candidatesTokenCount"), finish,
			r.attempts());
	}

	private static JsonArray textParts(String text) {
		JsonArray parts = new JsonArray();
		JsonObject p = new JsonObject();
		p.addProperty("text", text);
		parts.add(p);
		return parts;
	}

	// ------------------------------------------------------------------ OpenAI compatible

	private static LLMResponse openAi(ProviderConfig cfg, LLMRequest req, CancelCheck cancel,
			Consumer<String> log) throws LLMException {
		JsonObject body = new JsonObject();
		body.addProperty("model", cfg.model.trim());
		body.add("messages", roleMessages(req, true));
		if (cfg.maxOutputTokens > 0) {
			body.addProperty("max_tokens", cfg.maxOutputTokens);
		}
		if (cfg.useTemperature) {
			body.addProperty("temperature", cfg.temperature);
		}
		if (req.isExpectJson() && cfg.jsonMode) {
			JsonObject rf = new JsonObject();
			rf.addProperty("type", "json_object");
			body.add("response_format", rf);
		}
		body.addProperty("stream", false);
		applyExtraBody(cfg, body);

		Map<String, String> h = jsonHeaders();
		String auth = bearer(cfg);
		if (auth != null) {
			h.put("Authorization", auth);
		}
		withExtra(cfg, h);

		HttpTransport.HttpResult r =
			HttpTransport.send(cfg, "POST", chatUrl(cfg), h, body.toString(), cancel, log);
		JsonObject o = parseJson(r.body(), cfg);
		JsonElement choices = o.get("choices");
		if (choices == null || !choices.isJsonArray() || choices.getAsJsonArray().isEmpty()) {
			throw new LLMException(
				"No choices in response from " + cfg.name + ": " + snip(r.body()));
		}
		JsonObject c0 = choices.getAsJsonArray().get(0).getAsJsonObject();
		JsonObject msg = obj(c0, "message");
		String text = null;
		if (msg != null) {
			JsonElement content = msg.get("content");
			if (content != null && content.isJsonPrimitive()) {
				text = content.getAsString();
			}
			else if (content != null && content.isJsonArray()) {
				StringBuilder sb = new StringBuilder();
				for (JsonElement p : content.getAsJsonArray()) {
					if (p.isJsonObject() && p.getAsJsonObject().has("text")) {
						sb.append(p.getAsJsonObject().get("text").getAsString());
					}
				}
				text = sb.toString();
			}
		}
		String finish = str(c0, "finish_reason");
		if (text == null || text.isEmpty()) {
			throw new LLMException("Empty response from " + cfg.name +
				(finish != null ? " (finish_reason=" + finish + ")" : ""));
		}
		JsonObject usage = obj(o, "usage");
		return new LLMResponse(text, str(o, "model"), num(usage, "prompt_tokens"),
			num(usage, "completion_tokens"), finish, r.attempts());
	}

	// ------------------------------------------------------------------ OpenAI Responses API

	private static LLMResponse openAiResponses(ProviderConfig cfg, LLMRequest req,
			CancelCheck cancel, Consumer<String> log) throws LLMException {
		JsonObject body = new JsonObject();
		body.addProperty("model", cfg.model.trim());
		if (!req.getSystemPrompt().isBlank()) {
			body.addProperty("instructions", req.getSystemPrompt());
		}
		body.add("input", roleMessages(req, false));
		if (cfg.maxOutputTokens > 0) {
			body.addProperty("max_output_tokens", cfg.maxOutputTokens);
		}
		if (cfg.useTemperature) {
			body.addProperty("temperature", cfg.temperature);
		}
		if (req.isExpectJson() && cfg.jsonMode) {
			JsonObject fmt = new JsonObject();
			fmt.addProperty("type", "json_object");
			JsonObject text = new JsonObject();
			text.add("format", fmt);
			body.add("text", text);
		}
		body.addProperty("store", false);
		applyExtraBody(cfg, body);

		Map<String, String> h = jsonHeaders();
		String auth = bearer(cfg);
		if (auth != null) {
			h.put("Authorization", auth);
		}
		withExtra(cfg, h);

		HttpTransport.HttpResult r =
			HttpTransport.send(cfg, "POST", chatUrl(cfg), h, body.toString(), cancel, log);
		JsonObject o = parseJson(r.body(), cfg);
		StringBuilder text = new StringBuilder();
		String direct = str(o, "output_text");
		if (direct != null) {
			text.append(direct);
		}
		JsonElement output = o.get("output");
		if (text.length() == 0 && output != null && output.isJsonArray()) {
			for (JsonElement item : output.getAsJsonArray()) {
				if (!item.isJsonObject() ||
					!"message".equals(str(item.getAsJsonObject(), "type"))) {
					continue; // skip reasoning / tool items
				}
				JsonElement content = item.getAsJsonObject().get("content");
				if (content == null || !content.isJsonArray()) {
					continue;
				}
				for (JsonElement part : content.getAsJsonArray()) {
					if (part.isJsonObject() &&
						"output_text".equals(str(part.getAsJsonObject(), "type"))) {
						text.append(str(part.getAsJsonObject(), "text"));
					}
				}
			}
		}
		String status = str(o, "status");
		JsonObject incomplete = obj(o, "incomplete_details");
		String reason = incomplete != null ? str(incomplete, "reason") : null;
		if (text.length() == 0) {
			throw new LLMException("Empty response from " + cfg.name + " (status=" + status +
				(reason != null ? ", reason=" + reason : "") + ")");
		}
		JsonObject usage = obj(o, "usage");
		return new LLMResponse(text.toString(), str(o, "model"), num(usage, "input_tokens"),
			num(usage, "output_tokens"), reason != null ? reason : status, r.attempts());
	}

	// ------------------------------------------------------------------ Ollama

	private static LLMResponse ollama(ProviderConfig cfg, LLMRequest req, CancelCheck cancel,
			Consumer<String> log) throws LLMException {
		JsonObject body = new JsonObject();
		body.addProperty("model", cfg.model.trim());
		body.add("messages", roleMessages(req, true));
		body.addProperty("stream", false);
		JsonObject opts = new JsonObject();
		if (cfg.useTemperature) {
			opts.addProperty("temperature", cfg.temperature);
		}
		if (cfg.maxOutputTokens > 0) {
			opts.addProperty("num_predict", cfg.maxOutputTokens);
		}
		if (cfg.sendContextWindowToServer) {
			opts.addProperty("num_ctx", cfg.contextWindowTokens);
		}
		if (opts.size() > 0) {
			body.add("options", opts);
		}
		if (req.isExpectJson() && cfg.jsonMode) {
			body.addProperty("format", "json");
		}
		applyExtraBody(cfg, body);

		Map<String, String> h = jsonHeaders();
		String auth = bearer(cfg);
		if (auth != null) {
			h.put("Authorization", auth);
		}
		withExtra(cfg, h);

		HttpTransport.HttpResult r =
			HttpTransport.send(cfg, "POST", chatUrl(cfg), h, body.toString(), cancel, log);
		JsonObject o = parseJson(r.body(), cfg);
		if (o.has("error")) {
			throw new LLMException("Ollama error: " + o.get("error").getAsString());
		}
		JsonObject msg = obj(o, "message");
		String text = msg != null ? str(msg, "content") : null;
		if (text == null || text.isEmpty()) {
			throw new LLMException("Empty response from Ollama (" + str(o, "done_reason") + ")");
		}
		return new LLMResponse(text, str(o, "model"), num(o, "prompt_eval_count"),
			num(o, "eval_count"), str(o, "done_reason"), r.attempts());
	}

	// ------------------------------------------------------------------ model listing

	public static List<String> listModels(ProviderConfig cfg, CancelCheck cancel,
			Consumer<String> log) throws LLMException {
		// don't sit through thousands of retries just to populate a combo box
		ProviderConfig probe = ConfigStore.copyOf(cfg);
		probe.maxRetries = Math.min(probe.maxRetries, 2);
		probe.httpTimeoutSeconds = Math.min(probe.httpTimeoutSeconds, 60);

		String url = substituteModel(cfg.getModelsUrl(), cfg);
		Map<String, String> h = new LinkedHashMap<>();
		h.put("Accept", "application/json");
		String key = cfg.getResolvedApiKey().trim();
		switch (cfg.type) {
			case ANTHROPIC -> {
				if (!key.isEmpty()) {
					h.put("x-api-key", key);
				}
				h.put("anthropic-version", "2023-06-01");
				if (!url.contains("limit=")) {
					url += (url.contains("?") ? "&" : "?") + "limit=1000";
				}
			}
			case GEMINI -> {
				if (!key.isEmpty()) {
					h.put("x-goog-api-key", key);
				}
			}
			default -> {
				if (!key.isEmpty()) {
					h.put("Authorization", "Bearer " + key);
				}
			}
		}
		withExtra(cfg, h);
		HttpTransport.HttpResult r = HttpTransport.send(probe, "GET", url, h, null, cancel, log);
		JsonElement root = parseAny(r.body(), cfg);
		TreeSet<String> out = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
		if (root.isJsonArray()) {
			collectIds(root.getAsJsonArray(), null, out); // e.g. GitHub Models catalog
		}
		else if (root.isJsonObject()) {
			JsonObject o = root.getAsJsonObject();
			if (cfg.type == ProviderType.GEMINI) {
				JsonElement models = o.get("models");
				if (models != null && models.isJsonArray()) {
					for (JsonElement m : models.getAsJsonArray()) {
						JsonObject mo = m.getAsJsonObject();
						JsonElement methods = mo.get("supportedGenerationMethods");
						if (methods != null && !methods.toString().contains("generateContent")) {
							continue;
						}
						String n = str(mo, "name");
						if (n != null) {
							out.add(n.startsWith("models/") ? n.substring(7) : n);
						}
					}
				}
			}
			else {
				for (String arrayKey : new String[] { "data", "models" }) {
					JsonElement arr = o.get(arrayKey);
					if (arr != null && arr.isJsonArray()) {
						collectIds(arr.getAsJsonArray(),
							cfg.type == ProviderType.OLLAMA ? "name" : "id", out);
					}
				}
			}
		}
		return new ArrayList<>(out);
	}

	private static void collectIds(JsonArray arr, String preferredKey, Set<String> out) {
		for (JsonElement e : arr) {
			if (e.isJsonPrimitive()) {
				out.add(e.getAsString());
			}
			else if (e.isJsonObject()) {
				JsonObject eo = e.getAsJsonObject();
				String id = preferredKey != null ? str(eo, preferredKey) : null;
				for (String k : new String[] { "id", "name", "model" }) {
					if (id == null) {
						id = str(eo, k);
					}
				}
				if (id != null) {
					out.add(id);
				}
			}
		}
	}

	/** Anthropic and Gemini require alternating roles starting with the user. */
	static List<ChatMessage> mergeConsecutive(List<ChatMessage> in) {
		List<ChatMessage> out = new ArrayList<>();
		for (ChatMessage m : in) {
			if (!out.isEmpty() && out.get(out.size() - 1).getRole() == m.getRole()) {
				ChatMessage prev = out.remove(out.size() - 1);
				out.add(new ChatMessage(m.getRole(), prev.getContent() + "\n\n" + m.getContent()));
			}
			else {
				out.add(m);
			}
		}
		while (!out.isEmpty() && out.get(0).getRole() != ChatMessage.Role.USER) {
			out.remove(0);
		}
		return out;
	}
}
