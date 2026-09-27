package io.setu.security.authentication;

import io.setu.security.certificates.CertificateIdentity;
import io.setu.security.certificates.CertificateValidator;
import lombok.extern.slf4j.Slf4j;

import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Map;

@Slf4j
public final class MtlsAuthenticator implements AuthenticationProvider {

    private static final AuthenticationMethod METHOD = AuthenticationMethod.MTLS;

    private final CertificateValidator certificateValidator;

    public MtlsAuthenticator(CertificateValidator certificateValidator) {
        this.certificateValidator = certificateValidator;
    }

    @Override
    public AuthenticationResult authenticate(AuthenticationRequest request) {

        if (request == null || request.sslSession() == null) {
            throw new AuthenticationException("SSL session is required for mTLS authentication");
        }

        SSLSession sslSession = request.sslSession();

        log.debug("Authenticating client using TLS protocol {}", sslSession.getProtocol());

        X509Certificate certificate = extractClientCertificate(sslSession);

        // Setu-specific certificate validation.
        certificateValidator.validate(certificate);

        // Convert X.509 identity into Setu identity.
        CertificateIdentity identity = CertificateIdentity.from(certificate);

        log.info("mTLS authentication successful for principal={}", identity.principal());

        return new AuthenticationResult(
                identity.principal(),
                METHOD,
                Map.of("certificateIdentity", identity)
        );
    }

    private X509Certificate extractClientCertificate(
            SSLSession sslSession) {

        try {
            Certificate[] certificates = sslSession.getPeerCertificates();

            if (certificates.length == 0) {
                throw new AuthenticationException("Client certificate not found");
            }

            Certificate certificate = certificates[0];

            if (!(certificate instanceof X509Certificate x509Certificate)) {
                throw new AuthenticationException("Client certificate is not an X509 certificate");
            }

            return x509Certificate;

        } catch (SSLPeerUnverifiedException e) {
            throw new AuthenticationException("Unable to retrieve verified client certificate", e);
        }
    }

    @Override
    public AuthenticationMethod method() {
        return METHOD;
    }
}