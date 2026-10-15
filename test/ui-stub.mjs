/**
 * A stand-in harness for exercising the Android app's rendering.
 *
 * The plugin's own suite (`plugin-check.mjs`) proves the wire contract, and the
 * e2e scripts prove it against a real harness — but both need a session that
 * happens to contain the interesting cases: a diff-producing edit, a fenced
 * code block, a photo, a tool that produced an image, and an approval that is
 * still waiting. This serves exactly those, deterministically, on a loopback
 * port, so a UI change can be looked at without arranging a real conversation.
 *
 * It implements only the routes the app calls, with fixed data. It is not a
 * harness: nothing here talks to a model.
 *
 * Usage:
 *   node test/ui-stub.mjs [port]        # default 4399
 *
 * Then pair the emulator with `10.0.2.2:<port>` and any pairing code.
 */

import { createServer } from 'node:http'
import { readFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'

const PORT = Number(process.argv[2] ?? 4399)
const TOKEN = 'ui-stub-token'
const SESSION = 'session-ui-stub'
const LINK_SESSION = 'session-ui-link'
const IMAGE_SHA = 'sha256:ui-stub-image'

/** The photo the transcript references, served from the test fixtures. */
const photoBytes = await readFile(fileURLToPath(new URL('./fixtures/cerchio-giallo.jpg', import.meta.url)))

/** A PNG the "screenshot" tool produced, so a tool card can show an image. */
const toolImagePng = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAEAAAABACAYAAACqaXHeAAAAWklEQVR42u3QMQEAAAgDoC251a3g' +
    'L4qgOXFRAgQIECBAgAABAgQIECBAgAABAgQIECBAgAABAgQIECBAgAABAgQIECBAgAABAgQI' +
    'ECBAgAABAgQIECBAgAABAgQIEHAeWLwAAX0tGdMAAAAASUVORK5CYII=',
  'base64',
)

/**
 * One durable session event, in the shape the plugin's `wireEvent` produces.
 * @param seq - log position.
 * @param extra - the fields of the entry.
 * @returns the event.
 */
const entry = (seq, extra) => ({ seq, time: 1_760_000_000_000 + seq * 1_000, ...extra })

