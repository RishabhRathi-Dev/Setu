package io.setu.security.tls;

import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;

public final class SetuTrustManagerProvider {

    public TrustManager[] create(TlsConfiguration configuration) {

        try {
            KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
            Path path = Path.of(configuration.trustStorePath());

            try (InputStream inputStream = Files.newInputStream(path)) {
                trustStore.load(inputStream, configuration.trustStorePassword().toCharArray());
            }

            TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            factory.init(trustStore);

            return factory.getTrustManagers();
        } catch (Exception e) {
            throw new TlsConfigurationException("Unable to initialize TLS trust managers", e);
        }
    }
}
