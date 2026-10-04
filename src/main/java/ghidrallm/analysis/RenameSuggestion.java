package ghidrallm.analysis;

import java.util.LinkedHashMap;
import java.util.Map;

/** Parsed LLM answer. */
public class RenameSuggestion {

	public String functionName;
	public String summary;
	public double confidence = -1;
	/** old name -> new name, in the order the model gave them. */
	public final Map<String, String> renames = new LinkedHashMap<>();
	public String rawResponse;
	/** Notes about entries dropped while sanitizing. */
	public final java.util.List<String> warnings = new java.util.ArrayList<>();
}
