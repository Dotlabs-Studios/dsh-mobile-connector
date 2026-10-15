/**
 * Probe: does the app's "up one level" rule agree with the real tree?
 *
 * Runs the *app's* rule (the one in `data/Folders.kt`, `browseParent`) against the
 * live `/mobile/dirs` route, level by level, and reports every level where the
 * answer differs from the directory's real parent. It is how the original defect
 * was located: the rule was right on all 120 levels that have a parent, so the
 * broken case had to be the one that has none — a volume root — where a dead
 * arrow is the honest answer but a hidden way out is not. Read-only.
 *
 * Usage: node test/_probe-up.mjs [baseUrl] [token]
 */

import { readFileSync } from 'node:fs'
import { homedir } from 'node:os'
import { dirname, sep } from 'node:path'

const base = process.argv[2] ?? 'http://127.0.0.1:4380'
const statePath = `${homedir()}\\.dsh\\storages\\mobile-sync.json`
const token = process.argv[3] ?? JSON.parse(readFileSync(statePath, 'utf8')).devices[0].token

async function list(path) {
  const query = path === undefined ? '' : `?path=${encodeURIComponent(path)}`
  const response = await fetch(`${base}/mobile/dirs${query}`, { headers: { authorization: `Bearer ${token}` } })
  const body = await response.json()
  if (!response.ok) throw new Error(`${path}: ${response.status} ${JSON.stringify(body)}`)
  return body
}

/**
 * The app's rule, mirroring `browseParent`: the ancestor the host reported, from
 * the end (a drive shortcut and the ancestor it duplicates are the same path, and
 * only the last one is the ancestor), and never a shortcut.
 */
function appUp(level) {
  const here = level.path.trim()
  if (here === '') return null
  const index = level.crumbs.map((crumb) => crumb.path.trim().toLowerCase()).lastIndexOf(here.toLowerCase())
  if (index <= 0) return null
  const parent = level.crumbs[index - 1]
  if (parent.drive) return null
  const path = parent.path.trim()
  if (path === '' || path.toLowerCase() === here.toLowerCase()) return null
  return path
}

/** What "up" means to the filesystem. */
function trueParent(path) {
  const parent = dirname(path)
  return parent === path ? null : parent
}

const seen = new Set()
const queue = [undefined, 'C:\\', 'D:\\', 'E:\\', 'F:\\', 'H:\\', 'K:\\']
let checked = 0
const problems = []
const unreadable = []

while (queue.length > 0 && checked < 120) {
  const requested = queue.shift()
  let level
  try {
    level = await list(requested)
  } catch (error) {
    unreadable.push(String(error.message).split('\n')[0])
    continue
  }
  const key = level.path.toLowerCase()
  if (seen.has(key)) continue
  seen.add(key)
  checked += 1
  const actual = appUp(level)
  const expected = trueParent(level.path)
  const agrees = (actual ?? '').toLowerCase() === (expected ?? '').toLowerCase()
  if (!agrees) {
    problems.push({ path: level.path, appUp: actual, trueParent: expected, crumbs: level.crumbs.map((c) => `${c.name}=${c.path}${c.drive ? ' (drive)' : ''}`) })
  }
  if (checked <= 6) {
    console.log(`${agrees ? 'ok  ' : 'FAIL'} ${level.path}  up=${actual ?? '(nothing)'}  parent=${expected ?? '(none)'}`)
  }
  if (level.path === homedir() || requested === undefined) {
    console.log(`     crumbs: ${level.crumbs.map((c) => c.path + (c.drive ? '*' : '')).join(' | ')}`)
  }
  // Walk one screenful down, so the probe sees inside the tree as well as above it.
  for (const entry of level.entries.slice(0, 8)) queue.push(entry.path)
}

console.log(`\nchecked ${checked} levels`)
console.log(problems.length === 0 ? 'no disagreement' : `${problems.length} level(s) where "up" is not the parent:`)
for (const problem of problems.slice(0, 25)) {
  console.log(`  ${problem.path}\n     app up -> ${problem.appUp ?? '(nothing)'}   real parent -> ${problem.trueParent ?? '(none)'}`)
  console.log(`     crumbs: ${problem.crumbs.join(' | ')}`)
}
console.log(`separator in use: ${JSON.stringify(sep)}`)
console.log(`skipped ${unreadable.length} unreadable level(s)`)
