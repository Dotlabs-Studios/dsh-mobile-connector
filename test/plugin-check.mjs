/**
 * Offline verification for dsh-plugin-mobile-sync.
 *
 * Boots the plugin against a stand-in Cordis context that exposes the same
 * service surface the harness provides, then drives the real HTTP routes over
 * a loopback socket. Catches routing, auth, body handling, and wire-shape
 * regressions without restarting the desktop application.
 */

import { strict as assert } from 'node:assert'
import { mkdir, readFile, readdir, rm, writeFile } from 'node:fs/promises'
import * as path from 'node:path'
import { dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

// The suite asserts the pairing lifecycle, which depends on starting from a
// clean state file: a leftover device or spent code changes what is under
// test. Clearing it here keeps the suite self-contained instead of relying on
// the caller to remember.
const RUNTIME = fileURLToPath(new URL('./_runtime/', import.meta.url))
const RUNTIME_FRESH = fileURLToPath(new URL('./_runtime-fresh/', import.meta.url))
await rm(RUNTIME, { recursive: true, force: true })
await rm(RUNTIME_FRESH, { recursive: true, force: true })

process.env.DSH_HOME = RUNTIME
const { MobileSync, boundedBody } = await import('../dsh-plugin-mobile-sync/lib/index.js')

/** Minimal logger so the plugin's info/warn calls stay visible but tidy. */
const logger = {
  info: (message) => console.log('[info]', message),
  warn: (message) => console.log('[warn]', message),
  error: (message) => console.log('[error]', message),
}

const listeners = new Map()
/** Events a plugin registered *ahead* of whatever was already listening. */
const prependedEvents = new Set()
const effects = []

/** Host record of a new workspace, as `ctx.workspaceController.create` reports it. */
const createdWorkspaces = []

/** A workspace stand-in shaped like the controller's projection rows. */
function workspaceRow(id, path, title, sessionIds, updatedAt) {
  return { workspaceId: id, path, title, sessionIds, createdAt: '2026-10-01T00:00:00.000Z', updatedAt: updatedAt ?? '2026-10-02T00:00:00.000Z' }
}

/** Workspaces the stand-in host already groups, in registry display order. */
const workspaces = [
  workspaceRow('ws-acme', 'K:\\Repository\\Acme', 'Acme', ['session-aaaa', 'session-child']),
  workspaceRow('ws-default', 'H:\\Documenti\\deepseek-harness\\default-workspace', 'default-workspace', []),
]

/** How many times a workspace stream was detached without being drained. */
let workspaceStreamsDetached = 0

const workspaceController = {
  /**
   * Stream the baseline then increments. The plugin only needs the opening
   * frame; this reports whether that frame was detached cleanly so the test can
   * assert the stream is not leaked.
   */
  follow(signal) {
    const items = workspaces.map((item) => ({ ...item }))
    const stream = {
      async *[Symbol.asyncIterator]() {
        signal?.throwIfAborted()
        yield { type: 'baseline', value: { items, archivedSessionIds: [] } }
        await new Promise((resolve) => setTimeout(resolve, 50))
        yield { type: 'upsert', workspace: items[0] }
      },
      async return() {
        workspaceStreamsDetached += 1
      },
    }
    return stream
  },
  async create(request) {
    createdWorkspaces.push(request)
    if (request.path === 'C:\\Non\\Esiste') {
      const error = new Error(`cannot use "${request.path}" as a workspace`)
      error.code = 'workspace/invalid-path'
      throw error
    }
    const existing = workspaces.find((item) => item.path.toLowerCase() === request.path.toLowerCase())
    if (existing !== undefined) return { workspace: { ...existing }, created: false }
    const row = workspaceRow('ws-new', request.path, 'Nuovo', [])
    workspaces.unshift(row)
    return { workspace: { ...row }, created: true }
  },
  async delete(request) {
    if (request.workspaceId === 'ws-unknown') {
      const error = new Error(`workspace "${request.workspaceId}" is not registered`)
      error.code = 'workspace/not-found'
      throw error
    }
    deletedWorkspaces.push(request.workspaceId)
    return { deleted: true }
  },
}

/** Workspace ids the stand-in host was asked to unregister. */
const deletedWorkspaces = []

/** A session log realistic enough to exercise wireEvent. */
const sessionEvents = [
  { seq: 0, type: 'turn/start', time: 1_760_000_000_000, data: { turn: 1 } },
  { seq: 1, type: 'user/message', time: 1_760_000_000_100, data: { role: 'user', content: [{ type: 'text', text: 'ciao dal pc' }] } },
  { seq: 2, type: 'step/start', time: 1_760_000_000_200, data: { turn: 1, step: 1 } },
  { seq: 3, type: 'tool/call', time: 1_760_000_000_300, data: { turn: 1, step: 1, callId: 'call-1', name: 'pwsh', arguments: '{"command":"ls"}' } },
  {
    seq: 4,
    type: 'tool/result',
    time: 1_760_000_000_400,
    data: { turn: 1, step: 1, message: { role: 'tool', toolCallId: 'call-1', name: 'pwsh', content: [{ type: 'text', text: 'file.txt' }] } },
  },
  { seq: 5, type: 'step/end', time: 1_760_000_000_500, data: { turn: 1, step: 1 } },
  {
    seq: 6,
    type: 'assistant/message',
    time: 1_760_000_000_600,
    data: {
      turn: 1,
      step: 1,
      message: { role: 'assistant', content: [{ type: 'text', text: 'ecco il file' }, { type: 'reasoning', text: 'penso' }] },
      stream: [],
    },
  },
  {
    seq: 7,
    type: 'user/message',
    time: 1_760_000_000_700,
    data: {
      role: 'user',
      content: [{ type: 'text', text: 'guarda questa foto' }, { type: 'image', attachment: { attachmentId: 'att-9', mediaType: 'image/png', width: 4, height: 4, bytes: 68, name: 'foto.png' } }],
    },
  },
  {
    // What `present` leaves behind. A delivery is a durable event of its own, so
    // a phone can show the files an agent handed over without re-reading the call
    // that declared them — and the middle entry is deliberately unusable, because
    // the index a client sends back is a position in *this* array.
    seq: 8,
    type: 'deliverables/presented',
    time: 1_760_000_000_750,
    data: {
      turn: 1,
      callId: 'call-9',
      files: [
        { path: 'consegna.md', description: 'la nota' },
        { description: 'senza percorso' },
        { path: 'consegna.bin' },
        // A declaration that names a folder: the log can hold one, and the route
        // has to say what it is instead of streaming a directory.
        { path: '.' },
      ],
    },
  },
  { seq: 9, type: 'turn/end', time: 1_760_000_000_800, data: { turn: 1, reason: 'completed' } },
]

const sessions = [
  {
    sessionId: 'session-aaaa',
    updatedAt: 1_760_000_000_800,
    running: false,
    blank: false,
    agentAvailable: true,
    cwd: 'H:\\Documenti\\deepseek-harness\\default-workspace',
    projections: { kind: 'sequenced', asOfSeq: 9, values: { title: 'Chat dal PC', sessionListMetadata: { blank: false, lastPromptAt: 1_760_000_000_700 } } },
  },
  { sessionId: 'session-bbbb', updatedAt: 1_759_000_000_000, running: true, blank: false, agentAvailable: true, projections: { kind: 'sequenced', asOfSeq: 2, values: { title: null } } },
  { sessionId: 'session-child', updatedAt: 1_758_000_000_000, running: false, blank: true, agentAvailable: false, origin: 'subagent', parentSessionId: 'session-aaaa', projections: { kind: 'sequenced', asOfSeq: 0, values: {} } },
  // The four chats the status dot has to tell apart, one ending each.
  { sessionId: 'session-failed', updatedAt: 1_757_000_000_000, running: false, blank: false, agentAvailable: true, cwd: 'K:\\Repository\\Acme', projections: { kind: 'sequenced', asOfSeq: 9, values: { title: 'Rotta' } } },
  { sessionId: 'session-stopped', updatedAt: 1_756_000_000_000, running: false, blank: false, agentAvailable: true, cwd: 'K:\\Repository\\Acme', projections: { kind: 'sequenced', asOfSeq: 9, values: { title: 'Fermata' } } },
  { sessionId: 'session-unfinished', updatedAt: 1_755_000_000_000, running: false, blank: false, agentAvailable: true, cwd: 'K:\\Repository\\Acme', projections: { kind: 'sequenced', asOfSeq: 9, values: { title: 'A metà' } } },
  { sessionId: 'session-blank', updatedAt: 1_754_000_000_000, running: false, blank: true, agentAvailable: true, cwd: 'K:\\Repository\\Acme', projections: { kind: 'sequenced', asOfSeq: 0, values: { title: 'Vuota' } } },
]

/**
 * The newest durable event of a session, as the status reader sees it.
 *
 * `session-aaaa` reads through the event log the message routes use; the others
 * answer with one event each, because "how did the last turn end" is exactly one
 * event and the stand-in should not pretend a whole history exists where the test
 * only asked about the tail.
 */
const newestEvents = new Map([
  ['session-failed', { seq: 9, type: 'turn/end', time: 1_757_000_000_000, data: { turn: 2, reason: { kind: 'error', error: { name: 'LlmFailure', code: 'provider-unavailable' } } } }],
  ['session-stopped', { seq: 9, type: 'turn/end', time: 1_756_000_000_000, data: { turn: 2, reason: { kind: 'aborted', reason: { kind: 'user' } } } }],
  ['session-unfinished', { seq: 9, type: 'tool/call', time: 1_755_000_000_000, data: { turn: 2, step: 1, callId: 'call-x', name: 'pwsh' } }],
])

/** How many times the status reader opened one session's history. */
const statusReads = new Map()

const promptCalls = []

/** Every create request the routes forwarded, in order. */
const createRequests = []

/** Every preset switch the permission route handed to the harness. */
const presetSwitches = []

/** The preset each session currently holds. */
const chosenPresets = new Map([['session-aaaa', 'workspace-write']])

/**
 * The harness's permission-preset service.
 *
 * Its catalog carries only a label, so the interesting part for the phone is the
 * knobs the route fills in from `resolve` — that is what lets the app say what
 * "Full access" actually does.
 */
const permissionPresets = {
  names: ['read-only', 'workspace-write', 'danger-full-access', 'auto'],
  catalog: () => ({
    options: [
      { value: 'read-only', name: 'Read Only', description: 'Read files and run read-only commands.' },
      { value: 'workspace-write', name: 'Workspace Write', description: 'Write inside the workspace.' },
      { value: 'danger-full-access', name: 'Full access', description: 'Full file access without approval prompts.' },
      // Auto is advertised while its integration is live, but resolving it is the
      // integration's job — which is why its knobs have to stay unknown.
      { value: 'auto', name: 'Auto', description: 'Let the reviewer decide.' },
    ],
    defaultOptions: [{ value: 'workspace-write', name: 'Workspace Write' }],
    defaultPreset: 'workspace-write',
  }),
  resolve(name) {
    const table = {
      'read-only': { sandbox: 'read-only', approval: 'ask' },
      'workspace-write': { sandbox: 'workspace-write', approval: 'ask' },
      'danger-full-access': { sandbox: 'danger-full-access', approval: 'never' },
    }
    if (name === 'auto') throw new Error('auto resolves through its integration')
    const spec = table[name]
    if (spec === undefined) throw new Error(`unknown preset ${name}`)
    return spec
  },
  current(session) {
    // A session the service never pinned has no permissions projection, and the
    // service says so by throwing.
    if (session?.id === 'session-unpinned') throw new Error('no permissions projection')
    return chosenPresets.get(session?.id) ?? 'workspace-write'
  },
  set(session, name) {
    presetSwitches.push({ sessionId: session?.id, preset: name })
    if (name === 'auto') throw new Error('Auto is not available in this session')
    chosenPresets.set(session?.id, name)
  },
}

/** Held in a variable so a test can unmount the service and restore it. */
let permissionService = permissionPresets

/** The live sessions the permission route looks a session up in. */
const liveSessions = {
  list: () => [{ id: 'session-aaaa' }, { id: 'session-bbbb' }, { id: 'session-unpinned' }],
}

/** Every URL the preview route fetched, in order — the cache is what keeps this short. */
const webFetches = []

/**
 * The pages the web stub serves, by URL: a fetch result, or a function that
 * throws or returns one, for the failure table.
 */
const webPages = new Map()

/**
 * The harness's web seam.
 *
 * Only `fetch` matters here: the preview route deliberately knows nothing about
 * providers or their policy, and this stand-in inherits the same ignorance.
 */
const webSeam = {
  async fetch(request, signal) {
    webFetches.push(request.url)
    const served = webPages.get(request.url)
    if (served === undefined) {
      const error = new Error(`no stub page for ${request.url}`)
      error.code = 'WEB_PROVIDER_ERROR'
      throw error
    }
    return typeof served === 'function' ? served(request, signal) : served
  },
}

/** Held in a variable so a test can unmount the seam and restore it. */
let webMounted = webSeam

const ctx = {
  logger,
  get: (name) => {
    if (name === 'sessionController') return sessionController
    if (name === 'attachments') return attachments
    if (name === 'fileUploads') return fileUploads
    if (name === 'agents') return agents
    if (name === 'sessions') return liveSessions
    if (name === 'permissionPresets') return permissionService
    if (name === 'web') return webMounted
    if (name === 'workspaceController') return workspaceController
    if (name === 'llm') return llm
    if (name === 'userQuestions') return userQuestions
    return undefined
  },
  on: (event, handler, options) => {
    const set = listeners.get(event) ?? new Set()
    // Cordis prepends rather than appends when asked; the plugin relies on
    // that to sit in front of the desktop's own answerer, so the stand-in
    // honours it. A Set cannot hold two equal handlers, so the order of the
    // whole map is rebuilt instead.
    if (options?.prepend === true) {
      prependedEvents.add(event)
      listeners.set(event, new Set([handler, ...set]))
    } else {
      set.add(handler)
      listeners.set(event, set)
    }
    return () => (listeners.get(event) ?? new Set()).delete(handler)
  },
  effect: (factory) => {
    const dispose = factory()
    if (typeof dispose === 'function') effects.push(dispose)
    return () => {}
  },
  plugin: (ctor, config) => new ctor(ctx, config),
}

const sessionController = {
  async list(_request, _signal) {
    return { items: sessions }
  },
  async create(request) {
    createRequests.push({ ...request })
    // The real controller refuses both targets at once; the stand-in mirrors
    // that rule so the route cannot quietly start forwarding both.
    if (request.workspaceId !== undefined && request.cwd !== undefined) {
      const error = new Error('session.create accepts workspaceId or cwd, not both')
      error.code = 'gateway/bad-request'
      throw error
    }
    return { sessionId: 'session-new', ...(request.cwd === undefined ? { cwd: undefined } : {}) }
  },
  async page(request, signal) {
    signal?.throwIfAborted()
    const address = request.address
    assert.equal(address.kind, 'session')
    if (address.sessionId !== 'session-aaaa') {
      const error = new Error(`session "${address.sessionId}" not found`)
      error.code = 'session/not-found'
      throw error
    }
    // The harness page reader counts *message* entries; this stand-in simply
    // honours the same half-open window so the route's cursor math is exercised.
    const through = request.throughSeq + 1
    const before = request.beforeSeq === undefined ? through : Math.min(request.beforeSeq, through)
    const start = Math.max(0, before - request.maxMessages)
    return { records: sessionEvents.slice(start, before).map((event) => ({ type: 'event', event })), hasMore: start > 0 }
  },
  follow(request, signal) {
    const address = request.address
    statusReads.set(address.sessionId, (statusReads.get(address.sessionId) ?? 0) + 1)
    // A session whose newest event the test pinned answers with exactly that one:
    // the status reader asks for the tail, and one record *is* the tail.
    const pinned = newestEvents.get(address.sessionId)
    if (pinned !== undefined) {
      return (async function* pinnedStream() {
        signal?.throwIfAborted()
        yield { type: 'snapshot', cursor: pinned.seq, records: [{ type: 'event', event: pinned }], hasMore: true }
      })()
    }
    if (address.sessionId !== 'session-aaaa') {
      const error = new Error(`session "${address.sessionId}" not found`)
      error.code = 'session/not-found'
      return (async function* failed() {
        throw error
      })()
    }
    const cursor = sessionEvents.length - 1
    const max = request.maxMessages ?? 60
    const start = Math.max(0, sessionEvents.length - max)
    const records = sessionEvents.slice(start).map((event) => ({ type: 'event', event }))
    let closed = false
    return (async function* followStream() {
      try {
        signal?.throwIfAborted()
        yield { type: 'snapshot', cursor, records, hasMore: start > 0 }
        await new Promise((resolve) => setTimeout(resolve, 50))
        if (!closed) yield { type: 'event', event: sessionEvents[sessionEvents.length - 1] }
      } finally {
        closed = true
      }
    })()
  },
  async prompt(request) {
    promptCalls.push(request)
    if (request.sessionId === 'missing') {
      const error = new Error('session "missing" not found')
      error.code = 'session/not-found'
      throw error
    }
    return { accepted: true }
  },
  /**
   * The picker feed. Shaped like `buildModelCatalog`'s own output, including a
   * provider that failed, because that is the case the route has to carry
   * through rather than flatten.
   */
  async modelCatalog() {
    return {
      default: { provider: 'deepseek-official', model: 'deepseek-flash', reasoningEffort: 'high' },
      routableProviders: ['deepseek-official', 'mimo', 'glm'],
      groups: [
        { id: 'deepseek-official', name: 'DeepSeek', models: [{ id: 'deepseek-flash', name: 'deepseek-flash' }] },
        {
          id: 'mimo',
          name: 'MiMo',
          models: [
            {
              id: 'mimo-v2.6-flash',
              name: 'mimo-v2.6-flash',
              reasoning: { efforts: [{ id: 'low', name: 'Low' }, { id: 'high', name: 'High' }], defaultEffort: 'low' },
            },
            { id: 'mimo-v2.6-pro', name: 'mimo-v2.6-pro' },
          ],
        },
        { id: 'glm', name: 'GLM', models: [{ id: 'glm-5.3', name: 'glm-5.3' }] },
      ],
      failures: [{ id: 'broken', name: 'Broken', message: 'no adapter registered' }],
    }
  },
  async selectModel(request) {
    modelSelections.push({ ...request })
    if (request.model === 'missing-model') {
      const error = new Error(`model ${request.provider}/${request.model} is unavailable`)
      error.code = 'session/model-unavailable'
      throw error
    }
    return {
      selected: {
        provider: request.provider,
        model: request.model,
        ...(request.reasoningEffort === undefined ? {} : { reasoningEffort: request.reasoningEffort }),
      },
    }
  },
}

/** Every selectModel request the route forwarded, in order. */
const modelSelections = []

/** The LLM registry, used only to enrich a catalog row with capabilities. */
const llm = {
  async resolveModelInfo(provider, model) {
    // A provider whose catalog cannot be read must not remove its models from
    // the picker: they simply arrive without capability badges.
    if (provider === 'glm') throw new Error('glm exposes no model info')
    return {
      provider,
      id: model,
      name: model,
      inputModalities: provider === 'mimo' ? ['text', 'image'] : ['text'],
      context: { contextWindow: provider === 'mimo' ? 200_000 : 128_000 },
      defaultMaxTokens: 8_192,
    }
  },
}

const storedImage = { ref: { attachmentId: 'att-9', mediaType: 'image/png', width: 4, height: 4, bytes: 68 }, data: new Uint8Array(68) }

/** Every generic file the upload route committed, in order. */
const savedFiles = []

/**
 * The harness's file-upload service, which is what a generic file goes
 * through: it stages bytes under one session and answers with a receipt the
 * prompt later names. The bytes arrive as an async source, so consuming them is
 * what proves the route streamed rather than buffered.
 */
const fileUploads = {
  async uploadStream(request) {
    assert.ok(typeof request.sessionId === 'string' && request.sessionId.length > 0, 'an upload is addressed to a session')
    assert.equal(typeof request.data?.[Symbol.asyncIterator], 'function', 'the store is handed a byte source, not a buffer')
    let bytes = 0
    for await (const chunk of request.data) bytes += chunk.length
    const name = String(request.name ?? 'file').split(/[/\\]/).pop() || 'file'
    savedFiles.push({ sessionId: request.sessionId, name, bytes })
    return {
      receiptId: `receipt-${savedFiles.length}`,
      file: { attachmentId: `sha256:${'a'.repeat(64)}`, name, bytes },
    }
  },
}

const attachments = {
  async admitEncodedFile(input) {
    assert.ok(typeof input.data === 'string')
    return { attachmentId: 'att-uploaded', name: input.name ?? 'file', bytes: Buffer.from(input.data, 'base64').length }
  },
  async readImage(ref) {
    assert.equal(ref.attachmentId, 'att-9')
    return storedImage
  },
}

const agents = {
  list: () => [
    {
      session: {
        id: 'session-aaaa',
        deriveMessages: () => [
          { role: 'user', content: [{ type: 'text', text: 'guarda questa foto' }, { type: 'image', attachment: { attachmentId: 'att-9', mediaType: 'image/png', width: 4, height: 4, bytes: 68 } }] },
        ],
      },
    },
  ],
}

/** Every late answer the route handed to the harness's own service. */
const lateAnswers = []

/**
 * The harness's user-question service, reached only for an answer that arrives
 * after the live window closed.
 */
const userQuestions = {
  async answer(agent, callId, answer) {
    lateAnswers.push({ sessionId: agent?.session?.id, callId, answer })
    // `false` is what the real service returns for a question it no longer
    // holds, so the fixtures name the two awkward cases explicitly.
    if (callId === 'call-gone') return false
    if (callId === 'call-queued') {
      const error = new Error('a reply is already queued for this question')
      error.code = 'REPLY_QUEUED'
      throw error
    }
    return true
  },
}

const instance = new MobileSync(ctx, { host: '127.0.0.1', port: 0 })
await instance[Symbol.for('cordis.init')]()

const base = `http://127.0.0.1:${instance.port}`
const setupCode = instance.setupCode
let token
/** The id this device is known by, asserted stable across a token rotation. */
let deviceId

/**
 * Call one route and decode its JSON body.
 * @param path - request path.
 * @param options - fetch options.
 * @returns status and parsed body.
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

/**
 * Call one authenticated route with the paired device token.
 * @param path - request path.
 * @param options - fetch options.
 * @returns status and parsed body.
 */
function authed(path, options = {}) {
  const headers = { ...(options.headers ?? {}), authorization: `Bearer ${token}` }
  return call(path, { ...options, headers })
}

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

// ── unauthenticated surface ──────────────────────────────────────────────────
{
  const unknown = await call('/nope')
  ok(unknown.status === 404, `unknown route answers 404 (got ${unknown.status})`)

  const unauth = await call('/mobile/sessions')
  ok(unauth.status === 401, `unauthenticated list answers 401 (got ${unauth.status})`)

  const wrongCode = await call('/mobile/pair', { method: 'POST', body: JSON.stringify({ code: 'nope', device: 'Pixel' }) })
  ok(wrongCode.status === 403, `bad pairing code answers 403 (got ${wrongCode.status})`)

  const paired = await call('/mobile/pair', { method: 'POST', body: JSON.stringify({ code: setupCode, device: 'Pixel 8' }) })
  ok(paired.status === 200, `pairing succeeds (got ${paired.status})`)
  ok(typeof paired.body.token === 'string' && paired.body.token.length > 20, 'pairing returns a device token')
  ok(paired.body.reused === false, 'a first pairing is not a reuse')
  ok(typeof paired.body.id === 'string' && paired.body.id.length === 12, `pairing answers a stable device id (got ${paired.body.id})`)
  token = paired.body.token
  deviceId = paired.body.id

  // A different device must not be able to ride the spent code...
  const intruder = await call('/mobile/pair', { method: 'POST', body: JSON.stringify({ code: setupCode, device: 'Intruso' }) })
  ok(intruder.status === 403, `a second device cannot ride the spent code (got ${intruder.status})`)

  // ...and neither can a name. This is the regression this suite used to encode
  // as a feature: with the code spent, `POST /mobile/pair` handed out the token
  // of any device whose *label* the caller guessed, so the label was a password.
  const byName = await call('/mobile/pair', { method: 'POST', body: JSON.stringify({ code: 'stale', device: 'Pixel 8' }) })
  ok(byName.status === 403, `a spent code cannot be replayed by device name (got ${byName.status})`)
  ok(byName.body.error?.code === 'pairing-closed', `the refusal names the closed door (got ${byName.body.error?.code})`)

  // The phone that still holds its token can trade it for a fresh one — no code,
  // because the token *is* the proof — and the id it is known by survives.
  const rotated = await call('/mobile/pair', {
    method: 'POST',
    headers: { authorization: `Bearer ${token}` },
    body: JSON.stringify({ device: 'Pixel 8' }),
  })
  ok(rotated.status === 200, `a paired device can rotate its token (got ${rotated.status})`)
  ok(rotated.body.rotated === true, 'rotation says it rotated')
  ok(rotated.body.token !== token, 'rotation issues a new token')
  ok(rotated.body.id === deviceId, 'rotation keeps the device id')
  const rotatedAway = await call('/mobile/sessions', { headers: { authorization: `Bearer ${token}` } })
  ok(rotatedAway.status === 401, `the rotated-away token stops working (got ${rotatedAway.status})`)
  token = rotated.body.token

  // A token that is not in the table proves nothing, so the request falls
  // through to the code check — which is closed and refuses it.
  const staleRotation = await call('/mobile/pair', {
    method: 'POST',
    headers: { authorization: 'Bearer not-a-real-token' },
    body: JSON.stringify({ code: '', device: 'Pixel 8' }),
  })
  ok(staleRotation.status === 403, `an unknown token cannot rotate a device (got ${staleRotation.status})`)

  const closedStatus = await authed('/mobile/status')
  ok(closedStatus.body.pairingOpen === false, 'pairing is closed once a device has joined')
}

// ── status and session listing ───────────────────────────────────────────────
{
  const status = await authed('/mobile/status')
  ok(status.status === 200, `status answers 200 (got ${status.status})`)
  ok(status.body.sessionCount === sessions.length, `status counts every session (got ${status.body.sessionCount})`)
  ok(status.body.devices.length === 1, 'status reports one paired device')
  ok(status.body.pairingOpen === false, 'pairing is closed while the code is spent')
  ok(status.body.setupExpiresAt === undefined, 'the status payload never leaks pairing internals')

  const expected = sessions.filter((session) => session.origin !== 'subagent').length
  const list = await authed('/mobile/sessions')
  ok(list.status === 200, `session list answers 200 (got ${list.status})`)
  ok(list.body.items.length === expected, `subagent sessions are hidden by default (got ${list.body.items.length})`)
  ok(list.body.items[0].id === 'session-aaaa', 'sessions are ordered newest first')
  ok(list.body.items[0].title === 'Chat dal PC', 'the PC-set title is exposed')
  ok(list.body.items[1].running === true, 'running state is exposed')
  ok(list.body.items[0].workspaceId === 'ws-default', 'a session is placed in the workspace owning its directory')
  ok(list.body.items[0].workspaceTitle === 'default-workspace', 'the workspace title travels with the session')
  ok(list.body.items[1].workspaceId === null, 'a session without a directory stays ungrouped')

  // The dot on each row: how the last turn ended, which is a fact the host holds
  // in the session's log — a title and a timestamp imply nothing about it.
  const byId = new Map(list.body.items.map((item) => [item.id, item]))
  ok(byId.get('session-aaaa').status === 'completed', `a finished turn reads as completed (got ${byId.get('session-aaaa').status})`)
  ok(byId.get('session-bbbb').status === 'running', 'a running chat is running, from the host flag alone')
  ok(byId.get('session-failed').status === 'failed', `a turn that ended in error reads as failed (got ${byId.get('session-failed').status})`)
  ok(byId.get('session-stopped').status === 'stopped', `a turn the user stopped is not painted as a failure (got ${byId.get('session-stopped').status})`)
  ok(byId.get('session-unfinished').status === 'failed', `a log that stops mid-turn reads as failed, not as running (got ${byId.get('session-unfinished').status})`)
  ok(byId.get('session-blank').status === 'idle', 'a chat with no messages has no turn to have ended')
  ok((statusReads.get('session-bbbb') ?? 0) === 0, 'a running chat costs no history read')
  ok((statusReads.get('session-blank') ?? 0) === 0, 'a blank chat costs no history read')

  const readsSoFar = statusReads.get('session-aaaa') ?? 0
  await authed('/mobile/sessions')
  ok(
    (statusReads.get('session-aaaa') ?? 0) === readsSoFar,
    'an unchanged chat is not read again: the status is cached against its own updatedAt',
  )

  // The budget: one request never starts more reads than it is allowed to, so a
  // cold list of eighty chats cannot be as slow as eighty history reads.
  const readsBefore = statusReads.get('session-aaaa') ?? 0
  const rows = Array.from({ length: 40 }, (_, index) => ({
    id: 'session-aaaa',
    updatedAt: 5_000 + index,
    running: false,
    blank: false,
  }))
  await instance.attachChatStatuses(rows)
  const started = (statusReads.get('session-aaaa') ?? 0) - readsBefore
  ok(started === 24, `one request starts at most the status budget of reads (started ${started})`)
  ok(rows.filter((row) => row.status === 'completed').length === 24, 'the rows inside the budget carry their status')
  ok(rows.filter((row) => row.status === undefined).length === 16, 'the rows past the budget arrive without a dot, to be filled in later')

  const withSubagents = await authed('/mobile/sessions?subagents=1')
  ok(withSubagents.body.items.length === sessions.length, 'subagent sessions appear on request')

  const searched = await authed('/mobile/sessions?q=pc')
  ok(searched.body.items.length === 1, `title search filters the list (got ${searched.body.items.length})`)
}

// ── history ──────────────────────────────────────────────────────────────────
{
  const page = await authed('/mobile/sessions/session-aaaa/messages?limit=50')
  ok(page.status === 200, `history answers 200 (got ${page.status})`)
  ok(page.body.cursor === 9, `history reports the cursor (got ${page.body.cursor})`)
  ok(page.body.entries.length === 10, `every presentable event is returned (got ${page.body.entries.length})`)

  const kinds = page.body.entries.map((entry) => entry.kind)
  ok(kinds.includes('message') && kinds.includes('tool-call') && kinds.includes('tool-result'), 'message, tool-call, and tool-result entries are present')
  ok(kinds.includes('deliverable'), 'a declared delivery reaches the transcript as its own entry')

  const assistant = page.body.entries.find((entry) => entry.type === 'assistant/message')
  ok(assistant.content[0].type === 'text' && assistant.content[0].text === 'ecco il file', 'assistant text is preserved')
  ok(assistant.content[1].type === 'reasoning', 'reasoning blocks are preserved')

  const photo = page.body.entries.find((entry) => entry.content?.some?.((block) => block.type === 'image'))
  ok(photo.content[1].attachmentId === 'att-9', 'image attachment ids are exposed')

  const backwards = await authed('/mobile/sessions/session-aaaa/messages?limit=3&beforeSeq=10')
  ok(backwards.status === 200 && backwards.body.entries.length === 3, `backwards paging returns a bounded page (got ${backwards.body.entries.length})`)

  const badLimit = await authed('/mobile/sessions/session-aaaa/messages?limit=0')
  ok(badLimit.status === 400, `an out-of-range limit is refused (got ${badLimit.status})`)

  const missing = await authed('/mobile/sessions/missing/messages')
  ok(missing.status === 404, `an unknown session answers 404 (got ${missing.status})`)
}

// ── incremental reads, entity tags and the pulse ────────────────────────────
{
  const all = await authed('/mobile/sessions/session-aaaa/messages?limit=50')
  const newest = all.body.entries.at(-1).seq

  // The read that makes a 2.5 s poll cheap: ask for what is newer than the cursor
  // the client already holds.
  const nothing = await authed(`/mobile/sessions/session-aaaa/messages?afterSeq=${newest}`)
  ok(nothing.status === 200, `an incremental read answers 200 (got ${nothing.status})`)
  ok(nothing.body.entries.length === 0, 'an incremental read with nothing new answers an empty page')
  ok(nothing.body.afterSeq === newest, 'the answer names the cursor it applied')

  const incremental = await authed('/mobile/sessions/session-aaaa/messages?afterSeq=4')
  const newer = all.body.entries.filter((entry) => entry.seq > 4)
  ok(incremental.body.entries.length === newer.length, `an incremental page carries only newer entries (got ${incremental.body.entries.length})`)
  ok(incremental.body.entries.every((entry) => entry.seq > 4), 'no entry at or below the cursor is repeated')
  ok(incremental.body.nextBeforeSeq === newer[0].seq, 'the page names where older history continues')

  const both = await authed('/mobile/sessions/session-aaaa/messages?afterSeq=4&beforeSeq=8')
  ok(both.status === 400, `afterSeq and beforeSeq cannot be combined (got ${both.status})`)
  const malformed = await authed('/mobile/sessions/session-aaaa/messages?afterSeq=4x')
  ok(malformed.status === 400, `a malformed afterSeq is refused (got ${malformed.status})`)
  const typoLimit = await authed('/mobile/sessions/session-aaaa/messages?limit=60abc')
  ok(typoLimit.status === 400, `a malformed limit is refused instead of read as 60 (got ${typoLimit.status})`)

  // An entity tag makes "nothing changed" one round trip with no body.
  const first = await fetch(`${base}/mobile/sessions`, { headers: { authorization: `Bearer ${token}` } })
  const tag = first.headers.get('etag')
  ok(typeof tag === 'string' && tag.startsWith('"'), `a list answer carries an entity tag (got ${tag})`)
  const again = await fetch(`${base}/mobile/sessions`, {
    headers: { authorization: `Bearer ${token}`, 'if-none-match': tag },
  })
  ok(again.status === 304, `an unchanged list revalidates to 304 (got ${again.status})`)
  ok((await again.text()) === '', 'a 304 carries no body')

  // The pulse is the list screen's four polls under one answer and one tag.
  const pulseResponse = await fetch(`${base}/mobile/pulse`, { headers: { authorization: `Bearer ${token}` } })
  ok(pulseResponse.status === 200, `the pulse answers 200 (got ${pulseResponse.status})`)
  const pulseTag = pulseResponse.headers.get('etag')
  const pulse = await pulseResponse.json()
  ok(Array.isArray(pulse.sessions) && pulse.sessions.length === sessions.length - 1, `the pulse carries the session rows (got ${pulse.sessions.length})`)
  ok(Array.isArray(pulse.workspaces) && pulse.workspaces.length === workspaces.length, 'the pulse carries the projects')
  ok(pulse.workspacesAvailable === true, 'the pulse says the host groups chats')
  ok(Array.isArray(pulse.approvals) && Array.isArray(pulse.questions), 'the pulse carries both queues')
  ok(pulse.approvalsPending === 0 && pulse.questionsPending === 0, 'the pulse counts both queues')
  const pulseAgain = await fetch(`${base}/mobile/pulse`, {
    headers: { authorization: `Bearer ${token}`, 'if-none-match': pulseTag },
  })
  ok(pulseAgain.status === 304, `an unchanged pulse revalidates to 304 (got ${pulseAgain.status})`)
}

// ── images ───────────────────────────────────────────────────────────────────

{
  const raw = await fetch(`${base}/mobile/sessions/session-aaaa/images/att-9/raw`, { headers: { authorization: `Bearer ${token}` } })
  ok(raw.status === 200, `raw image answers 200 (got ${raw.status})`)
  ok(raw.headers.get('content-type') === 'image/png', 'raw image carries its media type')
  const bytes = new Uint8Array(await raw.arrayBuffer())
  ok(bytes.byteLength === 68, `raw image returns the stored bytes (got ${bytes.byteLength})`)

  const data = await authed('/mobile/sessions/session-aaaa/images/att-9/data')
  ok(data.status === 200 && typeof data.body.data === 'string', 'base64 image route answers with data')

  const absent = await authed('/mobile/sessions/session-aaaa/images/att-none/raw')
  ok(absent.status === 404, `an unreferenced image answers 404 (got ${absent.status})`)
}

// ── sending ──────────────────────────────────────────────────────────────────
{
  const text = await authed('/mobile/sessions/session-aaaa/messages', { method: 'POST', body: JSON.stringify({ text: 'ciao dal telefono' }) })
  ok(text.status === 202, `text send is accepted (got ${text.status})`)
  ok(promptCalls.length === 1, 'one prompt reached the controller')
  ok(promptCalls[0].content.length === 1 && promptCalls[0].content[0].text === 'ciao dal telefono', 'prompt text is forwarded')
  ok(promptCalls[0].mode === 'queue', 'the default delivery mode is queue')
  ok(typeof promptCalls[0].requestId === 'string' && promptCalls[0].requestId.startsWith('mobile-'), 'the prompt carries a stable request id')

  const withPhoto = await authed('/mobile/sessions/session-aaaa/messages', {
    method: 'POST',
    body: JSON.stringify({
      text: 'guarda',
      images: [{ mediaType: 'image/jpeg', data: Buffer.from('fakejpeg').toString('base64'), name: 'foto.jpg' }],
      mode: 'steer',
    }),
  })
  ok(withPhoto.status === 202, `photo send is accepted (got ${withPhoto.status})`)
  const second = promptCalls[1]
  ok(second.mode === 'steer', 'steer mode is forwarded')
  ok(second.content.some((part) => part.type === 'image' && part.mediaType === 'image/jpeg'), 'the image part reaches the controller')

  const empty = await authed('/mobile/sessions/session-aaaa/messages', { method: 'POST', body: JSON.stringify({ text: '   ' }) })
  ok(empty.status === 400, `an empty prompt is refused (got ${empty.status})`)

  const wrongType = await authed('/mobile/sessions/session-aaaa/messages', {
    method: 'POST',
    body: JSON.stringify({ text: 'x', images: [{ mediaType: 'image/tiff', data: 'AAAA' }] }),
  })
  ok(wrongType.status === 400, `an unsupported image media type is refused (got ${wrongType.status})`)

  // A generic file is named, not carried: its bytes were staged for this chat
  // by the upload route, and the prompt repeats the receipt it answered with.
  const withFile = await authed('/mobile/sessions/session-aaaa/messages', {
    method: 'POST',
    body: JSON.stringify({ text: 'leggi questa nota', files: [{ receiptId: 'receipt-1' }] }),
  })
  ok(withFile.status === 202, `file send is accepted (got ${withFile.status})`)
  const fileContent = promptCalls.at(-1).content
  const sent = fileContent.find((part) => part.type === 'file')
  ok(sent?.receiptId === 'receipt-1', `the file part carries the receipt (got ${JSON.stringify(sent)})`)
  ok(sent?.attachment === undefined, 'and nothing else: the reference is the controller\'s to resolve')
  ok(fileContent.some((part) => part.type === 'text' && part.text === 'leggi questa nota'), 'text and file travel in one prompt')

  const fileOnly = await authed('/mobile/sessions/session-aaaa/messages', {
    method: 'POST',
    body: JSON.stringify({ files: [{ receiptId: 'receipt-2' }] }),
  })
  ok(fileOnly.status === 202, `a file without text is a message (got ${fileOnly.status})`)
  ok(promptCalls.at(-1).content.length === 1, 'and it carries nothing else')

  const receiptless = await authed('/mobile/sessions/session-aaaa/messages', {
    method: 'POST',
    body: JSON.stringify({ text: 'x', files: [{ name: 'nota.md', bytes: 11 }] }),
  })
  ok(receiptless.status === 400, `a file part without a receipt is refused (got ${receiptless.status})`)

  const tooManyFiles = await authed('/mobile/sessions/session-aaaa/messages', {
    method: 'POST',
    body: JSON.stringify({ text: 'x', files: Array.from({ length: 9 }, (_, index) => ({ receiptId: `receipt-${index}` })) }),
  })
  ok(tooManyFiles.status === 400, `more files than one message carries are refused (got ${tooManyFiles.status})`)

  const missingSession = await authed('/mobile/sessions/missing/messages', { method: 'POST', body: JSON.stringify({ text: 'x' }) })
  ok(missingSession.status === 404, `sending to an unknown session answers 404 (got ${missingSession.status})`)

  const malformed = await authed('/mobile/sessions/session-aaaa/messages', { method: 'POST', body: '{oops' })
  ok(malformed.status === 400, `malformed JSON answers 400 (got ${malformed.status})`)
}

// ── upload and create ────────────────────────────────────────────────────────
{
  const upload = await authed('/mobile/images', { method: 'POST', body: JSON.stringify({ data: Buffer.from('hello-world').toString('base64'), name: 'nota.png' }) })
  ok(upload.status === 201, `image upload answers 201 (got ${upload.status})`)
  ok(upload.body.attachmentId === 'att-uploaded', 'upload returns the durable reference')
  ok(upload.body.bytes === 11, `upload reports the byte count (got ${upload.body.bytes})`)

  // A generic file travels as its own bytes: no base64, no JSON envelope, and
  // the name and the session in the query because a header cannot carry every
  // name a phone has a file for.
  const fileBytes = Buffer.from('# nota\nriga due\n')
  const fileUpload = await authed(`/mobile/files?sessionId=session-aaaa&name=${encodeURIComponent('K:\\Repository\\nota.md')}`, {
    method: 'POST',
    body: fileBytes,
  })
  ok(fileUpload.status === 201, `file upload answers 201 (got ${fileUpload.status})`)
  ok(typeof fileUpload.body.receiptId === 'string' && fileUpload.body.receiptId.length > 0, 'the upload answers with a receipt')
  ok(fileUpload.body.bytes === fileBytes.length, `the upload reports the byte count (got ${fileUpload.body.bytes})`)
  ok(fileUpload.body.name === 'nota.md', `the store's leaf name is what comes back (got ${fileUpload.body.name})`)
  ok(savedFiles.at(-1)?.bytes === fileBytes.length, 'the exact bytes reached the store, not a re-encoding')
  ok(savedFiles.at(-1)?.sessionId === 'session-aaaa', 'and the receipt is keyed to the addressed session')
  ok(String(fileUpload.body.attachmentId).startsWith('sha256:'), 'the stored reference is the id the store minted')

  const namelessUpload = await authed('/mobile/files?sessionId=session-aaaa', { method: 'POST', body: Buffer.from('senza nome') })
  ok(namelessUpload.status === 201 && namelessUpload.body.name === 'file', `a nameless upload still gets a label (got ${namelessUpload.body.name})`)

  const emptyUpload = await authed('/mobile/files?sessionId=session-aaaa&name=vuoto.txt', { method: 'POST', body: Buffer.alloc(0) })
  ok(emptyUpload.status === 201 && emptyUpload.body.bytes === 0, `a zero-byte file is stored, as the harness allows (got ${emptyUpload.status})`)

  const unresolvableSession = await authed('/mobile/files?name=solo.bin', { method: 'POST', body: Buffer.from('senza sessione') })
  ok(unresolvableSession.status === 400, `an upload with no session is refused (got ${unresolvableSession.status})`)

  const oversized = await authed('/mobile/files?sessionId=session-aaaa&name=grande.bin', { method: 'POST', body: Buffer.alloc(32 * 1024 * 1024 + 1) })
  ok(oversized.status === 413, `a file past the cap is refused (got ${oversized.status})`)
  ok(oversized.body?.error?.code === 'file-too-large', 'and the refusal names the reason')

  // A body that declares no length cannot be refused before it is read, so the
  // cap has to bite mid-stream — and the store calls any failure of its byte
  // source "could not be persisted", which must not become the answer a phone
  // gets for a file that is merely too big. The generator is driven here
  // directly rather than over a socket: answering a client that is still
  // writing 33 MB is a race the client's own runtime does not survive, and what
  // matters is the mechanism the route reads afterwards.
  const refused = { tooLarge: false }
  const body = (async function* source() {
    yield Buffer.alloc(6)
    yield Buffer.alloc(6)
    yield Buffer.alloc(6)
  })()
  let refusal
  try {
    for await (const chunk of boundedBody(body, 8, refused)) void chunk
  } catch (error) {
    refusal = error
  }
  ok(refusal?.status === 413 && refusal?.code === 'file-too-large', `the capped reader refuses past the limit (got ${refusal?.code})`)
  ok(refused.tooLarge === true, 'and records the refusal, so the route answers a size, not a storage fault')
  ok(refusal !== undefined && refused.tooLarge, 'the store never sees the bytes past the cap')

  const created = await authed('/mobile/sessions', { method: 'POST', body: JSON.stringify({ cwd: 'H:\\Documenti\\deepseek-harness\\default-workspace' }) })
  ok(created.status === 201 && created.body.sessionId === 'session-new', 'session creation returns the new id')

  // Starting a chat inside a project must name the project, not just the
  // directory: that is what records the chat in the project's own account and
  // therefore what makes it show up in the desktop sidebar.
  const inWorkspace = await authed('/mobile/sessions', { method: 'POST', body: JSON.stringify({ workspaceId: 'ws-acme' }) })
  ok(inWorkspace.status === 201, `session creation by project answers 201 (got ${inWorkspace.status})`)
  ok(createRequests.at(-1)?.workspaceId === 'ws-acme', 'the project id reached the controller')
  ok(createRequests.at(-1)?.cwd === undefined, 'a project-created session sends no separate directory')

  // The controller refuses both targets at once, so the route must pick one
  // rather than forwarding a request it knows the host will reject.
  const bothTargets = await authed('/mobile/sessions', { method: 'POST', body: JSON.stringify({ workspaceId: 'ws-acme', cwd: 'K:\\Repository\\Acme' }) })
  ok(bothTargets.status !== 201 || createRequests.at(-1)?.cwd === undefined, 'the project wins over a directory when both are named')
  ok(createRequests.at(-1)?.workspaceId === 'ws-acme', 'and the project is the one that reached the controller')
}


// ── workspaces and directory browsing ────────────────────────────────────────
{
  const list = await authed('/mobile/workspaces')
  ok(list.status === 200, `workspace list answers 200 (got ${list.status})`)
  ok(list.body.items.length === 2, `every workspace is reported (got ${list.body.items.length})`)
  ok(list.body.items[0].workspaceId === 'ws-acme', 'workspaces keep the host registry order')
  ok(list.body.items[0].sessionIds.includes('session-aaaa'), 'a workspace reports its accounted sessions')

  const raw = await authed('/mobile/sessions?workspaces=0')
  ok(raw.body.items[0].workspaceId === null, 'grouping can be switched off for a plain list')

  // Creating is idempotent: re-adopting a directory returns the existing row
  // rather than a duplicate, which is what lets a phone recover its list.
  const adopted = await authed('/mobile/workspaces', { method: 'POST', body: JSON.stringify({ path: 'K:\\Repository\\Acme' }) })
  ok(adopted.status === 200, `adopting a known directory answers 200 (got ${adopted.status})`)
  ok(adopted.body.workspaceId === 'ws-acme' && adopted.body.created === false, 're-adopting returns the existing workspace')

  const fresh = await authed('/mobile/workspaces', { method: 'POST', body: JSON.stringify({ path: 'K:\\Repository\\NuovoProgetto' }) })
  ok(fresh.status === 201, `adopting a new directory answers 201 (got ${fresh.status})`)
  ok(fresh.body.created === true && fresh.body.title === 'Nuovo', 'a new workspace reports itself as created')
  ok(createdWorkspaces.at(-1).path === 'K:\\Repository\\NuovoProgetto', 'the host path is forwarded verbatim, backslashes intact')

  const relative = await authed('/mobile/workspaces', { method: 'POST', body: JSON.stringify({ path: 'Repository\\Nuovo' }) })
  ok(relative.status === 400, `a drive-relative path is refused (got ${relative.status})`)

  const missing = await authed('/mobile/workspaces', { method: 'POST', body: JSON.stringify({ path: 'C:\\Non\\Esiste' }) })
  ok(missing.status === 409, `a directory the host rejects answers 409 (got ${missing.status})`)
  ok(missing.body.error.code === 'workspace/invalid-path', 'the host failure keeps its code')

  const empty = await authed('/mobile/workspaces', { method: 'POST', body: JSON.stringify({}) })
  ok(empty.status === 400, `a workspace without a path is refused (got ${empty.status})`)

  // Removal unregisters a project; it must never be able to mean "delete the
  // folder", so the route answers a receipt and the host keeps everything.
  const removed = await authed('/mobile/workspaces/ws-new', { method: 'DELETE' })
  ok(removed.status === 200 && removed.body.deleted === true, `removing a project answers 200 (got ${removed.status})`)
  ok(deletedWorkspaces.includes('ws-new'), 'the project id reached the controller')

  const unknownDelete = await authed('/mobile/workspaces/ws-unknown', { method: 'DELETE' })
  ok(unknownDelete.status === 404, `removing an unknown project answers 404 (got ${unknownDelete.status})`)
}

{
  // The browser's own directory is the fixture root: real levels, real names,
  // so the scan, the sort, and the truncation flag are exercised for real.
  const root = fileURLToPath(new URL('./fixtures/', import.meta.url))
  const listing = await authed(`/mobile/dirs?path=${encodeURIComponent(root)}`)
  ok(listing.status === 200, `browsing a real directory answers 200 (got ${listing.status})`)
  ok(listing.body.path.toLowerCase() === root.replace(/[\\/]$/, '').toLowerCase(), 'the listing reports the resolved path')
  ok(Array.isArray(listing.body.crumbs) && listing.body.crumbs.length > 0, 'the listing carries a breadcrumb chain')
  ok(listing.body.crumbs.at(-1).path.toLowerCase() === root.replace(/[\\/]$/, '').toLowerCase(), 'the last crumb is the listed directory')
  ok(listing.body.files === undefined, 'files never leak into a directory listing')
  ok(listing.body.entries.every((entry) => entry.path.toLowerCase().startsWith(listing.body.path.toLowerCase())), 'every row carries an absolute path the client can send back')
  ok(typeof listing.body.truncated === 'boolean', 'the cut flag is always present')

  const home = await authed('/mobile/dirs')
  ok(home.status === 200 && typeof home.body.home === 'string', 'a listing without a path starts at the host account home')
  ok(home.body.crumbs.at(-1).path.toLowerCase() === home.body.home.toLowerCase(), 'the home listing ends its crumbs at the home directory')

  // A phone has no way to guess that a volume exists: the host must offer them.
  // They lead every level, so another drive is one tap away from anywhere.
  const drives = home.body.crumbs.filter((crumb) => crumb.drive === true)
  if (process.platform === 'win32') {
    ok(drives.length > 0, 'the host enumerates its drive roots as leading crumbs')
    ok(drives.every((crumb) => /^[A-Z]:\\$/.test(crumb.path)), 'every drive crumb is a drive root')
    ok(drives.every((crumb) => /^[A-Z]:$/.test(crumb.name)), 'a drive crumb is labelled without its trailing separator')
    const onDrive = await authed(`/mobile/dirs?path=${encodeURIComponent(drives[0].path)}`)
    ok(onDrive.status === 200 && onDrive.body.path === drives[0].path, `a drive root can be listed (got ${onDrive.status})`)
    // A volume root has no parent to step up to, so the other drives are the
    // only way out of one. Suppressing the whole shortcut block there — as an
    // earlier version did — left a dead "up" and no way to another disk, which
    // reads as a stuck picker rather than as the top of a tree.
    ok(
      onDrive.body.crumbs.filter((crumb) => crumb.drive === true && crumb.path.toLowerCase() === drives[0].path.toLowerCase()).length === 0,
      'a volume root is not repeated as its own shortcut',
    )
    ok(
      onDrive.body.crumbs.at(-1).path.toLowerCase() === drives[0].path.toLowerCase() && onDrive.body.crumbs.at(-1).drive !== true,
      'the level being listed is its own last crumb',
    )
    if (drives.length > 1) {
      ok(
        onDrive.body.crumbs.some((crumb) => crumb.drive === true && crumb.path.toLowerCase() !== drives[0].path.toLowerCase()),
        'the other drives stay one tap away at a volume root',
      )
    }
    const looseDrive = await authed(`/mobile/dirs?path=${encodeURIComponent(drives[0].path.slice(0, 2))}`)
    ok(looseDrive.status === 200, 'a bare drive letter is accepted as its own root')
  } else {
    ok(drives.length === 0, 'a host without drive letters offers no drive crumbs')
  }

  // One crumb per level, never the same volume twice: the shortcut block skips
  // the volume the ancestry chain already names as its root.
  const ancestryRoot = listing.body.crumbs.find((crumb) => crumb.drive !== true)
  ok(
    listing.body.crumbs.filter((crumb) => crumb.path.toLowerCase() === ancestryRoot.path.toLowerCase()).length === 1,
    'the volume a level stands on appears once in its breadcrumb row',
  )

  const relative = await authed('/mobile/dirs?path=Repository')
  ok(relative.status === 400, `a drive-relative browse path is refused (got ${relative.status})`)

  const absent = await authed(`/mobile/dirs?path=${encodeURIComponent('C:\\Non\\Esiste\\Davvero')}`)
  ok(absent.status === 404, `an unreadable directory answers 404 (got ${absent.status})`)
  ok(absent.body.error.code === 'directory-unreadable', 'the unreadable failure keeps its code')

  const madeDir = `dsh-check-${Date.now()}`
  const created = await authed('/mobile/dirs', { method: 'POST', body: JSON.stringify({ parent: root, name: madeDir }) })
  ok(created.status === 201, `creating a directory answers 201 (got ${created.status})`)
  ok(created.body.created.toLowerCase() === `${root}${madeDir}`.replace(/[\\/]$/, '').toLowerCase(), 'the created path is returned')
  ok(created.body.entries.some((entry) => entry.name === madeDir), 'the refreshed listing already contains the new directory')

  const again = await authed('/mobile/dirs', { method: 'POST', body: JSON.stringify({ parent: root, name: madeDir }) })
  ok(again.status === 409 && again.body.error.code === 'directory-exists', `a duplicate name answers 409 (got ${again.status})`)

  const nested = await authed('/mobile/dirs', { method: 'POST', body: JSON.stringify({ parent: root, name: `a\\b` }) })
  ok(nested.status === 400, `a name holding a separator is refused (got ${nested.status})`)

  const noBody = await authed('/mobile/dirs', { method: 'POST' })
  ok(noBody.status === 400, `creating without a body is refused (got ${noBody.status})`)

  await rm(fileURLToPath(new URL(`./fixtures/${madeDir}/`, import.meta.url)), { recursive: true, force: true })
}

// ── reading a file an agent linked ───────────────────────────────────────────
{
  // A chat is full of links to files the agent itself wrote, and a phone that
  // can only preview URLs gets "not a valid URL: LORE-bozza.md" for a file on the
  // harness's own disk. The route reads it — bounded, as text — and the two
  // things worth pinning down are which path a *relative* reference resolves to
  // and what a file that is not text answers.
  const root = fileURLToPath(new URL('./fixtures/', import.meta.url)).replace(/[\\/]$/, '')
  const nota = `${root}${path.sep}nota-${Date.now()}.md`
  const big = `${root}${path.sep}lungo-${Date.now()}.md`
  const blob = `${root}${path.sep}binario-${Date.now()}.dat`
  await writeFile(nota, '# LORE\n\nbozza del file\n', 'utf8')
  await writeFile(big, 'a'.repeat(600 * 1024), 'utf8')
  await writeFile(blob, Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x00, 0x01, 0x02, 0x03]))

  const absolute = await authed(`/mobile/file?ref=${encodeURIComponent(nota)}`)
  ok(absolute.status === 200, `reading an absolute reference answers 200 (got ${absolute.status})`)
  ok(absolute.body.text === '# LORE\n\nbozza del file\n', 'the file is served as its own text, byte for byte')
  ok(absolute.body.name === path.basename(nota), 'the answer names the file')
  ok(absolute.body.bytes === Buffer.byteLength('# LORE\n\nbozza del file\n'), 'the answer reports the size on disk')
  ok(absolute.body.truncated === false, 'a small file is not reported as cut')
  ok(absolute.body.path.toLowerCase() === nota.toLowerCase(), 'the answer carries the path the host resolved')

  // The chat's own folder is what a bare name means: the agent wrote the link
  // while working there, and the host process's directory is an accident.
  const chat = sessions[0]
  const cwdBefore = chat.cwd
  chat.cwd = root
  const relative = await authed(`/mobile/file?ref=${encodeURIComponent(path.basename(nota))}&session=session-aaaa`)
  ok(relative.status === 200, `a relative reference resolves against the chat's folder (got ${relative.status})`)
  ok(relative.body.path.toLowerCase() === nota.toLowerCase(), 'and it resolves to the file beside that chat')

  const noSession = await authed(`/mobile/file?ref=${encodeURIComponent(path.basename(nota))}`)
  ok(noSession.status === 409, `a relative reference with no chat is refused (got ${noSession.status})`)
  ok(noSession.body.error.code === 'file/no-session', 'and says so with its own code')

  const unknownSession = await authed(`/mobile/file?ref=${encodeURIComponent(path.basename(nota))}&session=session-nonesiste`)
  ok(unknownSession.status === 409, `a relative reference against an unknown chat is refused (got ${unknownSession.status})`)

  // A relative reference may step outwards and back: an agent that works in a
  // subfolder links `../README.md` often enough, and the same device can already
  // browse every level of this disk, so a fence here would only hide what the
  // phone can otherwise see.
  const upward = await authed(
    `/mobile/file?ref=${encodeURIComponent(`..${path.sep}fixtures${path.sep}${path.basename(nota)}`)}&session=session-aaaa`,
  )
  ok(upward.status === 200 && upward.body.path.toLowerCase() === nota.toLowerCase(), 'a reference that steps out and back resolves to the same file')

  const asUrl = await authed(`/mobile/file?ref=${encodeURIComponent(`file:///${nota.replace(/\\/g, '/')}`)}`)
  ok(asUrl.status === 200 && asUrl.body.text === absolute.body.text, `a file: URL is unwrapped to the same file (got ${asUrl.status})`)

  // The harness's own file links carry a line — `[riga 35](LORE-bozza.md#L35)` —
  // and reading the fragment as part of the name answers "not found" for a file
  // that is right there, which is the same defect one layer down.
  const withLine = await authed(`/mobile/file?ref=${encodeURIComponent(`${path.basename(nota)}#L35`)}&session=session-aaaa`)
  ok(withLine.status === 200 && withLine.body.path.toLowerCase() === nota.toLowerCase(), `a #L35 fragment is not part of the file name (got ${withLine.status})`)
  ok(withLine.body.line === 35, `the line the fragment names travels with the answer (got ${withLine.body.line})`)

  const bareLine = await authed(`/mobile/file?ref=${encodeURIComponent(`${nota}#12`)}`)
  ok(bareLine.status === 200 && bareLine.body.line === 12, 'a bare #12 fragment names a line too')

  const wordFragment = await authed(`/mobile/file?ref=${encodeURIComponent(`${nota}#capitolo-2`)}`)
  ok(wordFragment.status === 200 && wordFragment.body.line === null, 'a fragment that names no line is reported as none, not guessed')

  const urlWithLine = await authed(`/mobile/file?ref=${encodeURIComponent(`file:///${nota.replace(/\\/g, '/')}#L7`)}`)
  ok(urlWithLine.status === 200 && urlWithLine.body.line === 7, 'a file: URL keeps its own fragment out of the path')

  // The caps: a file bigger than the read limit is served as its beginning, and
  // the answer says so instead of pretending it is the whole thing.
  const cut = await authed(`/mobile/file?ref=${encodeURIComponent(big)}`)
  ok(cut.status === 200 && cut.body.truncated === true, 'a file past the cap is reported as cut')
  ok(cut.body.bytes === 600 * 1024, 'the reported size is the file on disk, not the bytes read')
  ok(cut.body.text.length <= 512 * 1024, `the served text stays bounded (got ${cut.body.text.length})`)

  const binary = await authed(`/mobile/file?ref=${encodeURIComponent(blob)}`)
  ok(binary.status === 415, `a binary file is refused rather than decoded (got ${binary.status})`)
  ok(binary.body.error.code === 'file/binary', 'a binary file says which rule it broke')

  const directory = await authed(`/mobile/file?ref=${encodeURIComponent(root)}`)
  ok(directory.status === 400 && directory.body.error.code === 'file/not-a-file', `a directory is not a file (got ${directory.status})`)

  const missing = await authed(`/mobile/file?ref=${encodeURIComponent(`${root}${path.sep}non-esiste.md`)}`)
  ok(missing.status === 404 && missing.body.error.code === 'file/not-found', `a missing file answers 404 (got ${missing.status})`)

  const empty = await authed('/mobile/file?ref=')
  ok(empty.status === 400 && empty.body.error.code === 'file/invalid', `an empty reference is refused (got ${empty.status})`)

  if (process.platform === 'win32') {
    const driveRelative = await authed(`/mobile/file?ref=${encodeURIComponent('K:nota.md')}`)
    ok(driveRelative.status === 400, `a drive-relative reference is refused (got ${driveRelative.status})`)
    ok(driveRelative.body.error.code === 'file/invalid', 'and keeps the invalid-reference code')
  }

  chat.cwd = cwdBefore
  await rm(nota, { force: true })
  await rm(big, { force: true })
  await rm(blob, { force: true })
}

// ── delivering a file an agent declared ──────────────────────────────────────
{
  // `present` is the one tool whose result is a file the user is meant to open,
  // and the desktop opens it natively from the card. A phone cannot: it needs the
  // file's description to draw the card and its bytes to save, open or share it.
  // The address is the *declaration's* coordinates — chat, sequence, index — so
  // the path comes out of the harness's own log rather than out of the request.
  const root = fileURLToPath(new URL('./fixtures/', import.meta.url)).replace(/[\\/]$/, '')
  const nota = `${root}${path.sep}consegna.md`
  const blob = `${root}${path.sep}consegna.bin`
  await writeFile(nota, '# Consegna\n\ntesto della consegna\n', 'utf8')
  await writeFile(blob, Buffer.from([0x25, 0x50, 0x44, 0x46, 0x00, 0x01, 0x02]))
  const chat = sessions[0]
  const cwdBefore = chat.cwd
  chat.cwd = root

  const page = await authed('/mobile/sessions/session-aaaa/messages?limit=50')
  const delivery = page.body.entries.find((entry) => entry.kind === 'deliverable')
  ok(delivery !== undefined, 'a delivery is projected as its own entry')
  ok(delivery.seq === 8 && delivery.callId === 'call-9', 'the delivery carries the event it came from')
  ok(delivery.files.length === 3, `an entry that names no file is dropped (got ${delivery.files?.length})`)
  ok(delivery.files[0].path === 'consegna.md' && delivery.files[0].index === 0, 'the first declaration keeps its own index')
  ok(delivery.files[0].description === 'la nota', 'the description an agent wrote travels with the file')
  ok(delivery.files[1].index === 2, `an index is the position in the event, never a renumbering (got ${delivery.files[1].index})`)
  ok(delivery.files[2].index === 3, 'the index after a dropped entry keeps counting the event, not the readable files')

  // The description answer: the text for a text file, and the size and name the
  // card needs before anyone decides to download anything.
  const text = await authed('/mobile/delivery?session=session-aaaa&seq=8&index=0')
  ok(text.status === 200, `a declared text file is described (got ${text.status})`)
  ok(text.body.text === '# Consegna\n\ntesto della consegna\n', 'the delivery is served as its own text')
  ok(text.body.binary === false && text.body.truncated === false, 'a small text delivery is neither binary nor cut')
  ok(text.body.name === 'consegna.md', 'the answer names the file')
  ok(text.body.path.toLowerCase() === nota.toLowerCase(), 'a relative declaration resolves against the chat that made it')
  ok(text.body.description === 'la nota', 'the description travels with the description answer')

  // ...and, for everything else, the fact that it is not text. A 415 here would
  // put "cannot read this" on a delivery that is perfectly fine.
  const binary = await authed('/mobile/delivery?session=session-aaaa&seq=8&index=2')
  ok(binary.status === 200, `a declared binary file is still described (got ${binary.status})`)
  ok(binary.body.binary === true && binary.body.text === '', 'and says it is not text instead of refusing')
  ok(binary.body.bytes === 7, `and reports its size on disk (got ${binary.body.bytes})`)

  // The bytes themselves, with the file's own name in the disposition.
  const raw = await fetch(`${base}/mobile/delivery?session=session-aaaa&seq=8&index=2&raw=1`, { headers: { authorization: `Bearer ${token}` } })
  ok(raw.status === 200, `a raw delivery answers 200 (got ${raw.status})`)
  ok(raw.headers.get('content-type') === 'application/octet-stream', 'raw bytes carry the honest generic type')
  ok(raw.headers.get('content-disposition')?.includes('filename="consegna.bin"') === true, 'the file name travels in the disposition')
  const bytes = new Uint8Array(await raw.arrayBuffer())
  ok(bytes.byteLength === 7 && bytes[0] === 0x25 && bytes[4] === 0x00, 'the raw answer is the file byte for byte')

  const rawText = await fetch(`${base}/mobile/delivery?session=session-aaaa&seq=8&index=0&raw=1`, { headers: { authorization: `Bearer ${token}` } })
  ok((await rawText.text()) === '# Consegna\n\ntesto della consegna\n', 'a text delivery downloads as the same bytes it reads as')

  // Coordinates that name nothing.
  const missingSeq = await authed('/mobile/delivery?session=session-aaaa&seq=5&index=0')
  ok(missingSeq.status === 404 && missingSeq.body.error.code === 'delivery/not-found', `a sequence holding no delivery answers 404 (got ${missingSeq.status})`)
  const missingIndex = await authed('/mobile/delivery?session=session-aaaa&seq=8&index=1')
  ok(missingIndex.status === 404, 'an entry that declared no path answers 404 rather than serving another file')
  const pastEnd = await authed('/mobile/delivery?session=session-aaaa&seq=8&index=9')
  ok(pastEnd.status === 404, 'an index past the end of the event answers 404')
  const unknownChat = await authed('/mobile/delivery?session=session-nonesiste&seq=8&index=0')
  ok(unknownChat.status === 404, `an unknown chat answers 404 (got ${unknownChat.status})`)
  const noSession = await authed('/mobile/delivery?seq=8&index=0')
  ok(noSession.status === 400, 'a delivery with no chat is refused')
  const noSeq = await authed('/mobile/delivery?session=session-aaaa&index=0')
  ok(noSeq.status === 400, 'a delivery with no sequence is refused')
  const badIndex = await authed('/mobile/delivery?session=session-aaaa&seq=8&index=-1')
  ok(badIndex.status === 400, `a negative index is refused (got ${badIndex.status})`)

  // A declaration that resolved to a folder is not a file, and saying so is the
  // difference between a refusal and a stream of a directory's bytes.
  const folder = await authed('/mobile/delivery?session=session-aaaa&seq=8&index=3')
  ok(folder.status === 400 && folder.body.error.code === 'file/not-a-file', `a declaration naming a folder is refused (got ${folder.status})`)
  const folderRaw = await authed('/mobile/delivery?session=session-aaaa&seq=8&index=3&raw=1')
  ok(folderRaw.status === 400 && folderRaw.body.error.code === 'file/not-a-file', 'and its bytes are refused the same way')

  // A declaration whose file is gone: the log still says it was delivered, and
  // the honest answer is that the file is no longer there — not a 404 about the
  // delivery, which would send the reader looking in the wrong place.
  await rm(nota, { force: true })
  const gone = await authed('/mobile/delivery?session=session-aaaa&seq=8&index=0')
  ok(gone.status === 404 && gone.body.error.code === 'file/not-found', `a deleted delivery says the file is gone (got ${gone.status})`)
  const goneRaw = await authed('/mobile/delivery?session=session-aaaa&seq=8&index=0&raw=1')
  ok(goneRaw.status === 404 && goneRaw.body.error.code === 'file/not-found', 'and its bytes say the same')

  chat.cwd = cwdBefore
  await rm(blob, { force: true })
}

// ── live event feed ──────────────────────────────────────────────────────────
{
  const controller = new AbortController()
  const response = await fetch(`${base}/mobile/events`, { headers: { authorization: `Bearer ${token}` }, signal: controller.signal })
  ok(response.status === 200, `the event stream opens (got ${response.status})`)
  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  const opening = decoder.decode((await reader.read()).value)
  ok(opening.includes('retry: 3000'), 'the stream opens with a retry hint')

  const sessionEventHandlers = [...(listeners.get('session/event') ?? [])]
  ok(sessionEventHandlers.length === 1, 'the plugin subscribed to session/event exactly once')
  sessionEventHandlers[0]({ id: 'session-aaaa' }, sessionEvents[6])

  let frame = ''
  const deadline = Date.now() + 4000
  while (!frame.includes('session-event') && Date.now() < deadline) {
    const chunk = await Promise.race([reader.read(), new Promise((resolve) => setTimeout(() => resolve({ done: true, value: undefined }), 500))])
    if (chunk.value !== undefined) frame += decoder.decode(chunk.value)
  }
  ok(frame.includes('"type":"assistant/message"'), 'an appended assistant message reaches the phone')
  ok(frame.includes('ecco il file'), 'the streamed entry carries its text')

  sessionEventHandlers[0]({ id: 'session-aaaa' }, { seq: 99, type: 'request/header', time: 1, data: {} })
  controller.abort()
  await new Promise((resolve) => setTimeout(resolve, 100))
  ok(true, 'internal-only events are dropped without throwing')
}

// ── model catalog and selection ──────────────────────────────────────────────
{
  const listed = await authed('/mobile/models')
  ok(listed.status === 200, `the model catalog answers 200 (got ${listed.status})`)
  ok(listed.body.default.provider === 'deepseek-official' && listed.body.default.model === 'deepseek-flash', 'the deployment default travels with the catalog')
  ok(listed.body.groups.length === 3, `every provider group is reported (got ${listed.body.groups.length})`)
  ok(listed.body.failures.length === 1 && listed.body.failures[0].id === 'broken', 'a broken provider is reported rather than dropped')

  const mimo = listed.body.groups.find((group) => group.id === 'mimo')
  ok(mimo.name === 'MiMo', 'a group carries its display name')
  const flash = mimo.models.find((model) => model.id === 'mimo-v2.6-flash')
  ok(flash.vision === true, 'vision support is resolved from the LLM registry')
  ok(flash.contextWindow === 200_000, `the context window is exposed (got ${flash.contextWindow})`)
  ok(flash.reasoning.efforts.length === 2 && flash.reasoning.defaultEffort === 'low', 'reasoning efforts travel with the model')

  const pro = mimo.models.find((model) => model.id === 'mimo-v2.6-pro')
  ok(pro.vision === true && pro.reasoning === null, 'a model without declared efforts carries none')

  // A provider whose adapter cannot describe its models keeps them listed; the
  // badges are simply absent, which is the difference between an unknown
  // capability and a missing model.
  const glm = listed.body.groups.find((group) => group.id === 'glm')
  ok(glm.models.length === 1 && glm.models[0].vision === null, 'an unresolvable capability is null rather than hidden')

  const selected = await authed('/mobile/models', {
    method: 'POST',
    body: JSON.stringify({ sessionId: 'session-aaaa', provider: 'mimo', model: 'mimo-v2.6-pro', reasoningEffort: 'high' }),
  })
  ok(selected.status === 200, `selecting a model answers 200 (got ${selected.status})`)
  ok(modelSelections.length === 1, 'exactly one selection reached the controller')
  ok(modelSelections[0].sessionId === 'session-aaaa', 'the session is forwarded')
  ok(modelSelections[0].provider === 'mimo' && modelSelections[0].model === 'mimo-v2.6-pro', 'the provider and model are forwarded')
  ok(modelSelections[0].reasoningEffort === 'high', 'the reasoning effort is forwarded')
  ok(selected.body.selected.reasoningEffort === 'high', 'the host answer is echoed back')

  const noEffort = await authed('/mobile/models', {
    method: 'POST',
    body: JSON.stringify({ sessionId: 'session-aaaa', provider: 'glm', model: 'glm-5.3' }),
  })
  ok(noEffort.status === 200 && modelSelections.at(-1).reasoningEffort === undefined, 'an absent effort is not sent as undefined')

  const incomplete = await authed('/mobile/models', { method: 'POST', body: JSON.stringify({ provider: 'glm' }) })
  ok(incomplete.status === 400, `a selection without a session is refused (got ${incomplete.status})`)

  const unavailable = await authed('/mobile/models', {
    method: 'POST',
    body: JSON.stringify({ sessionId: 'session-aaaa', provider: 'glm', model: 'missing-model' }),
  })
  ok(unavailable.status === 409, `an unavailable model answers 409 (got ${unavailable.status})`)
  ok(unavailable.body.error.code === 'session/model-unavailable', 'the host failure keeps its code')
}

// ── approvals ────────────────────────────────────────────────────────────────
{
  const empty = await authed('/mobile/approvals')
  ok(empty.status === 200 && empty.body.items.length === 0, 'nothing is pending before anything asks')

  // The plugin answers as one listener on the harness's approval waterfall. It
  // must sit *before* the desktop's own listener, or the desktop would consume
  // every request and the phone would never be offered one.
  const handlers = [...(listeners.get('approval/request') ?? [])]
  ok(handlers.length === 1, `the plugin registered exactly one approval answerer (got ${handlers.length})`)
  ok(prependedEvents.has('approval/request'), 'the answerer registered ahead of the desktop listener')

  const session = { id: 'session-aaaa' }
  let offeredDownstream = false
  const phone = handlers[0](
    { agent: { session }, toolName: 'pwsh', callId: 'call-77', reason: 'audit text', displayReason: { en: 'Run the build', 'it-IT': 'Esegui la build' } },
    () => {
      offeredDownstream = true
      // The desktop's listener waits for a click; this is that wait.
      return new Promise(() => {})
    },
  )

  await new Promise((resolve) => setTimeout(resolve, 20))
  const listed = await authed('/mobile/approvals')
  ok(listed.body.items.length === 1, `a pending approval is listed (got ${listed.body.items.length})`)
  const item = listed.body.items[0]
  ok(item.toolName === 'pwsh', 'the tool name travels')
  ok(item.sessionId === 'session-aaaa', 'the session travels')
  ok(item.callId === 'call-77', 'the call id travels, so the transcript entry can be matched')
  ok(item.displayReason === 'Run the build', `the localized reason is flattened to one line (got ${item.displayReason})`)
  ok(Array.isArray(item.outcomes) && item.outcomes.includes('allowed-once') && !item.outcomes.includes('always'), 'only the two real outcomes are offered')
  ok(offeredDownstream, 'the same request is also offered to the desktop')
  ok(typeof item.createdAt === 'number', 'the approval carries a timestamp for the phone to age it')

  const allowed = await authed(`/mobile/approvals/${item.id}`, { method: 'POST', body: JSON.stringify({ outcome: 'allowed-once' }) })
  ok(allowed.status === 200, `answering answers 200 (got ${allowed.status})`)
  ok(allowed.body.outcome === 'allowed-once', 'the chosen outcome is echoed')
  ok((await phone) === 'allowed-once', 'the waterfall settles with the phone\'s outcome')

  const drained = await authed('/mobile/approvals')
  ok(drained.body.items.length === 0, 'a settled approval leaves the pending list')

  // A second answer loses the race, and losing a race is normal rather than a
  // server fault: the phone that answered first already settled it.
  const second = await authed(`/mobile/approvals/${item.id}`, { method: 'POST', body: JSON.stringify({ outcome: 'rejected' }) })
  ok(second.status === 409, `a second answer answers 409 (got ${second.status})`)
  ok(second.body.error.code === 'approval/settled', 'the conflict keeps its own code')
}

{
  const handlers = [...(listeners.get('approval/request') ?? [])]
  const request = { agent: { session: { id: 'session-bbbb' } }, toolName: 'write', callId: 'call-78' }

  const pending = handlers[0](request, () => new Promise(() => {}))
  await new Promise((resolve) => setTimeout(resolve, 20))
  const listed = await authed('/mobile/approvals')
  const id = listed.body.items[0].id

  const typo = await authed(`/mobile/approvals/${id}`, { method: 'POST', body: JSON.stringify({ outcome: 'yes' }) })
  ok(typo.status === 400, `an outcome outside the vocabulary is refused (got ${typo.status})`)

  const missing = await authed('/mobile/approvals/does-not-exist', { method: 'POST', body: JSON.stringify({ outcome: 'rejected' }) })
  ok(missing.status === 409, `an unknown approval answers 409 (got ${missing.status})`)

  const noBody = await authed(`/mobile/approvals/${id}`, { method: 'POST' })
  ok(noBody.status === 400, `answering without a body is refused (got ${noBody.status})`)

  // Clean up the request opened above so it cannot leak into the next block.
  await authed(`/mobile/approvals/${id}`, { method: 'POST', body: JSON.stringify({ outcome: 'rejected' }) })
  ok((await pending) === 'rejected', 'the refusal settles the request')
}

{
  const handlers = [...(listeners.get('approval/request') ?? [])]

  // An approval with no session has no chat to show it in, so it must go
  // straight down the chain instead of waiting for a phone that cannot display it.
  let delegated = false
  const outcome = await handlers[0]({ toolName: 'pwsh' }, () => {
    delegated = true
    return Promise.resolve('rejected')
  })
  ok(delegated, 'a request without a session is delegated')
  ok(outcome === 'rejected', 'the downstream outcome is returned unchanged')

  // The desktop wins the race when it answers first, and the phone's card is
  // withdrawn so nobody taps a button that no longer means anything.
  const desktop = await handlers[0](
    { agent: { session: { id: 'session-aaaa' } }, toolName: 'read', callId: 'call-88' },
    () => Promise.resolve('allowed-once'),
  )
  ok(desktop === 'allowed-once', `a desktop answer settles the request (got ${desktop})`)
  const afterDesktop = await authed('/mobile/approvals')
  ok(afterDesktop.body.items.length === 0, 'the phone\'s card is withdrawn once the desktop answers')
}

{
  const handlers = [...(listeners.get('approval/request') ?? [])]
  const original = instance.config.approvalWaitMs

  // With no browser attached, the harness's own chain reports `unavailable`
  // immediately. That is not a denial, and treating it as one would deny every
  // tool the moment the desktop window is closed.
  instance.config.approvalWaitMs = 400
  const phone = handlers[0](
    { agent: { session: { id: 'session-aaaa' } }, toolName: 'pwsh', callId: 'call-99' },
    () => Promise.resolve('unavailable'),
  )
  await new Promise((resolve) => setTimeout(resolve, 30))
  const listed = await authed('/mobile/approvals')
  ok(listed.body.items.length === 1, 'the phone is offered a request nobody downstream could answer')
  await authed(`/mobile/approvals/${listed.body.items[0].id}`, { method: 'POST', body: JSON.stringify({ outcome: 'allowed-once' }) })
  ok((await phone) === 'allowed-once', 'the phone can answer when no desktop is attached')

  // And when nobody answers at all, the wait is bounded and the request fails
  // closed rather than hanging the agent for as long as the app is closed.
  instance.config.approvalWaitMs = 60
  const abandoned = await handlers[0](
    { agent: { session: { id: 'session-aaaa' } }, toolName: 'pwsh', callId: 'call-100' },
    () => Promise.resolve('unavailable'),
  )
  ok(abandoned === 'unavailable', `an unanswered request fails closed (got ${abandoned})`)
  const swept = await authed('/mobile/approvals')
  ok(swept.body.items.length === 0, 'an abandoned request is swept out of the pending list')

  instance.config.approvalWaitMs = original
}

// ── questions ────────────────────────────────────────────────────────────────
{
  const empty = await authed('/mobile/questions')
  ok(empty.status === 200 && empty.body.items.length === 0, 'nothing is pending before anything is asked')

  // Same seam as approvals — the harness's question waterfall — so it has to sit
  // ahead of the desktop's own listener for the phone to be offered anything.
  const handlers = [...(listeners.get('user-questions/request') ?? [])]
  ok(handlers.length === 1, `the plugin registered exactly one question answerer (got ${handlers.length})`)
  ok(prependedEvents.has('user-questions/request'), 'the question answerer registered ahead of the desktop listener')

  let offeredDownstream = false
  const asked = handlers[0](
    {
      agent: { session: { id: 'session-aaaa' } },
      wait: { callId: 'call-ask' },
      questions: [
        {
          id: 'timing',
          header: 'Tempistica backend',
          question: 'Come procediamo con il deploy?',
          options: [
            { label: 'Adesso (Consigliato)', description: 'Faccio subito il deploy' },
            { label: 'Aspetta', description: 'Prima cambio il DNS' },
          ],
        },
      ],
    },
    () => {
      offeredDownstream = true
      return new Promise(() => {})
    },
  )

  await new Promise((resolve) => setTimeout(resolve, 20))
  const listed = await authed('/mobile/questions')
  ok(listed.body.items.length === 1, `a pending question is listed (got ${listed.body.items.length})`)
  const item = listed.body.items[0]
  ok(item.sessionId === 'session-aaaa', 'the session travels')
  ok(item.callId === 'call-ask', 'the call id travels, so the transcript card can be matched to it')
  ok(item.expired === false, 'a live question is not marked late')
  const question = item.questions[0]
  ok(question.id === 'timing', 'the question id travels, because the answer is keyed by it')
  ok(question.header === 'Tempistica backend', 'the header travels')
  ok(question.question === 'Come procediamo con il deploy?', 'the question text travels')
  ok(question.options.length === 2, 'every option travels')
  ok(question.options[0].label === 'Adesso (Consigliato)', 'an option keeps its label verbatim, marker included')
  ok(question.options[0].description === 'Faccio subito il deploy', 'an option keeps its description')
  ok(question.multiSelect === false, 'a single-choice question says so')
  ok(offeredDownstream, 'the same question is also offered to the desktop')

  const answered = await authed(`/mobile/questions/${item.id}`, {
    method: 'POST',
    body: JSON.stringify({ answers: [{ id: 'timing', selected: ['Adesso (Consigliato)'] }] }),
  })
  ok(answered.status === 200, `answering answers 200 (got ${answered.status})`)
  ok(answered.body.late === false, 'a live answer is not a late one')
  const settled = await asked
  ok(settled?.answers?.[0]?.id === 'timing', 'the waterfall settles with the phone\'s answer')
  ok(settled?.answers?.[0]?.selected?.[0] === 'Adesso (Consigliato)', 'the chosen label is forwarded verbatim')

  const drained = await authed('/mobile/questions')
  ok(drained.body.items.length === 0, 'an answered question leaves the list')

  const again = await authed(`/mobile/questions/${item.id}`, {
    method: 'POST',
    body: JSON.stringify({ answers: [{ id: 'timing', selected: ['Aspetta'] }] }),
  })
  ok(again.status === 409, `a second answer answers 409 (got ${again.status})`)
  ok(again.body.error.code === 'question/settled', 'the conflict keeps its own code')
}

{
  const handlers = [...(listeners.get('user-questions/request') ?? [])]
  // `multi_select` is the tool schema's spelling and `multiSelect` the harness
  // type's; both appear in real logs, so both have to be understood.
  const asked = handlers[0](
    {
      agent: { session: { id: 'session-aaaa' } },
      wait: { callId: 'call-multi' },
      questions: [{ id: 'next', question: 'Quale strada?', multi_select: true, options: [{ label: 'A' }, { label: 'B' }] }],
    },
    () => new Promise(() => {}),
  )
  await new Promise((resolve) => setTimeout(resolve, 20))
  const listed = await authed('/mobile/questions')
  const id = listed.body.items[0].id
  ok(listed.body.items[0].questions[0].multiSelect === true, 'the snake_case spelling of multi-select is understood')

  const noAnswers = await authed(`/mobile/questions/${id}`, { method: 'POST', body: JSON.stringify({ answers: [] }) })
  ok(noAnswers.status === 400, `an empty answer set is refused (got ${noAnswers.status})`)

  const noId = await authed(`/mobile/questions/${id}`, { method: 'POST', body: JSON.stringify({ answers: [{ selected: ['A'] }] }) })
  ok(noId.status === 400, `an answer without an id is refused (got ${noId.status})`)

  const badSelected = await authed(`/mobile/questions/${id}`, { method: 'POST', body: JSON.stringify({ answers: [{ id: 'next', selected: 'A' }] }) })
  ok(badSelected.status === 400, `a selection that is not a list is refused (got ${badSelected.status})`)

  const noBody = await authed(`/mobile/questions/${id}`, { method: 'POST' })
  ok(noBody.status === 400, `answering without a body is refused (got ${noBody.status})`)

  await authed(`/mobile/questions/${id}`, { method: 'POST', body: JSON.stringify({ answers: [{ id: 'next', selected: ['A', 'B'] }] }) })
  const settled = await asked
  ok(settled?.answers?.[0]?.selected?.length === 2, 'a multi-select answer carries every chosen label')
}

{
  const handlers = [...(listeners.get('user-questions/request') ?? [])]

  // A question set with nothing renderable has no card to show, so it goes
  // straight down the chain rather than waiting for a phone that cannot draw it.
  let delegated = false
  const passed = await handlers[0]({ agent: { session: { id: 'session-aaaa' } }, questions: [{ id: 'x', question: '   ' }] }, () => {
    delegated = true
    return Promise.resolve({ answers: [{ id: 'x', selected: ['dalla catena'] }] })
  })
  ok(delegated, 'a question with no text is delegated')
  ok(passed?.answers?.[0]?.selected?.[0] === 'dalla catena', 'the downstream answer is returned unchanged')

  const noSession = await handlers[0]({ questions: [{ id: 'x', question: 'Ciao?' }] }, () => Promise.resolve(undefined))
  ok(noSession === undefined, 'a question with no session is delegated too')

  // The desktop wins when it answers first, and the phone's card is withdrawn.
  const desktop = await handlers[0](
    {
      agent: { session: { id: 'session-aaaa' } },
      wait: { callId: 'call-desktop' },
      questions: [{ id: 'd', question: 'Quale?', options: [{ label: 'A' }] }],
    },
    () => Promise.resolve({ answers: [{ id: 'd', selected: ['A'] }] }),
  )
  ok(desktop?.answers?.[0]?.selected?.[0] === 'A', 'a desktop answer settles the question')
  const after = await authed('/mobile/questions')
  ok(after.body.items.length === 0, 'the phone\'s card is withdrawn once the desktop answers')
}

{
  const handlers = [...(listeners.get('user-questions/request') ?? [])]
  const original = instance.config.questionWaitMs

  // Past the harness's two-minute window the question is `continued` and still
  // answerable, so it must not disappear from the phone: a card that vanished
  // would hide the very thing the reader is still allowed to answer.
  instance.config.questionWaitMs = 40
  const asked = handlers[0](
    {
      agent: { session: { id: 'session-aaaa' } },
      wait: { callId: 'call-late' },
      questions: [{ id: 'l', question: 'Ancora lì?', options: [{ label: 'Sì' }] }],
    },
    () => Promise.resolve(undefined),
  )
  ok((await asked) === undefined, 'an unanswered question returns the chain\'s own answer')

  const late = await authed('/mobile/questions')
  ok(late.body.items.length === 1, `a timed-out question stays listed (got ${late.body.items.length})`)
  ok(late.body.items[0].expired === true, 'and it is marked as no longer live')

  const lateId = late.body.items[0].id
  const accepted = await authed(`/mobile/questions/${lateId}`, {
    method: 'POST',
    body: JSON.stringify({ answers: [{ id: 'l', selected: ['Sì'] }] }),
  })
  ok(accepted.status === 200, `a late answer is accepted (got ${accepted.status})`)
  ok(accepted.body.late === true, 'and it reports itself as late')
  ok(lateAnswers.length === 1, 'exactly one late answer reached the harness service')
  ok(lateAnswers[0].callId === 'call-late', 'the tool call id is what the service is addressed by')
  ok(
    lateAnswers[0].sessionId === 'session-aaaa',
    'the service is handed the live agent for the session, not a session id',
  )
  const swept = await authed('/mobile/questions')
  ok(swept.body.items.length === 0, 'an answered late question leaves the list')

  // The harness returns false — not an error — for a question it no longer
  // holds, and that has to become a conflict rather than a success.
  instance.config.questionWaitMs = 20
  await handlers[0](
    {
      agent: { session: { id: 'session-aaaa' } },
      wait: { callId: 'call-gone' },
      questions: [{ id: 'g', question: 'Ci sei?', options: [{ label: 'Sì' }] }],
    },
    () => Promise.resolve(undefined),
  )
  const goneList = await authed('/mobile/questions')
  const gone = await authed(`/mobile/questions/${goneList.body.items[0].id}`, {
    method: 'POST',
    body: JSON.stringify({ answers: [{ id: 'g', selected: ['Sì'] }] }),
  })
  ok(gone.status === 409, `a question the harness dropped answers 409 (got ${gone.status})`)
  ok(gone.body.error.code === 'question/settled', 'with its own code')

  // A reply already queued for the model is the same lost race, not a failure.
  await handlers[0](
    {
      agent: { session: { id: 'session-aaaa' } },
      wait: { callId: 'call-queued' },
      questions: [{ id: 'q', question: 'Di nuovo?', options: [{ label: 'Sì' }] }],
    },
    () => Promise.resolve(undefined),
  )
  const queuedList = await authed('/mobile/questions')
  const queued = await authed(`/mobile/questions/${queuedList.body.items[0].id}`, {
    method: 'POST',
    body: JSON.stringify({ answers: [{ id: 'q', selected: ['Sì'] }] }),
  })
  ok(queued.status === 409, `a queued reply answers 409 (got ${queued.status})`)
  ok(queued.body.error.code === 'question/settled', 'and keeps the same code')

  instance.config.questionWaitMs = original
  lateAnswers.length = 0
}

// ── permissions ──────────────────────────────────────────────────────────────
{
  const listed = await authed('/mobile/permissions')
  ok(listed.status === 200, `the permission catalog is served (got ${listed.status})`)
  ok(listed.body.current === null, 'without a session there is no current preset to report')
  ok(listed.body.defaultPreset === 'workspace-write', 'the default for future sessions travels')

  const options = listed.body.options
  ok(options.length === 4, `every advertised preset travels (got ${options.length})`)
  ok(options[0].value === 'read-only', 'the table keeps its declaration order')
  ok(options[0].name === 'Read Only', 'the host keeps its own label, not one the phone invented')
  ok(options[0].description === 'Read files and run read-only commands.', 'and its own description')
  ok(options[0].sandbox === 'read-only' && options[0].approval === 'ask', 'the knobs are filled in from resolve')

  const full = options.find((option) => option.value === 'danger-full-access')
  ok(full.sandbox === 'danger-full-access', 'the full-access preset reports its sandbox')
  ok(full.approval === 'never', 'and that it bundles "never ask" — the consequence worth showing')
  const auto = options.find((option) => option.value === 'auto')
  ok(auto.sandbox === null && auto.approval === null, 'a preset the route cannot resolve reports unknown knobs, not wrong ones')

  // `custom` is the derived "matches no preset" state: it may label the current
  // control but is never something a client may select.
  ok(!options.some((option) => option.value === 'custom'), 'the derived custom state is not offered as a choice')

  const forSession = await authed('/mobile/permissions?sessionId=session-aaaa')
  ok(forSession.body.sessionId === 'session-aaaa', 'the session is echoed back')
  ok(forSession.body.current === 'workspace-write', `the session's own preset is reported (got ${forSession.body.current})`)

  const unpinned = await authed('/mobile/permissions?sessionId=session-unpinned')
  ok(unpinned.status === 200, 'a session the host cannot derive a preset for still lists the catalog')
  ok(unpinned.body.current === null, 'with an unknown current value rather than a failure')

  const missing = await authed('/mobile/permissions?sessionId=session-zzzz')
  ok(missing.status === 200 && missing.body.current === null, 'an unknown session is reported as unknown, not as an error')
}

{
  const switched = await authed('/mobile/permissions', {
    method: 'POST',
    body: JSON.stringify({ sessionId: 'session-aaaa', preset: 'danger-full-access' }),
  })
  ok(switched.status === 200, `switching answers 200 (got ${switched.status})`)
  ok(switched.body.current === 'danger-full-access', 'and reports the preset now in force')
  ok(presetSwitches.length === 1, 'exactly one switch reached the harness')
  ok(presetSwitches[0].sessionId === 'session-aaaa', 'the switch names the session')
  ok(presetSwitches[0].preset === 'danger-full-access', 'and the preset')

  const after = await authed('/mobile/permissions?sessionId=session-aaaa')
  ok(after.body.current === 'danger-full-access', 'a later read agrees with the switch')

  // A typo must not become a permission: the route checks the name against the
  // host's own catalog instead of forwarding whatever arrived.
  const typo = await authed('/mobile/permissions', {
    method: 'POST',
    body: JSON.stringify({ sessionId: 'session-aaaa', preset: 'full-access' }),
  })
  ok(typo.status === 400, `a preset this harness does not have is refused (got ${typo.status})`)
  ok(typo.body.error.code === 'permission/unknown', 'with its own code')
  ok(presetSwitches.length === 1, 'and nothing reached the harness')

  const noSession = await authed('/mobile/permissions', {
    method: 'POST',
    body: JSON.stringify({ preset: 'read-only' }),
  })
  ok(noSession.status === 400, `a switch with no session is refused (got ${noSession.status})`)
  const noPreset = await authed('/mobile/permissions', {
    method: 'POST',
    body: JSON.stringify({ sessionId: 'session-aaaa' }),
  })
  ok(noPreset.status === 400, `a switch with no preset is refused (got ${noPreset.status})`)
  const notLoaded = await authed('/mobile/permissions', {
    method: 'POST',
    body: JSON.stringify({ sessionId: 'session-zzzz', preset: 'read-only' }),
  })
  ok(notLoaded.status === 404, `a session this host does not hold is refused (got ${notLoaded.status})`)
  ok(notLoaded.body.error.code === 'permission/no-session', 'with its own code')

  // Auto runs an admission check that can refuse, and the host's own words are
  // more use to the reader than a generic failure.
  const refused = await authed('/mobile/permissions', {
    method: 'POST',
    body: JSON.stringify({ sessionId: 'session-aaaa', preset: 'auto' }),
  })
  ok(refused.status === 409, `a refused switch is a conflict (got ${refused.status})`)
  ok(refused.body.error.code === 'permission/refused', 'with its own code')
  ok(/Auto is not available/.test(refused.body.error.message), 'and the host\'s own explanation')

  // A deployment without the service offers no selector at all, which is worth
  // saying rather than showing an empty picker.
  permissionService = undefined
  const unmounted = await authed('/mobile/permissions')
  ok(unmounted.status === 503, `a harness without permission presets says so (got ${unmounted.status})`)
  ok(unmounted.body.error.code === 'permission/unavailable', 'with its own code')
  const unmountedSwitch = await authed('/mobile/permissions', {
    method: 'POST',
    body: JSON.stringify({ sessionId: 'session-aaaa', preset: 'read-only' }),
  })
  ok(unmountedSwitch.status === 503, 'and refuses a switch too')
  permissionService = permissionPresets

  chosenPresets.set('session-aaaa', 'workspace-write')
  presetSwitches.length = 0
}

// ── a host may refuse a remote full-access switch ────────────────────────────
{
  const original = instance.config.allowRemoteFullAccess
  instance.config.allowRemoteFullAccess = false

  const blocked = await authed('/mobile/permissions', {
    method: 'POST',
    body: JSON.stringify({ sessionId: 'session-aaaa', preset: 'danger-full-access' }),
  })
  ok(blocked.status === 403, `a preset that stops the agent asking is refused when the host says so (got ${blocked.status})`)
  ok(blocked.body.error.code === 'permission/remote-forbidden', 'the refusal has its own code')
  ok(presetSwitches.length === 0, 'nothing reached the host')

  // The same switch on a preset that still asks is allowed: the check reads the
  // approval knob, not the preset's name.
  const asks = await authed('/mobile/permissions', {
    method: 'POST',
    body: JSON.stringify({ sessionId: 'session-aaaa', preset: 'read-only' }),
  })
  ok(asks.status === 200, `a preset that still asks is still switchable (got ${asks.status})`)

  instance.config.allowRemoteFullAccess = original
  chosenPresets.set('session-aaaa', 'workspace-write')
  presetSwitches.length = 0
}

// ── link previews ────────────────────────────────────────────────────────────
{
  webPages.set('https://example.com/docs/guide', {
    url: 'https://example.com/docs/guide',
    statusCode: 200,
    truncated: false,
    body: {
      kind: 'html',
      content: `<!doctype html><html><head><title>La &amp; guida</title>
<meta name="description" content="Come si usa &quot;la guida&quot; in pratica">
<script>window.track('never readable')</script><style>.x { color: red }</style>
</head><body><nav>Home Docs Blog Login</nav><h1>Introduzione</h1>
<p>Il primo paragrafo, fuori dall'articolo, parla d'altro.</p>
<article><h2>Contenuto vero</h2>
<p>Il testo che conta &mdash; quello che un lettore cerca, con entit&#224; decodificate.</p>
<ul><li>Prima voce</li><li>Seconda voce</li></ul>
<script>inside the article too</script></article>
<footer>&copy; Example</footer></body></html>`,
      },
  })
  webPages.set('https://example.com/notes.txt', {
    url: 'https://example.com/notes.txt',
    statusCode: 200,
    truncated: false,
    body: { kind: 'text', content: 'riga uno\nriga due' },
  })
  webPages.set('https://example.com/cut.txt', {
    url: 'https://example.com/cut.txt',
    statusCode: 200,
    truncated: false,
    body: { kind: 'text', content: 'a'.repeat(30_001) },
  })
  webPages.set('https://example.com/capped-fetch', {
    url: 'https://example.com/capped-fetch',
    statusCode: 200,
    truncated: true,
    body: { kind: 'html', content: '<html><head><title>Pagina</title></head><body><p>breve</p></body></html>' },
  })
  webPages.set('https://example.com/private', () => {
    const error = new Error('URL hostname "private" resolves to a non-public IP address')
    error.code = 'WEB_BLOCKED_URL'
    throw error
  })
  webPages.set('https://example.com/slow', () => {
    const error = new Error('web fetch timed out')
    error.code = 'WEB_FETCH_TIMEOUT'
    throw error
  })
  webPages.set('https://example.com/picture', () => {
    const error = new Error('unsupported content type "image/png"')
    error.code = 'WEB_UNSUPPORTED_CONTENT_TYPE'
    throw error
  })
  webPages.set('https://example.com/huge', () => {
    const error = new Error('response exceeds the maximum of 5000000 bytes')
    error.code = 'WEB_FETCH_TOO_LARGE'
    throw error
  })
  webPages.set('https://example.com/broken', () => {
    throw new Error('socket hang up')
  })

  // The page the phone reads is the page's own words: the article region wins,
  // scripts and chrome are dropped, entities become characters again.
  const guide = await authed(`/mobile/preview?url=${encodeURIComponent('https://example.com/docs/guide')}`)
  ok(guide.status === 200, `an html page is previewed (got ${guide.status})`)
  ok(guide.body.kind === 'html', 'and reports the kind it was reduced from')
  ok(guide.body.title === 'La & guida', `the title is decoded (got ${JSON.stringify(guide.body.title)})`)
  ok(guide.body.description === 'Come si usa "la guida" in pratica', 'the meta description travels, decoded')
  ok(guide.body.text.includes('Contenuto vero'), 'the article region supplies the text')
  ok(guide.body.text.includes('Il testo che conta — quello che un lettore cerca'), 'em-dashes and numeric entities decode')
  ok(guide.body.text.includes('• Prima voce'), 'list items keep their marker')
  ok(!guide.body.text.includes('window.track'), 'scripts never reach the phone')
  ok(!guide.body.text.includes('color: red'), 'styles neither')
  ok(!guide.body.text.includes('Home Docs Blog'), 'and site chrome neither')
  ok(!guide.body.text.includes('inside the article too'), 'a script inside the article is dropped with the rest')
  ok(!guide.body.text.includes('Il primo paragrafo'), 'prose outside the article region does not dilute the preview')
  ok(guide.body.truncated === false, 'a whole page reports itself uncut')
  ok(guide.body.statusCode === 200, 'the fetch status travels')

  // Plain text is already readable: it passes through, capped.
  const notes = await authed(`/mobile/preview?url=${encodeURIComponent('https://example.com/notes.txt')}`)
  ok(notes.status === 200 && notes.body.kind === 'text', 'a plain-text page keeps its kind')
  ok(notes.body.text === 'riga uno\nriga due', 'and its exact content')
  ok(notes.body.title === undefined, 'with no title invented for it')

  const cut = await authed(`/mobile/preview?url=${encodeURIComponent('https://example.com/cut.txt')}`)
  ok(cut.body.text.length === 30_000, `the phone-size cap applies (got ${cut.body.text.length})`)
  ok(cut.body.truncated === true, 'and says so')

  const capped = await authed(`/mobile/preview?url=${encodeURIComponent('https://example.com/capped-fetch')}`)
  ok(capped.body.truncated === true, "the fetcher's own truncation is not lost on the way")

  // Two spellings of one page are one fetch, and a repeat is not a fetch at all.
  const firstCount = webFetches.filter((url) => url === 'https://example.com/docs/guide').length
  await authed(`/mobile/preview?url=${encodeURIComponent('https://example.com:443/docs/guide')}`)
  await authed(`/mobile/preview?url=${encodeURIComponent(' https://example.com/docs/guide ')}`)
  const afterRepeats = webFetches.filter((url) => url === 'https://example.com/docs/guide').length
  ok(firstCount === 1 && afterRepeats === 1, 'canonical spellings share one cache entry')
  const cached = instance.previewCache.get('https://example.com/docs/guide')
  cached.expiresAt = Date.now() - 1
  await authed(`/mobile/preview?url=${encodeURIComponent('https://example.com/docs/guide')}`)
  ok(webFetches.filter((url) => url === 'https://example.com/docs/guide').length === 2, 'an expired entry is fetched again, not served')

  // Every failure the harness's fetcher can name keeps its own distinction.
  const blocked = await authed(`/mobile/preview?url=${encodeURIComponent('https://example.com/private')}`)
  ok(blocked.status === 403, `a blocked address is forbidden (got ${blocked.status})`)
  ok(blocked.body.error.code === 'preview/blocked', 'with its own code')
  ok(/non-public IP/.test(blocked.body.error.message), 'and the fetcher\'s own words')

  const slow = await authed(`/mobile/preview?url=${encodeURIComponent('https://example.com/slow')}`)
  ok(slow.status === 504 && slow.body.error.code === 'preview/timeout', 'a timeout is its own failure, not a generic one')

  const picture = await authed(`/mobile/preview?url=${encodeURIComponent('https://example.com/picture')}`)
  ok(picture.status === 415 && picture.body.error.code === 'preview/unsupported', 'an image the reader cannot show says which kind it refused')

  const huge = await authed(`/mobile/preview?url=${encodeURIComponent('https://example.com/huge')}`)
  ok(huge.status === 413 && huge.body.error.code === 'preview/too-large', 'an oversized body says so')

  const broken = await authed(`/mobile/preview?url=${encodeURIComponent('https://example.com/broken')}`)
  ok(broken.status === 502 && broken.body.error.code === 'preview/failed', 'an unclassified failure is a gateway failure')

  webMounted = undefined
  const noSeam = await authed(`/mobile/preview?url=${encodeURIComponent('https://example.com/docs/guide')}`)
  ok(noSeam.status === 503 && noSeam.body.error.code === 'preview/unavailable', 'a harness without the web seam says so instead of fetching anyway')
  webMounted = webSeam

  const noUrl = await authed('/mobile/preview')
  ok(noUrl.status === 400 && noUrl.body.error.code === 'preview/invalid', 'a request with no url parameter is refused')
  const localFile = await authed(`/mobile/preview?url=${encodeURIComponent('file:///etc/passwd')}`)
  ok(localFile.status === 400 && localFile.body.error.code === 'preview/invalid', 'a non-http scheme is refused before any fetch')
  ok(!webFetches.includes('file:///etc/passwd'), 'and never reaches the fetcher')
  const ftp = await authed(`/mobile/preview?url=${encodeURIComponent('ftp://example.com/x')}`)
  ok(ftp.status === 400, 'ftp is refused too')

  webFetches.length = 0
  webPages.clear()
}

// ── device ids, rotation, and revoking a phone from another phone ────────────
{
  // A separate host on its own state file: this block deliberately pairs more
  // than one device and revokes them, and the suite above asserts that exactly
  // one device exists.
  const devicesHome = fileURLToPath(new URL('./_runtime-devices/', import.meta.url))
  await rm(devicesHome, { recursive: true, force: true })
  const host = new MobileSync(ctx, { host: '127.0.0.1', port: 0 })
  host.configPath = fileURLToPath(new URL('./_runtime-devices/storages/mobile-sync.json', import.meta.url))
  await host[Symbol.for('cordis.init')]()
  const baseDevices = `http://127.0.0.1:${host.port}`

  // A code minted from the PC is the way back for a phone that lost its token,
  // since a name no longer recovers anything.
  const minted = await fetch(`${baseDevices}/mobile/pairing-code`)
  ok(minted.status === 200, `the PC can mint a pairing code (got ${minted.status})`)
  const mintedBody = await minted.json()
  ok(typeof mintedBody.code === 'string' && mintedBody.code.length > 10, 'the minted code is returned')
  ok(host.setupCode === mintedBody.code, 'the minted code is the one in force')

  const first = await fetch(`${baseDevices}/mobile/pair`, {
    method: 'POST',
    body: JSON.stringify({ code: mintedBody.code, device: 'Pixel 8' }),
  })
  ok(first.status === 200, `the minted code pairs a device (got ${first.status})`)
  const firstDevice = await first.json()

  // Same label, second phone: a name is a label, so this is a second device with
  // its own id and its own token rather than the first phone's credentials.
  const secondCode = await (await fetch(`${baseDevices}/mobile/pairing-code`)).json()
  const second = await fetch(`${baseDevices}/mobile/pair`, {
    method: 'POST',
    body: JSON.stringify({ code: secondCode.code, device: 'Pixel 8' }),
  })
  ok(second.status === 200, `two phones may share a label (got ${second.status})`)
  const secondDevice = await second.json()
  ok(secondDevice.token !== firstDevice.token, 'the second phone gets its own token')
  ok(secondDevice.id !== firstDevice.id, 'the second phone gets its own id')

  const listedResponse = await fetch(`${baseDevices}/mobile/devices`, { headers: { authorization: `Bearer ${firstDevice.token}` } })
  const listed = await listedResponse.json()
  ok(listedResponse.status === 200, `the device list answers 200 (got ${listedResponse.status})`)
  ok(listed.items.length === 2, `both devices are listed (got ${listed.items.length})`)
  ok(listed.items.some((device) => device.id === firstDevice.id && device.current === true), 'the list marks the caller')
  ok(listed.items.every((device) => device.token === undefined), 'the device list never carries tokens')

  const revokedResponse = await fetch(`${baseDevices}/mobile/devices/${secondDevice.id}`, {
    method: 'DELETE',
    headers: { authorization: `Bearer ${firstDevice.token}` },
  })
  ok(revokedResponse.status === 200, `revoking another device answers 200 (got ${revokedResponse.status})`)
  const revoked = await revokedResponse.json()
  ok(revoked.self === false, 'revoking someone else is not a self-revoke')
  const dead = await fetch(`${baseDevices}/mobile/sessions`, { headers: { authorization: `Bearer ${secondDevice.token}` } })
  ok(dead.status === 401, 'a revoked token stops working immediately')

  const unknownDevice = await fetch(`${baseDevices}/mobile/devices/nope`, {
    method: 'DELETE',
    headers: { authorization: `Bearer ${firstDevice.token}` },
  })
  ok(unknownDevice.status === 404, `revoking an unknown id answers 404 (got ${unknownDevice.status})`)

  // A phone may revoke itself — that is "unpair" — and its token dies with it.
  const selfResponse = await fetch(`${baseDevices}/mobile/devices/${firstDevice.id}`, {
    method: 'DELETE',
    headers: { authorization: `Bearer ${firstDevice.token}` },
  })
  ok(selfResponse.status === 200, `a device can revoke itself (got ${selfResponse.status})`)
  ok((await selfResponse.json()).self === true, 'the reply says the caller revoked itself')
  const afterSelf = await fetch(`${baseDevices}/mobile/sessions`, { headers: { authorization: `Bearer ${firstDevice.token}` } })
  ok(afterSelf.status === 401, 'a self-revoked token stops working')

  // The limiter is checked last because it deliberately poisons this peer.
  let refusedForLimit = 0
  for (let attempt = 0; attempt < 12; attempt += 1) {
    const response = await fetch(`${baseDevices}/mobile/pair`, {
      method: 'POST',
      body: JSON.stringify({ code: `guess-${attempt}`, device: 'Bruteforce' }),
    })
    if (response.status === 429) refusedForLimit += 1
  }
  ok(refusedForLimit === 2, `the pairing route refuses past its window (got ${refusedForLimit})`)

  // Minting is the PC's privilege: a request arriving over the network is told
  // nothing, even though the route is otherwise unauthenticated.
  let remoteRefusal = null
  try {
    await host.routePairingCode({ socket: { remoteAddress: '192.168.1.50' } }, {})
  } catch (error) {
    remoteRefusal = error
  }
  ok(remoteRefusal?.status === 403 && remoteRefusal?.code === 'loopback-only', 'a remote peer cannot mint a pairing code')

  host.server.closeAllConnections()
  await new Promise((resolve) => host.server.close(resolve))
  await rm(devicesHome, { recursive: true, force: true })
}

// ── version handshake and a whole-file state write ───────────────────────────
{
  const health = await authed('/mobile/health')
  ok(health.status === 200, `health answers 200 (got ${health.status})`)
  ok(typeof health.body.apiVersion === 'number' && health.body.apiVersion >= 2, 'health reports the wire version')
  ok(typeof health.body.pluginVersion === 'string' && health.body.pluginVersion.length > 0, 'health reports the plugin version')
  ok(health.body.devices === 1, 'health counts the paired devices')

  const status = await authed('/mobile/status')
  ok(status.body.apiVersion === health.body.apiVersion, 'the status payload carries the same wire version')
  ok(status.body.devices[0].id === deviceId, 'the status payload names the caller by id')

  const stored = JSON.parse(await readFile(instance.configPath, 'utf8'))
  ok(stored.version === 3, `the state file is written as version 3 (got ${stored.version})`)
  ok(stored.devices.every((device) => typeof device.id === 'string' && device.id.length === 12), 'every stored device carries its id')
  const leftovers = (await readdir(dirname(instance.configPath))).filter((name) => name.endsWith('.tmp'))
  ok(leftovers.length === 0, `an atomic write leaves no temporary file behind (got ${leftovers.join(', ')})`)
}

// ── the harness's own files are not for the phone ───────────────────────────
{
  const stateRead = await authed(`/mobile/file?ref=${encodeURIComponent(instance.configPath)}`)
  ok(stateRead.status === 403, `the device table is not readable through /mobile/file (got ${stateRead.status})`)
  ok(stateRead.body.error?.code === 'file/forbidden', `the refusal is a policy one (got ${stateRead.body.error?.code})`)

  // A note an agent could have linked is still served.
  const notes = fileURLToPath(new URL('./_runtime-notes/', import.meta.url))
  await mkdir(notes, { recursive: true })
  const note = path.join(notes, 'nota.md')
  await writeFile(note, '# nota\n\nciao\n', 'utf8')
  const read = await authed(`/mobile/file?ref=${encodeURIComponent(note)}`)
  ok(read.status === 200, `a normal file still opens (got ${read.status})`)
  ok(read.body.text.includes('ciao'), 'the file body is served')
  await rm(notes, { recursive: true, force: true })
}

// ── an unreadable state file is kept aside, not overwritten ─────────────────
{
  const corruptHome = fileURLToPath(new URL('./_runtime-corrupt/', import.meta.url))
  await rm(corruptHome, { recursive: true, force: true })
  const corruptPath = fileURLToPath(new URL('./_runtime-corrupt/storages/mobile-sync.json', import.meta.url))
  await mkdir(dirname(corruptPath), { recursive: true })
  await writeFile(corruptPath, '{ this is not json', 'utf8')

  const recovered = new MobileSync(ctx, { host: '127.0.0.1', port: 0 })
  recovered.configPath = corruptPath
  await recovered[Symbol.for('cordis.init')]()
  ok(recovered.setupCode.length > 0, 'an unreadable state file boots with a code instead of locking everyone out')
  const persisted = JSON.parse(await readFile(corruptPath, 'utf8'))
  ok(persisted.setupCode === recovered.setupCode, 'the recovery code is persisted, not just logged')
  const kept = (await readdir(dirname(corruptPath))).some((name) => name.includes('.corrupt-'))
  ok(kept, 'the unreadable file is kept aside rather than deleted')

  recovered.server.closeAllConnections()
  await new Promise((resolve) => recovered.server.close(resolve))
  await rm(corruptHome, { recursive: true, force: true })
}

// ── a nonsensical port is a boot refusal, not a surprise bind ───────────────
{
  const badPath = fileURLToPath(new URL('./_runtime-badport/storages/mobile-sync.json', import.meta.url))
  let refusedPort = null
  try {
    const bad = new MobileSync(ctx, { host: '127.0.0.1', port: 'not-a-port' })
    bad.configPath = badPath
    await bad[Symbol.for('cordis.init')]()
  } catch (error) {
    refusedPort = error
  }
  ok(refusedPort !== null, 'a non-numeric port is refused at boot')
  ok(String(refusedPort?.message).includes('port'), 'the refusal names the port')
  await rm(fileURLToPath(new URL('./_runtime-badport/', import.meta.url)), { recursive: true, force: true })
}

// ── a stalled SSE reader is dropped, and unknown routes are one bucket ──────
{
  let destroyed = 0
  const stalled = { writableLength: 8 * 1024 * 1024, write() {}, destroy() { destroyed += 1 } }
  instance.streams.add(stalled)
  instance.broadcast({ event: 'probe' })
  ok(destroyed === 1, 'a stalled SSE reader is dropped instead of buffered forever')
  ok(!instance.streams.has(stalled), 'the dropped reader is forgotten')

  for (let index = 0; index < 30; index += 1) await call(`/mobile/unknown-${index}`)
  ok(instance.requestCounts.has('GET /mobile/*'), 'unknown routes fold into one bucket')
  ok(instance.requestCounts.size <= 64, `unauthenticated paths cannot grow the counter (got ${instance.requestCounts.size})`)
}

// ── teardown ─────────────────────────────────────────────────────────────────
{
  for (const dispose of effects) await dispose()
  instance.server.closeAllConnections()
  await new Promise((resolve) => instance.server.close(resolve))
  ok(true, 'the listener closes cleanly')
}

// ── persistence and re-pairing after a restart ───────────────────────────────
{
  // A second instance reads the same state file, exactly as a harness restart
  // would: the paired device must survive, and a spent code must be replaced.
  const restarted = new MobileSync(ctx, { host: '127.0.0.1', port: 0 })
  await restarted[Symbol.for('cordis.init')]()
  const base2 = `http://127.0.0.1:${restarted.port}`

  const stillPaired = await fetch(`${base2}/mobile/sessions`, { headers: { authorization: `Bearer ${token}` } })
  ok(stillPaired.status === 200, `a paired token survives a restart (got ${stillPaired.status})`)

  const statusAfter = await fetch(`${base2}/mobile/status`, { headers: { authorization: `Bearer ${token}` } })
  const statusBody = await statusAfter.json()
  ok(statusBody.devices.length === 1, 'the paired device is still listed after a restart')
  ok(statusBody.pairingOpen === true, 'a spent code is replaced on boot, so pairing is open again')
  ok(restarted.setupCode.length > 0, 'the boot mint produced a usable code')
  ok(restarted.setupCode !== setupCode, 'the replacement code differs from the spent one')

  // The replacement is persisted before it is announced, so the value in the
  // log and the value on disk always agree.
  const onDisk = JSON.parse(await readFile(restarted.configPath, 'utf8'))
  ok(onDisk.setupCode === restarted.setupCode, 'the announced code was persisted before being logged')

  const joined = await fetch(`${base2}/mobile/pair`, {
    method: 'POST',
    body: JSON.stringify({ code: restarted.setupCode, device: 'Secondo telefono' }),
  })
  ok(joined.status === 200, `the replacement code pairs a second device (got ${joined.status})`)
  const second = await joined.json()
  ok(second.token !== token, 'the second device receives its own token')

  const admitted = await fetch(`${base2}/mobile/sessions`, { headers: { authorization: `Bearer ${second.token}` } })
  ok(admitted.status === 200, 'the second device can read sessions immediately')

  const spentAgain = await fetch(`${base2}/mobile/pair`, {
    method: 'POST',
    body: JSON.stringify({ code: 'qualcosa-di-sbagliato', device: 'Terzo telefono' }),
  })
  ok(spentAgain.status === 403, `an unknown device cannot join a closed host (got ${spentAgain.status})`)

  // A known device that lost its token may **not** pair again by name: the name
  // is a label printed on the PC's device list, not a credential. The way back is
  // a code minted from the PC, or a rotation performed with the token it still
  // holds.
  const returning = await fetch(`${base2}/mobile/pair`, {
    method: 'POST',
    body: JSON.stringify({ code: 'anything', device: 'Secondo telefono' }),
  })
  ok(returning.status === 403, `a known name cannot recover a token (got ${returning.status})`)

  const rotated = await fetch(`${base2}/mobile/pair`, {
    method: 'POST',
    headers: { authorization: `Bearer ${second.token}` },
    body: JSON.stringify({ device: 'Secondo telefono' }),
  })
  ok(rotated.status === 200, `a device that still holds its token can rotate it (got ${rotated.status})`)
  const rotatedBody = await rotated.json()
  ok(rotatedBody.rotated === true, 'rotation reports itself')
  ok(rotatedBody.token !== second.token, 'rotation issues a different token')
  const rotatedOld = await fetch(`${base2}/mobile/sessions`, { headers: { authorization: `Bearer ${second.token}` } })
  ok(rotatedOld.status === 401, 'the pre-rotation token stops working')
  const rotatedNew = await fetch(`${base2}/mobile/sessions`, { headers: { authorization: `Bearer ${rotatedBody.token}` } })
  ok(rotatedNew.status === 200, 'the rotated token works')

  // The PC can mint a code without a restart, and that code is the one that
  // pairs — which is what replaces "re-pair by name" as the recovery path.
  const mintedAfterBoot = await fetch(`${base2}/mobile/pairing-code`)
  ok(mintedAfterBoot.status === 200, `a code can be minted at runtime (got ${mintedAfterBoot.status})`)
  const mintedAfterBootBody = await mintedAfterBoot.json()
  const third = await fetch(`${base2}/mobile/pair`, {
    method: 'POST',
    body: JSON.stringify({ code: mintedAfterBootBody.code, device: 'Terzo telefono' }),
  })
  ok(third.status === 200, `the runtime-minted code pairs (got ${third.status})`)

  const unknownAfterClose = await fetch(`${base2}/mobile/pair`, {
    method: 'POST',
    body: JSON.stringify({ code: 'anything', device: 'Sconosciuto' }),
  })
  ok(unknownAfterClose.status === 403, `an unknown device cannot join a closed host (got ${unknownAfterClose.status})`)

  restarted.server.closeAllConnections()
  await new Promise((resolve) => restarted.server.close(resolve))
}

// ── a cleared device table mints a usable code on boot ───────────────────────
{
  // The state a user creates by emptying "devices" to start over.
  const fresh = new MobileSync(ctx, { host: '127.0.0.1', port: 0 })
  fresh.configPath = fileURLToPath(new URL('./_runtime-fresh/storages/mobile-sync.json', import.meta.url))
  await fresh[Symbol.for('cordis.init')]()
  const base3 = `http://127.0.0.1:${fresh.port}`

  ok(fresh.setupCode.length > 0, 'an empty table mints a usable pairing code on boot')
  const mintedCode = fresh.setupCode

  const status3 = await fetch(`${base3}/mobile/status`, { headers: { authorization: `Bearer ${token}` } })
  ok(status3.status === 401, 'a token from another host is not accepted')

  const pairing = await fetch(`${base3}/mobile/pair`, {
    method: 'POST',
    body: JSON.stringify({ code: mintedCode, device: 'Pixel 8' }),
  })
  ok(pairing.status === 200, `the minted code pairs (got ${pairing.status})`)
  const issued = await pairing.json()

  const admitted = await fetch(`${base3}/mobile/sessions`, { headers: { authorization: `Bearer ${issued.token}` } })
  ok(admitted.status === 200, 'the newly paired device can read sessions')

  fresh.server.closeAllConnections()
  await new Promise((resolve) => fresh.server.close(resolve))

  const reread = JSON.parse(await readFile(fresh.configPath, 'utf8'))
  ok(reread.setupCode === '', 'the code is retired on disk once it has been used')
  ok(reread.devices.length === 1, 'exactly the new device is on disk')
  ok(reread.setupCodeExpiresAt === undefined, 'no expiry field is persisted any more')
}

// ── a byte-order mark must not wipe the device table ─────────────────────────
{
  // The failure this guards against is silent: the file looks right in any
  // editor, the boot log says nothing, and every paired phone is quietly
  // locked out. PowerShell's `Set-Content -Encoding UTF8` writes exactly this
  // file, so it is one hand-edit away at any time.
  const bomPath = fileURLToPath(new URL('./_runtime-bom/storages/mobile-sync.json', import.meta.url))
  const bomHome = fileURLToPath(new URL('./_runtime-bom/', import.meta.url))
  await rm(bomHome, { recursive: true, force: true })
  await mkdir(dirname(bomPath), { recursive: true })
  const payload = {
    version: 2,
    setupCode: '',
    devices: [{ name: 'Telefono con BOM', token: 'tok-bom-device', createdAt: 1, lastSeenAt: 2 }],
  }
  await writeFile(bomPath, `\uFEFF${JSON.stringify(payload, null, 2)}`, 'utf8')

  const bom = new MobileSync(ctx, { host: '127.0.0.1', port: 0 })
  bom.configPath = bomPath
  await bom[Symbol.for('cordis.init')]()
  const baseBom = `http://127.0.0.1:${bom.port}`

  ok(bom.devices.length === 1, 'a file with a byte-order mark still yields its devices')
  ok(typeof bom.devices[0].id === 'string' && bom.devices[0].id.length === 12, 'a table written before ids existed still gets one')
  ok(bom.setupCode.length > 0, 'a spent code in that file is still replaced on boot')
  const admitted = await fetch(`${baseBom}/mobile/status`, { headers: { authorization: 'Bearer tok-bom-device' } })
  ok(admitted.status === 200, `the device from that file can still authenticate (got ${admitted.status})`)

  bom.server.closeAllConnections()
  await new Promise((resolve) => bom.server.close(resolve))
  await rm(bomHome, { recursive: true, force: true })
}

console.log(`\nmobile-sync offline checks passed: ${checks}`)