const messages = [
  entry(0, { kind: 'turn-start', type: 'turn/start', turn: 1 }),
  entry(1, {
    kind: 'message',
    type: 'user/message',
    role: 'user',
    content: [
      { type: 'text', text: 'Guarda questa foto e sistemami il README, poi lancia i test.' },
      { type: 'image', attachmentId: IMAGE_SHA, mediaType: 'image/jpeg', width: 512, height: 512, name: 'cerchio-giallo.jpg' },
    ],
  }),
  entry(2, { kind: 'step-start', type: 'step/start', turn: 1, step: 1 }),
  entry(3, {
    kind: 'message',
    type: 'assistant/message',
    role: 'assistant',
    content: [
      {
        type: 'reasoning',
        text: 'Devo prima leggere il README per capire la struttura, poi correggere la sezione sulla compilazione.',
      },
      { type: 'text', text: 'Leggo il README e controllo la sezione di build.' },
    ],
  }),
  entry(4, {
    kind: 'tool-call',
    type: 'tool/call',
    turn: 1,
    step: 1,
    callId: 'call-read',
    name: 'read',
    arguments: '{"file_path":"K:\\\\Repository\\\\dsh-mobile-connector\\\\README.md","offset":280,"limit":40}',
  }),
  entry(5, {
    kind: 'tool-result',
    type: 'tool/result',
    turn: 1,
    step: 1,
    callId: 'call-read',
    name: 'read',
    content: [{ type: 'text', text: '280: ```powershell\n281: cd K:\\Repository\\dsh-mobile-connector\\android\n282: .\\gradlew.bat assembleDebug\n283: ```' }],
  }),
  entry(6, {
    kind: 'tool-call',
    type: 'tool/call',
    turn: 1,
    step: 2,
    callId: 'call-edit',
    name: 'edit',
    arguments: JSON.stringify({
      file_path: 'K:\\Repository\\dsh-mobile-connector\\README.md',
      old_string: 'cd K:\\Repository\\dsh-mobile-connector\\android\n.\\gradlew.bat assembleDebug',
      new_string: 'cd K:\\Repository\\dsh-mobile-connector\\android\n.\\gradlew.bat assembleDebug\n# richiede il wrapper Gradle nella cartella android\\',
    }),
  }),
  entry(7, { kind: 'tool-result', type: 'tool/result', turn: 1, step: 2, callId: 'call-edit', name: 'edit', content: [{ type: 'text', text: 'Modifica applicata.' }] }),
  entry(8, {
    kind: 'message',
    type: 'assistant/message',
    role: 'assistant',
    content: [
      {
        type: 'text',
        text: 'Fatto. La sezione ora dice:\n\n```powershell\ncd K:\\Repository\\dsh-mobile-connector\\android\n.\\gradlew.bat assembleDebug\n```\n\nUsa `JAVA_HOME` per puntare al JBR di Android Studio.\n\nLa guida sta su https://example.com/docs/guide e qui sotto c\'è l\'indirizzo nudo.\n\nhttps://example.com/docs/guide\n\n[Il changelog](https://example.com/changelog)\n\nhttps://example.com/binary.png\n\nLa bozza del racconto sta in [LORE-bozza.md](LORE-bozza.md), e il file grosso in [lungo.md](lungo.md).',
      },
    ],
  }),
  entry(9, {
    kind: 'tool-call',
    type: 'tool/call',
    turn: 1,
    step: 3,
    callId: 'call-pwsh',
    name: 'pwsh',
    arguments: JSON.stringify({ command: '.\\gradlew.bat --no-daemon testDebugUnitTest', description: 'Esegue i test unitari' }),
  }),
  entry(10, {
    kind: 'tool-result',
    type: 'tool/result',
    turn: 1,
    step: 3,
    callId: 'call-pwsh',
    name: 'pwsh',
    content: [
      {
        type: 'text',
        text: '> Task :app:testDebugUnitTest\nBUILD SUCCESSFUL in 23s\n95 tests completed',
      },
    ],
  }),
  entry(11, {
    kind: 'tool-call',
    type: 'tool/call',
    turn: 1,
    step: 4,
    callId: 'call-shot',
    name: 'screenshot',
    arguments: '{"target":"emulator"}',
  }),
  entry(12, {
    kind: 'tool-result',
    type: 'tool/result',
    turn: 1,
    step: 4,
    callId: 'call-shot',
    name: 'screenshot',
    content: [
      { type: 'text', text: 'Cattura dello schermo:' },
      { type: 'image', attachmentId: 'sha256:ui-stub-shot', mediaType: 'image/png', width: 64, height: 64, name: 'schermo.png' },
    ],
  }),
  entry(13, {
    kind: 'tool-call',
    type: 'tool/call',
    turn: 1,
    step: 5,
    callId: 'call-deploy',
    name: 'pwsh',
    arguments: JSON.stringify({ command: 'adb install -r app-debug.apk', description: 'Installa sul telefono' }),
  }),
  entry(14, {
    kind: 'tool-result',
    type: 'tool/result',
    turn: 1,
    step: 5,
    callId: 'call-deploy',
    name: 'pwsh',
    isError: true,
    content: [{ type: 'text', text: 'adb: device offline\nerror: no devices/emulators found' }],
  }),
  // The handover: the call, its result, and the durable delivery the plugin
  // projects from it. Two files, one this app can read and one it cannot, so the
  // card and the sheet show both of their faces in one screenshot.
  entry(15, {
    kind: 'tool-call',
    type: 'tool/call',
    turn: 1,
    step: 6,
    callId: 'call-present',
    name: 'present',
    arguments: JSON.stringify({
      files: [
        { path: 'docs\\Report-artefatti.docx', description: 'Il report impaginato' },
        { path: 'docs\\nota-consegna.md', description: 'La nota di sintesi' },
      ],
    }),
  }),
  entry(16, {
    kind: 'tool-result',
    type: 'tool/result',
    turn: 1,
    step: 6,
    callId: 'call-present',
    name: 'present',
    content: [{ type: 'text', text: 'Presented docs\\Report-artefatti.docx\nPresented docs\\nota-consegna.md' }],
  }),
  entry(17, {
    kind: 'deliverable',
    type: 'deliverables/presented',
    turn: 1,
    callId: 'call-present',
    files: [
      { index: 0, path: 'docs\\Report-artefatti.docx', description: 'Il report impaginato' },
      { index: 1, path: 'docs\\nota-consegna.md', description: 'La nota di sintesi' },
    ],
  }),
  // An approval that the plugin is still holding, matched by callId: this is
  // the live one, so the card has working buttons.
  entry(18, { kind: 'approval-asked', type: 'approval/asked', approvalId: 'appr-live', name: 'pwsh', callId: 'call-live', reason: 'run a shell command' }),
  // And one that was already decided, so the settled rendering is visible too.
  entry(19, { kind: 'approval-asked', type: 'approval/asked', approvalId: 'appr-done', name: 'write', callId: 'call-write', reason: 'write a file' }),
  entry(20, { kind: 'approval-decided', type: 'approval/decided', approvalId: 'appr-done', outcome: 'allowed-once' }),
  entry(21, {
    kind: 'message',
    type: 'assistant/message',
    role: 'assistant',
    content: [{ type: 'text', text: 'Aspetto il permesso per lanciare l\'installazione.' }],
  }),
  entry(22, { kind: 'turn-end', type: 'turn/end', turn: 1, reason: 'completed' }),
]

