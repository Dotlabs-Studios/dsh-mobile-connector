/**
 * End-to-end verification of file attachments against a *real* harness.
 *
 * Boots nothing: it talks to a live `dsh web` instance on which the plugin is
 * mounted, so the receipts it spends come from the genuine file-upload service
 * and the bytes it sends are stored by the real attachment backend.
 *
 * The offline suite can prove the wire shape and nothing more. What only a real
 * harness can answer is whether a mobile upload turns into a prompt the session
 * controller accepts — a `file` part is not an inline blob but a receipt the
 * controller resolves against the receiving agent — and whether the bytes are
 * really on the machine afterwards.
 *
 * Usage: node file-check.mjs <baseUrl> <pairingCode> [attachmentRoot]
 */

import { mkdir, readFile, rm, stat } from 'node:fs/promises'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'

const base = process.argv[2] ?? 'http://127.0.0.1:4399'
const setupCode = process.argv[3]
// The attachment store of the harness under test, when it is the one this
// repository's own runs use. Point it elsewhere with the fourth argument.
const repoRoot = fileURLToPath(new URL('..', import.meta.url))
const attachmentRoot =
  process.argv[4] ?? join(repoRoot, 'test', '_runtime-files', 'attachments', 'v1')

if (setupCode === undefined) {
  console.error('usage: node file-check.mjs <baseUrl> <pairingCode> [attachmentRoot]')
  process.exit(2)
}

let failures = 0
let checks = 0

/**
 * Assert one condition, recording rather than throwing so the run reports every
 * problem it finds.
 * @param condition - value that must be truthy.
 * @param label - description of the expectation.
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

/**
 * Call one route.
 * @param path - request path.
 * @param options - fetch options.
 * @returns status and decoded body.
 */
async function call(path, options = {}) {
  const response = await fetch(`${base}${path}`, options)
  const text = await response.text()
  let body
  try {
    body = JSON.parse(text)
  } catch {
    body = text
  }
  return { status: response.status, body }
}

console.log(`\n== pairing against ${base} ==`)
const paired = await call('/mobile/pair', {
  method: 'POST',
  headers: { 'content-type': 'application/json' },
  body: JSON.stringify({ code: setupCode, device: 'file-verify' }),
})
ok(paired.status === 200, `pairing succeeds (status ${paired.status})`)
if (paired.status !== 200) {
  console.log(JSON.stringify(paired.body))
  process.exit(1)
}
const auth = { authorization: `Bearer ${paired.body.token}` }
const jsonAuth = { ...auth, 'content-type': 'application/json' }

/**
 * Read one chat's transcript until it shows the user message being waited for.
 *
 * The harness accepts a prompt before it writes it — resolving a cold agent and
 * admitting the content happen in between — so a read taken the moment the send
 * returns is a race. A phone polls; so does this.
 *
 * @param sessionId - the chat to read.
 * @param predicate - what the awaited message must satisfy.
 * @param timeoutMs - how long to keep asking.
 * @returns the message, and how many user messages were seen without it.
 */
async function waitForUserMessage(sessionId, predicate, timeoutMs = 20_000) {
  const deadline = Date.now() + timeoutMs
  let seen = 0
  while (Date.now() < deadline) {
    const history = await call(`/mobile/sessions/${encodeURIComponent(sessionId)}/messages?limit=40`, { headers: auth })
    const users = (history.body.entries ?? []).filter((entry) => entry.kind === 'message' && entry.role === 'user')
    seen = users.length
    const found = users.find(predicate)
    if (found !== undefined) return { message: found, seen }
    await new Promise((resolve) => setTimeout(resolve, 500))
  }
  return { message: undefined, seen }
}

// One directory to run the chats in, so nothing of the user's is touched.
const workdir = join(process.cwd(), 'test', '_file-e2e')
await mkdir(workdir, { recursive: true })

console.log('\n== a chat to attach to ==')
const created = await call('/mobile/sessions', {
  method: 'POST',
  headers: jsonAuth,
  body: JSON.stringify({ cwd: workdir }),
})
ok(created.status === 201, `the chat is created (got ${created.status})`)
const sessionId = created.body.sessionId
const other = await call('/mobile/sessions', {
  method: 'POST',
  headers: jsonAuth,
  body: JSON.stringify({ cwd: workdir }),
})
ok(other.status === 201, `a second chat exists, for the cross-session check (got ${other.status})`)

console.log('\n== staging one file ==')
const content = Buffer.from('# nota\n\nriga due del file allegato.\n')
const upload = await call(`/mobile/files?sessionId=${encodeURIComponent(sessionId)}&name=${encodeURIComponent('K:\\Repository\\nota.md')}`, {
  method: 'POST',
  headers: { ...auth, 'content-type': 'application/octet-stream' },
  body: content,
})
ok(upload.status === 201, `the upload is accepted (got ${upload.status})`)
ok(typeof upload.body.receiptId === 'string' && upload.body.receiptId.length > 0, 'the host answers with a receipt')
ok(upload.body.name === 'nota.md', `the store reduced the path to a leaf name (got ${upload.body.name})`)
ok(upload.body.bytes === content.length, `the stored size is the uploaded size (got ${upload.body.bytes})`)
const attachmentId = String(upload.body.attachmentId ?? '')
ok(/^sha256:[0-9a-f]{64}$/.test(attachmentId), `the reference is a content digest (got ${attachmentId})`)

