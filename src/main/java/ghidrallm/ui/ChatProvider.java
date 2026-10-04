package ghidrallm.ui;

import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.swing.*;
import javax.swing.text.*;

import ghidra.framework.plugintool.ComponentProviderAdapter;
import ghidra.framework.plugintool.PluginTool;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.Program;
import ghidra.util.task.TaskMonitor;
import ghidrallm.analysis.*;
import ghidrallm.config.*;
import ghidrallm.llm.*;

/** Dockable chat window for asking the selected LLM questions about the binary. */
public class ChatProvider extends ComponentProviderAdapter {

	/** What the chat needs from the plugin. */
	public interface Host {
		Program currentProgram();

		Function currentFunction();

		void showConfig(boolean addNewProvider);

		void log(String msg);
	}

	private final Host host;
	private final ConfigStore store;
	private final JPanel root = new JPanel(new BorderLayout(4, 4));
	private final JComboBox<ProviderConfig> providerCombo = new JComboBox<>();
	private final JCheckBox includeFunction = new JCheckBox("Include current function", true);
	private final JCheckBox includeXrefs = new JCheckBox("with callers/callees", false);
	private final JTextPane transcript = new JTextPane();
	private final JTextArea input = new JTextArea(4, 60);
	private final JButton send = new JButton("Send");
	private final JButton stop = new JButton("Stop");
	private final JLabel status = new JLabel(" ");

	private final List<ChatMessage> history = new ArrayList<>();
	private final StringBuilder plainTranscript = new StringBuilder();
	private final AtomicBoolean cancel = new AtomicBoolean();
	private SwingWorker<?, ?> running;
	private boolean refreshing;
	private final Runnable configListener = () -> SwingUtilities.invokeLater(this::refreshProviders);

	public ChatProvider(PluginTool tool, String owner, Host host, ConfigStore store) {
		super(tool, "GhidraLLM Chat", owner);
		this.host = host;
		this.store = store;
		setTitle("GhidraLLM Chat");
		build();
		refreshProviders();
		store.addChangeListener(configListener);
		setDefaultWindowPosition(docking.WindowPosition.RIGHT);
		addToTool();
	}

