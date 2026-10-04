package ghidrallm.config;

/** Settings that control what is sent to the LLM and what is renamed. Global, not per-provider. */
public class AnalysisSettings {

	public enum CodeMode {
		DECOMPILED("Decompiled C"),
		DISASSEMBLY("Disassembly"),
		BOTH("Decompiled + Disassembly");

		private final String label;

		CodeMode(String label) {
			this.label = label;
		}

		@Override
		public String toString() {
			return label;
		}
	}

	public enum NeighborMode {
		SAME_AS_TARGET("Same as target"),
		DECOMPILED("Decompiled C"),
		DISASSEMBLY("Disassembly"),
		SIGNATURE_ONLY("Signatures only");

		private final String label;

		NeighborMode(String label) {
			this.label = label;
		}

		@Override
		public String toString() {
			return label;
		}
	}

	public CodeMode codeMode = CodeMode.DECOMPILED;
	public NeighborMode neighborMode = NeighborMode.SAME_AS_TARGET;

	/** How many levels of callers (xrefs to the function) to walk. 0 = none. */
	public int callerDepth = 1;
	/** How many levels of callees (calls made by the function) to walk. 0 = none. */
	public int calleeDepth = 1;
	/** Maximum callers to include per level. */
	public int maxCallersPerLevel = 10;
	/** Maximum callees to include per level. */
	public int maxCalleesPerLevel = 20;

	/** Max characters of the target function's code. */
	public int maxCodeChars = 12_000;
	/** Max total characters of caller/callee context. */
	public int maxContextChars = 125_000;
	/** Max characters of any single caller/callee body. */
	public int maxNeighborChars = 6_000;

	public int decompileTimeoutSeconds = 60;

	public boolean includeStrings = true;
	public int maxStrings = 60;
	public boolean includeImports = true;

	public boolean renameFunction = true;
	public boolean renameParameters = true;
	public boolean renameLocals = true;
	public boolean addPlateComment = true;
	/** If false, functions/variables that already have user-defined names are left alone. */
	public boolean overwriteUserNames = false;

	/** Show a preview/confirm dialog when renaming a single function. */
	public boolean confirmSingleFunction = true;
	/** For batch runs, process callees before callers so callers see the new names. */
	public boolean bottomUpOrder = true;
	/** Parallel LLM requests during batch runs. */
	public int concurrentRequests = 1;

	/** Free-form instructions appended to the system prompt. */
	public String additionalInstructions = "";

	// --- last used filter (remembered between runs) ---
	public String filterPattern = "FUN_*";
	public boolean filterRegex = false;
	public boolean filterCaseSensitive = true;
	public boolean filterSkipThunks = true;
	public boolean filterSkipExternal = true;
	public int filterMinBodyBytes = 0;

	public void clampValues() {
		callerDepth = ProviderConfig.clamp(callerDepth, 0, 50);
		calleeDepth = ProviderConfig.clamp(calleeDepth, 0, 50);
		maxCallersPerLevel = ProviderConfig.clamp(maxCallersPerLevel, 0, 100_000);
		maxCalleesPerLevel = ProviderConfig.clamp(maxCalleesPerLevel, 0, 100_000);
		maxCodeChars = ProviderConfig.clamp(maxCodeChars, 256, 100_000_000);
		maxContextChars = ProviderConfig.clamp(maxContextChars, 0, 100_000_000);
		maxNeighborChars = ProviderConfig.clamp(maxNeighborChars, 128, 100_000_000);
		decompileTimeoutSeconds = ProviderConfig.clamp(decompileTimeoutSeconds, 1, 86400);
		maxStrings = ProviderConfig.clamp(maxStrings, 0, 100_000);
		concurrentRequests = ProviderConfig.clamp(concurrentRequests, 1, 64);
		filterMinBodyBytes = ProviderConfig.clamp(filterMinBodyBytes, 0, Integer.MAX_VALUE);
		if (codeMode == null) {
			codeMode = CodeMode.DECOMPILED;
		}
		if (neighborMode == null) {
			neighborMode = NeighborMode.SAME_AS_TARGET;
		}
		if (filterPattern == null) {
			filterPattern = "FUN_*";
		}
		if (additionalInstructions == null) {
			additionalInstructions = "";
		}
	}
}
