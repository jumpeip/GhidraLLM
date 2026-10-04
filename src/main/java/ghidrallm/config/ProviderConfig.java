package ghidrallm.config;

import java.util.*;

/**
 * Everything needed to talk to one LLM endpoint. Serialized to JSON by {@link ConfigStore}.
 * <p>
 * Secret fields (API key, PKI password) may be given literally or as {@code env:VAR_NAME},
 * in which case the value is read from the environment at request time.
 */
public class ProviderConfig {

	public static final int MAX_RETRIES_LIMIT = 20000;
	public static final int DEFAULT_CONTEXT_WINDOW = 1_000_000;
	public static final int DEFAULT_HTTP_TIMEOUT_SECONDS = 180;

	public String id = UUID.randomUUID().toString();
	public String name = "New Provider";
	public ProviderType type = ProviderType.OPENAI_COMPATIBLE;

	/** Scheme + host (+ optional path prefix), e.g. https://api.llama.com/compat */
	public String baseUrl = "";
	/**
	 * Chat endpoint path appended to {@link #baseUrl}, e.g. /v1/chat/completions. May contain a
	 * query string and the {model} placeholder. An absolute http(s) URL here overrides baseUrl.
	 */
	public String chatPath = "";
	/** Path (or absolute URL) used by "Fetch Models". Blank = the type's default path. */
	public String modelsPath = "";

	/** Legacy single-URL field; migrated into baseUrl/chatPath and kept as a read-only mirror. */
	public String endpoint = "";
	/** Legacy field; migrated into modelsPath. */
	public String modelsEndpoint = "";
	public String model = "";
	public String apiKey = "";

	/** Extra HTTP headers, one per line: {@code Header-Name: value}. */
	public String extraHeaders = "";
	/** Optional JSON object merged into every request body (escape hatch for vendor knobs). */
	public String extraBodyJson = "";

	public int maxOutputTokens = 8192;
	public boolean useTemperature = false;
	public double temperature = 0.2;
	public int contextWindowTokens = DEFAULT_CONTEXT_WINDOW;
	/** Ollama only: send the context window as options.num_ctx. */
	public boolean sendContextWindowToServer = false;
	/** Ask the server for a strict JSON response when renaming (where supported). */
	public boolean jsonMode = false;

	public int httpTimeoutSeconds = DEFAULT_HTTP_TIMEOUT_SECONDS;
	public int connectTimeoutSeconds = 30;

	public int maxRetries = 10;
	public int retryInitialDelayMs = 2000;
	public int retryMaxDelayMs = 60000;
	public boolean honorRetryAfter = true;
	/** Comma separated HTTP status codes that trigger a retry. */
	public String retryOnStatus = "429,500,502,503,504,529";
	public boolean retryOnNetworkError = true;

	// ---- PKI ----
	public boolean useClientCertificate = false;
	/** PKCS#12 keystore (.p12 / .pfx). */
	public String clientCertPath = "";
	public String clientCertPassword = "";
	public boolean useCustomCa = false;
	/** CA certificate (.cer/.crt/.pem, DER or PEM, may contain a chain). */
	public String caCertPath = "";
	/** Keep the JVM's default trusted CAs in addition to the custom CA. */
	public boolean trustDefaultCas = true;

	public ProviderConfig() {
	}

	public ProviderConfig(String name, ProviderType type, String model) {
		this.name = name;
		this.type = type;
		this.baseUrl = type.getDefaultBaseUrl();
		this.chatPath = type.getDefaultChatPath();
		this.model = model;
	}

	public Set<Integer> getRetryStatusCodes() {
		Set<Integer> codes = new HashSet<>();
		if (retryOnStatus == null) {
			return codes;
		}
		for (String s : retryOnStatus.split("[,;\\s]+")) {
			try {
				if (!s.isBlank()) {
					codes.add(Integer.parseInt(s.trim()));
				}
			}
			catch (NumberFormatException e) {
				// ignore junk
			}
		}
		return codes;
	}

	public Map<String, String> getExtraHeaderMap() {
		Map<String, String> map = new LinkedHashMap<>();
		if (extraHeaders == null) {
			return map;
		}
		for (String line : extraHeaders.split("\\r?\\n")) {
			int idx = line.indexOf(':');
			if (idx > 0) {
				String k = line.substring(0, idx).trim();
				String v = resolveSecret(line.substring(idx + 1).trim());
				if (!k.isEmpty()) {
					map.put(k, v);
				}
			}
		}
		return map;
	}

	public String getResolvedApiKey() {
		return resolveSecret(apiKey);
	}

	public String getResolvedClientCertPassword() {
		return resolveSecret(clientCertPassword);
	}

	// ------------------------------------------------------------------ URLs

	/** The chat URL with the base and path joined ({model} is substituted by the client). */
	public String getChatUrl() {
		if (isAbsolute(chatPath)) {
			return chatPath.trim();
		}
		if (isBlank(baseUrl)) {
			// legacy config (single URL) or nothing set
			return !isBlank(endpoint) ? endpoint.trim() : type.getDefaultEndpoint();
		}
		return join(baseUrl, chatPath);
	}

	/** URL used by "Fetch Models". */
	public String getModelsUrl() {
		if (isAbsolute(modelsPath)) {
			return modelsPath.trim();
		}
		if (!isBlank(modelsEndpoint) && isAbsolute(modelsEndpoint)) {
			return modelsEndpoint.trim();
		}
		String base = !isBlank(baseUrl) ? baseUrl : split(getChatUrl(), type)[0];
		String path = !isBlank(modelsPath) ? modelsPath : type.getDefaultModelsPath();
		return join(base, path);
	}

