// Offline evidence summary only. This script never connects to an application or database.
import fs from 'node:fs'
import path from 'node:path'

const root = process.argv[2] ?? 'docs/performance/evidence/perf-001'
const runs = process.argv.slice(3)
function read(file) {
  try { return JSON.parse(fs.readFileSync(file, 'utf8')) }
  catch { throw new Error('Missing or invalid JSON evidence: ' + file) }
}
const max = values => {
  const present = values.filter(v => v !== null && v !== undefined && Number.isFinite(v))
  return present.length ? Math.max(...present) : null
}
const status = database => Object.fromEntries((database?.globalStatus ?? []).map(v => [v.Variable_name, Number(v.Value)]))
const output = {}
for (const run of runs.length ? runs : ['before', 'after']) {
  const folder = path.join(root, run)
  const results = read(path.join(folder, 'results.json'))
  const resources = fs.readFileSync(path.join(folder, 'mysql-resource-samples.jsonl'), 'utf8').trim().split(/\r?\n/).map((line, i) => {
    try { return JSON.parse(line) } catch { throw new Error('Invalid resource evidence line ' + (i + 1) + ' in ' + folder) }
  })
  let previous = fs.existsSync(path.join(folder, 'database-before.json')) ? read(path.join(folder, 'database-before.json')) : null
  output[run] = { environment: read(path.join(folder, 'environment.json')), stages: [] }
  for (const stage of results) {
    const times = stage.samples.map(s => Date.parse(s.time))
    const during = resources.filter(s => Date.parse(s.time) >= Math.min(...times) && Date.parse(s.time) <= Math.max(...times))
    const currentStatus = status(stage.database), previousStatus = status(previous)
    const deltas = Object.fromEntries(Object.entries(currentStatus).filter(([k]) => k in previousStatus).map(([k, v]) => [k, v - previousStatus[k]]))
    const timers = Object.fromEntries(Object.entries(stage.database.poolTimersCumulative ?? {}).map(([name, timer]) => {
      const before = previous?.poolTimersCumulative?.[name]
      const count = before ? timer.count - before.count : null
      return [name, { count, meanMs: count > 0 ? 1000 * (timer.totalSeconds - before.totalSeconds) / count : null }]
    }))
    const orders = previous ? stage.database.committedOrders - previous.committedOrders : null
    const failingRoutes = Object.entries(stage.routes).filter(([, r]) => r.requests >= 20 && r.p95 > 3000).map(([name]) => name)
    output[run].stages.push({ users: stage.users, peakActiveUsers: stage.peakActiveUsers, seconds: stage.seconds,
      requests: stage.requests, requestsPerSecond: stage.rps, p50Ms: stage.p50, p95Ms: stage.p95, p99Ms: stage.p99,
      errors: Object.values(stage.routes).reduce((sum, route) => sum + route.errors, 0), errorRate: stage.errorRate,
      committedOrders: orders, ordersPerSecond: orders === null ? null : orders / stage.seconds,
      gate: stage.aborted || stage.p95 > 3000 || stage.errorRate > .01 || stage.integrityErrors > 0 || failingRoutes.length ? 'FAIL' : 'PASS',
      failingRoutes, integrity: stage.database.integrity, monitorFailures: stage.monitorFailures,
      peakPoolActive: max(stage.samples.map(s => s.dbActive)), peakPoolPending: max(stage.samples.map(s => s.dbPending)), poolTimers: timers,
      peakBackendCpuFraction: max(stage.samples.map(s => s.cpu)), peakJvmUsedMiB: max(stage.samples.map(s => s.heapUsed === null ? null : s.heapUsed / 1048576)),
      peakBackendRssMiB: max(stage.samples.map(s => s.rss === null ? null : s.rss / 1048576)),
      peakGeneratorRssMiB: max(stage.samples.map(s => s.generatorRss / 1048576)),
      mysqlSamples: during.length, peakMysqlCpuPercent: max(during.map(s => parseFloat(s.stats.CPUPerc))),
      peakMysqlMemoryPercent: max(during.map(s => parseFloat(s.stats.MemPerc))),
      peakLockWaitEdges: max(during.map(s => s.lockWaitEdges)), peakOldestTransactionSeconds: max(during.map(s => s.oldestTransactionSeconds)),
      lockSampleFailures: during.filter(s => s.lockSampleOk === false).length,
      databaseCounterDeltas: deltas, routes: stage.routes })
    previous = stage.database
  }
}
console.log(JSON.stringify(output, null, 2))
