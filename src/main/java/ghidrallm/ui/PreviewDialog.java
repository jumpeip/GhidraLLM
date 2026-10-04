package ghidrallm.ui;

import java.awt.*;
import java.util.*;
import java.util.List;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;

import docking.DialogComponentProvider;
import ghidrallm.analysis.*;
import ghidrallm.config.AnalysisSettings;

/** Lets the analyst review/edit the LLM's proposal for one function before it is applied. */
public class PreviewDialog extends DialogComponentProvider {

	private final RenameEngine.Analysis analysis;
	private final JCheckBox renameFunction = new JCheckBox("Rename function to:");
	private final JTextField functionName = new JTextField(40);
	private final JCheckBox addComment = new JCheckBox("Add summary as plate comment");
	private final JTextArea summary = new JTextArea(3, 60);
	private final VarModel model;
	private boolean accepted;

	/** Accepted results after OK. */
	public RenameSuggestion acceptedSuggestion;
	public Set<String> acceptedVariables;
	public boolean acceptedRenameFunction;
	public boolean acceptedComment;

	public PreviewDialog(RenameEngine.Analysis an, AnalysisSettings s, String providerName) {
		super("GhidraLLM - Review suggestions for " + an.function().getName(), true, true, true,
			false);
		this.analysis = an;
		RenameSuggestion sug = an.suggestion();

		boolean funcAllowed = RenameApplier.isFunctionRenameAllowed(an.function(), s);
		functionName.setText(sug.functionName == null ? an.function().getName() : sug.functionName);
		renameFunction.setSelected(s.renameFunction && sug.functionName != null && funcAllowed);
		if (sug.functionName != null && !funcAllowed) {
			renameFunction.setToolTipText(
				"The function already has a user-defined name; tick to overwrite it.");
		}
		summary.setText(sug.summary == null ? "" : sug.summary);
		summary.setLineWrap(true);
		summary.setWrapStyleWord(true);
		addComment.setSelected(s.addPlateComment && sug.summary != null);

		List<Row> rows = new ArrayList<>();
		for (FunctionContext.VarInfo v : an.context().variables) {
			String proposed = sug.renames.get(v.name());
			if (proposed == null) {
				continue;
			}
			boolean kindOn = v.parameter() ? s.renameParameters : s.renameLocals;
			boolean allowed = RenameApplier.isVariableRenameAllowed(v.name(), s);
			rows.add(new Row(kindOn && allowed, v.parameter() ? "param" : "local", v.name(),
				v.type(), proposed));
		}
		model = new VarModel(rows);
		JTable table = new JTable(model);
		table.getColumnModel().getColumn(0).setMaxWidth(50);
		table.getColumnModel().getColumn(1).setMaxWidth(60);

		JPanel top = new JPanel(new BorderLayout(4, 4));
		StringBuilder info = new StringBuilder("<html>");
		info.append("<b>").append(escape(an.function().getName())).append("</b> @ ")
				.append(an.function().getEntryPoint())
				.append(" &nbsp; | &nbsp; ")
				.append(escape(providerName));
		String usage = an.response().usageSummary();
		if (!usage.isEmpty()) {
			info.append(" (").append(escape(usage)).append(")");
		}
		if (sug.confidence >= 0) {
			info.append(String.format(" &nbsp; | &nbsp; confidence %.2f", sug.confidence));
		}
		info.append("<br>Prompt size: ").append(an.promptChars()).append(" chars, ")
				.append(an.context().callers.size()).append(" caller(s), ")
				.append(an.context().callees.size()).append(" callee(s) included");
		info.append("</html>");
		top.add(new JLabel(info.toString()), BorderLayout.NORTH);

		UiUtil.Form f = new UiUtil.Form();
		f.add("", UiUtil.row(renameFunction, functionName));
		f.add("Summary:", new JScrollPane(summary));
		f.add("", addComment);
		top.add(f, BorderLayout.CENTER);

		JPanel panel = new JPanel(new BorderLayout(4, 4));
		panel.add(top, BorderLayout.NORTH);
		JPanel center = new JPanel(new BorderLayout());
		center.add(new JLabel("Variable renames (" + rows.size() + ") - names are editable:"),
			BorderLayout.NORTH);
		center.add(new JScrollPane(table), BorderLayout.CENTER);
		panel.add(center, BorderLayout.CENTER);

		JButton raw = new JButton("Show Raw Response");
		raw.addActionListener(e -> {
			JTextArea ta = new JTextArea(sug.rawResponse, 25, 90);
			ta.setEditable(false);
			ta.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
			JOptionPane.showMessageDialog(panel, new JScrollPane(ta), "Raw LLM response",
				JOptionPane.PLAIN_MESSAGE);
		});
		JPanel bottom = new JPanel(new BorderLayout());
		bottom.add(raw, BorderLayout.WEST);
		if (!sug.warnings.isEmpty()) {
			JLabel w = new JLabel(sug.warnings.size() + " suggestion(s) ignored");
			w.setToolTipText("<html>" + escape(String.join("\n", sug.warnings))
					.replace("\n", "<br>") + "</html>");
			bottom.add(w, BorderLayout.EAST);
		}
		panel.add(bottom, BorderLayout.SOUTH);

		addWorkPanel(panel);
		addOKButton();
		setOkButtonText("Apply");
		addCancelButton();
		setPreferredSize(900, 620);
		setRememberSize(true);
	}