	/** Sets base URL and path from one full URL, recognising the type's common paths. */
	public void setUrl(String fullUrl) {
		String[] parts = split(fullUrl == null ? "" : fullUrl.trim(), type);
		baseUrl = parts[0];
		chatPath = parts[1];
		endpoint = getChatUrl();
	}

	public static boolean isAbsolute(String s) {
		if (s == null) {
			return false;
		}
		String t = s.trim().toLowerCase();
		return t.startsWith("http://") || t.startsWith("https://");
	}

	/** Joins base and path with exactly one slash; a leading '?' path is appended as a query. */
	public static String join(String base, String path) {
		String b = base == null ? "" : base.trim();
		String p = path == null ? "" : path.trim();
		if (isAbsolute(p)) {
			return p;
		}
		while (b.endsWith("/")) {
			b = b.substring(0, b.length() - 1);
		}
		if (p.isEmpty()) {
			return b;
		}
		if (p.startsWith("?")) {
			return b + p;
		}
		return b + (p.startsWith("/") ? p : "/" + p);
	}

	/**
	 * Splits a full URL into {base, path}. Prefers the longest of the type's known paths that
	 * the URL ends with (so https://api.llama.com/compat/v1/chat/completions becomes
	 * https://api.llama.com/compat + /v1/chat/completions); otherwise splits after the host.
	 */
	public static String[] split(String url, ProviderType type) {
		String u = url == null ? "" : url.trim();
		if (u.isEmpty()) {
			return new String[] { "", "" };
		}
		String best = null;
		for (ProviderType t : ProviderType.values()) {
			if (type != null && t != type) {
				continue;
			}
			for (String preset : t.getChatPathPresets()) {
				if (u.endsWith(preset) && (best == null || preset.length() > best.length())) {
					best = preset;
				}
			}
		}
		if (best != null && u.length() > best.length()) {
			return new String[] { u.substring(0, u.length() - best.length()), best };
		}
		int schemeEnd = u.indexOf("://");
		int pathStart = schemeEnd < 0 ? -1 : u.indexOf('/', schemeEnd + 3);
		int queryStart = u.indexOf('?', schemeEnd < 0 ? 0 : schemeEnd + 3);
		if (pathStart < 0 || (queryStart >= 0 && queryStart < pathStart)) {
			pathStart = queryStart;
		}
		if (pathStart < 0) {
			return new String[] { u, "" };
		}
		return new String[] { u.substring(0, pathStart), u.substring(pathStart) };
	}

	/** Converts configs saved before base URL / path were separate fields. */
	void migrateLegacyUrls() {
		if (isBlank(baseUrl) && !isAbsolute(chatPath) && !isBlank(endpoint)) {
			String[] parts = split(endpoint, type);
			baseUrl = parts[0];
			chatPath = parts[1];
		}
		if (isBlank(modelsPath) && !isBlank(modelsEndpoint)) {
			modelsPath = modelsEndpoint.trim();
		}
		if (isBlank(modelsPath) && String.valueOf(baseUrl).contains("models.github.ai")) {
			modelsPath = "https://models.github.ai/catalog/models";
		}
		if (isBlank(baseUrl) && isBlank(chatPath) && isBlank(endpoint)) {
			baseUrl = type.getDefaultBaseUrl(); // brand-new provider
			chatPath = type.getDefaultChatPath();
		}
		modelsEndpoint = "";
		if (baseUrl == null) {
			baseUrl = "";
		}
		if (chatPath == null) {
			chatPath = "";
		}
		if (modelsPath == null) {
			modelsPath = "";
		}
		endpoint = getChatUrl(); // human-readable mirror in config.json
	}

	private static boolean isBlank(String s) {
		return s == null || s.isBlank();
	}

	/** Rough token budget for prompt text, in characters (about 3.5 chars per token). */
	public long getPromptCharBudget() {
		long tokens = Math.max(1024L, (long) contextWindowTokens - Math.max(0, maxOutputTokens) - 512L);
		return (long) (tokens * 3.5);
	}

	public static String resolveSecret(String value) {
		if (value == null) {
			return "";
		}
		String v = value.trim();
		if (v.regionMatches(true, 0, "env:", 0, 4)) {
			String env = System.getenv(v.substring(4).trim());
			return env == null ? "" : env;
		}
		return value;
	}

	public void clampValues() {
		maxRetries = clamp(maxRetries, 0, MAX_RETRIES_LIMIT);
		httpTimeoutSeconds = clamp(httpTimeoutSeconds, 1, 86400);
		connectTimeoutSeconds = clamp(connectTimeoutSeconds, 1, 3600);
		retryInitialDelayMs = clamp(retryInitialDelayMs, 0, 3_600_000);
		retryMaxDelayMs = clamp(retryMaxDelayMs, Math.max(0, retryInitialDelayMs), 3_600_000);
		contextWindowTokens = clamp(contextWindowTokens, 1024, 100_000_000);
		maxOutputTokens = clamp(maxOutputTokens, 0, 10_000_000);
		temperature = Math.max(0.0, Math.min(2.0, temperature));
		if (type == null) {
			type = ProviderType.OPENAI_COMPATIBLE;
		}
		if (id == null || id.isBlank()) {
			id = UUID.randomUUID().toString();
		}
		migrateLegacyUrls();
	}

	static int clamp(int v, int lo, int hi) {
		return Math.max(lo, Math.min(hi, v));
	}

	@Override
	public String toString() {
		return name + (model == null || model.isBlank() ? "" : "  [" + model + "]");
	}
}
