#!/bin/sh
set -eu

fail() { printf '%s\n' "Brandista CA setup failed: $1" >&2; exit 1; }

# Explicit opt-in preserves existing local Docker behavior. Render supplies RENDER=true.
ca_file=${AIVEN_CA_CERT:-}
if [ -z "$ca_file" ] && [ "${RENDER:-}" = "true" ]; then
    ca_file=/etc/secrets/ca.pem
fi
if [ -z "$ca_file" ]; then
    exec java -jar /app/brandista.jar "$@"
fi

[ -r "$ca_file" ] && [ -s "$ca_file" ] || fail "CA certificate is missing, empty, or unreadable: $ca_file"
[ "$(grep -c -- '-----BEGIN CERTIFICATE-----' "$ca_file" || true)" = "1" ] || fail "provide exactly one PEM CA certificate."
# Reject malformed, expired/not-yet-valid, and non-CA certificates before Java starts.
openssl x509 -in "$ca_file" -noout -ext basicConstraints 2>/dev/null | grep -q 'CA:TRUE' || fail "certificate is not a valid X.509 CA."
openssl verify -check_ss_sig -partial_chain -trusted "$ca_file" "$ca_file" >/dev/null 2>&1 || fail "CA certificate is invalid or outside its validity period."

public_roots=${JAVA_HOME:?JAVA_HOME must identify the Java runtime}/lib/security/cacerts
[ -r "$public_roots" ] || fail "Java's default public CA truststore is unreadable."
umask 077
trust_dir=$(mktemp -d /tmp/brandista-trust.XXXXXXXX) || fail "cannot create a private runtime truststore directory."
# Keep runtime trust material available for lazy TLS initialization; clean up setup failures.
trap 'rm -rf -- "$trust_dir"' 0
trust_store=$trust_dir/truststore.p12
trust_password=$(od -An -N32 -tx1 /dev/urandom | tr -d ' \n')
[ "${#trust_password}" = "64" ] || fail "cannot generate runtime truststore integrity password."
# 'changeit' is Java's standard public-cacerts integrity password, not an application secret.
keytool -importkeystore -noprompt -srckeystore "$public_roots" -srcstorepass changeit \
    -destkeystore "$trust_store" -deststoretype PKCS12 -deststorepass "$trust_password" >/dev/null 2>&1 \
    || fail "cannot copy Java's public CAs into the runtime truststore."
keytool -importcert -noprompt -alias brandista-aiven-ca -file "$ca_file" \
    -keystore "$trust_store" -storetype PKCS12 -storepass "$trust_password" >/dev/null 2>&1 \
    || fail "cannot import the Aiven CA into the runtime truststore."
chmod 600 "$trust_store"
printf '%s\n' "Brandista CA setup: Java public CAs preserved; mounted Aiven CA imported."
trap - 0
exec java "-Djavax.net.ssl.trustStore=$trust_store" \
    "-Djavax.net.ssl.trustStoreType=PKCS12" "-Djavax.net.ssl.trustStorePassword=$trust_password" \
    -jar /app/brandista.jar "$@"
