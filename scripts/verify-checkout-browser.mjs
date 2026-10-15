import { chromium } from '../target/browser-qa/node_modules/playwright/index.mjs'
import assert from 'node:assert/strict'
import { randomBytes, randomUUID } from 'node:crypto'
import { mkdirSync, writeFileSync } from 'node:fs'

const browser = await chromium.launch({ channel: 'msedge', headless: true })
const context = await browser.newContext({ viewport: { width: 1366, height: 768 } })
const page = await context.newPage()
const exceptions = [], apiFailures = []
page.on('pageerror', error => exceptions.push(error.message))
page.on('requestfailed', request => { if (request.url().includes('/api/v1/')) apiFailures.push({ url: request.url().split('?')[0], error: request.failure()?.errorText }) })
const base = 'http://127.0.0.1:5176'
const email = `checkout-browser-${randomUUID()}@example.test`, password = randomBytes(24).toString('base64')
const screenshots = 'docs/testing/screenshots'
mkdirSync(screenshots, { recursive: true })
const checks = { browser: 'Microsoft Edge (Playwright, isolated headless context)', database: 'MySQL via real Spring HTTP API', checks: {}, responsive: [] }
async function visible(locator) { await locator.waitFor({ state: 'visible', timeout: 15000 }) }
async function fillAddress(name, line, city, province) {
  await page.getByLabel('Recipient name', { exact: true }).fill(name)
  await page.getByLabel('Phone number', { exact: true }).fill('+92 300 1234567')
  await page.getByLabel('Address line 1', { exact: true }).fill(line)
  await page.getByLabel('City', { exact: true }).fill(city)
  await page.getByLabel('Province / region', { exact: true }).fill(province)
  await page.getByLabel('Delivery instructions (optional)', { exact: true }).fill('Fictional local test address; ring once')
}
const shop = () => page.getByRole('navigation', { name: 'Main navigation' }).getByRole('link', { name: 'Shop all', exact: true }).click()
const bag = () => page.getByRole('banner').getByRole('link', { name: /Bag/ }).click()
try {
  await page.goto(`${base}/register`)
  await page.getByLabel('Email address', { exact: true }).fill(email)
  await page.getByLabel('Password', { exact: true }).fill(password)
  await page.getByRole('button', { name: 'Create account', exact: true }).click()
  await page.waitForURL('**/sign-in')
  await page.getByLabel('Password', { exact: true }).fill(password)
  await page.getByRole('button', { name: 'Sign in', exact: true }).click()
  await page.waitForURL('**/account')
  checks.checks.registrationAndLogin = 'PASS'
  await page.getByRole('link', { name: 'Manage addresses', exact: true }).click()
  await visible(page.getByRole('heading', { name: 'My addresses', exact: true }))
  await page.getByRole('button', { name: 'Add address', exact: true }).click()
  await fillAddress('QA Delivery One', 'Fictional House 1', 'Lahore', 'Punjab')
  await page.getByRole('button', { name: 'Save address', exact: true }).click()
  await visible(page.getByText('Address saved.', { exact: true }))
  await page.getByRole('button', { name: 'Add address', exact: true }).click()
  await fillAddress('QA Delivery Two', 'Fictional House 2', 'Karachi', 'Sindh')
  await page.getByRole('button', { name: 'Save address', exact: true }).click()
  const second = page.locator('article.saved-address').filter({ hasText: 'QA Delivery Two' })
  await second.getByRole('button', { name: 'Make default', exact: true }).click()
  await visible(page.getByText('Default address updated.', { exact: true }))
  await second.getByRole('button', { name: 'Edit', exact: true }).click()
  await page.getByLabel('City', { exact: true }).fill('Hyderabad')
  await page.getByRole('button', { name: 'Save address', exact: true }).click()
  await visible(page.getByText('Hyderabad, Sindh', { exact: false }))
  checks.checks.addressCreateEditAndDefault = 'PASS'

  await shop()
  await page.getByRole('link', { name: 'DEMO Linen Shirt', exact: true }).click()
  await page.getByRole('button', { name: 'Add to your bag ↗', exact: true }).click()
  await visible(page.getByText('Added to your bag.', { exact: true }))
  await bag()
  await page.getByRole('spinbutton', { name: 'Quantity', exact: true }).fill('2')
  await page.getByRole('button', { name: 'Update quantity', exact: true }).click()
  await visible(page.getByText(/4,999\.9/).first())
  await page.getByRole('link', { name: '← Keep exploring', exact: true }).click()
  await page.getByRole('link', { name: 'DEMO Canvas Tote', exact: true }).click()
  await page.getByRole('button', { name: 'Add to your bag ↗', exact: true }).click()
  await visible(page.getByText('Added to your bag.', { exact: true }))
  await bag()
  await visible(page.getByText(/6,250\.4/).first())
  await page.getByRole('link', { name: 'Proceed to checkout', exact: true }).click()
  await visible(page.getByRole('button', { name: 'Review order', exact: true }))
  assert.equal(await page.getByRole('radio', { name: /QA Delivery Two/ }).isChecked(), true)
  await page.getByRole('button', { name: 'Review order', exact: true }).click()
  await visible(page.getByRole('button', { name: 'Place COD order', exact: true }))
  await visible(page.getByText(/6,250\.40/).first())
  checks.checks.multiBrandCartAndServerReview = 'PASS'
  for (const [width, height] of [[1366, 768], [360, 800], [390, 844], [768, 1024], [1920, 1080]]) {
    await page.setViewportSize({ width, height })
    const measurement = await page.evaluate(() => ({ width: innerWidth, height: innerHeight, scrollWidth: document.documentElement.scrollWidth, overflow: document.documentElement.scrollWidth > innerWidth }))
    assert.equal(measurement.overflow, false, `Checkout overflow at ${width}`)
    checks.responsive.push(measurement)
    await page.screenshot({ path: `${screenshots}/checkout-review-${width}x${height}.png`, fullPage: true })
  }
  await page.setViewportSize({ width: 1366, height: 768 })
  const placedResponse = page.waitForResponse(response => response.url().endsWith('/customers/me/orders') && response.request().method() === 'POST')
  await page.getByRole('button', { name: 'Place COD order', exact: true }).click()
  const response = await placedResponse
  assert.equal(response.status(), 201)
  const placed = await response.json()
  assert.equal(placed.total, 6250.40); assert.equal(placed.paymentStatus, 'UNPAID')
  await visible(page.getByRole('heading', { name: 'Order confirmed', exact: true }))
  await visible(page.getByText('UNPAID', { exact: true }))
  await page.screenshot({ path: `${screenshots}/checkout-confirmation-desktop.png`, fullPage: true })
  checks.orderId = placed.id
  checks.checks.codPlacementAndConfirmation = 'PASS (PLACED / UNPAID; no payment)'
  await bag()
  await visible(page.getByRole('heading', { name: 'Your bag is taking a little breather', exact: true }))
  checks.checks.cartClearedAfterSuccess = 'PASS'

  await page.getByRole('banner').getByRole('link', { name: /Account/ }).click()
  await page.getByRole('link', { name: 'Manage addresses', exact: true }).click()
  await page.locator('article.saved-address').filter({ hasText: 'QA Delivery Two' }).getByRole('button', { name: 'Edit', exact: true }).click()
  await page.getByLabel('City', { exact: true }).fill('Islamabad')
  await page.getByRole('button', { name: 'Save address', exact: true }).click()
  await visible(page.getByText(/Islamabad, Sindh/))
  page.once('dialog', dialog => dialog.accept())
  await page.locator('article.saved-address').filter({ hasText: 'QA Delivery Two' }).getByRole('button', { name: 'Delete', exact: true }).click()
  await visible(page.getByText('Address deleted.', { exact: true }))
  await page.locator('main').getByRole('link', { name: 'My orders', exact: true }).click()
  await visible(page.getByRole('heading', { name: 'My orders', exact: true }))
  await page.getByRole('link', { name: 'View order details', exact: true }).click()
  await visible(page.getByText(/Hyderabad, Sindh/))
  assert.equal(await page.getByText(/Islamabad/).count(), 0)
  checks.checks.addressDeletionKeepsOrderSnapshotAndHistory = 'PASS'
  await page.getByRole('button', { name: 'Sign out', exact: true }).click()
  await page.waitForURL('**/sign-in')
  await page.getByLabel('Email address', { exact: true }).fill(email)
  await page.getByLabel('Password', { exact: true }).fill(password)
  await page.getByRole('button', { name: 'Sign in', exact: true }).click()
  await page.waitForURL(`**/orders/${placed.id}`)
  await visible(page.getByText('UNPAID', { exact: true }))
  checks.checks.logoutLoginRestoresOrder = 'PASS'
  await page.setViewportSize({ width: 390, height: 844 })
  await page.screenshot({ path: `${screenshots}/checkout-order-mobile.png`, fullPage: true })
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false)
  assert.deepEqual(exceptions, [])
  assert.deepEqual(apiFailures, [])
  checks.checks.javascriptExceptions = 'PASS (none)'
  checks.checks.failedApiRequests = 'PASS (none)'
  writeFileSync('target/address-checkout-browser-session.local.json', JSON.stringify({ email, password, orderId: placed.id }))
  checks.status = 'PASS'
} catch (error) {
  checks.status = 'FAIL'; checks.error = error.message
  await page.screenshot({ path: 'target/address-checkout-browser-failure.png', fullPage: true }).catch(() => {})
  throw error
} finally {
  writeFileSync('docs/testing/ADDRESS-CHECKOUT-BROWSER-EVIDENCE.json', JSON.stringify(checks, null, 2))
  console.log(JSON.stringify(checks, null, 2))
  await browser.close()
}