/**
 * The transcript of the link chat: two entries, and the links are in the last
 * one — a file the chat's own folder holds, a page, a name that is not there,
 * and one that is not text.
 */
const linkMessages = [
  entry(0, {
    kind: 'message',
    type: 'user/message',
    role: 'user',
    content: [{ type: 'text', text: 'Apri la bozza che hai scritto.' }],
  }),
  entry(1, {
    kind: 'message',
    type: 'assistant/message',
    role: 'assistant',
    content: [
      {
        type: 'text',
        text: 'La bozza sta in [LORE-bozza.md](LORE-bozza.md).\n\nIl resto è su https://example.com/changelog.\n\nManca invece [questo](non-esiste.md), e [questa](binario.png) non è testo.',
      },
    ],
  }),
]

/**
 * A project with more chats than its card shows at once.
 *
 * The home list keeps the newest five chats of a project and offers the rest, and
 * that rule cannot be looked at on a fixture of three: these eight exist so the
 * cut, the count in the row, and the way back are things a person *sees* rather
 * than imagines. They are deliberately the oldest chats in the stub, so the
 * projects the other checks name keep the order they have on screen.
 */
const TAIL_WORKSPACE = 'ws-tail'
const tailSessions = Array.from({ length: 8 }, (_, index) => {
  const at = Date.now() - 86_400_000 - index * 60_000
  return {
    id: `session-tail-${index + 1}`,
    title: `Chat d'archivio ${index + 1}`,
    cwd: 'K:\\Repository\\Archivio',
    workspaceId: TAIL_WORKSPACE,
    workspaceTitle: 'Archivio',
    running: false,
    agentAvailable: true,
    blank: false,
    status: 'completed',
    updatedAt: at,
    lastPromptAt: at,
    parentSessionId: null,
    origin: null,
  }
})

