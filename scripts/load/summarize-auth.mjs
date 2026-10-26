// Offline analysis of sanitized PERF-002 request timings. No HTTP/database access.
import fs from 'node:fs'
const file=process.argv[2]
if(!file)throw new Error('Provide one auth-N.json evidence file')
let data
try{data=JSON.parse(fs.readFileSync(file,'utf8'))}catch{throw new Error('Invalid evidence: '+file)}
const percentile=(a,p)=>a.length?[...a].sort((a,b)=>a-b)[Math.min(a.length-1,Math.ceil(a.length*p)-1)]:null
const stats=a=>({samples:a.length,p50:percentile(a,.5),p95:percentile(a,.95),p99:percentile(a,.99),max:a.length?Math.max(...a):null,mean:a.length?a.reduce((a,b)=>a+b,0)/a.length:null})
const result={file,requests:data.requests.length,droppedTraces:data.droppedTraces,inflightAtSnapshot:data.inflightAtSnapshot,threadCpuAvailable:data.threadCpuAvailable,groups:{}}
for(const route of [...new Set(data.requests.map(r=>r.route))]) {
 const rows=data.requests.filter(r=>r.route===route)
 const keys=[...new Set(rows.flatMap(r=>Object.keys(r.timingsNanos)))]
 const timings=Object.fromEntries(keys.map(key=> {
  const measured=rows.filter(r=>r.timingsNanos[key])
  return [key,{invocations:measured.reduce((n,r)=>n+r.timingsNanos[key][0],0),
   wallMsPerRequest:stats(measured.map(r=>r.timingsNanos[key][1]/1e6)),
   cpuMsPerRequest:data.threadCpuAvailable&&!key.startsWith('db.hold')&&!key.startsWith('http.dispatchQueue')?stats(measured.map(r=>r.timingsNanos[key][2]/1e6)):null}]
 }))
 result.groups[route]={requests:rows.length,statuses:rows.reduce((o,r)=>(o[r.status]=(o[r.status]??0)+1,o),{}),timings,
  threadWaitedMs:stats(rows.map(r=>r.threadWaitedMs).filter(v=>v!==null)),
  threadBlockedMs:stats(rows.map(r=>r.threadBlockedMs).filter(v=>v!==null)),
  bcryptConnectionsAtEntry:stats(rows.map(r=>r.bcryptConnectionsAtEntry).filter(v=>v!==null)),
  peakConnectionsHeld:stats(rows.map(r=>r.peakConnectionsHeld))}
}
const max=key=>data.threadSamples.length?Math.max(...data.threadSamples.map(r=>r[key])):null
result.executor={samples:data.threadSamples.length,peakThreads:max('poolSize'),peakBusy:max('busy'),peakQueued:max('queued'),
 peakHttpStates:Object.fromEntries([...new Set(data.threadSamples.flatMap(r=>Object.keys(r.httpThreadStates)))].map(k=>[k,Math.max(...data.threadSamples.map(r=>r.httpThreadStates[k]??0))]))}
result.notes=['Timing stages are nested: do not sum them or subtract independent percentiles. Repeated calls are summed per request; invocations are also reported.',
 'JSON write includes configured converter serialization and output writes; its wall time can include output backpressure.',data.queueScope]
console.log(JSON.stringify(result,null,2))
