package ghidrallm.ui;

import java.awt.*;
import java.nio.file.Paths;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.List;

import javax.swing.*;

import ghidrallm.config.ProviderConfig;
import ghidrallm.config.ProviderType;
import ghidrallm.llm.*;

/** Editor for a single {@link ProviderConfig}. */
class ProviderEditorPanel extends JPanel {

	private final JTextField name = new JTextField(30);
	private final JComboBox<ProviderType> type = new JComboBox<>(ProviderType.values());
	private final JTextField baseUrl = new JTextField(40);
	private final JComboBox<String> chatPath = new JComboBox<>();
	private final JComboBox<String> modelsPath = new JComboBox<>();
	private final JButton fromFullUrl = new JButton("Paste Full URL...");
	private final JTextField urlPreview = new JTextField(40);
	private final JComboBox<String> model = new JComboBox<>();
	private final JButton fetchModels = new JButton("Fetch Models");
	private final JPasswordField apiKey = new JPasswordField(36);
	private final JCheckBox showKey = new JCheckBox("Show");
	private final JTextArea extraHeaders = new JTextArea(3, 40);
	private final JTextArea extraBody = new JTextArea(3, 40);

	private final JSpinner maxOutputTokens = UiUtil.intSpinner(8192, 0, 10_000_000, 1024);
	private final JCheckBox useTemperature = new JCheckBox("Send temperature");
	private final JSpinner temperature = UiUtil.doubleSpinner(0.2, 0.0, 2.0, 0.05);
	private final JSpinner contextWindow =
		UiUtil.intSpinner(ProviderConfig.DEFAULT_CONTEXT_WINDOW, 1024, 100_000_000, 1000);
	private final JCheckBox sendNumCtx = new JCheckBox("Send as num_ctx (Ollama)");
	private final JCheckBox jsonMode = new JCheckBox("Request JSON response mode when renaming");

	private final JSpinner httpTimeout =
		UiUtil.intSpinner(ProviderConfig.DEFAULT_HTTP_TIMEOUT_SECONDS, 1, 86400, 10);
	private final JSpinner connectTimeout = UiUtil.intSpinner(30, 1, 3600, 5);
	private final JSpinner maxRetries =
		UiUtil.intSpinner(10, 0, ProviderConfig.MAX_RETRIES_LIMIT, 1);
	private final JSpinner retryInitial = UiUtil.intSpinner(2000, 0, 3_600_000, 500);
	private final JSpinner retryMax = UiUtil.intSpinner(60000, 0, 3_600_000, 1000);
	private final JCheckBox honorRetryAfter = new JCheckBox("Honor Retry-After header");
	private final JTextField retryStatus = new JTextField(24);
	private final JCheckBox retryNetwork = new JCheckBox("Retry on timeouts / connection errors");

	private final JCheckBox useClientCert = new JCheckBox("Use client certificate (PKI)");
	private final JTextField clientCertPath = new JTextField(34);
	private final JPasswordField clientCertPassword = new JPasswordField(20);
	private final JCheckBox useCustomCa = new JCheckBox("Use CA certificate");
	private final JTextField caCertPath = new JTextField(34);
	private final JCheckBox trustDefaultCas = new JCheckBox("Also trust Java's default CAs");
	private final JButton inspectPki = new JButton("Inspect Certificates");
	private final JButton testConnection = new JButton("Test Connection");

	private ProviderType lastType = ProviderType.ANTHROPIC;
	private ProviderConfig loaded;

