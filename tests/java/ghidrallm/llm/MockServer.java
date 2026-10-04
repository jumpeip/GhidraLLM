package ghidrallm.llm;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import com.sun.net.httpserver.*;

/** Tiny scripted HTTP(S) server for tests. */
class MockServer implements AutoCloseable {

	record Reply(int status, String body, Map<String, String> headers) {
		static Reply ok(String body) {
			return new Reply(200, body, Map.of());
		}
	}

	record Seen(String method, String path, String query, Map<String, List<String>> headers,
			String body) {
		String header(String name) {
			for (Map.Entry<String, List<String>> e : headers.entrySet()) {
				if (e.getKey().equalsIgnoreCase(name)) {
					return e.getValue().get(0);
				}
			}
			return null;
		}
	}

	final HttpServer server;
	final Deque<Reply> script = new ArrayDeque<>();
	final List<Seen> seen = new CopyOnWriteArrayList<>();
	Reply fallback = new Reply(500, "{\"error\":{\"message\":\"no script\"}}", Map.of());

	MockServer() throws IOException {
		this(HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0));
	}

	MockServer(HttpServer s) {
		this.server = s;
		server.createContext("/", this::handle);
		server.start();
	}

	String url(String path) {
		String scheme = server instanceof HttpsServer ? "https" : "http";
		return scheme + "://127.0.0.1:" + server.getAddress().getPort() + path;
	}

	MockServer then(int status, String body) {
		script.add(new Reply(status, body, Map.of()));
		return this;
	}

	MockServer then(int status, String body, Map<String, String> headers) {
		script.add(new Reply(status, body, headers));
		return this;
	}

	private synchronized void handle(HttpExchange ex) throws IOException {
		String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
		seen.add(new Seen(ex.getRequestMethod(), ex.getRequestURI().getPath(),
			ex.getRequestURI().getQuery(), new TreeMap<>(ex.getRequestHeaders()), body));
		Reply r = script.isEmpty() ? fallback : script.poll();
		byte[] out = r.body().getBytes(StandardCharsets.UTF_8);
		ex.getResponseHeaders().add("Content-Type", "application/json");
		r.headers().forEach((k, v) -> ex.getResponseHeaders().add(k, v));
		ex.sendResponseHeaders(r.status(), out.length);
		try (OutputStream os = ex.getResponseBody()) {
			os.write(out);
		}
	}

	@Override
	public void close() {
		server.stop(0);
	}
}
