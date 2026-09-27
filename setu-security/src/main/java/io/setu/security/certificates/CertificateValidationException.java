package io.setu.security.certificates;

public class CertificateValidationException
        extends RuntimeException {

    public CertificateValidationException(String message) {
        super(message);
    }

    public CertificateValidationException(
            String message,
            Throwable cause) {

        super(message, cause);
    }
}
