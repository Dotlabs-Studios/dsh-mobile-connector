/**
 * Smoke check for the UI stub.
 *
 * `test/ui-stub.mjs` is the stand-in harness a person pairs an emulator with to look
 * at the screens without arranging a real conversation. Nothing else runs it, so
 * nothing else notices when it rots — and a stub that has quietly stopped answering
 * a route is worse than no stub, because the screen under inspection then looks
 * broken for a reason that is not the screen.
 *
 * This boots it on a loopback port, drives the routes the app cannot render without,
 * and stops it. The stub is spawned with `stdio: 'ignore'` on purpose: its own log is
 * not what is under test, and piping it would make this check depend on how the log
 * is buffered.
 */

import { strict as assert } from 'node:assert'
import { spawn } from 'node:child_process'
import { fileURLToPath } from 'node:url'

const STUB = fileURLToPath(new URL('./ui-stub.mjs', import.meta.url))
const PORT = Number(process.env.UI_STUB_PORT ?? 4398)
const BASE = `http://127.0.0.1:${PORT}`
const TOKEN = 'ui-stub-token'

let checks = 0
/**
 * Assert one condition and count it.
 * @param condition - value that must be truthy.
 * @param label - description used in the failure message.
 */
function ok(condition, label) {
  checks += 1
  assert.ok(condition, label)
}

/** Fetch one route with a short timeout, returning status and parsed body. */
async function call(path, options = {}) {
  const response = await fetch(`${BASE}${path}`, { ...options, signal: AbortSignal.timeout(5_000) })
  const text = await response.text()
  let body
  try {
    body = JSON.parse(text)
  } catch {
    body = text
  }
  return { status: response.status, body }
}

/** Wait until the stub answers, or fail with the reason it never did. */
async function ready(deadlineMs = 10_000) {
  const started = Date.now()
  for (;;) {
    try {
      // Any answer means the listener is up; a 401 is the expected one before pairing.
      await fetch(`${BASE}/mobile/status`, { signal: AbortSignal.timeout(1_000) })
      return
    } catch (error) {
      if (Date.now() - started > deadlineMs) {
        throw new Error(`the UI stub did not answer on ${BASE}: ${String(error)}`)
      }
      await new Promise((resolve) => setTimeout(resolve, 100))
    }
  }
}

const stub = spawn(process.execPath, [STUB, String(PORT)], { stdio: 'ignore' })
try {
  await ready()

  const unauthorised = await call('/mobile/sessions')
  ok(unauthorised.status === 401, `an unpaired read answers 401 (got ${unauthorised.status})`)

  const paired = await call('/mobile/pair', {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ code: 'anything', device: 'Emulatore' }),
  })
  ok(paired.status === 200, `the stub pairs any code (got ${paired.status})`)
  ok(paired.body.token === TOKEN, 'and answers the token the app will use')

  const auth = { authorization: `Bearer ${TOKEN}` }

  const status = await call('/mobile/status', { headers: auth })
  ok(status.status === 200 && status.body.ok === true, 'status answers')

  const sessions = await call('/mobile/sessions', { headers: auth })
  ok(sessions.status === 200, `the session list answers (got ${sessions.status})`)
  ok(Array.isArray(sessions.body.items) && sessions.body.items.length > 0, 'with at least one chat')
  const session = sessions.body.items.find((item) => item.id === 'session-ui-stub')
  ok(session !== undefined, 'including the one the stub documents')

  const messages = await call('/mobile/sessions/session-ui-stub/messages?limit=20', { headers: auth })
  ok(messages.status === 200, `the transcript answers (got ${messages.status})`)
  ok(Array.isArray(messages.body.entries) && messages.body.entries.length > 0, 'with entries')
  ok(
    messages.body.entries.some((entry) => entry.kind === 'message'),
    'including a message bubble',
  )
  ok(
    messages.body.entries.some((entry) => entry.kind === 'tool-call'),
    'and a tool card',
  )

  const unknown = await call('/mobile/no-such-route', { headers: auth })
  ok(unknown.status === 404, `an unknown route answers 404 (got ${unknown.status})`)
  ok(unknown.body.error?.code === 'not-found', 'with the envelope the app decodes')
} finally {
  stub.kill()
}

console.log(`\nUI stub smoke checks passed: ${checks}`)
