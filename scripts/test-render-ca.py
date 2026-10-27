"""Offline Docker trust smoke test. Generates disposable keys only under ignored target/."""
import os
import pathlib
import secrets
import subprocess

ROOT = pathlib.Path(__file__).resolve().parents[1]
FIXTURE = ROOT / "target" / "render-ca-test"
FIXTURE.mkdir(parents=True, exist_ok=True)
java_home = pathlib.Path(os.environ["JAVA_HOME"])
exe = ".exe" if os.name == "nt" else ""
password = secrets.token_hex(24)


def run(args, *, expected=0):
    result = subprocess.run([str(a) for a in args], cwd=FIXTURE, capture_output=True, text=True)
    if result.returncode != expected:
        raise RuntimeError(f"{args[0]} failed ({result.returncode}): {result.stdout} {result.stderr}")
    return result.stdout + result.stderr


def keytool(*args):
    return run([java_home / "bin" / ("keytool" + exe), *args])


# Unique files permit repeated runs without retaining or committing a private key.
nonce = secrets.token_hex(6)
ca_store = FIXTURE / (nonce + "-ca.p12")
leaf_store = FIXTURE / (nonce + "-leaf.p12")
expired_store = FIXTURE / (nonce + "-expired.p12")
for store, name, extra in [
    (ca_store, "Synthetic Render CA", ["-ext", "BC=ca:true", "-validity", "2"]),
    (leaf_store, "localhost", ["-ext", "SAN=dns:localhost", "-validity", "2"]),
    (expired_store, "Expired Synthetic CA", ["-ext", "BC=ca:true", "-startdate", "2020/01/01", "-validity", "1"]),
]:
    keytool("-genkeypair", "-alias", "fixture", "-keyalg", "RSA", "-keysize", "2048", "-dname", "CN=" + name,
            "-keystore", store, "-storetype", "PKCS12", "-storepass", password, *extra)
keytool("-exportcert", "-rfc", "-alias", "fixture", "-keystore", ca_store, "-storepass", password, "-file", "ca.pem")
keytool("-exportcert", "-rfc", "-alias", "fixture", "-keystore", expired_store, "-storepass", password, "-file", "expired.pem")
keytool("-certreq", "-alias", "fixture", "-keystore", leaf_store, "-storepass", password, "-file", "leaf.csr")
keytool("-gencert", "-rfc", "-alias", "fixture", "-keystore", ca_store, "-storepass", password,
        "-infile", "leaf.csr", "-outfile", "leaf.pem", "-ext", "SAN=dns:localhost", "-ext", "EKU=serverAuth", "-validity", "1")
