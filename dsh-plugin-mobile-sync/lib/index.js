/**
 * dsh-plugin-mobile-sync — a dedicated HTTP surface that mirrors the running
 * harness to a phone client.
 *
 * Design notes
 * ------------
 * The shipped Web GUI binds loopback and authenticates browsers with a launch
 * token plus a host-bound signed cookie. That contract is deliberately not
 * reusable by a native phone client, so this plugin owns its own listener on a
 * separate port, its own bearer-token device model, and its own small REST +
 * SSE vocabulary. Only the endpoints below are exposed; the GUI's `/api` bridge
 * and SPA routes are never touched.
 *
 * Services used (all injected, never imported, so the package needs no
 * `@deepseek-ai/*` runtime dependency):
 *   - ctx.sessionController : list / create / page / prompt  (Host generation)
 *   - ctx.attachments       : readImage, readFileStream, admitEncodedFile
 *   - ctx.agents            : live-agent lookup for cached image serving
 *   - ctx.workspaceController : the durable project list (optional; the phone
 *     falls back to an ungrouped session list when the host mounts none)
 *
 * Everything else is node: builtins. Directory browsing is implemented here
 * rather than borrowed from `ctx.directoryPicker` on purpose: that seam is
 * pinned per boot and on an attended host it resolves to the *native* chooser,
 * which opens a dialog on the PC's own display — a dialog no phone can see or
 * answer. Listing one level with the platform's own path rules works from any
 * client, which is the only interaction a remote phone can drive.
 */

import { createServer } from 'node:http'
import { spawn } from 'node:child_process'
import { createHash, randomBytes, timingSafeEqual } from 'node:crypto'
import { createReadStream } from 'node:fs'
import { mkdir, open, opendir, readFile, rename, stat } from 'node:fs/promises'
import { homedir, networkInterfaces } from 'node:os'
import { basename as baseNameOf, dirname, isAbsolute, join, parse as parsePath, relative, resolve, sep, win32 } from 'node:path'

/**
 * The wire contract this build speaks.
 *
 * Bumped when a route changes shape in a way an older client would decode
 * wrongly, so the phone can say "this host is too old" instead of failing at the
 * first request it did not expect. The app reports its own version at pairing;
 * both travel in `/mobile/status` and `/mobile/health`.
 */
const API_VERSION = 2
/** The plugin's own version, mirrored from package.json for the status payload. */
const PLUGIN_VERSION = '0.3.0'

/** Hard cap on one JSON request body; base64 photos dominate this. */
const MAX_BODY_BYTES = 48 * 1024 * 1024
/**
 * Cap on the pairing request body.
 *
 * Pairing carries a code and a device label, nothing else, so it gets its own
 * ceiling instead of the 48 MiB the upload routes need: the one unauthenticated
 * route on the listener should not be a place to push megabytes into.
 */
const MAX_PAIR_BODY_BYTES = 4 * 1024
/** Pair attempts one address may make inside {@link PAIR_ATTEMPT_WINDOW_MS}. */
const PAIR_ATTEMPT_LIMIT = 10
/** The window a pair attempt is counted in, in milliseconds. */
const PAIR_ATTEMPT_WINDOW_MS = 60_000
/** Addresses tracked by the pairing limiter; the oldest is dropped past this. */
const PAIR_ATTEMPT_TRACK_LIMIT = 256
/**
 * Bytes a stalled SSE reader may fall behind before it is dropped.
 *
 * `res.write` returns `false` when Node has buffered past the high-water mark,
 * and a phone that stops reading (suspend, tunnel flap, dead radio) never
 * drains it. Left alone, every event the harness appends accumulates in the
 * plugin's heap; dropping the connection after one megabyte is the difference
 * between one client reconnecting and the whole harness running out of memory.
 */
const SSE_MAX_BUFFER_BYTES = 1024 * 1024
/** Route keys the request counter will create; anything else folds into one bucket. */
const TRACKED_ROUTES = new Set([
  'status', 'health', 'sessions', 'workspaces', 'dirs', 'events', 'images', 'files', 'models',
  'approvals', 'questions', 'permissions', 'file', 'delivery', 'preview', 'devices', 'pulse',
  'pair', 'pairing-code',
])
/** Distinct request-counter keys kept before the counter is reset. */
const MAX_TRACKED_ROUTE_KEYS = 64
/** Cap on a single decoded base64 photo. */
const MAX_IMAGE_BYTES = 12 * 1024 * 1024
/**
 * Cap on one uploaded file's bytes.
 *
 * The harness stores a generic file verbatim, with no type whitelist and no
 * size limit of its own, so this bound belongs to the phone: the bytes travel
 * in one piece and a client that could send a gigabyte would eventually be
 * asked to. It stays well under {@link MAX_BODY_BYTES} because the upload is
 * the whole body.
 */
const MAX_FILE_BYTES = 32 * 1024 * 1024
/** Files one message may carry, matching the photo cap. */
const MAX_FILES_PER_MESSAGE = 8
/** SSE keep-alive interval. */
const SSE_HEARTBEAT_MS = 15_000
/** Default history page size when the client does not ask. */
const DEFAULT_MAX_MESSAGES = 60
/**
 * Message ceiling a route may ask the controller for.
 *
 * Mirrors the bound the history route accepts, and bounds the scan the image
 * fallback performs when it looks for an attachment inside a session log.
 */
const MAX_HISTORY_MESSAGES = 400
/** How many models one catalog read enriches with context size and vision. */
const MAX_ENRICHED_MODELS = 60
/** Default time a pending approval waits for the phone before giving up. */
const DEFAULT_APPROVAL_WAIT_MS = 120_000
/** The only two outcomes a phone may choose. Everything else fails closed. */
const APPROVAL_OUTCOMES = new Set(['allowed-once', 'rejected'])
/**
 * How long a question waits for the phone.
 *
 * Matches the harness's own two-minute window: past it the model is told to
 * carry on, and a longer wait here would only hold a waterfall open for an
 * answer the harness has already stopped waiting for.
 */
const DEFAULT_QUESTION_WAIT_MS = 120_000
/** How many timed-out questions stay answerable through the harness's late channel. */
const MAX_EXPIRED_QUESTIONS = 20
/** How long a timed-out question keeps being offered before it is dropped. */
const MAX_EXPIRED_AGE_MS = 30 * 60 * 1000
/** Child directories one browse level may materialize, hidden rows included. */
const MAX_DIR_ENTRIES = 500

/** How long one chat-status read may take before its row is served without a dot. */
const STATUS_READ_TIMEOUT_MS = 3_000

/**
 * Status reads one list request may start.
 *
 * A session's status comes from its newest durable event, and one read costs a
 * few milliseconds — but eighty rows read in one request would make the list
 * answer at the speed of its own worst case. A bounded number per request means a
 * cold phone fills its dots in over the next polls instead of waiting, and a warm
 * one recomputes only the rows that actually changed.
 */
const STATUS_READS_PER_REQUEST = 24

/** How many chat statuses to remember; the oldest is dropped once this is exceeded. */
const STATUS_CACHE_LIMIT = 1024

/** How long one preview stays answerable without asking the fetcher again. */
const PREVIEW_CACHE_TTL_MS = 5 * 60 * 1000

/** How many previews to remember; the oldest is dropped once this is exceeded. */
const PREVIEW_CACHE_LIMIT = 16

/** Longest URL a preview accepts, matching the harness's own fetch limit. */
const PREVIEW_URL_LIMIT = 2048

/**
 * Characters of extracted text one preview serves.
 *
 * The fetcher already caps a body at its own configured limit; this is the
 * second cap, for the phone: a reader scrolls tens of thousands of characters
 * at most, and a payload sized for a desktop helps nobody on a metered link.
 */
const PREVIEW_TEXT_LIMIT = 30_000

/**
 * Bytes one file read pulls off the disk.
 *
 * A phone reads a source file, a note, a config — never a disk image. The cap is
 * what keeps a link that happens to point at a 4 GB file from turning into a 4 GB
 * allocation, and the byte past it is what tells "this is all of it" from "there
 * was more", so the answer can say which one it is instead of guessing.
 */
const FILE_READ_LIMIT = 512 * 1024

/** Characters of file text one answer serves, the second cap and the phone-sized one. */
const FILE_TEXT_LIMIT = 120_000

/** Longest file reference a route accepts; a path, not a document. */
const FILE_REFERENCE_LIMIT = 4096

/**
 * Bytes one *delivered* file is streamed to a phone for.
 *
 * A read is bounded by what a phone can display; a download is bounded by what a
 * phone should be handed at all. A deliverable is a document an agent produced —
 * an office file, a slide deck, a PDF — and none of those is hundreds of
 * megabytes, so the cap is well above any real one and still finite: the plugin
 * streams rather than buffers, but an unbounded route is an invitation to pull a
 * disk image over a metered connection by tapping the wrong row.
 */
const FILE_DOWNLOAD_LIMIT = 64 * 1024 * 1024

/** Upper bound on crumbs; far past any real depth, so a pathological path cannot blow up the payload. */
const MAX_CRUMBS = 64
/** The host platform's path flavor, sampled once: browse semantics must match Node's `path`. */
const WIN32 = process.platform === 'win32'
/** How long an enumerated drive list stays usable before it is read again. */
const VOLUME_CACHE_MS = 60_000
/** Last successful drive enumeration, so a browse level does not shell out every time. */
let volumeCache = null

const IMAGE_MEDIA_TYPES = new Set(['image/png', 'image/jpeg', 'image/webp', 'image/gif'])

/**
 * Resolve `$DSH_HOME`, falling back to the documented default location.
 * @returns the absolute DSH home directory.
 */
function dshHome() {
  const fromEnv = process.env.DSH_HOME
  if (typeof fromEnv === 'string' && fromEnv.length > 0) return fromEnv
  const profileDir = process.env.DSH_PROFILE_DIR
  if (typeof profileDir === 'string' && profileDir.length > 0) {
    const marker = `${join('', 'profiles')}`
    const at = profileDir.lastIndexOf(marker)
    if (at > 0) return profileDir.slice(0, at - 1)
  }
  // The home directory, not the working directory: a harness launched from
  // another folder (a shortcut, a different project, a service manager) must
  // still find the state file that holds every paired phone, and `cwd` is where
  // that assumption silently breaks.
  return join(homedir(), '.dsh')
}

/**
 * Derive the stable public id of one device from its token.
 *
 * The token itself is the credential and never appears in a list; the id is what
 * the phone shows and what a revoke names, and deriving it from the token means
 * no extra field to keep in sync and no id that can drift from the credential it
 * points at.
 *
 * @param token - the device's bearer token.
 * @returns a short hex id, stable for the lifetime of the token.
 */
function deviceIdFor(token) {
  return createHash('sha256').update(String(token)).digest('hex').slice(0, 12)
}

/**
 * Whether one socket address is the same machine.
 *
 * Pairing codes are minted from the PC by opening a loopback URL, so the route
 * that mints them must refuse anything arriving from the network. `::ffff:`
 * prefixed IPv4 addresses and `::1` both mean "this machine" on a dual-stack
 * socket, and `localhost` is never a remote interface.
 *
 * @param address - `req.socket.remoteAddress`.
 * @returns whether the peer is loopback.
 */
function isLoopback(address) {
  if (typeof address !== 'string') return false
  const value = address.toLowerCase()
  return value === '::1' || value === '127.0.0.1' || value.startsWith('127.') || value.startsWith('::ffff:127.')
}

/**
 * Whether one resolved path sits inside a directory.
 *
 * Comparison uses `path.relative`, which respects the platform's own separator
 * and drive rules, and a `..` result is the one answer that means "outside":
 * string prefixes would call `C:\dsh-evil` a child of `C:\dsh`.
 *
 * @param target - absolute path to test.
 * @param root - absolute directory that must contain it.
 * @returns whether target is root or below it.
 */
function pathIsInside(target, root) {
  if (typeof target !== 'string' || typeof root !== 'string' || target.length === 0 || root.length === 0) return false
  const steps = relative(root, target)
  if (steps.length === 0) return true
  return !steps.startsWith('..') && !isAbsolute(steps)
}

/**
 * Mint an opaque URL-safe token.
 * @param bytes - entropy bytes.
 * @returns base64url-encoded random string.
 */
function mintToken(bytes) {
  return randomBytes(bytes).toString('base64url')
}

/**
 * Constant-time string comparison that tolerates length mismatch.
 * @param left - candidate value.
 * @param right - expected value.
 * @returns whether both strings are equal.
 */
function sameSecret(left, right) {
  const a = Buffer.from(String(left), 'utf8')
  const b = Buffer.from(String(right), 'utf8')
  if (a.length !== b.length) return false
  return timingSafeEqual(a, b)
}

/**
 * Normalize one base64 payload, accepting both padded and unpadded input.
 * @param value - candidate base64 string.
 * @returns the canonical Buffer.
 * @throws when the value is not valid base64.
 */
function decodeBase64(value) {
  const text = String(value).replace(/\s+/g, '').replace(/-/g, '+').replace(/_/g, '/')
  const padded = text.length % 4 === 0 ? text : text + '='.repeat(4 - (text.length % 4))
  if (!/^[A-Za-z0-9+/]*={0,2}$/.test(padded)) throw new HttpError(400, 'invalid-base64', 'image data is not valid base64')
  return Buffer.from(padded, 'base64')
}

/**
 * Re-yield one request body, refusing anything past `limit`.
 *
 * Generic files travel as their own bytes and the harness's upload service
 * consumes a byte source, so nothing here has to hold the whole file: what this
 * adds is the one thing the harness deliberately does not have — a ceiling, so
 * a phone cannot fill the PC's disk one request at a time.
 *
 * A body with no declared length can only be stopped here, and stopping it means
 * throwing into the store's own consumption — which is why the refusal is also
 * recorded in [refused]: the store calls any failure of its byte source
 * "attachment could not be persisted", and that answer would hide a plain size
 * refusal behind a storage fault.
 *
 * @param source - the request body as an async byte source.
 * @param limit - maximum accepted byte count.
 * @param refused - a record the generator marks (`tooLarge`) when it stops early.
 * @yields the same chunks, in order, until the source ends.
 * @throws HttpError 413 `file-too-large` once the body passes the limit.
 */
export async function* boundedBody(source, limit, refused) {
  let total = 0
  for await (const chunk of source) {
    total += chunk.length
    if (total > limit) {
      refused.tooLarge = true
      throw new HttpError(413, 'file-too-large', `the upload exceeds ${limit} bytes`)
    }
    yield chunk
  }
}

/**
 * Every address the phone could dial for this listener.
 *
 * The bind address is all-interfaces, which is not something a person can
 * type into a phone. These are the concrete addresses instead, with the
 * Tailscale range called out because that is the one that works from outside
 * the house.
 *
 * @param port - the listening port.
 * @returns one `http://address:port` URL per reachable non-loopback IPv4 address.
 */
function reachableUrls(port) {
  const found = []
  for (const addresses of Object.values(networkInterfaces())) {
    for (const address of addresses ?? []) {
      if (address.family !== 'IPv4' || address.internal) continue
      // A CGNAT address in 100.64.0.0/10 is Tailscale's IPv4 range.
      const parts = address.address.split('.').map(Number)
      const tailscale = parts[0] === 100 && parts[1] >= 64 && parts[1] <= 127
      found.push({ url: `http://${address.address}:${port}`, address: address.address, tailscale })
    }
  }
  return found
}

/**
 * Whether one path names a fixed filesystem location regardless of process
 * state.
 *
 * On POSIX that is `path.isAbsolute`; on Windows only drive-qualified
 * (`C:\dir`) or complete UNC (`\\server\share\dir`) forms qualify. Rooted but
 * drive-less forms (`\dir`, `/dir`) and the UNC prefix alone pass
 * `path.isAbsolute` yet still resolve against the process's current drive,
 * which for a value arriving over the wire means "the host's idea of where you
 * are" — exactly the ambiguity a remote browser must not have.
 *
 * @param value - candidate path.
 * @returns whether the path is fully qualified.
 */
export function fullyQualified(value) {
  if (typeof value !== 'string' || value.length === 0) return false
  if (!WIN32) return isAbsolute(value)
  if (/^[A-Za-z]:([\\/]|$)/.test(value)) return true
  return /^\\\\[^\\/]+[\\/][^\\/]+/.test(value)
}

/**
 * Put one absolute path in the form this platform's filesystem calls it.
 *
 * A drive root arrives from a client in several spellings — `C:`, `C:\`,
 * `C:/` — and all three mean the same thing. Normalizing them here keeps that
 * knowledge in one place instead of spreading "what counts as a drive" across
 * every route.
 *
 * The caller must have established that the path is fully qualified: `resolve`
 * answers a relative path by attaching the process's working directory, which
 * is exactly the host-side guess a remote browse target must never receive.
 *
 * @param value - a path already known to be fully qualified.
 * @returns the normalized path.
 */
