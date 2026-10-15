/**
 * Scratch: what does the harness project onto a session-list row?
 *
 * The phone's status dot needs a status the *host* already knows: "the last turn
 * ended well" is not something a client can derive from a title and a timestamp.
 * This searches the harness bundle for the projection the plugin already reads
 * (`sessionListMetadata`) and for how a turn's end is recorded, then prints the
 * code around every hit — so the plugin carries what exists instead of inventing
 * a status on the phone.
 *
 * Read-only.
 */

import { readFileSync } from 'node:fs'
import { join } from 'node:path'

const ASAR =
  process.env.ASARPATH ??
  join(
    process.env.LOCALAPPDATA ?? join(process.env.USERPROFILE ?? '', 'AppData', 'Local'),
    'Programs',
    'DeepSeek Harness',
    'resources',
    'app.asar',
  )
const buf = readFileSync(ASAR)

const start = buf.indexOf('{"files":')
let depth = 0
let end = -1
let inString = false
let escaped = false
for (let at = start; at < buf.length; at += 1) {
  const char = buf[at]
  if (inString) {
    if (escaped) escaped = false
    else if (char === 0x5c) escaped = true
    else if (char === 0x22) inString = false
    continue
  }
  if (char === 0x22) inString = true
  else if (char === 0x7b) depth += 1
  else if (char === 0x7d) {
    depth -= 1
    if (depth === 0) {
      end = at + 1
      break
    }
  }
}
const header = JSON.parse(buf.subarray(start, end).toString('utf8'))
const bodyAt = start + Math.ceil((end - start) / 4) * 4

function walk(node, prefix, out) {
  for (const [name, entry] of Object.entries(node.files ?? {})) {
    const path = prefix === '' ? name : `${prefix}/${name}`
    if (entry.files !== undefined) walk(entry, path, out)
    else out.push({ path, offset: Number(entry.offset), size: entry.size })
  }
  return out
}

const interesting = /(session|sidebar|list|desktop-host|turn)/i
const scripts = walk(header, '', []).filter(
  (file) => /\.(js|mjs|cjs)$/.test(file.path) && interesting.test(file.path),
)
console.log(`${scripts.length} candidate scripts`)

const needles = process.argv.slice(2)
const wanted = needles.length > 0 ? needles : ['sessionListMetadata', 'lastTurnReason', 'lastTurnFailed', 'turn/end']
let hits = 0
for (const file of scripts) {
  const text = buf.subarray(bodyAt + file.offset, bodyAt + file.offset + file.size).toString('utf8')
  for (const needle of wanted) {
    let at = text.indexOf(needle)
    let shown = 0
    while (at >= 0 && shown < 2 && hits < 40) {
      console.log(`\n--- ${file.path} :: ${needle}\n${text.slice(Math.max(0, at - 320), at + 380).replace(/\s+/g, ' ')}`)
      shown += 1
      hits += 1
      at = text.indexOf(needle, at + needle.length)
    }
  }
}
console.log(`\n${hits} hits`)
