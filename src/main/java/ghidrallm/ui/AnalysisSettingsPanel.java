package ghidrallm.ui;

import java.awt.*;

import javax.swing.*;

import ghidrallm.config.AnalysisSettings;
import ghidrallm.config.AnalysisSettings.CodeMode;
import ghidrallm.config.AnalysisSettings.NeighborMode;

/** Global settings: what code/context to send and what to rename. */
class AnalysisSettingsPanel extends JPanel {

	private final JComboBox<CodeMode> codeMode = new JComboBox<>(CodeMode.values());
	private final JComboBox<NeighborMode> neighborMode = new JComboBox<>(NeighborMode.values());
	private final JSpinner callerDepth = UiUtil.intSpinner(1, 0, 50, 1);
	private final JSpinner calleeDepth = UiUtil.intSpinner(1, 0, 50, 1);
	private final JSpinner maxCallers = UiUtil.intSpinner(10, 0, 100_000, 1);
	private final JSpinner maxCallees = UiUtil.intSpinner(20, 0, 100_000, 1);
	private final JSpinner maxCodeChars = UiUtil.intSpinner(12_000, 256, 100_000_000, 1000);
	private final JSpinner maxContextChars = UiUtil.intSpinner(125_000, 0, 100_000_000, 5000);
	private final JSpinner maxNeighborChars = UiUtil.intSpinner(6_000, 128, 100_000_000, 500);
	private final JSpinner decompileTimeout = UiUtil.intSpinner(60, 1, 86400, 5);
	private final JCheckBox includeStrings = new JCheckBox("Include referenced strings");
	private final JSpinner maxStrings = UiUtil.intSpinner(60, 0, 100_000, 10);
	private final JCheckBox includeImports = new JCheckBox("Include imported/external calls");

	private final JCheckBox renameFunction = new JCheckBox("Rename function");
	private final JCheckBox renameParams = new JCheckBox("Rename parameters");
	private final JCheckBox renameLocals = new JCheckBox("Rename local variables");
	private final JCheckBox addComment = new JCheckBox("Add LLM summary as plate comment");
	private final JCheckBox overwrite =
		new JCheckBox("Overwrite names that are already user-defined");
	private final JCheckBox confirmSingle =
		new JCheckBox("Preview and confirm before applying (single function)");
	private final JCheckBox bottomUp =
		new JCheckBox("Batch: process callees before callers (bottom-up)");
	private final JSpinner concurrent = UiUtil.intSpinner(1, 1, 64, 1);
	private final JTextArea instructions = new JTextArea(4, 50);

	AnalysisSettingsPanel() {
		super(new BorderLayout());
		instructions.setLineWrap(true);
		instructions.setWrapStyleWord(true);

		UiUtil.Form f = new UiUtil.Form();
		f.section("Code sent to the LLM");
		f.add("Target function as:", codeMode,
			"Decompiled C, disassembly, or both. Variables are renamed either way.");
		f.add("Callers/callees as:", neighborMode);
		f.add("Decompile timeout (seconds):", decompileTimeout);
		f.add("Max code characters:", maxCodeChars,
			"Limit for the target function's code. Default 12,000.");

		f.section("Cross-references (xrefs)");
		f.add("Caller depth:", callerDepth,
			"How many levels of callers (xrefs to the function) to follow. 0 = none.");
		f.add("Max callers per level:", maxCallers);
		f.add("Callee depth:", calleeDepth,
			"How many levels of called functions to follow. 0 = none.");
		f.add("Max callees per level:", maxCallees);
		f.add("Max caller/callee characters:", maxContextChars,
			"Total budget for all caller/callee code. Default 125,000.");
		f.add("Max characters per caller/callee:", maxNeighborChars);
		f.add("", UiUtil.row(includeStrings, new JLabel("max"), maxStrings));
		f.add("", includeImports);

		f.section("What to rename");
		f.add("", renameFunction);
		f.add("", renameParams);
		f.add("", renameLocals);
		f.add("", addComment);
		f.add("", overwrite);
		f.add("", confirmSingle);

		f.section("Batch runs");
		f.add("", bottomUp);
		f.add("Concurrent requests:", concurrent,
			"Parallel LLM calls for 'all' / 'filtered' runs. Mind your rate limits.");

		f.section("Additional instructions (appended to the system prompt)");
		f.full(new JScrollPane(instructions));
		f.filler();

		JScrollPane scroll = new JScrollPane(f);
		scroll.getVerticalScrollBar().setUnitIncrement(16);
		scroll.setBorder(BorderFactory.createEmptyBorder());
		add(scroll, BorderLayout.CENTER);

		includeStrings.addActionListener(e -> maxStrings.setEnabled(includeStrings.isSelected()));
	}

	void load(AnalysisSettings s) {
		codeMode.setSelectedItem(s.codeMode);
		neighborMode.setSelectedItem(s.neighborMode);
		callerDepth.setValue(s.callerDepth);
		calleeDepth.setValue(s.calleeDepth);
		maxCallers.setValue(s.maxCallersPerLevel);
		maxCallees.setValue(s.maxCalleesPerLevel);
		maxCodeChars.setValue(s.maxCodeChars);
		maxContextChars.setValue(s.maxContextChars);
		maxNeighborChars.setValue(s.maxNeighborChars);
		decompileTimeout.setValue(s.decompileTimeoutSeconds);
		includeStrings.setSelected(s.includeStrings);
		maxStrings.setValue(s.maxStrings);
		maxStrings.setEnabled(s.includeStrings);
		includeImports.setSelected(s.includeImports);
		renameFunction.setSelected(s.renameFunction);
		renameParams.setSelected(s.renameParameters);
		renameLocals.setSelected(s.renameLocals);
		addComment.setSelected(s.addPlateComment);
		overwrite.setSelected(s.overwriteUserNames);
		confirmSingle.setSelected(s.confirmSingleFunction);
		bottomUp.setSelected(s.bottomUpOrder);
		concurrent.setValue(s.concurrentRequests);
		instructions.setText(s.additionalInstructions);
	}

	void store(AnalysisSettings s) {
		s.codeMode = (CodeMode) codeMode.getSelectedItem();
		s.neighborMode = (NeighborMode) neighborMode.getSelectedItem();
		s.callerDepth = UiUtil.intValue(callerDepth);
		s.calleeDepth = UiUtil.intValue(calleeDepth);
		s.maxCallersPerLevel = UiUtil.intValue(maxCallers);
		s.maxCalleesPerLevel = UiUtil.intValue(maxCallees);
		s.maxCodeChars = UiUtil.intValue(maxCodeChars);
		s.maxContextChars = UiUtil.intValue(maxContextChars);
		s.maxNeighborChars = UiUtil.intValue(maxNeighborChars);
		s.decompileTimeoutSeconds = UiUtil.intValue(decompileTimeout);
		s.includeStrings = includeStrings.isSelected();
		s.maxStrings = UiUtil.intValue(maxStrings);
		s.includeImports = includeImports.isSelected();
		s.renameFunction = renameFunction.isSelected();
		s.renameParameters = renameParams.isSelected();
		s.renameLocals = renameLocals.isSelected();
		s.addPlateComment = addComment.isSelected();
		s.overwriteUserNames = overwrite.isSelected();
		s.confirmSingleFunction = confirmSingle.isSelected();
		s.bottomUpOrder = bottomUp.isSelected();
		s.concurrentRequests = UiUtil.intValue(concurrent);
		s.additionalInstructions = instructions.getText();
		s.clampValues();
	}
}
