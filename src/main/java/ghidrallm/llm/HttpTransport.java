package ghidrallm.llm;

import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

import javax.net.ssl.SSLContext;

import ghidrallm.config.ProviderConfig;

/**
 * HTTP with PKI support, per-request timeout, cancellation and retry/backoff for
 * rate limiting (429) and transient server errors (5xx).
 */
public final class HttpTransport {

	/** Headers java.net.http refuses to let callers set. */
	private static final Set<String> RESTRICTED =
		Set.of("connection", "content-length", "expect", "host", "upgrade");

	private static final Map<String, HttpClient> CLIENTS = new ConcurrentHashMap<>();

	private HttpTransport() {
	}

	public record HttpResult(int status, String body, HttpHeaders headers, int attempts) {
	}

	/** Sleep hook so tests don't actually wait. */
	interface Sleeper {
		void sleep(long ms, CancelCheck cancel) throws LLMException;
	}

	static Sleeper sleeper = HttpTransport::interruptibleSleep;

	public static HttpResult send(ProviderConfig cfg, String method, String url,
			Map<String, String> headers, String body, CancelCheck cancel, Consumer<String> log)
			throws LLMException {
		if (cancel == null) {
			cancel = CancelCheck.NEVER;
		}
		if (log == null) {
			log = s -> {
			};
		}
		URI uri;
		try {
			uri = new URI(url);
			if (uri.getScheme() == null || uri.getHost() == null) {
				throw new URISyntaxException(url, "missing scheme or host");
			}
		}
		catch (URISyntaxException e) {
			throw new LLMException("Invalid URL: " + url);
		}

		HttpClient client = clientFor(cfg);
		Set<Integer> retryCodes = cfg.getRetryStatusCodes();
		int maxRetries = Math.max(0, Math.min(ProviderConfig.MAX_RETRIES_LIMIT, cfg.maxRetries));

		LLMException last = null;
		for (int attempt = 0; attempt <= maxRetries; attempt++) {
			if (cancel.isCancelled()) {
				throw LLMException.cancelled();
			}
			HttpRequest.Builder rb = HttpRequest.newBuilder(uri)
					.timeout(Duration.ofSeconds(Math.max(1, cfg.httpTimeoutSeconds)));
			for (Map.Entry<String, String> h : headers.entrySet()) {
				if (h.getValue() != null && !RESTRICTED.contains(h.getKey().toLowerCase())) {
					rb.header(h.getKey(), h.getValue());
				}
			}
			if (body != null) {
				rb.method(method, HttpRequest.BodyPublishers.ofString(body));
			}
			else {
				rb.method(method, HttpRequest.BodyPublishers.noBody());
			}

			HttpResponse<String> resp;
			try {
				resp = await(client.sendAsync(rb.build(), HttpResponse.BodyHandlers.ofString()),
					cancel);
			}
			catch (LLMException e) {
				throw e; // cancellation
			}
			catch (IOException e) {
				String what = describeIo(e);
				last = new LLMException("Network error calling " + uri.getHost() + ": " + what, e);
				if (cfg.retryOnNetworkError && attempt < maxRetries && !isFatalIo(e)) {
					long delay = backoff(cfg, attempt);
					log.accept(String.format("%s - retry %d/%d in %.1fs", what, attempt + 1,
						maxRetries, delay / 1000.0));
					sleeper.sleep(delay, cancel);
					continue;
				}
				throw last;
			}

			int status = resp.statusCode();
			if (status >= 200 && status < 300) {
				return new HttpResult(status, resp.body(), resp.headers(), attempt + 1);
			}
			String msg = "HTTP " + status + " from " + uri.getHost() + ": " +
				extractErrorMessage(resp.body());
			last = new LLMException(msg, status, resp.body());
			if (retryCodes.contains(status) && attempt < maxRetries) {
				long delay = backoff(cfg, attempt);
				if (cfg.honorRetryAfter) {
					long ra = parseRetryAfter(resp.headers());
					if (ra >= 0) {
						delay = Math.max(ra, 250); // the server knows best
					}
				}
				log.accept(String.format("HTTP %d - retry %d/%d in %.1fs", status, attempt + 1,
					maxRetries, delay / 1000.0));
				sleeper.sleep(delay, cancel);
				continue;
			}
			throw last;
		}
		throw last != null ? last : new LLMException("Request failed");
	}

	private static HttpResponse<String> await(CompletableFuture<HttpResponse<String>> f,
			CancelCheck cancel) throws IOException, LLMException {
		while (true) {
			if (cancel.isCancelled()) {
				f.cancel(true);
				throw LLMException.cancelled();
			}
			try {
				return f.get(250, TimeUnit.MILLISECONDS);
			}
			catch (TimeoutException e) {
				// poll cancel again
			}
			catch (InterruptedException e) {
				f.cancel(true);
				Thread.currentThread().interrupt();
				throw LLMException.cancelled();
			}
			catch (ExecutionException e) {
				Throwable c = e.getCause();
				if (c instanceof IOException io) {
					throw io;
				}
				throw new IOException(c == null ? "unknown error" : c.toString(), c);
			}
		}
	}