const sessions = [
  {
    id: SESSION,
    title: 'Sistema il README e lancia i test',
    cwd: 'K:\\Repository\\dsh-mobile-connector',
    workspaceId: 'ws-dsh',
    workspaceTitle: 'dsh-mobile-connector',
    running: true,
    agentAvailable: true,
    blank: false,
    // One chat per state the dot has to draw, so a rendering change can be looked
    // at instead of imagined: working, finished, failed, interrupted, never run.
    status: 'running',
    updatedAt: Date.now(),
    lastPromptAt: Date.now(),
    parentSessionId: null,
    origin: null,
  },
  {
    // A short chat whose *last* message is the one with the links, so a tapped
    // link can be exercised without scrolling a long transcript first.
    id: LINK_SESSION,
    title: 'Un link a un file',
    cwd: 'K:\\Repository\\dsh-mobile-connector',
    workspaceId: 'ws-dsh',
    workspaceTitle: 'dsh-mobile-connector',
    running: false,
    agentAvailable: true,
    blank: false,
    status: 'completed',
    updatedAt: Date.now() - 60_000,
    lastPromptAt: Date.now() - 60_000,
    parentSessionId: null,
    origin: null,
  },
  {
    id: 'session-broken',
    title: 'La build che non passa',
    cwd: 'K:\\Repository\\dsh-mobile-connector',
    workspaceId: 'ws-dsh',
    workspaceTitle: 'dsh-mobile-connector',
    running: false,
    agentAvailable: true,
    blank: false,
    status: 'failed',
    updatedAt: Date.now() - 120_000,
    lastPromptAt: Date.now() - 120_000,
    parentSessionId: null,
    origin: null,
  },
  {
    id: 'session-halted',
    title: 'Interrotta a metà',
    cwd: 'K:\\Repository\\Acme',
    workspaceId: 'ws-ash',
    workspaceTitle: 'Acme',
    running: false,
    agentAvailable: true,
    blank: false,
    status: 'stopped',
    updatedAt: Date.now() - 900_000,
    lastPromptAt: Date.now() - 900_000,
    parentSessionId: null,
    origin: null,
  },
  {
    id: 'session-fresh',
    title: 'Mai avviata',
    cwd: 'K:\\Repository\\Acme',
    workspaceId: 'ws-ash',
    workspaceTitle: 'Acme',
    running: false,
    agentAvailable: true,
    blank: true,
    status: 'idle',
    updatedAt: Date.now() - 1_800_000,
    lastPromptAt: null,
    parentSessionId: null,
    origin: null,
  },
  {
    id: 'session-loose',
    title: 'Fuori da ogni progetto',
    cwd: 'K:\\Repository\\demo-app',
    workspaceId: null,
    workspaceTitle: null,
    running: false,
    agentAvailable: true,
    blank: false,
    status: 'completed',
    updatedAt: Date.now() - 7_200_000,
    lastPromptAt: Date.now() - 7_200_000,
    parentSessionId: null,
    origin: null,
  },
  {
    id: 'session-other',
    title: 'Un\'altra chat dal PC',
    cwd: 'K:\\Repository\\Acme',
    workspaceId: 'ws-ash',
    workspaceTitle: 'Acme',
    running: false,
    agentAvailable: false,
    blank: false,
    // No status at all: a harness older than the dot, which must draw nothing
    // rather than invent a verdict.
    updatedAt: Date.now() - 3_600_000,
    lastPromptAt: null,
    parentSessionId: null,
    origin: null,
  },
  ...tailSessions,
]

/**
 * The projects the PC groups chats into.
 *
 * Mutable, because removing one is a route the app calls and the list has to look
 * different afterwards — a stub that kept answering the same projects would make a
 * working removal look broken.
 */
const workspaces = [
  { workspaceId: 'ws-dsh', path: 'K:\\Repository\\dsh-mobile-connector', title: 'dsh-mobile-connector', sessionIds: [SESSION, LINK_SESSION], createdAt: null, updatedAt: null },
  { workspaceId: 'ws-ash', path: 'K:\\Repository\\Acme', title: 'Acme', sessionIds: ['session-other'], createdAt: null, updatedAt: null },
  { workspaceId: TAIL_WORKSPACE, path: 'K:\\Repository\\Archivio', title: 'Archivio', sessionIds: tailSessions.map((session) => session.id), createdAt: null, updatedAt: null },
]

/** The pending approval the plugin would be holding right now. */
const pendingApprovals = [
  {
    id: 'approval-ui-1',
    sessionId: SESSION,
    toolName: 'pwsh',
    callId: 'call-live',
    reason: 'run a shell command',
    displayReason: 'Lo strumento vuole eseguire comandi nella cartella del progetto',
    createdAt: Date.now(),
    outcomes: ['allowed-once', 'rejected'],
  },
]

/** Verdicts this stub has been told, so the list can drain. */
const settled = new Map()

/**
 * The PC's folder tree, small enough to read and real enough to walk.
 *
 * A browse level is generated from the requested path instead of being one fixed
 * answer, because the *shape* of the answer is what the pane has to get right:
 * the volume roots lead every level as shortcuts, the ancestry follows root
 * first, and a level with no parent above it is the case the "up" arrow has to
 * report honestly. A single canned level cannot show any of that.
 */
