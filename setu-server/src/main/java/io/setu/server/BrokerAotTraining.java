package io.setu.server;

import io.setu.client.SetuClient;
import io.setu.core.broker.DefaultBroker;
import io.setu.core.protocol.BrokerResponse;
import io.setu.core.protocol.ErrorCode;
import io.setu.security.authentication.MtlsAuthenticator;
import io.setu.security.authorization.InMemoryAuthorizer;
import io.setu.security.authorization.Permission;
import io.setu.security.certificates.CertificateValidator;
import io.setu.security.quota.QuotaManager;
import io.setu.security.quota.QuotaPolicy;
import io.setu.security.quota.TokenBucketRateLimiter;
import io.setu.security.tls.*;
import io.setu.server.connection.FramedConnectionHandler;
import io.setu.server.dispatcher.BrokerRequestHandler;
import io.setu.server.tcp.TcpServer;
import io.setu.storage.log.StorageMode;
import io.setu.storage.log.MessageLogs;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.CountDownLatch;

/** Finite application training workload using the real client and plaintext/mTLS broker paths. */
final class BrokerAotTraining {
    private static final int FRAME_BYTES = 16 * 1024 * 1024;
    private static final int RECORD_BYTES = 1024;
    private static final String TOPIC = "aot-training";
    private static final String GROUP = "training";
    private static final String MEMBER = "reader";
    private static final String PRINCIPAL = "aot-client";
    private static final String PASSWORD = "aot-training-only";
    private static final QuotaPolicy TRAINING_QUOTA = new QuotaPolicy(10_000, 20_000,
            1_000_000_000, 2_000_000_000);
    private BrokerAotTraining() { }

