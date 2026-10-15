/**
 * Find a session the desktop application is not currently holding, so the
 * phone-side write path can be exercised without fighting its writer lock.
 *
 * Usage: node find-free-session.mjs [baseUrl] [token]
 */

const base = process.argv[2] ?? 'http://127.0.0.1:4399'
const token = process.argv[3]

if (token === undefined) {
  console.error('usage: node find-free-session.mjs <baseUrl> <token>')
  process.exit(2)
}

const auth = { authorization: `Bearer ${token}` }
const listed = await (await fetch(`${base}/mobile/sessions`, { headers: auth })).json()

let free = null
for (const session of (listed.items ?? []).slice(0, 25)) {
  const response = await fetch(`${base}/mobile/sessions/${encodeURIComponent(session.id)}/messages`, {
    method: 'POST',
    headers: { ...auth, 'content-type': 'application/json' },
    body: JSON.stringify({ text: 'probe' }),
  })
  const accepted = response.status === 202
  const detail = accepted ? '' : `  (${(await response.text()).slice(0, 90)})`
  console.log(`${accepted ? 'FREE  ' : 'LOCKED'} ${response.status} ${session.id} ${session.title ?? '(no title)'}${detail}`)
  if (accepted && free === null) {
    free = session.id
    break
  }
}

console.log(`FREE_SESSION=${free ?? ''}`)
