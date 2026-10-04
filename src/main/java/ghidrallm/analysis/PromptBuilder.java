package ghidrallm.analysis;

import ghidrallm.config.AnalysisSettings;

/** Builds the rename prompt and the chat context block. */
public final class PromptBuilder {

	private PromptBuilder() {
	}

	public static String renameSystemPrompt(AnalysisSettings s) {
		StringBuilder sb = new StringBuilder();
		sb.append("""
			You are an expert reverse engineer working inside Ghidra. You will be given one \
			function (decompiled C and/or disassembly) plus surrounding context: the strings \
			it references, imported APIs it calls, and the code of its callers and callees.

			Your job is to infer what the function does and propose meaningful names for the \
			function, its parameters and its local variables.

			Respond with ONLY one JSON object, no markdown fences and no commentary:
			{
			  "function_name": "descriptive_name",
			  "summary": "One or two sentences describing what the function does.",
			  "confidence": 0.0,
			  "renames": [ { "old": "param_1", "new": "buffer" }, { "old": "local_10", "new": "bytes_read" } ]
			}

			Rules:
			- Names must be valid C identifiers: letters, digits and underscores, not starting \
			with a digit, not a C keyword, at most 64 characters.
			- Use lower_snake_case for functions and variables unless the binary clearly uses \
			another convention (e.g. C++ methods or an SDK naming style).
			- "old" must be copied exactly from the VARIABLES list. Do not invent variables and \
			do not rename variables that are not listed.
			- Prefer names grounded in evidence: API calls, strings, constants, structure \
			offsets, how callers use the return value.
			- Never return default-style names such as FUN_xxxxxxxx, param_N, local_xx, uVarN.
			- If a variable's purpose is unclear, leave it out of "renames".
			- "confidence" is your confidence in function_name from 0.0 to 1.0.
			""");
		if (s.additionalInstructions != null && !s.additionalInstructions.isBlank()) {
			sb.append("\nAdditional instructions from the analyst:\n")
					.append(s.additionalInstructions.strip())
					.append('\n');
		}
		return sb.toString();
	}

	public static String renameUserPrompt(FunctionContext c, AnalysisSettings s) {
		StringBuilder sb = new StringBuilder();
		appendFunction(sb, c, s, true);
		sb.append("\nReturn the JSON object now.\n");
		return sb.toString();
	}

	/** Context block used by the chat window when "include current function" is ticked. */
	public static String chatContext(FunctionContext c, AnalysisSettings s) {
		StringBuilder sb = new StringBuilder();
		sb.append("Context from Ghidra for the function currently selected:\n\n");
		appendFunction(sb, c, s, false);
		return sb.toString();
	}

	private static void appendFunction(StringBuilder sb, FunctionContext c, AnalysisSettings s,
			boolean forRename) {
		sb.append("## TARGET FUNCTION\n");
		sb.append("Name: ").append(c.name).append('\n');
		sb.append("Entry: ").append(c.address).append('\n');
		sb.append("Signature: ").append(c.signature).append('\n');
		if (c.callingConvention != null) {
			sb.append("Calling convention: ").append(c.callingConvention).append('\n');
		}

		if (!c.variables.isEmpty()) {
			sb.append(forRename ? "\n## VARIABLES (rename candidates)\n" : "\n## VARIABLES\n");
			for (FunctionContext.VarInfo v : c.variables) {
				sb.append(v.parameter() ? "param " : "local ")
						.append(v.name())
						.append(" : ")
						.append(v.type())
						.append('\n');
			}
		}

		if (c.decompiled != null) {
			sb.append("\n## DECOMPILED C\n```c\n").append(c.decompiled.strip()).append("\n```\n");
		}
		if (c.disassembly != null && !c.disassembly.isBlank()) {
			sb.append("\n## DISASSEMBLY\n```asm\n").append(c.disassembly.strip()).append("\n```\n");
		}
		if (c.decompiled == null && c.decompileError != null) {
			sb.append("\n(Decompiler: ").append(c.decompileError).append(")\n");
		}

		if (!c.strings.isEmpty()) {
			sb.append("\n## STRINGS REFERENCED\n");
			for (String str : c.strings) {
				sb.append("- ").append(str.replace('\n', ' ')).append('\n');
			}
		}
		if (!c.externalCalls.isEmpty()) {
			sb.append("\n## IMPORTED / EXTERNAL CALLS\n");
			sb.append(String.join(", ", c.externalCalls)).append('\n');
		}
		appendNeighbors(sb, "CALLERS (functions that call the target)", c.callers,
			c.callersOmitted);
		appendNeighbors(sb, "CALLEES (functions the target calls)", c.callees, c.calleesOmitted);
	}

	private static void appendNeighbors(StringBuilder sb, String title,
			java.util.List<FunctionContext.Neighbor> list, int omitted) {
		if (list.isEmpty() && omitted == 0) {
			return;
		}
		sb.append("\n## ").append(title).append('\n');
		for (FunctionContext.Neighbor n : list) {
			sb.append("\n### ").append(n.name()).append(" @ ").append(n.address())
					.append(" (depth ").append(n.depth()).append(")\n");
			sb.append("Signature: ").append(n.signature()).append('\n');
			if (n.code() != null && !n.code().isBlank()) {
				sb.append("```\n").append(n.code().strip()).append("\n```\n");
			}
		}
		if (omitted > 0) {
			sb.append("\n(").append(omitted).append(" more omitted due to limits)\n");
		}
	}
}
