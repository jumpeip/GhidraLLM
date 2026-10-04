package ghidrallm.llm;

/** Provider-neutral response. */
public class LLMResponse {

	private final String text;
	private final String model;
	private final long inputTokens;
	private final long outputTokens;
	private final String finishReason;
	private final int attempts;

	public LLMResponse(String text, String model, long inputTokens, long outputTokens,
			String finishReason, int attempts) {
		this.text = text == null ? "" : text;
		this.model = model;
		this.inputTokens = inputTokens;
		this.outputTokens = outputTokens;
		this.finishReason = finishReason;
		this.attempts = attempts;
	}

	public String getText() {
		return text;
	}

	public String getModel() {
		return model;
	}

	public long getInputTokens() {
		return inputTokens;
	}

	public long getOutputTokens() {
		return outputTokens;
	}

	public String getFinishReason() {
		return finishReason;
	}

	public int getAttempts() {
		return attempts;
	}

	public String usageSummary() {
		StringBuilder sb = new StringBuilder();
		if (model != null && !model.isBlank()) {
			sb.append(model);
		}
		if (inputTokens > 0 || outputTokens > 0) {
			sb.append(sb.length() > 0 ? ", " : "").append(inputTokens).append(" in / ")
					.append(outputTokens).append(" out tokens");
		}
		if (attempts > 1) {
			sb.append(sb.length() > 0 ? ", " : "").append(attempts).append(" attempts");
		}
		return sb.toString();
	}
}
