package ghidrallm;

import java.util.*;

import javax.swing.JOptionPane;

import docking.ActionContext;
import docking.action.DockingAction;
import docking.action.MenuData;
import docking.action.ToggleDockingAction;
import docking.action.builder.ActionBuilder;
import ghidra.MiscellaneousPluginPackage;
import ghidra.app.context.ProgramLocationActionContext;
import ghidra.app.plugin.PluginCategoryNames;
import ghidra.app.plugin.ProgramPlugin;
import ghidra.framework.plugintool.PluginInfo;
import ghidra.framework.plugintool.PluginTool;
import ghidra.framework.plugintool.util.PluginStatus;
import ghidra.program.model.listing.*;
import ghidra.program.util.ProgramLocation;
import ghidra.util.Msg;
import ghidra.util.Swing;
import ghidra.util.task.*;
import ghidrallm.analysis.*;
import ghidrallm.config.*;
import ghidrallm.llm.LLMException;
import ghidrallm.ui.*;

//@formatter:off
@PluginInfo(
	status = PluginStatus.RELEASED,
	packageName = MiscellaneousPluginPackage.NAME,
	category = PluginCategoryNames.ANALYSIS,
	shortDescription = "LLM-assisted function, parameter and variable renaming",
	description = "Sends one function, every function, or a filtered set of functions " +
		"(e.g. FUN_*) to a configurable LLM (Claude, Gemini, Meta Llama, GitHub Copilot " +
		"models, Ollama or any OpenAI-compatible endpoint) to rename functions, parameters " +
		"and local variables. Includes a chat window, PKI client-certificate support, retry " +
		"handling and caller/callee context gathering."
)
//@formatter:on
public class GhidraLLMPlugin extends ProgramPlugin implements ChatProvider.Host {

	static final String MENU = "GhidraLLM";
	private static final String SELECT_MENU = "Select LLM";

	private final ConfigStore store = ConfigStore.getInstance();
	private final List<DockingAction> providerActions = new ArrayList<>();
	private final Runnable configListener = () -> Swing.runLater(this::rebuildProviderMenu);
	private ChatProvider chat;
	private LogProvider logProvider;

	public GhidraLLMPlugin(PluginTool tool) {
		super(tool);
	}

	@Override
	protected void init() {
		super.init();
		logProvider = new LogProvider(tool, getName());
		chat = new ChatProvider(tool, getName(), this, store);
		createActions();
		rebuildProviderMenu();
		store.addChangeListener(configListener);
		if (store.getLastLoadError() != null) {
			log(store.getLastLoadError());
		}
	}

	@Override
	protected void dispose() {
		store.removeChangeListener(configListener);
		if (chat != null) {
			chat.dispose();
		}
		if (logProvider != null) {
			logProvider.removeFromTool();
		}
		super.dispose();
	}

	// ------------------------------------------------------------------ actions

	private void createActions() {
		String owner = getName();

		new ActionBuilder("GhidraLLM Rename Current Function", owner)
				.menuPath(MENU, "Rename Current Function")
				.menuGroup("1_rename", "a")
				.description("Send the function at the cursor to the active LLM for renaming")
				.enabledWhen(c -> currentFunction() != null)
				.onAction(c -> renameSingle(currentFunction()))
				.buildAndInstall(tool);

		new ActionBuilder("GhidraLLM Rename Function (popup)", owner)
				.popupMenuPath(MENU, "Rename Function with LLM")
				.popupMenuGroup("ZZ_GhidraLLM")
				.popupWhen(c -> functionFromContext(c) != null)
				.enabledWhen(c -> functionFromContext(c) != null)
				.onAction(c -> renameSingle(functionFromContext(c)))
				.buildAndInstall(tool);

		new ActionBuilder("GhidraLLM Chat About Function (popup)", owner)
				.popupMenuPath(MENU, "Ask LLM About This Function...")
				.popupMenuGroup("ZZ_GhidraLLM")
				.popupWhen(c -> functionFromContext(c) != null)
				.onAction(c -> showChat())
				.buildAndInstall(tool);

		new ActionBuilder("GhidraLLM Rename Filtered Functions", owner)
				.menuPath(MENU, "Rename Filtered Functions (e.g. FUN_*)...")
				.menuGroup("1_rename", "b")
				.enabledWhen(c -> currentProgram != null)
				.onAction(c -> renameFiltered())
				.buildAndInstall(tool);

		new ActionBuilder("GhidraLLM Rename Functions In Selection", owner)
				.menuPath(MENU, "Rename Functions in Selection")
				.menuGroup("1_rename", "c")
				.enabledWhen(c -> currentProgram != null && currentSelection != null &&
					!currentSelection.isEmpty())
				.onAction(c -> renameSelection())
				.buildAndInstall(tool);

		new ActionBuilder("GhidraLLM Rename All Functions", owner)
				.menuPath(MENU, "Rename All Functions...")
				.menuGroup("1_rename", "d")
				.enabledWhen(c -> currentProgram != null)
				.onAction(c -> renameAll())
				.buildAndInstall(tool);

		new ActionBuilder("GhidraLLM Chat", owner)
				.menuPath(MENU, "Chat with LLM...")
				.menuGroup("2_chat")
				.onAction(c -> showChat())
				.buildAndInstall(tool);

		new ActionBuilder("GhidraLLM Configure", owner)
				.menuPath(MENU, "LLM Configuration...")
				.menuGroup("4_config", "a")
				.onAction(c -> showConfig(false))
				.buildAndInstall(tool);

		new ActionBuilder("GhidraLLM Activity Log", owner)
				.menuPath(MENU, "Activity Log")
				.menuGroup("4_config", "b")
				.onAction(c -> logProvider.setVisible(true))
				.buildAndInstall(tool);
	}

