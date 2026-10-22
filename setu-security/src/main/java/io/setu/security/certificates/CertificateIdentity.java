package io.setu.security.certificates;

import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.List;

public record CertificateIdentity(
        String principal
) {

    private static final int DNS_NAME_TYPE = 2;

    public static CertificateIdentity from(X509Certificate certificate) {
        String principal = extractPrincipal(certificate);

        return new CertificateIdentity(principal);
    }

    private static String extractPrincipal(X509Certificate certificate) {
        try {
            Collection<List<?>> subjectAlternativeNames = certificate.getSubjectAlternativeNames();

            if (subjectAlternativeNames == null) {
                throw new CertificateValidationException("Certificate does not contain a subject alternative name");
            }

            for (List<?> san : subjectAlternativeNames) {
                if (san.size() < 2) {
                    continue;
                }

                Integer type = (Integer) san.get(0);

                if (type == DNS_NAME_TYPE) {
                    String principal = (String) san.get(1);

                    if (principal != null && !principal.isBlank()) {
                        return principal;
                    }
                }
            }
            throw new CertificateValidationException("Certificate does not contain a valid DNS SAN");
        } catch (CertificateParsingException e) {
            throw new CertificateValidationException("Unable to parse certificate identity", e);
        }
    }
}
