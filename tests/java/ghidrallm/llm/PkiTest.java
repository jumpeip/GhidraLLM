package ghidrallm.llm;

import static org.junit.Assert.*;

import java.io.*;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.security.*;
import java.security.cert.X509Certificate;
import java.util.*;

import javax.net.ssl.*;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.*;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.*;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.*;

import com.sun.net.httpserver.*;

import ghidrallm.config.ProviderConfig;
import ghidrallm.config.ProviderType;

/**
 * End-to-end mutual TLS: a local HTTPS server that requires a client certificate issued by a
 * test CA. Verifies .p12 client auth and .cer CA trust.
 */
public class PkiTest {

	private static Path dir;
	private static Path caCer;
	private static Path caDer;
	private static Path clientP12;
	private static Path otherP12;
	private static MockServer server;

	@BeforeClass
	public static void setUp() throws Exception {
		dir = Files.createTempDirectory("gllm-pki");
		KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
		g.initialize(2048);

		KeyPair caKp = g.generateKeyPair();
		X509Certificate ca = cert("CN=GhidraLLM Test CA", caKp, "CN=GhidraLLM Test CA", caKp, true,
			null);
		KeyPair srvKp = g.generateKeyPair();
		X509Certificate srv = cert("CN=localhost", srvKp, "CN=GhidraLLM Test CA", caKp, false,
			new GeneralNames(new GeneralName[] { new GeneralName(GeneralName.dNSName, "localhost"),
				new GeneralName(GeneralName.iPAddress, "127.0.0.1") }));
		KeyPair cliKp = g.generateKeyPair();
		X509Certificate cli = cert("CN=analyst", cliKp, "CN=GhidraLLM Test CA", caKp, false, null);

		// An unrelated self-signed client cert the server must reject
		KeyPair otherKp = g.generateKeyPair();
		X509Certificate other = cert("CN=intruder", otherKp, "CN=intruder", otherKp, false, null);

		caCer = dir.resolve("ca.cer");
		try (Writer w = Files.newBufferedWriter(caCer)) {
			w.write("-----BEGIN CERTIFICATE-----\n");
			w.write(Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(ca.getEncoded()));
			w.write("\n-----END CERTIFICATE-----\n");
		}
		caDer = dir.resolve("ca-der.cer");
		Files.write(caDer, ca.getEncoded());

		clientP12 = dir.resolve("client.pfx");
		writeP12(clientP12, "client", cliKp, new X509Certificate[] { cli, ca }, "s3cret");
		otherP12 = dir.resolve("other.p12");
		writeP12(otherP12, "other", otherKp, new X509Certificate[] { other }, "pw");

		// server side: own key + trust only the test CA, require client auth
		KeyStore sks = KeyStore.getInstance("PKCS12");
		sks.load(null, null);
		sks.setKeyEntry("srv", srvKp.getPrivate(), "x".toCharArray(),
			new X509Certificate[] { srv, ca });
		KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
		kmf.init(sks, "x".toCharArray());
		KeyStore sts = KeyStore.getInstance("PKCS12");
		sts.load(null, null);
		sts.setCertificateEntry("ca", ca);
		TrustManagerFactory tmf =
			TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
		tmf.init(sts);
		SSLContext sctx = SSLContext.getInstance("TLS");
		sctx.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);

