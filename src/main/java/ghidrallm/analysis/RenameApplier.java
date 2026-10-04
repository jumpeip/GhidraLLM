package ghidrallm.analysis;

import java.util.*;

import ghidra.program.model.listing.*;
import ghidra.program.model.pcode.*;
import ghidra.program.model.pcode.HighFunctionDBUtil.ReturnCommitOption;
import ghidra.program.model.symbol.SourceType;
import ghidra.util.exception.DuplicateNameException;
import ghidrallm.config.AnalysisSettings;

/** Writes an accepted {@link RenameSuggestion} into the program database. */
public final class RenameApplier {

	public static final String COMMENT_TAG = "[GhidraLLM]";

	private RenameApplier() {
	}

	public static class Result {
		public String oldFunctionName;
		public String newFunctionName;
		public int variablesRenamed;
		public boolean commentAdded;
		public final List<String> skipped = new ArrayList<>();
		public final List<String> errors = new ArrayList<>();

		public String summary() {
			StringBuilder sb = new StringBuilder();
			if (newFunctionName != null) {
				sb.append(oldFunctionName).append(" -> ").append(newFunctionName);
			}
			else {
				sb.append(oldFunctionName).append(" (name kept)");
			}
			sb.append(", ").append(variablesRenamed).append(" variable(s) renamed");
			if (!errors.isEmpty()) {
				sb.append(", ").append(errors.size()).append(" error(s)");
			}
			return sb.toString();
		}
	}

	/** True if the function still has an auto-generated (or analysis-imported) name. */
	public static boolean isFunctionRenameAllowed(Function f, AnalysisSettings s) {
		SourceType src = f.getSymbol().getSource();
		return s.overwriteUserNames || src == SourceType.DEFAULT;
	}

	public static boolean isVariableRenameAllowed(String currentName, AnalysisSettings s) {
		return s.overwriteUserNames || ResponseParser.DEFAULT_VAR.matcher(currentName).matches();
	}

	/**
	 * Applies the suggestion in its own transaction.
	 *
	 * @param acceptedVars old names to rename (null = everything allowed by settings)
	 * @param renameFunction whether to rename the function itself
	 * @param addComment whether to write the summary as a plate comment
	 */
	public static Result apply(Function f, HighFunction high, RenameSuggestion sug,
			AnalysisSettings s, Set<String> acceptedVars, boolean renameFunction,
			boolean addComment) {
		Program program = f.getProgram();
		Result res = new Result();
		res.oldFunctionName = f.getName();
		int tx = program.startTransaction("GhidraLLM rename " + f.getName());
		boolean commit = false;
		try {
			renameVariables(f, high, sug, s, acceptedVars, res);
			if (renameFunction && sug.functionName != null &&
				!sug.functionName.equals(f.getName())) {
				renameFunction(f, sug.functionName, res);
			}
			if (addComment && sug.summary != null && !sug.summary.isBlank()) {
				setSummaryComment(f, sug, res);
			}
			commit = true;
		}
		catch (Exception e) {
			res.errors.add("unexpected: " + e);
			commit = true; // keep whatever was applied; the user can undo
		}
		finally {
			program.endTransaction(tx, commit);
		}
		return res;
	}

	private static void renameFunction(Function f, String newName, Result res) {
		String candidate = newName;
		for (int i = 2; i < 100; i++) {
			try {
				f.setName(candidate, SourceType.USER_DEFINED);
				res.newFunctionName = candidate;
				return;
			}
			catch (DuplicateNameException e) {
				candidate = newName + "_" + i;
			}
			catch (Exception e) {
				res.errors.add("function rename to '" + newName + "' failed: " + e.getMessage());
				return;
			}
		}
		res.errors.add("function rename to '" + newName + "' failed: name in use");
	}

	private static void setSummaryComment(Function f, RenameSuggestion sug, Result res) {
		String block = COMMENT_TAG + " " + sug.summary.strip();
		if (sug.confidence >= 0) {
			block += String.format(" (confidence %.2f)", sug.confidence);
		}
		String existing = f.getComment();
		String updated;
		if (existing == null || existing.isBlank()) {
			updated = block;
		}
		else {
			// replace our previous block, keep anything the analyst wrote
			StringBuilder kept = new StringBuilder();
			for (String line : existing.split("\\r?\\n")) {
				if (!line.startsWith(COMMENT_TAG)) {
					kept.append(line).append('\n');
				}
			}
			String k = kept.toString().strip();
			updated = k.isEmpty() ? block : k + "\n" + block;
		}
		try {
			f.setComment(updated);
			res.commentAdded = true;
		}
		catch (Exception e) {
			res.errors.add("comment failed: " + e.getMessage());
		}
	}

