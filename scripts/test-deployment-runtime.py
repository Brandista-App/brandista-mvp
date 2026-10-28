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
import datetime
import re
import threading
import urllib.error
import urllib.request

INVESTIGATE = os.environ.get("HEALTH_INVESTIGATION") == "true"
CPU = os.environ.get("RUNTIME_CPU_LIMIT", "0.5")
if CPU not in ("0.5", "0.1", "0.15"):
    raise ValueError("Only isolated fixture CPU limits 0.5, 0.1 and 0.15 are supported")
CONTROLLED = os.environ.get("CONTROLLED_015") == "true"
NEGATIVE_PROFILE = os.environ.get("NEGATIVE_PROFILE_CONTROL") == "true"
STARTUP_RETRY = os.environ.get("STARTUP_RETRY_CONTROL") == "true"
if STARTUP_RETRY and (not CONTROLLED or NEGATIVE_PROFILE):
    raise ValueError("Startup retry control requires the controlled production-profile case")
if CONTROLLED and (CPU != "0.15" or not INVESTIGATE):
    raise ValueError("Controlled reproduction requires investigation mode and exactly 0.15 CPU")

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
health_observations = []
resource_samples = []
sample_lock = threading.Lock()
sample_stop = threading.Event()
sample_thread = None
phase = "fixture-preparation"
target_app = None
target_started_at = None
target_ready = False


def run(*args, input=None, check=True, env=None, timeout=180):
    result = subprocess.run(args, input=input, text=True, capture_output=True,
                            cwd=ROOT, env=env, timeout=timeout)
    if check and result.returncode:
        # Never print a command/environment containing disposable credentials.
        raise RuntimeError("Fixture command failed: " + args[0] + ": " + scrub(result.stderr[-2000:]))
    return result


def scrub(value):
    for secret in hidden:
        value = value.replace(secret, "[REDACTED]")
    return value


def private_http(name, path):
    script = ('exec 3<>/dev/tcp/127.0.0.1/9091; printf "GET ' + path +
              ' HTTP/1.0\\r\\nHost: localhost\\r\\nConnection: close\\r\\n\\r\\n" >&3; cat <&3')
    return run("docker", "exec", name, "bash", "-c", script, check=False, timeout=8)


def capture_resources(name, sample_phase):
    sample = {"phase": sample_phase, "atUtc": datetime.datetime.now(datetime.timezone.utc).isoformat(),
              "elapsedSeconds": round(time.monotonic() - target_started_at, 3)}
    try:
        command = r'''for metric in cpu.max cpu.stat memory.current memory.peak memory.events; do
printf '\n@@%s\n' "$metric"
cat "/sys/fs/cgroup/$metric" 2>/dev/null || true
done
printf '\n@@process\n'
cat /proc/1/status'''
        raw = run("docker", "exec", name, "sh", "-c", command, check=False, timeout=8)
        parts = {}
        key = None
        for line in raw.stdout.splitlines():
            if line.startswith("@@"):
                key = line[2:]
                parts[key] = []
            elif key:
                parts[key].append(line)
        sample["cpuMax"] = " ".join(parts.get("cpu.max", [])).strip()
        sample["cpuStat"] = {row.split()[0]: int(row.split()[1]) for row in parts.get("cpu.stat", []) if len(row.split()) == 2}
        for metric in ("memory.current", "memory.peak"):
            value = "".join(parts.get(metric, [])).strip()
            sample[metric] = int(value) if value else None
        sample["memory.events"] = {row.split()[0]: int(row.split()[1]) for row in parts.get("memory.events", []) if len(row.split()) == 2}
        sample["process"] = [row for row in parts.get("process", []) if row.startswith(("VmRSS:", "VmHWM:", "Threads:"))]
        metrics = private_http(name, "/actuator/prometheus")
        allowed = {"hikaricp_connections", "hikaricp_connections_active", "hikaricp_connections_idle",
                   "hikaricp_connections_pending", "hikaricp_connections_max", "hikaricp_connections_min",
                   "hikaricp_connections_timeout_total", "hikaricp_connections_acquire_seconds_count",
                   "hikaricp_connections_acquire_seconds_sum"}
        pool = {}
        for line in metrics.stdout.splitlines():
            metric_name = line.split("{", 1)[0].split(" ", 1)[0]
            if metric_name in allowed:
                pool[metric_name] = float(line.split()[-1])
        sample["pool"] = pool
    except (OSError, ValueError, subprocess.TimeoutExpired) as error:
        sample["samplingErrorType"] = type(error).__name__
    with sample_lock:
        previous = next((x for x in reversed(resource_samples) if x.get("cpuStat")), None)
        if previous and sample.get("cpuStat"):
            elapsed = sample["elapsedSeconds"] - previous["elapsedSeconds"]
            if elapsed > 0:
                sample["cpuUsageCoresSincePreviousSample"] = round((sample["cpuStat"]["usage_usec"] - previous["cpuStat"]["usage_usec"]) / (elapsed * 1000000), 5)
        resource_samples.append(sample)
        (EVIDENCE / "resource-samples.json").write_text(json.dumps(resource_samples, indent=2))
    return sample


