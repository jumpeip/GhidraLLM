package ghidrallm.llm;

/** Raised for HTTP failures, malformed responses, configuration problems and cancellation. */
public class LLMException extends Exception {

	private final int statusCode;
	private final String responseBody;
	private final boolean cancelled;

	public LLMException(String message) {
		this(message, -1, null, null, false);
	}

	public LLMException(String message, Throwable cause) {
		this(message, -1, null, cause, false);
	}

	public LLMException(String message, int statusCode, String responseBody) {
		this(message, statusCode, responseBody, null, false);
	}

	private LLMException(String message, int statusCode, String responseBody, Throwable cause,
			boolean cancelled) {
		super(message, cause);
		this.statusCode = statusCode;
		this.responseBody = responseBody;
		this.cancelled = cancelled;
	}

	public static LLMException cancelled() {
		return new LLMException("Cancelled", -1, null, null, true);
	}

	public int getStatusCode() {
		return statusCode;
	}

	public String getResponseBody() {
		return responseBody;
	}

	public boolean isCancelled() {
		return cancelled;
	}
}
