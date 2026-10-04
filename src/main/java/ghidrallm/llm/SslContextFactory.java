package ghidrallm.llm;

import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.security.cert.*;
import java.util.*;

import javax.net.ssl.*;

import ghidrallm.config.ProviderConfig;

/**
 * Builds an {@link SSLContext} from a provider's PKI settings:
 * <ul>
 * <li>optional client certificate from a PKCS#12 file (.p12 / .pfx)</li>
 * <li>optional CA certificate file (.cer/.crt/.pem; DER or PEM; may hold a chain/bundle),
 * either added to or replacing the JVM's default trusted CAs</li>
 * </ul>
 */
public final class SslContextFactory {

	private SslContextFactory() {
	}

	public static boolean needsCustomContext(ProviderConfig cfg) {
		return (cfg.useClientCertificate && notBlank(cfg.clientCertPath)) ||
			(cfg.useCustomCa && notBlank(cfg.caCertPath));
	}

	public static SSLContext create(ProviderConfig cfg) throws LLMException {
		try {
			KeyManager[] keyManagers = null;
			if (cfg.useClientCertificate && notBlank(cfg.clientCertPath)) {
				keyManagers = loadKeyManagers(Paths.get(cfg.clientCertPath.trim()),
					cfg.getResolvedClientCertPassword());
			}
			TrustManager[] trustManagers = null;
			if (cfg.useCustomCa && notBlank(cfg.caCertPath)) {
				trustManagers = buildTrustManagers(Paths.get(cfg.caCertPath.trim()),
					cfg.trustDefaultCas);
			}
			SSLContext ctx = SSLContext.getInstance("TLS");
			ctx.init(keyManagers, trustManagers, null);
			return ctx;
		}
		catch (LLMException e) {
			throw e;
		}
		catch (Exception e) {
			throw new LLMException("TLS/PKI setup failed: " + e.getMessage(), e);
		}
	}

	static KeyManager[] loadKeyManagers(Path p12, String password) throws Exception {
		if (!Files.isRegularFile(p12)) {
			throw new LLMException("Client certificate file not found: " + p12);
		}
		char[] pwd = password == null ? new char[0] : password.toCharArray();
		KeyStore ks = KeyStore.getInstance("PKCS12");
		try (InputStream in = Files.newInputStream(p12)) {
			ks.load(in, pwd);
		}
		catch (IOException e) {
			Throwable root = e.getCause() != null ? e.getCause() : e;
			if (root instanceof UnrecoverableKeyException ||
				String.valueOf(e.getMessage()).toLowerCase().contains("password")) {
				throw new LLMException("Wrong password for client certificate " + p12, e);
			}
			throw new LLMException(
				"Could not read PKCS#12 file " + p12 + " (" + e.getMessage() + ")", e);
		}
		boolean hasKey = false;
		for (Enumeration<String> en = ks.aliases(); en.hasMoreElements();) {
			if (ks.isKeyEntry(en.nextElement())) {
				hasKey = true;
				break;
			}
		}
		if (!hasKey) {
			throw new LLMException("PKCS#12 file contains no private key entry: " + p12);
		}
		KeyManagerFactory kmf =
			KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
		kmf.init(ks, pwd);
		return kmf.getKeyManagers();
	}

	public static List<X509Certificate> loadCertificates(Path file) throws Exception {
		if (!Files.isRegularFile(file)) {
			throw new LLMException("CA certificate file not found: " + file);
		}
		CertificateFactory cf = CertificateFactory.getInstance("X.509");
		List<X509Certificate> out = new ArrayList<>();
		try (InputStream in = new BufferedInputStream(Files.newInputStream(file))) {
			for (java.security.cert.Certificate c : cf.generateCertificates(in)) {
				if (c instanceof X509Certificate x) {
					out.add(x);
				}
			}
		}
		if (out.isEmpty()) {
			throw new LLMException("No X.509 certificates found in " + file);
		}
		return out;
	}

	static TrustManager[] buildTrustManagers(Path caFile, boolean includeDefaults)
			throws Exception {
		List<X509Certificate> certs = loadCertificates(caFile);
		KeyStore ts = KeyStore.getInstance(KeyStore.getDefaultType());
		ts.load(null, null);
		int i = 0;
		for (X509Certificate c : certs) {
			ts.setCertificateEntry("ghidrallm-ca-" + (i++), c);
		}
		TrustManagerFactory tmf =
			TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
		tmf.init(ts);
		X509TrustManager custom = firstX509(tmf.getTrustManagers());
		if (!includeDefaults) {
			return new TrustManager[] { custom };
		}
		TrustManagerFactory def =
			TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
		def.init((KeyStore) null);
		X509TrustManager defaults = firstX509(def.getTrustManagers());
		return new TrustManager[] { new CompositeTrustManager(custom, defaults) };
	}

	private static X509TrustManager firstX509(TrustManager[] tms) {
		for (TrustManager tm : tms) {
			if (tm instanceof X509TrustManager x) {
				return x;
			}
		}
		throw new IllegalStateException("No X509TrustManager available");
	}

	private static boolean notBlank(String s) {
		return s != null && !s.isBlank();
	}

	/** Trusts a server if either the custom CA set or the JVM default set trusts it. */
	static final class CompositeTrustManager implements X509TrustManager {
		private final X509TrustManager primary;
		private final X509TrustManager secondary;

		CompositeTrustManager(X509TrustManager primary, X509TrustManager secondary) {
			this.primary = primary;
			this.secondary = secondary;
		}

		@Override
		public void checkClientTrusted(X509Certificate[] chain, String authType)
				throws CertificateException {
			try {
				primary.checkClientTrusted(chain, authType);
			}
			catch (CertificateException e) {
				secondary.checkClientTrusted(chain, authType);
			}
		}

		@Override
		public void checkServerTrusted(X509Certificate[] chain, String authType)
				throws CertificateException {
			try {
				primary.checkServerTrusted(chain, authType);
			}
			catch (CertificateException e) {
				secondary.checkServerTrusted(chain, authType);
			}
		}

		@Override
		public X509Certificate[] getAcceptedIssuers() {
			X509Certificate[] a = primary.getAcceptedIssuers();
			X509Certificate[] b = secondary.getAcceptedIssuers();
			X509Certificate[] all = Arrays.copyOf(a, a.length + b.length);
			System.arraycopy(b, 0, all, a.length, b.length);
			return all;
		}
	}
}
