package io.setu.security.certificates;

import org.junit.jupiter.api.Test;

import java.security.cert.CertificateExpiredException;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CertificateTests {

    private static final String CLIENT_AUTH_EKU = "1.3.6.1.5.5.7.3.2";

    @Test
    void validatorAcceptsCurrentCertificateWithClientAuthEku() throws Exception {
        X509Certificate certificate = mock(X509Certificate.class);
        when(certificate.getExtendedKeyUsage()).thenReturn(List.of(CLIENT_AUTH_EKU));

        new CertificateValidator().validate(certificate);

        verify(certificate).checkValidity();
    }

    @Test
    void validatorRejectsNullExpiredAndWrongEkuCertificates() throws Exception {
        CertificateValidator validator = new CertificateValidator();
        assertThrows(CertificateValidationException.class, () -> validator.validate(null));

        X509Certificate wrongEku = mock(X509Certificate.class);
        when(wrongEku.getExtendedKeyUsage()).thenReturn(List.of("1.3.6.1.5.5.7.3.1"));
        assertThrows(CertificateValidationException.class, () -> validator.validate(wrongEku));

        X509Certificate expired = mock(X509Certificate.class);
        doThrow(new CertificateExpiredException()).when(expired).checkValidity();
        assertThrows(CertificateValidationException.class, () -> validator.validate(expired));
    }

    @Test
    void identityUsesFirstNonblankDnsSan() throws Exception {
        X509Certificate certificate = mock(X509Certificate.class);
        when(certificate.getSubjectAlternativeNames()).thenReturn(List.of(
                List.of(7, "ignored"),
                List.of(2, " "),
                List.of(2, "orders.service")));

        assertEquals("orders.service", CertificateIdentity.from(certificate).principal());
    }

    @Test
    void identityRejectsMissingMalformedAndBlankDnsSans() throws Exception {
        X509Certificate certificate = mock(X509Certificate.class);
        when(certificate.getSubjectAlternativeNames()).thenReturn(null);
        assertThrows(CertificateValidationException.class,
                () -> CertificateIdentity.from(certificate));

        when(certificate.getSubjectAlternativeNames()).thenReturn(List.of(List.of(2, " ")));
        assertThrows(CertificateValidationException.class,
                () -> CertificateIdentity.from(certificate));

        when(certificate.getSubjectAlternativeNames())
                .thenThrow(new CertificateParsingException("malformed SAN"));
        assertThrows(CertificateValidationException.class,
                () -> CertificateIdentity.from(certificate));
    }
}
