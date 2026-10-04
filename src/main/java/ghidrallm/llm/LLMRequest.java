package ghidrallm.llm;

import java.util.ArrayList;
import java.util.List;

/** Provider-neutral request. */
public class LLMRequest {

	private final String systemPrompt;
	private final List<ChatMessage> messages;
	private final boolean expectJson;

	public LLMRequest(String systemPrompt, List<ChatMessage> messages, boolean expectJson) {
		this.systemPrompt = systemPrompt == null ? "" : systemPrompt;
		this.messages = new ArrayList<>(messages);
		this.expectJson = expectJson;
	}

	public static LLMRequest single(String system, String user, boolean expectJson) {
		List<ChatMessage> m = new ArrayList<>();
		m.add(ChatMessage.user(user));
		return new LLMRequest(system, m, expectJson);
	}

	public String getSystemPrompt() {
		return systemPrompt;
	}

	public List<ChatMessage> getMessages() {
		return messages;
	}

	public boolean isExpectJson() {
		return expectJson;
	}

	public long totalChars() {
		long n = systemPrompt.length();
		for (ChatMessage m : messages) {
			n += m.getContent().length();
		}
		return n;
	}
}
