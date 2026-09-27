package io.setu.security.tls;

public class TlsConfigurationException extends RuntimeException {

    public TlsConfigurationException(String message) {
        super(message);
    }

    public TlsConfigurationException(
            String message,
            Throwable cause) {

        super(message, cause);
    }
}