def collect_resources(name):
    while not sample_stop.is_set():
        capture_resources(name, phase)
        sample_stop.wait(10)


def startup_milestone(name):
    logs = run("docker", "logs", name, check=False)
    match = re.search(r"Started BrandistaApplication in ([0-9.]+) seconds", logs.stdout + logs.stderr)
    return float(match.group(1)) if match else None


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
        with urllib.request.build_opener(urllib.request.ProxyHandler({})).open(req, timeout=5) as response:
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


def observe(base, path, label, headers=None):
    started = time.monotonic()
    item = {"label": label, "path": path}
    try:
        code, body, elapsed, _ = request(base, path, headers=headers)
        item.update(httpStatus=code, seconds=round(elapsed, 3))
        try:
            item["healthStatus"] = json.loads(body).get("status")
        except (ValueError, AttributeError):
            pass
    except (OSError, urllib.error.URLError) as error:
        item.update(httpStatus=None, seconds=round(time.monotonic()-started, 3),
                    errorType=type(getattr(error, "reason", error)).__name__)
    health_observations.append(item)
    (EVIDENCE / "health-observations.json").write_text(json.dumps(health_observations, indent=2))
    return item


def wait_ready(name, base, timeout=None):
    timeout = timeout or (900 if INVESTIGATE else 180)
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        state = json.loads(run("docker", "inspect", "--format", "{{json .State}}", name).stdout)
        if not state["Running"]:
            raise AssertionError(f"{name}: exited {state['ExitCode']}, OOMKilled={state['OOMKilled']}")
        try:
            probe_path = "/actuator/health/readiness" if NEGATIVE_PROFILE and "-default-" in name else "/readyz"
            probe = observe(base, probe_path, "startup:" + name) if INVESTIGATE else {"httpStatus": request(base, probe_path)[0]}
            if probe["httpStatus"] == 200:
                return
        except (OSError, TimeoutError):
            pass
        time.sleep(2)
    raise AssertionError("Application did not become ready within bounded fixture startup window")


def launch(label, user, password, options=None, cpu=None):
    name = "brandista-" + label + "-" + nonce
    env_path = WORK / (label + ".env")
    values = {
        "RENDER": "true", "AIVEN_CA_CERT": "/etc/secrets/ca.pem",
        "SPRING_PROFILES_ACTIVE": "production", "SERVER_PORT": "10000",
        "SPRING_DATASOURCE_URL": "jdbc:mysql://brandista-db:3306/brandista?sslMode=VERIFY_IDENTITY" +
            ("" if INVESTIGATE else "&connectTimeout=3000&socketTimeout=3000"),
        "SPRING_DATASOURCE_USERNAME": user, "SPRING_DATASOURCE_PASSWORD": password,
        "JWT_SECRET_BASE64": jwt,
        "CORS_ALLOWED_ORIGINS": "https://brandistaapp.com,https://admin.brandistaapp.com",
    }
    if options:
        values.update(JAVA_TOOL_OPTIONS="-Xms64m -Xmx256m -XX:+ExitOnOutOfMemoryError",
                      DB_POOL_MAX="4", DB_POOL_MIN="2", HTTP_THREADS_MAX="16")
    if NEGATIVE_PROFILE and label == "default":
        values.pop("SPRING_PROFILES_ACTIVE")
    if STARTUP_RETRY and label == "default":
        values["SPRING_DATASOURCE_HIKARI_INITIALIZATION_FAIL_TIMEOUT"] = "60000"
    env_path.write_text("".join(f"{k}={v}\n" for k, v in values.items()))
    env_path.chmod(0o600)
    containers.append(name)
    run("docker", "run", "-d", "--name", name, "--network", network,
        "--memory=512m", "--memory-swap=512m", "--cpus=" + (cpu or CPU),
        "--env-file", str(env_path), "-v", f"{WORK / 'ca.pem'}:/etc/secrets/ca.pem:ro",
        "-p", "127.0.0.1::10000", "brandista-render-ca:test")
    port = run("docker", "port", name, "10000/tcp").stdout.strip().rsplit(":", 1)[1]
    return name, "http://127.0.0.1:" + port