    static void run() throws Exception {
        verifyJvmOptions();
        Path directory = Files.createTempDirectory("setu-aot-training-");
        try {
            TrainingTls tls = createTls(directory.resolve("certificates"));
            for (StorageMode mode : StorageMode.values()) {
                train(mode, directory.resolve("plaintext").resolve(mode.name()), null);
                train(mode, directory.resolve("mtls").resolve(mode.name()), tls);
            }
            trainMemoryBackpressure();
            System.out.println("AOT_COVERAGE modules=setu-server,setu-client,setu-core,setu-storage,setu-security "
                    + "gc=ZGC string_dedup_requested=true completed=true");
        } finally {
            Files.walkFileTree(directory, new SimpleFileVisitor<>() {
                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult postVisitDirectory(Path dir, IOException failure) throws IOException {
                    if (failure != null) throw failure;
                    Files.delete(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        }
    }

    private static void verifyJvmOptions() {
        boolean zgc = ManagementFactory.getGarbageCollectorMXBeans().stream()
                .anyMatch(collector -> collector.getName().startsWith("ZGC"));
        var diagnostics = ManagementFactory.getPlatformMXBean(com.sun.management.HotSpotDiagnosticMXBean.class);
        boolean deduplication = Boolean.parseBoolean(diagnostics.getVMOption("UseStringDeduplication").getValue());
        String aotMode = diagnostics.getVMOption("AOTMode").getValue();
        boolean requested = ManagementFactory.getRuntimeMXBean().getInputArguments()
                .contains("-XX:+UseStringDeduplication");
        // HotSpot disables deduplication while recording archive objects; deployment must enable it.
        if (!zgc || !requested || (!deduplication && !"record".equals(aotMode)))
            throw new IllegalStateException("AOT training requires -XX:+UseZGC -XX:+UseStringDeduplication"
                    + "; collectors=" + ManagementFactory.getGarbageCollectorMXBeans().stream()
                    .map(java.lang.management.GarbageCollectorMXBean::getName).toList() + "; string_dedup=" + deduplication);
        System.out.printf("AOT_RUNTIME gc=ZGC string_dedup_requested=%s string_dedup_actual=%s aot_mode=%s%n",
                requested, deduplication, aotMode.isEmpty() ? "auto" : aotMode);
    }

    private static void train(StorageMode mode, Path directory, TrainingTls tls) throws Exception {
        var authorizer = new InMemoryAuthorizer();
        authorizer.grant(PRINCIPAL, "topic/" + TOPIC, Permission.TOPIC_CREATE);
        authorizer.grant(PRINCIPAL, "topic/" + TOPIC, Permission.PRODUCE);
        authorizer.grant(PRINCIPAL, "topic/" + TOPIC, Permission.CONSUME);
        authorizer.grant(PRINCIPAL, "consumer-group/" + GROUP, Permission.CONSUMER_GROUP_CREATE);
        authorizer.grant(PRINCIPAL, "consumer-group/" + GROUP, Permission.CONSUMER_GROUP_WRITE);
        authorizer.grant(PRINCIPAL, "broker", Permission.VIEW_METADATA);
        var limiter = new TokenBucketRateLimiter();
        limiter.registerPolicy(PRINCIPAL, TRAINING_QUOTA);
        var address = new InetSocketAddress("127.0.0.1", 0);
        try (var broker = new DefaultBroker(mode, mode == StorageMode.FILE ? directory : null, RECORD_BYTES);
             var server = tls == null
                     ? new TcpServer(address, new FramedConnectionHandler(new BrokerRequestHandler(broker)))
                     : SecureServerFactory.createBroker(address, FRAME_BYTES, tls.server, tls.contexts,
                             new MtlsAuthenticator(new CertificateValidator()), authorizer,
                             new QuotaManager(limiter), broker)) {
            server.start();
            int port = server.localAddress().getPort();
            try (var client = connect(port, tls)) {
                require(client.createTopic(TOPIC, 4), ErrorCode.NONE);
                require(client.joinGroup(GROUP, TOPIC, MEMBER), ErrorCode.NONE);
                require(client.heartbeat(GROUP, MEMBER), ErrorCode.NONE);
                long[] offsets = new long[4];
                byte[] payload = new byte[RECORD_BYTES];
                Arrays.fill(payload, (byte) 7);
                var batch = Collections.nCopies(256, payload);
                // Four bounded clients exercise concurrent framing, payload ownership, and partition writers.
                try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
                    var ready = new CountDownLatch(offsets.length);
                    List<Future<?>> tasks = new ArrayList<>();
                    for (int partition = 0; partition < offsets.length; partition++) {
                        int selected = partition;
                        tasks.add(workers.submit(() -> {
                            try (var worker = connect(port, tls)) {
                                ready.countDown();
                                if (!ready.await(10, TimeUnit.SECONDS)) throw new IOException("Training clients failed to connect");
                                for (int round = 0; round < 16; round++) {
                                    var produced = require(worker.produceBatch(TOPIC, selected, batch), ErrorCode.NONE);
                                    if (produced.offset() != offsets[selected])
                                        throw new IOException("Training produce offset mismatch");
                                    drain(worker, selected, offsets, batch.size(), payload);
                                }
                            }
                            return null;
                        }));
                    }
                    for (var task : tasks) task.get(30, TimeUnit.SECONDS);
                }
                var produced = require(client.produce(TOPIC, 0, payload), ErrorCode.NONE);
                if (produced.offset() != offsets[0]) throw new IOException("Training single produce offset mismatch");
                drain(client, 0, offsets, 1, payload);
                var metadata = require(client.metadata(), ErrorCode.NONE);
                if (!Integer.valueOf(4).equals(metadata.topics().get(TOPIC)))
                    throw new IOException("Training metadata mismatch");
                if (tls != null) {
                    requireMissingCertificateRejected(port, tls);
                    // Prove the authenticated connection remains healthy after the rejected handshake.
                    require(client.metadata(), ErrorCode.NONE);
                    authorizer.revoke(PRINCIPAL, "topic/" + TOPIC, Permission.CONSUME);
                    require(client.fetch(GROUP, MEMBER, TOPIC, 0, offsets[0], 1024), ErrorCode.AUTHORIZATION_DENIED);
                    authorizer.grant(PRINCIPAL, "topic/" + TOPIC, Permission.CONSUME);
                    // A metadata frame always exceeds this byte burst, making denial independent of refill timing.
                    limiter.registerPolicy(PRINCIPAL, new QuotaPolicy(1, 1, 1, 1));
                    require(client.metadata(), ErrorCode.QUOTA_EXCEEDED);
                    limiter.registerPolicy(PRINCIPAL, TRAINING_QUOTA);
                    require(client.metadata(), ErrorCode.NONE);
                }
                require(client.leaveGroup(GROUP, MEMBER), ErrorCode.NONE);
                if (mode == StorageMode.IN_MEMORY && broker.retainedInMemoryBytes() != 0)
                    throw new IOException("Training memory backlog was not reclaimed");
                System.out.printf("AOT_TRAINING mode=%s transport=%s records=16385 record_bytes=%d batch_records=256 partitions=4 clients=4 "
                                + "authentication=%s authorization=%s quotas=%s completed=true%n",
                        mode, tls == null ? "plaintext" : "mtls", RECORD_BYTES, tls == null ? "disabled" : "allow_and_deny",
                        tls == null ? "disabled" : "allow_and_deny", tls == null ? "disabled" : "allow_and_deny");
            }
        }
    }

    private static SetuClient connect(int port, TrainingTls tls) throws IOException {
        return tls == null ? SetuClient.connectPlain("127.0.0.1", port, 5_000, 10_000, FRAME_BYTES)
                : SetuClient.connectTls("localhost", port, tls.client, tls.contexts, 5_000, 10_000, FRAME_BYTES);
    }

    private static void requireMissingCertificateRejected(int port, TrainingTls tls) throws Exception {
        var context = javax.net.ssl.SSLContext.getInstance("TLS");
        context.init(new javax.net.ssl.KeyManager[0], new SetuTrustManagerProvider().create(tls.client), null);
        try (var anonymous = SetuClient.connectTls("localhost", port, context, List.of("TLSv1.3"), List.of(),
                5_000, 10_000, FRAME_BYTES)) {
            // TLS 1.3 can deliver the server's certificate-required alert on the first application read.
            anonymous.metadata();
        } catch (javax.net.ssl.SSLException rejected) {
            if (rejected.getMessage() != null && rejected.getMessage().contains("certificate_required")) return;
            throw rejected;
        } catch (java.net.SocketException | java.io.EOFException rejected) {
            // Some platforms reset/close the transport before delivering the TLS 1.3 alert.
            // Timeouts remain failures, and the caller verifies authenticated traffic still succeeds.
            return;
        }
        throw new IOException("Training mTLS server accepted a client without a certificate");
    }

    private static void trainMemoryBackpressure() throws Exception {
        long budget = 64 * 1024;
        try (var broker = new DefaultBroker(StorageMode.IN_MEMORY, null, RECORD_BYTES,
                     MessageLogs.DEFAULT_MAX_SEGMENT_BYTES, DefaultBroker.DEFAULT_ACK_TIMEOUT_MILLIS,
                     DefaultBroker.DEFAULT_CONSUMER_INACTIVITY_TIMEOUT_MILLIS, budget);
             var server = new TcpServer(new InetSocketAddress("127.0.0.1", 0),
                     new FramedConnectionHandler(new BrokerRequestHandler(broker)))) {
            server.start();
            try (var client = connect(server.localAddress().getPort(), null)) {
                require(client.createTopic(TOPIC, 1), ErrorCode.NONE);
                require(client.joinGroup(GROUP, TOPIC, MEMBER), ErrorCode.NONE);
                byte[] payload = new byte[RECORD_BYTES];
                var batch = Collections.nCopies(32, payload);
                int accepted = 0;
                boolean rejected = false;
                for (int attempt = 0; attempt < 8; attempt++) {
                    var response = client.produceBatch(TOPIC, 0, batch);
                    if (response.errorCode() == ErrorCode.QUOTA_EXCEEDED) { rejected = true; break; }
                    require(response, ErrorCode.NONE);
                    if (response.offset() != accepted) throw new IOException("Training backpressure offset mismatch");
                    accepted += batch.size();
                }
                if (!rejected || accepted == 0) throw new IOException("Training did not exercise memory backpressure");
                long[] offsets = {0};
                drain(client, 0, offsets, accepted, payload);
                if (broker.retainedInMemoryBytes() != 0) throw new IOException("Training failed to reclaim memory budget");
                var retried = require(client.produceBatch(TOPIC, 0, batch), ErrorCode.NONE);
                if (retried.offset() != accepted) throw new IOException("Rejected training batch changed the next offset");
                drain(client, 0, offsets, batch.size(), payload);
                require(client.leaveGroup(GROUP, MEMBER), ErrorCode.NONE);
                System.out.printf("AOT_BACKPRESSURE budget_bytes=%d rejected=true reclaimed=true retry=accepted%n", budget);
            }
        }
    }

    private static void drain(SetuClient client, int partition, long[] offsets, int count, byte[] payload)
            throws IOException {
        var fetched = require(client.fetch(GROUP, MEMBER, TOPIC, partition, offsets[partition], 1024 * 1024), ErrorCode.NONE);
        if (fetched.records().size() != count) throw new IOException("Training fetch did not drain batch");
        for (var record : fetched.records()) {
            if (record.offset() != offsets[partition]++ || !Arrays.equals(record.value(), payload))
                throw new IOException("Training fetch record mismatch");
        }
        require(client.acknowledge(GROUP, MEMBER, TOPIC, partition, offsets[partition]), ErrorCode.NONE);
    }

    private static BrokerResponse require(BrokerResponse response, ErrorCode expected) throws IOException {
        if (response.errorCode() != expected)
            throw new IOException("Training expected " + expected + " but received " + response.errorCode()
                    + ": " + response.errorMessage());
        return response;
    }

    private static TrainingTls createTls(Path directory) throws Exception {
        Files.createDirectories(directory);
        Path brokerKeys = directory.resolve("broker.p12"), clientKeys = directory.resolve("client.p12");
        Path brokerTrust = directory.resolve("broker-trust.p12"), clientTrust = directory.resolve("client-trust.p12");
        generateCertificate(brokerKeys, "broker", "localhost", "serverAuth");
        generateCertificate(clientKeys, "client", PRINCIPAL, "clientAuth");
        exportCertificate(brokerKeys, "broker", directory.resolve("broker.cer"));
        exportCertificate(clientKeys, "client", directory.resolve("client.cer"));
        importCertificate(brokerTrust, "client", directory.resolve("client.cer"));
        importCertificate(clientTrust, "broker", directory.resolve("broker.cer"));
        return new TrainingTls(tls(brokerKeys, brokerTrust), tls(clientKeys, clientTrust),
                new TlsContextFactory(new SetuKeyManagerProvider(), new SetuTrustManagerProvider()));
    }

    private static TlsConfiguration tls(Path keys, Path trust) {
        return new TlsConfiguration(true, "TLS", keys.toString(), PASSWORD, trust.toString(), PASSWORD,
                null, true, List.of("TLSv1.3"), List.of());
    }

    private static void generateCertificate(Path store, String alias, String principal, String eku) throws Exception {
        keytool("-genkeypair", "-noprompt", "-alias", alias, "-dname", "CN=" + principal,
                "-keyalg", "RSA", "-keysize", "2048", "-validity", "2", "-storetype", "PKCS12",
                "-keystore", store.toString(), "-storepass", PASSWORD, "-keypass", PASSWORD,
                "-ext", "SAN=dns:" + principal, "-ext", "EKU=" + eku);
    }

    private static void exportCertificate(Path store, String alias, Path certificate) throws Exception {
        keytool("-exportcert", "-rfc", "-alias", alias, "-keystore", store.toString(),
                "-storepass", PASSWORD, "-file", certificate.toString());
    }

    private static void importCertificate(Path store, String alias, Path certificate) throws Exception {
        keytool("-importcert", "-noprompt", "-alias", alias, "-file", certificate.toString(),
                "-keystore", store.toString(), "-storetype", "PKCS12", "-storepass", PASSWORD);
    }

    private static void keytool(String... arguments) throws Exception {
        String executable = System.getProperty("os.name").startsWith("Windows") ? "keytool.exe" : "keytool";
        var command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                "-J-XX:+UseZGC", "-J-XX:+UseStringDeduplication"));
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        try {
            if (!process.waitFor(45, TimeUnit.SECONDS)) throw new IOException("AOT training keytool timed out");
            if (process.exitValue() != 0)
                throw new IOException("AOT training keytool failed: "
                        + new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        } finally {
            if (process.isAlive()) process.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
        }
    }

    private record TrainingTls(TlsConfiguration server, TlsConfiguration client, TlsContextFactory contexts) { }
}
