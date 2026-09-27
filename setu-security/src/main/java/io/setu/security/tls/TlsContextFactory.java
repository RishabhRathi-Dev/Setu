package io.setu.security.tls;

import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;

public final class TlsContextFactory {

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

            return sslContext;

        } catch (Exception e) {
            throw new TlsConfigurationException("Unable to initialize TLS context", e);
        }
    }
}