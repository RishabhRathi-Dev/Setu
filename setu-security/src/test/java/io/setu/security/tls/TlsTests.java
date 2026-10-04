package io.setu.security.tls;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TlsTests {

    @Test
    void enabledConfigurationRequiresProtocolAndStorePaths() {
        assertThrows(IllegalArgumentException.class,
                () -> configuration(true, null, "key", "trust"));
        assertThrows(IllegalArgumentException.class,
                () -> configuration(true, "TLS", null, "trust"));
        assertThrows(IllegalArgumentException.class,
                () -> configuration(true, "TLS", "key", null));
        assertNotNull(configuration(false, null, null, null));
    }

    @Test
    void configurationDefensivelyCopiesProtocolAndCipherLists() {
        List<String> protocols = new ArrayList<>(List.of("TLSv1.3"));
        List<String> cipherSuites = new ArrayList<>(List.of("TLS_AES_128_GCM_SHA256"));
        TlsConfiguration configuration = new TlsConfiguration(
                true, "TLS", "key", "password", "trust", "password", null, true,
                protocols, cipherSuites);

        protocols.add("TLSv1.2");
        cipherSuites.clear();

        assertEquals(List.of("TLSv1.3"), configuration.enabledProtocols());
        assertEquals(List.of("TLS_AES_128_GCM_SHA256"), configuration.enabledCipherSuites());
        assertThrows(UnsupportedOperationException.class,
                () -> configuration.enabledProtocols().add("TLSv1.2"));
    }

    @Test
    void providersLoadStoresAndContextInitializes(@TempDir Path directory) throws Exception {
        Path keyStorePath = directory.resolve("key-store.p12");
        Path trustStorePath = directory.resolve("trust-store.p12");
        writeEmptyStore(keyStorePath);
        writeEmptyStore(trustStorePath);
        TlsConfiguration configuration = configuration(
                true, "TLS", keyStorePath.toString(), trustStorePath.toString());

        assertTrue(new SetuKeyManagerProvider().create(configuration).length > 0);
        assertTrue(new SetuTrustManagerProvider().create(configuration).length > 0);
        assertNotNull(new TlsContextFactory(
                new SetuKeyManagerProvider(), new SetuTrustManagerProvider()).create(configuration));

        TlsConfiguration missingKeyStore = configuration(
                true, "TLS", directory.resolve("missing.p12").toString(), trustStorePath.toString());
        assertThrows(TlsConfigurationException.class,
                () -> new SetuKeyManagerProvider().create(missingKeyStore));
        assertThrows(IllegalArgumentException.class,
                () -> new TlsContextFactory(null, new SetuTrustManagerProvider()));
    }

    private static TlsConfiguration configuration(
            boolean enabled,
            String protocol,
            String keyStorePath,
            String trustStorePath) {
        return new TlsConfiguration(enabled, protocol, keyStorePath, "password",
                trustStorePath, "password", null, true, null, null);
    }

    private static void writeEmptyStore(Path path) throws Exception {
        KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
        keyStore.load(null, "password".toCharArray());

        try (var output = Files.newOutputStream(path)) {
            keyStore.store(output, "password".toCharArray());
        }
    }
}
