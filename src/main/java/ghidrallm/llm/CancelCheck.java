package ghidrallm.llm;

/** Lets long-running HTTP work notice that the user pressed Cancel. */
@FunctionalInterface
public interface CancelCheck {

	CancelCheck NEVER = () -> false;

	boolean isCancelled();
}
