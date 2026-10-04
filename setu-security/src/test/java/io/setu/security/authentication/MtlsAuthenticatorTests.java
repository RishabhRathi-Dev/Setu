package io.setu.security.authentication;

import io.setu.security.certificates.CertificateValidationException;
import io.setu.security.certificates.CertificateIdentity;
import io.setu.security.certificates.CertificateValidator;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MtlsAuthenticatorTests {

    @Test
    void authenticatesValidatedPeerCertificateUsingDnsSan() throws Exception {
        X509Certificate certificate = certificateWithDnsSan("service");
        SSLSession session = sessionWithPeer(certificate);
        CertificateValidator validator = mock(CertificateValidator.class);

        AuthenticationResult result = new MtlsAuthenticator(validator)
                .authenticate(new AuthenticationRequest(session));

        assertEquals("service", result.principal());
        assertEquals(AuthenticationMethod.MTLS, result.method());
        assertEquals(new CertificateIdentity("service"), result.attributes().get("certificateIdentity"));
        verify(validator).validate(certificate);
    }

    @Test
    void rejectsSessionWithoutVerifiedPeerCertificates() throws Exception {
        SSLSession session = mock(SSLSession.class);
        when(session.getPeerCertificates())
                .thenThrow(new SSLPeerUnverifiedException("no verified peer"));

        MtlsAuthenticator authenticator = new MtlsAuthenticator(new CertificateValidator());

        assertThrows(AuthenticationException.class,
                () -> authenticator.authenticate(new AuthenticationRequest(session)));
        assertEquals(AuthenticationMethod.MTLS, authenticator.method());
    }

    @Test
    void rejectsEmptyAndNonX509PeerCertificateChains() throws Exception {
        SSLSession emptySession = mock(SSLSession.class);
        when(emptySession.getPeerCertificates()).thenReturn(new Certificate[0]);

        SSLSession nonX509Session = sessionWithPeer(mock(Certificate.class));
        MtlsAuthenticator authenticator = new MtlsAuthenticator(new CertificateValidator());

        assertThrows(AuthenticationException.class,
                () -> authenticator.authenticate(new AuthenticationRequest(emptySession)));
        assertThrows(AuthenticationException.class,
                () -> authenticator.authenticate(new AuthenticationRequest(nonX509Session)));
    }

    @Test
    void propagatesCertificateValidationFailure() throws Exception {
        X509Certificate certificate = certificateWithDnsSan("service");
        SSLSession session = sessionWithPeer(certificate);
        CertificateValidator validator = mock(CertificateValidator.class);
        doThrow(new CertificateValidationException("invalid client certificate"))
                .when(validator).validate(certificate);

        MtlsAuthenticator authenticator = new MtlsAuthenticator(validator);

        assertThrows(CertificateValidationException.class,
                () -> authenticator.authenticate(new AuthenticationRequest(session)));
    }

    private static X509Certificate certificateWithDnsSan(String principal) throws Exception {
        X509Certificate certificate = mock(X509Certificate.class);
        when(certificate.getSubjectAlternativeNames())
                .thenReturn(List.of(List.of(2, principal)));
        return certificate;
    }

    private static SSLSession sessionWithPeer(Certificate certificate) throws Exception {
        SSLSession session = mock(SSLSession.class);
        when(session.getPeerCertificates()).thenReturn(new Certificate[]{certificate});
        return session;
    }
}