export function normalizePath(value) {
  if (WIN32) {
    const drive = /^([A-Za-z]):$/.exec(value)
    if (drive !== null) return `${drive[1].toUpperCase()}:\\`
  }
  return WIN32 ? resolve(value).replace(/\//g, '\\') : resolve(value)
}

/**
 * The drive roots this host exposes, as the directory rows that represent them.
 *
 * A browser opened from another machine has no way to learn that `K:` exists,
 * and a person typing a path from a phone will not remember it. Enumerating
 * the volumes is what a Windows file dialog shows in its sidebar, and it is
 * the only honest way to let a remote client start from the top.
 *
 * @returns one row per fixed volume, in drive-letter order, or an empty list
 *   on a host without drive letters.
 */
async function volumeRoots() {
  if (!WIN32) return []
  if (volumeCache !== null && Date.now() - volumeCache.at < VOLUME_CACHE_MS) return volumeCache.rows
  const script = `Get-CimInstance Win32_LogicalDisk | Where-Object { $_.DriveType -eq 3 } | ForEach-Object { "$($_.DeviceID) $($_.DriveType)" }`
  const roots = []
  try {
    // Static analysis argument, not a command line: everything is quoted here,
    // and the comment-aware flag stops the stop-parsing token `--%` from being
    // honoured by anything that re-reads this text.
    const child = spawn('powershell.exe', ['-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-Command', script], {
      windowsHide: true,
      stdio: ['ignore', 'pipe', 'ignore'],
    })
    const stdout = await new Promise((resolvePromise) => {
      let text = ''
      child.stdout.on('data', (chunk) => {
        text += chunk.toString('utf8')
      })
      child.once('error', () => resolvePromise(''))
      child.once('close', () => resolvePromise(text))
      setTimeout(() => {
        child.kill()
        resolvePromise(text)
      }, 2500).unref?.()
    })
    for (const line of stdout.split(/\r?\n/)) {
      const token = line.trim().split(/\s+/)[0]
      if (/^[A-Za-z]:$/.test(token)) roots.push(`${token.toUpperCase()}\\`)
    }
  } catch {
    return []
  }
  roots.sort((left, right) => left.localeCompare(right))
  // The row shows `C:` while its path stays `C:\`: a name is a label, and a
  // trailing separator in one reads as a typo rather than a root.
  const rows = roots.map((path) => ({ name: path.replace(/\\$/, ''), path, hidden: false, drive: true }))
  volumeCache = { at: Date.now(), rows }
  return rows
}

/**
 * Compare paths the way this platform's filesystem does.
 *
 * Windows folds case and treats both separators alike; a path that differs
 * from a workspace's canonical one only by a capital letter or a slash is the
 * same directory. POSIX is compared verbatim.
 *
 * @param value - path to normalize.
 * @returns the comparison key.
 */
export function pathKey(value) {
  if (typeof value !== 'string') return ''
  let key = value.trim().replace(/[\\/]+$/, '')
  if (key.length === 0) key = value.trim()
  return WIN32 ? key.toLowerCase() : key
}

/**
 * Ancestor chain from the filesystem root down to `target`, inclusive.
 *
 * Built from the platform's own parser rather than by chopping strings, so a
 * Windows drive root (`C:\`) and a POSIX root (`/`) both come out as single
 * crumbs that are themselves jump targets.
 *
 * @param target - a resolved, fully qualified directory path.
 * @returns one crumb per level, root first.
 */
export function crumbsOf(target) {
  const flavor = WIN32 ? win32 : { parse: parsePath, sep }
  const root = flavor.parse(target).root
  const trail = target.slice(root.length).split(/[\\/]+/).filter((part) => part.length > 0)
  const crumbs = [{ name: root, path: root, hidden: false }]
  let current = root
  for (const part of trail) {
    current = current.endsWith(flavor.sep) ? `${current}${part}` : `${current}${flavor.sep}${part}`
    crumbs.push({ name: part, path: current, hidden: false })
  }
  return crumbs.length > MAX_CRUMBS ? crumbs.slice(crumbs.length - MAX_CRUMBS) : crumbs
}

/**
 * Resolve a requested browse target, or explain why it cannot be one.
 * @param requested - an absolute path, or undefined for the host account's home.
 * @returns the resolved absolute directory path.
 * @throws HttpError 400 `invalid-request` when the value is not fully qualified.
 */
export function resolveBrowseTarget(requested) {
  if (requested === undefined || requested.trim().length === 0) return normalizePath(homedir())
  const raw = requested.trim()
  // Drive-relative forms (`C:progetti`) are the one Windows spelling that
  // resolves against per-process state, so they are refused before
  // normalization can launder them into an absolute path.
  if (WIN32 && /^[A-Za-z]:[^\\/]/.test(raw)) {
    throw new HttpError(400, 'invalid-request', `"${raw}" is drive-relative, not a fully qualified path`)
  }
  if (!fullyQualified(raw)) {
    throw new HttpError(400, 'invalid-request', `"${raw}" is not a fully qualified path; a remote browser must send one it read from the host`)
  }
  return normalizePath(raw)
}

/**
 * List one directory level: its ancestry, its child directories, and whether
 * the level was cut at the entry bound.
 *
 * The scan streams the directory through a bounded, name-ordered window, so a
 * level with a hundred thousand children costs the same memory as a small one;
 * `truncated` reports the cut rather than silently hiding rows.
 *
 * @param requested - absolute directory to list, or undefined for the host account's home.
 * @returns the listing, shaped exactly like the harness's own browse backend.
 * @throws HttpError 400 `invalid-request` for a path this host cannot address.
 * @throws HttpError 404 `directory-unreadable` when the target is not a readable directory.
 */
export async function listDirectory(requested) {
  const home = normalizePath(homedir())
  const target = resolveBrowseTarget(requested)
  const listing = await listLevel(target, home)
  // The drive roots ride on every level as leading crumbs, so a client can jump
  // to another volume in one tap from anywhere instead of walking up to the top.
  //
  // Exactly one of them is left out: the volume the level is already standing
  // on, whose crumb the ancestry chain supplies anyway (as its root). Keeping it
  // would print the same `K:` twice on one row, and dropping the whole shortcut
  // block on a drive root — as an earlier version did — took away the *other*
  // drives at the one level where they are the only way out: a volume root has
  // no parent to step up to, so that level had a dead "up" and no way to another
  // disk, which reads as a stuck picker rather than as the top of a tree.
  const drives = await volumeRoots()
  const root = listing.crumbs[0]?.path ?? listing.path
  const shortcuts = drives.filter((drive) => pathKey(drive.path) !== pathKey(root))
  if (shortcuts.length > 0) {
    listing.crumbs = shortcuts.concat(listing.crumbs)
  }
  return listing
}

/**
 * List one level without the drive shortcuts.
 * @param target - a normalized absolute directory path.
 * @param home - the host account's home, for the breadcrumb "Home" root.
 * @returns the listing for exactly that level.
 * @throws HttpError 404 `directory-unreadable` when the target cannot be opened.
 */
async function listLevel(target, home) {
  const keep = MAX_DIR_ENTRIES + 1
  const window = []
  let evicted = false
  let level
  try {
    level = await opendir(target)
  } catch (error) {
    // A drive that holds no media, or a volume the account cannot read, is not
    // an error: it is an empty place, and the phone shows it as one.
    if (WIN32 && /^[A-Za-z]:\\$/.test(target)) {
      return { path: target, home, crumbs: crumbsOf(target), entries: [], truncated: false }
    }
    throw new HttpError(404, 'directory-unreadable', `cannot list ${target}: ${messageOf(error)}`)
  }
  try {
    for (;;) {
      const entry = await level.read()
      if (entry === null) break
      if (!entry.isDirectory() && !entry.isSymbolicLink()) continue
      if (boundedInsert(window, entry.name, entry.isDirectory(), entry.isSymbolicLink(), keep)) evicted = true
    }
  } catch (error) {
    throw new HttpError(404, 'directory-unreadable', `cannot list ${target}: ${messageOf(error)}`)
  } finally {
    await level.close().catch(() => {})
  }

  const entries = []
  let truncated = evicted
  for (const candidate of window) {
    const row = await directoryRow(target, candidate)
    if (row === null) continue
    if (entries.length === MAX_DIR_ENTRIES) {
      truncated = true
      break
    }
    entries.push(row)
  }
  return { path: target, home, crumbs: crumbsOf(target), entries, truncated }
}

/**
 * Create one child directory under an existing parent.
 * @param parent - absolute existing parent directory.
 * @param name - a single path segment.
 * @returns the created directory's absolute path.
 * @throws HttpError 400 `invalid-request` for a relative parent or a name that is not one segment.
 * @throws HttpError 409 `directory-exists` when the child is already there.
 * @throws HttpError 409 `directory-create-failed` when creation fails.
 */
export async function createDirectoryUnder(parent, name) {
  const parentPath = resolveBrowseTarget(parent)
  const child = typeof name === 'string' ? name.trim() : ''
  if (child.length === 0 || child === '.' || child === '..' || /[/\\]/.test(child)) {
    throw new HttpError(400, 'invalid-request', `"${name ?? ''}" is not a single directory name`)
  }
  const target = join(parentPath, child)
  try {
    await mkdir(target)
  } catch (error) {
    if (error?.code === 'EEXIST') throw new HttpError(409, 'directory-exists', `${target} already exists`)
    throw new HttpError(409, 'directory-create-failed', `cannot create ${target}: ${messageOf(error)}`)
  }
  return target
}

/**
 * The filesystem path behind a `file:` URL, or null when the value is not one.
 *
 * An agent that links a local file writes it the way its own tools spell it —
 * sometimes as a path, sometimes as a `file:` URL, because that is how a browser
 * would be handed one. All three spellings of a drive (`file:///C:/x`,
 * `file://C:/x`, `file:/C:/x`) mean the same file, and a `file:` URL naming a
 * host (`file://server/share/x`) is a UNC path.
 *
 * @param value - the reference as the client sent it.
 * @returns an absolute path in this platform's flavour, or null when it is not a file URL.
 * @throws HttpError 400 when it is a file URL this host cannot turn into a path.
 */
export function fileUrlPath(value) {
  if (!/^file:/i.test(value)) return null
  let parsed
  try {
    parsed = new URL(value)
  } catch {
    throw new HttpError(400, 'file/invalid', `not a usable file URL: ${value}`)
  }
  let pathname
  try {
    pathname = decodeURIComponent(parsed.pathname)
  } catch {
    throw new HttpError(400, 'file/invalid', `not a usable file URL: ${value}`)
  }
  if (parsed.host.length > 0 && parsed.host.toLowerCase() !== 'localhost') {
    // A URL path always uses slashes, and Windows is the only platform where a
    // `file:` URL can name another machine, so the UNC spelling is built here.
    return `\\\\${parsed.host}${pathname.replace(/\//g, '\\')}`
  }
  // A drive is a "host" in URL terms only when it is left in the authority, so
  // the common Windows spelling arrives as a leading slash before the letter.
  return /^\/[A-Za-z]:([\\/]|$)/.test(pathname) ? pathname.slice(1) : pathname
}

/**
 * Split one reference into the path it names and the fragment after it.
 *
 * The harness's own file links carry a line: `[riga 35](LORE-bozza.md#L35)` is
 * how an agent points at a spot in a file it just read, and the desktop opens it
 * there. The fragment is therefore not part of the name — treating it as one
 * answers `file/not-found` for a file that is right there, which is the same
 * *"opening a linked file doesn't work"* the classifier exists to remove, one
 * layer down. A `file:` URL already separates the two, so it is read through the
 * URL parser; a plain path is cut at the first `#`, which is the harness's own
 * convention and not a character a Windows filename contains on purpose.
 *
 * @param reference - the reference as the client sent it.
 * @returns the path part and the fragment without its `#`.
 * @throws HttpError 400 when it is a `file:` URL this host cannot turn into a path.
 */
export function splitFileFragment(reference) {
  const value = typeof reference === 'string' ? reference.trim() : ''
  if (/^file:/i.test(value)) {
    return { path: fileUrlPath(value), fragment: new URL(value).hash.replace(/^#/, '') }
  }
  const at = value.indexOf('#')
  if (at < 0) return { path: value, fragment: '' }
  return { path: value.slice(0, at), fragment: value.slice(at + 1) }
}

/**
 * The line one fragment names, or null when it names none.
 *
 * `#L35` is the harness's spelling and `#35` is the same intent; anything else —
 * an anchor, a word — is a fragment this route cannot honour, and answering
 * `null` is how it says so instead of guessing a line.
 *
 * @param fragment - the fragment text, without its `#`.
 * @returns the 1-based line, or null.
 */
export function fragmentLine(fragment) {
  const match = /^L?(\d{1,7})$/i.exec(typeof fragment === 'string' ? fragment.trim() : '')
  return match === null ? null : Number(match[1])
}

/**
 * Resolve one file reference to an absolute host path.
 *
 * The reference is whatever an agent printed, so it comes in three shapes and
 * only one of them is self-sufficient:
 *
 * - a `file:` URL, which is unwrapped first;
 * - a fully qualified path, taken as it stands;
 * - a relative path or a bare file name, which means the file *next to the chat
 *   that printed it* — the agent wrote it while working inside that folder. The
 *   host account's own working directory is the one base that must never be used
 *   for it: it is the process's accident, and it would read a different file with
 *   the same name without saying so.
 *
 * A trailing fragment is stripped before any of that, because it names a place
 * *inside* the file rather than another file — see [splitFileFragment].
 *
 * @param reference - the reference as the client sent it.
 * @param cwd - the addressed chat's folder, or null when the host has none.
 * @returns the absolute path to read.
 * @throws HttpError 400 `file/invalid` for an empty or drive-relative reference.
 * @throws HttpError 409 `file/no-session` for a relative reference with no folder to resolve against.
 */
export function resolveFileReference(reference, cwd) {
  const raw = splitFileFragment(reference).path
  if (raw.length === 0) throw new HttpError(400, 'file/invalid', 'the ref parameter is required')
  if (raw.length > FILE_REFERENCE_LIMIT) {
    throw new HttpError(400, 'file/invalid', `the reference exceeds the maximum of ${FILE_REFERENCE_LIMIT} characters`)
  }
  const unwrapped = fileUrlPath(raw) ?? raw
  if (fullyQualified(unwrapped)) return normalizePath(unwrapped)
  if (WIN32 && /^[A-Za-z]:/.test(unwrapped)) {
    throw new HttpError(400, 'file/invalid', `"${unwrapped}" is drive-relative, not a fully qualified path`)
  }
  const base = typeof cwd === 'string' && fullyQualified(cwd) ? normalizePath(cwd) : null
  if (base === null) {
    throw new HttpError(409, 'file/no-session', `"${unwrapped}" is relative and this harness holds no folder for the chat that printed it`)
  }
  // A `../` step out of the chat's folder is allowed rather than fenced: the same
  // device can already browse every level of this disk through `/mobile/dirs`,
  // so a fence here would hide a file the phone can otherwise see while making
  // an agent's `../README.md` unopenable.
  return normalizePath(resolve(base, unwrapped))
}

/**
 * The status a chat row shows, one word each.
 *
 * Deliberately not a colour: the host says what happened, the phone decides how
 * to draw it, and a wire that carried `#ff0000` would put the palette in a plugin.
 * Five states rather than three, because "not running" is not one thing — a turn
 * the user stopped and a turn that failed are different news, and painting them
 * the same colour is the kind of lie a status dot is worst at.
 */
export const CHAT_STATUSES = Object.freeze(['running', 'completed', 'failed', 'stopped', 'idle'])

/**
 * Map one `turn/end` reason to a status.
 *
 * The vocabulary is the harness's own (`TurnEndReasonMap`): `completed` finished
 * the turn, `error` / `blocked` / `max-tokens` are the host reporting that it did
 * not, and `aborted` / `interrupted` / `forked` mean it was stopped — by the user,
 * by a shutdown, or by the session being forked — which is not a failure of the
 * chat and must not be painted as one.
 *
 * The reason arrives in two shapes, because the durable log predates the current
 * one: `{ kind: 'completed' }` today, a bare `'completed'` in older records. Both
 * are read; anything else — including a kind this build has never heard of —
 * answers `idle`, which is the one status that claims nothing.
 *
 * @param reason - the `reason` of one `turn/end` event.
 * @returns one of [CHAT_STATUSES].
 */
export function statusFromTurnEnd(reason) {
  const kind = typeof reason === 'string' ? reason : typeof reason?.kind === 'string' ? reason.kind : ''
  switch (kind) {
    case 'completed':
      return 'completed'
    case 'aborted':
    case 'interrupted':
    case 'forked':
      return 'stopped'
    case 'error':
    case 'blocked':
    case 'max-tokens':
      return 'failed'
    default:
      return 'idle'
  }
}

/** Event types that belong *inside* a turn: a log ending on one of these stopped mid-turn. */
const MID_TURN_EVENTS = new Set([
  'turn/start',
  'step/start',
  'step/end',
  'tool/call',
  'tool/result',
  'assistant/message',
  'developer/message',
  'reasoning',
])

/**
 * One chat's status, from its newest durable event and the host's own flag.
 *
 * The running flag is the host speaking about *now* and wins over anything the log
 * says. Otherwise the last turn's ending decides — and a log whose newest event is
 * still inside a turn, on a chat nobody is running, is a turn that never reported
 * an end: the host was closed or killed while it worked. That is `failed` rather
 * than `stopped`, because nothing chose to stop it.
 *
 * @param lastEvent - the newest durable event of the session, or undefined.
 * @param running - the host's `running` flag for that session.
 * @returns one of [CHAT_STATUSES].
 */
export function chatStatusFor(lastEvent, running) {
  if (running === true) return 'running'
  if (lastEvent === undefined || lastEvent === null) return 'idle'
  if (lastEvent.type === 'turn/end') return statusFromTurnEnd(lastEvent.data?.reason)
  return MID_TURN_EVENTS.has(lastEvent.type) ? 'failed' : 'idle'
}

/**
 * Read one file as text for a phone.
 *
 * The cap is applied on the way in, not after: a file larger than the limit is
 * read up to it, and `truncated` says the answer is a window rather than the
 * whole thing. A byte of NUL anywhere in what was read is what makes a file
 * binary here — the same test the usual text tools use, and the only one that
 * does not need a media-type table this plugin has no business owning.
 *
 * @param target - an absolute path this host resolved itself.
 * @returns the wire payload: path, name, size, text, and whether it was cut.
 * @throws HttpError 404 `file/not-found` when there is nothing there.
 * @throws HttpError 400 `file/not-a-file` for a directory or a device.
 * @throws HttpError 415 `file/binary` when the content is not text.
 */
export async function readTextFile(target) {
  let info
  try {
    info = await stat(target)
  } catch (error) {
    throw new HttpError(404, 'file/not-found', `cannot read ${target}: ${messageOf(error)}`)
  }
  if (info.isDirectory()) throw new HttpError(400, 'file/not-a-file', `${target} is a directory, not a file`)
  if (!info.isFile()) throw new HttpError(400, 'file/not-a-file', `${target} is not a regular file`)
  const take = Math.min(info.size, FILE_READ_LIMIT)
  const handle = await open(target, 'r')
  let read
  try {
    const buffer = Buffer.allocUnsafe(take)
    const result = await handle.read(buffer, 0, take, 0)
    read = buffer.subarray(0, result.bytesRead)
  } catch (error) {
    throw new HttpError(404, 'file/not-found', `cannot read ${target}: ${messageOf(error)}`)
  } finally {
    await handle.close().catch(() => {})
  }
  if (read.includes(0)) {
    throw new HttpError(415, 'file/binary', `${baseNameOf(target)} is not text (${info.size} bytes)`)
  }
  let text = read.toString('utf8')
  let truncated = info.size > read.length
  if (text.length > FILE_TEXT_LIMIT) {
    text = text.slice(0, FILE_TEXT_LIMIT)
    truncated = true
  }
  return { path: target, name: baseNameOf(target), bytes: info.size, text, truncated }
}

/**
 * Describe one delivered file for a phone: its text when it is text, and the
 * fact that it is not when it is not.
 *
 * The text half is [readTextFile] itself, so the read cap, the truncation flag
 * and the NUL-byte test are the same ones the linked-file route already answers
 * with — two implementations of "is this text?" would eventually disagree about
 * one file, and the phone would show a preview of one of them.
 *
 * The binary half is that same refusal read as an *answer* instead of as a
 * failure. A delivered PDF, spreadsheet or slide deck is not an error: it is a
 * file the phone downloads rather than reads, and answering `file/binary` here
 * would put "cannot read this" on a card whose whole point is that the file is
 * there and is fine.
 *
 * @param target - an absolute path this host resolved itself.
 * @returns the wire payload: path, name, size, text, truncated, binary.
 * @throws HttpError 404 `file/not-found` when there is nothing there.
 * @throws HttpError 400 `file/not-a-file` for a directory or a device.
 */
export async function readDeliveryFile(target) {
  try {
    return { ...(await readTextFile(target)), binary: false }
  } catch (error) {
    if (!(error instanceof HttpError) || error.code !== 'file/binary') throw error
  }
  let info
  try {
    info = await stat(target)
  } catch (error) {
    throw new HttpError(404, 'file/not-found', `cannot read ${target}: ${messageOf(error)}`)
  }
  return { path: target, name: baseNameOf(target), bytes: info.size, text: '', truncated: false, binary: true }
}

/**
 * One file name reduced to what a plain `filename=` parameter can carry.
 *
 * The header carries the real name twice — this ASCII form and an RFC 5987
 * `filename*` — because the two halves of the ecosystem disagree: an accented
 * name is mangled by a client that reads only the plain parameter, and dropped
 * entirely by one that reads neither.
 *
 * @param name - the file's own name.
 * @returns the name with everything outside printable ASCII replaced.
 */
function asciiFileName(name) {
  return name.replace(/[^\x20-\x7e]/g, '_').replace(/["\\]/g, '_')
}

/**
 * Place one sorted candidate into a bounded window, name-ascending.
 * @param window - the window, mutated in place.
 * @param name - child name.
 * @param isDirectory - the dirent's own claim.
 * @param isSymbolicLink - whether the entry is a link that must be probed.
 * @param keep - window bound.
 * @returns true when the window evicted a row, i.e. the level has more than it holds.
 */
function boundedInsert(window, name, isDirectory, isSymbolicLink, keep) {
  if (window.length === keep && name.localeCompare(window[window.length - 1].name) >= 0) return true
  let low = 0
  let high = window.length
  while (low < high) {
    const mid = (low + high) >>> 1
    if (name.localeCompare(window[mid].name) < 0) high = mid
    else low = mid + 1
  }
  window.splice(low, 0, { name, isDirectory, isSymbolicLink })
  if (window.length <= keep) return false
  window.pop()
  return true
}

/**
 * Render one candidate as a row, following links to directories and skipping
 * anything that cannot be entered (a broken or cyclic link is not a place).
 * @param parent - the listed directory.
 * @param candidate - one window row.
 * @returns the browse row, or null when the target is not a directory.
 */
async function directoryRow(parent, candidate) {
  const path = join(parent, candidate.name)
  let enterable = candidate.isDirectory
  if (!enterable && candidate.isSymbolicLink) {
    try {
      enterable = (await stat(path)).isDirectory()
    } catch {
      return null
    }
  }
  if (!enterable) return null
  return { name: candidate.name, path, hidden: candidate.name.startsWith('.') }
}

/**
 * The message text of an unknown thrown value.
 * @param error - value caught from a filesystem call.
 * @returns its message.
 */
function messageOf(error) {
  return error instanceof Error ? error.message : String(error)
}

/** One HTTP failure carrying the status and a machine-routable code. */
class HttpError extends Error {
  /**
   * @param status - HTTP status to answer.
   * @param code - stable machine-readable code.
   * @param message - human-readable detail.
   */
  constructor(status, code, message) {
    super(message)
    this.status = status
    this.code = code
  }
}

/**
 * Map an arbitrary thrown value to an HTTP failure, preserving Remote codes.
 * @param error - value caught from a harness call.
 * @param debug - when true, forward the original code as detail for diagnosis.
 * @returns status, code, and message to answer with.
 */
function toHttpFailure(error, debug = false) {
  if (error instanceof HttpError) return { status: error.status, code: error.code, message: error.message }
  const rawCode = typeof error?.code === 'string' ? error.code : 'internal'
  // A diagnostic build prefixes the code so a failure is visible in the client;
  // the classification below still reads the underlying harness code.
  const debugPrefix = rawCode.startsWith('debug/') ? 'debug/' : ''
  const code = debugPrefix === '' ? rawCode : rawCode.slice(debugPrefix.length)
  const message = error instanceof Error ? error.message : String(error)
  if (debug) {
    return { status: 500, code: `${debugPrefix}${code}`, message: `${error?.name ?? 'Error'}: ${message}` }
  }
  if (code === 'session/not-found' || code === 'subagent/not-found' || code === 'workspace/not-found') {
    return { status: 404, code, message }
  }
  if (code === 'workspace/name-conflict' || code === 'workspace/move-invalid') {
    return { status: 409, code, message }
  }
  if (code === 'gateway/bad-request' || code === 'session/attachment-invalid' || code === 'session/invalid-time-zone') {
    return { status: 400, code, message }
  }
  if (code === 'directory-unreadable') return { status: 404, code, message }
  if (code === 'directory-exists' || code === 'directory-create-failed' || code === 'workspace/invalid-path') {
    return { status: 409, code, message }
  }
  if (code === 'agent-preset/not-found' || code === 'agent-preset/locked' || code === 'agent-preset/conflict') {
    return { status: 400, code, message }
  }
  if (code.startsWith('session/') && code.endsWith('unavailable')) return { status: 409, code, message }
  if (code === 'session/agent-busy' || code === 'session/writer-held' || code === 'session/conflict') {
    return { status: 409, code, message }
  }
  return { status: 500, code, message }
}

/**
 * Reduce one harness content block to the phone wire shape.
 * @param block - a message content block from the session log.
 * @returns the compact wire block, or undefined when unsupported.
 */
function wireContentBlock(block) {
  if (block === null || typeof block !== 'object') return undefined
  switch (block.type) {
    case 'text':
      return { type: 'text', text: typeof block.text === 'string' ? block.text : '' }
    case 'reasoning':
      return { type: 'reasoning', text: typeof block.text === 'string' ? block.text : '' }
    case 'image': {
      const attachment = block.attachment
      if (attachment === null || typeof attachment !== 'object') return { type: 'image' }
      return {
        type: 'image',
        attachmentId: typeof attachment.attachmentId === 'string' ? attachment.attachmentId : undefined,
        mediaType: typeof attachment.mediaType === 'string' ? attachment.mediaType : undefined,
        width: typeof attachment.width === 'number' ? attachment.width : undefined,
        height: typeof attachment.height === 'number' ? attachment.height : undefined,
        name: typeof attachment.name === 'string' ? attachment.name : undefined,
      }
    }
    case 'file': {
      const attachment = block.attachment
      if (attachment === null || typeof attachment !== 'object') return { type: 'file' }
      return {
        type: 'file',
        attachmentId: typeof attachment.attachmentId === 'string' ? attachment.attachmentId : undefined,
        name: typeof attachment.name === 'string' ? attachment.name : undefined,
        bytes: typeof attachment.bytes === 'number' ? attachment.bytes : undefined,
      }
    }
    case 'tool-call':
      return {
        type: 'tool-call',
        name: typeof block.name === 'string' ? block.name : undefined,
        arguments: typeof block.arguments === 'string' ? block.arguments : undefined,
      }
    default:
      return undefined
  }
}

/**
 * Reduce a message's content to wire blocks.
 * @param content - message content array.
 * @returns compact wire blocks.
 */
function wireContent(content) {
  if (!Array.isArray(content)) return []
  const out = []
  for (const block of content) {
    const wired = wireContentBlock(block)
    if (wired !== undefined) out.push(wired)
  }
  return out
}

/**
 * Reduce the harness's question items to the shape a phone renders.
 *
 * The fields are copied rather than forwarded wholesale so the phone sees a
 * stable vocabulary: what arrives is a model-written structure, and a client
 * should not have to defend itself against whatever keys a model invented.
 *
 * @param items - the request's `questions` array, or anything else.
 * @returns one entry per renderable question.
 */
function wireQuestions(items) {
  if (!Array.isArray(items)) return []
  const out = []
  for (const item of items) {
    if (item === null || typeof item !== 'object') continue
    const text = typeof item.question === 'string' ? item.question.trim() : ''
    if (text.length === 0) continue
    const options = []
    if (Array.isArray(item.options)) {
      for (const option of item.options) {
        if (option === null || typeof option !== 'object') continue
        const label = typeof option.label === 'string' ? option.label.trim() : ''
        if (label.length === 0) continue
        options.push({
          label,
          description: typeof option.description === 'string' ? option.description : null,
        })
      }
    }
    out.push({
      // A question without an id cannot be answered — the reply is keyed by it —
      // so the position stands in rather than dropping a question the reader can
      // still see.
      id: typeof item.id === 'string' && item.id.length > 0 ? item.id : `q${out.length}`,
      header: typeof item.header === 'string' && item.header.length > 0 ? item.header : null,
      question: text,
      detail: typeof item.detail === 'string' ? item.detail : null,
      // The tool schema writes `multi_select`; the harness type writes
      // `multiSelect`. Both spellings are accepted because both are in the wild.
      multiSelect: item.multi_select === true || item.multiSelect === true,
      options,
    })
  }
  return out
}

/**
 * Reduce the host's permission catalog to the shape a phone renders.
 *
 * @param service - the permission-preset service, used to resolve the knobs.
 * @param catalog - the value of `catalog()`.
 * @returns one entry per selectable preset.
 */
function wirePresetOptions(service, catalog) {
  const options = Array.isArray(catalog?.options) ? catalog.options : []
  const out = []
  for (const option of options) {
    const value = typeof option?.value === 'string' ? option.value : ''
    if (value.length === 0) continue
    // `custom` is a derived "matches no preset" state rather than a selectable
    // one, and resolving it throws; so does a preset whose Auto registration was
    // withdrawn. Either way the knobs are simply unknown, which the app shows as
    // an absence instead of a wrong value.
    let spec = null
    try {
      spec = service.resolve(value)
    } catch {
      spec = null
    }
    out.push({
      value,
      name: typeof option?.name === 'string' && option.name.length > 0 ? option.name : value,
      description: typeof option?.description === 'string' ? option.description : null,
      sandbox: typeof spec?.sandbox === 'string' ? spec.sandbox : null,
      approval: typeof spec?.approval === 'string' ? spec.approval : null,
    })
  }
  return out
}

/**
 * Validate one client answer set against the vocabulary the harness expects.
 *
 * @param value - the request body's `answers`.
 * @returns the answers, or null when the shape is not one the harness accepts.
 */
function validAnswers(value) {
  if (!Array.isArray(value) || value.length === 0) return null
  const out = []
  for (const answer of value) {
    if (answer === null || typeof answer !== 'object') return null
    if (typeof answer.id !== 'string' || answer.id.length === 0) return null
    if (!Array.isArray(answer.selected)) return null
    const selected = answer.selected.filter((label) => typeof label === 'string')
    if (selected.length !== answer.selected.length) return null
    const custom = typeof answer.custom === 'string' && answer.custom.length > 0 ? answer.custom : undefined
    out.push({ id: answer.id, selected, ...(custom === undefined ? {} : { custom }) })
  }
  return out
}

/**
 * Canonicalize the URL one preview was asked for.
 *
 * The harness's own fetcher revalidates everything that matters about a
 * destination — scheme, credentials, network locality — so this only has to
 * refuse what has no business reaching it at all and return one canonical
 * spelling, which is also the cache key: two spellings of one page should not
 * be two fetches.
 *
 * @param input - the raw `url` query parameter.
 * @returns the canonical URL string.
 */
function normalizePreviewUrl(input) {
  const value = typeof input === 'string' ? input.trim() : ''
  if (value.length === 0) throw new HttpError(400, 'preview/invalid', 'the url parameter is required')
  if (value.length > PREVIEW_URL_LIMIT) {
    throw new HttpError(400, 'preview/invalid', `the URL exceeds the maximum of ${PREVIEW_URL_LIMIT} characters`)
  }
  let parsed
  try {
    parsed = new URL(value)
  } catch {
    throw new HttpError(400, 'preview/invalid', `not a valid URL: ${value}`)
  }
  if (parsed.protocol !== 'http:' && parsed.protocol !== 'https:') {
    throw new HttpError(400, 'preview/invalid', `only http and https can be previewed, not "${parsed.protocol}"`)
  }
  return parsed.toString()
}

/**
 * Translate one harness fetch failure into this route's own status and code.
 *
 * The fetcher's codes are precise and worth keeping — a blocked address is not a
 * timeout and neither is an unsupported type — but they are the fetcher's
 * vocabulary. This owns the mapping so the phone can speak its own.
 *
 * @param error - what `ctx.web.fetch` threw.
 * @returns the HTTP failure to raise instead.
 */
function previewHttpError(error) {
  const code = typeof error?.code === 'string' ? error.code : ''
  if (code === 'WEB_INVALID_URL') return new HttpError(400, 'preview/invalid', messageOf(error))
  if (code === 'WEB_BLOCKED_URL' || code === 'WEB_REDIRECT_BLOCKED') {
    return new HttpError(403, 'preview/blocked', messageOf(error))
  }
  if (code === 'WEB_FETCH_TIMEOUT') return new HttpError(504, 'preview/timeout', messageOf(error))
  if (code === 'WEB_UNSUPPORTED_CONTENT_TYPE') return new HttpError(415, 'preview/unsupported', messageOf(error))
  if (code === 'WEB_FETCH_TOO_LARGE') return new HttpError(413, 'preview/too-large', messageOf(error))
  if (code.startsWith('WEB_PROVIDER')) return new HttpError(503, 'preview/unavailable', messageOf(error))
  return new HttpError(502, 'preview/failed', messageOf(error))
}

/** Named entities worth decoding in a reader; everything else stays literal. */
const HTML_ENTITIES = {
  amp: '&', lt: '<', gt: '>', quot: '"', apos: "'", nbsp: ' ',
  hellip: '…', mdash: '—', ndash: '–', rsquo: '\u2019', lsquo: '\u2018',
  rdquo: '\u201D', ldquo: '\u201C', laquo: '«', raquo: '»',
  copy: '©', reg: '®', trade: '™', deg: '°', middot: '·', bull: '•', times: '×',
  agrave: 'à', eacute: 'é', egrave: 'è', igrave: 'ì', ograve: 'ò', ugrave: 'ù',
}

/**
 * Decode HTML character references, `&amp;` last so an escaped entity never
 * decodes twice.
 *
 * @param text - HTML text content.
 * @returns the decoded text.
 */
function decodeHtmlEntities(text) {
  return text
    .replace(/&#x([0-9a-f]+);/gi, (_, hex) => codePointText(parseInt(hex, 16)))
    .replace(/&#(\d+);/g, (_, dec) => codePointText(Number(dec)))
    .replace(/&([a-z][a-z0-9]*);/gi, (whole, name) => {
      const key = name.toLowerCase()
      if (key === 'amp') return whole
      return HTML_ENTITIES[key] ?? whole
    })
    .replace(/&amp;/gi, '&')
}

/** One code point as text, or nothing for a value that is not a character. */
function codePointText(code) {
  if (!Number.isInteger(code) || code <= 0 || code > 0x10ffff) return ''
  try {
    return String.fromCodePoint(code)
  } catch {
    return ''
  }
}

/**
 * Pull a readable page out of one HTML document.
 *
 * This is deliberately not a browser. The harness's sidebar browser renders the
 * page because it runs where the page's scripts are worth sandboxing; a phone
 * reading a link the agent cited needs the title and the words, so everything
 * that is not words — scripts, styles, chrome, markup — is dropped rather than
 * sandboxed.
 *
 * @param html - the fetched document.
 * @param limit - the most characters of text to return.
 * @returns the page's title, meta description, and readable text.
 */
function readablePage(html, limit) {
  const rawTitle = /<title\b[^>]*>([\s\S]*?)<\/title>/i.exec(html)?.[1] ?? ''
  const title = decodeHtmlEntities(rawTitle.replace(/<[^>]+>/g, ' ')).replace(/\s+/g, ' ').trim().slice(0, 300)
  const description = metaDescriptionOf(html)
  // Prefer the region the page calls its content: an article or main block is
  // the words; the whole body is the fallback; a fragment with neither is read
  // as it stands.
  let scope = html
  for (const tag of ['article', 'main', 'body']) {
    const region = new RegExp(`<${tag}\\b[^>]*>([\\s\\S]*?)</${tag}>`, 'i').exec(html)
    if (region !== null) {
      scope = region[1]
      break
    }
  }
  let text = scope
    .replace(/<(script|style|noscript|template|svg|iframe|head|nav|footer|aside|form)\b[^>]*>[\s\S]*?<\/\1>/gi, ' ')
    .replace(/<\/(p|div|section|article|main|li|ul|ol|h[1-6]|blockquote|tr|table|figure|figcaption|dd|dt|dl|pre|header)>/gi, '\n')
    .replace(/<br\s*\/?>/gi, '\n')
    .replace(/<li\b[^>]*>/gi, '• ')
    .replace(/<[^>]+>/g, ' ')
  text = decodeHtmlEntities(text)
    .replace(/[ \t\u00a0]+/g, ' ')
    .split('\n')
    .map((line) => line.trim())
    .join('\n')
    .replace(/\n{3,}/g, '\n\n')
    .trim()
  if (text.length <= limit) return { title, description, text, truncated: false }
  const cut = text.lastIndexOf(' ', limit)
  return {
    title,
    description,
    text: text.slice(0, cut > limit * 0.8 ? cut : limit).trimEnd(),
    truncated: true,
  }
}

/**
 * The page's own summary of itself, from `description` or `og:description`.
 *
 * @param html - the fetched document.
 * @returns the decoded description, or null when the page declares none.
 */
function metaDescriptionOf(html) {
  for (const tag of html.match(/<meta\b[^>]*>/gi) ?? []) {
    if (!/(?:name|property)\s*=\s*["']?(?:description|og:description)["']?/i.test(tag)) continue
    const content = /content\s*=\s*["']([^"']*)["']/i.exec(tag)?.[1]
    if (content === undefined) continue
    const decoded = decodeHtmlEntities(content).replace(/\s+/g, ' ').trim()
    if (decoded.length > 0) return decoded.slice(0, 500)
  }
  return null
}

/**
 * Shape one fetch result for the phone.
 *
 * @param requested - the canonical URL the client asked about.
 * @param result - what `ctx.web.fetch` resolved with.
 * @returns the wire payload.
 */
function buildPreviewPayload(requested, result) {
  const body = result?.body ?? {}
  const kind = body.kind === 'html' ? 'html' : 'text'
  let title = null
  let description = null
  let text = typeof body.content === 'string' ? body.content : ''
  let truncated = result?.truncated === true
  if (kind === 'html') {
    const page = readablePage(text, PREVIEW_TEXT_LIMIT)
    title = page.title
    description = page.description
    text = page.text
    truncated = truncated || page.truncated
  } else if (text.length > PREVIEW_TEXT_LIMIT) {
    text = text.slice(0, PREVIEW_TEXT_LIMIT)
    truncated = true
  }
  const payload = {
    url: typeof result?.url === 'string' && result.url.length > 0 ? result.url : requested,
    kind,
    text,
    truncated,
  }
  if (Number.isInteger(result?.statusCode)) payload.statusCode = result.statusCode
  if (title !== null && title.length > 0) payload.title = title
  if (description !== null && description.length > 0) payload.description = description
  return payload
}

/**
 * Pick the most readable text out of the harness's localized reason object.
 *
 * `displayReason` is a locale map — `{ en, "zh-CN", … }` — and a phone shows one
 * line. English is preferred when present, and any other string is better than
 * showing a raw object.
 *
 * @param displayReason - the harness's display reason, or anything else.
 * @returns one line of text, or null.
 */
function displayReasonText(displayReason) {
  if (displayReason === null || typeof displayReason !== 'object') return null
  if (typeof displayReason.en === 'string' && displayReason.en.length > 0) return displayReason.en
  for (const value of Object.values(displayReason)) {
    if (typeof value === 'string' && value.length > 0) return value
  }
  return null
}

/**
 * Find one image attachment reference inside a message's content blocks.
 *
 * The reference is returned whole because a read verifies the recorded bytes
 * and geometry against it: a ref rebuilt from the id alone would be refused.
 *
 * @param content - a message content array, or anything else.
 * @param attachmentId - the durable attachment id to look for.
 * @returns the recorded reference, or undefined.
 */
function imageRefIn(content, attachmentId) {
  if (!Array.isArray(content)) return undefined
  for (const block of content) {
    if (block?.type !== 'image') continue
    if (block.attachment?.attachmentId === attachmentId) return block.attachment
  }
  return undefined
}

/**
 * One file of a `deliverables/presented` event, as a phone is told about it.
 *
 * The declaration is the harness's, not this plugin's: the `present` tool
 * appends a durable event naming the files an agent delivered, and the desktop
 * folds the same event into its delivery card. Only the two fields the tool's
 * own schema guarantees are read, and an entry that names no path is dropped
 * rather than rendered as a row that leads nowhere.
 *
 * @param value - one element of the event's `files` array.
 * @returns the wire row, or null when it is not a declaration.
 */
function declaredFile(value) {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) return null
  const path = typeof value.path === 'string' ? value.path.trim() : ''
  if (path.length === 0) return null
  const description = typeof value.description === 'string' ? value.description.trim() : ''
  return description.length === 0 ? { path } : { path, description }
}

/**
 * Render one session event as a phone-facing entry, or null when the event
 * carries nothing a chat client can show.
 * @param event - one durable session event.
 * @returns the wire entry or null.
 */
function wireEvent(event) {
  const seq = event?.seq
  const type = event?.type
  const data = event?.data
  if (typeof seq !== 'number' || typeof type !== 'string') return null
  switch (type) {
    case 'user/message':
    case 'assistant/message':
    case 'developer/message':
    case 'system/message': {
      const message = type === 'user/message' ? data : data?.message
      if (message === null || typeof message !== 'object') return null
      const role = typeof message.role === 'string' ? message.role : type.split('/')[0]
      return {
        kind: 'message',
        seq,
        type,
        role,
        time: typeof event.time === 'number' ? event.time : undefined,
        content: wireContent(message.content),
        interrupted: data?.interrupted === true ? true : undefined,
        source: typeof message.source?.kind === 'string' ? message.source.kind : undefined,
      }
    }
    case 'tool/call':
      return {
        kind: 'tool-call',
        seq,
        type,
        time: typeof event.time === 'number' ? event.time : undefined,
        turn: typeof data?.turn === 'number' ? data.turn : undefined,
        step: typeof data?.step === 'number' ? data.step : undefined,
        callId: typeof data?.callId === 'string' ? data.callId : undefined,
        name: typeof data?.name === 'string' ? data.name : undefined,
        arguments: typeof data?.arguments === 'string' ? data.arguments : undefined,
      }
    case 'tool/result': {
      const message = data?.message
      return {
        kind: 'tool-result',
        seq,
        type,
        time: typeof event.time === 'number' ? event.time : undefined,
        turn: typeof data?.turn === 'number' ? data.turn : undefined,
        step: typeof data?.step === 'number' ? data.step : undefined,
        callId: typeof message?.toolCallId === 'string' ? message.toolCallId : undefined,
        name: typeof message?.name === 'string' ? message.name : undefined,
        isError: message?.isError === true ? true : undefined,
        content: wireContent(message?.content),
      }
    }
    case 'turn/start':
      return { kind: 'turn-start', seq, type, time: typeof event.time === 'number' ? event.time : undefined, turn: data?.turn }
    case 'deliverables/presented': {
      // What an agent handed over, as its own entry rather than as the arguments
      // of the `present` call that produced it: the tool call says *that* files
      // were declared, this says *which*, and only this one survives being read
      // from a log whose call the phone paged past.
      const declared = Array.isArray(data?.files) ? data.files : []
      const files = []
      for (let index = 0; index < declared.length; index += 1) {
        const file = declaredFile(declared[index])
        // The index is the position in the *event's* array, dropped entries
        // included: it is the coordinate the delivery route resolves the
        // declaration by, and renumbering here would address another file.
        if (file !== null) files.push({ index, ...file })
      }
      if (files.length === 0) return null
      return {
        kind: 'deliverable',
        seq,
        type,
        time: typeof event.time === 'number' ? event.time : undefined,
        turn: typeof data?.turn === 'number' ? data.turn : undefined,
        callId: typeof data?.callId === 'string' ? data.callId : undefined,
        files,
      }
    }
    case 'approval/asked':
      // A durable record that the agent asked for permission. It is what lets a
      // transcript show an approval after the fact, and its `callId` is the
      // same one the live request carries, so a pending approval and its log
      // entry can be recognised as the same event.
      return {
        kind: 'approval-asked',
        seq,
        type,
        time: typeof event.time === 'number' ? event.time : undefined,
        approvalId: typeof data?.id === 'string' ? data.id : undefined,
        name: typeof data?.toolName === 'string' ? data.toolName : undefined,
        callId: typeof data?.callId === 'string' ? data.callId : undefined,
        reason: typeof data?.reason === 'string' ? data.reason : undefined,
      }
    case 'approval/decided':
      return {
        kind: 'approval-decided',
        seq,
        type,
        time: typeof event.time === 'number' ? event.time : undefined,
        approvalId: typeof data?.id === 'string' ? data.id : undefined,
        outcome: typeof data?.outcome === 'string' ? data.outcome : undefined,
      }
    case 'turn/end':
      return {
        kind: 'turn-end',
        seq,
        type,
        time: typeof event.time === 'number' ? event.time : undefined,
        turn: data?.turn,
        // Both spellings of the reason travel as the kind alone: the wire is read
        // by a phone that has no use for the nested cancel cause, and the status
        // dot above is derived from the same vocabulary.
        reason: typeof data?.reason === 'string'
          ? data.reason
          : typeof data?.reason?.kind === 'string'
            ? data.reason.kind
            : undefined,
      }
    case 'step/start':
      return { kind: 'step-start', seq, type, time: typeof event.time === 'number' ? event.time : undefined, turn: data?.turn, step: data?.step }
    case 'step/end':
      return { kind: 'step-end', seq, type, time: typeof event.time === 'number' ? event.time : undefined, turn: data?.turn, step: data?.step }
    default:
      return null
  }
}

/**
 * Read and parse one JSON request body with a hard size cap.
 *
 * An empty body is refused only when the caller requires one: `GET`-shaped
 * work driven by `POST` (a directory listing with a long path) legitimately
 * arrives without a body.
 *
 * @param req - the incoming request.
 * @param options - `required` refuses an absent/empty body; `empty` is the fallback value;
 *   `limit` overrides the body ceiling for routes that carry almost nothing.
 * @returns the parsed JSON value, the fallback for an empty body, or undefined.
 * @throws HttpError on oversize, malformed, or non-object bodies.
 */
async function readJsonBody(req, options = {}) {
  const { required = false, empty, limit = MAX_BODY_BYTES } = options
  const declared = req.headers['content-length']
  if (declared === '0' || (declared === undefined && req.headers['transfer-encoding'] === undefined)) {
    if (required) throw new HttpError(400, 'invalid-request', 'a JSON object body is required')
    return empty
  }
  const chunks = []
  let total = 0
  for await (const chunk of req) {
    total += chunk.length
    if (total > limit) throw new HttpError(413, 'body-too-large', `request body exceeds ${limit} bytes`)
    chunks.push(chunk)
  }
  if (total === 0) {
    if (required) throw new HttpError(400, 'invalid-request', 'a JSON object body is required')
    return empty
  }
  let parsed
  try {
    parsed = JSON.parse(Buffer.concat(chunks).toString('utf8'))
  } catch {
    throw new HttpError(400, 'invalid-json', 'request body is not valid JSON')
  }
  if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) {
    throw new HttpError(400, 'invalid-json', 'request body must be a JSON object')
  }
  return parsed
}

/**
 * Answer one request with a JSON payload.
 * @param res - the response to write.
 * @param status - HTTP status code.
 * @param payload - JSON-serializable value.
 */
function sendJson(res, status, payload) {
  const body = Buffer.from(JSON.stringify(payload), 'utf8')
  res.writeHead(status, {
    'content-type': 'application/json; charset=utf-8',
    'content-length': String(body.length),
    'cache-control': 'no-store',
  })
  res.end(body)
}

/**
 * Answer one failure as a JSON error envelope.
 * @param res - the response to write.
 * @param failure - status, code, and message.
 */
function sendFailure(res, failure) {
  sendJson(res, failure.status, { error: { code: failure.code, message: failure.message } })
}

/** The mobile-sync host plugin. */
class MobileSync {
  /**
   * @param ctx - the Cordis context this plugin was mounted into.
   * @param config - resolved plugin configuration.
   */
  constructor(ctx, config) {
    this.ctx = ctx
    this.config = config
    this.server = undefined
    this.port = undefined
    this.devices = []
    this.setupCode = mintToken(16)
    this.streams = new Set()
    this.seenSessionIds = new Set()
    /** Requests served since activation, per route prefix; a cheap liveness signal. */
    this.requestCounts = new Map()
    this.startedAt = Date.now()
    this.configPath = join(dshHome(), 'storages', 'mobile-sync.json')
    /**
     * Serialises state writes.
     *
     * Two pairing requests arriving together used to snapshot the device table at
     * different moments and write it twice, so the second write silently dropped
     * the device the first one had just added. Every write goes through this
     * chain, and each one is a whole-file replace, so the last write always holds
     * both devices.
     */
    this.saveChain = Promise.resolve()
    /**
     * Pair attempts, by peer address, for the one unauthenticated route.
     *
     * A code is 128 bits of entropy, so guessing it is not a real attack; a
     * listener that answers an unlimited number of guesses at wire speed is
     * still a bad neighbour on a shared network. The window is small, the map is
     * bounded, and a successful pair clears the count.
     */
    this.pairAttempts = new Map()
    /**
     * Approvals waiting for a phone answer, by the id handed to the client.
     *
     * Deliberately in memory and never persisted: an approval belongs to one
     * open turn, and a harness restart cancels it. Writing them to disk would
     * let a stale card be answered after the request it belonged to is gone.
     */
    this.pendingApprovals = new Map()
    this.approvalSeq = 0
    /**
     * Recent link previews, by canonical URL.
     *
     * A preview is a read of a page the phone just showed a link for, and taps
     * repeat: reopening the same link should be instant and should not ask the
     * network again. Minutes, not persisted, and bounded — this is a
     * convenience, and a stale page beats a hammer.
     */
    this.previewCache = new Map()
    /**
     * Chat statuses, by session id, each remembered against the row it was read for.
     *
     * The status is "how did the last turn end", which only changes when the
     * session logs something new — and a session that logged something new has a
     * new `updatedAt`. Keying on that is what keeps a list of eighty chats from
     * reading eighty histories every poll: only the rows that changed are read
     * again. In memory, bounded, and rebuildable, because it is a cache of a fact
     * the log still holds.
     */
    this.chatStatuses = new Map()
    /**
     * Questions waiting for a phone answer, by the id handed to the client.
     *
     * In memory for the same reason approvals are: a question belongs to a live
     * exchange, and a persisted one could be answered long after the model moved
     * on.
     */
    this.pendingQuestions = new Map()
    /**
     * Questions whose live window closed but that the harness still accepts an
     * answer for.
     *
     * The harness gives a question two minutes and then tells the model to carry
     * on, marking the question `continued` — it stays answerable, and a reader who
     * takes three minutes should still be able to reply. This remembers enough to
     * route a late answer through the harness's own RPC. Bounded, because it is a
     * convenience and not a durable queue.
     */
    this.expiredQuestions = new Map()
    this.questionSeq = 0
  }

  /** Load persisted pairing state, start listening, and wire the event feed. */
  async [Symbol.for('cordis.init')]() {
    await this.loadState()
    const row = this.ctx.get('sessionController')
    if (row === undefined) {
      this.ctx.logger.warn('mobile-sync: sessionController is absent; the mobile API cannot serve session data')
    }
    this.ctx.effect(() => {
      const dispose = this.ctx.on('session/event', (session, event) => {
        if (session === null || typeof session !== 'object') return
        this.publish(session.id, event)
      })
      return dispose
    }, 'mobile-sync: session event feed')
    this.ctx.effect(() => {
      const dispose = this.ctx.on('api-session/status', (sessionId, running) => {
        this.broadcast({ event: 'status', sessionId, running: running === true })
      })
      return dispose
    }, 'mobile-sync: session status feed')
    this.ctx.effect(() => this.offerApprovals(), 'mobile-sync: approval answerer')
    this.ctx.effect(() => this.offerQuestions(), 'mobile-sync: question answerer')
    await this.listen()
  }

  /**
   * Register this plugin as an `ask_user_question` answerer for the phone.
   *
   * Same seam as approvals — the Agent-scoped `user-questions/request` waterfall,
   * where the first listener to return an answer settles it — but with a
   * different failure mode. An unanswered approval denies a tool; an unanswered
   * *question* denies nothing: the harness tells the model to carry on and leaves
   * the question open. So this listener never invents an answer and never turns
   * silence into a decision; it forwards what a paired device chose, and
   * otherwise hands the request down the chain untouched.
   *
   * @returns the disposer Cordis calls when the plugin unloads.
   */
  offerQuestions() {
    return this.ctx.on(
      'user-questions/request',
      async (request, next) => {
        const sessionId = request?.agent?.session?.id
        const questions = wireQuestions(request?.questions)
        // Without a session there is no chat to show the question in, and a
        // request with nothing to ask has nothing to render either.
        if (typeof sessionId !== 'string' || sessionId.length === 0 || questions.length === 0) return next()

        const callId = typeof request?.wait?.callId === 'string' ? request.wait.callId : null
        const pending = this.openQuestions({ sessionId, callId, questions })
        // Offered downstream at the same time, not after: waiting for the desktop
        // *while* holding the phone's answer is what makes this a race rather than
        // a queue.
        const downstream = Promise.resolve()
          .then(() => next())
          .then(
            (answer) => ({ kind: 'answer', answer }),
            (error) => ({ kind: 'error', error }),
          )
        const raced = await Promise.race([pending.answered, downstream])
        let answer = raced?.kind === 'phone' ? raced.answer : null
        let by = answer === null ? null : 'phone'
        if (answer === null && raced?.kind === 'answer' && raced.answer !== undefined) {
          answer = raced.answer
          by = 'desktop'
        }
        if (answer === null) {
          const fromPhone = await this.awaitAnswer(pending, request.signal, 'questionWaitMs', DEFAULT_QUESTION_WAIT_MS)
          if (fromPhone !== null) {
            answer = fromPhone
            by = 'phone'
          } else {
            // Nobody answered. The harness will tell the model to carry on and
            // leave the question answerable, so it moves to the late list rather
            // than disappearing — a card that vanished would hide the very thing
            // the reader is still allowed to answer.
            this.expireQuestions(pending)
            const settled = await downstream
            if (settled.kind === 'error') throw settled.error
            return settled.answer
          }
        }
        this.settleQuestions(pending.id, answer, by)
        return answer
      },
      { prepend: true },
    )
  }

  /**
   * Wait for a phone answer, bounded.
   *
   * @param pending - the open record.
   * @param signal - the request's own abort signal, when the harness provided one.
   * @param configKey - which configured wait to use.
   * @param fallbackMs - the wait to use when the configuration says nothing.
   * @returns the chosen answer, or null when the window closed or was cancelled.
   */
  async awaitAnswer(pending, signal, configKey, fallbackMs) {
    const configured = Number(this.config[configKey])
    const wait = Number.isFinite(configured) ? configured : fallbackMs
    if (wait <= 0) return null
    let timer
    const timeout = new Promise((resolve) => {
      timer = setTimeout(() => resolve(null), wait)
      timer.unref?.()
    })
    const aborted = new Promise((resolve) => {
      if (signal === undefined) return
      if (signal.aborted) {
        resolve(null)
        return
      }
      signal.addEventListener('abort', () => resolve(null), { once: true })
    })
    try {
      return await Promise.race([pending.answered, timeout, aborted])
    } finally {
      clearTimeout(timer)
    }
  }

  /**
   * Record one question set as pending and announce it to every paired phone.
   *
   * @param details - the request as the harness described it.
   * @returns the pending record, whose `answered` promise resolves with the chosen answers.
   */
  openQuestions(details) {
    this.questionSeq += 1
    const id = `question-${this.questionSeq}-${mintToken(6)}`
    let resolveAnswer
    const answered = new Promise((resolve) => {
      resolveAnswer = resolve
    })
    const record = {
      id,
      ...details,
      createdAt: Date.now(),
      answered,
      // `answered` resolves with a tagged object, so the race can tell the
      // phone's answer from the chain's by shape.
      resolve: (answer) => resolveAnswer({ kind: 'phone', answer }),
      settled: false,
    }
    this.pendingQuestions.set(id, record)
    this.broadcast({ event: 'question-request', question: this.wireQuestion(record) })
    this.ctx.logger.info(
      `mobile-sync: ${record.questions.length} question(s) asked in session ${record.sessionId}; waiting for the phone or the PC`,
    )
    return record
  }

  /**
   * Move one question from the live list to the late list.
   *
   * The harness marks a timed-out question `continued` and still accepts an
   * answer for it, so this is not a close: the card has to stay, and the only
   * thing that changes is which channel an answer takes.
   *
   * @param record - the record whose live window just closed.
   */
  expireQuestions(record) {
    this.pendingQuestions.delete(record.id)
    // A question with no call id cannot be addressed by the harness's RPC, so
    // keeping it would offer a button that could only ever return a conflict.
    if (record.callId === null) {
      this.broadcast({ event: 'question-settled', questionId: record.id, sessionId: record.sessionId, answer: null, by: 'timeout' })
      return
    }
    this.expiredQuestions.set(record.id, {
      id: record.id,
      sessionId: record.sessionId,
      callId: record.callId,
      questions: record.questions,
      createdAt: record.createdAt,
      expiredAt: Date.now(),
    })
    while (this.expiredQuestions.size > MAX_EXPIRED_QUESTIONS) {
      const oldest = this.expiredQuestions.keys().next().value
      this.expiredQuestions.delete(oldest)
    }
    this.broadcast({ event: 'question-expired', questionId: record.id, sessionId: record.sessionId })
    this.ctx.logger.info(`mobile-sync: question ${record.id} moved to the late list; the harness still accepts an answer`)
  }

  /**
   * Close one question record and announce the outcome.
   *
   * @param id - the id handed to the client.
   * @param answer - the answers, or null when the question simply went unanswered.
   * @param by - who settled it, for the log and the client's own labelling.
   */
  settleQuestions(id, answer, by) {
    const record = this.pendingQuestions.get(id)
    if (record !== undefined && !record.settled) {
      record.settled = true
      this.pendingQuestions.delete(id)
    }
    this.expiredQuestions.delete(id)
    this.broadcast({ event: 'question-settled', questionId: id, sessionId: record?.sessionId ?? null, answer: answer ?? null, by })
  }

  /**
   * The questions a client should still see: the live ones, then the late ones.
   *
   * @returns the wire questions, each flagged with whether it is still live.
   */
  questionItems() {
    const now = Date.now()
    const items = [...this.pendingQuestions.values()].map((record) => this.wireQuestion(record, false))
    for (const record of [...this.expiredQuestions.values()]) {
      // A late question that nobody answered is not worth showing forever; the
      // harness refuses an answer to a session that has moved several turns on.
      if (now - record.expiredAt > MAX_EXPIRED_AGE_MS) {
        this.expiredQuestions.delete(record.id)
        continue
      }
      items.push(this.wireQuestion(record, true))
    }
    return items
  }

  /**
   * Reduce one question set to the shape a phone can render.
   * @param record - the pending or late record.
   * @param expired - whether the live window has closed.
   * @returns the wire question.
   */
  wireQuestion(record, expired) {
    return {
      id: record.id,
      sessionId: record.sessionId,
      callId: record.callId,
      createdAt: record.createdAt,
      // The client needs to know, because the answer takes a different channel
      // and the card should say that the agent has already moved on.
      expired: expired === true,
      questions: record.questions,
    }
  }

  /**
   * Register this plugin as an approval answerer for the phone.
   *
   * The harness has no pending-approval list and no RPC to answer one: an
   * approval exists only as the Agent-scoped `approval/request` waterfall, and
   * whoever returns an outcome first settles it. The desktop GUI is one
   * listener on that waterfall, so this plugin can be another — which is
   * exactly what makes a phone able to answer without breaking the PC.
   *
   * Registration is prepended, and that is deliberate. A listener placed after
   * the GUI's would only ever run when no browser is attached, because the
   * GUI's own listener waits for a click. Sitting first lets this one offer the
   * request to the phone *and* pass it down the chain with `next()`, so both
   * surfaces show the same card and the first answer wins — the same
   * first-answer-wins rule the harness already applies between two browsers.
   *
   * The outcome vocabulary is fixed and fail-closed: `allowed-once` is the only
   * grant, everything else denies. This listener never invents one; it only
   * forwards what a paired device explicitly chose.
   *
   * @returns the disposer Cordis calls when the plugin unloads.
   */
  offerApprovals() {
    return this.ctx.on(
      'approval/request',
      async (request, next) => {
        const sessionId = request?.agent?.session?.id
        // An approval with no session cannot be attributed to a chat, and the
        // phone has nowhere to show it: hand it straight to the next answerer.
        if (typeof sessionId !== 'string' || sessionId.length === 0) return next()
        const pending = this.openApproval({
          sessionId,
          toolName: typeof request.toolName === 'string' ? request.toolName : 'tool',
          callId: typeof request.callId === 'string' ? request.callId : null,
          reason: typeof request.reason === 'string' ? request.reason : null,
          displayReason: displayReasonText(request.displayReason),
        })
        // Offer it to the desktop as well, concurrently. Waiting for the
        // downstream answer *while* holding the phone's answer is what makes
        // this a race rather than a queue.
        const downstream = Promise.resolve()
          .then(() => next())
          .then(
            (outcome) => ({ kind: 'outcome', outcome: typeof outcome === 'string' ? outcome : 'unavailable' }),
            () => ({ kind: 'outcome', outcome: 'unavailable' }),
          )
        const raced = await Promise.race([pending.answered, downstream])
        // The phone answers with a bare outcome string; the chain answers with
        // a tagged object. `unavailable` from the chain means no answerer was
        // reachable, which is not a denial — it is an invitation to keep
        // waiting for the phone.
        let outcome = typeof raced === 'string' ? raced : null
        let by = outcome === null ? null : 'phone'
        if (outcome === null && raced !== null && typeof raced === 'object' && raced.outcome !== 'unavailable') {
          outcome = raced.outcome
          by = 'desktop'
        }
        if (outcome === null) {
          const fromPhone = await this.awaitPhone(pending, request.signal)
          if (fromPhone !== null) {
            outcome = fromPhone
            by = 'phone'
          } else {
            // The window closed with nobody answering. If the chain still holds
            // an open card, keep waiting for it: that is the behaviour without
            // this plugin, and it must not turn into a silent deny.
            const settled = await downstream
            outcome = settled.outcome
            by = 'desktop'
          }
        }
        this.settleApproval(pending.id, outcome, by)
        return outcome
      },
      { prepend: true },
    )
  }

  /**
   * Wait for a phone answer, bounded so an ignored card cannot hang a turn.
   *
   * @param pending - the open approval record.
   * @param signal - the request's own abort signal, when the harness provided one.
   * @returns the chosen outcome, or null when the window closed or the request was cancelled.
   */
  async awaitPhone(pending, signal) {
    const wait = Number.isFinite(this.config.approvalWaitMs) ? this.config.approvalWaitMs : DEFAULT_APPROVAL_WAIT_MS
    if (wait <= 0) return null
    let timer
    const timeout = new Promise((resolve) => {
      timer = setTimeout(() => resolve(null), wait)
      timer.unref?.()
    })
    const aborted = new Promise((resolve) => {
      if (signal === undefined) return
      if (signal.aborted) {
        resolve(null)
        return
      }
      signal.addEventListener('abort', () => resolve(null), { once: true })
    })
    try {
      return await Promise.race([pending.answered, timeout, aborted])
    } finally {
      clearTimeout(timer)
    }
  }

  /**
   * Record one approval as pending and announce it to every paired phone.
   *
   * @param details - the request as the harness described it.
   * @returns the pending record, whose `answered` promise resolves with the chosen outcome.
   */
  openApproval(details) {
    this.approvalSeq += 1
    const id = `approval-${this.approvalSeq}-${mintToken(6)}`
    let resolveAnswer
    const answered = new Promise((resolve) => {
      resolveAnswer = resolve
    })
    const record = {
      id,
      ...details,
      createdAt: Date.now(),
      answered,
      resolve: resolveAnswer,
      settled: false,
    }
    this.pendingApprovals.set(id, record)
    this.broadcast({ event: 'approval-request', approval: this.wireApproval(record) })
    this.ctx.logger.info(`mobile-sync: approval requested (${record.toolName}) for session ${record.sessionId}; waiting for the phone or the PC`)
    return record
  }

  /**
   * Close one approval record and announce the outcome.
   *
   * @param id - the approval id handed to the client.
   * @param outcome - the verdict, or null when the request simply went away.
   * @param by - who settled it, for the log and the client's own labelling.
   */
  settleApproval(id, outcome, by) {
    const record = this.pendingApprovals.get(id)
    if (record === undefined || record.settled) return
    record.settled = true
    this.pendingApprovals.delete(id)
    record.resolve(null)
    this.broadcast({ event: 'approval-settled', approvalId: id, sessionId: record.sessionId, outcome: outcome ?? null, by })
  }

  /**
   * Reduce one pending approval to the shape a phone can render.
   * @param record - the pending record.
   * @returns the wire approval.
   */
  wireApproval(record) {
    return {
      id: record.id,
      sessionId: record.sessionId,
      toolName: record.toolName,
      callId: record.callId,
      reason: record.reason,
      displayReason: record.displayReason,
      createdAt: record.createdAt,
      outcomes: [...APPROVAL_OUTCOMES],
    }
  }

  /**
   * Load devices and access data from `$DSH_HOME/storages/mobile-sync.json`.
   * @returns after the in-memory state reflects the file, or after writing it.
   */
  async loadState() {
    let raw
    try {
      raw = await readFile(this.configPath, 'utf8')
    } catch (error) {
      if (error?.code !== 'ENOENT') this.ctx.logger.warn(`mobile-sync: cannot read ${this.configPath}: ${String(error)}`)
      await this.saveState()
      this.ctx.logger.info(`mobile-sync: created ${this.configPath}`)
      return
    }
    try {
      // A byte-order mark is invisible but fatal to `JSON.parse`, and this file
      // is exactly the kind a person edits by hand: PowerShell's `Set-Content
      // -Encoding UTF8` writes one, and so does Notepad's "UTF-8" option. Left
      // in, it would silently boot with an empty device table — every paired
      // phone would get `Token non più valido` with nothing in the log to say
      // why. Stripping it costs one line and removes that whole failure mode.
      const parsed = JSON.parse(raw.charCodeAt(0) === 0xFEFF ? raw.slice(1) : raw)
      if (Array.isArray(parsed?.devices)) {
        this.devices = parsed.devices
          .filter((device) => typeof device?.token === 'string' && device.token.length > 0)
          .map((device) => ({
            // The id is derived rather than stored so a table written by an older
            // build (which had no ids) and one written by this build address the
            // same device: revoking by id works across the migration.
            id: typeof device.id === 'string' && device.id.length > 0 ? device.id : deviceIdFor(device.token),
            name: typeof device.name === 'string' && device.name.length > 0 ? device.name : 'Android device',
            token: device.token,
            createdAt: Number.isFinite(device.createdAt) ? device.createdAt : Date.now(),
            lastSeenAt: Number.isFinite(device.lastSeenAt) ? device.lastSeenAt : Date.now(),
          }))
      }
      // The code is authoritative here: an empty string means it was already
      // spent, anything else is usable. There is deliberately no expiry — a
      // short window only punishes a slow restart, while single use is what
      // actually closes the door. (An earlier version persisted an Infinity
      // expiry, which JSON.stringify turns into null and silently discards.)
      if (typeof parsed?.setupCode === 'string') this.setupCode = parsed.setupCode
    } catch (error) {
      // A file that no longer parses is kept, never overwritten: whoever edited
      // it by hand can still see what was there. The boot then mints a code and
      // *persists* it, so the value in the log and the value on disk agree — the
      // old path logged a random code that was never written, which reads exactly
      // like a pairing bug to the person typing it in.
      this.ctx.logger.warn(`mobile-sync: ${this.configPath} is not valid JSON; keeping it aside and starting empty: ${String(error)}`)
      await this.setCorruptAside()
      this.setupCode = mintToken(16)
      await this.saveState()
    }
  }

  /**
   * Move an unreadable state file out of the way instead of deleting it.
   * @returns after the move was attempted.
   */
  async setCorruptAside() {
    const target = `${this.configPath}.corrupt-${Date.now()}`
    try {
      await rename(this.configPath, target)
      this.ctx.logger.warn(`mobile-sync: the unreadable state file is kept as ${target}`)
    } catch (error) {
      this.ctx.logger.warn(`mobile-sync: cannot keep the unreadable state file aside: ${String(error)}`)
    }
  }

  /**
   * Persist pairing state, creating the storage directory when absent.
   *
   * Writes are serialised and atomic: the new table is written to a sibling
   * file, flushed, and renamed over the old one. A crash mid-write therefore
   * leaves the previous table intact instead of truncating the file that holds
   * every paired phone.
   *
   * @returns after the file is written.
   */
  saveState() {
    const run = this.saveChain.then(() => this.writeState())
    this.saveChain = run.catch(() => {})
    return run
  }

  /**
   * Write the state file once, via a flushed temporary file.
   * @returns after the rename has replaced the previous file.
   */
  async writeState() {
    const payload = {
      version: 3,
      setupCode: this.setupCode,
      devices: this.devices,
    }
    const text = `${JSON.stringify(payload, null, 2)}\n`
    await mkdir(dirname(this.configPath), { recursive: true })
    const temporary = `${this.configPath}.${process.pid}.tmp`
    const handle = await open(temporary, 'w', 0o600)
    try {
      await handle.writeFile(text, 'utf8')
      // Flush before the rename: a rename that lands ahead of the data is how a
      // power loss turns a valid table into an empty file.
      await handle.sync()
    } finally {
      await handle.close()
    }
    await rename(temporary, this.configPath)
  }

  /**
   * Bind the dedicated listener and register its disposer.
   * @returns after the socket is listening.
   */
  async listen() {
    const port = Number(this.config?.port ?? 4380)
    if (!Number.isInteger(port) || port < 0 || port > 65_535) {
      throw new Error(`mobile-sync: port must be an integer between 0 and 65535 (got ${String(this.config?.port)})`)
    }
    // No host means loopback: a plugin mounted without a host must not be the
    // reason the harness is reachable from the network. Admitting phones is a
    // deliberate `host: "0.0.0.0"`, which the boot log then explains.
    const host = typeof this.config?.host === 'string' && this.config.host.length > 0 ? this.config.host : '127.0.0.1'
    const server = createServer((req, res) => {
      this.handle(req, res).catch((error) => {
        // A malformed percent-escape is the client's mistake, not the harness's
        // failure: `decodeURIComponent` throws `URIError`, and answering 500 with
        // a warning line made a typo look like an internal fault.
        const failure = error instanceof URIError
          ? { status: 400, code: 'invalid-request', message: 'the request path is not valid percent-encoding' }
          : toHttpFailure(error, this.config.debug === true)
        this.ctx.logger.warn(`mobile-sync: request failed: ${String(error)}`)
        if (res.headersSent) {
          res.destroy()
          return
        }
        sendFailure(res, failure)
      })
    })
    // A slow header, not a slow phone: uploads legitimately take minutes on a
    // mobile link, headers never do.
    server.headersTimeout = 30_000
    server.requestTimeout = 600_000
    server.on('clientError', (_error, socket) => {
      socket.destroy()
    })
    await new Promise((resolve, reject) => {
      server.once('error', reject)
      server.listen(port, host, () => {
        server.off('error', reject)
        server.on('error', (error) => this.ctx.logger.error(`mobile-sync: listener error: ${String(error)}`))
        resolve()
      })
    })
    this.server = server
    this.port = server.address().port
    this.ctx.effect(() => () => {
      for (const stream of this.streams) stream.end()
      this.streams.clear()
      // A turn parked on a phone answer must not outlive the listener: resolving
      // with null is the same "nobody answered" the timeout produces, and it is
      // what lets the harness close instead of waiting for a card that can never
      // be drawn again.
      for (const record of this.pendingApprovals.values()) record.resolve(null)
      this.pendingApprovals.clear()
      for (const record of this.pendingQuestions.values()) record.resolve(null)
      this.pendingQuestions.clear()
      server.closeAllConnections()
      server.close()
    }, 'mobile-sync: listener')
    this.ctx.logger.info(`mobile-sync: listening on http://${host}:${this.port}`)
    if (isLoopback(host) || host === 'localhost') {
      this.ctx.logger.info('mobile-sync: bound to loopback, so a phone cannot reach it; set host to 0.0.0.0 to admit one')
    } else {
      const urls = reachableUrls(this.port)
      if (urls.length === 0) {
        this.ctx.logger.warn('mobile-sync: no non-loopback IPv4 address found; is this machine on a network?')
      }
      for (const candidate of urls) {
        this.ctx.logger.info(
          `mobile-sync: reachable at ${candidate.url}${candidate.tailscale ? '  (Tailscale — works from any network)' : '  (same Wi-Fi only)'}`,
        )
      }
      this.ctx.logger.info('mobile-sync: type one of these addresses into the app; if the phone cannot connect, allow this app through Windows Firewall on private networks')
    }
    // No expiry: a code lives until it is used. Every boot with a spent code
    // mints a fresh one and persists it before announcing it, so the value in
    // the log and the value on disk always agree.
    if (this.setupCode.length === 0) {
      this.setupCode = mintToken(16)
      await this.saveState()
      this.ctx.logger.info(`mobile-sync: new pairing code minted`)
    }
    this.ctx.logger.info(`mobile-sync: pairing code ${this.setupCode} — enter it in the app to pair a device`)
    if (this.devices.length > 0) {
      this.ctx.logger.info(`mobile-sync: ${this.devices.length} paired device(s) already; the code above pairs another`)
    }
  }

  /**
   * Publish one appended session event to every subscribed phone.
   * @param sessionId - the session the event belongs to.
   * @param event - the durable session event.
   */
  publish(sessionId, event) {
    const entry = wireEvent(event)
    if (entry === null) return
    this.broadcast({ event: 'session-event', sessionId, entry })
  }

  /**
   * Send one SSE frame to every subscribed stream.
   * @param payload - JSON-serializable frame payload.
   */
  broadcast(payload) {
    if (this.streams.size === 0) return
    const frame = `data: ${JSON.stringify(payload)}\n\n`
    for (const res of this.streams) {
      // A phone that stopped reading (suspended, tunnel down, radio gone) leaves
      // its frames in this process's memory. `write` answering false is Node
      // saying the socket buffer is full: past the cap the connection is dropped,
      // and the client reconnects instead of the harness growing until it dies.
      if (res.writableLength > SSE_MAX_BUFFER_BYTES) {
        this.dropStream(res)
        continue
      }
      try {
        res.write(frame)
      } catch {
        this.dropStream(res)
      }
    }
  }

  /**
   * Forget one SSE connection and close its socket.
   * @param res - the response whose stream is being abandoned.
   */
  dropStream(res) {
    this.streams.delete(res)
    try {
      res.destroy()
    } catch {
      // The socket is already gone; there is nothing left to close.
    }
  }

  /**
   * Resolve the device a request's bearer token belongs to, if any.
   *
   * Split from [authenticate] because two callers need the answer without the
   * refusal: pairing *rotation* (a device that still holds its token may trade it
   * for a new one without a code) and the device list's "which one is me".
   *
   * @param req - the incoming request.
   * @returns the matching device record, or null when the request carries no valid token.
   */
  deviceFor(req) {
    const header = req.headers.authorization
    if (typeof header !== 'string' || !header.startsWith('Bearer ')) return null
    const token = header.slice('Bearer '.length).trim()
    if (token.length === 0) return null
    return this.devices.find((candidate) => sameSecret(candidate.token, token)) ?? null
  }

  /**
   * Authenticate one request against the paired-device token table.
   * @param req - the incoming request.
   * @returns the matching device record.
   * @throws HttpError 401 when no valid bearer token is present.
   */
  authenticate(req) {
    const device = this.deviceFor(req)
    if (device === null) {
      const header = req.headers.authorization
      if (typeof header !== 'string' || !header.startsWith('Bearer ')) {
        throw new HttpError(401, 'unauthorized', 'a paired device token is required')
      }
      throw new HttpError(401, 'unauthorized', 'this device token is not paired with the harness')
    }
    // Kept in memory rather than written: a request is not a reason to rewrite
    // the state file, but "when did this phone last talk to me" is the one thing
    // a person asks a device list, and the next save carries it to disk.
    device.lastSeenAt = Date.now()
    return device
  }

  /**
   * Require the harness session service.
   * @returns the session controller service.
   * @throws HttpError 503 when the deployment mounts no controller.
   */
  controller() {
    const service = this.ctx.get('sessionController')
    if (service === undefined) throw new HttpError(503, 'unavailable', 'this harness mounts no session controller')
    return service
  }

  /**
   * Create a cancellation signal tied to the request lifetime.
   * @param req - the incoming request.
   * @returns a signal that aborts when the client disconnects.
   */
  signalFor(req) {
    const controller = new AbortController()
    req.once('close', () => controller.abort())
    return controller.signal
  }

  /**
   * Route one request.
   * @param req - the incoming request.
   * @param res - the response to write.
   * @returns after the response is settled.
   */
  async handle(req, res) {
    const url = new URL(req.url ?? '/', 'http://mobile.local')
    const segments = url.pathname.split('/').filter((part) => part.length > 0)
    const method = req.method ?? 'GET'
    this.countRequest(method, segments)

    if (segments[0] !== 'mobile') {
      sendFailure(res, { status: 404, code: 'not-found', message: 'unknown route' })
      return
    }
    if (segments.length === 2 && segments[1] === 'pair' && method === 'POST') {
      await this.routePair(req, res)
      return
    }
    // Minting a code is unauthenticated by necessity — a wiped phone has no
    // token — so the route itself refuses anything that is not this machine.
    if (segments.length === 2 && segments[1] === 'pairing-code' && (method === 'GET' || method === 'POST')) {
      await this.routePairingCode(req, res)
      return
    }

    this.authenticate(req)

    if (segments.length === 2 && segments[1] === 'health' && method === 'GET') {
      await this.routeHealth(req, res)
      return
    }
    if (segments.length === 2 && segments[1] === 'devices' && method === 'GET') {
      await this.routeDevices(req, res)
      return
    }
    if (segments.length === 3 && segments[1] === 'devices' && method === 'DELETE') {
      await this.routeRevokeDevice(req, res, decodeURIComponent(segments[2]))
      return
    }
    if (segments.length === 2 && segments[1] === 'status' && method === 'GET') {
      await this.routeStatus(req, res)
      return
    }
    if (segments.length === 2 && segments[1] === 'sessions' && method === 'GET') {
      await this.routeSessions(req, res, url)
      return
    }
    if (segments.length === 2 && segments[1] === 'sessions' && method === 'POST') {
      await this.routeCreateSession(req, res)
      return
    }
    if (segments.length === 2 && segments[1] === 'workspaces') {
      if (method === 'GET') {
        await this.routeWorkspaces(req, res)
        return
      }
      if (method === 'POST') {
        await this.routeCreateWorkspace(req, res)
        return
      }
    }
    if (segments.length === 3 && segments[1] === 'workspaces' && method === 'DELETE') {
      await this.routeDeleteWorkspace(req, res, decodeURIComponent(segments[2]))
      return
    }
    if (segments.length === 2 && segments[1] === 'dirs' && (method === 'GET' || method === 'POST')) {
      await this.routeDirs(req, res, url)
      return
    }
    if (segments.length === 2 && segments[1] === 'events' && method === 'GET') {
      this.routeEvents(req, res)
      return
    }
    if (segments.length === 2 && segments[1] === 'images' && method === 'POST') {
      await this.routeUploadImage(req, res)
      return
    }
    if (segments.length === 2 && segments[1] === 'files' && method === 'POST') {
      await this.routeUploadFile(req, res, url)
      return
    }
    if (segments.length === 2 && segments[1] === 'models') {
      if (method === 'GET') {
        await this.routeModels(req, res)
        return
      }
      if (method === 'POST') {
        await this.routeSelectModel(req, res)
        return
      }
    }
    if (segments.length === 2 && segments[1] === 'approvals' && method === 'GET') {
      await this.routeApprovals(req, res)
      return
    }
    if (segments.length === 3 && segments[1] === 'approvals' && method === 'POST') {
      await this.routeAnswerApproval(req, res, decodeURIComponent(segments[2]))
      return
    }
    if (segments.length === 2 && segments[1] === 'questions' && method === 'GET') {
      await this.routeQuestions(req, res)
      return
    }
    if (segments.length === 3 && segments[1] === 'questions' && method === 'POST') {
      await this.routeAnswerQuestion(req, res, decodeURIComponent(segments[2]))
      return
    }
    if (segments.length === 2 && segments[1] === 'permissions' && method === 'GET') {
      await this.routePermissions(req, res, url)
      return
    }
    if (segments.length === 2 && segments[1] === 'permissions' && method === 'POST') {
      await this.routeSelectPermission(req, res)
      return
    }
    if (segments.length === 2 && segments[1] === 'file' && method === 'GET') {
      await this.routeFile(req, res, url)
      return
    }
    if (segments.length === 2 && segments[1] === 'delivery' && method === 'GET') {
      await this.routeDelivery(req, res, url)
      return
    }
    if (segments.length === 2 && segments[1] === 'preview' && method === 'GET') {
      await this.routePreview(req, res, url)
      return
    }
    if (segments.length >= 3 && segments[1] === 'sessions') {
      const sessionId = decodeURIComponent(segments[2])
      if (segments.length === 4 && segments[3] === 'messages' && method === 'GET') {
        await this.routeMessages(req, res, sessionId, url)
        return
      }
      if (segments.length === 4 && segments[3] === 'messages' && method === 'POST') {
        await this.routeSend(req, res, sessionId)
        return
      }
      if (segments.length === 6 && segments[3] === 'images' && (segments[5] === 'raw' || segments[5] === 'data') && method === 'GET') {
        await this.routeImage(req, res, sessionId, decodeURIComponent(segments[4]), segments[5] === 'raw')
        return
      }
    }

    sendFailure(res, { status: 404, code: 'not-found', message: 'unknown route' })
  }

  /**
   * Claim the pairing code and mint (or reuse) a device token.
   *
   * The code is single use: it is retired the moment a device joins, so
   * nothing else on the network can ride the same code. There is no expiry —
   * it lives until it is spent, and the next boot mints a replacement. A
   * device that already paired and lost its token can pair again with the
   * current code, which keeps a wiped phone from needing a state-file edit.
   *
   * @param req - the incoming request.
   * @param res - the response to write.
   */
  async routePair(req, res) {
    if (this.pairAttemptsExhausted(req.socket.remoteAddress)) {
      this.ctx.logger.warn(`mobile-sync: pairing refused for ${req.socket.remoteAddress}: too many attempts`)
      throw new HttpError(429, 'rate-limited', 'too many pairing attempts; wait a minute and try again')
    }
    const body = await readJsonBody(req, { required: true, limit: MAX_PAIR_BODY_BYTES })
    const code = typeof body.code === 'string' ? body.code.trim() : ''
    const askedName = typeof body.device === 'string' && body.device.trim().length > 0
      ? body.device.trim().slice(0, 80)
      : 'Android device'
    const existing = this.deviceFor(req)
    if (existing !== null) {
      // Rotation. The caller proved it holds this device's token, so the token is
      // the only credential this branch consults — and the row survives, which is
      // what makes "rotate a credential I think leaked" possible without the PC.
      // The public id is deliberately *not* rederived: it names the device across
      // rotations, so a revoke still points at the phone that rotated.
      existing.token = mintToken(32)
      existing.lastSeenAt = Date.now()
      if (typeof body.device === 'string' && body.device.trim().length > 0) existing.name = askedName
      await this.saveState()
      this.pairAttempts.delete(req.socket.remoteAddress)
      this.ctx.logger.info(`mobile-sync: token rotated for device "${existing.name}" (${existing.id})`)
      sendJson(res, 200, { token: existing.token, device: existing.name, id: existing.id, reused: true, rotated: true })
      return
    }
    if (this.setupCode.length === 0) {
      this.ctx.logger.warn(`mobile-sync: pairing refused for "${askedName}": no code is open`)
      throw new HttpError(403, 'pairing-closed', 'pairing is closed; mint a new code on the PC at /mobile/pairing-code')
    }
    if (code.length === 0) throw new HttpError(400, 'invalid-request', 'a pairing code is required')
    if (!sameSecret(code, this.setupCode)) {
      this.ctx.logger.warn(`mobile-sync: pairing refused for "${askedName}": wrong code`)
      throw new HttpError(403, 'pairing-refused', 'the pairing code is not valid')
    }
    // A name is a label, not an identity: two phones of the same model are two
    // devices, and neither can take the other's token by claiming its name.
    const token = mintToken(32)
    const device = { id: deviceIdFor(token), name: askedName, token, createdAt: Date.now(), lastSeenAt: Date.now() }
    this.devices.push(device)
    this.setupCode = ''
    await this.saveState()
    this.pairAttempts.delete(req.socket.remoteAddress)
    this.ctx.logger.info(`mobile-sync: paired device "${device.name}" (${device.id})`)
    sendJson(res, 200, { token: device.token, device: device.name, id: device.id, reused: false })
  }

  /**
   * Mint a fresh pairing code, from the PC only.
   *
   * A wiped phone has no token, so the only honest way back is a code that
   * somebody standing at the keyboard asked for. Restarting the harness also
   * mints one; this route is the same thing without the restart, and it answers
   * loopback only — a code minted by a request from the network would be a code
   * the network had already seen.
   *
   * @param req - the incoming request.
   * @param res - the response to write.
   */
  async routePairingCode(req, res) {
    if (!isLoopback(req.socket.remoteAddress)) {
      throw new HttpError(403, 'loopback-only', 'a pairing code can only be minted from this PC')
    }
    this.setupCode = mintToken(16)
    await this.saveState()
    this.ctx.logger.info(`mobile-sync: pairing code ${this.setupCode} minted on request — enter it in the app to pair a device`)
    sendJson(res, 200, { code: this.setupCode, pairingOpen: true })
  }

  /**
   * List every paired device, without its token.
   * @param req - the incoming request.
   * @param res - the response to write.
   */
  async routeDevices(req, res) {
    const current = this.deviceFor(req)
    sendJson(res, 200, {
      items: this.devices.map((device) => ({
        id: device.id,
        name: device.name,
        createdAt: device.createdAt,
        lastSeenAt: device.lastSeenAt,
        current: current !== null && device.id === current.id,
      })),
    })
  }

  /**
   * Revoke one paired device by id.
   *
   * A device may revoke itself — that is what "unpair" means from the phone — and
   * the reply says so, because the caller's own token stops working on the very
   * next request.
   *
   * @param req - the incoming request.
   * @param res - the response to write.
   * @param id - the device id from the path.
   */
  async routeRevokeDevice(req, res, id) {
    const index = this.devices.findIndex((device) => device.id === id)
    if (index < 0) throw new HttpError(404, 'device/not-found', `no paired device has id "${id}"`)
    const [removed] = this.devices.splice(index, 1)
    await this.saveState()
    const stillPaired = this.deviceFor(req) !== null
    this.ctx.logger.info(`mobile-sync: revoked device "${removed.name}" (${removed.id})`)
    sendJson(res, 200, { revoked: true, id: removed.id, self: !stillPaired })
  }

  /**
   * Count one pair attempt for a peer and report whether it is over the limit.
   *
   * @param address - `req.socket.remoteAddress`.
   * @returns whether the next attempt must be refused.
   */
  pairAttemptsExhausted(address) {
    const key = typeof address === 'string' && address.length > 0 ? address : 'unknown'
    const now = Date.now()
    const entry = this.pairAttempts.get(key)
    if (entry === undefined || now >= entry.resetAt) {
      if (this.pairAttempts.size >= PAIR_ATTEMPT_TRACK_LIMIT) this.pairAttempts.clear()
      this.pairAttempts.set(key, { count: 1, resetAt: now + PAIR_ATTEMPT_WINDOW_MS })
      return false
    }
    entry.count += 1
    return entry.count > PAIR_ATTEMPT_LIMIT
  }

  /**
   * Bucket one request by route and method.
   *
   * A client that polls shows up here as a rising count, which is how a
   * "is the app still talking to me?" question gets a definite answer without
   * instrumenting the phone.
   *
   * @param method - the HTTP verb.
   * @param segments - the path split on slashes.
   */
  countRequest(method, segments) {
    // Only a known route may create a key. Keying on whatever the caller typed
    // turned the counter into an unbounded map fed by unauthenticated requests:
    // `GET /mobile/<random>` added a key per request, forever.
    const prefix = segments[0] === 'mobile' && TRACKED_ROUTES.has(segments[1]) ? segments[1] : null
    let route = '/mobile/*'
    if (prefix !== null) {
      route = `/mobile/${prefix}`
      if (prefix === 'sessions') {
        if (segments[3] === 'messages') route = '/mobile/sessions/*/messages'
        else if (segments[3] === 'images') route = '/mobile/sessions/*/images'
        else route = '/mobile/sessions'
      }
    }
    const key = `${method} ${route}`
    if (!this.requestCounts.has(key) && this.requestCounts.size >= MAX_TRACKED_ROUTE_KEYS) this.requestCounts.clear()
    this.requestCounts.set(key, (this.requestCounts.get(key) ?? 0) + 1)
  }

  /**
   * Answer the cheap liveness question: is this listener the build I think it is?
   *
   * `/mobile/status` answers what the host holds and costs a session list to do
   * it; this one only reads the plugin's own counters, which is what a phone
   * checking "is the PC still there, and is it new enough" needs on a bad link.
   *
   * @param req - the incoming request.
   * @param res - the response to write.
   */
  async routeHealth(req, res) {
    sendJson(res, 200, {
      ok: true,
      apiVersion: API_VERSION,
      pluginVersion: PLUGIN_VERSION,
      appVersion: typeof req.headers['x-dsh-mobile-app'] === 'string' ? req.headers['x-dsh-mobile-app'].slice(0, 40) : null,
      port: this.port,
      uptimeSeconds: Math.round((Date.now() - this.startedAt) / 1000),
      devices: this.devices.length,
      streams: this.streams.size,
      pendingApprovals: this.pendingApprovals.size,
      pendingQuestions: this.pendingQuestions.size,
    })
  }

  /**
   * Report listener, pairing, and session counts for the client's settings screen.
   * @param req - the incoming request.
   * @param res - the response to write.
   */
  async routeStatus(req, res) {
    const controller = this.ctx.get('sessionController')
    const current = this.deviceFor(req)
    let sessionCount = 0
    let liveCount = 0
    if (controller !== undefined) {
      const listed = await controller.list({}, this.signalFor(req))
      sessionCount = listed.items.length
      liveCount = listed.items.filter((item) => item.agentAvailable === true).length
    }
    sendJson(res, 200, {
      ok: true,
      apiVersion: API_VERSION,
      pluginVersion: PLUGIN_VERSION,
      host: this.config.host,
      port: this.port,
      urls: reachableUrls(this.port),
      devices: this.devices.map((device) => ({
        id: device.id,
        name: device.name,
        createdAt: device.createdAt,
        lastSeenAt: device.lastSeenAt,
        current: current !== null && device.id === current.id,
      })),
      pairingOpen: this.setupCode.length > 0,
      sessionCount,
      liveCount,
      requests: Object.fromEntries(this.requestCounts),
      uptimeSeconds: Math.round((Date.now() - this.startedAt) / 1000),
      version: 1,
    })
  }

  /**
   * List every session the harness knows, newest first.
   * @param req - the incoming request.
   * @param res - the response to write.
   * @param url - the parsed request URL.
   */
  async routeSessions(req, res, url) {
    const controller = this.controller()
    const signal = this.signalFor(req)
    const listed = await controller.list({}, signal)
    const includeSubagents = url.searchParams.get('subagents') === '1'
    const query = (url.searchParams.get('q') ?? '').trim().toLowerCase()
    // One workspace read serves the whole page. A host that mounts no
    // workspace controller yields null and the rows simply carry no group,
    // which the phone renders as the flat list it had before.
    const byPath = url.searchParams.get('workspaces') === '0' ? new Map() : await this.workspaceIndex(signal)
    const items = []
    for (const summary of listed.items) {
      if (!includeSubagents && summary.origin === 'subagent') continue
      const title = typeof summary.projections?.values?.title === 'string' ? summary.projections.values.title : null
      const workspace = typeof summary.cwd === 'string' ? byPath.get(pathKey(summary.cwd)) : undefined
      const record = {
        id: summary.sessionId,
        title,
        cwd: summary.cwd,
        workspaceId: workspace?.workspaceId ?? null,
        workspaceTitle: workspace?.title ?? null,
        running: summary.running === true,
        agentAvailable: summary.agentAvailable === true,
        blank: summary.blank === true,
        updatedAt: summary.updatedAt,
        lastPromptAt: summary.projections?.values?.sessionListMetadata?.lastPromptAt ?? null,
        parentSessionId: summary.parentSessionId,
        origin: summary.origin,
      }
      if (query.length > 0) {
        const haystack = `${record.title ?? ''} ${record.cwd ?? ''} ${record.workspaceTitle ?? ''} ${record.id}`.toLowerCase()
        if (!haystack.includes(query)) continue
      }
      this.seenSessionIds.add(record.id)
      items.push(record)
    }
    items.sort((left, right) => (right.updatedAt ?? 0) - (left.updatedAt ?? 0))
    await this.attachChatStatuses(items)
    sendJson(res, 200, { items })
  }

  /**
   * Read the complete Workspace projection from the host controller.
   *
   * Workspaces are how the PC groups chats into projects, so the phone shows
   * the same grouping instead of a flat list. The controller publishes a
   * reconnect-safe stream whose first frame is the complete baseline, which is
   * exactly the read this needs: attach, take the baseline, detach.
   *
   * @param signal - request lifetime; abort ends the stream read.
   * @returns every Workspace row, or null when the host mounts no controller.
   * @throws when the controller's stream fails.
   */
  async workspaceItems(signal) {
    const controller = this.ctx.get('workspaceController')
    if (controller === undefined) return null
    const stream = controller.follow(signal)
    let baseline
    try {
      for await (const frame of stream) {
        if (frame?.type === 'baseline') baseline = frame.value
        break
      }
    } finally {
      if (typeof stream.return === 'function') {
        try {
          await stream.return(undefined)
        } catch {
          // The generation is already settled; nothing to release.
        }
      }
    }
    if (baseline === undefined || !Array.isArray(baseline.items)) return []
    return baseline.items.map((item) => ({
      workspaceId: item.workspaceId,
      path: item.path,
      title: item.title,
      sessionIds: Array.isArray(item.sessionIds) ? item.sessionIds : [],
      createdAt: item.createdAt,
      updatedAt: item.updatedAt,
    }))
  }

  /**
   * Index Workspaces by canonical directory so a session can be placed in one.
   * @param signal - request lifetime.
   * @returns path key to workspace row, empty when the host groups nothing.
   */
  async workspaceIndex(signal) {
    const items = await this.workspaceItems(signal)
    const index = new Map()
    for (const item of items ?? []) index.set(pathKey(item.path), item)
    return index
  }

  /**
   * List the host's Workspaces, newest first as the registry orders them.
   * @param req - the incoming request.
   * @param res - the response to write.
   */
  async routeWorkspaces(req, res) {
    const items = await this.workspaceItems(this.signalFor(req))
    if (items === null) throw new HttpError(503, 'unavailable', 'this harness mounts no workspace controller')
    sendJson(res, 200, { items })
  }

  /**
   * Adopt one existing host directory as a Workspace.
   *
   * Idempotent by design: creating a Workspace for a directory that already
   * has one returns that record and reports `created: false`, so a phone that
   * lost track of the list converges instead of duplicating.
   *
   * @param req - the incoming request.
   * @param res - the response to write.
   */
  async routeCreateWorkspace(req, res) {
    const controller = this.ctx.get('workspaceController')
    if (controller === undefined) throw new HttpError(503, 'unavailable', 'this harness mounts no workspace controller')
    const body = await readJsonBody(req, { required: true })
    if (typeof body.path !== 'string' || body.path.trim().length === 0) {
      throw new HttpError(400, 'invalid-request', 'a host directory path is required')
    }
    const path = body.path.trim()
    if (!fullyQualified(path)) {
      throw new HttpError(400, 'invalid-request', `"${path}" is not a fully qualified path`)
    }
    const result = await controller.create({ path })
    sendJson(res, result.created ? 201 : 200, {
      workspaceId: result.workspace.workspaceId,
      path: result.workspace.path,
      title: result.workspace.title,
      sessionIds: result.workspace.sessionIds ?? [],
      created: result.created === true,
    })
  }

  /**
   * Fill in the status dot on one page of session rows.
   *
   * Three things happen here, and each is a decision rather than a step:
   *
   * - a running session is answered by the host's own flag, without touching the
   *   log at all: it is the one status that is about *now*;
   * - a blank session is `idle` for the same reason, without a read — a chat with
   *   no messages has no turn to have ended;
   * - everything else is read once per `updatedAt`, so a chat is re-read only when
   *   it has logged something, and at most [STATUS_READS_PER_REQUEST] rows are
   *   started per request, so a cold list is never as slow as its own worst case.
   *   A row that is not read yet simply arrives without a `status`: an absent dot
   *   claims nothing, and the next poll fills it in.
   *
   * @param items - the page's rows, in place.
   */
  async attachChatStatuses(items) {
    const toRead = []
    for (const item of items) {
      if (item.running === true) {
        item.status = 'running'
        continue
      }
      if (item.blank === true) {
        item.status = 'idle'
        continue
      }
      const cached = this.chatStatuses.get(item.id)
      if (cached !== undefined && cached.at === item.updatedAt) {
        item.status = cached.status
        continue
      }
      toRead.push(item)
    }
    await Promise.all(
      toRead.slice(0, STATUS_READS_PER_REQUEST).map(async (item) => {
        const status = await this.readChatStatus(item.id)
        if (status === null) return
        item.status = status
        this.rememberChatStatus(item.id, item.updatedAt, status)
      }),
    )
  }

  /**
   * Remember one chat status against the row it was read for.
   *
   * @param sessionId - the session.
   * @param updatedAt - the row's timestamp, which is the version of the fact.
   * @param status - the status read.
   */
  rememberChatStatus(sessionId, updatedAt, status) {
    this.chatStatuses.delete(sessionId)
    this.chatStatuses.set(sessionId, { at: updatedAt, status })
    while (this.chatStatuses.size > STATUS_CACHE_LIMIT) {
      this.chatStatuses.delete(this.chatStatuses.keys().next().value)
    }
  }

  /**
   * Read one chat's status from its newest durable event.
   *
   * The read is bounded by its own timeout rather than by the request that caused
   * it: the answer outlives the request in the cache, and a phone that hung up
   * mid-poll should not leave a half-read status behind.
   *
   * @param sessionId - the session to read.
   * @returns the status, or null when its history cannot be read at all.
   */
  async readChatStatus(sessionId) {
    const controller = this.controller()
    const signal = AbortSignal.timeout(STATUS_READ_TIMEOUT_MS)
    let stream
    try {
      stream = controller.follow({ address: { kind: 'session', sessionId }, maxMessages: 1 }, signal)
      for await (const frame of stream) {
        if (frame?.type !== 'snapshot') break
        const newest = frame.records?.[frame.records.length - 1]?.event
        return chatStatusFor(newest, false)
      }
      return 'idle'
    } catch (error) {
      // An unreadable history is not a failed chat: the row is served without a
      // dot, and the next poll tries again. Saying `failed` here would invent a
      // verdict from this plugin's own trouble reading.
      this.ctx.logger.warn(`mobile-sync: cannot read the last event of ${sessionId}: ${messageOf(error)}`)
      return null
    } finally {
      if (stream !== undefined && typeof stream.return === 'function') {
        try {
          await stream.return(undefined)
        } catch {
          // Already settled; nothing to release.
        }
      }
    }
  }

  /**
   * Remove one Workspace registration.
   *
   * Non-destructive by contract: the folder, its files, and the chat histories
   * are never touched — the project simply leaves the list and its chats become
   * ungrouped. That is what makes it safe to expose to a client that cannot
   * show a confirmation dialog about someone's filesystem.
   *
   * @param req - the incoming request.
   * @param res - the response to write.
   * @param workspaceId - the project to remove.
   */
  async routeDeleteWorkspace(req, res, workspaceId) {
    const controller = this.ctx.get('workspaceController')
    if (controller === undefined) throw new HttpError(503, 'unavailable', 'this harness mounts no workspace controller')
    await controller.delete({ workspaceId })
    sendJson(res, 200, { ok: true, deleted: true, workspaceId })
  }

  /**
   * Browse the PC's directories one level at a time.
   *
   * Two shapes on one route, because a phone needs both and they answer the
   * same question: `GET ?path=` returns one level (no path: the host account's
   * home, whose leading crumbs are the host's drive roots), and
   * `POST {parent, name}` creates one child and returns the parent's refreshed
   * level, so the client never has to re-ask what it just changed.
   *
   * @param req - the incoming request.
   * @param res - the response to write.
   * @param url - the parsed request URL.
   */
  async routeDirs(req, res, url) {
    if (req.method === 'POST') {
      const body = await readJsonBody(req, { required: true })
      if (typeof body.parent !== 'string' || body.parent.trim().length === 0) {
        throw new HttpError(400, 'invalid-request', 'a parent directory is required')
      }
      const created = await createDirectoryUnder(body.parent, body.name)
      sendJson(res, 201, { created, ...(await listDirectory(body.parent)) })
      return
    }
    sendJson(res, 200, await listDirectory(url.searchParams.get('path') ?? undefined))
  }

  /**
   * Create one new session so the phone can start a chat on the harness.
   * @param req - the incoming request.
   * @param res - the response to write.
   */
  async routeCreateSession(req, res) {
    const controller = this.controller()
    const body = await readJsonBody(req, { required: true })
    const request = {}
    const workspaceId = typeof body.workspaceId === 'string' ? body.workspaceId.trim() : ''
    if (workspaceId.length > 0) {
      // Creating *by project* is what makes the chat appear in the desktop
      // sidebar straight away: the controller attaches it to the project's own
      // session account. Creating by directory alone leaves the account alone,
      // so a chat the phone started there would stay invisible on the PC until
      // the next project mutation happened to prune the account.
      request.workspaceId = workspaceId
    } else if (typeof body.cwd === 'string' && body.cwd.trim().length > 0) {
      request.cwd = body.cwd.trim()
    }
    if (typeof body.agentPreset === 'string' && body.agentPreset.trim().length > 0) request.agentPreset = body.agentPreset.trim()
    const created = await controller.create(request)
    sendJson(res, 201, { sessionId: created.sessionId, agentPreset: created.agentPreset })
  }

  /**
   * Read one message-aligned history page, oldest first.
   *
   * The page cursor comes from the `follow` opening snapshot rather than from
   * a `page` call: `page` reports only the events inside its own bounded
   * window, so the newest message sequence it returns is not the log cursor
   * and using it as `throughSeq` truncates freshly appended turns.
   *
   * @param req - the incoming request.
   * @param res - the response to write.
   * @param sessionId - the addressed session.
   * @param url - the parsed request URL.
   */
  async routeMessages(req, res, sessionId, url) {
    const controller = this.controller()
    const signal = this.signalFor(req)
    const beforeParam = url.searchParams.get('beforeSeq')
    const beforeSeq = beforeParam === null ? undefined : Number.parseInt(beforeParam, 10)
    if (beforeSeq !== undefined && (!Number.isSafeInteger(beforeSeq) || beforeSeq < 0)) {
      throw new HttpError(400, 'invalid-request', 'beforeSeq must be a non-negative integer')
    }
    const maxParam = url.searchParams.get('limit')
    const maxMessages = maxParam === null ? DEFAULT_MAX_MESSAGES : Number.parseInt(maxParam, 10)
    if (!Number.isSafeInteger(maxMessages) || maxMessages <= 0 || maxMessages > 400) {
      throw new HttpError(400, 'invalid-request', 'limit must be an integer between 1 and 400')
    }

    const stream = controller.follow(
      { address: { kind: 'session', sessionId }, maxMessages },
      signal,
    )
    let snapshot
    try {
      for await (const frame of stream) {
        if (frame?.type === 'snapshot') snapshot = frame
        break
      }
    } finally {
      if (typeof stream.return === 'function') {
        try {
          await stream.return(undefined)
        } catch {
          // The stream is already settled; nothing to release.
        }
      }
    }
    if (snapshot === undefined) throw new HttpError(404, 'session/not-found', `session "${sessionId}" has no readable history`)

    const cursor = snapshot.cursor
    let records = snapshot.records
    let hasMore = snapshot.hasMore === true

    if (beforeSeq !== undefined) {
      const page = await controller.page(
        { address: { kind: 'session', sessionId }, throughSeq: cursor, maxMessages, beforeSeq },
        signal,
      )
      records = page.records
      hasMore = page.hasMore === true
    }

    const entries = []
    for (const record of records) {
      const entry = wireEvent(record.event)
      if (entry !== null) entries.push(entry)
    }
    sendJson(res, 200, { sessionId, cursor, hasMore, entries })
  }

  /**
   * Append one user prompt built from text, optional base64 photos, and
   * optional stored files.
   *
   * Photos arrive inline as canonical base64 image parts on purpose. The
   * harness admits them into its durable image store, and the model receives
   * image content. A staged file receipt would instead surface as a `file`
   * block — an opaque handle — which is the wrong modality for a photo.
   *
   * Files are the opposite case and are named, not carried: their bytes were
   * staged by `POST /mobile/files` for this same session, and the `file` part
   * repeats the receipt that upload minted. The session controller resolves the
   * receipt into the stored reference and the harness then hands the model one
   * handle line naming the saved read-only path — so a twenty-page PDF costs a
   * line of context instead of twenty pages of it. A receipt the controller
   * cannot resolve for *this* chat is refused, not guessed at.
   *
   * @param req - the incoming request.
   * @param res - the response to write.
   * @param sessionId - the addressed session.
   */
  async routeSend(req, res, sessionId) {
    const controller = this.controller()
    const body = await readJsonBody(req)
    const content = []
    if (typeof body.text === 'string' && body.text.trim().length > 0) {
      content.push({ type: 'text', text: body.text })
    }
    if (Array.isArray(body.images)) {
      if (body.images.length > 8) throw new HttpError(400, 'invalid-request', 'at most 8 images per message are accepted')
      for (const image of body.images) {
        const data = typeof image?.data === 'string' ? image.data : undefined
        const mediaType = typeof image?.mediaType === 'string' ? image.mediaType : undefined
        if (data === undefined) throw new HttpError(400, 'invalid-request', 'every image needs base64 data')
        if (mediaType === undefined || !IMAGE_MEDIA_TYPES.has(mediaType)) {
          throw new HttpError(400, 'invalid-request', `image mediaType must be one of ${[...IMAGE_MEDIA_TYPES].join(', ')}`)
        }
        if (Buffer.byteLength(data, 'base64') > MAX_IMAGE_BYTES) {
          throw new HttpError(413, 'image-too-large', `an image exceeds ${MAX_IMAGE_BYTES} bytes`)
        }
        content.push({
          type: 'image',
          mediaType,
          data,
          ...(typeof image.name === 'string' && image.name.length > 0 ? { name: image.name } : {}),
        })
      }
    }
    if (Array.isArray(body.files)) {
      if (body.files.length > MAX_FILES_PER_MESSAGE) {
        throw new HttpError(400, 'invalid-request', `at most ${MAX_FILES_PER_MESSAGE} files per message are accepted`)
      }
      for (const file of body.files) {
        const receiptId = typeof file?.receiptId === 'string' ? file.receiptId.trim() : ''
        // Only the receipt travels: it is opaque, single-use, and bound to the
        // receiving agent — the name and the bytes live in the stored
        // reference the controller resolves it into.
        if (receiptId.length === 0) {
          throw new HttpError(400, 'invalid-request', 'every file needs the receiptId its upload answered with')
        }
        content.push({ type: 'file', receiptId })
      }
    }
    if (content.length === 0) {
      throw new HttpError(400, 'invalid-request', 'a message needs non-whitespace text, at least one image, or at least one file')
    }
    // `prompt` declares a trailing AbortSignal that the RPC gateway normally
    // injects; calling the service directly means supplying it ourselves.
    await controller.prompt(
      {
        requestId: `mobile-${Date.now()}-${mintToken(6)}`,
        sessionId,
        mode: body.mode === 'steer' ? 'steer' : 'queue',
        content,
      },
      this.signalFor(req),
    )
    sendJson(res, 202, { accepted: true })
  }

  /**
   * Store one uploaded image and return its durable reference.
   *
   * This is an administrative companion to inline prompt photos: it exists so
   * a client can commit a photo once and learn its content-addressed id, which
   * is what the transcript reports back for an admitted image.
   *
   * @param req - the incoming request.
   * @param res - the response to write.
   */
  async routeUploadImage(req, res) {
    const attachments = this.ctx.get('attachments')
    if (attachments === undefined) throw new HttpError(503, 'unavailable', 'this harness mounts no attachment store')
    const body = await readJsonBody(req)
    const data = typeof body.data === 'string' ? body.data : undefined
    if (data === undefined) throw new HttpError(400, 'invalid-request', 'base64 image data is required')
    const bytes = decodeBase64(data)
    if (bytes.length === 0) throw new HttpError(400, 'invalid-request', 'the uploaded image is empty')
    if (bytes.length > MAX_IMAGE_BYTES) throw new HttpError(413, 'image-too-large', `an image exceeds ${MAX_IMAGE_BYTES} bytes`)
    const ref = await attachments.admitEncodedFile({
      data: bytes.toString('base64'),
      ...(typeof body.name === 'string' && body.name.length > 0 ? { name: body.name } : {}),
    })
    sendJson(res, 201, {
      attachmentId: ref.attachmentId,
      name: ref.name,
      bytes: ref.bytes,
      sha256: createHash('sha256').update(bytes).digest('hex'),
    })
  }

  /**
   * Stage one uploaded file for a session and return the receipt a prompt names.
   *
   * A generic file cannot be admitted the way a photo is. An image travels
   * inline in the prompt and is persisted during prompt admission; a `file`
   * part, by the session controller's own rule, carries a *receipt* — an opaque
   * handle minted when the bytes were stored for that one session — and a
   * reference it cannot resolve is refused outright (`FILE_NOT_STAGED`). So the
   * two steps are the harness's, not this plugin's invention: store first, then
   * send a prompt that names what was stored.
   *
   * `uploadStream` is the entry the desktop's own raw route uses. It resolves
   * the session itself — resuming a cold ordinary agent when it has to — stores
   * the bytes verbatim with no format whitelist and no size limit, and keys the
   * receipt to the receiving agent: a receipt cannot be spent on another chat.
   *
   * The bytes are the request body. The name and the session ride in the query,
   * because a header cannot carry every name a phone has a file for, and the
   * store reduces whatever arrives to a leaf name anyway.
   *
   * @param req - the incoming request, whose body is the file's exact bytes.
   * @param res - the response to write.
   * @param url - the parsed request URL; `sessionId` and `name` are its parameters.
   */
  async routeUploadFile(req, res, url) {
    const uploads = this.ctx.get('fileUploads')
    if (uploads === undefined) throw new HttpError(503, 'unavailable', 'this harness mounts no file upload service')
    const sessionId = (url.searchParams.get('sessionId') ?? '').trim()
    if (sessionId.length === 0) {
      throw new HttpError(400, 'invalid-request', 'a sessionId is required: a file is staged for one session')
    }
    const name = url.searchParams.get('name') ?? undefined
    // A client that declares its size gets the plain answer before a byte is
    // read; one that streams without declaring is stopped mid-body by
    // `boundedBody`, and that refusal is translated back below.
    const declared = Number.parseInt(req.headers['content-length'] ?? '', 10)
    if (Number.isSafeInteger(declared) && declared > MAX_FILE_BYTES) {
      throw new HttpError(413, 'file-too-large', `the upload exceeds ${MAX_FILE_BYTES} bytes`)
    }
    const refused = { tooLarge: false }
    let staged
    try {
      staged = await uploads.uploadStream({
        sessionId,
        data: boundedBody(req, MAX_FILE_BYTES, refused),
        // Deliberately no cancellation signal. The body *is* the upload, and
        // Node emits the request's `close` as soon as its last chunk has been
        // read — measured, not assumed — so handing the store that signal would
        // abort every upload that finished correctly.
        ...(name === undefined || name.length === 0 ? {} : { name }),
      })
    } catch (error) {
      if (refused.tooLarge) throw new HttpError(413, 'file-too-large', `the upload exceeds ${MAX_FILE_BYTES} bytes`)
      throw error
    }
    sendJson(res, 201, {
      receiptId: staged.receiptId,
      attachmentId: staged.file.attachmentId,
      name: staged.file.name,
      bytes: staged.file.bytes,
    })
  }

  /**
   * Serve one stored image either as raw bytes or as base64 JSON.
   * @param req - the incoming request.
   * @param res - the response to write.
   * @param sessionId - the session the client addressed, used to find the reference.
   * @param attachmentId - the durable attachment id.
   * @param raw - true to stream bytes, false to answer base64 JSON.
   */
  async routeImage(req, res, sessionId, attachmentId, raw) {
    const attachments = this.ctx.get('attachments')
    if (attachments === undefined) throw new HttpError(503, 'unavailable', 'this harness mounts no attachment store')
    const signal = this.signalFor(req)
    // The stored reference carries the recorded bytes and dimensions, and a
    // read verifies them; inventing one from the id alone would fail closed.
    // So the reference has to be found, and there are two places to look: the
    // live agents (cheap, and the only place a just-sent photo exists) and the
    // addressed session's own durable log (which is what makes history work
    // for a chat whose agent is not loaded).
    const ref = this.findImageRef(attachmentId) ?? (await this.findImageRefInSession(sessionId, attachmentId, signal))
    if (ref === undefined) throw new HttpError(404, 'not-found', 'this image is not referenced by any live session log')
    const stored = await attachments.readImage(ref, signal)
    if (raw) {
      res.writeHead(200, {
        'content-type': stored.ref.mediaType,
        'content-length': String(stored.data.byteLength),
        'cache-control': 'private, max-age=86400, immutable',
      })
      res.end(Buffer.from(stored.data))
      return
    }
    sendJson(res, 200, {
      attachmentId,
      mediaType: stored.ref.mediaType,
      width: stored.ref.width,
      height: stored.ref.height,
      data: Buffer.from(stored.data).toString('base64'),
    })
  }

  /**
   * Locate one stored image reference by scanning live agent logs.
   * @param attachmentId - the durable attachment id.
   * @returns the recorded reference, or undefined when nothing references it.
   */
  findImageRef(attachmentId) {
    const agents = this.ctx.get('agents')
    if (agents === undefined) return undefined
    const live = typeof agents.list === 'function' ? agents.list() : []
    for (const agent of live) {
      const session = agent?.session
      if (session === undefined) continue
      let messages
      try {
        messages = session.deriveMessages()
      } catch {
        continue
      }
      for (const message of messages) {
        const found = imageRefIn(message?.content, attachmentId)
        if (found !== undefined) return found
      }
    }
    return undefined
  }

  /**
   * Locate one stored image reference inside one session's durable log.
   *
   * This is the fallback that makes a photo visible after the agent that
   * received it has been unloaded — the ordinary state of a chat the user
   * reopened from the phone days later. Only the addressed session is read, so
   * the cost is one bounded snapshot rather than a walk over every session.
   *
   * The scan is bounded by the same ceiling the history route honours, so an
   * image older than the newest `MAX_HISTORY_MESSAGES` messages is not found
   * here; the truthful failure is a 404, not a wrong image.
   *
   * @param sessionId - the addressed session.
   * @param attachmentId - the durable attachment id.
   * @param signal - request lifetime; abort ends the stream read.
   * @returns the recorded reference, or undefined when this session never referenced it.
   */
  async findImageRefInSession(sessionId, attachmentId, signal) {
    const controller = this.ctx.get('sessionController')
    if (controller === undefined) return undefined
    const stream = controller.follow(
      { address: { kind: 'session', sessionId }, maxMessages: MAX_HISTORY_MESSAGES },
      signal,
    )
    let records
    try {
      for await (const frame of stream) {
        if (frame?.type === 'snapshot') records = frame.records
        break
      }
    } catch {
      // An unknown or unreadable session is not a different failure here: the
      // image simply was not found, and the caller answers 404 either way.
      return undefined
    } finally {
      if (typeof stream.return === 'function') {
        try {
          await stream.return(undefined)
        } catch {
          // The stream is already settled; nothing to release.
        }
      }
    }
    for (const record of records ?? []) {
      const found = imageRefIn(record?.event?.data?.message?.content, attachmentId)
      if (found !== undefined) return found
      const foundInPrompt = imageRefIn(record?.event?.data?.content, attachmentId)
      if (foundInPrompt !== undefined) return foundInPrompt
    }
    return undefined
  }

  /**
   * List the models this PC can answer with, and which one is the default.
   *
   * The list comes from the session controller's own catalog, which is the very
   * object the desktop picker renders, so the phone offers the same choices
   * instead of a second, drifting idea of what is configured.
   *
   * The catalog deliberately carries no capability data — no context window and
   * no vision flag — so each model is enriched from the LLM registry. That
   * lookup is per-model and can be slow, hence the cap and the best-effort
   * failure: a model whose details cannot be resolved is still selectable.
   *
   * @param req - the incoming request.
   * @param res - the response to write.
   */
  async routeModels(req, res) {
    const controller = this.controller()
    const catalog = await controller.modelCatalog()
    const llm = this.ctx.get('llm')
    const groups = Array.isArray(catalog?.groups) ? catalog.groups : []
    const flat = []
    for (const group of groups) {
      for (const model of Array.isArray(group?.models) ? group.models : []) flat.push({ group, model })
    }
    const details = new Map()
    if (llm !== undefined && typeof llm.resolveModelInfo === 'function' && flat.length > 0 && flat.length <= MAX_ENRICHED_MODELS) {
      await Promise.all(
        flat.map(async ({ group, model }) => {
          try {
            const info = await llm.resolveModelInfo(group.id, model.id)
            if (info !== null && typeof info === 'object') details.set(`${group.id}\u0000${model.id}`, info)
          } catch {
            // A model whose adapter cannot describe it stays in the list with
            // no capability badges rather than disappearing from the picker.
          }
        }),
      )
    }
    const items = groups.map((group) => ({
      id: group.id,
      name: typeof group.name === 'string' ? group.name : group.id,
      models: (Array.isArray(group?.models) ? group.models : []).map((model) => {
        const info = details.get(`${group.id}\u0000${model.id}`)
        return {
          provider: group.id,
          id: model.id,
          name: typeof model.name === 'string' && model.name.length > 0 ? model.name : model.id,
          description: typeof model.description === 'string' ? model.description : null,
          // The picker shows a reasoning-effort chooser only for a model that
          // declares efforts, so this must stay absent rather than empty.
          reasoning: model.reasoning ?? info?.reasoning ?? null,
          vision: Array.isArray(info?.inputModalities) ? info.inputModalities.includes('image') : null,
          contextWindow: typeof info?.context?.contextWindow === 'number' ? info.context.contextWindow : null,
          maxTokens: typeof info?.defaultMaxTokens === 'number' ? info.defaultMaxTokens : null,
        }
      }),
    }))
    sendJson(res, 200, {
      default: catalog?.default ?? null,
      routableProviders: Array.isArray(catalog?.routableProviders) ? catalog.routableProviders : [],
      groups: items,
      failures: Array.isArray(catalog?.failures) ? catalog.failures : [],
    })
  }

  /**
   * Choose the model one session uses from its next request onward.
   *
   * This is the same call the desktop picker makes, including its side effect:
   * the harness records the choice as the deployment default as well as the
   * session's own selection. The phone therefore changes the PC's default too,
   * which the app says out loud rather than hiding.
   *
   * A switch does not disturb a step already running: the harness applies the
   * new route to the next request and notes the change in the transcript.
   *
   * @param req - the incoming request.
   * @param res - the response to write.
   */
  async routeSelectModel(req, res) {
    const controller = this.controller()
    const body = await readJsonBody(req, { required: true })
    const sessionId = typeof body.sessionId === 'string' ? body.sessionId.trim() : ''
    const provider = typeof body.provider === 'string' ? body.provider.trim() : ''
    const model = typeof body.model === 'string' ? body.model.trim() : ''
    if (sessionId.length === 0 || provider.length === 0 || model.length === 0) {
      throw new HttpError(400, 'invalid-request', 'sessionId, provider and model are required')
    }
    const selection = { sessionId, provider, model }
    const effort = typeof body.reasoningEffort === 'string' ? body.reasoningEffort.trim() : ''
    if (effort.length > 0) selection.reasoningEffort = effort
    const result = await controller.selectModel(selection)
    const selected = result?.selected ?? { provider, model }
    this.ctx.logger.info(`mobile-sync: session ${sessionId} switched to ${selected.provider}/${selected.model}`)
    sendJson(res, 200, { selected })
  }

  /**
   * List the approvals currently waiting for an answer.
   *
   * A phone polls, so it cannot rely on catching the stream frame that
   * announced a request: this is the read that makes the card survive a
   * reconnect, a backgrounded app, or a missed frame.
   *
   * @param req - the incoming request.
   * @param res - the response to write.
   */
  async routeApprovals(req, res) {
    sendJson(res, 200, { items: [...this.pendingApprovals.values()].map((record) => this.wireApproval(record)) })
  }

  /**
   * Answer one pending approval.
   *
   * The body carries one of the harness's own two outcomes. Anything else is
   * refused rather than coerced, because the vocabulary is fail-closed: only
   * `allowed-once` grants anything, and a typo must never be read as consent.
   *
   * An approval that was already settled — by this phone, by another one, or by
   * the desktop — answers 409 rather than 500: losing that race is normal.
   *
   * @param req - the incoming request.
   * @param res - the response to write.
   * @param approvalId - the approval the client is answering.
   */
  async routeAnswerApproval(req, res, approvalId) {
    const body = await readJsonBody(req, { required: true })
    const outcome = typeof body.outcome === 'string' ? body.outcome.trim() : ''
    if (!APPROVAL_OUTCOMES.has(outcome)) {
      throw new HttpError(400, 'invalid-request', `outcome must be one of ${[...APPROVAL_OUTCOMES].join(', ')}`)
    }
    const record = this.pendingApprovals.get(approvalId)
    if (record === undefined || record.settled) {
      throw new HttpError(409, 'approval/settled', 'this approval has already been settled')
    }
    record.resolve(outcome)
    sendJson(res, 200, { resolved: true, approvalId, outcome })
  }

  /**
   * List the questions the harness is waiting on.
   *
   * Like the approval list, this is the read that makes a question survive a
   * reconnect or a backgrounded app: a phone polls, and a card that only existed
   * in an event frame would vanish whenever the app was not looking.
   *
   * @param req - the incoming request.
   * @param res - the response to write.
   */
  async routeQuestions(req, res) {
    sendJson(res, 200, { items: this.questionItems() })
  }

  /**
   * Answer one question set.
   *
   * Two channels, in order. While the question is live, resolving the pending
   * record settles the waterfall the tool is waiting on. After the harness's own
   * two-minute window has closed the question is `continued`, and the only way in
   * is the service's own `answer` — which takes the live Agent, not the session
   * id, because it has to reach into that agent's inbox.
   *
   * @param req - the incoming request.
   * @param res - the response to write.
   * @param questionId - the question set the client is answering.
   */
  async routeAnswerQuestion(req, res, questionId) {
    const body = await readJsonBody(req, { required: true })
    const answers = validAnswers(body.answers)
    if (answers === null) {
      throw new HttpError(400, 'invalid-request', 'answers must be a non-empty array of { id, selected: [] }')
    }
    const record = this.pendingQuestions.get(questionId)
    if (record !== undefined && !record.settled) {
      record.resolve({ answers })
      sendJson(res, 200, { resolved: true, questionId, answers, late: false })
      return
    }

    const expired = this.expiredQuestions.get(questionId)
    if (expired === undefined) {
      throw new HttpError(409, 'question/settled', 'this question has already been settled')
    }
    const service = this.ctx.get('userQuestions')
    if (service === undefined || typeof service.answer !== 'function') {
      throw new HttpError(503, 'unavailable', 'this harness mounts no user-question service')
    }
    const agent = this.liveAgentFor(expired.sessionId)
    if (agent === undefined) {
      // The question survives, but the agent that asked it does not, so there is
      // nobody to hand the answer to.
      this.expiredQuestions.delete(questionId)
      throw new HttpError(409, 'question/settled', 'the session that asked this question is no longer live')
    }
    let accepted
    try {
      accepted = await service.answer(agent, expired.callId, { answers })
    } catch (error) {
      if (error?.code === 'REPLY_QUEUED') {
        this.expiredQuestions.delete(questionId)
        throw new HttpError(409, 'question/settled', 'a reply is already queued for this question')
      }
      throw error
    }
    if (accepted !== true) {
      // `false` is what the service returns for a question it no longer holds,
      // rather than an error — so it has to be turned into one here.
      this.expiredQuestions.delete(questionId)
      throw new HttpError(409, 'question/settled', 'the harness no longer accepts an answer for this question')
    }
    this.expiredQuestions.delete(questionId)
    sendJson(res, 200, { resolved: true, questionId, answers, late: true })
  }

  /**
   * Find the live agent serving one session.
   *
   * @param sessionId - the session to look for.
   * @returns the agent, or undefined when that session has no loaded agent.
   */
  liveAgentFor(sessionId) {
    const agents = this.ctx.get('agents')
    if (agents === undefined || typeof agents.list !== 'function') return undefined
    return agents.list().find((agent) => agent?.session?.id === sessionId)
  }

  /**
   * Read the permission presets one session may be switched to.
   *
   * The catalog is the host's own table, so the phone offers exactly the presets
   * this deployment configured — including any a deployment invented — rather
   * than a hardcoded idea of what exists. The sandbox and approval knobs are
   * filled in from `resolve`, because the catalog itself carries only the label:
   * without them the phone could offer "Full access" without being able to say
   * what it does, and the whole point of showing the choice is that its
   * consequence is legible.
   *
   * @param req - the incoming request.
   * @param res - the response to write.
   * @param url - the parsed request URL, whose `sessionId` names the session.
   */
  async routePermissions(req, res, url) {
    const service = this.permissionService()
    const sessionId = url.searchParams.get('sessionId')
    const catalog = service.catalog()
    const session = this.liveSessionFor(sessionId)
    sendJson(res, 200, {
      sessionId: session === undefined ? null : session.id,
      // Read separately from the catalog because it is per session, and null
      // when there is no live session to ask about — an unknown current value is
      // not the same as a preset named "null".
      current: session === undefined ? null : this.currentPermission(service, session),
      defaultPreset: typeof catalog?.defaultPreset === 'string' ? catalog.defaultPreset : null,
      options: wirePresetOptions(service, catalog),
    })
  }

  /**
   * Switch one session's permission preset.
   *
   * This is the one route in this plugin that changes what the agent is allowed
   * to do rather than what it is doing, and the two knobs it moves are the
   * sandbox mode and the approval policy — so `full access` here means an agent
   * that no longer asks before changing files or running commands. Two
   * consequences follow, and both are deliberate:
   *
   * - the preset is validated against the host's own catalog rather than
   *   forwarded, because `set` is the call that hands out that ability and a
   *   typo must not become a permission;
   * - the switch is recorded: `set` appends a `permission/preset` entry to the
   *   session log, so a change made from a phone is auditable on the PC instead
   *   of invisible.
   *
   * @param req - the incoming request.
   * @param res - the response to write.
   */
  async routeSelectPermission(req, res) {
    const service = this.permissionService()
    const body = await readJsonBody(req, { required: true })
    const sessionId = typeof body.sessionId === 'string' ? body.sessionId : ''
    const preset = typeof body.preset === 'string' ? body.preset : ''
    if (sessionId.length === 0 || preset.length === 0) {
      throw new HttpError(400, 'invalid-request', 'sessionId and preset are both required')
    }
    const names = Array.isArray(service.names) ? service.names : []
    if (!names.includes(preset)) {
      throw new HttpError(400, 'permission/unknown', `this harness has no preset named "${preset}"`)
    }
    const session = this.liveSessionFor(sessionId)
    if (session === undefined) {
      throw new HttpError(404, 'permission/no-session', 'that session is not loaded on this harness')
    }
    try {
      service.set(session, preset)
    } catch (error) {
      // Switching to Auto runs an admission check that can refuse; the host's
      // own words are more useful than a generic failure.
      throw new HttpError(409, 'permission/refused', error?.message ?? 'the harness refused the switch')
    }
    const current = this.currentPermission(service, session)
    this.broadcast({ event: 'permission-changed', sessionId, preset: current })
    this.ctx.logger.info(`mobile-sync: session ${sessionId} switched to permission preset ${current}`)
    sendJson(res, 200, { sessionId, current })
  }

  /**
   * The permission-preset service, when this deployment mounts one.
   *
   * A deployment without it offers no selector at all, which is a fact worth
   * reporting rather than a failure to hide: the app says the host has no
   * presets instead of showing an empty picker.
   *
   * @returns the service.
   */
  permissionService() {
    const service = this.ctx.get('permissionPresets')
    if (
      service === undefined ||
      typeof service.catalog !== 'function' ||
      typeof service.set !== 'function' ||
      typeof service.current !== 'function'
    ) {
      throw new HttpError(503, 'permission/unavailable', 'this harness mounts no permission presets')
    }
    return service
  }

  /**
   * Read one session's preset, tolerating a session the service cannot derive it for.
   *
   * @param service - the permission-preset service.
   * @param session - the session to ask about.
   * @returns the preset name, or null when the host cannot derive one.
   */
  currentPermission(service, session) {
    try {
      return service.current(session)
    } catch {
      // A session the service never pinned has no permissions projection; that
      // is a readable "unknown", not a reason to fail the whole listing.
      return null
    }
  }

  /**
   * Find the session a client is asking about.
   *
   * The preset service itself reads from `ctx.sessions`, so a session taken from
   * there is the same object it pins and switches.
   *
   * @param sessionId - the session id from the client, if any.
   * @returns the session, or undefined when it is not loaded.
   */
  liveSessionFor(sessionId) {
    if (typeof sessionId !== 'string' || sessionId.length === 0) return undefined
    const sessions = this.ctx.get('sessions')
    if (sessions === undefined || typeof sessions.list !== 'function') return undefined
    return sessions.list().find((session) => session?.id === sessionId)
  }

  /**
   * The harness's web seam, which a fetch provider must have mounted.
   *
   * This is the whole reason the preview runs here: the seam is where the
   * harness's own fetch policy lives — public destinations only, DNS pinned so
   * a re-resolution cannot reach somewhere else, byte and time caps, no
   * credentials. Writing a second fetcher for the phone would duplicate none of
   * that and forget one of it.
   *
   * @returns the web service.
   */
  webService() {
    const service = this.ctx.get('web')
    if (service === undefined || typeof service.fetch !== 'function') {
      throw new HttpError(503, 'preview/unavailable', 'this harness mounts no web fetch provider')
    }
    return service
  }

  /**
   * Serve one file an agent linked, as its text.
   *
   * A chat is full of references to files the agent just wrote or read —
   * `[LORE-bozza.md](LORE-bozza.md)` is what that looks like on the wire — and a
   * phone that can only send those to the web preview gets "not a valid URL: …"
   * for a file sitting on the same disk as the harness. Reading it here is the
   * other half of the preview route: the *page* a link names is fetched under the
   * host's web policy, and the *file* a link names is read under the host's own
   * path rules, with the chat's folder as the base the agent meant.
   *
   * Only text is served. Deliberately not a file browser: `/mobile/dirs` already
   * enumerates directories, so this route owns exactly one question — "what does
   * this file say?" — and answers it bounded.
   *
   * `line` carries the spot a `#L35` fragment pointed at, when there was one: the
   * reader still reads the whole file here, but saying which line the link meant
   * is the difference between honouring the link and quietly dropping half of it.
   *
   * @param req - the incoming request.
   * @param res - the response to write.
   * @param url - the parsed request URL, carrying `ref` and the chat's `session`.
   */
  async routeFile(req, res, url) {
    const reference = url.searchParams.get('ref')
    const cwd = await this.sessionCwd(url.searchParams.get('session'))
    const target = resolveFileReference(reference, cwd)
    this.assertPhoneReadable(target)
    const line = fragmentLine(splitFileFragment(reference).fragment)
    sendJson(res, 200, { ...(await readTextFile(target)), line })
  }

  /**
   * Refuse a path a phone may not read, however it spelled it.
   *
   * The route exists so a link an *agent printed* opens on the phone, and the
   * reference is taken as given — which also means it can name any file the
   * harness account can read. Two of those are worth more than the whole
   * conversation: the state file holds every paired device's token, and the
   * harness home holds provider credentials. A phone is trusted to drive its own
   * agent; it is not trusted to mint its siblings' credentials.
   *
   * @param target - the absolute path the route resolved.
   * @throws HttpError 403 `file/forbidden` for the harness's own installation.
   */
  assertPhoneReadable(target) {
    const home = dshHome()
    const extra = Array.isArray(this.config?.protectedPaths) ? this.config.protectedPaths : []
    const forbidden = [home, this.configPath, ...extra.filter((entry) => typeof entry === 'string' && entry.length > 0)]
    const name = baseNameOf(target).toLowerCase()
    const looksLikeCredentials = name.startsWith('.credentials') || name === 'credentials.yaml' || name === 'credentials.yml'
    if (looksLikeCredentials || forbidden.some((root) => pathIsInside(resolve(target), resolve(root)))) {
      throw new HttpError(403, 'file/forbidden', 'that file belongs to the harness itself and is not readable from a phone')
    }
  }

  /**
   * The folder of one chat, for resolving the relative paths that chat printed.
   *
   * @param sessionId - the session the phone is looking at, if it said which.
   * @returns the session's working directory, or null when there is none to use.
   */
  async sessionCwd(sessionId) {
    if (typeof sessionId !== 'string' || sessionId.length === 0) return null
    const controller = this.ctx.get('sessionController')
    if (controller === undefined || typeof controller.list !== 'function') return null
    const listed = await controller.list({}, undefined)
    const found = (listed?.items ?? []).find((item) => item?.sessionId === sessionId)
    return typeof found?.cwd === 'string' && found.cwd.length > 0 ? found.cwd : null
  }

  /**
   * Serve one file an agent *delivered*, addressed by its declaration.
   *
   * `present` is the one tool whose output is a file the user is meant to open,
   * and on the desktop that file opens natively from the card that names it. A
   * phone has no such shortcut, so it needs the two things a download needs: the
   * file's description, and then its bytes.
   *
   * The address is a *coordinate*, not a path: the chat, the sequence of the
   * `deliverables/presented` event, and the file's index inside that event. The
   * desktop's own native-open route takes the same three, and for the same
   * reason — the path then comes out of the harness's log rather than out of the
   * client's request, so a phone cannot ask this route for a file no agent
   * declared, and a declaration read long ago still resolves to what the log
   * says now. A relative path in the declaration is resolved against the chat's
   * own folder, exactly as a file link is.
   *
   * `raw=1` answers the bytes; without it the answer is the JSON a card needs to
   * decide between showing the file and offering to download it.
   *
   * @param req - the incoming request.
   * @param res - the response to write.
   * @param url - the parsed request URL, carrying `session`, `seq`, `index`, `raw`.
   */
  async routeDelivery(req, res, url) {
    const sessionId = (url.searchParams.get('session') ?? '').trim()
    if (sessionId.length === 0) {
      throw new HttpError(400, 'invalid-request', 'a session is required: a delivery belongs to one chat')
    }
    const seq = Number.parseInt(url.searchParams.get('seq') ?? '', 10)
    if (!Number.isSafeInteger(seq) || seq < 0) {
      throw new HttpError(400, 'invalid-request', 'seq must be a non-negative integer')
    }
    const index = Number.parseInt(url.searchParams.get('index') ?? '', 10)
    if (!Number.isSafeInteger(index) || index < 0) {
      throw new HttpError(400, 'invalid-request', 'index must be a non-negative integer')
    }

    const signal = this.signalFor(req)
    const declared = await this.findDelivery(sessionId, seq, index, signal)
    if (declared === undefined) {
      throw new HttpError(404, 'delivery/not-found', `chat "${sessionId}" declares no delivered file at seq ${seq}, index ${index}`)
    }
    const cwd = await this.sessionCwd(sessionId)
    const target = resolveFileReference(declared.path, cwd)

    if (url.searchParams.get('raw') === '1') {
      await this.sendDeliveryBytes(res, target)
      return
    }
    const file = await readDeliveryFile(target)
    sendJson(res, 200, declared.description === undefined ? file : { ...file, description: declared.description })
  }

  /**
   * Stream one delivered file's exact bytes to the phone, as an attachment.
   *
   * `application/octet-stream` rather than a media type: this plugin owns no
   * media-type table, and the honest generic type plus the file's own name lets
   * the phone decide with the table it already has which app should open it. The
   * image route carries a real type because the *store* recorded one; guessing
   * here would be that same table maintained in the wrong place, where a wrong
   * guess is worse than saying nothing.
   *
   * @param res - the response to stream on.
   * @param target - an absolute path this host resolved itself.
   * @throws HttpError 404 `file/not-found` when the file is gone.
   * @throws HttpError 400 `file/not-a-file` for a directory or a device.
   * @throws HttpError 413 `file/too-large` past the download bound.
   */
  async sendDeliveryBytes(res, target) {
    let info
    try {
      info = await stat(target)
    } catch (error) {
      throw new HttpError(404, 'file/not-found', `cannot read ${target}: ${messageOf(error)}`)
    }
    if (info.isDirectory()) throw new HttpError(400, 'file/not-a-file', `${target} is a directory, not a file`)
    if (!info.isFile()) throw new HttpError(400, 'file/not-a-file', `${target} is not a regular file`)
    if (info.size > FILE_DOWNLOAD_LIMIT) {
      throw new HttpError(413, 'file/too-large', `${baseNameOf(target)} is ${info.size} bytes, past the ${FILE_DOWNLOAD_LIMIT}-byte download bound`)
    }
    const name = baseNameOf(target)
    res.writeHead(200, {
      'content-type': 'application/octet-stream',
      'content-length': String(info.size),
      'content-disposition': `attachment; filename="${asciiFileName(name)}"; filename*=UTF-8''${encodeURIComponent(name)}`,
      'cache-control': 'no-store',
    })
    await new Promise((resolve) => {
      const stream = createReadStream(target)
      // A read that fails midway cannot become a failure answer: the status line
      // and the length are already on the wire. The socket is torn down instead,
      // which is how a client learns the body it received is short.
      const finish = () => {
        stream.destroy()
        resolve()
      }
      stream.once('error', () => {
        res.destroy()
        finish()
      })
      res.once('finish', finish)
      res.once('close', finish)
      stream.pipe(res)
    })
  }

  /**
   * Locate one declared delivery inside one chat's durable log.
   *
   * Reading the session is the point rather than an overhead: the declaration is
   * the harness's and the log is where it lives. The scan is the bounded one the
   * history and image routes already use — the opening snapshot covers the newest
   * [MAX_HISTORY_MESSAGES] messages — so a delivery older than that answers 404
   * rather than resolving to whatever now sits at that sequence.
   *
   * @param sessionId - the addressed chat.
   * @param seq - the sequence of the `deliverables/presented` event.
   * @param index - the file's position in that event's own array.
   * @param signal - request lifetime; abort ends the stream read.
   * @returns the declaration, or undefined when this chat holds none there.
   */
  async findDelivery(sessionId, seq, index, signal) {
    const controller = this.ctx.get('sessionController')
    if (controller === undefined) return undefined
    const stream = controller.follow(
      { address: { kind: 'session', sessionId }, maxMessages: MAX_HISTORY_MESSAGES },
      signal,
    )
    let records
    try {
      for await (const frame of stream) {
        if (frame?.type === 'snapshot') records = frame.records
        break
      }
    } catch {
      // An unknown or unreadable chat is not a different failure here: no
      // declaration was found, and the caller answers 404 either way.
      return undefined
    } finally {
      if (typeof stream.return === 'function') {
        try {
          await stream.return(undefined)
        } catch {
          // The stream is already settled; nothing to release.
        }
      }
    }
    for (const record of records ?? []) {
      const event = record?.event
      if (event?.seq !== seq || event?.type !== 'deliverables/presented') continue
      const declared = Array.isArray(event.data?.files) ? event.data.files[index] : undefined
      return declaredFile(declared) ?? undefined
    }
    return undefined
  }

  /**
   * Serve one link preview: the page fetched on this host, reduced to what a
   * phone reads.
   *
   * The phone could fetch many of these itself; running it here is not a
   * workaround but the point. The harness's fetch is the one the agent's own
   * `web_fetch` uses, so a link is previewed under exactly the policy the host
   * already chose, and the phone is never a second, softer entrance to the
   * network this machine sits on.
   *
   * @param req - the incoming request.
   * @param res - the response to write.
   * @param url - the parsed request URL, carrying the `url` parameter.
   */
  async routePreview(req, res, url) {
    // The seam is consulted before the cache: whether previews exist is the
    // host's to say, and a remembered page must not answer for a deployment
    // that has just stopped offering them.
    const service = this.webService()
    const requested = normalizePreviewUrl(url.searchParams.get('url'))
    const cached = this.previewCache.get(requested)
    if (cached !== undefined && cached.expiresAt > Date.now()) {
      sendJson(res, 200, cached.payload)
      return
    }
    let result
    try {
      result = await service.fetch({ url: requested })
    } catch (error) {
      throw previewHttpError(error)
    }
    const payload = buildPreviewPayload(requested, result)
    this.rememberPreview(requested, payload)
    sendJson(res, 200, payload)
  }

  /**
   * Keep one preview answerable for a while, newest-first, bounded.
   *
   * @param url - the canonical URL it was fetched under.
   * @param payload - the wire payload to serve again.
   */
  rememberPreview(url, payload) {
    const cache = this.previewCache
    cache.delete(url)
    cache.set(url, { expiresAt: Date.now() + PREVIEW_CACHE_TTL_MS, payload })
    while (cache.size > PREVIEW_CACHE_LIMIT) {
      cache.delete(cache.keys().next().value)
    }
  }

  /**
   * Hold one Server-Sent-Events connection open for the phone's live feed.
   * @param req - the incoming request.
   * @param res - the response to stream frames on.
   */
  routeEvents(req, res) {
    res.writeHead(200, {
      'content-type': 'text/event-stream; charset=utf-8',
      'cache-control': 'no-store, no-transform',
      connection: 'keep-alive',
      'x-accel-buffering': 'no',
    })
    res.write(`retry: 3000\n\n`)
    res.write(`data: ${JSON.stringify({ event: 'ready', port: this.port })}\n\n`)
    this.streams.add(res)
    const heartbeat = setInterval(() => {
      try {
        if (res.writableLength > SSE_MAX_BUFFER_BYTES) {
          clearInterval(heartbeat)
          this.dropStream(res)
          return
        }
        res.write(`: keep-alive\n\n`)
      } catch {
        clearInterval(heartbeat)
        this.dropStream(res)
      }
    }, SSE_HEARTBEAT_MS)
    // A heartbeat must not be the reason the harness process stays alive after
    // the plugin is unloaded.
    heartbeat.unref?.()
    const cleanup = () => {
      clearInterval(heartbeat)
      this.streams.delete(res)
    }
    req.once('close', cleanup)
    res.once('close', cleanup)
    res.once('error', cleanup)
    req.once('error', cleanup)
  }
}

/** Cordis plugin row: services this plugin needs before it activates. */
export const inject = ['sessions']

/**
 * Cordis plugin entry.
 * @param ctx - the host context.
 * @param config - user configuration for the listener.
 */
export function apply(ctx, config) {
  ctx.plugin(MobileSync, config)
}

export { MobileSync }
