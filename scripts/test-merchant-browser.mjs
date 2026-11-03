// Real browser + React portal + real disposable Spring HTTP API. No API mocks.
import { pathToFileURL } from 'node:url'
import { resolve } from 'node:path'
import { mkdirSync, writeFileSync } from 'node:fs'
import assert from 'node:assert/strict'

const root = process.env.MERCHANT_BROWSER_ADMIN_ROOT
const backend = process.env.MERCHANT_BROWSER_API
assert.match(backend || '', /^http:\/\/127\.0\.0\.1:\d+\/api\/v1$/)
const { chromium } = await import(pathToFileURL(process.env.MERCHANT_BROWSER_PLAYWRIGHT).href)
const { createServer } = await import(pathToFileURL(resolve(root, 'node_modules/vite/dist/node/index.js')).href)
const { default: react } = await import(pathToFileURL(resolve(root, 'node_modules/@vitejs/plugin-react/dist/index.js')).href)
process.env.VITE_API_BASE_URL = backend
const server = await createServer({ configFile: false, root, plugins: [react()], server: { host: '127.0.0.1', port: 5190, strictPort: true } })
let browser
try {
  await server.listen()
  browser = await chromium.launch({ headless: true, ...(process.env.MERCHANT_BROWSER_CHANNEL ? { channel: process.env.MERCHANT_BROWSER_CHANNEL } : {}) })
  const context = await browser.newContext({ viewport: { width: 1366, height: 900 } })
  const page = await context.newPage()
  const failures = []
  page.on('pageerror', () => failures.push('browser exception'))
  await page.goto('http://127.0.0.1:5190/accept-invitation#token=' + process.env.MERCHANT_BROWSER_TOKEN)
  await page.getByRole('button', { name: 'Verify invitation', exact: true }).click()
  await page.getByRole('heading', { name: process.env.MERCHANT_BROWSER_BRAND }).waitFor()
  assert.equal(new URL(page.url()).hash, '')
  mkdirSync('target/merchant-browser', { recursive: true })
  await page.screenshot({ path: 'target/merchant-browser/registration-desktop.png', fullPage: true })
  await page.setViewportSize({ width: 390, height: 844 })
  await page.screenshot({ path: 'target/merchant-browser/registration-mobile.png', fullPage: true })
  await page.setViewportSize({ width: 1366, height: 900 })
  await page.getByLabel('Create password', { exact: true }).fill(process.env.MERCHANT_BROWSER_PASSWORD)
  await page.getByLabel('Confirm password', { exact: true }).fill(process.env.MERCHANT_BROWSER_PASSWORD)
  await page.getByRole('checkbox').check()
  await page.getByRole('button', { name: 'Accept terms and activate account', exact: true }).click()
  await page.getByRole('heading', { name: 'Your merchant account is ready.' }).waitFor()
  await page.getByRole('link', { name: 'Continue to merchant sign-in' }).click()
  await page.getByLabel('Workspace', { exact: true }).selectOption('merchant')
  await page.getByLabel('Email address', { exact: true }).fill(process.env.MERCHANT_BROWSER_EMAIL)
  await page.getByLabel('Password', { exact: true }).fill(process.env.MERCHANT_BROWSER_PASSWORD)
  await page.getByRole('button', { name: /Sign in securely/ }).click()
  await page.getByRole('heading', { name: 'Your assigned brands' }).waitFor()
  await page.getByRole('heading', { name: process.env.MERCHANT_BROWSER_BRAND }).waitFor()
  assert.equal(await page.getByRole('link', { name: /Brand applications/ }).count(), 0)
  await page.getByRole('link', { name: 'Manage catalogue', exact: true }).click()
  await page.getByRole('button', { name: /Sign out/ }).waitFor()
  mkdirSync('target/merchant-browser', { recursive: true })
  await page.screenshot({ path: 'target/merchant-browser/assigned-workspace.png', fullPage: true })
  await page.setViewportSize({ width: 390, height: 844 })
  await page.screenshot({ path: 'target/merchant-browser/mobile-workspace.png', fullPage: true })
  await page.getByRole('button', { name: /Sign out/ }).click()
  await page.goto('http://127.0.0.1:5190/accept-invitation#token=' + process.env.MERCHANT_BROWSER_TOKEN)
  await page.getByRole('button', { name: 'Verify invitation', exact: true }).click()
  await page.getByRole('alert').waitFor()
  assert.match(await page.getByRole('alert').textContent(), /expired, revoked, or already used/)
  assert.equal(failures.length, 0)
  writeFileSync('target/merchant-browser/results.json', JSON.stringify({ passed: true, realBrowser: true, realBackend: true, terms: true, login: true, assignedBrand: true, replayRejected: true, productionEmailSent: false }, null, 2))
  console.log('Real browser merchant acceptance, login, workspace and replay checks passed.')
} catch {
  console.error('Real browser merchant acceptance test failed; secret-bearing diagnostics suppressed.')
  process.exitCode = 1
} finally {
  if (browser) await browser.close()
  await server.close()
}