	private void build() {
		transcript.setEditable(false);
		transcript.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
		input.setLineWrap(true);
		input.setWrapStyleWord(true);
		input.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));

		JButton add = new JButton("+");
		add.setToolTipText("Add a new LLM provider");
		add.addActionListener(e -> host.showConfig(true));
		JButton cfg = new JButton("Configure...");
		cfg.addActionListener(e -> host.showConfig(false));
		providerCombo.addActionListener(e -> {
			if (!refreshing && providerCombo.getSelectedItem() instanceof ProviderConfig p) {
				store.setActiveProvider(p.id);
			}
		});
		includeFunction.addActionListener(e -> includeXrefs.setEnabled(includeFunction.isSelected()));

		JPanel north = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
		north.add(new JLabel("LLM:"));
		north.add(providerCombo);
		north.add(add);
		north.add(cfg);
		north.add(includeFunction);
		north.add(includeXrefs);

		JButton clear = new JButton("New Chat");
		clear.addActionListener(e -> clear());
		JButton copy = new JButton("Copy Transcript");
		copy.addActionListener(e -> Toolkit.getDefaultToolkit()
				.getSystemClipboard()
				.setContents(new StringSelection(plainTranscript.toString()), null));
		send.addActionListener(e -> send());
		stop.addActionListener(e -> cancel.set(true));
		stop.setEnabled(false);

		input.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER,
			InputEvent.CTRL_DOWN_MASK), "ghidrallm-send");
		input.getActionMap().put("ghidrallm-send", new AbstractAction() {
			@Override
			public void actionPerformed(ActionEvent e) {
				send();
			}
		});

		JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 2));
		buttons.add(status);
		buttons.add(clear);
		buttons.add(copy);
		buttons.add(stop);
		buttons.add(send);

		JPanel south = new JPanel(new BorderLayout(2, 2));
		JScrollPane inScroll = new JScrollPane(input);
		inScroll.setBorder(BorderFactory.createTitledBorder("Ask a question (Ctrl+Enter to send)"));
		south.add(inScroll, BorderLayout.CENTER);
		south.add(buttons, BorderLayout.SOUTH);

		JSplitPane split =
			new JSplitPane(JSplitPane.VERTICAL_SPLIT, new JScrollPane(transcript), south);
		split.setResizeWeight(0.8);

		root.add(north, BorderLayout.NORTH);
		root.add(split, BorderLayout.CENTER);
		root.setPreferredSize(new Dimension(640, 700));
	}

	private void refreshProviders() {
		refreshing = true;
		try {
			LLMConfig c = store.get();
			providerCombo.removeAllItems();
			for (ProviderConfig p : c.providers) {
				providerCombo.addItem(p);
			}
			ProviderConfig active = c.getActiveProvider();
			if (active != null) {
				for (int i = 0; i < providerCombo.getItemCount(); i++) {
					if (providerCombo.getItemAt(i).id.equals(active.id)) {
						providerCombo.setSelectedIndex(i);
					}
				}
			}
		}
		finally {
			refreshing = false;
		}
	}

	private void clear() {
		if (running != null) {
			cancel.set(true);
		}
		history.clear();
		plainTranscript.setLength(0);
		transcript.setText("");
		status.setText(" ");
	}

	private void send() {
		String question = input.getText().strip();
		if (question.isEmpty() || running != null) {
			return;
		}
		ProviderConfig provider = store.get().getActiveProvider();
		if (provider == null) {
			append("Error", "No provider configured.", Color.RED.darker());
			return;
		}
		ProviderConfig p = ConfigStore.copyOf(provider);
		AnalysisSettings s = store.copy().analysis;
		boolean withFunction = includeFunction.isSelected();
		boolean withXrefs = includeXrefs.isSelected();
		Program program = host.currentProgram();
		Function function = withFunction ? host.currentFunction() : null;

		input.setText("");
		append("You", question, new Color(0x1f5fbf));
		cancel.set(false);
		send.setEnabled(false);
		stop.setEnabled(true);
		status.setText("Waiting for " + p.name + "...");

		running = new SwingWorker<LLMResponse, String>() {
			private String sentUserMessage;

			@Override
			protected LLMResponse doInBackground() throws Exception {
				String userMsg = question;
				if (function != null && program != null) {
					publish("Gathering context for " + function.getName() + "...");
					if (!withXrefs) {
						s.callerDepth = 0;
						s.calleeDepth = 0;
					}
					try (CodeExtractor ex = new CodeExtractor(program, s.decompileTimeoutSeconds)) {
						FunctionContext ctx =
							new ContextBuilder(s, ex).build(function, TaskMonitor.DUMMY);
						userMsg = PromptBuilder.chatContext(ctx, s) + "\n\n## QUESTION\n" + question;
					}
				}
				sentUserMessage = userMsg;
				List<ChatMessage> msgs = new ArrayList<>(history);
				msgs.add(ChatMessage.user(userMsg));
				trimToBudget(msgs, p);
				publish("Waiting for " + p.name + "...");
				return LLMClient.chat(p, new LLMRequest(systemPrompt(s), msgs, false),
					cancel::get, this::publish);
			}

			@Override
			protected void process(List<String> chunks) {
				status.setText(chunks.get(chunks.size() - 1));
			}

			@Override
			protected void done() {
				running = null;
				send.setEnabled(true);
				stop.setEnabled(false);
				try {
					LLMResponse r = get();
					history.add(ChatMessage.user(sentUserMessage));
					history.add(ChatMessage.assistant(r.getText()));
					append(p.name, r.getText(), new Color(0x2e7d32));
					status.setText(r.usageSummary());
				}
				catch (Exception e) {
					String m = ProviderEditorPanel.rootMessage(e);
					boolean cancelled = cancel.get() || m.equals("Cancelled");
					append(cancelled ? "Stopped" : "Error", cancelled ? "Request cancelled." : m,
						Color.RED.darker());
					status.setText(" ");
					if (!cancelled) {
						host.log("Chat error: " + m);
					}
				}
			}
		};
		running.execute();
	}

	static String systemPrompt(AnalysisSettings s) {
		String base = "You are an expert reverse engineer and malware analyst assisting a user " +
			"inside Ghidra. When code context is supplied, ground your answers in it, cite " +
			"addresses and function names, and say when something is uncertain. Be concise " +
			"and technical.";
		if (s.additionalInstructions != null && !s.additionalInstructions.isBlank()) {
			base += "\n\nAdditional instructions from the analyst:\n" + s.additionalInstructions;
		}
		return base;
	}

	/** Drops the oldest turns until the conversation fits the provider's context window. */
	static void trimToBudget(List<ChatMessage> msgs, ProviderConfig p) {
		long budget = p.getPromptCharBudget() - 2000;
		long total = 0;
		for (ChatMessage m : msgs) {
			total += m.getContent().length();
		}
		while (msgs.size() > 1 && total > budget) {
			total -= msgs.remove(0).getContent().length();
		}
	}

	private void append(String who, String text, Color color) {
		StyledDocument doc = transcript.getStyledDocument();
		SimpleAttributeSet head = new SimpleAttributeSet();
		StyleConstants.setBold(head, true);
		StyleConstants.setForeground(head, color);
		SimpleAttributeSet body = new SimpleAttributeSet();
		SimpleAttributeSet code = new SimpleAttributeSet();
		StyleConstants.setFontFamily(code, Font.MONOSPACED);
		StyleConstants.setBackground(code, UIManager.getColor("TextArea.background") != null
				? UIManager.getColor("TextArea.background")
				: new Color(0xf4f4f4));
		try {
			doc.insertString(doc.getLength(), (doc.getLength() > 0 ? "\n" : "") + who + ":\n",
				head);
			boolean inCode = false;
			for (String line : text.split("\n", -1)) {
				if (line.strip().startsWith("```")) {
					inCode = !inCode;
					continue;
				}
				doc.insertString(doc.getLength(), line + "\n", inCode ? code : body);
			}
		}
		catch (BadLocationException e) {
			// cannot happen when appending
		}
		plainTranscript.append(who).append(":\n").append(text).append("\n\n");
		transcript.setCaretPosition(doc.getLength());
	}

	@Override
	public JComponent getComponent() {
		return root;
	}

	public void dispose() {
		cancel.set(true);
		store.removeChangeListener(configListener);
		removeFromTool();
	}
}
