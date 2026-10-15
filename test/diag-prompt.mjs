/**
 * Focused diagnosis: walk the create -> prompt -> read path and print the raw
 * server answer at every step, so a failure reports its own cause.
 *
 * Usage: node diag-prompt.mjs <baseUrl> <pairingCode>
 */

const base = process.argv[2] ?? 'http://127.0.0.1:4399'
const setupCode = process.argv[3]

/**
 * Call one route and print everything about the answer.
 * @param label - step name for the log.
 * @param path - request path.
 * @param options - fetch options.
 * @returns the decoded body.
 */
async function step(label, path, options = {}) {
  const response = await fetch(`${base}${path}`, options)
  const text = await response.text()
  console.log(`\n--- ${label} ---`)
  console.log(`${options.method ?? 'GET'} ${path} -> ${response.status}`)
  console.log(text.slice(0, 900))
  try {
    return JSON.parse(text)
  } catch {
    return text
  }
}

const paired = await step('pair', '/mobile/pair', {
  method: 'POST',
  headers: { 'content-type': 'application/json' },
  body: JSON.stringify({ code: setupCode, device: 'diag' }),
})
const token = paired.token
const auth = { authorization: `Bearer ${token}` }
const jsonAuth = { ...auth, 'content-type': 'application/json' }

const status = await step('status before', '/mobile/status', { headers: auth })
console.log(`sessions=${status.sessionCount} live=${status.liveCount}`)

const created = await step('create session', '/mobile/sessions', {
  method: 'POST',
  headers: jsonAuth,
  body: JSON.stringify({}),
})
const sessionId = created.sessionId
if (sessionId === undefined) {
  console.log('\ncannot continue without a session id')
  process.exit(1)
}

// Give the host a moment, then inspect whether a live agent exists.
await new Promise((resolve) => setTimeout(resolve, 2000))
const listed = await step('list after create', '/mobile/sessions', { headers: auth })
const mine = (listed.items ?? []).find((item) => item.id === sessionId)
console.log(`\nnew session row: ${JSON.stringify(mine)}`)

const sent = await step('prompt', `/mobile/sessions/${encodeURIComponent(sessionId)}/messages`, {
  method: 'POST',
  headers: jsonAuth,
  body: JSON.stringify({ text: 'Rispondi solo con la parola: MOBILEOK' }),
})
console.log(`\nprompt answer: ${JSON.stringify(sent)}`)

for (const wait of [3000, 6000, 12000]) {
  await new Promise((resolve) => setTimeout(resolve, wait))
  const page = await step(`history after +${wait}ms`, `/mobile/sessions/${encodeURIComponent(sessionId)}/messages?limit=40`, { headers: auth })
  console.log(`cursor=${page.cursor} entries=${(page.entries ?? []).length}`)
  if ((page.entries ?? []).length > 0) {
    console.log(JSON.stringify(page.entries.map((entry) => ({
      kind: entry.kind,
      role: entry.role,
      text: entry.content?.filter((block) => block.type === 'text').map((block) => block.text).join(' ').slice(0, 80),
      name: entry.name,
    })), null, 2))
    break
  }
  const reread = await step('session row', '/mobile/sessions', { headers: auth })
  const row = (reread.items ?? []).find((item) => item.id === sessionId)
  console.log(`row now: ${JSON.stringify(row)}`)
}