// The bytes are the store's, not the wire's: reading them back off this
// machine is the only proof that "stored verbatim" happened.
const digest = attachmentId.slice('sha256:'.length)
const storedPath = join(attachmentRoot, 'files', digest.slice(0, 2), digest, 'nota.md')
const stored = await readFile(storedPath).catch(() => null)
ok(stored !== null, `the file is on disk at ${storedPath}`)
ok(stored !== null && stored.equals(content), 'and its bytes are exactly what was uploaded')

const reread = await call(`/mobile/files?sessionId=${encodeURIComponent(sessionId)}&name=nota.md`, {
  method: 'POST',
  headers: { ...auth, 'content-type': 'application/octet-stream' },
  body: content,
})
ok(reread.body.attachmentId === attachmentId, 'uploading the same bytes again is the same object')

console.log('\n== sending the file ==')
const sent = await call(`/mobile/sessions/${encodeURIComponent(sessionId)}/messages`, {
  method: 'POST',
  headers: jsonAuth,
  body: JSON.stringify({ text: 'Leggi il file allegato.', files: [{ receiptId: upload.body.receiptId }] }),
})
ok(sent.status === 202, `the prompt with the file is accepted (got ${sent.status})`)

const fileOnly = await call(`/mobile/files?sessionId=${encodeURIComponent(sessionId)}&name=solo.txt`, {
  method: 'POST',
  headers: { ...auth, 'content-type': 'application/octet-stream' },
  body: Buffer.from('senza testo'),
})
const fileOnlySend = await call(`/mobile/sessions/${encodeURIComponent(sessionId)}/messages`, {
  method: 'POST',
  headers: jsonAuth,
  body: JSON.stringify({ files: [{ receiptId: fileOnly.body.receiptId }] }),
})
ok(fileOnlySend.status === 202, `a message that is only a file is accepted (got ${fileOnlySend.status})`)

console.log('\n== the transcript remembers it ==')
// `202` is an acceptance, not an append: the controller resolves the agent,
// admits the content and only then writes the message, so reading the
// transcript the instant the send returns is a race the transcript loses.
const transcript = await waitForUserMessage(
  sessionId,
  (entry) => (entry.content ?? []).some((block) => block.type === 'file'),
)
const withFile = transcript.message
ok(withFile !== undefined, `the user message carries a file block (saw ${transcript.seen} user messages)`)
const block = (withFile?.content ?? []).find((candidate) => candidate.type === 'file')
ok(block?.attachmentId === attachmentId, `the block names the stored reference (got ${block?.attachmentId})`)
ok(block?.name === 'nota.md' && block?.bytes === content.length, 'the block carries the name and the size the store reported')
const text = (withFile?.content ?? []).find((candidate) => candidate.type === 'text')
ok(text?.text === 'Leggi il file allegato.', 'and the text that came with it')

console.log('\n== what the harness refuses ==')
const noSession = await call('/mobile/files?name=senza.txt', {
  method: 'POST',
  headers: { ...auth, 'content-type': 'application/octet-stream' },
  body: Buffer.from('x'),
})
ok(noSession.status === 400, `an upload with no chat is refused (got ${noSession.status})`)

const unknownSession = await call('/mobile/files?sessionId=session-che-non-esiste&name=x.txt', {
  method: 'POST',
  headers: { ...auth, 'content-type': 'application/octet-stream' },
  body: Buffer.from('x'),
})
ok(unknownSession.status === 404, `an upload for a chat the PC does not hold is refused (got ${unknownSession.status})`)

const oversized = await call(`/mobile/files?sessionId=${encodeURIComponent(sessionId)}&name=grande.bin`, {
  method: 'POST',
  headers: { ...auth, 'content-type': 'application/octet-stream' },
  body: Buffer.alloc(32 * 1024 * 1024 + 1),
})
ok(oversized.status === 413, `a file past the plugin's cap is refused (got ${oversized.status})`)

// A receipt is bound to the agent it was minted for: spending it in another
// chat is not a retry, it is a different conversation, and the controller says
// so. This is the check that the binding is real and not a formality.
const crossChat = await call(`/mobile/sessions/${encodeURIComponent(other.body.sessionId)}/messages`, {
  method: 'POST',
  headers: jsonAuth,
  body: JSON.stringify({ text: 'x', files: [{ receiptId: upload.body.receiptId }] }),
})
ok(crossChat.status === 400, `another chat cannot spend the receipt (got ${crossChat.status})`)
ok(crossChat.body?.error?.code === 'session/attachment-invalid', `and it says why (got ${crossChat.body?.error?.code})`)

console.log('\n== cleanup ==')
await rm(workdir, { recursive: true, force: true })
const gone = !(await stat(workdir).then(() => true).catch(() => false))
ok(gone, 'the probe directory is removed from this machine')
console.log('        (the chats and the stored attachments stay on the harness)')

console.log(`\n${failures === 0 ? 'FILE E2E PASSED' : 'FILE E2E FAILED'}: ${checks - failures}/${checks} checks`)
process.exit(failures === 0 ? 0 : 1)
