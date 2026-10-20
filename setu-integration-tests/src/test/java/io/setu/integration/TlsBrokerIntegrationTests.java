package io.setu.integration;

import io.setu.core.broker.DefaultBroker;
import io.setu.core.protocol.ErrorCode;
import io.setu.security.authentication.MtlsAuthenticator;
import io.setu.security.authorization.InMemoryAuthorizer;
import io.setu.security.authorization.Permission;
import io.setu.security.certificates.CertificateValidator;
import io.setu.security.quota.QuotaManager;
import io.setu.security.quota.QuotaPolicy;
import io.setu.security.quota.TokenBucketRateLimiter;
import io.setu.security.tls.SetuKeyManagerProvider;
import io.setu.security.tls.SetuTrustManagerProvider;
import io.setu.security.tls.TlsConfiguration;
import io.setu.security.tls.TlsContextFactory;
import io.setu.server.tcp.TcpServer;
import io.setu.server.SecureServerFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TlsBrokerIntegrationTests {
    private static final String PASSWORD = "changeit-test-only";
    @TempDir Path tempDir;

    @Test
    void tlsClientAuthenticatesThenUsesBrokerAndReceivesStructuredAuthorizationError() throws Exception {
        Path serverKeyStore = tempDir.resolve("broker.p12");
        Path clientKeyStore = tempDir.resolve("client.p12");
        Path serverTrustStore = tempDir.resolve("broker-trust.p12");
        Path clientTrustStore = tempDir.resolve("client-trust.p12");
        Path serverCert = tempDir.resolve("broker.cer");
        Path clientCert = tempDir.resolve("client.cer");

        generateCertificate(serverKeyStore, "broker", "CN=broker", "dns:localhost");
        generateCertificate(clientKeyStore, "client", "CN=orders-service", "dns:orders-service");
        exportCertificate(serverKeyStore, "broker", serverCert);
        exportCertificate(clientKeyStore, "client", clientCert);
        importTrustedCert(serverTrustStore, "orders-service", clientCert);
        importTrustedCert(clientTrustStore, "broker", serverCert);

        TlsConfiguration serverTls = tls(serverKeyStore, serverTrustStore);
        TlsConfiguration clientTls = tls(clientKeyStore, clientTrustStore);
        TlsContextFactory tlsContexts = new TlsContextFactory(
                new SetuKeyManagerProvider(), new SetuTrustManagerProvider());

        InMemoryAuthorizer authorizer = new InMemoryAuthorizer();
        authorizer.grant("orders-service", "topic/orders", Permission.TOPIC_CREATE);
        authorizer.grant("orders-service", "topic/orders", Permission.PRODUCE);
        authorizer.grant("orders-service", "topic/orders", Permission.CONSUME);
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter();
        limiter.registerPolicy("orders-service", new QuotaPolicy(100, 200, 1_000_000, 2_000_000));

        try (DefaultBroker broker = new DefaultBroker(tempDir.resolve("data"), 1_000_000);
             TcpServer server = SecureServerFactory.createBroker(
                     new java.net.InetSocketAddress("127.0.0.1", 0),
                     16 * 1024 * 1024,
                     serverTls,
                     tlsContexts,
                     new MtlsAuthenticator(new CertificateValidator()),
                     authorizer,
                     new QuotaManager(limiter),
                     broker, new io.setu.server.tcp.TcpServerOptions(
                             10, 10, 0, 1024 * 1024, 1024 * 1024, 3000, 1000, 250))) {
            server.start();
            int port = server.localAddress().getPort();
            // No TLS bytes: the handshake deadline must apply even with the idle timeout disabled.
            try (var stalled = new java.net.Socket("localhost", port)) {
                stalled.setSoTimeout(3000);
                try { assertEquals(-1, stalled.getInputStream().read()); }
                catch (java.net.SocketException reset) { /* The OS may report a reset. */ }
            }
            try (io.setu.client.SetuClient client = io.setu.client.SetuClient.connectTls(
                    "localhost", port, clientTls, tlsContexts,
                    5_000, 5_000, 16 * 1024 * 1024)) {
                assertEquals(ErrorCode.NONE, client.createTopic("orders", 1).errorCode());
                var produced = client.produce("orders", 0, "order-1".getBytes());
                assertEquals(ErrorCode.NONE, produced.errorCode());
                assertEquals(0, produced.offset());

                var fetched = client.fetch("orders", 0, 0, 1024);
                assertEquals(ErrorCode.NONE, fetched.errorCode());
                assertEquals("order-1", new String(fetched.records().getFirst().value()));
                broker.execute(new io.setu.core.protocol.BrokerRequest(1, io.setu.core.protocol.Command.JOIN_GROUP,
                        "orders", -1, -1, -1, null, 0, "protected", "member"));
                assertEquals(ErrorCode.AUTHORIZATION_DENIED,
                        client.fetch("protected", "member", "orders", 0, 0, 1024).errorCode());
                var notFetched = org.junit.jupiter.api.Assertions.assertThrows(io.setu.core.broker.BrokerException.class,
                        () -> broker.execute(new io.setu.core.protocol.BrokerRequest(1, io.setu.core.protocol.Command.COMMIT_OFFSET,
                                "orders", 0, 1, 0, null, 0, "protected", "member")));
                assertEquals(ErrorCode.BAD_REQUEST, notFetched.errorCode());
                authorizer.grant("orders-service", "consumer-group/protected", Permission.CONSUMER_GROUP_WRITE);
                assertEquals(ErrorCode.NONE,
                        client.fetch("protected", "member", "orders", 0, 0, 1024).errorCode());
                assertEquals(ErrorCode.NONE, client.acknowledge("protected", "member", "orders", 0, 1).errorCode());
                authorizer.revoke("orders-service", "topic/orders", Permission.CONSUME);
                assertEquals(ErrorCode.AUTHORIZATION_DENIED,
                        client.fetch("protected", "member", "orders", 0, 0, 1024).errorCode());
                var denied = client.fetch("orders", 0, 0, 1024);
                assertEquals(ErrorCode.AUTHORIZATION_DENIED, denied.errorCode());
            }
            // An established TLS connection is also subject to the whole-frame deadline.
            try (var tls = (javax.net.ssl.SSLSocket) tlsContexts.create(clientTls).getSocketFactory()
                    .createSocket("localhost", port)) {
                tls.setSoTimeout(3000);
                tls.startHandshake();
                var output = new java.io.DataOutputStream(tls.getOutputStream());
                output.writeInt(1024); output.writeByte(0); output.flush();
                org.junit.jupiter.api.Assertions.assertThrows(IOException.class, () -> {
                    if (tls.getInputStream().read() == -1) throw new java.io.EOFException();
                });
            }
        }
    }

    private TlsConfiguration tls(Path keyStore, Path trustStore) {
        return new TlsConfiguration(true, "TLS", keyStore.toString(), PASSWORD,
                trustStore.toString(), PASSWORD, null, true, List.of("TLSv1.3"), List.of());
    }

    private static void generateCertificate(Path store, String alias, String subject, String san) throws Exception {
        runKeytool("-genkeypair", "-noprompt", "-alias", alias, "-dname", subject,
                "-keyalg", "RSA", "-keysize", "2048", "-validity", "2",
                "-storetype", "PKCS12", "-keystore", store.toString(),
                "-storepass", PASSWORD, "-keypass", PASSWORD,
                "-ext", "SAN=" + san, "-ext", "EKU=serverAuth,clientAuth");
    }

    private static void exportCertificate(Path store, String alias, Path certificate) throws Exception {
        runKeytool("-exportcert", "-rfc", "-alias", alias, "-keystore", store.toString(),
                "-storetype", "PKCS12", "-storepass", PASSWORD, "-file", certificate.toString());
    }

    private static void importTrustedCert(Path store, String alias, Path certificate) throws Exception {
        runKeytool("-importcert", "-noprompt", "-alias", alias, "-file", certificate.toString(),
                "-keystore", store.toString(), "-storetype", "PKCS12", "-storepass", PASSWORD);
    }

    private static void runKeytool(String... args) throws Exception {
        String executable = System.getProperty("os.name").toLowerCase().contains("win") ? "keytool.exe" : "keytool";
        Path keytool = Path.of(System.getProperty("java.home"), "bin", executable);
        var command = new java.util.ArrayList<String>();
        command.add(keytool.toString());
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        if (!process.waitFor(Duration.ofSeconds(45).toMillis(), TimeUnit.MILLISECONDS)) {
            process.destroyForcibly();
            throw new IOException("Timed out running keytool");
        }
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(process.exitValue() == 0, "keytool failed: " + output);
    }
}
