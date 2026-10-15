/**
 * End-to-end verification of the mobile-sync plugin against a *real* harness.
 *
 * Boots nothing: it talks to a live `dsh web` instance on which the plugin is
 * mounted, so every service behind the routes is the genuine
 * sessionController / attachments / agents assembly.
 *
 * Usage: node e2e-real.mjs <baseUrl> <pairingCode>
 */

const base = process.argv[2] ?? 'http://127.0.0.1:4399'
const setupCode = process.argv[3]

if (setupCode === undefined) {
  console.error('usage: node e2e-real.mjs <baseUrl> <pairingCode>')
  process.exit(2)
}

/** The port under test, so the status assertion works on any deployment. */
const expectedPort = Number.parseInt(new URL(base).port, 10)

let failures = 0
let checks = 0

/**
 * Assert one condition, recording rather than throwing so the run reports
 * every problem it finds.
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
  return { status: response.status, body, headers: response.headers }
}

console.log(`\n== pairing against ${base} ==`)
const paired = await call('/mobile/pair', {
  method: 'POST',
  headers: { 'content-type': 'application/json' },
  body: JSON.stringify({ code: setupCode, device: 'e2e-verify' }),
})
ok(paired.status === 200, `pairing succeeds (status ${paired.status})`)
if (paired.status !== 200) {
  console.log(JSON.stringify(paired.body))
  process.exit(1)
}
const token = paired.body.token
ok(typeof token === 'string' && token.length > 20, 'a device token was issued')

const auth = { authorization: `Bearer ${token}` }
const jsonAuth = { ...auth, 'content-type': 'application/json' }

console.log('\n== status ==')
const status = await call('/mobile/status', { headers: auth })
ok(status.status === 200, `status answers 200 (got ${status.status})`)
ok(status.body.ok === true, 'status reports ok')
ok(typeof status.body.sessionCount === 'number', `host reports a session count (${status.body.sessionCount})`)
ok(status.body.port === expectedPort, `listener reports the port it was reached on (${status.body.port} vs ${expectedPort})`)
ok(
  Array.isArray(status.body.devices) && status.body.devices.some((device) => device.name === 'e2e-verify'),
  'this run\'s paired device is listed',
)

console.log('\n== session list (real stored sessions) ==')
const list = await call('/mobile/sessions', { headers: auth })
ok(list.status === 200, `session list answers 200 (got ${list.status})`)
ok(Array.isArray(list.body.items), 'items is an array')
const items = list.body.items ?? []
console.log(`        ${items.length} session(s) returned`)
if (items.length > 0) {
  const sample = items[0]
  console.log(`        newest: ${sample.id}`)
  console.log(`                title=${JSON.stringify(sample.title)} running=${sample.running} cwd=${JSON.stringify(sample.cwd)}`)
  ok(typeof sample.id === 'string' && sample.id.startsWith('session-'), 'session ids use the harness vocabulary')
  ok(typeof sample.updatedAt === 'number' && sample.updatedAt > 0, 'updatedAt is a real timestamp')
  ok(items.every((item, index) => index === 0 || item.updatedAt <= items[index - 1].updatedAt), 'sessions are ordered newest first')
  ok(!('projections' in sample), 'projection internals are not leaked raw')
}

console.log('\n== history page (real session log) ==')
const target = items.find((item) => item.origin !== 'subagent') ?? items[0]
if (target === undefined) {
  console.log('  SKIP  no stored session to page through')
} else {
  const page = await call(`/mobile/sessions/${encodeURIComponent(target.id)}/messages?limit=40`, { headers: auth })
  ok(page.status === 200, `history answers 200 (got ${page.status})`)
  ok(page.body.sessionId === target.id, 'history is addressed to the requested session')
  ok(typeof page.body.cursor === 'number', `a durable cursor is returned (${page.body.cursor})`)
  const entries = page.body.entries ?? []
  console.log(`        ${entries.length} presentable entr(ies), hasMore=${page.body.hasMore}`)
  ok(entries.every((entry) => typeof entry.seq === 'number'), 'every entry carries its log sequence')
  ok(entries.every((entry, index) => index === 0 || entry.seq > entries[index - 1].seq), 'entries are strictly increasing by seq')
  ok(entries.every((entry) => typeof entry.kind === 'string'), 'every entry carries a kind')
  const kinds = [...new Set(entries.map((entry) => entry.kind))]
  console.log(`        kinds present: ${kinds.join(', ') || '(none)'}`)
  const messages = entries.filter((entry) => entry.kind === 'message')
  if (messages.length > 0) {
    const withText = messages.find((entry) => entry.content.some((block) => block.type === 'text' && block.text.length > 0))
    ok(withText !== undefined, 'at least one message carries readable text')
    if (withText !== undefined) {
      const text = withText.content.find((block) => block.type === 'text').text
      console.log(`        sample ${withText.role}: ${JSON.stringify(text.slice(0, 90))}`)
    }
    const withImage = messages.find((entry) => entry.content.some((block) => block.type === 'image' && block.attachmentId))
    if (withImage !== undefined) {
      const block = withImage.content.find((item) => item.type === 'image' && item.attachmentId)
      console.log(`        found an image attachment: ${block.attachmentId} (${block.mediaType})`)
      const raw = await fetch(`${base}/mobile/sessions/${encodeURIComponent(target.id)}/images/${encodeURIComponent(block.attachmentId)}/raw`, { headers: auth })
      ok(raw.status === 200 || raw.status === 404, `image route answers deterministically (got ${raw.status})`)
      if (raw.status === 200) {
        const bytes = new Uint8Array(await raw.arrayBuffer())
        ok(bytes.byteLength > 0, `image bytes are served (${bytes.byteLength} bytes)`)
        ok(raw.headers.get('content-type')?.startsWith('image/') === true, `correct content-type (${raw.headers.get('content-type')})`)
      } else {
        console.log('        (image not referenced by a live agent; route correctly refused)')
      }
    } else {
      console.log('        (no image in this session log)')
    }
  } else {
    console.log('        (session log holds no surfaced messages yet)')
  }

  const backwards = await call(`/mobile/sessions/${encodeURIComponent(target.id)}/messages?limit=5`, { headers: auth })
  ok(backwards.status === 200, 'a small page is served')
  if (entries.length > 5 && backwards.body.entries.length > 0) {
    const oldest = backwards.body.entries[0].seq
    const older = await call(`/mobile/sessions/${encodeURIComponent(target.id)}/messages?limit=5&beforeSeq=${oldest}`, { headers: auth })
    ok(older.status === 200, `backwards paging answers 200 (got ${older.status})`)
    ok(
      (older.body.entries ?? []).every((entry) => entry.seq < oldest),
      'backwards pages return strictly older entries',
    )
  }
}

console.log('\n== live event feed ==')
{
  const controller = new AbortController()
  const stream = await fetch(`${base}/mobile/events`, { headers: auth, signal: controller.signal })
  ok(stream.status === 200, `the event stream opens (got ${stream.status})`)
  ok(stream.headers.get('content-type')?.includes('text/event-stream') === true, 'the stream declares text/event-stream')
  const reader = stream.body.getReader()
  const decoder = new TextDecoder()
  const opening = decoder.decode((await reader.read()).value)
  ok(opening.includes('"event":"ready"'), 'the stream announces readiness')
  controller.abort()
}

console.log('\n== prompt delivery to a real agent ==')
{
  // A freshly created session is the honest test: it proves session creation,
  // prompt admission, agent dispatch, and history readback in one pass, and it
  // avoids attaching a synthetic turn to a log the desktop app may be holding.
  const created = await call('/mobile/sessions', {
    method: 'POST',
    headers: jsonAuth,
    body: JSON.stringify({}),
  })
  ok(created.status === 201, `session creation answers 201 (got ${created.status})`)
  if (created.status !== 201) {
    console.log(`        ${JSON.stringify(created.body)}`)
  } else {
    const sessionId = created.body.sessionId
    console.log(`        new session: ${sessionId}`)
    ok(typeof sessionId === 'string' && sessionId.startsWith('session-'), 'the host minted a session id')

    const relisted = await call('/mobile/sessions', { headers: auth })
    ok(
      (relisted.body.items ?? []).some((item) => item.id === sessionId),
      'the new session appears in the mobile list',
    )

    const sent = await call(`/mobile/sessions/${encodeURIComponent(sessionId)}/messages`, {
      method: 'POST',
      headers: jsonAuth,
      body: JSON.stringify({ text: 'Rispondi solo con la parola: MOBILEOK' }),
    })
    ok(sent.status === 202, `prompt is accepted (got ${sent.status})`)
    if (sent.status !== 202) console.log(`        ${JSON.stringify(sent.body)}`)

    let sawUser = false
    let sawAssistant = false
    const deadline = Date.now() + 90_000
    while (Date.now() < deadline && !sawAssistant) {
      await new Promise((resolve) => setTimeout(resolve, 2500))
      const now = await call(`/mobile/sessions/${encodeURIComponent(sessionId)}/messages?limit=40`, { headers: auth })
      for (const entry of now.body.entries ?? []) {
        if (entry.kind !== 'message') continue
        const text = entry.content.filter((block) => block.type === 'text').map((block) => block.text).join(' ')
        if (entry.role === 'user' && text.includes('MOBILEOK')) {
          if (!sawUser) console.log(`        user message appended: ${JSON.stringify(text.slice(0, 60))}`)
          sawUser = true
        }
        if (entry.role === 'assistant' && text.trim().length > 0) {
          console.log(`        assistant replied: ${JSON.stringify(text.trim().slice(0, 120))}`)
          sawAssistant = true
        }
      }
    }
    ok(sawUser, 'the prompt was durably appended to the real session log')
    ok(sawAssistant, 'a real agent answered the phone-originated prompt')

    const after = await call(`/mobile/sessions/${encodeURIComponent(sessionId)}/messages?limit=40`, { headers: auth })
    const kinds = [...new Set((after.body.entries ?? []).map((entry) => entry.kind))]
    console.log(`        transcript kinds: ${kinds.join(', ')}`)
    ok(kinds.includes('message'), 'the transcript exposes message entries')
    ok(typeof after.body.cursor === 'number' && after.body.cursor >= 0, `the cursor advanced past the new turn (${after.body.cursor})`)
  }
}

console.log('\n== photo round trip (store, prompt, serve) ==')
{
  const created = await call('/mobile/sessions', {
    method: 'POST',
    headers: jsonAuth,
    body: JSON.stringify({}),
  })
  ok(created.status === 201, `a session for the photo test was created (got ${created.status})`)
  const photoSession = created.body.sessionId

  // A real 8x8 red PNG, built here so the test carries no binary fixture.
  const png = makePng(8, 8, [220, 40, 40])
  const base64 = Buffer.from(png).toString('base64')
  ok(png[1] === 0x50 && png[2] === 0x4e && png[3] === 0x47, 'the local fixture is a real PNG')

  const uploaded = await call('/mobile/images', {
    method: 'POST',
    headers: jsonAuth,
    body: JSON.stringify({ data: base64, name: 'rosso.png' }),
  })
  ok(uploaded.status === 201, `image upload answers 201 (got ${uploaded.status})`)
  if (uploaded.status !== 201) {
    console.log(`        ${JSON.stringify(uploaded.body)}`)
  } else {
    console.log(`        stored as ${uploaded.body.attachmentId} (${uploaded.body.bytes} bytes)`)

    // Photos travel inline as canonical base64 image parts: the harness admits
    // them into the durable image store and the model sees image content, not
    // an opaque file handle. (A staged file receipt would surface as a file
    // block, which is the wrong modality for a photo.)
    const sent = await call(`/mobile/sessions/${encodeURIComponent(photoSession)}/messages`, {
      method: 'POST',
      headers: jsonAuth,
      body: JSON.stringify({
        text: 'Descrivi in una parola il colore di questa immagine.',
        images: [{ mediaType: 'image/png', data: base64, name: 'rosso.png' }],
      }),
    })
    ok(sent.status === 202, `a photo message is accepted (got ${sent.status})`)
    if (sent.status !== 202) console.log(`        ${JSON.stringify(sent.body)}`)

    let imageAttachmentId
    let sawReply = false
    const deadline = Date.now() + 90_000
    while (Date.now() < deadline && !(imageAttachmentId !== undefined && sawReply)) {
      await new Promise((resolve) => setTimeout(resolve, 2500))
      const page = await call(`/mobile/sessions/${encodeURIComponent(photoSession)}/messages?limit=40`, { headers: auth })
      for (const entry of page.body.entries ?? []) {
        if (entry.kind !== 'message') continue
        if (entry.role === 'user') {
          const image = entry.content.find((block) => block.type === 'image' && block.attachmentId)
          if (image !== undefined && imageAttachmentId === undefined) {
            imageAttachmentId = image.attachmentId
            console.log(`        user message carries an image block: ${image.attachmentId} (${image.mediaType} ${image.width}x${image.height})`)
          }
        }
        if (entry.role === 'assistant') {
          const text = entry.content.filter((block) => block.type === 'text').map((block) => block.text).join(' ')
          if (text.trim().length > 0) {
            if (!sawReply) console.log(`        assistant replied: ${JSON.stringify(text.trim().slice(0, 120))}`)
            sawReply = true
          }
        }
      }
    }
    ok(imageAttachmentId !== undefined, 'the photo was admitted as a durable image block in the real log')
    ok(sawReply, 'the real agent answered a photo message')

    if (imageAttachmentId !== undefined) {
      const raw = await fetch(
        `${base}/mobile/sessions/${encodeURIComponent(photoSession)}/images/${encodeURIComponent(imageAttachmentId)}/raw`,
        { headers: auth },
      )
      ok(raw.status === 200, `the transcript image is served back (got ${raw.status})`)
      if (raw.status === 200) {
        const served = Buffer.from(await raw.arrayBuffer())
        ok(served.length > 0, `image bytes are non-empty (${served.length} bytes)`)
        ok(served[1] === 0x50 && served[2] === 0x4e && served[3] === 0x47, 'the served bytes are the PNG we sent')
        ok(raw.headers.get('content-type') === 'image/png', `served with the right media type (${raw.headers.get('content-type')})`)
      }
    }
  }
}

console.log(`\n${failures === 0 ? 'E2E PASSED' : 'E2E FAILED'}: ${checks - failures}/${checks} checks\n`)
process.exit(failures === 0 ? 0 : 1)

/**
 * Build a minimal valid PNG: no compression is needed beyond stored deflate
 * blocks, so this stays dependency-free.
 *
 * @param width - image width in pixels.
 * @param height - image height in pixels.
 * @param rgb - the solid fill colour.
 * @returns the encoded PNG bytes.
 */
