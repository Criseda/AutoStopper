'use strict'

// Test-only Engine API relay for the release-candidate E2E. It sits between the
// socket proxy and the Docker socket, records every request that passed the
// proxy's allowlist, and can fail the first stop of one container. It must never
// appear in the public examples.

const fs = require('fs')
const http = require('http')

const listenSocket = process.env.E2E_LISTEN_SOCKET || '/run/autostopper-e2e/docker.sock'
const upstreamSocket = process.env.E2E_UPSTREAM_SOCKET || '/var/run/docker.sock'
const failStopContainer = (process.env.E2E_FAIL_STOP_CONTAINER || '').trim()
let stopFailureInjected = false

function audit(method, path, status, note) {
  process.stdout.write(`AUDIT ${method} ${path} ${status}${note ? ` ${note}` : ''}\n`)
}

function injectStopFailure(request, response, path) {
  stopFailureInjected = true
  request.resume()
  const body = JSON.stringify({ message: 'injected release-candidate stop failure' })
  response.writeHead(500, {
    'Content-Type': 'application/json',
    'Content-Length': Buffer.byteLength(body),
    Connection: 'close'
  })
  response.end(body)
  audit(request.method, path, 500, 'injected')
}

const server = http.createServer((request, response) => {
  const path = request.url.split('?')[0]
  if (failStopContainer && !stopFailureInjected && request.method === 'POST'
      && path === `/containers/${failStopContainer}/stop`) {
    injectStopFailure(request, response, path)
    return
  }

  const upstream = http.request({
    socketPath: upstreamSocket,
    method: request.method,
    path: request.url,
    headers: request.headers
  }, upstreamResponse => {
    audit(request.method, path, upstreamResponse.statusCode)
    response.writeHead(upstreamResponse.statusCode, upstreamResponse.headers)
    upstreamResponse.pipe(response)
  })
  upstream.on('error', error => {
    audit(request.method, path, 502, error.code || 'upstream-error')
    if (!response.headersSent) {
      response.writeHead(502, { Connection: 'close' })
    }
    response.end()
  })
  request.pipe(upstream)
})

// A container stop waits for its grace period; never cut it here.
server.requestTimeout = 0

try {
  fs.unlinkSync(listenSocket)
} catch (error) {
  if (error.code !== 'ENOENT') throw error
}
server.listen(listenSocket, () => {
  fs.chmodSync(listenSocket, 0o600)
  process.stdout.write(`READY ${listenSocket} -> ${upstreamSocket}\n`)
})

process.on('SIGTERM', () => server.close(() => process.exit(0)))
