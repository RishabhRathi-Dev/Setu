package io.setu.security.tls;

import javax.net.ssl.TrustManager;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;

public final class TrustManagerFactory {

    public TrustManager[] create(TlsConfiguration configuration) {

        try {
            KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
            Path path = Path.of(configuration.trustStorePath());

            try (InputStream inputStream = Files.newInputStream(path)) {
                trustStore.load(inputStream, configuration.trustStorePassword().toCharArray());
            }

            javax.net.ssl.TrustManagerFactory factory =
                    javax.net.ssl.TrustManagerFactory
                            .getInstance(
                                    javax.net.ssl.TrustManagerFactory
                                            .getDefaultAlgorithm()
                            );

            factory.init(trustStore);

            return factory.getTrustManagers();

        } catch (Exception e) {
            throw new TlsConfigurationException("Unable to initialize TLS trust managers", e);
        }
    }
}