try:
    ergonomics = run("docker", "run", "--rm", "--memory=512m", "--memory-swap=512m",
                     "--cpus=" + CPU, "--entrypoint", "java", "brandista-render-ca:test",
                     "-XshowSettings:all", "-version")
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
    # Prepare a current V14 database without making setup CPU the target condition.
    migrator, base = launch("migrate", "brandista_migrator", migration_password, options=True,
                            cpu="0.5" if CONTROLLED else None)
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

    configurations = [("default", False)] if INVESTIGATE else [("default", False), ("bounded", True)]
    for label, bounded in configurations:
        started = time.monotonic()
        app, base = launch(label, "brandista_app", runtime_password, options=bounded)
        if CONTROLLED:
            target_app, target_started_at = app, started
            phase = "startup"
            limits = json.loads(run("docker", "inspect", "--format", "{{json .HostConfig}}", app).stdout)
            verified = {k: limits[k] for k in ("NanoCpus", "Memory", "MemorySwap")}
            if verified != {"NanoCpus": 150000000, "Memory": 536870912, "MemorySwap": 536870912}:
                raise AssertionError("Controlled fixture limits do not match 0.15 CPU / 512 MiB / no swap")
            (EVIDENCE / "verified-container-limits.json").write_text(json.dumps(verified, indent=2))
            sample_thread = threading.Thread(target=collect_resources, args=(app,), daemon=True)
            sample_thread.start()
        wait_ready(app, base)
        target_ready = True
        entry = {"configuration": label, "startupSeconds": round(time.monotonic() - started, 3),
                 "memoryLimitBytes": 512 * 1024 * 1024, "cpuQuota": float(CPU), "samples": []}
        if CONTROLLED:
            entry["springStartupSeconds"] = startup_milestone(app)
            entry["startupRetryTimeoutOverrideMs"] = 60000 if STARTUP_RETRY else None
            if NEGATIVE_PROFILE:
                phase = "missing-profile-control"
                probes = [observe(base, path, phase) for path in (
                    "/readyz", "/livez", "/actuator/health/readiness", "/actuator/health/liveness")]
                if [probe["httpStatus"] for probe in probes] != [404, 404, 200, 200]:
                    raise AssertionError("Missing-profile negative control did not match expected routing")
                logs = run("docker", "logs", app, check=False)
                if "No active profile set" not in logs.stdout + logs.stderr:
                    raise AssertionError("Negative control unexpectedly had an active profile")
                entry["missingProfileProbes"] = probes
                entry["sourceRevision"] = "773623a9ddea2211be5088a5ce5e3667f286b261"
                entry["resources"] = capture_resources(app, phase)
                results.append(entry)
                (EVIDENCE / "results.json").write_text(json.dumps(results, indent=2))
                print("PASS: exact 0.15 CPU missing-profile control: aliases 404, actuator groups 200")
                run("docker", "stop", app)
                continue
            phase = "healthy-post-start"
            entry["initialPoolAndResources"] = capture_resources(app, phase)
            # Match the owner's eleven-minute interval after successful startup.
            deadline = time.monotonic() + 660
            healthy = []
            while time.monotonic() < deadline:
                for path in ("/livez", "/readyz"):
                    healthy.append(observe(base, path, "healthy-post-start"))
                time.sleep(min(10, max(0, deadline-time.monotonic())))
            entry["healthySoakSeconds"] = 660
            entry["healthySoakProbeCount"] = len(healthy)
            entry["healthySoakFailures"] = [x for x in healthy if x["httpStatus"] != 200 or x["seconds"] >= 5]
            if entry["healthySoakFailures"]:
                (EVIDENCE / "results.json").write_text(json.dumps([entry], indent=2))
                raise AssertionError("Post-start healthy-DB probes failed; see recorded status/timing")
        if INVESTIGATE:
            entry["sourceRevision"] = "773623a9ddea2211be5088a5ce5e3667f286b261"
            entry["jdbcTimeoutOverrides"] = False
            networks = json.loads(run("docker", "inspect", "--format", "{{json .NetworkSettings.Networks}}", app).stdout)
            address = networks[network]["IPAddress"]
            for host in ("brandista-mvp.onrender.com", "api.brandistaapp.com"):
                for path in ("/readyz", "/livez"):
                    probe = observe("http://"+address+":10000", path, "bridge:"+host, {"Host": host})
                    if probe["httpStatus"] != 200:
                        raise AssertionError("App-port bridge/Render Host-header health failed")
            for path in ("/actuator/health/readiness", "/actuator/health/liveness"):
                # Same network namespace reaches the intentionally private loopback listener.
                script = ('exec 3<>/dev/tcp/127.0.0.1/9091; printf "GET ' + path +
                          ' HTTP/1.1\\r\\nHost: localhost\\r\\nConnection: close\\r\\n\\r\\n" >&3; cat <&3')
                reply = run("docker", "exec", app, "bash", "-c", script).stdout
                if reply.splitlines()[0].split()[1] != "200":
                    raise AssertionError("Loopback management health failed")
            private = observe("http://"+address+":9091", "/actuator/health/readiness", "bridge:private-management")
            if private["httpStatus"] is not None:
                raise AssertionError("Management unexpectedly reachable on bridge IP")
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
        if INVESTIGATE:
            phase = "database-frozen"
            run("docker", "pause", database)
            try:
                # A frozen server keeps TCP established but cannot answer a JDBC ping.
                entry["silentDatabase"] = observe(base, "/readyz", "database-paused")
                expect(base, "/livez", 200)
                observe(base, "/livez", "database-paused")
                if CONTROLLED:
                    entry["frozenPoolAndResources"] = capture_resources(app, phase)
            finally:
                run("docker", "unpause", database)
            phase = "database-unfrozen-recovery"
            wait_ready(app, base, timeout=90)
            if CONTROLLED:
                observe(base, "/readyz", "database-unfrozen-recovered")
                entry["unfrozenPoolAndResources"] = capture_resources(app, phase)
        phase = "database-stopped"
        run("docker", "stop", database)
        expect(base, "/livez", 200)
        expect(base, "/readyz", 503)
        if INVESTIGATE:
            observe(base, "/readyz", "database-stopped")
            observe(base, "/livez", "database-stopped")
        run("docker", "start", database)
        phase = "database-restarted-recovery"
        wait_ready(app, base, timeout=90)
        entry["databaseOutageAndRecovery"] = "PASS: livez=200, readyz=503, recovered=200"
        if CONTROLLED:
            for _ in range(3):
                observe(base, "/readyz", "database-restarted-recovered")
                observe(base, "/livez", "database-restarted-recovered")
            entry["recoveredPoolAndResources"] = capture_resources(app, phase)
            phase = "controlled-shutdown"
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
        print("PASS:", label, "512 MiB /", CPU, "CPU TLS MySQL runtime, API/CORS/authorization and outage/recovery")
finally:
    if sample_thread:
        sample_stop.set()
        sample_thread.join(timeout=20)
    if target_app:
        spring_started = startup_milestone(target_app)
        classification = {"springStartedLogSeen": spring_started is not None,
                          "springStartupSeconds": spring_started, "firstReadiness200Seen": target_ready,
                          "lastPhase": phase,
                          "classification": "started-and-ready" if target_ready else
                              "started-but-never-ready" if spring_started is not None else "startup-failure"}
        (EVIDENCE / "startup-classification.json").write_text(json.dumps(classification, indent=2))
    for name in reversed(containers):
        logs = run("docker", "logs", name, check=False)
        (EVIDENCE / (name + ".log")).write_text(scrub(logs.stdout + logs.stderr))
        run("docker", "rm", "-f", "-v", name, check=False)
    run("docker", "network", "rm", network, check=False)
    for path in WORK.glob("*.env"):
        path.unlink()
    for path in WORK.glob("*.key"):
        path.unlink()