		HttpsServer https = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		https.setHttpsConfigurator(new HttpsConfigurator(sctx) {
			@Override
			public void configure(HttpsParameters params) {
				SSLParameters p = getSSLContext().getDefaultSSLParameters();
				p.setNeedClientAuth(true);
				params.setSSLParameters(p);
			}
		});
		server = new MockServer(https);
		server.fallback = MockServer.Reply.ok(
			"{\"choices\":[{\"message\":{\"content\":\"mtls-ok\"}}]}");
	}

	@AfterClass
	public static void tearDown() {
		if (server != null) {
			server.close();
		}
	}

	@Before
	public void clearCache() {
		HttpTransport.clearClientCache();
	}

	private static ProviderConfig base() {
		ProviderConfig p = new ProviderConfig("pki", ProviderType.OPENAI_COMPATIBLE, "m");
		p.setUrl(server.url("/v1/chat/completions"));
		p.maxRetries = 0;
		p.httpTimeoutSeconds = 10;
		return p;
	}

	@Test
	public void mutualTlsWithPfxAndPemCa() throws Exception {
		ProviderConfig p = base();
		p.useClientCertificate = true;
		p.clientCertPath = clientP12.toString();
		p.clientCertPassword = "s3cret";
		p.useCustomCa = true;
		p.caCertPath = caCer.toString();
		LLMResponse r = LLMClient.chat(p, LLMRequest.single("", "hi", false), null, null);
		assertEquals("mtls-ok", r.getText());
	}

	@Test
	public void derCaOnlyWithoutDefaults() throws Exception {
		ProviderConfig p = base();
		p.useClientCertificate = true;
		p.clientCertPath = clientP12.toString();
		p.clientCertPassword = "s3cret";
		p.useCustomCa = true;
		p.caCertPath = caDer.toString();
		p.trustDefaultCas = false;
		assertEquals("mtls-ok",
			LLMClient.chat(p, LLMRequest.single("", "hi", false), null, null).getText());
	}

	@Test
	public void failsWithoutClientCertificate() {
		ProviderConfig p = base();
		p.useCustomCa = true;
		p.caCertPath = caCer.toString();
		assertThrows(LLMException.class,
			() -> LLMClient.chat(p, LLMRequest.single("", "hi", false), null, null));
	}

	@Test
	public void failsWithUntrustedClientCertificate() {
		ProviderConfig p = base();
		p.useClientCertificate = true;
		p.clientCertPath = otherP12.toString();
		p.clientCertPassword = "pw";
		p.useCustomCa = true;
		p.caCertPath = caCer.toString();
		assertThrows(LLMException.class,
			() -> LLMClient.chat(p, LLMRequest.single("", "hi", false), null, null));
	}

	@Test
	public void failsWithoutCustomCa() {
		ProviderConfig p = base();
		p.useClientCertificate = true;
		p.clientCertPath = clientP12.toString();
		p.clientCertPassword = "s3cret";
		LLMException e = assertThrows(LLMException.class,
			() -> LLMClient.chat(p, LLMRequest.single("", "hi", false), null, null));
		assertTrue(e.getMessage(), e.getMessage().toLowerCase().contains("network error") ||
			e.getMessage().toLowerCase().contains("certif") ||
			e.getMessage().toLowerCase().contains("pkix"));
	}

	@Test
	public void wrongPasswordIsReportedClearly() {
		ProviderConfig p = base();
		p.useClientCertificate = true;
		p.clientCertPath = clientP12.toString();
		p.clientCertPassword = "nope";
		LLMException e = assertThrows(LLMException.class,
			() -> LLMClient.chat(p, LLMRequest.single("", "hi", false), null, null));
		assertTrue(e.getMessage(), e.getMessage().toLowerCase().contains("password"));
	}

	@Test
	public void missingFilesAreReported() {
		ProviderConfig p = base();
		p.useCustomCa = true;
		p.caCertPath = dir.resolve("nope.cer").toString();
		LLMException e = assertThrows(LLMException.class,
			() -> LLMClient.chat(p, LLMRequest.single("", "hi", false), null, null));
		assertTrue(e.getMessage().contains("not found"));
	}

	// ------------------------------------------------------------------ helpers

	private static void writeP12(Path path, String alias, KeyPair kp, X509Certificate[] chain,
			String pwd) throws Exception {
		KeyStore ks = KeyStore.getInstance("PKCS12");
		ks.load(null, null);
		ks.setKeyEntry(alias, kp.getPrivate(), pwd.toCharArray(), chain);
		try (OutputStream o = Files.newOutputStream(path)) {
			ks.store(o, pwd.toCharArray());
		}
	}

	private static X509Certificate cert(String subject, KeyPair subjectKp, String issuer,
			KeyPair issuerKp, boolean isCa, GeneralNames san) throws Exception {
		long now = System.currentTimeMillis();
		X509v3CertificateBuilder b = new JcaX509v3CertificateBuilder(new X500Name(issuer),
			BigInteger.valueOf(now + new Random().nextInt(100000)), new Date(now - 60_000),
			new Date(now + 86_400_000L), new X500Name(subject), subjectKp.getPublic());
		b.addExtension(Extension.basicConstraints, true, new BasicConstraints(isCa));
		if (isCa) {
			b.addExtension(Extension.keyUsage, true,
				new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign));
		}
		if (san != null) {
			b.addExtension(Extension.subjectAlternativeName, false, san);
		}
		return new JcaX509CertificateConverter().getCertificate(
			b.build(new JcaContentSignerBuilder("SHA256withRSA").build(issuerKp.getPrivate())));
	}
}