	private static void renameVariables(Function f, HighFunction high, RenameSuggestion sug,
			AnalysisSettings s, Set<String> accepted, Result res) {
		if (sug.renames.isEmpty() ||
			(accepted == null && !s.renameParameters && !s.renameLocals)) {
			return;
		}
		if (high == null) {
			renameDbVariables(f, sug, s, accepted, res);
			return;
		}

		Map<String, HighSymbol> byName = new HashMap<>();
		Set<String> taken = new HashSet<>();
		Iterator<HighSymbol> it = high.getLocalSymbolMap().getSymbols();
		while (it.hasNext()) {
			HighSymbol hs = it.next();
			byName.putIfAbsent(hs.getName(), hs);
			taken.add(hs.getName());
		}
		for (Variable v : f.getAllVariables()) {
			taken.add(v.getName());
		}

		// Work out the list first so parameters can be committed once.
		List<Map.Entry<HighSymbol, String>> plan = new ArrayList<>();
		boolean anyParam = false;
		for (Map.Entry<String, String> e : sug.renames.entrySet()) {
			String oldName = e.getKey();
			if (accepted != null && !accepted.contains(oldName)) {
				continue;
			}
			HighSymbol hs = byName.get(oldName);
			if (hs == null) {
				res.skipped.add(oldName + " (not found)");
				continue;
			}
			if (accepted == null && (hs.isParameter() ? !s.renameParameters : !s.renameLocals)) {
				continue;
			}
			if (accepted == null && !isVariableRenameAllowed(oldName, s)) {
				res.skipped.add(oldName + " (already named)");
				continue;
			}
			taken.remove(oldName);
			String newName = unique(e.getValue(), taken);
			taken.add(newName);
			plan.add(Map.entry(hs, newName));
			anyParam |= hs.isParameter();
		}
		if (plan.isEmpty()) {
			return;
		}
		if (anyParam && needsFullCommit(high)) {
			try {
				HighFunctionDBUtil.commitParamsToDatabase(high, false,
					ReturnCommitOption.NO_COMMIT, f.getSignatureSource());
			}
			catch (Exception ex) {
				res.errors.add("could not commit parameters: " + ex.getMessage());
			}
		}
		for (Map.Entry<HighSymbol, String> p : plan) {
			HighSymbol hs = p.getKey();
			try {
				HighFunctionDBUtil.updateDBVariable(hs, p.getValue(), null,
					SourceType.USER_DEFINED);
				res.variablesRenamed++;
			}
			catch (Exception ex) {
				res.errors.add(hs.getName() + " -> " + p.getValue() + ": " + ex.getMessage());
			}
		}
	}

	/** Same check Ghidra's decompiler rename action uses before touching a parameter. */
	static boolean needsFullCommit(HighFunction high) {
		Function function = high.getFunction();
		Parameter[] parameters = function.getParameters();
		LocalSymbolMap lsm = high.getLocalSymbolMap();
		int numParams = lsm.getNumParams();
		if (numParams != parameters.length) {
			return true;
		}
		for (int i = 0; i < numParams; i++) {
			HighSymbol param = lsm.getParamSymbol(i);
			if (param.getCategoryIndex() != i) {
				return true;
			}
			if (0 != param.getStorage().compareTo(parameters[i].getVariableStorage())) {
				return true;
			}
		}
		return false;
	}

	private static void renameDbVariables(Function f, RenameSuggestion sug, AnalysisSettings s,
			Set<String> accepted, Result res) {
		Set<String> taken = new HashSet<>();
		for (Variable v : f.getAllVariables()) {
			taken.add(v.getName());
		}
		for (Variable v : f.getAllVariables()) {
			String newName = sug.renames.get(v.getName());
			if (newName == null || (accepted != null && !accepted.contains(v.getName()))) {
				continue;
			}
			boolean isParam = v instanceof Parameter;
			if (accepted == null && (isParam ? !s.renameParameters : !s.renameLocals)) {
				continue;
			}
			if (accepted == null && !isVariableRenameAllowed(v.getName(), s)) {
				continue;
			}
			taken.remove(v.getName());
			String n = unique(newName, taken);
			try {
				v.setName(n, SourceType.USER_DEFINED);
				taken.add(n);
				res.variablesRenamed++;
			}
			catch (Exception ex) {
				taken.add(v.getName());
				res.errors.add(v.getName() + " -> " + n + ": " + ex.getMessage());
			}
		}
	}

	static String unique(String name, Set<String> taken) {
		if (!taken.contains(name)) {
			return name;
		}
		for (int i = 2;; i++) {
			String c = name + "_" + i;
			if (!taken.contains(c)) {
				return c;
			}
		}
	}
}
