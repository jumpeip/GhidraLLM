package ghidrallm.analysis;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.Program;
import ghidra.util.Msg;
import ghidra.util.task.Task;
import ghidra.util.task.TaskMonitor;
import ghidrallm.config.AnalysisSettings;
import ghidrallm.config.ProviderConfig;
import ghidrallm.llm.LLMException;

/** Renames many functions in the background, optionally with parallel LLM requests. */
public class BatchRenameTask extends Task {

	/** HTTP statuses that mean "your configuration is wrong" - stop instead of retrying 10k times. */
	private static final Set<Integer> FATAL = Set.of(400, 401, 402, 403, 404, 405, 413, 422);

	private final Program program;
	private final List<Function> functions;
	private final ProviderConfig provider;
	private final AnalysisSettings settings;
	private final Consumer<String> log;
	private final Object applyLock = new Object();

	private final AtomicInteger renamed = new AtomicInteger();
	private final AtomicInteger unchanged = new AtomicInteger();
	private final AtomicInteger failed = new AtomicInteger();
	private final AtomicInteger varsRenamed = new AtomicInteger();
	private final AtomicBoolean aborted = new AtomicBoolean();
	private volatile String abortReason;

	public BatchRenameTask(Program program, List<Function> functions, ProviderConfig provider,
			AnalysisSettings settings, Consumer<String> log) {
		super("GhidraLLM: renaming " + functions.size() + " function(s)", true, true, false);
		this.program = program;
		this.functions = new ArrayList<>(functions);
		this.provider = provider;
		this.settings = settings;
		this.log = log;
	}

	@Override
	public void run(TaskMonitor monitor) {
		long start = System.currentTimeMillis();
		List<Function> order = functions;
		if (settings.bottomUpOrder && functions.size() > 1) {
			monitor.setMessage("Ordering functions bottom-up...");
			order = FunctionFilter.bottomUp(functions, monitor);
		}
		List<Address> entries = new ArrayList<>(order.size());
		for (Function f : order) {
			entries.add(f.getEntryPoint());
		}
		monitor.initialize(entries.size());
		log.accept(String.format("Batch started: %d function(s) via %s [%s], %d worker(s)",
			entries.size(), provider.name, provider.model, settings.concurrentRequests));

		int workers = Math.max(1, Math.min(settings.concurrentRequests, entries.size()));
		if (workers == 1) {
			try (CodeExtractor ex = new CodeExtractor(program, settings.decompileTimeoutSeconds)) {
				for (Address a : entries) {
					if (monitor.isCancelled() || aborted.get()) {
						break;
					}
					processOne(a, ex, monitor);
					monitor.incrementProgress(1);
				}
			}
		}
		else {
			runParallel(entries, workers, monitor);
		}

		long secs = (System.currentTimeMillis() - start) / 1000;
		String summary = String.format(
			"Batch %s: %d renamed, %d unchanged, %d failed, %d variables renamed (%dm %02ds)",
			aborted.get() ? "ABORTED" : monitor.isCancelled() ? "cancelled" : "finished",
			renamed.get(), unchanged.get(), failed.get(), varsRenamed.get(), secs / 60, secs % 60);
		log.accept(summary);
		if (aborted.get()) {
			Msg.showError(this, null, "GhidraLLM", summary + "\n\n" + abortReason);
		}
		else {
			Msg.showInfo(this, null, "GhidraLLM", summary);
		}
	}

	private void runParallel(List<Address> entries, int workers, TaskMonitor monitor) {
		ExecutorService pool = Executors.newFixedThreadPool(workers, r -> {
			Thread t = new Thread(r, "GhidraLLM-worker");
			t.setDaemon(true);
			return t;
		});
		List<CodeExtractor> extractors = Collections.synchronizedList(new ArrayList<>());
		ThreadLocal<CodeExtractor> local = ThreadLocal.withInitial(() -> {
			CodeExtractor ex = new CodeExtractor(program, settings.decompileTimeoutSeconds);
			extractors.add(ex);
			return ex;
		});
		List<Future<?>> futures = new ArrayList<>();
		for (Address a : entries) {
			futures.add(pool.submit(() -> {
				if (monitor.isCancelled() || aborted.get()) {
					return;
				}
				processOne(a, local.get(), monitor);
				monitor.incrementProgress(1);
			}));
		}
		pool.shutdown();
		try {
			while (!pool.awaitTermination(250, TimeUnit.MILLISECONDS)) {
				if (monitor.isCancelled() || aborted.get()) {
					for (Future<?> f : futures) {
						f.cancel(false);
					}
				}
			}
		}
		catch (InterruptedException e) {
			pool.shutdownNow();
			Thread.currentThread().interrupt();
		}
		finally {
			for (CodeExtractor ex : extractors) {
				ex.dispose();
			}
		}
	}

	private void processOne(Address entry, CodeExtractor ex, TaskMonitor monitor) {
		Function f = program.getFunctionManager().getFunctionAt(entry);
		if (f == null) {
			return;
		}
		String name = f.getName();
		try {
			monitor.setMessage(name);
			RenameEngine.Analysis an =
				RenameEngine.analyze(f, provider, settings, ex, monitor, msg -> log.accept(name + ": " + msg));
			RenameApplier.Result r;
			synchronized (applyLock) {
				boolean doFunc = settings.renameFunction &&
					RenameApplier.isFunctionRenameAllowed(f, settings);
				r = RenameApplier.apply(f, an.context().high, an.suggestion(), settings, null,
					doFunc, settings.addPlateComment);
			}
			varsRenamed.addAndGet(r.variablesRenamed);
			if (r.newFunctionName != null || r.variablesRenamed > 0) {
				renamed.incrementAndGet();
			}
			else {
				unchanged.incrementAndGet();
			}
			String extra = r.errors.isEmpty() ? "" : "  errors: " + String.join("; ", r.errors);
			log.accept(entry + "  " + r.summary() + "  [" + an.response().usageSummary() + "]" +
				extra);
		}
		catch (LLMException e) {
			if (e.isCancelled()) {
				return;
			}
			failed.incrementAndGet();
			log.accept(entry + "  " + name + "  FAILED: " + e.getMessage());
			if (FATAL.contains(e.getStatusCode())) {
				abortReason = "Stopped because the provider returned HTTP " + e.getStatusCode() +
					", which usually means a configuration problem (API key, model name, " +
					"endpoint or request too large):\n" + e.getMessage();
				aborted.set(true);
			}
		}
		catch (Exception e) {
			failed.incrementAndGet();
			log.accept(entry + "  " + name + "  FAILED: " + e);
			Msg.error(this, "GhidraLLM failed on " + name, e);
		}
	}
}
