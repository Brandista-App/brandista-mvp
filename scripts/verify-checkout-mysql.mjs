import assert from 'node:assert/strict'
import { randomUUID, randomBytes } from 'node:crypto'
import { execFileSync } from 'node:child_process'
import { mkdirSync, writeFileSync } from 'node:fs'

const base = 'http://127.0.0.1:8082/api/v1'
const root = '/customers/me'
function db(sql) {
  return execFileSync('docker', ['compose', '--env-file', '.env.m01', '-p', 'brandista-m01', 'exec', '-T', 'mysql', 'sh', '-c', 'MYSQL_PWD="$MYSQL_PASSWORD" mysql -u "$MYSQL_USER" "$MYSQL_DATABASE" -N'], { input: sql, encoding: 'utf8' }).trim()
}
assert.equal(db('SELECT DATABASE();'), 'brandista_m01')
assert.equal(db('SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history WHERE success=1;'), '9')
async function api(path, { method = 'GET', token, body, key, status = 200 } = {}) {
  const response = await fetch(base + path, { method, headers: { ...(body ? { 'Content-Type': 'application/json' } : {}), ...(token ? { Authorization: `Bearer ${token}` } : {}), ...(key ? { 'Idempotency-Key': key } : {}) }, ...(body ? { body: JSON.stringify(body) } : {}) })
  const value = response.status === 204 ? null : await response.json()
  assert.equal(response.status, status, `${method} ${path}: ${response.status} ${value?.message || ''}`)
  return value
}
async function customer(label) {
  const email = `checkout-${label}-${randomUUID()}@example.test`, password = randomBytes(24).toString('base64')
  const saved = await api('/registrations', { method: 'POST', body: { email, password }, status: 201 })
  const login = await api('/auth/login', { method: 'POST', body: { email, password } })
  assert.equal(login.account.role, 'CUSTOMER')
  return { id: saved.id, token: login.accessToken, email, password }
}
const addressBody = { recipientName: 'QA Synthetic Customer', phoneNumber: '+92 300 1234567', addressLine1: 'QA Demo House 1', city: 'Lahore', province: 'Punjab', postalCode: '54000', deliveryInstructions: 'Fictional address for local testing only' }
const results = { database: 'MySQL brandista_m01', checks: {}, orderIds: [] }
const a = await customer('a'), b = await customer('b')
const owner = a.token, other = b.token
const aa = await api(`${root}/addresses`, { method: 'POST', token: owner, body: addressBody, status: 201 })
const ab = await api(`${root}/addresses`, { method: 'POST', token: owner, body: { ...addressBody, addressLine1: 'QA Demo House 2' }, status: 201 })
const ba = await api(`${root}/addresses`, { method: 'POST', token: other, body: addressBody, status: 201 })
assert.equal(aa.isDefault, true)
await api(`${root}/addresses/${ab.id}/default`, { method: 'PUT', token: owner })
assert.equal((await api(`${root}/addresses`, { token: owner })).filter(value => value.isDefault).length, 1)
await api(`${root}/addresses/${aa.id}/default`, { method: 'PUT', token: owner })
await api(`${root}/addresses/${aa.id}/default`, { method: 'PUT', token: other, status: 404 })
await api(`${root}/addresses/${aa.id}`, { token: other, status: 404 })
await api(`${root}/addresses/${aa.id}`, { method: 'PUT', token: other, body: addressBody, status: 404 })
await api(`${root}/addresses/${aa.id}`, { method: 'DELETE', token: other, status: 404 })
await api(`${root}/checkout/preview`, { method: 'POST', token: other, body: { addressId: aa.id }, status: 404 })
results.checks.addressCrudDefaultAndOwnership = 'PASS'
const suffix = randomUUID(), brand1 = `qa-checkout-a-${suffix}`, brand2 = `qa-checkout-b-${suffix}`, category = `qa-checkout-${suffix}`
db(`START TRANSACTION;
INSERT INTO brands(name,slug,description,status,created_at,updated_at) VALUES ('QA Fictional Brand A','${brand1}','Synthetic checkout verification only','ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)),('QA Fictional Brand B','${brand2}','Synthetic checkout verification only','ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));
INSERT INTO categories(name,slug,status,created_at,updated_at) VALUES ('QA Fictional Category','${category}','ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)); COMMIT;`)
const id = slug => Number(db(`SELECT id FROM brands WHERE slug='${slug}';`))
const brandA = id(brand1), brandB = id(brand2), categoryId = Number(db(`SELECT id FROM categories WHERE slug='${category}';`))
function fixture(label, brandId, price, stock) {
  const slug = `qa-${label}-${suffix}`
  db(`INSERT INTO products(name,slug,description,brand_id,category_id,price,availability,publication_status,stock_quantity,created_at,updated_at) VALUES ('QA ${label}','${slug}','Fictional checkout fixture',${brandId},${categoryId},${price},'AVAILABLE','PUBLISHED',${stock},UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));`)
  return Number(db(`SELECT id FROM products WHERE slug='${slug}';`))
}
const shirt = fixture('shirt', brandA, '2499.95', 10), tote = fixture('tote', brandB, '1250.50', 10), last = fixture('last-unit', brandA, '99.95', 1)
const add = (token, productId, quantity) => api(`${root}/cart/items`, { method: 'POST', token, body: { productId, quantity }, status: 201 })
const quote = (token, addressId) => api(`${root}/checkout/preview`, { method: 'POST', token, body: { addressId } })
const submit = (token, addressId, review, key = randomUUID(), status = 201) => api(`${root}/orders`, { method: 'POST', token, key, body: { addressId, quoteToken: review.quoteToken, total: 1, paymentStatus: 'PAID' }, status })
try {
  assert.equal((await api(`${root}/checkout/config`, { token: owner })).codEnabled, true)
  const first = await add(owner, shirt, 1)
  await api(`${root}/cart/items/${first.items[0].itemId}`, { method: 'PATCH', token: owner, body: { quantity: 2 } })
  await add(owner, tote, 1)
  const review = await quote(owner, aa.id), key = randomUUID()
  assert.equal(review.total, 6250.40)
  const order = await submit(owner, aa.id, review, key)
  results.orderIds.push(order.id)
  assert.equal(order.total, 6250.40); assert.equal(order.paymentStatus, 'UNPAID'); assert.equal(order.status, 'PLACED')
  assert.equal(new Set(order.items.map(line => line.brandId)).size, 2)
  assert.equal((await api(`${root}/cart`, { token: owner })).items.length, 0)
  assert.equal(db(`SELECT stock_quantity FROM products WHERE id=${shirt};`), '8')
  assert.equal(db(`SELECT total FROM customer_orders WHERE id=${order.id};`), '6250.40')
  const replay = await submit(owner, aa.id, review, key, 200)
  assert.equal(replay.id, order.id)
  await submit(owner, ab.id, review, key, 409)
  await api(`${root}/orders/${order.id}`, { token: other, status: 404 })
  assert.equal((await api(`${root}/orders`, { token: other })).totalElements, 0)
  await api(`${root}/orders/${order.id}`, { status: 401 })
  await api(`/admin/products/${shirt}/stock`, { method: 'PUT', token: owner, body: { stockQuantity: 999 }, status: 403 })
  results.checks.multiBrandServerTotalsUnpaidAndIdempotency = 'PASS'
  results.checks.orderOwnershipAndRoleGuards = 'PASS'

  await api(`${root}/addresses/${aa.id}`, { method: 'PUT', token: owner, body: { ...addressBody, city: 'Islamabad' } })
  await api(`${root}/addresses/${aa.id}`, { method: 'DELETE', token: owner, status: 204 })
  assert.equal(db(`SELECT default_address_id FROM users WHERE id=${a.id};`), String(ab.id))
  const historic = await api(`${root}/orders/${order.id}`, { token: owner })
  assert.equal(historic.deliveryAddress.city, 'Lahore')
  assert.equal(historic.deliveryAddress.deliveryInstructions, addressBody.deliveryInstructions)
  assert.equal((await submit(owner, aa.id, review, key, 200)).id, order.id)
  results.checks.addressSnapshotSurvivesEditAndDeletion = 'PASS'

  await add(owner, last, 1); await add(other, last, 1)
  const [qa, qb] = await Promise.all([quote(owner, ab.id), quote(other, ba.id)])
  const race = await Promise.all([{ token: owner, addressId: ab.id, quote: qa }, { token: other, addressId: ba.id, quote: qb }].map(async attempt => {
    const response = await fetch(`${base}${root}/orders`, { method: 'POST', headers: { Authorization: `Bearer ${attempt.token}`, 'Content-Type': 'application/json', 'Idempotency-Key': randomUUID() }, body: JSON.stringify({ addressId: attempt.addressId, quoteToken: attempt.quote.quoteToken }) })
    const body = await response.json(); if (response.status === 201) results.orderIds.push(body.id)
    return response.status
  }))
  assert.deepEqual(race.sort(), [201, 409]); assert.equal(db(`SELECT stock_quantity FROM products WHERE id=${last};`), '0')
  results.checks.concurrentLastUnit = 'PASS (201/409, stock 0)'
  await api(`${root}/cart/items`, { method: 'DELETE', token: owner })
  await api(`${root}/cart/items`, { method: 'DELETE', token: other })

  await add(owner, shirt, 1)
  const duplicateQuote = await quote(owner, ab.id), duplicateKey = randomUUID()
  const before = Number(db(`SELECT COUNT(*) FROM customer_orders WHERE customer_id=${a.id};`))
  const duplicates = await Promise.all([1, 2].map(async () => {
    const response = await fetch(`${base}${root}/orders`, { method: 'POST', headers: { Authorization: `Bearer ${owner}`, 'Content-Type': 'application/json', 'Idempotency-Key': duplicateKey }, body: JSON.stringify({ addressId: ab.id, quoteToken: duplicateQuote.quoteToken }) })
    return { status: response.status, body: await response.json() }
  }))
  assert.deepEqual(duplicates.map(value => value.status).sort(), [200, 201]); assert.equal(duplicates[0].body.id, duplicates[1].body.id)
  assert.equal(Number(db(`SELECT COUNT(*) FROM customer_orders WHERE customer_id=${a.id};`)), before + 1)
  assert.equal(db(`SELECT stock_quantity FROM products WHERE id=${shirt};`), '7')
  results.orderIds.push(duplicates[0].body.id)
  results.checks.concurrentSameKey = 'PASS (201/200, one order and one decrement)'

  await add(other, tote, 1)
  const stale = await quote(other, ba.id), stockBefore = db(`SELECT stock_quantity FROM products WHERE id=${tote};`), orderCount = db(`SELECT COUNT(*) FROM customer_orders WHERE customer_id=${b.id};`)
  db(`UPDATE products SET price=1251.00,name='QA renamed tote' WHERE id=${tote};`)
  await submit(other, ba.id, stale, randomUUID(), 409)
  assert.equal(db(`SELECT stock_quantity FROM products WHERE id=${tote};`), stockBefore)
  assert.equal(db(`SELECT COUNT(*) FROM customer_orders WHERE customer_id=${b.id};`), orderCount)
  assert.equal((await api(`${root}/cart`, { token: other })).items.length, 1)
  const fresh = await quote(other, ba.id)
  db(`UPDATE products SET availability='UNAVAILABLE' WHERE id=${tote};`)
  await submit(other, ba.id, fresh, randomUUID(), 409)
  const snapshot = await api(`${root}/orders/${order.id}`, { token: owner })
  assert.equal(snapshot.items.find(line => line.productId === tote).productName, 'QA tote')
  assert.equal(snapshot.total, 6250.40)
  results.checks.changedPriceUnavailableRollbackAndProductSnapshots = 'PASS'
  db(`UPDATE users SET status='DISABLED' WHERE id=${a.id};`)
  await api(`${root}/orders/${order.id}`, { token: owner, status: 403 })
  db(`UPDATE users SET status='ENABLED' WHERE id=${a.id};`)
  results.checks.disabledAccount = 'PASS'
  results.status = 'PASS'
  results.customerIds = [a.id, b.id]
  results.persistence = db(`SELECT 'order',id,customer_id,status,payment_method,payment_status,currency,total FROM customer_orders WHERE customer_id IN (${a.id},${b.id}) ORDER BY id; SELECT 'items',order_id,COUNT(*),SUM(line_subtotal) FROM customer_order_items WHERE order_id IN (SELECT id FROM customer_orders WHERE customer_id IN (${a.id},${b.id})) GROUP BY order_id;`).split('\n')
  mkdirSync('target', { recursive: true })
  writeFileSync('target/address-checkout-mysql-session.local.json', JSON.stringify({ customer: a, orderId: order.id }))
} finally {
  // Hide only our synthetic fixtures from public discovery; retain all historical rows/snapshots.
  db(`UPDATE brands SET status='INACTIVE' WHERE id IN (${brandA},${brandB});`)
}
writeFileSync('docs/testing/ADDRESS-CHECKOUT-MYSQL-EVIDENCE.json', JSON.stringify(results, null, 2))
console.log(JSON.stringify(results, null, 2))
