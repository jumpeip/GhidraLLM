package ghidrallm.llm;

/** A single turn in a conversation. */
public class ChatMessage {

	public enum Role {
		USER, ASSISTANT
	}

	private final Role role;
	private final String content;

	public ChatMessage(Role role, String content) {
		this.role = role;
		this.content = content == null ? "" : content;
	}

	public static ChatMessage user(String text) {
		return new ChatMessage(Role.USER, text);
	}

	public static ChatMessage assistant(String text) {
		return new ChatMessage(Role.ASSISTANT, text);
	}

	public Role getRole() {
		return role;
	}

	public String getContent() {
		return content;
	}
}
