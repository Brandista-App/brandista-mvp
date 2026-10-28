"""Cloud-only disposable production-image smoke: real TLS MySQL, no external target.

Requires the image built by backend-tests.yml and a Linux Docker daemon. All
credentials/certificates are freshly generated under ignored target; only the
sanitized evidence subdirectory is uploaded. No deployment credentials accepted.
"""
import base64
import json
import os
import pathlib
import secrets
import subprocess
import time
import urllib.error
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parents[1]
WORK = ROOT / "target" / "deployment-runtime"
EVIDENCE = WORK / "evidence"
EVIDENCE.mkdir(parents=True, exist_ok=True)
nonce = secrets.token_hex(5)
network, database = "brandista-smoke-" + nonce, "brandista-db-" + nonce
containers, hidden, results = [], [], []
root_password, migration_password, runtime_password = [secrets.token_hex(24) for _ in range(3)]
jwt = base64.b64encode(secrets.token_bytes(32)).decode()
hidden.extend([root_password, migration_password, runtime_password, jwt])


def run(*args, input=None, check=True, env=None):
    result = subprocess.run(args, input=input, text=True, capture_output=True,
                            cwd=ROOT, env=env, timeout=180)
    if check and result.returncode:
        # Never print a command/environment containing disposable credentials.
        raise RuntimeError("Fixture command failed: " + args[0] + ": " + scrub(result.stderr[-2000:]))
    return result


def scrub(value):
    for secret in hidden:
        value = value.replace(secret, "[REDACTED]")
    return value


def sql(statement, user="root", password=root_password, check=True):
    env = dict(os.environ, MYSQL_PWD=password)
    return run("docker", "exec", "-i", "--env", "MYSQL_PWD", database,
               "mysql", "--protocol=TCP", "-h127.0.0.1", "--ssl-mode=REQUIRED",
               "-u" + user, "-N", input=statement, env=env, check=check)


def request(base, path, method="GET", body=None, headers=None):
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(base + path, data=data, method=method,
                                 headers={"Content-Type": "application/json", **(headers or {})})
    start = time.monotonic()
    try:
        with urllib.request.urlopen(req, timeout=5) as response:
            return response.status, response.read(), time.monotonic() - start, response.headers
    except urllib.error.HTTPError as error:
        return error.code, error.read(), time.monotonic() - start, error.headers


def expect(base, path, status, **kwargs):
    actual, body, elapsed, headers = request(base, path, **kwargs)
    if actual != status:
        raise AssertionError(f"{path}: expected {status}, got {actual}")
    if elapsed >= 5:
        raise AssertionError(f"{path}: exceeds Render's five-second health budget")
    return body, headers


def wait_ready(name, base, timeout=180):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        state = json.loads(run("docker", "inspect", "--format", "{{json .State}}", name).stdout)
        if not state["Running"]:
            raise AssertionError(f"{name}: exited {state['ExitCode']}, OOMKilled={state['OOMKilled']}")
        try:
            if request(base, "/readyz")[0] == 200:
                return
        except (OSError, TimeoutError):
            pass
        time.sleep(2)
    raise AssertionError("Application did not become ready within bounded fixture startup window")


def launch(label, user, password, options=None):
    name = "brandista-" + label + "-" + nonce
    env_path = WORK / (label + ".env")
    values = {
        "RENDER": "true", "AIVEN_CA_CERT": "/etc/secrets/ca.pem",
        "SPRING_PROFILES_ACTIVE": "production", "SERVER_PORT": "10000",
        "SPRING_DATASOURCE_URL": "jdbc:mysql://brandista-db:3306/brandista?sslMode=VERIFY_IDENTITY&connectTimeout=3000&socketTimeout=3000",
        "SPRING_DATASOURCE_USERNAME": user, "SPRING_DATASOURCE_PASSWORD": password,
        "JWT_SECRET_BASE64": jwt,
        "CORS_ALLOWED_ORIGINS": "https://brandistaapp.com,https://admin.brandistaapp.com",
    }
    if options:
        values.update(JAVA_TOOL_OPTIONS="-Xms64m -Xmx256m -XX:+ExitOnOutOfMemoryError",
                      DB_POOL_MAX="4", DB_POOL_MIN="2", HTTP_THREADS_MAX="16")
    env_path.write_text("".join(f"{k}={v}\n" for k, v in values.items()))
    env_path.chmod(0o600)
    containers.append(name)
    run("docker", "run", "-d", "--name", name, "--network", network,
        "--memory=512m", "--memory-swap=512m", "--cpus=0.5",
        "--env-file", str(env_path), "-v", f"{WORK / 'ca.pem'}:/etc/secrets/ca.pem:ro",
        "-p", "127.0.0.1::10000", "brandista-render-ca:test")
    port = run("docker", "port", name, "10000/tcp").stdout.strip().rsplit(":", 1)[1]
    return name, "http://127.0.0.1:" + port


