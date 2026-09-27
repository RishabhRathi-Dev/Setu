package io.setu.security.tls;

import java.util.List;

public record TlsConfiguration(
        boolean enabled,
        String protocol,
        String keyStorePath,
        String keyStorePassword,
        String trustStorePath,
        String trustStorePassword,
        String keyAlias,
        boolean clientAuthenticationRequired,
        List<String> enabledProtocols,
        List<String> enabledCipherSuites
) {
    public TlsConfiguration {
        if (enabled && protocol == null) {
            throw new IllegalArgumentException("TLS protocol must be specified");
        }

        if (enabled && keyStorePath == null) {
            throw new IllegalArgumentException("Key store path must be specified");
        }

        if (enabled && trustStorePath == null) {
            throw new IllegalArgumentException("Trust store path must be specified");
        }

        enabledProtocols = enabledProtocols == null
                ? List.of()
                : List.copyOf(enabledProtocols);

        enabledCipherSuites = enabledCipherSuites == null
                ? List.of()
                : List.copyOf(enabledCipherSuites);
    }
}
