package ghidrallm.analysis;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import ghidra.program.model.listing.Function;
import ghidra.util.task.TaskMonitor;
import ghidrallm.config.AnalysisSettings;
import ghidrallm.config.ProviderConfig;
import ghidrallm.llm.*;

/** Context gathering + LLM call + parsing for one function (no database changes). */
public final class RenameEngine {

	private RenameEngine() {
	}

	public record Analysis(Function function, FunctionContext context,
			RenameSuggestion suggestion, LLMResponse response, int promptChars) {
	}

	public static Analysis analyze(Function f, ProviderConfig provider, AnalysisSettings s,
			CodeExtractor ex, TaskMonitor monitor, Consumer<String> log) throws LLMException {
		CancelCheck cancel = monitor == null ? CancelCheck.NEVER : monitor::isCancelled;
		String system = PromptBuilder.renameSystemPrompt(s);

		long promptBudget = provider.getPromptCharBudget();
		long contextBudget = Math.min(s.maxContextChars,
			promptBudget - system.length() - s.maxCodeChars - 6000L);
		contextBudget = Math.max(0, contextBudget);

		if (monitor != null) {
			monitor.setMessage("Decompiling " + f.getName());
		}
		FunctionContext ctx = new ContextBuilder(s, ex).build(f, monitor, contextBudget);
		if (cancel.isCancelled()) {
			throw LLMException.cancelled();
		}
		String user = PromptBuilder.renameUserPrompt(ctx, s);
		long maxUser = promptBudget - system.length();
		if (user.length() > maxUser) {
			user = CodeExtractor.truncate(user, (int) Math.max(1000, maxUser)) +
				"\nReturn the JSON object now.\n";
		}

		if (monitor != null) {
			monitor.setMessage("Asking " + provider.name + " about " + f.getName());
		}
		List<ChatMessage> msgs = new ArrayList<>();
		msgs.add(ChatMessage.user(user));
		LLMResponse resp = LLMClient.chat(provider, new LLMRequest(system, msgs, true), cancel, log);
		RenameSuggestion sug;
		try {
			sug = ResponseParser.parse(resp.getText());
		}
		catch (IllegalArgumentException first) {
			// one repair round-trip: show the model its answer and ask for JSON only
			if (log != null) {
				log.accept(f.getName() + ": " + first.getMessage() + " - asking model to fix");
			}
			msgs.add(ChatMessage.assistant(resp.getText()));
			msgs.add(ChatMessage.user("That response could not be parsed (" +
				first.getMessage() + "). Reply with ONLY the JSON object described in the " +
				"instructions, with no other text."));
			resp = LLMClient.chat(provider, new LLMRequest(system, msgs, true), cancel, log);
			try {
				sug = ResponseParser.parse(resp.getText());
			}
			catch (IllegalArgumentException second) {
				throw new LLMException(second.getMessage() + ". Response began: " +
					CodeExtractor.truncate(resp.getText(), 300));
			}
		}
		// keep only renames for variables that actually exist
		final RenameSuggestion result = sug;
		java.util.Set<String> known = ctx.variableNames();
		result.renames.keySet().removeIf(old -> {
			boolean unknown = !known.contains(old);
			if (unknown) {
				result.warnings.add("unknown variable '" + old + "' ignored");
			}
			return unknown;
		});
		return new Analysis(f, ctx, result, resp, system.length() + user.length());
	}
}
