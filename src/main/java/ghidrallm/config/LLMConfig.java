package ghidrallm.config;

import java.util.ArrayList;
import java.util.List;

/** Root of the persisted configuration. */
public class LLMConfig {

	public int configVersion = 1;
	public List<ProviderConfig> providers = new ArrayList<>();
	public String activeProviderId = "";
	public AnalysisSettings analysis = new AnalysisSettings();

	public static LLMConfig createDefault() {
		LLMConfig c = new LLMConfig();
		c.providers.addAll(createDefaultProviders());
		c.activeProviderId = c.providers.get(0).id;
		return c;
	}

	public static List<ProviderConfig> createDefaultProviders() {
		List<ProviderConfig> list = new ArrayList<>();

		ProviderConfig claude =
			new ProviderConfig("Claude (Anthropic)", ProviderType.ANTHROPIC, "claude-sonnet-5-5");
		claude.apiKey = "env:ANTHROPIC_API_KEY";
		claude.maxOutputTokens = 8192;
		list.add(claude);

		ProviderConfig gemini =
			new ProviderConfig("Gemini (Google)", ProviderType.GEMINI, "gemini-2.5-pro");
		gemini.apiKey = "env:GEMINI_API_KEY";
		gemini.jsonMode = true;
		gemini.maxOutputTokens = 16384; // thinking models spend output tokens on reasoning
		list.add(gemini);

		ProviderConfig meta = new ProviderConfig("Meta (Llama API)", ProviderType.OPENAI_COMPATIBLE,
			"Llama-4-Maverick-17B-128E-Instruct-FP8");
		meta.baseUrl = "https://api.llama.com/compat";
		meta.chatPath = "/v1/chat/completions";
		meta.apiKey = "env:LLAMA_API_KEY";
		list.add(meta);

		// GitHub's official model inference API (OpenAI-compatible). Authenticate with a
		// GitHub personal access token that has the "models:read" permission.
		ProviderConfig copilot = new ProviderConfig("GitHub Copilot (GitHub Models)",
			ProviderType.OPENAI_COMPATIBLE, "openai/gpt-4.1");
		copilot.baseUrl = "https://models.github.ai/inference";
		copilot.chatPath = "/chat/completions";
		copilot.modelsPath = "https://models.github.ai/catalog/models";
		copilot.apiKey = "env:GITHUB_TOKEN";
		list.add(copilot);

		ProviderConfig ollama =
			new ProviderConfig("Ollama (local)", ProviderType.OLLAMA, "qwen2.5-coder:14b");
		ollama.apiKey = "";
		ollama.jsonMode = true;
		ollama.maxRetries = 3;
		list.add(ollama);

		return list;
	}

	public ProviderConfig getActiveProvider() {
		for (ProviderConfig p : providers) {
			if (p.id.equals(activeProviderId)) {
				return p;
			}
		}
		return providers.isEmpty() ? null : providers.get(0);
	}

	public ProviderConfig findById(String id) {
		for (ProviderConfig p : providers) {
			if (p.id.equals(id)) {
				return p;
			}
		}
		return null;
	}

	public void normalize() {
		if (providers == null) {
			providers = new ArrayList<>();
		}
		providers.removeIf(p -> p == null);
		if (providers.isEmpty()) {
			providers.addAll(createDefaultProviders());
		}
		for (ProviderConfig p : providers) {
			p.clampValues();
		}
		if (analysis == null) {
			analysis = new AnalysisSettings();
		}
		analysis.clampValues();
		if (findById(activeProviderId) == null) {
			activeProviderId = providers.get(0).id;
		}
	}
}
