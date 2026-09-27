package io.setu.security.certificates;

import java.security.cert.X509Certificate;
import java.util.List;

public final class CertificateValidator {

    private static final String CLIENT_AUTH_EKU =
            "1.3.6.1.5.5.7.3.2";

    public void validate(X509Certificate certificate) {

        if (certificate == null) {
            throw new CertificateValidationException(
                    "Client certificate must not be null"
            );
        }

        validateValidity(certificate);
        validateExtendedKeyUsage(certificate);
    }

    private void validateValidity(X509Certificate certificate) {
        try {
            certificate.checkValidity();
        } catch (Exception e) {
            throw new CertificateValidationException(
                    "Client certificate is expired or not yet valid",
                    e
            );
        }
    }

    private void validateExtendedKeyUsage(
            X509Certificate certificate) {

        try {
            List<String> usages =
                    certificate.getExtendedKeyUsage();

            /*
             * If EKU is absent, the certificate does not explicitly
             * restrict its intended usage.
             *
             * For Setu we can choose to require clientAuth explicitly.
             */
            if (usages == null || !usages.contains(CLIENT_AUTH_EKU)) {
                throw new CertificateValidationException(
                        "Certificate is not authorized for TLS client authentication"
                );
            }

        } catch (CertificateValidationException e) {
            throw e;
        } catch (Exception e) {
            throw new CertificateValidationException(
                    "Unable to inspect certificate extended key usage",
                    e
            );
        }
    }
}