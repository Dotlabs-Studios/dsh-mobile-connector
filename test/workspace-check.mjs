/**
 * End-to-end verification of the workspace and directory routes against a
 * *real* harness.
 *
 * Boots nothing: it talks to a live `dsh web` instance on which the plugin is
 * mounted, so the projects it reads come from the genuine workspace registry
 * and the levels it lists come from the real filesystem of this machine.
 *
 * The point of this suite is the part the offline suite cannot prove: that the
 * host actually mounts `workspaceController`, that the registry's own projects
 * reach the phone with the key the sessions are grouped by, and that a
 * directory the phone creates really appears on disk.
 *
 * Usage: node workspace-check.mjs <baseUrl> <pairingCode>
 */

import { readFile, rm, stat } from 'node:fs/promises'
import { homedir } from 'node:os'
import { basename, dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const base = process.argv[2] ?? 'http://127.0.0.1:4399'
const setupCode = process.argv[3]

// The levels this suite names are the ones the checkout actually sits in, so
// the run proves something about *this* machine instead of a path written down
// once on the author's.
const testDir = fileURLToPath(new URL('.', import.meta.url)).replace(/[\\/]$/, '')
const repoRoot = dirname(testDir)
const repoParent = dirname(repoRoot)
const repoName = basename(repoRoot)

if (setupCode === undefined) {
  console.error('usage: node workspace-check.mjs <baseUrl> <pairingCode>')
  process.exit(2)
}

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
  return { status: response.status, body }
}

console.log(`\n== pairing against ${base} ==`)
const paired = await call('/mobile/pair', {
  method: 'POST',
  headers: { 'content-type': 'application/json' },
  body: JSON.stringify({ code: setupCode, device: 'workspace-verify' }),
})
ok(paired.status === 200, `pairing succeeds (status ${paired.status})`)
if (paired.status !== 200) {
  console.log(JSON.stringify(paired.body))
  process.exit(1)
}
const token = paired.body.token
const auth = { authorization: `Bearer ${token}` }
const jsonAuth = { ...auth, 'content-type': 'application/json' }

console.log('\n== workspace registry ==')
const listed = await call('/mobile/workspaces', { headers: auth })
ok(listed.status === 200, `workspace list answers 200 (got ${listed.status})`)
if (listed.status !== 200) {
  console.log(JSON.stringify(listed.body))
  process.exit(1)
}
const workspaces = listed.body.items
ok(Array.isArray(workspaces) && workspaces.length > 0, `the host reports its projects (${workspaces.length})`)
ok(
  workspaces.every((item) => typeof item.workspaceId === 'string' && typeof item.path === 'string'),
  'every project carries an id and a host path',
)
ok(
  workspaces.every((item) => item.path === item.path.trim() && /^[A-Za-z]:\\/.test(item.path)),
  'every project path is a canonical Windows directory',
)

// The harness keeps its own registry on disk; the route must agree with it.
// The path follows `DSH_HOME` when a verification boot redirects it, so the
// assertion reads the very file the harness under test is serving from.
const dshHome = process.env.DSH_HOME ?? join(process.env.USERPROFILE ?? homedir(), '.dsh')
const registryPath = join(dshHome, 'storages', 'workspace.json')
const registry = JSON.parse(await readFile(registryPath, 'utf8'))
const registryPaths = Object.values(registry.tables.workspaces).map((record) => record.path)
ok(
  registryPaths.every((path) => workspaces.some((item) => item.path.toLowerCase() === path.toLowerCase())),
  'every project in the durable registry is reported by the route',
)
console.log(`        projects: ${workspaces.map((item) => item.title).join(', ')}`)

console.log('\n== sessions carry their project ==')
const sessions = await call('/mobile/sessions', { headers: auth })
ok(sessions.status === 200, `session list answers 200 (got ${sessions.status})`)
const rows = sessions.body.items
const grouped = rows.filter((row) => row.workspaceId !== null)
ok(grouped.length > 0, `at least one chat is placed in a project (${grouped.length}/${rows.length})`)
ok(
  grouped.every((row) => row.workspaceTitle !== null),
  'a grouped chat always carries its project title too',
)
const byId = new Map(workspaces.map((item) => [item.workspaceId, item]))
ok(
  grouped.every((row) => byId.get(row.workspaceId)?.path.toLowerCase() === row.cwd.toLowerCase()),
  'each chat is grouped under the project that owns its own directory',
)
ok(
  rows.filter((row) => row.cwd === null || row.cwd === undefined).every((row) => row.workspaceId === null),
  'a chat without a directory stays ungrouped',
)
for (const row of grouped.slice(0, 3)) {
  console.log(`        ${row.workspaceTitle} <- ${row.cwd}`)
}

const plain = await call('/mobile/sessions?workspaces=0', { headers: auth })
ok(plain.body.items.every((row) => row.workspaceId === null), 'grouping can be switched off for a plain list')

