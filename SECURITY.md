# Security policy

## Reporting a vulnerability

Report suspected vulnerabilities privately. On the repository's GitHub **Security** tab, use **Report a vulnerability** if private reporting is enabled. If that option is unavailable, open an issue asking the maintainer for a private reporting channel, without including vulnerability details.

Include the following in the private report:

- The affected commit or version, JDK, operating system, storage mode, and transport.
- The affected component and the access or configuration needed to trigger the issue.
- Minimal reproduction steps, expected behavior, and observed impact.
- A proposed fix or mitigation, if known.

Redact credentials, private keys, personal data, and application payloads from captures. Coordinate disclosure with the maintainer before publishing exploit details. This project does not publish a guaranteed response time or a maintained release support window; identify the exact revision in every report.

## Deployment boundaries

Setu is under active development. The standalone properties launcher binds to `127.0.0.1:9092` by default and serves plaintext. Changing `bind.host` does not enable authentication or encryption. Use [secure embedding](setu-server/README.md#secure-broker-wiring-example) for TLS/mTLS, certificate identity, exact ACL grants, and quotas. The selector transport supports plaintext only.

The embedding application provisions credentials, grants, and quotas. Policies are process-local. Certificate issuing, automatic rotation, revocation integration, and persistent policy administration are outside the current implementation. See the [security module](setu-security/README.md) for the precise identity and authorization contracts.

Development certificate generators create local test material under ignored `certs/generated/`. Keep generated stores, passwords, runtime data, and diagnostic recordings out of commits and public reports.

For ordinary bugs and usage questions, see [SUPPORT.md](SUPPORT.md).