	/** Rebuilds GhidraLLM > Select LLM > (one checkable entry per provider) + Add New Provider. */
	private void rebuildProviderMenu() {
		for (DockingAction a : providerActions) {
			tool.removeAction(a);
		}
		providerActions.clear();
		LLMConfig cfg = store.get();
		ProviderConfig active = cfg.getActiveProvider();
		int i = 0;
		for (ProviderConfig p : cfg.providers) {
			String id = p.id;
			int order = i++;
			ToggleDockingAction a =
				new ToggleDockingAction("GhidraLLM Select LLM " + order, getName(), false) {
					@Override
					public void actionPerformed(ActionContext context) {
						store.setActiveProvider(id);
					}
				};
			MenuData md = new MenuData(new String[] { MENU, SELECT_MENU, p.name }, null,
				"a_providers");
			md.setMenuSubGroup(String.format("%04d", order));
			a.setMenuBarData(md);
			a.setDescription(p.type + " / " + p.model);
			a.setSelected(active != null && active.id.equals(id));
			a.markHelpUnnecessary();
			tool.addAction(a);
			providerActions.add(a);
		}
		DockingAction add = new ActionBuilder("GhidraLLM Add Provider", getName())
				.menuPath(MENU, SELECT_MENU, "Add New Provider...")
				.menuGroup("z_add")
				.onAction(c -> showConfig(true))
				.buildAndInstall(tool);
		providerActions.add(add);
		chat.setSubTitle(active == null ? "" : active.name);
	}

	// ------------------------------------------------------------------ Host

	@Override
	public Program currentProgram() {
		return currentProgram;
	}

	@Override
	public Function currentFunction() {
		return functionAt(currentProgram, currentLocation);
	}

	@Override
	public void showConfig(boolean addNewProvider) {
		tool.showDialog(new ConfigDialog(store, addNewProvider));
	}

	@Override
	public void log(String msg) {
		Msg.info(this, "GhidraLLM: " + msg);
		if (logProvider != null) {
			logProvider.log(msg);
		}
	}

	private void showChat() {
		chat.setVisible(true);
		chat.toFront();
	}

	private static Function functionAt(Program program, ProgramLocation loc) {
		if (program == null || loc == null || loc.getAddress() == null) {
			return null;
		}
		return program.getFunctionManager().getFunctionContaining(loc.getAddress());
	}

	private static Function functionFromContext(ActionContext c) {
		if (c instanceof ProgramLocationActionContext plc) {
			return functionAt(plc.getProgram(), plc.getLocation());
		}
		return null;
	}

	// ------------------------------------------------------------------ rename flows

	private ProviderConfig activeProviderOrWarn() {
		ProviderConfig p = store.get().getActiveProvider();
		if (p == null) {
			Msg.showWarn(this, tool.getActiveWindow(), "GhidraLLM",
				"No LLM provider is configured. Open GhidraLLM > LLM Configuration.");
			return null;
		}
		return ConfigStore.copyOf(p);
	}

	private void renameSingle(Function f) {
		if (f == null) {
			return;
		}
		ProviderConfig provider = activeProviderOrWarn();
		if (provider == null) {
			return;
		}
		AnalysisSettings s = store.copy().analysis;
		Program program = f.getProgram();
		log("Analyzing " + f.getName() + " @ " + f.getEntryPoint() + " with " + provider.name +
			" [" + provider.model + "]");

		Task task = new Task("GhidraLLM: " + f.getName(), true, false, true) {
			@Override
			public void run(TaskMonitor monitor) {
				RenameEngine.Analysis an;
				try (CodeExtractor ex = new CodeExtractor(program, s.decompileTimeoutSeconds)) {
					an = RenameEngine.analyze(f, provider, s, ex, monitor,
						m -> log(f.getName() + ": " + m));
				}
				catch (LLMException e) {
					if (!e.isCancelled()) {
						log(f.getName() + " FAILED: " + e.getMessage());
						Msg.showError(GhidraLLMPlugin.this, tool.getActiveWindow(),
							"GhidraLLM", "Request failed:\n" + e.getMessage());
					}
					return;
				}
				catch (Exception e) {
					log(f.getName() + " FAILED: " + e);
					Msg.showError(GhidraLLMPlugin.this, tool.getActiveWindow(), "GhidraLLM",
						"Unexpected error: " + e.getMessage(), e);
					return;
				}
				Swing.runLater(() -> finishSingle(an, s, provider));
			}
		};
		new TaskLauncher(task, tool.getActiveWindow());
	}