console.log('\n== the PC filesystem, as the phone sees it ==')
const home = await call('/mobile/dirs', { headers: auth })
ok(home.status === 200, `the home level answers 200 (got ${home.status})`)
ok(home.body.home.toLowerCase() === homedir().toLowerCase(), 'the host reports its own home directory')
const driveCrumbs = home.body.crumbs.filter((crumb) => crumb.drive === true)
ok(driveCrumbs.length > 0, `the host offers its volumes as shortcuts (${driveCrumbs.map((c) => c.path).join(' ')})`)
ok(
  home.body.entries.every((entry) => entry.path.toLowerCase().startsWith(home.body.path.toLowerCase())),
  'every row is an absolute path inside the listed level',
)
ok(
  home.body.entries.every((entry) => !entry.name.includes('\\') && !entry.name.includes('/')),
  'a row carries a name, not a path',
)

const repo = await call(`/mobile/dirs?path=${encodeURIComponent(repoParent)}`, { headers: auth })
ok(repo.status === 200, `a real project directory lists (got ${repo.status})`)
const repoNames = repo.body.entries.map((entry) => entry.name)
ok(repoNames.includes(repoName), 'the level contains the directory we know is there')
ok(
  repoNames.every((name, index) => index === 0 || repoNames[index - 1].localeCompare(name) <= 0),
  'the level arrives name-sorted',
)

const relative = await call('/mobile/dirs?path=Repository', { headers: auth })
ok(relative.status === 400, `a drive-relative path is refused (got ${relative.status})`)
const missing = await call(`/mobile/dirs?path=${encodeURIComponent('K:\\Non\\Esiste\\Per\\Nulla')}`, { headers: auth })
ok(missing.status === 404, `an unreadable directory answers 404 (got ${missing.status})`)

console.log('\n== creating a folder on the PC ==')
const folderName = `dsh-mobile-check-${Date.now()}`
const created = await call('/mobile/dirs', {
  method: 'POST',
  headers: jsonAuth,
  body: JSON.stringify({ parent: testDir, name: folderName }),
})
ok(created.status === 201, `creating a folder answers 201 (got ${created.status})`)
const createdPath = join(testDir, folderName)
ok(created.body.created === createdPath, `the host reports the created path (${created.body.created})`)
const onDisk = await stat(createdPath).then((info) => info.isDirectory()).catch(() => false)
ok(onDisk, 'the folder really exists on this machine')
ok(created.body.entries.some((entry) => entry.name === folderName), 'the refreshed level already shows it')

console.log('\n== adopting a directory as a project ==')
const adopted = await call('/mobile/workspaces', {
  method: 'POST',
  headers: jsonAuth,
  body: JSON.stringify({ path: createdPath }),
})
ok(adopted.status === 201, `adopting a new directory answers 201 (got ${adopted.status})`)
ok(adopted.body.created === true, 'the host reports the project as newly created')
ok(adopted.body.workspaceId.length > 0, 'the project has an id')
ok(adopted.body.title === folderName, 'the project is titled after its folder')

const again = await call('/mobile/workspaces', {
  method: 'POST',
  headers: jsonAuth,
  body: JSON.stringify({ path: createdPath }),
})
ok(again.status === 200 && again.body.created === false, `re-adopting is idempotent (got ${again.status})`)
ok(again.body.workspaceId === adopted.body.workspaceId, 're-adopting returns the same project')

const afterAdopt = await call('/mobile/workspaces', { headers: auth })
ok(afterAdopt.body.items.length === workspaces.length + 1, 'the new project joined the registry order')

console.log('\n== starting a chat in that project ==')
const chat = await call('/mobile/sessions', {
  method: 'POST',
  headers: jsonAuth,
  // Naming the project, not the directory: that is what records the chat in
  // the project's own account and therefore what makes it show up in the
  // desktop sidebar. Creating by directory alone leaves the account empty.
  body: JSON.stringify({ workspaceId: adopted.body.workspaceId }),
})
ok(chat.status === 201, `a session starts in the new project (got ${chat.status})`)
const sessionId = chat.body.sessionId
const afterChat = await call('/mobile/sessions', { headers: auth })
const row = afterChat.body.items.find((item) => item.id === sessionId)
ok(row !== undefined, 'the new chat appears in the list')
ok(row?.cwd === createdPath, 'the chat runs in the directory that was chosen')
ok(row?.workspaceId === adopted.body.workspaceId, 'the chat is grouped under the new project')
console.log(`        session ${sessionId} -> ${row?.workspaceTitle}`)

// The durable account is the difference between "the phone groups it" and
// "the PC groups it": only an attached session shows in the desktop sidebar.
const afterDisk = JSON.parse(await readFile(registryPath, 'utf8'))
const account = afterDisk.tables.workspaces[adopted.body.workspaceId]
ok(
  Array.isArray(account?.sessionIds) && account.sessionIds.includes(sessionId),
  'the project records the chat in its own durable account',
)

console.log('\n== cleanup ==')
await rm(createdPath, { recursive: true, force: true })
ok(!(await stat(createdPath).then(() => true).catch(() => false)), 'the probe directory is removed from this machine')
console.log('        (the project and the chat stay on the harness; delete them from the GUI if unwanted)')

console.log(`\n${failures === 0 ? 'WORKSPACE E2E PASSED' : 'WORKSPACE E2E FAILED'}: ${checks - failures}/${checks} checks`)
process.exit(failures === 0 ? 0 : 1)
