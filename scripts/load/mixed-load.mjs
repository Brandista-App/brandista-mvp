// Called only by the opt-in disposable MySQL JUnit harness. No external URL option.
import { createInterface } from 'node:readline'
import { performance } from 'node:perf_hooks'
import { setTimeout as sleep } from 'node:timers/promises'
import os from 'node:os'
const input = createInterface({ input: process.stdin })
const fixture = JSON.parse(await new Promise(resolve => input.once('line', resolve)))
input.close()
if (fixture.guard !== 'brandista-prod001-disposable' ||
    !/^http:\/\/127\.0\.0\.1:\d+$/.test(fixture.base) ||
    !/^http:\/\/127\.0\.0\.1:\d+$/.test(fixture.management) || fixture.users.length !== 500)
  throw new Error('Only the parent-created isolated loopback fixture is allowed')
let stage, aborted = false, pending = 0
const stages = [10, 50, 100, 250, 500]
const percentile = (values, p) => values.length ? [...values].sort((a,b)=>a-b)[Math.min(values.length-1,Math.ceil(values.length*p)-1)] : null
async function request(label, path, token, body, method = body ? 'POST' : 'GET', extra = {}) {
  const start = performance.now(); pending++
  let status = 0, value = null
  try {
    const response = await fetch(fixture.base + path, {method, redirect:'error',
      signal:AbortSignal.timeout(6000), headers:{'Content-Type':'application/json',
        ...(token ? {Authorization:'Bearer '+token} : {}),...extra},
      ...(body ? {body:JSON.stringify(body)} : {})})
    status = response.status
    value = await response.json().catch(()=>null)
  } catch { /* timeout/transport errors count as failures; never log credentials */ }
  finally { pending-- }
  const elapsed = performance.now()-start
  const bucket = stage.routes[label] ??= {latency:[], statuses:{}, errors:0}
  bucket.latency.push(elapsed); bucket.statuses[status]=(bucket.statuses[status]??0)+1
  if(status < 200 || status >= 300) {bucket.errors++; stage.errors++}
  stage.latency.push(elapsed); stage.requests++
  if(stage.requests >= 100 && stage.errors/stage.requests > .05) aborted = true
  return {status,value}
}
async function metrics() {
  try {
    const response = await fetch(fixture.management+'/actuator/prometheus', {signal:AbortSignal.timeout(3000),redirect:'error'})
    if(!response.ok) throw new Error('Private monitoring unavailable')
    const text = await response.text()
    const value = name => {const rows=text.split('\n').filter(s=>s.startsWith(name+'{')||s.startsWith(name+' ')); return rows.length ? rows.reduce((a,s)=>a+Number(s.split(' ').at(-1)),0) : null}
    const sample={time:new Date().toISOString(),pending,
      activeUsers:stage.activeUsers,hostFreeBytes:os.freemem(),generatorRss:process.memoryUsage().rss,
      cpu:value('process_cpu_usage'),cpuTimeNs:value('process_cpu_time_ns'),
      rss:value('process_memory_rss_bytes'),heapUsed:value('jvm_memory_used_bytes'),
      dbActive:value('hikaricp_connections_active'),dbIdle:value('hikaricp_connections_idle'),
      dbPending:value('hikaricp_connections_pending'),dbMax:value('hikaricp_connections_max'),
      threadBusy:value('tomcat_threads_busy_threads')}
    if(sample.dbMax===null || sample.heapUsed===null) throw new Error('Required resource metrics missing')
    stage.samples.push(sample)
    // Abort on JVM pressure; a brief high CPU sample alone is not a capacity failure.
    if(sample.heapUsed > 600*1024*1024 || sample.hostFreeBytes < 256*1024*1024) aborted=true
  } catch {stage.monitorFailures++;if(stage.monitorFailures>=3)aborted=true}
}
async function user(index, until, rampMs) {
  await sleep(index*rampMs/stage.users)
  if(aborted || performance.now()>=until) return
  const identity = fixture.users[index]
  const auth=await request('authentication','/api/v1/auth/login',null,{email:identity.email,password:fixture.password})
  if(auth.status !== 200 || !auth.value?.accessToken) return
  const token=auth.value.accessToken
  stage.activeUsers++; stage.peakActiveUsers=Math.max(stage.peakActiveUsers,stage.activeUsers)
  let iteration=0
  while(!aborted && performance.now()<until) {
    try {
      await request('brands','/api/v1/brands')
      await request('search','/api/v1/products?size=20&keyword=Synthetic&sort=price_asc')
      await request('product','/api/v1/products/'+fixture.slug)
      if(index%10<2) {
        const addresses=await request('address-list','/api/v1/customers/me/addresses',token)
        let address=addresses.value?.[0]?.id
        if(!address) address=(await request('address-create','/api/v1/customers/me/addresses',token,
          {recipientName:'Synthetic load customer',phoneNumber:'+923001234567',addressLine1:'Synthetic residence',city:'Test city',province:'Test province'})).value?.id
        await request('cart-read','/api/v1/customers/me/cart',token)
        for(const productId of fixture.productIds) await request('cart-add','/api/v1/customers/me/cart/items',token,{productId,quantity:1})
        const quote=await request('checkout-preview','/api/v1/customers/me/checkout/preview',token,{addressId:address})
        if(quote.status===200) {
          const body={addressId:address,quoteToken:quote.value.quoteToken}
          const headers={'Idempotency-Key':crypto.randomUUID()}
          const placed=await request('checkout-place','/api/v1/customers/me/orders',token,body,'POST',headers)
          if(placed.status===201 && iteration%3===0) {
            const replay=await request('checkout-replay','/api/v1/customers/me/orders',token,body,'POST',headers)
            if(replay.status!==200 || replay.value?.id!==placed.value.id) {stage.integrityErrors++;aborted=true}
          }
        }
        await request('order-history','/api/v1/customers/me/orders?size=20',token)
      } else if(index%10===2) {
        const root='/api/v1/merchant/brands/'+fixture.brandId
        await request('merchant-catalogue',root+'/products?size=20',token)
        const product=identity.productId
        await request('merchant-stock',root+'/products/'+product+'/stock',token,{stockQuantity:1000},'PUT')
        const orders=await request('merchant-orders',root+'/orders?size=10',token)
        const first=orders.value?.content?.[0]
        if(first) await request('merchant-order-detail',root+'/orders/'+first.orderId,token)
      }
    } catch {stage.integrityErrors++;aborted=true}
    iteration++
    await sleep(500) // Closed-loop users with think time; not an open-loop arrival-rate test.
  }
  stage.activeUsers--
}
for(const users of stages) {
  stage={users,activeUsers:0,peakActiveUsers:0,requests:0,errors:0,integrityErrors:0,latency:[],routes:{},samples:[],monitorFailures:0}
  const rampMs=5000, holdMs=Number(process.env.PROD001_HOLD_MS || 30000)
  if(holdMs<10000||holdMs>120000) throw new Error('Hold must be 10–120 seconds')
  const start=performance.now(), until=start+rampMs+holdMs
  const timer=setInterval(()=>void metrics(),3000)
  await metrics()
  await Promise.all(Array.from({length:users},(_,i)=>user(i,until,rampMs)))
  clearInterval(timer); await metrics()
  const elapsed=(performance.now()-start)/1000
  const summarize=b=>({requests:b.latency.length,p50:percentile(b.latency,.5),p95:percentile(b.latency,.95),
    p99:percentile(b.latency,.99),errors:b.errors,statuses:b.statuses})
  const result={users,peakActiveUsers:stage.peakActiveUsers,seconds:elapsed,requests:stage.requests,rps:stage.requests/elapsed,
    p50:percentile(stage.latency,.5),p95:percentile(stage.latency,.95),p99:percentile(stage.latency,.99),
    errorRate:stage.errors/Math.max(1,stage.requests),integrityErrors:stage.integrityErrors,aborted,
    routes:Object.fromEntries(Object.entries(stage.routes).map(([k,v])=>[k,summarize(v)])),
    samples:stage.samples,monitorFailures:stage.monitorFailures}
  console.log(JSON.stringify(result))
  if(aborted || result.p95>3000 || result.errorRate>.01) break
  await sleep(5000)
}
