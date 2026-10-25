# PROD-001 sanitized evidence

Accepted disposable run,2026-10-25. Source and resources are recorded in `load-environment.json`. No passwords, bearer tokens, invitation tokens, signing keys or raw SQL/data dumps are committed here.

- `load-results.json`: exact stage and per-route counts, nearest-rank latency percentiles in milliseconds, status/errors, resource samples and cumulative normalized database observations. Only10/50-user stages were executed;50 failed route escalation gates.
- `mysql-resource-samples.jsonl`: Docker MySQL CPU/memory samples across fixture/load/restore lifecycle. Use timestamps to restrict a stage window; lifetime maxima must not be presented as steady-state load maxima.
- `commercial-acceptance.json`, `load-integrity.json`: actual automated MySQL correctness scope. No device or production invitation delivery acceptance is implied.
- `restore.json`: consistent synthetic dump restored into a new schema and six count comparisons; not a production RPO/RTO test.
- `proxy-results.json`: isolated HTTPS Nginx component test with stub upstream, readiness mapping and429/413/404 assertions.
- `backend-runtime-dependencies.txt`, `security-summary.json`: resolved Maven runtime inventory and OSV before/after version matches. Matches require applicability review; absence of matches is not binary/image clearance.

Raw logs, failed exploratory runs and synthetic dumps remain outside Git in the task evidence folder or ignored `target/`. The post-measurement MVC503 response fix does not alter these recorded latency/status measurements.
