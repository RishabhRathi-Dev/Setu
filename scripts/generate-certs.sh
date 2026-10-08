#!/usr/bin/env sh
set -eu

OUT_DIR="${1:-certs/generated}"
PASSWORD="${SETU_CERT_PASSWORD:-changeit}"
KEYTOOL="${JAVA_HOME:+$JAVA_HOME/bin/keytool}"
KEYTOOL="${KEYTOOL:-keytool}"

mkdir -p "$OUT_DIR"
"$KEYTOOL" -genkeypair -noprompt -alias broker -dname "CN=localhost" -keyalg RSA -keysize 2048 -validity 365 \
  -storetype PKCS12 -keystore "$OUT_DIR/broker.p12" -storepass "$PASSWORD" -keypass "$PASSWORD" \
  -ext "SAN=dns:localhost,ip:127.0.0.1" -ext "EKU=serverAuth,clientAuth"
"$KEYTOOL" -genkeypair -noprompt -alias client -dname "CN=setu-client" -keyalg RSA -keysize 2048 -validity 365 \
  -storetype PKCS12 -keystore "$OUT_DIR/client.p12" -storepass "$PASSWORD" -keypass "$PASSWORD" \
  -ext "SAN=dns:setu-client" -ext "EKU=clientAuth"
"$KEYTOOL" -exportcert -rfc -alias broker -keystore "$OUT_DIR/broker.p12" -storetype PKCS12 -storepass "$PASSWORD" -file "$OUT_DIR/broker.crt"
"$KEYTOOL" -exportcert -rfc -alias client -keystore "$OUT_DIR/client.p12" -storetype PKCS12 -storepass "$PASSWORD" -file "$OUT_DIR/client.crt"
"$KEYTOOL" -importcert -noprompt -alias client -file "$OUT_DIR/client.crt" -keystore "$OUT_DIR/broker-trust.p12" -storetype PKCS12 -storepass "$PASSWORD"
"$KEYTOOL" -importcert -noprompt -alias broker -file "$OUT_DIR/broker.crt" -keystore "$OUT_DIR/client-trust.p12" -storetype PKCS12 -storepass "$PASSWORD"
printf 'Development certificates written to %s (password: configured value or changeit). Do not use these in production.\n' "$OUT_DIR"