const HOME = 'C:\\Users\\stub'
const DRIVES = ['C:\\', 'K:\\']
const TREE = {
  'C:\\': ['Users'],
  'C:\\Users': ['stub'],
  'C:\\Users\\stub': [],
  'K:\\': ['Repository'],
  'K:\\Repository': ['dsh-mobile-connector'],
  'K:\\Repository\\dsh-mobile-connector': ['android', 'test'],
  'K:\\Repository\\dsh-mobile-connector\\android': [],
  'K:\\Repository\\dsh-mobile-connector\\test': [],
}

/** The ancestry of one level, root first, as the host reports it. */
function crumbsOf(path) {
  const root = `${path.slice(0, 2)}\\`
  const parts = path.slice(root.length).split('\\').filter(Boolean)
  const crumbs = [{ name: root, path: root, hidden: false }]
  let current = root
  for (const part of parts) {
    current = current.endsWith('\\') ? `${current}${part}` : `${current}\\${part}`
    crumbs.push({ name: part, path: current, hidden: false })
  }
  return crumbs
}

/** One browse level, with the other volume's shortcut in front of its ancestry. */
function levelOf(path) {
  const root = `${path.slice(0, 2)}\\`
  const shortcuts = DRIVES.filter((drive) => drive !== root).map((drive) => ({
    name: drive.slice(0, 2),
    path: drive,
    hidden: false,
    drive: true,
  }))
  return {
    path,
    home: HOME,
    crumbs: shortcuts.concat(crumbsOf(path)),
    entries: (TREE[path] ?? []).map((name) => ({ name, path: path.endsWith('\\') ? `${path}${name}` : `${path}\\${name}`, hidden: false })),
    truncated: false,
  }
}

/** The files a linked reference can name, keyed by the name a chat would print. */
const FILES = {
  'LORE-bozza.md': {
    path: 'K:\\Repository\\dsh-mobile-connector\\LORE-bozza.md',
    name: 'LORE-bozza.md',
    text: '# LORE\n\nLa bozza del racconto.\n\n## Capitoli\n\n1. La partenza\n2. Il ritorno\n\n> Nota: **non** pubblicare questa versione.\n',
    truncated: false,
  },
  'lungo.md': {
    path: 'K:\\Repository\\dsh-mobile-connector\\lungo.md',
    name: 'lungo.md',
    text: `${'# Un documento lungo\n\n'.repeat(1)}${'Riga di testo che continua. '.repeat(20)}\n`,
    truncated: true,
  },
}

/**
 * The files the stub says an agent delivered, keyed by the index the declaration
 * carries.
 *
 * Index 0 is a document this app cannot read and index 1 is one it can, because
 * the sheet draws exactly those two cases differently: the text is shown in
 * place, and the bytes get *Apri con un'app*, *Scarica* and *Condividi*. The
 * docx bytes are a real ZIP header so a download produces a file an emulator's
 * file manager recognises rather than an empty one.
 */
const DELIVERIES = {
  0: {
    path: 'docs\\Report-artefatti.docx',
    name: 'Report-artefatti.docx',
    description: 'Il report impaginato',
    bytes: Buffer.concat([
      Buffer.from([0x50, 0x4b, 0x03, 0x04, 0x14, 0x00, 0x06, 0x00]),
      Buffer.from('PK\u0003\u0004 finto docx per la verifica sul telefono', 'utf8'),
    ]),
  },
  1: {
    path: 'docs\\nota-consegna.md',
    name: 'nota-consegna.md',
    description: 'La nota di sintesi',
    text: '# Nota di consegna\n\nIl report è in `docs/Report-artefatti.docx`.\n\n- tre sezioni\n- una tabella\n\n| Voce | Valore |\n|---|---|\n| Righe | 128 |\n| Allegati | 2 |\n',
    bytes: Buffer.from('# Nota di consegna\n\nIl report è in `docs/Report-artefatti.docx`.\n', 'utf8'),
  },
}

const json = (res, status, payload) => {
  const body = Buffer.from(JSON.stringify(payload), 'utf8')
  res.writeHead(status, { 'content-type': 'application/json; charset=utf-8', 'content-length': String(body.length) })
  res.end(body)
}

const readBody = async (req) => {
  const chunks = []
  for await (const chunk of req) chunks.push(chunk)
  if (chunks.length === 0) return {}
  try {
    return JSON.parse(Buffer.concat(chunks).toString('utf8'))
  } catch {
    return {}
  }
}

