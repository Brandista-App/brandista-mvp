// Keep previously shared local demo links useful; serve no application/mock data.
import http from 'node:http'
const aliases = new Map([
  ['/brands/sample-studio', '/brands/demo-m01-lahore-loom'],
  ['/products/sample-linen-shirt', '/products/demo-m01-linen-shirt'],
])
http.createServer((request, response) => {
  const url = new URL(request.url, 'http://127.0.0.1:5175')
  const path = aliases.get(url.pathname) || url.pathname
  response.writeHead(302, {
    Location: `http://127.0.0.1:5176${path}${url.search}`,
    'Cache-Control': 'no-store',
  })
  response.end()
}).listen(5175, '127.0.0.1', () => console.log('Old demo links redirect to the MySQL acceptance app on 5176'))
