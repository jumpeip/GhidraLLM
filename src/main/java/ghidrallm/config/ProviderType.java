package ghidrallm.config;

import java.util.List;

/**
 * Wire protocol spoken by a provider. The URL is configured separately as a base URL plus an
 * endpoint path, so any vendor that speaks one of these formats can be used whatever its paths
 * look like (Meta Llama API, Azure, OpenRouter, Groq, LM Studio, vLLM, corporate gateways...).
 */
public enum ProviderType {
	ANTHROPIC("Anthropic (Claude Messages API)",
		"https://api.anthropic.com",
		List.of("/v1/messages", "/messages", "/anthropic/v1/messages"),
		List.of("/v1/models", "/models")),

	GEMINI("Google Gemini (generateContent)",
		"https://generativelanguage.googleapis.com",
		List.of("/v1beta/models/{model}:generateContent", "/v1/models/{model}:generateContent"),
		List.of("/v1beta/models?pageSize=1000", "/v1/models?pageSize=1000")),

	OPENAI_COMPATIBLE("OpenAI-compatible (Chat Completions)",
		"https://api.openai.com",
		List.of("/v1/chat/completions", "/chat/completions", "/openai/v1/chat/completions",
			"/api/v1/chat/completions",
			"/openai/deployments/{model}/chat/completions?api-version=2024-10-21"),
		List.of("/v1/models", "/models", "/openai/v1/models", "/api/v1/models",
			"/openai/models?api-version=2024-10-21")),

	OPENAI_RESPONSES("OpenAI Responses API (/v1/responses)",
		"https://api.openai.com",
		List.of("/v1/responses", "/responses", "/openai/v1/responses",
			"/openai/responses?api-version=2025-04-01-preview"),
		List.of("/v1/models", "/models", "/openai/v1/models")),

	OLLAMA("Ollama (native /api/chat)",
		"http://localhost:11434",
		List.of("/api/chat"),
		List.of("/api/tags"));

	private final String displayName;
	private final String defaultBaseUrl;
	private final List<String> chatPathPresets;
	private final List<String> modelsPathPresets;

	ProviderType(String displayName, String defaultBaseUrl, List<String> chatPaths,
			List<String> modelsPaths) {
		this.displayName = displayName;
		this.defaultBaseUrl = defaultBaseUrl;
		this.chatPathPresets = chatPaths;
		this.modelsPathPresets = modelsPaths;
	}

	public String getDefaultBaseUrl() {
		return defaultBaseUrl;
	}

	public String getDefaultChatPath() {
		return chatPathPresets.get(0);
	}

	public String getDefaultModelsPath() {
		return modelsPathPresets.get(0);
	}

	/** Common endpoint paths for this protocol, most common first. */
	public List<String> getChatPathPresets() {
		return chatPathPresets;
	}

	public List<String> getModelsPathPresets() {
		return modelsPathPresets;
	}

	/** Full default chat URL (base + default path). */
	public String getDefaultEndpoint() {
		return defaultBaseUrl + getDefaultChatPath();
	}

	@Override
	public String toString() {
		return displayName;
	}
}
