package io.setu.security.tls;

import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class TlsContextFactory {
    private static final Logger LOG = LoggerFactory.getLogger(TlsContextFactory.class);

    private static final String TLS_PROTOCOL = "TLS";

    private final SetuKeyManagerProvider setuKeyManagerProvider;
    private final SetuTrustManagerProvider setuTrustManagerProvider;

    public TlsContextFactory(SetuKeyManagerProvider setuKeyManagerProvider, SetuTrustManagerProvider setuTrustManagerProvider) {

        if (setuKeyManagerProvider == null) {
            throw new IllegalArgumentException("SetuKeyManagerProvider must not be null");
        }

        if (setuTrustManagerProvider == null) {
            throw new IllegalArgumentException("SetuTrustManagerProvider must not be null");
        }

        this.setuKeyManagerProvider = setuKeyManagerProvider;
        this.setuTrustManagerProvider = setuTrustManagerProvider;
    }

    public SSLContext create(TlsConfiguration configuration) {

        if (configuration == null) {
            throw new IllegalArgumentException(
                    "TLS configuration must not be null"
            );
        }

        try {
            KeyManager[] keyManagers = setuKeyManagerProvider.create(configuration);
            TrustManager[] trustManagers = setuTrustManagerProvider.create(configuration);
            SSLContext sslContext = SSLContext.getInstance(TLS_PROTOCOL);

            sslContext.init(keyManagers, trustManagers, null);
            LOG.info("TLS context initialized protocols={} cipher_suites={} key_managers={} trust_managers={}",
                    configuration.enabledProtocols(), configuration.enabledCipherSuites(),
                    keyManagers == null ? 0 : keyManagers.length, trustManagers == null ? 0 : trustManagers.length);

            return sslContext;

        } catch (Exception e) {
            LOG.error("TLS context initialization failed", e);
            throw new TlsConfigurationException("Unable to initialize TLS context", e);
        }
    }
}
