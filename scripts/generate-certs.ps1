param(
    [string] $OutputDirectory = "certs/generated"
)

$ErrorActionPreference = "Stop"
$password = if ($env:SETU_CERT_PASSWORD) { $env:SETU_CERT_PASSWORD } else { "changeit" }
$keytool = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME "bin/keytool.exe" } else { "keytool.exe" }
New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null

function Invoke-Keytool {
    param([string[]] $Arguments)
    & $keytool @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "keytool failed with exit code $LASTEXITCODE"
    }
}

Invoke-Keytool @("-genkeypair", "-noprompt", "-alias", "broker", "-dname", "CN=localhost", "-keyalg", "RSA", "-keysize", "2048", "-validity", "365", "-storetype", "PKCS12", "-keystore", "$OutputDirectory/broker.p12", "-storepass", $password, "-keypass", $password, "-ext", "SAN=dns:localhost,ip:127.0.0.1", "-ext", "EKU=serverAuth,clientAuth")
Invoke-Keytool @("-genkeypair", "-noprompt", "-alias", "client", "-dname", "CN=setu-client", "-keyalg", "RSA", "-keysize", "2048", "-validity", "365", "-storetype", "PKCS12", "-keystore", "$OutputDirectory/client.p12", "-storepass", $password, "-keypass", $password, "-ext", "SAN=dns:setu-client", "-ext", "EKU=clientAuth")
Invoke-Keytool @("-exportcert", "-rfc", "-alias", "broker", "-keystore", "$OutputDirectory/broker.p12", "-storetype", "PKCS12", "-storepass", $password, "-file", "$OutputDirectory/broker.crt")
Invoke-Keytool @("-exportcert", "-rfc", "-alias", "client", "-keystore", "$OutputDirectory/client.p12", "-storetype", "PKCS12", "-storepass", $password, "-file", "$OutputDirectory/client.crt")
Invoke-Keytool @("-importcert", "-noprompt", "-alias", "client", "-file", "$OutputDirectory/client.crt", "-keystore", "$OutputDirectory/broker-trust.p12", "-storetype", "PKCS12", "-storepass", $password)
Invoke-Keytool @("-importcert", "-noprompt", "-alias", "broker", "-file", "$OutputDirectory/broker.crt", "-keystore", "$OutputDirectory/client-trust.p12", "-storetype", "PKCS12", "-storepass", $password)

Write-Output "Development certificates written to $OutputDirectory (password: configured value or changeit). Do not use these in production."