try:
    ergonomics = run("docker", "run", "--rm", "--memory=512m", "--memory-swap=512m",
                     "--cpus=0.5", "--entrypoint", "java", "brandista-render-ca:test",
                     "-XshowSettings:system", "-XshowSettings:vm", "-version")
    (EVIDENCE / "linux-jvm-ergonomics.txt").write_text(ergonomics.stdout + ergonomics.stderr)
    # Synthetic CA/server certificates. Private files are never artifact inputs.
    run("openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "2",
        "-subj", "/CN=Brandista disposable CI CA", "-addext", "basicConstraints=critical,CA:TRUE",
        "-keyout", str(WORK / "ca.key"), "-out", str(WORK / "ca.pem"))
    run("openssl", "req", "-newkey", "rsa:2048", "-nodes", "-subj", "/CN=brandista-db",
        "-keyout", str(WORK / "server.key"), "-out", str(WORK / "server.csr"))
    (WORK / "server.ext").write_text("subjectAltName=DNS:brandista-db\nextendedKeyUsage=serverAuth\n")
    run("openssl", "x509", "-req", "-days", "2", "-in", str(WORK / "server.csr"),
        "-CA", str(WORK / "ca.pem"), "-CAkey", str(WORK / "ca.key"), "-CAcreateserial",
        "-extfile", str(WORK / "server.ext"), "-out", str(WORK / "server.pem"))
    # Disposable fixture leaf key must be readable by MySQL's container uid.
    (WORK / "server.key").chmod(0o644)
    run("docker", "network", "create", network)
    containers.append(database)
    run("docker", "run", "-d", "--name", database, "--network", network,
        "--network-alias", "brandista-db", "--env", "MYSQL_ROOT_PASSWORD",
        "--env", "MYSQL_DATABASE=brandista", "-v", f"{WORK}:/fixtures:ro",
        "mysql:8.4.11-oraclelinux9", "--ssl-ca=/fixtures/ca.pem",
        "--ssl-cert=/fixtures/server.pem", "--ssl-key=/fixtures/server.key",
        "--require-secure-transport=ON", env=dict(os.environ, MYSQL_ROOT_PASSWORD=root_password))
    for attempt in range(90):
        if sql("SELECT 1", check=False).returncode == 0:
            break
        time.sleep(2)
    else:
        raise AssertionError("Disposable TLS MySQL did not start")
    sql(f"CREATE USER 'brandista_migrator'@'%' IDENTIFIED BY '{migration_password}' REQUIRE SSL;"
        "GRANT SELECT,INSERT,UPDATE,DELETE,CREATE,ALTER,INDEX,REFERENCES ON brandista.* TO 'brandista_migrator'@'%';"
        f"CREATE USER 'brandista_app'@'%' IDENTIFIED BY '{runtime_password}' REQUIRE SSL;")

    # Apply the exact image's Flyway migrations, then remove DDL credentials.
    migrator, base = launch("migrate", "brandista_migrator", migration_password, options=True)
    wait_ready(migrator, base)
    history = sql("SELECT version,success FROM brandista.flyway_schema_history ORDER BY installed_rank").stdout
    if len(history.strip().splitlines()) != 14 or history.strip().splitlines()[-1] != "14\t1":
        raise AssertionError("Flyway V1-V14 did not all succeed")
    (EVIDENCE / "flyway-history.tsv").write_text(history)
    run("docker", "stop", migrator)
    tables = sql("SHOW TABLES FROM brandista").stdout.splitlines()
    for table in tables:
        privileges = ("SELECT" if table == "flyway_schema_history" else
                      "SELECT,UPDATE" if table == "security_provisioning_state" else
                      "SELECT,INSERT" if table in ("security_audit_events", "merchant_order_events") else
                      "SELECT,INSERT,UPDATE,DELETE")
        sql(f"GRANT {privileges} ON brandista.`{table}` TO 'brandista_app'@'%';")
    if sql("UPDATE brandista.security_audit_events SET reason=reason WHERE 1=0",
           "brandista_app", runtime_password, check=False).returncode == 0:
        raise AssertionError("Runtime unexpectedly has audit UPDATE privilege")

    for label, bounded in [("default", False), ("bounded", True)]:
        started = time.monotonic()
        app, base = launch(label, "brandista_app", runtime_password, options=bounded)
        wait_ready(app, base)
        entry = {"configuration": label, "startupSeconds": round(time.monotonic() - started, 3),
                 "memoryLimitBytes": 512 * 1024 * 1024, "cpuQuota": 0.5, "samples": []}
        for _ in range(6):
            for path in ("/livez", "/readyz", "/api/v1/brands"):
                expect(base, path, 200)
            entry["samples"].append(json.loads(run("docker", "stats", "--no-stream", "--format", "{{json .}}", app).stdout))
        for path in ("/actuator/prometheus", "/actuator/metrics"):
            if request(base, path)[0] < 400:
                raise AssertionError("Private management data exposed on public listener")
        _, headers = expect(base, "/api/v1/brands", 200, method="OPTIONS", headers={
            "Origin": "https://admin.brandistaapp.com", "Access-Control-Request-Method": "GET",
            "Access-Control-Request-Headers": "Authorization"})
        if headers.get("Access-Control-Allow-Origin") != "https://admin.brandistaapp.com":
            raise AssertionError("Expected exact admin CORS origin")
        expect(base, "/api/v1/brands", 403, method="OPTIONS", headers={
            "Origin": "https://foreign.example.invalid", "Access-Control-Request-Method": "GET"})
        account = {"email": label + "@example.invalid", "password": secrets.token_hex(18)}
        hidden.append(account["password"])
        expect(base, "/api/v1/registrations", 201, method="POST", body=account)
        body, _ = expect(base, "/api/v1/auth/login", 200, method="POST", body=account)
        token = json.loads(body)["accessToken"]
        hidden.append(token)
        expect(base, "/api/v1/super-admin/identity", 403, headers={"Authorization": "Bearer " + token})
        expect(base, "/api/v1/brand-applications", 201, method="POST", body={
            "brandName": "Synthetic " + label, "contactName": "CI Fixture",
            "contactEmail": account["email"], "website": "https://example.invalid"})
        run("docker", "stop", database)
        expect(base, "/livez", 200)
        expect(base, "/readyz", 503)
        run("docker", "start", database)
        wait_ready(app, base, timeout=90)
        entry["databaseOutageAndRecovery"] = "PASS: livez=200, readyz=503, recovered=200"
        entry["processMemoryStatus"] = "\n".join(line for line in run(
            "docker", "exec", app, "cat", "/proc/1/status").stdout.splitlines()
            if line.startswith(("VmRSS:", "VmHWM:", "Threads:")))
        for filename in ("memory.peak", "memory.events"):
            metric = run("docker", "exec", app, "cat", "/sys/fs/cgroup/" + filename, check=False)
            entry[filename] = metric.stdout.strip() if metric.returncode == 0 else None
        run("docker", "stop", app)
        state = json.loads(run("docker", "inspect", "--format", "{{json .State}}", app).stdout)
        entry.update(exitCode=state["ExitCode"], oomKilled=state["OOMKilled"])
        if state["OOMKilled"] or state["ExitCode"] != 143 and state["ExitCode"] != 0:
            raise AssertionError("Unexpected application shutdown/OOM")
        results.append(entry)
        (EVIDENCE / "results.json").write_text(json.dumps(results, indent=2))
        print("PASS:", label, "512 MiB / 0.5 CPU TLS MySQL runtime, API/CORS/authorization and outage/recovery")
finally:
    for name in reversed(containers):
        logs = run("docker", "logs", name, check=False)
        (EVIDENCE / (name + ".log")).write_text(scrub(logs.stdout + logs.stderr))
        run("docker", "rm", "-f", "-v", name, check=False)
    run("docker", "network", "rm", network, check=False)
    for path in WORK.glob("*.env"):
        path.unlink()
    for path in WORK.glob("*.key"):
        path.unlink()
