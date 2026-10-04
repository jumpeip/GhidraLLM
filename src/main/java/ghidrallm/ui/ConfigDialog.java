package ghidrallm.ui;

import java.awt.*;
import java.io.IOException;

import javax.swing.*;

import docking.DialogComponentProvider;
import ghidrallm.config.*;
import ghidrallm.llm.HttpTransport;

/** "LLM Configuration" dialog: provider list + editor, and the analysis settings tab. */
public class ConfigDialog extends DialogComponentProvider {

	private final ConfigStore store;
	private final LLMConfig work;
	private final DefaultListModel<ProviderConfig> listModel = new DefaultListModel<>();
	private final JList<ProviderConfig> list = new JList<>(listModel);
	private final ProviderEditorPanel editor = new ProviderEditorPanel();
	private final AnalysisSettingsPanel analysisPanel = new AnalysisSettingsPanel();
	private final JTabbedPane tabs = new JTabbedPane();
	private ProviderConfig editing;
	private boolean adjusting;

	public ConfigDialog(ConfigStore store, boolean addNewProvider) {
		super("GhidraLLM - LLM Configuration", true, true, true, false);
		this.store = store;
		this.work = store.copy();
		addWorkPanel(buildPanel());
		addOKButton();
		addApplyButton();
		addCancelButton();
		setRememberSize(true);
		setPreferredSize(1050, 760);

		for (ProviderConfig p : work.providers) {
			listModel.addElement(p);
		}
		analysisPanel.load(work.analysis);
		if (addNewProvider) {
			addProvider();
		}
		else {
			ProviderConfig active = work.getActiveProvider();
			list.setSelectedValue(active, true);
		}
		if (store.getLastLoadError() != null) {
			setStatusText(store.getLastLoadError());
		}
		else {
			setStatusText("Config file: " + store.getFile());
		}
	}

	private JComponent buildPanel() {
		list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		list.setCellRenderer(new DefaultListCellRenderer() {
			@Override
			public Component getListCellRendererComponent(JList<?> l, Object value, int index,
					boolean sel, boolean focus) {
				ProviderConfig p = (ProviderConfig) value;
				boolean active = p.id.equals(work.activeProviderId);
				String text = (active ? "★ " : "    ") + p.name;
				JLabel c = (JLabel) super.getListCellRendererComponent(l, text, index, sel, focus);
				c.setToolTipText(p.type + " - " + p.model);
				if (active) {
					c.setFont(c.getFont().deriveFont(Font.BOLD));
				}
				return c;
			}
		});
		list.addListSelectionListener(e -> {
			if (!e.getValueIsAdjusting() && !adjusting) {
				switchTo(list.getSelectedValue());
			}
		});

		JButton add = new JButton("Add New Provider");
		JButton dup = new JButton("Duplicate");
		JButton remove = new JButton("Remove");
		JButton up = new JButton("Up");
		JButton down = new JButton("Down");
		JButton setActive = new JButton("Set as Active");
		JButton defaults = new JButton("Add Missing Defaults");
		add.addActionListener(e -> addProvider());
		dup.addActionListener(e -> duplicate());
		remove.addActionListener(e -> remove());
		up.addActionListener(e -> move(-1));
		down.addActionListener(e -> move(1));
		setActive.addActionListener(e -> {
			ProviderConfig p = list.getSelectedValue();
			if (p != null) {
				work.activeProviderId = p.id;
				list.repaint();
			}
		});
		defaults.addActionListener(e -> addDefaults());

		JPanel buttons = new JPanel(new GridLayout(0, 1, 2, 2));
		buttons.add(add);
		buttons.add(dup);
		buttons.add(remove);
		JPanel updown = new JPanel(new GridLayout(1, 2, 2, 2));
		updown.add(up);
		updown.add(down);
		buttons.add(updown);
		buttons.add(setActive);
		buttons.add(defaults);

		JPanel left = new JPanel(new BorderLayout(4, 4));
		left.add(new JLabel("Providers (★ = active)"), BorderLayout.NORTH);
		left.add(new JScrollPane(list), BorderLayout.CENTER);
		left.add(buttons, BorderLayout.SOUTH);
		left.setPreferredSize(new Dimension(250, 400));

		JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, editor);
		split.setResizeWeight(0);
		split.setDividerLocation(260);

