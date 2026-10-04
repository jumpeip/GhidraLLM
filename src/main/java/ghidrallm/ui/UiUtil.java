package ghidrallm.ui;

import java.awt.*;
import java.io.File;
import java.text.ParseException;

import javax.swing.*;
import javax.swing.text.DefaultFormatter;

import docking.widgets.filechooser.GhidraFileChooser;
import docking.widgets.filechooser.GhidraFileChooserMode;
import ghidra.util.filechooser.ExtensionFileFilter;

/** Small Swing helpers shared by the dialogs. */
final class UiUtil {

	private UiUtil() {
	}

	/**
	 * Integer spinner with up/down arrows that also accepts typed values. The typed value is
	 * committed on every valid keystroke so OK/Apply never loses an edit.
	 */
	static JSpinner intSpinner(int value, int min, int max, int step) {
		SpinnerNumberModel model =
			new SpinnerNumberModel(Math.max(min, Math.min(max, value)), min, max, step);
		JSpinner sp = new JSpinner(model);
		JSpinner.NumberEditor ed = new JSpinner.NumberEditor(sp, "#");
		sp.setEditor(ed);
		JFormattedTextField tf = ed.getTextField();
		tf.setColumns(Math.max(6, String.valueOf(max).length() + 1));
		tf.setHorizontalAlignment(SwingConstants.RIGHT);
		if (tf.getFormatter() instanceof DefaultFormatter df) {
			df.setCommitsOnValidEdit(true);
		}
		tf.setFocusLostBehavior(JFormattedTextField.COMMIT_OR_REVERT);
		return sp;
	}

	static JSpinner doubleSpinner(double value, double min, double max, double step) {
		JSpinner sp = new JSpinner(new SpinnerNumberModel(value, min, max, step));
		JSpinner.NumberEditor ed = new JSpinner.NumberEditor(sp, "0.00");
		sp.setEditor(ed);
		ed.getTextField().setColumns(5);
		if (ed.getTextField().getFormatter() instanceof DefaultFormatter df) {
			df.setCommitsOnValidEdit(true);
		}
		return sp;
	}

	static int intValue(JSpinner sp) {
		commit(sp);
		return ((Number) sp.getValue()).intValue();
	}

	static double doubleValue(JSpinner sp) {
		commit(sp);
		return ((Number) sp.getValue()).doubleValue();
	}

	private static void commit(JSpinner sp) {
		try {
			sp.commitEdit();
		}
		catch (ParseException e) {
			// keep last valid value
		}
	}

	/** Lays out label/component rows in a two-column grid. */
	static final class Form extends JPanel {
		private final GridBagConstraints gc = new GridBagConstraints();
		private int row;

		Form() {
			super(new GridBagLayout());
			gc.insets = new Insets(3, 4, 3, 4);
			gc.anchor = GridBagConstraints.WEST;
		}

		Form add(String label, JComponent comp) {
			return add(label, comp, null);
		}

		Form add(String label, JComponent comp, String tooltip) {
			gc.gridy = row++;
			gc.gridx = 0;
			gc.weightx = 0;
			gc.fill = GridBagConstraints.NONE;
			gc.gridwidth = 1;
			JLabel l = new JLabel(label);
			if (tooltip != null) {
				l.setToolTipText(tooltip);
				comp.setToolTipText(tooltip);
			}
			l.setLabelFor(comp);
			super.add(l, gc);
			gc.gridx = 1;
			gc.weightx = 1;
			gc.fill = comp instanceof JSpinner || comp instanceof JCheckBox
					? GridBagConstraints.NONE
					: GridBagConstraints.HORIZONTAL;
			super.add(comp, gc);
			return this;
		}

		Form full(JComponent comp) {
			gc.gridy = row++;
			gc.gridx = 0;
			gc.gridwidth = 2;
			gc.weightx = 1;
			gc.fill = GridBagConstraints.HORIZONTAL;
			super.add(comp, gc);
			gc.gridwidth = 1;
			return this;
		}

		Form section(String title) {
			JLabel l = new JLabel(title);
			l.setFont(l.getFont().deriveFont(Font.BOLD));
			l.setBorder(BorderFactory.createEmptyBorder(8, 0, 2, 0));
			return full(l);
		}

		Form filler() {
			gc.gridy = row++;
			gc.gridx = 0;
			gc.weighty = 1;
			super.add(Box.createGlue(), gc);
			gc.weighty = 0;
			return this;
		}
	}

	static JPanel row(JComponent... comps) {
		JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
		for (JComponent c : comps) {
			p.add(c);
		}
		return p;
	}

	/** Text field + "Browse..." button that opens a Ghidra file chooser. */
	static JPanel fileField(JTextField field, Component parent, String title, String desc,
			String... exts) {
		JButton browse = new JButton("Browse...");
		browse.addActionListener(e -> {
			GhidraFileChooser fc = new GhidraFileChooser(parent);
			fc.setTitle(title);
			fc.setFileSelectionMode(GhidraFileChooserMode.FILES_ONLY);
			fc.setFileFilter(ExtensionFileFilter.forExtensions(desc, exts));
			String cur = field.getText().trim();
			if (!cur.isEmpty()) {
				File f = new File(cur);
				File dir = f.isDirectory() ? f : f.getParentFile();
				if (dir != null && dir.isDirectory()) {
					fc.setCurrentDirectory(dir);
				}
			}
			File sel = fc.getSelectedFile();
			fc.dispose();
			if (sel != null) {
				field.setText(sel.getAbsolutePath());
			}
		});
		JPanel p = new JPanel(new BorderLayout(4, 0));
		p.add(field, BorderLayout.CENTER);
		p.add(browse, BorderLayout.EAST);
		return p;
	}
}