/** The preset this stub's session is running under; the routes move it. */
let chosenPreset = 'workspace-write'

const server = createServer(async (req, res) => {
  const url = new URL(req.url ?? '/', 'http://stub.local')
  const parts = url.pathname.split('/').filter(Boolean)

  if (url.pathname === '/mobile/pair' && req.method === 'POST') {
    await readBody(req)
    // Any code pairs: this stub exists to exercise the app, not the handshake.
    json(res, 200, { token: TOKEN, device: 'Emulatore', reused: false })
    return
  }
  if (req.headers.authorization !== `Bearer ${TOKEN}`) {
    // A refusal is worth a line too: the header the app actually sent is the one
    // fact that says whether the problem is its token or this stub's expectation.
    console.log(`[ui-stub] REFUSED ${req.method} ${url.pathname} — authorization: ${JSON.stringify(req.headers.authorization ?? null)}`)
    json(res, 401, { error: { code: 'unauthorized', message: 'pair first' } })
    return
  }
  // Log every request the app makes — including the ones the polling issues —
  // because when the app and the stub disagree about what happened, the request
  // line is the one piece of evidence neither of them can reinterpret.
  console.log(`[ui-stub] ${req.method} ${url.pathname}${url.search}`)

  if (parts[1] === 'status') {
    json(res, 200, { ok: true, host: '127.0.0.1', port: PORT, urls: [], devices: [{ name: 'Emulatore', createdAt: 0, lastSeenAt: 0 }], pairingOpen: false, sessionCount: sessions.length, liveCount: 1, version: 1 })
    return
  }
  if (parts[1] === 'sessions' && parts.length === 2 && req.method === 'GET') {
    json(res, 200, { items: sessions })
    return
  }
  if (parts[1] === 'sessions' && parts.length === 2 && req.method === 'POST') {
    await readBody(req)
    json(res, 201, { sessionId: SESSION, agentPreset: null })
    return
  }
  if (parts[1] === 'workspaces' && req.method === 'GET') {
    json(res, 200, { items: workspaces })
    return
  }
  // Removing a project: the receipt the host sends, and the two consequences the
  // app draws — the project leaves the list, and its chats come back ungrouped.
  // The host computes a chat's group from the registry on every list read, so a
  // chat cannot keep pointing at a project that no longer exists.
  if (parts[1] === 'workspaces' && parts.length === 3 && req.method === 'DELETE') {
    const workspaceId = decodeURIComponent(parts[2])
    const at = workspaces.findIndex((workspace) => workspace.workspaceId === workspaceId)
    if (at < 0) {
      json(res, 404, { error: { code: 'workspace/not-found', message: `workspace "${workspaceId}" is not registered` } })
      return
    }
    workspaces.splice(at, 1)
    for (const session of sessions) {
      if (session.workspaceId !== workspaceId) continue
      session.workspaceId = null
      session.workspaceTitle = null
    }
    console.log(`[ui-stub] project ${workspaceId} removed`)
    json(res, 200, { ok: true, deleted: true, workspaceId })
    return
  }
  if (parts[1] === 'dirs' && req.method === 'GET') {
    const requested = url.searchParams.get('path')
    const target = requested === null || requested.trim() === '' ? HOME : requested
    if (!(target in TREE)) {
      json(res, 404, { error: { code: 'directory-unreadable', message: `cannot list ${target}` } })
      return
    }
    json(res, 200, levelOf(target))
    return
  }
  // The file half of a tapped link: a name relative to the chat that printed it,
  // an absolute path, or a `file:` URL. The stub resolves them all against its
  // own tree so the app's classification and sheet are what is under test.
  if (parts[1] === 'file' && req.method === 'GET') {
    const reference = (url.searchParams.get('ref') ?? '').trim()
    const name = reference.replace(/^file:\/\/\//i, '').split(/[\\/]/).filter(Boolean).at(-1) ?? ''
    if (reference === '') {
      json(res, 400, { error: { code: 'file/invalid', message: 'the ref parameter is required' } })
      return
    }
    if (reference === 'binario.png') {
      json(res, 415, { error: { code: 'file/binary', message: 'binario.png is not text (4096 bytes)' } })
      return
    }
    if (reference === 'senza-chat.md') {
      json(res, 409, { error: { code: 'file/no-session', message: '"senza-chat.md" is relative and this harness holds no folder for the chat that printed it' } })
      return
    }
    const found = FILES[name]
    if (found === undefined) {
      json(res, 404, { error: { code: 'file/not-found', message: `cannot read ${reference}: ENOENT` } })
      return
    }
    json(res, 200, { ...found, bytes: Buffer.byteLength(found.text, 'utf8') })
    return
  }
  // The files an agent delivered. The app asks by the declaration's coordinates
  // — chat, event, index — and never by a path, so this answers the way the host
  // does: the text for a text file, the fact that it is not for anything else,
  // and the bytes themselves on `raw=1`.
  if (parts[1] === 'delivery' && req.method === 'GET') {
    const index = Number(url.searchParams.get('index'))
    const declared = DELIVERIES[index]
    if (declared === undefined) {
      json(res, 404, { error: { code: 'delivery/not-found', message: `no delivered file at index ${index}` } })
      return
    }
    if (url.searchParams.get('raw') === '1') {
      res.writeHead(200, {
        'content-type': 'application/octet-stream',
        'content-length': String(declared.bytes.length),
        'content-disposition': `attachment; filename="${declared.name}"`,
      })
      res.end(declared.bytes)
      return
    }
    json(res, 200, {
      path: `K:\\Repository\\dsh-mobile-connector\\${declared.path}`,
      name: declared.name,
      bytes: declared.bytes.length,
      text: declared.text ?? '',
      truncated: false,
      binary: declared.text === undefined,
      description: declared.description,
    })
    return
  }
  // The permission presets, as the harness ships them: the two knobs bundled
  // under one name, including the one that stops the agent asking.
  if (parts[1] === 'permissions' && req.method === 'GET') {
    const sessionId = url.searchParams.get('sessionId')
    json(res, 200, {
      sessionId,
      current: sessionId === null ? null : chosenPreset,
      defaultPreset: 'workspace-write',
      options: [
        { value: 'read-only', name: 'Read Only', description: 'Read files and run read-only commands.', sandbox: 'read-only', approval: 'ask' },
        { value: 'workspace-write', name: 'Workspace Write', description: 'Write inside the workspace.', sandbox: 'workspace-write', approval: 'ask' },
        { value: 'danger-full-access', name: 'Full access', description: 'Full file access without approval prompts.', sandbox: 'danger-full-access', approval: 'never' },
      ],
    })
    return
  }
  if (parts[1] === 'permissions' && req.method === 'POST') {
    const body = await readBody(req)
    if (typeof body.preset === 'string') chosenPreset = body.preset
    json(res, 200, { sessionId: body.sessionId ?? null, current: chosenPreset })
    return
  }
  // The link preview, as the harness's own fetch would report it: the page's
  // title and meta description, and its readable text with the markup gone.
  if (parts[1] === 'preview' && req.method === 'GET') {
    const target = url.searchParams.get('url') ?? ''
    if (target.includes('changelog')) {
      json(res, 200, {
        url: target,
        kind: 'html',
        title: 'Changelog — DSH Mobile',
        description: 'Cosa è cambiato in ogni versione.',
        text: 'v1.4\n\n· I link si aprono in un\'anteprima letta dall\'harness.\n· I gruppi di comandi si piegano.\n\nv1.3\n\n· Il livello di accesso si cambia dal telefono.',
        truncated: false,
        statusCode: 200,
      })
      return
    }
    if (target.includes('binary')) {
      json(res, 415, { error: { code: 'preview/unsupported', message: 'unsupported content type "image/png"' } })
      return
    }
    if (target.includes('private')) {
      json(res, 403, { error: { code: 'preview/blocked', message: 'URL hostname resolves to a non-public IP address' } })
      return
    }
    json(res, 200, {
      url: target,
      kind: 'html',
      title: 'La guida',
      description: 'Come si usa il progetto, in pratica.',
      text: 'Introduzione\n\nQuesta guida spiega come si usa il progetto. Il primo passo è aprire la cartella del workspace e lanciare i test.\n\nInstallazione\n\n· Serve la JBR di Android Studio, perché il progetto compila con Java 17.\n· Il percorso si imposta con JAVA_HOME.\n\nLa pagina continua con altri dettagli che qui non servono.',
      truncated: true,
      statusCode: 200,
    })
    return
  }
  if (parts[1] === 'models' && req.method === 'GET') {
    json(res, 200, {
      default: { provider: 'deepseek-official', model: 'deepseek-flash', reasoningEffort: 'high' },
      routableProviders: ['deepseek-official', 'mimo', 'glm'],
      groups: [
        { id: 'deepseek-official', name: 'DeepSeek', models: [{ provider: 'deepseek-official', id: 'deepseek-flash', name: 'deepseek-flash', description: null, reasoning: null, vision: false, contextWindow: 128_000, maxTokens: 8192 }] },
        {
          id: 'mimo',
          name: 'MiMo',
          models: [
            { provider: 'mimo', id: 'mimo-v2.6-flash', name: 'mimo-v2.6-flash', description: null, reasoning: { efforts: [{ id: 'low', name: 'Basso' }, { id: 'high', name: 'Alto' }], defaultEffort: 'low' }, vision: true, contextWindow: 200_000, maxTokens: 8192 },
            { provider: 'mimo', id: 'mimo-v2.6-pro', name: 'mimo-v2.6-pro', description: null, reasoning: null, vision: true, contextWindow: 200_000, maxTokens: 8192 },
          ],
        },
        { id: 'glm', name: 'GLM', models: [{ provider: 'glm', id: 'glm-5.3', name: 'glm-5.3', description: null, reasoning: null, vision: null, contextWindow: null, maxTokens: null }] },
      ],
      failures: [{ id: 'broken', name: 'Broken route', message: 'nessun adapter registrato' }],
    })
    return
  }
  if (parts[1] === 'models' && req.method === 'POST') {
    const body = await readBody(req)
    json(res, 200, { selected: { provider: body.provider, model: body.model, reasoningEffort: body.reasoningEffort ?? null } })
    return
  }
  if (parts[1] === 'approvals' && parts.length === 2 && req.method === 'GET') {
    json(res, 200, { items: pendingApprovals.filter((item) => !settled.has(item.id)) })
    return
  }
  if (parts[1] === 'approvals' && parts.length === 3 && req.method === 'POST') {
    const body = await readBody(req)
    const id = decodeURIComponent(parts[2])
    if (!['allowed-once', 'rejected'].includes(body.outcome)) {
      json(res, 400, { error: { code: 'invalid-request', message: 'outcome must be allowed-once or rejected' } })
      return
    }
    if (settled.has(id)) {
      json(res, 409, { error: { code: 'approval/settled', message: 'already settled' } })
      return
    }
    settled.set(id, body.outcome)
    console.log(`[ui-stub] approval ${id} answered: ${body.outcome}`)
    json(res, 200, { resolved: true, approvalId: id, outcome: body.outcome })
    return
  }
  if (parts[1] === 'sessions' && parts[3] === 'images') {
    const isPhoto = decodeURIComponent(parts[4]) === IMAGE_SHA
    const bytes = isPhoto ? photoBytes : toolImagePng
    res.writeHead(200, { 'content-type': isPhoto ? 'image/jpeg' : 'image/png', 'content-length': String(bytes.length) })
    res.end(bytes)
    return
  }
  if (parts[1] === 'sessions' && parts[3] === 'messages' && req.method === 'GET') {
    const transcript = decodeURIComponent(parts[2]) === LINK_SESSION ? linkMessages : messages
    json(res, 200, { sessionId: parts[2], cursor: transcript.length - 1, hasMore: false, entries: transcript })
    return
  }
  if (parts[1] === 'sessions' && parts[3] === 'messages' && req.method === 'POST') {
    const body = await readBody(req)
    console.log(`[ui-stub] prompt: ${JSON.stringify(body).slice(0, 200)}`)
    json(res, 202, { accepted: true })
    return
  }

  json(res, 404, { error: { code: 'not-found', message: `no stub route for ${req.method} ${url.pathname}` } })
})

server.listen(PORT, '0.0.0.0', () => {
  console.log(`[ui-stub] listening on http://127.0.0.1:${PORT} (emulator: 10.0.2.2:${PORT})`)
  console.log('[ui-stub] pair with any code; the token is fixed')
})
