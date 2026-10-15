import { readFileSync, writeFileSync } from 'node:fs'
import { execFileSync } from 'node:child_process'
import assert from 'node:assert/strict'
const mode = process.argv[2] || 'before'
assert.ok(['before', 'after'].includes(mode))
const api = 'http://127.0.0.1:8082/api/v1'
const apiSession = JSON.parse(readFileSync('target/address-checkout-mysql-session.local.json', 'utf8'))
const browserSession = JSON.parse(readFileSync('target/address-checkout-browser-session.local.json', 'utf8'))
const identities = [{ ...apiSession.customer, orderId: apiSession.orderId }, browserSession]
const records = []
for (const identity of identities) {
  const loginResponse = await fetch(`${api}/auth/login`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ email: identity.email, password: identity.password }) })
  assert.equal(loginResponse.status, 200)
  const login = await loginResponse.json()
  const response = await fetch(`${api}/customers/me/orders/${identity.orderId}`, { headers: { Authorization: `Bearer ${login.accessToken}` } })
  assert.equal(response.status, 200)
  const order = await response.json()
  assert.equal(order.paymentStatus, 'UNPAID')
  records.push({ customerId: login.account.id, order })
}
const ids = records.map(value => Number(value.order.id)).join(',')
const rows = execFileSync('docker', ['compose', '--env-file', '.env.m01', '-p', 'brandista-m01', 'exec', '-T', 'mysql', 'sh', '-c', 'MYSQL_PWD="$MYSQL_PASSWORD" mysql -u "$MYSQL_USER" "$MYSQL_DATABASE" -N'], { encoding: 'utf8', input: `SELECT 'order',id,customer_id,status,payment_method,payment_status,total,city FROM customer_orders WHERE id IN (${ids}) ORDER BY id; SELECT 'item',order_id,product_id,brand_id,quantity,unit_price,line_subtotal FROM customer_order_items WHERE order_id IN (${ids}) ORDER BY order_id,id; SELECT 'inventory',id,stock_quantity FROM products WHERE id IN (SELECT product_id FROM customer_order_items WHERE order_id IN (${ids})) ORDER BY id;` }).trim().split('\n')
if (mode === 'before') {
  writeFileSync('target/address-checkout-persistence-before.local.json', JSON.stringify({ records, rows }))
  console.log(JSON.stringify({ status: 'RECORDED', orderIds: records.map(value => value.order.id), rows }, null, 2))
} else {
  const before = JSON.parse(readFileSync('target/address-checkout-persistence-before.local.json', 'utf8'))
  assert.deepEqual(records, before.records)
  assert.deepEqual(rows, before.rows)
  const result = { status: 'PASS', check: 'Both API-created and browser-created orders, snapshots and inventory survive MySQL and backend restart', orderIds: records.map(value => value.order.id), mysqlRows: rows }
  writeFileSync('docs/testing/ADDRESS-CHECKOUT-PERSISTENCE-EVIDENCE.json', JSON.stringify(result, null, 2))
  console.log(JSON.stringify(result, null, 2))
}
