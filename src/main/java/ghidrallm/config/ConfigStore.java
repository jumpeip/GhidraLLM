package ghidrallm.config;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import com.google.gson.*;

/**
 * Loads/saves {@link LLMConfig} as JSON in {@code ~/.ghidrallm/config.json}.
 * One shared instance per JVM so every tool window sees the same providers.
 */
public final class ConfigStore {

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting()
			.disableHtmlEscaping()
			.serializeSpecialFloatingPointValues()
			.create();

	private static ConfigStore instance;

	private final Path file;
	private LLMConfig config;
	private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
	private String lastLoadError;

	public static synchronized ConfigStore getInstance() {
		if (instance == null) {
			String override = System.getProperty("ghidrallm.config");
			Path p = override != null && !override.isBlank() ? Paths.get(override)
					: Paths.get(System.getProperty("user.home"), ".ghidrallm", "config.json");
			instance = new ConfigStore(p);
		}
		return instance;
	}

	public ConfigStore(Path file) {
		this.file = file;
		load();
	}

	public Path getFile() {
		return file;
	}

	public String getLastLoadError() {
		return lastLoadError;
	}

	public synchronized LLMConfig get() {
		return config;
	}

	/** A deep copy that UI code can edit freely and later hand back to {@link #replace}. */
	public synchronized LLMConfig copy() {
		return GSON.fromJson(GSON.toJson(config), LLMConfig.class);
	}

	public static ProviderConfig copyOf(ProviderConfig p) {
		return GSON.fromJson(GSON.toJson(p), ProviderConfig.class);
	}

	public synchronized void replace(LLMConfig newConfig) throws IOException {
		newConfig.normalize();
		this.config = newConfig;
		save();
		fireChanged();
	}

	public synchronized void setActiveProvider(String id) {
		if (config.findById(id) == null) {
			return;
		}
		config.activeProviderId = id;
		trySave();
		fireChanged();
	}

	public synchronized void addProvider(ProviderConfig p, boolean makeActive) {
		p.clampValues();
		config.providers.add(p);
		if (makeActive) {
			config.activeProviderId = p.id;
		}
		trySave();
		fireChanged();
	}

	/** Persist analysis-only changes (e.g. the last used filter). */
	public synchronized void saveAnalysis(AnalysisSettings a) {
		a.clampValues();
		config.analysis = a;
		trySave();
		fireChanged();
	}

	public void addChangeListener(Runnable r) {
		listeners.add(r);
	}

	public void removeChangeListener(Runnable r) {
		listeners.remove(r);
	}

	private void fireChanged() {
		for (Runnable r : listeners) {
			try {
				r.run();
			}
			catch (Throwable t) {
				// a broken listener must not break config handling
			}
		}
	}

	private void load() {
		lastLoadError = null;
		if (Files.isRegularFile(file)) {
			try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
				LLMConfig c = GSON.fromJson(r, LLMConfig.class);
				if (c == null) {
					throw new JsonParseException("empty file");
				}
				c.normalize();
				config = c;
				return;
			}
			catch (Exception e) {
				lastLoadError = "Could not read " + file + ": " + e.getMessage() +
					" (a backup was made and defaults were loaded)";
				try {
					Files.copy(file, file.resolveSibling(file.getFileName() + ".corrupt-" +
						System.currentTimeMillis()), StandardCopyOption.REPLACE_EXISTING);
				}
				catch (IOException ignored) {
					// best effort
				}
			}
		}
		config = LLMConfig.createDefault();
		config.normalize();
		trySave();
	}

	private void trySave() {
		try {
			save();
		}
		catch (IOException e) {
			lastLoadError = "Could not save " + file + ": " + e.getMessage();
		}
	}

	private void save() throws IOException {
		Path dir = file.getParent();
		if (dir != null) {
			Files.createDirectories(dir);
		}
		Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
		try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
			GSON.toJson(config, w);
		}
		restrictPermissions(tmp);
		try {
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING,
				StandardCopyOption.ATOMIC_MOVE);
		}
		catch (AtomicMoveNotSupportedException e) {
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	private static void restrictPermissions(Path p) {
		try {
			Files.setPosixFilePermissions(p,
				EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
		}
		catch (UnsupportedOperationException | IOException e) {
			// Windows: rely on the user profile ACLs
		}
	}

	public static Gson gson() {
		return GSON;
	}
}