		tabs.addTab("LLM Providers", split);
		tabs.addTab("Analysis & Context", analysisPanel);
		return tabs;
	}

	private void switchTo(ProviderConfig p) {
		if (editing != null && editing != p) {
			editor.store(editing);
			int i = listModel.indexOf(editing);
			if (i >= 0) {
				listModel.set(i, editing); // refresh name in the list
			}
		}
		editing = p;
		if (p != null) {
			editor.load(p);
		}
		editor.setVisible(p != null);
	}

	private void addProvider() {
		ProviderConfig p = new ProviderConfig();
		p.name = uniqueName("New Provider");
		p.type = ProviderType.OPENAI_COMPATIBLE;
		p.baseUrl = ProviderType.OPENAI_COMPATIBLE.getDefaultBaseUrl();
		p.chatPath = ProviderType.OPENAI_COMPATIBLE.getDefaultChatPath();
		work.providers.add(p);
		listModel.addElement(p);
		list.setSelectedValue(p, true);
		tabs.setSelectedIndex(0);
		setStatusText("Fill in the new provider, then press OK or Apply.");
	}

	private void duplicate() {
		ProviderConfig src = list.getSelectedValue();
		if (src == null) {
			return;
		}
		editor.store(src);
		ProviderConfig copy = ConfigStore.copyOf(src);
		copy.id = java.util.UUID.randomUUID().toString();
		copy.name = uniqueName(src.name + " copy");
		work.providers.add(copy);
		listModel.addElement(copy);
		list.setSelectedValue(copy, true);
	}

	private void remove() {
		ProviderConfig p = list.getSelectedValue();
		if (p == null) {
			return;
		}
		if (listModel.size() <= 1) {
			setStatusText("At least one provider is required.");
			return;
		}
		int idx = list.getSelectedIndex();
		editing = null;
		work.providers.remove(p);
		listModel.removeElement(p);
		if (p.id.equals(work.activeProviderId)) {
			work.activeProviderId = work.providers.get(0).id;
		}
		list.setSelectedIndex(Math.min(idx, listModel.size() - 1));
	}

	private void move(int delta) {
		int i = list.getSelectedIndex();
		int j = i + delta;
		if (i < 0 || j < 0 || j >= listModel.size()) {
			return;
		}
		editor.store(listModel.get(i));
		adjusting = true;
		ProviderConfig p = listModel.remove(i);
		listModel.add(j, p);
		work.providers.remove(p);
		work.providers.add(j, p);
		list.setSelectedIndex(j);
		adjusting = false;
	}

	private void addDefaults() {
		int added = 0;
		for (ProviderConfig d : LLMConfig.createDefaultProviders()) {
			boolean exists = false;
			for (ProviderConfig p : work.providers) {
				exists |= p.name.equalsIgnoreCase(d.name);
			}
			if (!exists) {
				work.providers.add(d);
				listModel.addElement(d);
				added++;
			}
		}
		setStatusText(added == 0 ? "All default providers are already present."
				: "Added " + added + " default provider(s).");
	}

	private String uniqueName(String base) {
		String n = base;
		int i = 2;
		while (nameTaken(n)) {
			n = base + " " + (i++);
		}
		return n;
	}

	private boolean nameTaken(String n) {
		for (ProviderConfig p : work.providers) {
			if (p.name.equalsIgnoreCase(n)) {
				return true;
			}
		}
		return false;
	}

	private boolean commit() {
		if (editing != null) {
			String problem = editor.validateForm();
			if (problem != null) {
				tabs.setSelectedIndex(0);
				setStatusText(editing.name + ": " + problem);
				return false;
			}
			editor.store(editing);
			list.repaint();
		}
		analysisPanel.store(work.analysis);
		try {
			LLMConfig toSave = ConfigStore.gson().fromJson(ConfigStore.gson().toJson(work),
				LLMConfig.class);
			store.replace(toSave);
			HttpTransport.clearClientCache();
			setStatusText("Saved to " + store.getFile());
			return true;
		}
		catch (IOException e) {
			setStatusText("Save failed: " + e.getMessage());
			return false;
		}
	}

	@Override
	protected void applyCallback() {
		commit();
	}

	@Override
	protected void okCallback() {
		if (commit()) {
			close();
		}
	}

	@Override
	protected void cancelCallback() {
		close();
	}
}