	static long backoff(ProviderConfig cfg, int attempt) {
		long base = Math.max(0, cfg.retryInitialDelayMs);
		long max = Math.max(base, cfg.retryMaxDelayMs);
		int shift = Math.min(attempt, 30);
		long d = base << shift;
		if (d < 0 || d > max) {
			d = max;
		}
		// +/- 20% jitter so parallel workers don't retry in lockstep
		double jitter = 0.8 + ThreadLocalRandom.current().nextDouble() * 0.4;
		return Math.max(0, Math.min(max, (long) (d * jitter)));
	}

	static long parseRetryAfter(HttpHeaders h) {
		Optional<String> ms = h.firstValue("retry-after-ms");
		if (ms.isPresent()) {
			try {
				return (long) Double.parseDouble(ms.get().trim());
			}
			catch (NumberFormatException e) {
				// fall through
			}
		}
		Optional<String> ra = h.firstValue("retry-after");
		if (ra.isEmpty()) {
			return -1;
		}
		String v = ra.get().trim();
		try {
			return (long) (Double.parseDouble(v) * 1000);
		}
		catch (NumberFormatException e) {
			try {
				ZonedDateTime t = ZonedDateTime.parse(v, DateTimeFormatter.RFC_1123_DATE_TIME);
				return Math.max(0, Duration.between(ZonedDateTime.now(), t).toMillis());
			}
			catch (Exception e2) {
				return -1;
			}
		}
	}

	private static void interruptibleSleep(long ms, CancelCheck cancel) throws LLMException {
		long end = System.currentTimeMillis() + ms;
		while (true) {
			if (cancel.isCancelled()) {
				throw LLMException.cancelled();
			}
			long left = end - System.currentTimeMillis();
			if (left <= 0) {
				return;
			}
			try {
				Thread.sleep(Math.min(left, 200));
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw LLMException.cancelled();
			}
		}
	}

	private static boolean isFatalIo(IOException e) {
		// TLS handshake/cert problems won't fix themselves on retry
		for (Throwable t = e; t != null; t = t.getCause()) {
			if (t instanceof javax.net.ssl.SSLHandshakeException ||
				t instanceof java.security.cert.CertificateException ||
				t instanceof UnknownHostException) {
				return true;
			}
		}
		return false;
	}

	private static String describeIo(IOException e) {
		if (e instanceof HttpTimeoutException) {
			return "timed out";
		}
		if (e instanceof ConnectException) {
			return "connection refused/failed";
		}
		String m = e.getMessage();
		return e.getClass().getSimpleName() + (m == null ? "" : ": " + m);
	}

	public static String extractErrorMessage(String body) {
		if (body == null || body.isBlank()) {
			return "(empty body)";
		}
		try {
			com.google.gson.JsonElement el = com.google.gson.JsonParser.parseString(body);
			if (el.isJsonObject()) {
				var o = el.getAsJsonObject();
				if (o.has("error")) {
					var err = o.get("error");
					if (err.isJsonObject() && err.getAsJsonObject().has("message")) {
						return err.getAsJsonObject().get("message").getAsString();
					}
					if (err.isJsonPrimitive()) {
						return err.getAsString();
					}
				}
				if (o.has("message")) {
					return o.get("message").getAsString();
				}
			}
		}
		catch (Exception e) {
			// not JSON
		}
		String b = body.strip();
		return b.length() > 500 ? b.substring(0, 500) + "..." : b;
	}

	/** Clients are cached per distinct network/PKI configuration. */
	static HttpClient clientFor(ProviderConfig cfg) throws LLMException {
		String key = fingerprint(cfg);
		HttpClient c = CLIENTS.get(key);
		if (c != null) {
			return c;
		}
		HttpClient.Builder b = HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(Math.max(1, cfg.connectTimeoutSeconds)))
				.followRedirects(HttpClient.Redirect.NORMAL)
				.version(HttpClient.Version.HTTP_1_1);
		ProxySelector ps = ProxySelector.getDefault();
		if (ps != null) {
			b.proxy(ps);
		}
		if (SslContextFactory.needsCustomContext(cfg)) {
			SSLContext ctx = SslContextFactory.create(cfg);
			b.sslContext(ctx);
		}
		c = b.build();
		CLIENTS.put(key, c);
		return c;
	}

	public static void clearClientCache() {
		CLIENTS.clear();
	}

	private static String fingerprint(ProviderConfig cfg) {
		StringBuilder sb = new StringBuilder();
		sb.append(cfg.connectTimeoutSeconds).append('|');
		if (cfg.useClientCertificate) {
			sb.append("p12=").append(cfg.clientCertPath).append('@').append(mtime(cfg.clientCertPath))
					.append('#').append(cfg.getResolvedClientCertPassword().hashCode()).append('|');
		}
		if (cfg.useCustomCa) {
			sb.append("ca=").append(cfg.caCertPath).append('@').append(mtime(cfg.caCertPath))
					.append('+').append(cfg.trustDefaultCas);
		}
		return sb.toString();
	}

	private static long mtime(String path) {
		try {
			return Files.getLastModifiedTime(Paths.get(path.trim())).toMillis();
		}
		catch (Exception e) {
			return 0;
		}
	}
}
