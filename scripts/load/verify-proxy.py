"""Isolated HTTPS proxy configuration/rate/body-limit verification, not backend acceptance."""
import json
import pathlib
import shutil
import ssl
import subprocess
import time
import urllib.error
import urllib.request
import uuid

root = pathlib.Path(__file__).resolve().parents[2]
out = root / "target/prod001-proxy"
out.mkdir(parents=True, exist_ok=True)
openssl = shutil.which("openssl") or r"C:\Program Files\Git\usr\bin\openssl.exe"
subprocess.run([openssl, "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "1",
                "-keyout", str(out / "privkey.pem"), "-out", str(out / "fullchain.pem"),
                "-subj", "/CN=example.invalid", "-addext", "subjectAltName=IP:127.0.0.1"],
               check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=30)
config = (root / "deploy/production-like/api.conf.template").read_text()
config = config.replace("${APPROVED_API_HOST}", "example.invalid").replace("backend:8080", "127.0.0.1:18080")
config += "\nserver { listen 18080; return 204; }\n"
(out / "default.conf").write_text(config)
name = "brandista-prod001-proxy-" + uuid.uuid4().hex[:10]
mounts = ["--volume", f"{out / 'default.conf'}:/etc/nginx/conf.d/default.conf:ro",
          "--volume", f"{out}:/etc/nginx/tls:ro"]
def docker(*args):
    return subprocess.check_output(["docker", *args], text=True, stderr=subprocess.STDOUT, timeout=60).strip()
try:
    docker("run", "--rm", "--label", "brandista.task=prod001-proxy", *mounts,
           "nginx:stable-alpine", "nginx", "-t")
    docker("run", "--rm", "-d", "--name", name, "--label", "brandista.task=prod001-proxy",
           "--memory=96m", "--cpus=0.25", "-p", "127.0.0.1::443", *mounts, "nginx:stable-alpine")
    binding = docker("port", name, "443/tcp")
    assert binding.startswith("127.0.0.1:")
    context = ssl.create_default_context(cafile=str(out / "fullchain.pem"))
    base = "https://" + binding
    def request(path, body=None):
        try:
            req = urllib.request.Request(base + path, data=body)
            with urllib.request.urlopen(req, context=context, timeout=5) as response:
                return response.status
        except urllib.error.HTTPError as error:
            return error.code
    time.sleep(2)
    assert request("/api/v1/products") == 204
    assert request("/actuator/prometheus") == 404
    assert request("/api/v1/products", b"x" * (2 * 1024 * 1024)) == 413
    statuses = [request("/api/v1/auth/login", b"{}") for _ in range(10)]
    assert 204 in statuses and 429 in statuses, statuses
    result = {"status":"PASS", "nginxConfig":"PASS", "trustedLocalTLS":"PASS",
              "identityStatuses":statuses, "oversizedBody":413, "publicMetrics":404,
              "scope":"isolated Nginx with stub upstream; not deployed backend/TLS acceptance"}
    (out / "results.json").write_text(json.dumps(result, indent=2))
    print(json.dumps(result))
finally:
    subprocess.run(["docker", "rm", "-f", name], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=30)