	private static String escape(String s) {
		return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}

	@Override
	protected void okCallback() {
		RenameSuggestion out = new RenameSuggestion();
		out.rawResponse = analysis.suggestion().rawResponse;
		out.confidence = analysis.suggestion().confidence;
		String fn = ResponseParser.sanitize(functionName.getText());
		if (renameFunction.isSelected() && fn == null) {
			setStatusText("Function name is not a valid identifier.");
			return;
		}
		out.functionName = fn;
		out.summary = summary.getText().strip();
		Set<String> vars = new HashSet<>();
		for (Row r : model.rows) {
			if (!r.apply) {
				continue;
			}
			String n = ResponseParser.sanitize(r.newName);
			if (n == null) {
				setStatusText("'" + r.newName + "' is not a valid identifier.");
				return;
			}
			out.renames.put(r.oldName, n);
			vars.add(r.oldName);
		}
		acceptedSuggestion = out;
		acceptedVariables = vars;
		acceptedRenameFunction = renameFunction.isSelected();
		acceptedComment = addComment.isSelected() && !out.summary.isEmpty();
		accepted = true;
		close();
	}

	@Override
	protected void cancelCallback() {
		accepted = false;
		close();
	}

	public boolean isAccepted() {
		return accepted;
	}

	private static class Row {
		boolean apply;
		final String kind;
		final String oldName;
		final String type;
		String newName;

		Row(boolean apply, String kind, String oldName, String type, String newName) {
			this.apply = apply;
			this.kind = kind;
			this.oldName = oldName;
			this.type = type;
			this.newName = newName;
		}
	}

	private static class VarModel extends AbstractTableModel {
		private final String[] cols = { "Apply", "Kind", "Current name", "Type", "New name" };
		final List<Row> rows;

		VarModel(List<Row> rows) {
			this.rows = rows;
		}

		@Override
		public int getRowCount() {
			return rows.size();
		}

		@Override
		public int getColumnCount() {
			return cols.length;
		}

		@Override
		public String getColumnName(int c) {
			return cols[c];
		}

		@Override
		public Class<?> getColumnClass(int c) {
			return c == 0 ? Boolean.class : String.class;
		}

		@Override
		public boolean isCellEditable(int r, int c) {
			return c == 0 || c == 4;
		}

		@Override
		public Object getValueAt(int r, int c) {
			Row row = rows.get(r);
			return switch (c) {
				case 0 -> row.apply;
				case 1 -> row.kind;
				case 2 -> row.oldName;
				case 3 -> row.type;
				default -> row.newName;
			};
		}

		@Override
		public void setValueAt(Object v, int r, int c) {
			Row row = rows.get(r);
			if (c == 0) {
				row.apply = Boolean.TRUE.equals(v);
			}
			else if (c == 4) {
				row.newName = String.valueOf(v).trim();
				row.apply = true;
			}
			fireTableRowsUpdated(r, r);
		}
	}
}