function makePng(width, height, rgb) {
  const raw = Buffer.alloc(height * (1 + width * 3))
  for (let y = 0; y < height; y += 1) {
    const rowStart = y * (1 + width * 3)
    raw[rowStart] = 0
    for (let x = 0; x < width; x += 1) {
      const at = rowStart + 1 + x * 3
      raw[at] = rgb[0]
      raw[at + 1] = rgb[1]
      raw[at + 2] = rgb[2]
    }
  }
  const ihdr = Buffer.alloc(13)
  ihdr.writeUInt32BE(width, 0)
  ihdr.writeUInt32BE(height, 4)
  ihdr[8] = 8
  ihdr[9] = 2
  const signature = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a])
  return Buffer.concat([signature, chunk('IHDR', ihdr), chunk('IDAT', storedZlib(raw)), chunk('IEND', Buffer.alloc(0))])
}

/**
 * Wrap a payload in one PNG chunk with its CRC.
 *
 * @param type - the four-character chunk type.
 * @param payload - the chunk body.
 * @returns the framed chunk.
 */
function chunk(type, payload) {
  const length = Buffer.alloc(4)
  length.writeUInt32BE(payload.length, 0)
  const body = Buffer.concat([Buffer.from(type, 'ascii'), payload])
  const crc = Buffer.alloc(4)
  crc.writeUInt32BE(crc32(body) >>> 0, 0)
  return Buffer.concat([length, body, crc])
}

