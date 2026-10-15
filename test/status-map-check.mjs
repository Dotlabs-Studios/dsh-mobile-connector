/**
 * Verify the plugin classifies harness failures correctly, including under the
 * diagnostic flag that prefixes error codes.
 *
 * The prefix once broke the mapping: a writer-lock conflict answered 500
 * instead of 409 because the classifier no longer recognised the code. This
 * drives the real route against a stand-in controller that throws the exact
 * RemoteError codes the harness uses.
 */

import { strict as assert } from 'node:assert'
import { fileURLToPath } from 'node:url'

process.env.DSH_HOME = fileURLToPath(new URL('./_runtime-status', import.meta.url))

const { MobileSync } = await import('../dsh-plugin-mobile-sync/lib/index.js')

let checks = 0
let failures = 0

/**
 * Assert one condition and record it.
 * @param condition - value that must be truthy.
 * @param label - the expectation being checked.
 */
function ok(condition, label) {
  checks += 1
  if (condition) {
    console.log(`  PASS  ${label}`)
  } else {
    failures += 1
    console.log(`  FAIL  ${label}`)
  }
}

/** A logger that keeps the run quiet. */
const logger = { info: () => {}, warn: () => {}, error: () => {} }

/**
 * Build a context whose session controller throws one chosen failure.
 * @param failure - the error to throw from prompt().
 * @param debug - whether the plugin runs in diagnostic mode.
 * @returns a booted plugin instance.
 */
async function bootWith(failure, debug) {
  const ctx = {
    logger,
    get: (name) => (name === 'sessionController' ? controller : undefined),
    on: () => () => {},
    effect: (factory) => {
      const dispose = factory()
      return typeof dispose === 'function' ? dispose : () => {}
    },
    plugin: (ctor, config) => new ctor(ctx, config),
  }
  const controller = {
    async list() {
      return { items: [] }
    },
    async page() {
      return { records: [], hasMore: false }
    },
    follow() {
      return (async function* empty() {})()
    },
    async prompt() {
      throw failure
    },
    async create() {
      throw failure
    },
  }
  const instance = new MobileSync(ctx, { host: '127.0.0.1', port: 0, ...(debug ? { debug: true } : {}) })
  await instance[Symbol.for('cordis.init')]()
  return instance
}

/**
 * Issue one prompt through the running plugin.
 * @param instance - the booted plugin.
 * @returns the HTTP status and decoded body.
 */
async function attemptPrompt(instance) {
  const base = `http://127.0.0.1:${instance.port}`
  const paired = await fetch(`${base}/mobile/pair`, {
    method: 'POST',
    body: JSON.stringify({ code: instance.setupCode, device: 'status-probe' }),
  })
  const { token } = await paired.json()
  const response = await fetch(`${base}/mobile/sessions/session-1/messages`, {
    method: 'POST',
    headers: { authorization: `Bearer ${token}`, 'content-type': 'application/json' },
    body: JSON.stringify({ text: 'ciao' }),
  })
  const text = await response.text()
  let body
  try {
    body = JSON.parse(text)
  } catch {
    body = text
  }
  return { status: response.status, body }
}

/**
 * Build a harness-shaped failure.
 * @param code - the RemoteError code.
 * @param message - its message.
 * @returns the error value.
 */
function remoteError(code, message) {
  const error = new Error(message)
  error.code = code
  error.name = 'RemoteError'
  return error
}

const cases = [
  { code: 'session/writer-held', message: 'session "x" is already owned by an active write handle', expected: 409 },
  { code: 'session/agent-busy', message: 'prompt rejected', expected: 409 },
  { code: 'session/conflict', message: 'conflicting workspace', expected: 409 },
  { code: 'session/not-found', message: 'session "x" not found', expected: 404 },
  { code: 'gateway/bad-request', message: 'bad arguments', expected: 400 },
  { code: 'session/attachment-invalid', message: 'no image support', expected: 400 },
  { code: 'gateway/internal', message: 'something else entirely', expected: 500 },
]

console.log('\n== classification without the diagnostic flag ==')
for (const item of cases) {
  const instance = await bootWith(remoteError(item.code, item.message), false)
  const result = await attemptPrompt(instance)
  ok(result.status === item.expected, `${item.code} -> ${item.expected} (got ${result.status})`)
  ok(result.body?.error?.code === item.code, `${item.code} reaches the client unchanged (got ${result.body?.error?.code})`)
  instance.server.closeAllConnections()
  await new Promise((resolve) => instance.server.close(resolve))
}

console.log('\n== classification with the diagnostic flag on ==')
for (const item of cases) {
  const instance = await bootWith(remoteError(item.code, item.message), true)
  const result = await attemptPrompt(instance)
  // The flag must change the payload, never the classification.
  ok(result.status === 500, `debug mode reports the failure loudly for ${item.code} (got ${result.status})`)
  ok(
    typeof result.body?.error?.code === 'string' && result.body.error.code.includes(item.code),
    `debug mode keeps the harness code visible for ${item.code} (got ${result.body?.error?.code})`,
  )
  instance.server.closeAllConnections()
  await new Promise((resolve) => instance.server.close(resolve))
}

console.log('\n== reachable addresses are reported, never the bind wildcard ==')
{
  const instance = await bootWith(remoteError('gateway/internal', 'irrelevant'), false)
  const base = `http://127.0.0.1:${instance.port}`
  const paired = await fetch(`${base}/mobile/pair`, {
    method: 'POST',
    body: JSON.stringify({ code: instance.setupCode, device: 'url-probe' }),
  })
  const { token } = await paired.json()
  const status = await (await fetch(`${base}/mobile/status`, { headers: { authorization: `Bearer ${token}` } })).json()

  ok(Array.isArray(status.urls), 'status reports a list of reachable addresses')
  const urls = status.urls ?? []
  ok(urls.every((entry) => typeof entry.url === 'string' && entry.url.startsWith('http://')), 'every entry is an http URL')
  ok(
    urls.every((entry) => !entry.url.includes('0.0.0.0') && !entry.url.includes('127.0.0.1')),
    'the wildcard and loopback are never offered to a phone',
  )
  ok(
    urls.every((entry) => entry.url.endsWith(`:${instance.port}`)),
    'every URL carries the live listening port',
  )
  if (urls.length > 0) {
    console.log(`        host offers: ${urls.map((entry) => entry.url).join(', ')}`)
  }
  ok(
    urls.every((entry) => typeof entry.tailscale === 'boolean'),
    'each address says whether it is the Tailscale range',
  )

  instance.server.closeAllConnections()
  await new Promise((resolve) => instance.server.close(resolve))
}

console.log(`\n${failures === 0 ? 'STATUS MAP PASSED' : 'STATUS MAP FAILED'}: ${checks - failures}/${checks} checks\n`)
process.exit(failures === 0 ? 0 : 1)
