package ghidrallm.ui;

import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.regex.PatternSyntaxException;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableRowSorter;

import docking.DialogComponentProvider;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.Program;
import ghidra.util.task.TaskMonitor;
import ghidrallm.analysis.FunctionFilter;
import ghidrallm.config.AnalysisSettings;

/** Choose functions by name pattern (e.g. FUN_*) and review the matches before renaming. */
public class FunctionFilterDialog extends DialogComponentProvider {

	private final Program program;
	private final AnalysisSettings settings;
	private final JTextField pattern = new JTextField(30);
	private final JCheckBox regex = new JCheckBox("Regular expression");
	private final JCheckBox caseSensitive = new JCheckBox("Case sensitive");
	private final JCheckBox skipThunks = new JCheckBox("Skip thunks");
	private final JCheckBox skipExternal = new JCheckBox("Skip external functions");
	private final JSpinner minBytes = UiUtil.intSpinner(0, 0, Integer.MAX_VALUE, 16);
	private final JLabel countLabel = new JLabel(" ");
	private final Model model = new Model();
	private final JTable table = new JTable(model);
	private List<Function> result;

	public FunctionFilterDialog(Program program, AnalysisSettings settings, String providerName) {
		super("GhidraLLM - Rename Filtered Functions", true, true, true, false);
		this.program = program;
		this.settings = settings;

		pattern.setText(settings.filterPattern);
		regex.setSelected(settings.filterRegex);
		caseSensitive.setSelected(settings.filterCaseSensitive);
		skipThunks.setSelected(settings.filterSkipThunks);
		skipExternal.setSelected(settings.filterSkipExternal);
		minBytes.setValue(settings.filterMinBodyBytes);

		JButton preview = new JButton("Find Matches");
		preview.addActionListener(e -> refresh());
		pattern.addActionListener(e -> refresh());
		JButton all = new JButton("Select All");
		JButton none = new JButton("Select None");
		all.addActionListener(e -> model.setAll(true));
		none.addActionListener(e -> model.setAll(false));

		UiUtil.Form f = new UiUtil.Form();
		f.add("Name pattern(s):", pattern,
			"Comma separated globs such as FUN_*, sub_40????. Prefix with ! to exclude. " +
				"With 'Regular expression' ticked, separate patterns with commas or newlines.");
		f.add("", UiUtil.row(regex, caseSensitive, skipThunks, skipExternal));
		f.add("Min body size (bytes):", minBytes);
		f.full(UiUtil.row(preview, all, none, countLabel));

		table.setAutoCreateRowSorter(true);
		table.getColumnModel().getColumn(0).setMaxWidth(60);
		table.getColumnModel().getColumn(2).setPreferredWidth(140);
		((TableRowSorter<?>) table.getRowSorter()).setSortsOnUpdates(false);

		JPanel panel = new JPanel(new BorderLayout(4, 4));
		panel.add(f, BorderLayout.NORTH);
		panel.add(new JScrollPane(table), BorderLayout.CENTER);
		JLabel prov = new JLabel("Provider: " + providerName);
		prov.setBorder(BorderFactory.createEmptyBorder(4, 4, 0, 0));
		panel.add(prov, BorderLayout.SOUTH);
		addWorkPanel(panel);

		addOKButton();
		addCancelButton();
		setOkButtonText("Rename Selected");
		setPreferredSize(800, 600);
		setRememberSize(true);
		refresh();
	}

	private void refresh() {
		FunctionFilter filter;
		try {
			filter = new FunctionFilter(pattern.getText(), regex.isSelected(),
				caseSensitive.isSelected());
		}
		catch (PatternSyntaxException e) {
			setStatusText("Invalid pattern: " + e.getDescription());
			return;
		}
		filter.skipThunks = skipThunks.isSelected();
		filter.skipExternal = skipExternal.isSelected();
		filter.minBodyBytes = UiUtil.intValue(minBytes);
		List<Function> matches = filter.apply(program, TaskMonitor.DUMMY);
		model.setFunctions(matches);
		updateCount();
		setStatusText(matches.isEmpty() ? "No functions match." : "");
	}

	private void updateCount() {
		countLabel.setText(model.selectedCount() + " of " + model.getRowCount() + " selected");
		setOkEnabled(model.selectedCount() > 0);
	}

	@Override
	protected void okCallback() {
		result = model.selected();
		if (result.isEmpty()) {
			setStatusText("Nothing selected.");
			return;
		}
		settings.filterPattern = pattern.getText();
		settings.filterRegex = regex.isSelected();
		settings.filterCaseSensitive = caseSensitive.isSelected();
		settings.filterSkipThunks = skipThunks.isSelected();
		settings.filterSkipExternal = skipExternal.isSelected();
		settings.filterMinBodyBytes = UiUtil.intValue(minBytes);
		close();
	}

	@Override
	protected void cancelCallback() {
		result = null;
		close();
	}

	/** Selected functions, or null if cancelled. */
	public List<Function> getResult() {
		return result;
	}

	private class Model extends AbstractTableModel {
		private final String[] cols = { "Use", "Name", "Address", "Size", "Callers" };
		private List<Function> rows = new ArrayList<>();
		private boolean[] checked = new boolean[0];

		void setFunctions(List<Function> fs) {
			rows = new ArrayList<>(fs);
			checked = new boolean[rows.size()];
			Arrays.fill(checked, true);
			fireTableDataChanged();
		}

		void setAll(boolean v) {
			Arrays.fill(checked, v);
			fireTableDataChanged();
			updateCount();
		}

		int selectedCount() {
			int n = 0;
			for (boolean b : checked) {
				n += b ? 1 : 0;
			}
			return n;
		}

		List<Function> selected() {
			List<Function> out = new ArrayList<>();
			for (int i = 0; i < rows.size(); i++) {
				if (checked[i]) {
					out.add(rows.get(i));
				}
			}
			return out;
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
			return switch (c) {
				case 0 -> Boolean.class;
				case 3, 4 -> Long.class;
				default -> String.class;
			};
		}

		@Override
		public boolean isCellEditable(int r, int c) {
			return c == 0;
		}

		@Override
		public Object getValueAt(int r, int c) {
			Function f = rows.get(r);
			return switch (c) {
				case 0 -> checked[r];
				case 1 -> f.getName();
				case 2 -> f.getEntryPoint().toString();
				case 3 -> f.getBody().getNumAddresses();
				case 4 -> (long) f.getSymbol().getReferenceCount();
				default -> "";
			};
		}

		@Override
		public void setValueAt(Object v, int r, int c) {
			if (c == 0) {
				checked[r] = Boolean.TRUE.equals(v);
				fireTableCellUpdated(r, c);
				updateCount();
			}
		}
	}
}
