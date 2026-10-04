package ghidrallm.ui;

import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

import javax.swing.*;

import ghidra.framework.plugintool.ComponentProviderAdapter;
import ghidra.framework.plugintool.PluginTool;

/** Activity log for rename runs (results, retries, errors). Thread-safe {@link #log}. */
public class LogProvider extends ComponentProviderAdapter {

	private static final int MAX_CHARS = 2_000_000;
	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

	private final JPanel root = new JPanel(new BorderLayout());
	private final JTextArea area = new JTextArea();

	public LogProvider(PluginTool tool, String owner) {
		super(tool, "GhidraLLM Activity Log", owner);
		setTitle("GhidraLLM Activity Log");
		area.setEditable(false);
		area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
		JButton clear = new JButton("Clear");
		clear.addActionListener(e -> area.setText(""));
		JButton copy = new JButton("Copy");
		copy.addActionListener(e -> Toolkit.getDefaultToolkit()
				.getSystemClipboard()
				.setContents(new StringSelection(area.getText()), null));
		JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
		buttons.add(copy);
		buttons.add(clear);
		root.add(new JScrollPane(area), BorderLayout.CENTER);
		root.add(buttons, BorderLayout.SOUTH);
		root.setPreferredSize(new Dimension(800, 300));
		setDefaultWindowPosition(docking.WindowPosition.BOTTOM);
		addToTool();
	}

	public void log(String msg) {
		String line = LocalTime.now().format(TIME) + "  " + msg + "\n";
		SwingUtilities.invokeLater(() -> {
			area.append(line);
			int len = area.getDocument().getLength();
			if (len > MAX_CHARS) {
				area.replaceRange("", 0, len - MAX_CHARS / 2);
			}
			area.setCaretPosition(area.getDocument().getLength());
		});
	}

	@Override
	public JComponent getComponent() {
		return root;
	}
}
