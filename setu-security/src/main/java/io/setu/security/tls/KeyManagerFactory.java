package io.setu.security.tls;

import javax.net.ssl.KeyManager;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;

public final class KeyManagerFactory {

    public KeyManager[] create(TlsConfiguration configuration) {

        try {
            KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());

            Path path = Path.of(configuration.keyStorePath());

            try (InputStream inputStream = Files.newInputStream(path)) {
                keyStore.load(inputStream, configuration.keyStorePassword().toCharArray());
            }

            javax.net.ssl.KeyManagerFactory factory =
                    javax.net.ssl.KeyManagerFactory
                            .getInstance(
                                    javax.net.ssl.KeyManagerFactory
                                            .getDefaultAlgorithm()
                            );

            factory.init(keyStore, configuration.keyStorePassword().toCharArray());
            return factory.getKeyManagers();
        } catch (Exception e) {
            throw new TlsConfigurationException("Unable to initialize TLS key managers", e);
        }
    }
}