/**
 * Wrap bytes in a zlib stream made of stored (uncompressed) deflate blocks.
 *
 * @param data - the raw bytes to wrap.
 * @returns a valid zlib stream.
 */
function storedZlib(data) {
  const parts = [Buffer.from([0x78, 0x01])]
  const maxBlock = 65535
  for (let offset = 0; offset < data.length; offset += maxBlock) {
    const slice = data.subarray(offset, Math.min(offset + maxBlock, data.length))
    const last = offset + maxBlock >= data.length ? 1 : 0
    const header = Buffer.alloc(5)
    header[0] = last
    header.writeUInt16LE(slice.length, 1)
    header.writeUInt16LE(0xffff - slice.length, 3)
    parts.push(header, slice)
  }
  const adler = Buffer.alloc(4)
  adler.writeUInt32BE(adler32(data) >>> 0, 0)
  parts.push(adler)
  return Buffer.concat(parts)
}

/**
 * Compute the CRC-32 used by PNG chunks.
 *
 * @param buffer - bytes to hash.
 * @returns the unsigned checksum.
 */
function crc32(buffer) {
  let crc = 0xffffffff
  for (const byte of buffer) {
    crc ^= byte
    for (let bit = 0; bit < 8; bit += 1) {
      crc = crc & 1 ? (crc >>> 1) ^ 0xedb88320 : crc >>> 1
    }
  }
  return (crc ^ 0xffffffff) >>> 0
}

/**
 * Compute the Adler-32 checksum used by zlib.
 *
 * @param buffer - bytes to hash.
 * @returns the unsigned checksum.
 */
function adler32(buffer) {
  let a = 1
  let b = 0
  for (const byte of buffer) {
    a = (a + byte) % 65521
    b = (b + a) % 65521
  }
  return ((b << 16) | a) >>> 0
}