	ProviderEditorPanel() {
		super(new BorderLayout());
		model.setEditable(true);
		model.setPrototypeDisplayValue("claude-sonnet-5-5-xxxxxxxxxxxxxxxxxxxx");
		chatPath.setEditable(true);
		modelsPath.setEditable(true);
		chatPath.setPrototypeDisplayValue(
			"/openai/deployments/{model}/chat/completions?api-version=2024-10-21xx");
		modelsPath.setPrototypeDisplayValue(chatPath.getPrototypeDisplayValue());
		urlPreview.setEditable(false);
		urlPreview.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));
		urlPreview.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
		extraHeaders.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
		extraBody.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
		char echo = apiKey.getEchoChar();
		showKey.addActionListener(e -> apiKey.setEchoChar(showKey.isSelected() ? 0 : echo));

		UiUtil.Form f = new UiUtil.Form();
		f.section("Connection");
		f.add("Name:", name);
		f.add("API type:", type);
		f.add("Base URL:", UiUtil.row(baseUrl, fromFullUrl),
			"Scheme, host and any path prefix, e.g. https://api.llama.com/compat or " +
				"https://my-gateway.corp/llm. 'Paste Full URL...' splits a complete URL for you.");
		f.add("Endpoint path:", chatPath,
			"<html>Appended to the Base URL. Pick a common path or type your own " +
				"(query strings allowed).<br>{model} is replaced with the model name " +
				"(e.g. Gemini, Azure deployments).<br>A full https://... URL here overrides " +
				"the Base URL.</html>");
		f.add("Models path:", modelsPath,
			"<html>Used by 'Fetch Models'. Blank = the API type's default.<br>" +
				"Relative to the Base URL, or a full https://... URL.</html>");
		f.add("Full URL:", urlPreview, "The exact URL requests are sent to.");
		JPanel modelRow = new JPanel(new BorderLayout(4, 0));
		modelRow.add(model, BorderLayout.CENTER);
		modelRow.add(fetchModels, BorderLayout.EAST);
		f.add("Model:", modelRow);
		f.add("API key:", UiUtil.row(apiKey, showKey),
			"Literal key, or env:VARIABLE_NAME to read it from the environment " +
				"(keeps the key out of the config file).");
		f.add("Extra headers:", new JScrollPane(extraHeaders),
			"One per line, 'Header-Name: value'. Values may use env:VAR.");
		f.add("Extra body JSON:", new JScrollPane(extraBody),
			"Optional JSON object merged into every request body, e.g. " +
				"{\"max_completion_tokens\": 8192, \"max_tokens\": null}");

		f.section("Model & generation");
		f.add("Max output tokens:", maxOutputTokens, "0 = don't send (Anthropic uses 8192)");
		f.add("Temperature (optional):", UiUtil.row(useTemperature, temperature),
			"Only sent when checked; otherwise the provider default is used.");
		f.add("Context window (tokens):", UiUtil.row(contextWindow, sendNumCtx),
			"Used to cap how much code/context is sent. Default 1,000,000.");
		f.add("", jsonMode);

		f.section("Network & retries");
		f.add("HTTP timeout (seconds):", httpTimeout, "Per-request read timeout. Default 180.");
		f.add("Connect timeout (seconds):", connectTimeout);
		f.add("Max retries:", maxRetries,
			"Retries for 429 / 5xx responses (0 - 20000). Use the arrows or type a number.");
		f.add("Initial retry delay (ms):", retryInitial,
			"Delay doubles each attempt (with jitter) up to the maximum.");
		f.add("Max retry delay (ms):", retryMax);
		f.add("Retry on HTTP status:", retryStatus, "Comma separated, e.g. 429,500,502,503,504,529");
		f.add("", honorRetryAfter);
		f.add("", retryNetwork);

		f.section("PKI / TLS (optional)");
		f.add("", useClientCert);
		f.add("Client cert (.p12/.pfx):", UiUtil.fileField(clientCertPath, this,
			"Select client certificate", "PKCS#12 (*.p12, *.pfx)", "p12", "pfx"));
		f.add("Certificate password:", clientCertPassword, "Literal password or env:VAR");
		f.add("", useCustomCa);
		f.add("CA certificate (.cer):", UiUtil.fileField(caCertPath, this,
			"Select CA certificate", "Certificates (*.cer, *.crt, *.pem, *.der)", "cer", "crt",
			"pem", "der"));
		f.add("", trustDefaultCas);
		f.full(UiUtil.row(inspectPki, testConnection));
		f.filler();

		JScrollPane scroll = new JScrollPane(f);
		scroll.getVerticalScrollBar().setUnitIncrement(16);
		scroll.setBorder(BorderFactory.createEmptyBorder());
		add(scroll, BorderLayout.CENTER);

		useTemperature.addActionListener(e -> updateEnablement());
		useClientCert.addActionListener(e -> updateEnablement());
		useCustomCa.addActionListener(e -> updateEnablement());
		type.addActionListener(e -> onTypeChanged());
		fromFullUrl.addActionListener(e -> pasteFullUrl());
		javax.swing.event.DocumentListener dl = new javax.swing.event.DocumentListener() {
			@Override
			public void insertUpdate(javax.swing.event.DocumentEvent e) {
				updatePreview();
			}

			@Override
			public void removeUpdate(javax.swing.event.DocumentEvent e) {
				updatePreview();
			}

			@Override
			public void changedUpdate(javax.swing.event.DocumentEvent e) {
				updatePreview();
			}
		};
		baseUrl.getDocument().addDocumentListener(dl);
		editorField(chatPath).getDocument().addDocumentListener(dl);
		editorField(model).getDocument().addDocumentListener(dl);
		chatPath.addActionListener(e -> updatePreview());
		fetchModels.addActionListener(e -> fetchModels());
		inspectPki.addActionListener(e -> inspectPki());
		testConnection.addActionListener(e -> testConnection());
	}

	void load(ProviderConfig p) {
		loaded = p;
		name.setText(p.name);
		lastType = p.type;
		type.setSelectedItem(p.type);
		baseUrl.setText(p.baseUrl);
		setPresets(chatPath, p.type.getChatPathPresets(), false);
		setComboText(chatPath, p.chatPath);
		setPresets(modelsPath, p.type.getModelsPathPresets(), true);
		setComboText(modelsPath, p.modelsPath);
		model.removeAllItems();
		if (p.model != null && !p.model.isBlank()) {
			model.addItem(p.model);
		}
		model.setSelectedItem(p.model);
		apiKey.setText(p.apiKey);
		extraHeaders.setText(p.extraHeaders);
		extraBody.setText(p.extraBodyJson);
		maxOutputTokens.setValue(p.maxOutputTokens);
		useTemperature.setSelected(p.useTemperature);
		temperature.setValue(p.temperature);
		contextWindow.setValue(p.contextWindowTokens);
		sendNumCtx.setSelected(p.sendContextWindowToServer);
		jsonMode.setSelected(p.jsonMode);
		httpTimeout.setValue(p.httpTimeoutSeconds);
		connectTimeout.setValue(p.connectTimeoutSeconds);
		maxRetries.setValue(p.maxRetries);
		retryInitial.setValue(p.retryInitialDelayMs);
		retryMax.setValue(p.retryMaxDelayMs);
		honorRetryAfter.setSelected(p.honorRetryAfter);
		retryStatus.setText(p.retryOnStatus);
		retryNetwork.setSelected(p.retryOnNetworkError);
		useClientCert.setSelected(p.useClientCertificate);
		clientCertPath.setText(p.clientCertPath);
		clientCertPassword.setText(p.clientCertPassword);
		useCustomCa.setSelected(p.useCustomCa);
		caCertPath.setText(p.caCertPath);
		trustDefaultCas.setSelected(p.trustDefaultCas);
		updateEnablement();
		updatePreview();
	}

	/** Copies the UI state into the given config. */
	void store(ProviderConfig p) {
		p.name = name.getText().trim().isEmpty() ? "Unnamed" : name.getText().trim();
		p.type = (ProviderType) type.getSelectedItem();
		p.baseUrl = baseUrl.getText().trim();
		p.chatPath = comboText(chatPath);
		p.modelsPath = comboText(modelsPath);
		p.endpoint = "";
		p.modelsEndpoint = "";
		Object m = model.isEditable() ? model.getEditor().getItem() : model.getSelectedItem();
		p.model = m == null ? "" : m.toString().trim();
		p.apiKey = new String(apiKey.getPassword()).trim();
		p.extraHeaders = extraHeaders.getText();
		p.extraBodyJson = extraBody.getText().trim();
		p.maxOutputTokens = UiUtil.intValue(maxOutputTokens);
		p.useTemperature = useTemperature.isSelected();
		p.temperature = UiUtil.doubleValue(temperature);
		p.contextWindowTokens = UiUtil.intValue(contextWindow);
		p.sendContextWindowToServer = sendNumCtx.isSelected();
		p.jsonMode = jsonMode.isSelected();
		p.httpTimeoutSeconds = UiUtil.intValue(httpTimeout);
		p.connectTimeoutSeconds = UiUtil.intValue(connectTimeout);
		p.maxRetries = UiUtil.intValue(maxRetries);
		p.retryInitialDelayMs = UiUtil.intValue(retryInitial);
		p.retryMaxDelayMs = UiUtil.intValue(retryMax);
		p.honorRetryAfter = honorRetryAfter.isSelected();
		p.retryOnStatus = retryStatus.getText().trim();
		p.retryOnNetworkError = retryNetwork.isSelected();
		p.useClientCertificate = useClientCert.isSelected();
		p.clientCertPath = clientCertPath.getText().trim();
		p.clientCertPassword = new String(clientCertPassword.getPassword());
		p.useCustomCa = useCustomCa.isSelected();
		p.caCertPath = caCertPath.getText().trim();
		p.trustDefaultCas = trustDefaultCas.isSelected();
		p.clampValues();
	}

	ProviderConfig getLoaded() {
		return loaded;
	}

	/** Returns a problem description, or null if the form is valid. */
	String validateForm() {
		String full = ProviderConfig.join(baseUrl.getText(), comboText(chatPath));
		if (!ProviderConfig.isAbsolute(full)) {
			return "Base URL must start with http:// or https:// (or put a full URL in " +
				"Endpoint path).";
		}
		if (full.contains(" ")) {
			return "The endpoint URL contains spaces: " + full;
		}
		if (useClientCert.isSelected() && clientCertPath.getText().isBlank()) {
			return "Select a .p12/.pfx file or untick 'Use client certificate'.";
		}
		if (useCustomCa.isSelected() && caCertPath.getText().isBlank()) {
			return "Select a CA certificate or untick 'Use CA certificate'.";
		}
		if (!extraBody.getText().isBlank()) {
			try {
				if (!com.google.gson.JsonParser.parseString(extraBody.getText()).isJsonObject()) {
					return "Extra body JSON must be a JSON object.";
				}
			}
			catch (Exception e) {
				return "Extra body JSON is invalid: " + e.getMessage();
			}
		}
		return null;
	}

	private void onTypeChanged() {
		ProviderType t = (ProviderType) type.getSelectedItem();
		if (t == null || t == lastType) {
			return;
		}
		String base = baseUrl.getText().trim();
		boolean defaultBase = base.isEmpty();
		for (ProviderType pt : ProviderType.values()) {
			defaultBase |= base.equals(pt.getDefaultBaseUrl());
		}
		if (defaultBase) {
			baseUrl.setText(t.getDefaultBaseUrl());
		}
		String path = comboText(chatPath);
		boolean presetPath = path.isEmpty() || lastType.getChatPathPresets().contains(path);
		setPresets(chatPath, t.getChatPathPresets(), false);
		setComboText(chatPath, presetPath ? t.getDefaultChatPath() : path);
		String mp = comboText(modelsPath);
		boolean presetModels = mp.isEmpty() || lastType.getModelsPathPresets().contains(mp);
		setPresets(modelsPath, t.getModelsPathPresets(), true);
		setComboText(modelsPath, presetModels ? "" : mp);
		if (t == ProviderType.OLLAMA || t == ProviderType.GEMINI) {
			jsonMode.setSelected(true);
		}
		lastType = t;
		updateEnablement();
		updatePreview();
	}

	private void pasteFullUrl() {
		String url = JOptionPane.showInputDialog(this,
			"Paste the complete endpoint URL (it will be split into Base URL + Endpoint path):",
			ProviderConfig.join(baseUrl.getText(), comboText(chatPath)));
		if (url == null || url.isBlank()) {
			return;
		}
		String[] parts = ProviderConfig.split(url.trim(), (ProviderType) type.getSelectedItem());
		baseUrl.setText(parts[0]);
		setComboText(chatPath, parts[1]);
		updatePreview();
	}

	private void updatePreview() {
		ProviderConfig tmp = new ProviderConfig();
		tmp.type = (ProviderType) type.getSelectedItem();
		tmp.model = comboText(model);
		String full = ProviderConfig.join(baseUrl.getText(), comboText(chatPath));
		urlPreview.setText(LLMClient.substituteModel(full, tmp));
		urlPreview.setCaretPosition(0);
	}

	private static JTextField editorField(JComboBox<String> combo) {
		return (JTextField) combo.getEditor().getEditorComponent();
	}

	private static String comboText(JComboBox<String> combo) {
		Object o = combo.isEditable() ? editorField(combo).getText() : combo.getSelectedItem();
		return o == null ? "" : o.toString().trim();
	}

	private static void setComboText(JComboBox<String> combo, String text) {
		combo.setSelectedItem(text == null ? "" : text);
		editorField(combo).setText(text == null ? "" : text);
	}

	private static void setPresets(JComboBox<String> combo, List<String> presets,
			boolean includeBlank) {
		String keep = comboText(combo);
		combo.removeAllItems();
		if (includeBlank) {
			combo.addItem("");
		}
		for (String s : presets) {
			combo.addItem(s);
		}
		setComboText(combo, keep);
	}

	private void updateEnablement() {
		temperature.setEnabled(useTemperature.isSelected());
		boolean cc = useClientCert.isSelected();
		clientCertPath.setEnabled(cc);
		clientCertPassword.setEnabled(cc);
		boolean ca = useCustomCa.isSelected();
		caCertPath.setEnabled(ca);
		trustDefaultCas.setEnabled(ca);
		sendNumCtx.setEnabled(type.getSelectedItem() == ProviderType.OLLAMA);
	}

	private ProviderConfig snapshot() {
		ProviderConfig p = new ProviderConfig();
		if (loaded != null) {
			p.id = loaded.id;
		}
		store(p);
		return p;
	}

	private void fetchModels() {
		ProviderConfig p = snapshot();
		fetchModels.setEnabled(false);
		fetchModels.setText("Fetching...");
		new SwingWorker<List<String>, Void>() {
			@Override
			protected List<String> doInBackground() throws Exception {
				HttpTransport.clearClientCache();
				return LLMClient.listModels(p, CancelCheck.NEVER, null);
			}

			@Override
			protected void done() {
				fetchModels.setEnabled(true);
				fetchModels.setText("Fetch Models");
				try {
					List<String> models = get();
					Object current = model.getEditor().getItem();
					model.removeAllItems();
					for (String m : models) {
						model.addItem(m);
					}
					model.setSelectedItem(current);
					if (models.isEmpty()) {
						message("The provider returned no models.", JOptionPane.WARNING_MESSAGE);
					}
					else {
						model.showPopup();
					}
				}
				catch (Exception e) {
					message("Could not fetch models:\n" + rootMessage(e),
						JOptionPane.ERROR_MESSAGE);
				}
			}
		}.execute();
	}

	private void testConnection() {
		String problem = validateForm();
		if (problem != null) {
			message(problem, JOptionPane.WARNING_MESSAGE);
			return;
		}
		ProviderConfig p = snapshot();
		p.maxRetries = Math.min(p.maxRetries, 2);
		testConnection.setEnabled(false);
		testConnection.setText("Testing...");
		long start = System.currentTimeMillis();
		new SwingWorker<LLMResponse, Void>() {
			@Override
			protected LLMResponse doInBackground() throws Exception {
				HttpTransport.clearClientCache();
				return LLMClient.chat(p,
					LLMRequest.single("You are a connectivity test.",
						"Reply with exactly: OK", false),
					CancelCheck.NEVER, null);
			}

			@Override
			protected void done() {
				testConnection.setEnabled(true);
				testConnection.setText("Test Connection");
				try {
					LLMResponse r = get();
					long ms = System.currentTimeMillis() - start;
					message("Success in " + ms + " ms.\n\nReply: " +
						abbreviate(r.getText(), 200) + "\n" + r.usageSummary(),
						JOptionPane.INFORMATION_MESSAGE);
				}
				catch (Exception e) {
					message("Connection test failed:\n" + rootMessage(e),
						JOptionPane.ERROR_MESSAGE);
				}
			}
		}.execute();
	}

	private void inspectPki() {
		StringBuilder sb = new StringBuilder();
		try {
			if (!clientCertPath.getText().isBlank()) {
				sb.append("Client certificate: ").append(clientCertPath.getText().trim())
						.append('\n');
				KeyStore ks = KeyStore.getInstance("PKCS12");
				char[] pwd = ProviderConfig
						.resolveSecret(new String(clientCertPassword.getPassword()))
						.toCharArray();
				try (var in = java.nio.file.Files
						.newInputStream(Paths.get(clientCertPath.getText().trim()))) {
					ks.load(in, pwd);
				}
				for (Enumeration<String> en = ks.aliases(); en.hasMoreElements();) {
					String alias = en.nextElement();
					java.security.cert.Certificate c = ks.getCertificate(alias);
					sb.append("  alias '").append(alias).append("'")
							.append(ks.isKeyEntry(alias) ? " [private key]" : "")
							.append('\n');
					if (c instanceof X509Certificate x) {
						describe(sb, x);
					}
				}
			}
			if (!caCertPath.getText().isBlank()) {
				sb.append("\nCA certificate file: ").append(caCertPath.getText().trim())
						.append('\n');
				for (X509Certificate x : SslContextFactory
						.loadCertificates(Paths.get(caCertPath.getText().trim()))) {
					describe(sb, x);
				}
			}
			if (sb.length() == 0) {
				sb.append("No certificate files selected.");
			}
			JTextArea ta = new JTextArea(sb.toString(), 16, 70);
			ta.setEditable(false);
			ta.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
			JOptionPane.showMessageDialog(this, new JScrollPane(ta), "Certificates",
				JOptionPane.INFORMATION_MESSAGE);
		}
		catch (Exception e) {
			message("Could not read certificate: " + rootMessage(e), JOptionPane.ERROR_MESSAGE);
		}
	}

	private static void describe(StringBuilder sb, X509Certificate x) {
		sb.append("    Subject: ").append(x.getSubjectX500Principal().getName()).append('\n');
		sb.append("    Issuer:  ").append(x.getIssuerX500Principal().getName()).append('\n');
		sb.append("    Valid:   ").append(x.getNotBefore()).append("  ->  ")
				.append(x.getNotAfter());
		try {
			x.checkValidity();
		}
		catch (Exception e) {
			sb.append("   ** NOT CURRENTLY VALID **");
		}
		sb.append('\n');
	}

	private void message(String msg, int type) {
		JOptionPane.showMessageDialog(this, msg, "GhidraLLM", type);
	}

	static String rootMessage(Throwable e) {
		Throwable t = e;
		while ((t instanceof java.util.concurrent.ExecutionException ||
			t instanceof java.lang.reflect.InvocationTargetException) && t.getCause() != null) {
			t = t.getCause();
		}
		String m = t.getMessage();
		return m == null ? t.toString() : m;
	}

	static String abbreviate(String s, int max) {
		return s.length() <= max ? s : s.substring(0, max) + "...";
	}
}