	private void finishSingle(RenameEngine.Analysis an, AnalysisSettings s,
			ProviderConfig provider) {
		Function f = an.function();
		RenameApplier.Result r;
		if (s.confirmSingleFunction) {
			PreviewDialog dlg = new PreviewDialog(an, s, provider.name);
			tool.showDialog(dlg);
			if (!dlg.isAccepted()) {
				log(f.getName() + ": suggestions discarded");
				return;
			}
			r = RenameApplier.apply(f, an.context().high, dlg.acceptedSuggestion, s,
				dlg.acceptedVariables, dlg.acceptedRenameFunction, dlg.acceptedComment);
		}
		else {
			r = RenameApplier.apply(f, an.context().high, an.suggestion(), s, null,
				s.renameFunction && RenameApplier.isFunctionRenameAllowed(f, s),
				s.addPlateComment);
		}
		String msg = f.getEntryPoint() + "  " + r.summary() + "  [" +
			an.response().usageSummary() + "]";
		log(msg);
		for (String e : r.errors) {
			log("   error: " + e);
		}
		tool.setStatusInfo("GhidraLLM: " + r.summary());
	}

	private void renameFiltered() {
		if (currentProgram == null) {
			return;
		}
		ProviderConfig provider = activeProviderOrWarn();
		if (provider == null) {
			return;
		}
		LLMConfig cfgCopy = store.copy();
		AnalysisSettings s = cfgCopy.analysis;
		FunctionFilterDialog dlg = new FunctionFilterDialog(currentProgram, s, provider.name);
		tool.showDialog(dlg);
		List<Function> chosen = dlg.getResult();
		if (chosen == null || chosen.isEmpty()) {
			return;
		}
		store.saveAnalysis(s); // remember the filter
		runBatch(chosen, provider, s);
	}

	private void renameSelection() {
		if (currentProgram == null || currentSelection == null || currentSelection.isEmpty()) {
			return;
		}
		ProviderConfig provider = activeProviderOrWarn();
		if (provider == null) {
			return;
		}
		List<Function> list = new ArrayList<>();
		for (Function f : currentProgram.getFunctionManager().getFunctions(currentSelection,
			true)) {
			if (!f.isExternal() && !f.isThunk()) {
				list.add(f);
			}
		}
		if (list.isEmpty()) {
			Msg.showInfo(this, tool.getActiveWindow(), "GhidraLLM",
				"No functions start inside the current selection.");
			return;
		}
		if (confirmBatch(list.size(), provider, "in the selection")) {
			runBatch(list, provider, store.copy().analysis);
		}
	}

	private void renameAll() {
		if (currentProgram == null) {
			return;
		}
		ProviderConfig provider = activeProviderOrWarn();
		if (provider == null) {
			return;
		}
		List<Function> list = new ArrayList<>();
		for (Function f : currentProgram.getFunctionManager().getFunctions(true)) {
			if (!f.isExternal() && !f.isThunk()) {
				list.add(f);
			}
		}
		if (list.isEmpty()) {
			return;
		}
		if (confirmBatch(list.size(), provider, "in the program (thunks/externals excluded)")) {
			runBatch(list, provider, store.copy().analysis);
		}
	}

	private boolean confirmBatch(int n, ProviderConfig provider, String where) {
		AnalysisSettings s = store.get().analysis;
		String note = s.overwriteUserNames ? "Existing user-defined names WILL be overwritten."
				: "Functions/variables that already have user-defined names keep them.";
		int choice = JOptionPane.showConfirmDialog(tool.getActiveWindow(),
			"Send " + n + " function(s) " + where + " to " + provider.name + " [" +
				provider.model + "]?\n\n" + note + "\nEach function is one undoable transaction.",
			"GhidraLLM", JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
		return choice == JOptionPane.OK_OPTION;
	}

	private void runBatch(List<Function> functions, ProviderConfig provider, AnalysisSettings s) {
		logProvider.setVisible(true);
		BatchRenameTask task =
			new BatchRenameTask(currentProgram, functions, provider, s, this::log);
		new TaskLauncher(task, tool.getActiveWindow());
	}
}