(FIXTURE / "invalid.pem").write_text("-----BEGIN CERTIFICATE-----\ninvalid\n-----END CERTIFICATE-----\n")
(FIXTURE / "TrustStoreProbe.java").write_text(r'''import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.security.*;
import java.security.cert.*;
import javax.net.ssl.*;
import java.util.*;
public class TrustStoreProbe {
    public static void main(String[] args) throws Exception {
        boolean expected = !Arrays.asList(args).contains("--without-custom-ca");
        if (!System.getProperty("user.name").equals("appuser")) throw new AssertionError("Runtime must be non-root appuser");
        if (!Arrays.asList(args).contains("--probe-argument")) throw new AssertionError("App arguments lost");
        var original = KeyStore.getInstance(Path.of(System.getenv("JAVA_HOME"), "lib/security/cacerts").toFile(), "changeit".toCharArray());
        if (expected) {
            var path = Path.of(System.getProperty("javax.net.ssl.trustStore"));
            var active = KeyStore.getInstance(path.toFile(), System.getProperty("javax.net.ssl.trustStorePassword").toCharArray());
            if (active.size() != original.size() + 1 || !active.containsAlias("brandista-aiven-ca")) throw new AssertionError("CA count mismatch");
            for (var aliases = original.aliases(); aliases.hasMoreElements();) {
                String alias = aliases.nextElement();
                if (!original.getCertificate(alias).equals(active.getCertificate(alias))) throw new AssertionError("Lost public CA: " + alias);
            }
            if (!Files.getPosixFilePermissions(path).equals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)))
                throw new AssertionError("Truststore permissions are not 0600");
            if (!Files.getPosixFilePermissions(path.getParent()).equals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE)))
                throw new AssertionError("Trust directory permissions are not 0700");
            System.out.println("Public CAs preserved: " + original.size() + "; active count: " + active.size());
        } else if (System.getProperty("javax.net.ssl.trustStore") != null) throw new AssertionError("Local mode unexpectedly changed truststore");
        var factory = CertificateFactory.getInstance("X.509");
        X509Certificate leaf, ca;
        try (var in = Files.newInputStream(Path.of("/fixtures/leaf.pem"))) { leaf = (X509Certificate)factory.generateCertificate(in); }
        try (var in = Files.newInputStream(Path.of("/fixtures/ca.pem"))) { ca = (X509Certificate)factory.generateCertificate(in); }
        var managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        managers.init((KeyStore)null);
        boolean trusted = false;
        for (var manager : managers.getTrustManagers()) if (manager instanceof X509TrustManager tm) {
            try { tm.checkServerTrusted(new X509Certificate[]{leaf, ca}, "RSA"); trusted = true; }
            catch (CertificateException untrusted) { }
        }
        if (trusted != expected) throw new AssertionError("Unexpected synthetic server-chain trust: " + trusted);
        System.out.println("Synthetic server-chain trust: " + trusted);
    }
}
''')
run([java_home / "bin" / ("javac" + exe), "--release", "21", "TrustStoreProbe.java"])
run([java_home / "bin" / ("jar" + exe), "--create", "--file", "probe.jar", "--main-class", "TrustStoreProbe", "TrustStoreProbe.class"])
(FIXTURE / ".dockerignore").write_text("*\n!Dockerfile\n!probe.jar\n!*.pem\n")
(FIXTURE / "Dockerfile").write_text("""FROM brandista-render-ca:test
USER root
COPY *.pem /fixtures/
COPY probe.jar /app/brandista.jar
RUN mkdir -p /etc/secrets && cp /fixtures/ca.pem /etc/secrets/ca.pem && chown root:1000 /etc/secrets/ca.pem && chmod 640 /etc/secrets/ca.pem
USER appuser
""")
run(["docker", "build", "-t", "brandista-render-ca-fixture:test", "."])
base = ["docker", "run", "--rm", "--read-only", "--tmpfs", "/tmp:rw,noexec,nosuid,size=16m", "-e", "JAVA_TOOL_OPTIONS=-Xmx128m"]
for name, env, argument in [
    ("explicit CA, preserved roots, restrictive Render file permissions", ["-e", "AIVEN_CA_CERT=/etc/secrets/ca.pem"], []),
    ("Render default path", ["-e", "RENDER=true"], []),
    ("unchanged local Docker defaults", [], ["--without-custom-ca"]),
]:
    output = run(base + env + ["brandista-render-ca-fixture:test", "--probe-argument"] + argument)
    print("PASS:", name, "\n" + output)
for name, filename, message in [
    ("missing", "/fixtures/missing.pem", "missing, empty, or unreadable"),
    ("malformed", "/fixtures/invalid.pem", "valid X.509 CA"),
    ("expired", "/fixtures/expired.pem", "validity period"),
    ("non-CA", "/fixtures/leaf.pem", "valid X.509 CA"),
]:
    output = run(base + ["-e", "AIVEN_CA_CERT=" + filename, "brandista-render-ca-fixture:test"], expected=1)
    if message not in output: raise AssertionError("Incorrect failure diagnostic for " + name + ": " + output)
    print("PASS: rejects", name, "certificate")
# Disposable private keys never enter a Docker image or Git; remove them after verification.
for path in [ca_store, leaf_store, expired_store]: path.unlink()
