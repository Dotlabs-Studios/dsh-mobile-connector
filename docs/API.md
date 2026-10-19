## HTTP API reference

`dsh-plugin-mobile-sync` owns its own `node:http` listener, on the `host` and `port` from its plugin configuration and
nowhere near the Web GUI's loopback server. Both have safe defaults — `127.0.0.1:4380`, which admits no phone until
`host` is set — and a nonsensical `port` is refused at boot rather than bound (`lib/index.js:2269`, `2276`).
Everything it serves is under the base path `/mobile`, and a request whose first segment is not `mobile` is answered
`404 not-found` before authentication runs (`lib/index.js:2469`). Auth is a paired-device token — `Authorization:
Bearer <token>` — required on every route except `POST /mobile/pair` and `GET|POST /mobile/pairing-code` (the second
one answers loopback only), and compared in constant time (`lib/index.js:2425`). Bodies are JSON objects, read whole
and capped at 48 MiB — except `POST /mobile/files`, whose body *is* the file's bytes, and `POST /mobile/pair`, which
carries a code and a label and is capped at 4 KiB; answers are `application/json; charset=utf-8` with `cache-control:
no-store`, apart from the two byte streams noted below. Every failure, the router's own included, uses one envelope
(`lib/index.js:1650`):

```json
{ "error": { "code": "unauthorized", "message": "a paired device token is required" } }
```

Binding `127.0.0.1` is the default and is logged as a warning, because a phone then cannot reach the listener at all
(`lib/index.js:2326`).

### Routes

| Method | Path | Purpose |
| --- | --- | --- |
| `POST` | `/mobile/pair` | Claim the pairing code and mint a device token, or rotate the token the caller already holds. |
| `GET`/`POST` | `/mobile/pairing-code` | Mint a fresh pairing code. **Loopback only.** |
| `GET` | `/mobile/health` | Wire version, plugin version, and the plugin's own counters. |
| `GET` | `/mobile/devices` | The paired devices, with the stable id each is addressed by. |
| `DELETE` | `/mobile/devices/{id}` | Revoke one device, or the caller itself. |
| `GET` | `/mobile/status` | Listener, pairing and session counts for the settings screen. |
| `GET` | `/mobile/sessions` | List the host's chats, newest first, with a status dot. |
| `POST` | `/mobile/sessions` | Create one chat, optionally inside a project. |
| `GET` | `/mobile/sessions/{id}/messages` | Read one page of history. |
| `POST` | `/mobile/sessions/{id}/messages` | Append one prompt: text, photos, staged files. |
| `GET` | `/mobile/sessions/{id}/images/{attachmentId}/raw` | One stored image: bytes (`/raw`) or base64 JSON (`/data`). |
| `POST` | `/mobile/images` | Store one photo and return its durable reference. |
| `POST` | `/mobile/files` | Stage one file for a chat and return its receipt. |
| `GET` | `/mobile/dirs` | List one level of the PC's filesystem. |
| `POST` | `/mobile/dirs` | Create one directory and read its parent back. |
| `GET` | `/mobile/workspaces` | List the host's projects. |
| `POST` | `/mobile/workspaces` | Adopt one existing host directory as a project. |
| `DELETE` | `/mobile/workspaces/{workspaceId}` | Unregister one project, non-destructively. |
| `GET` | `/mobile/models` | List the models this host can answer with. |
| `POST` | `/mobile/models` | Point one chat at a model from its next request. |
| `GET` | `/mobile/approvals` | List approvals waiting for an answer. |
| `POST` | `/mobile/approvals/{id}` | Answer one approval. |
| `GET` | `/mobile/questions` | List questions waiting for an answer, live and late. |
| `POST` | `/mobile/questions/{id}` | Answer one question set. |
| `GET` | `/mobile/permissions` | Read the permission presets and one chat's current one. |
| `POST` | `/mobile/permissions` | Switch one chat's permission preset. |
| `GET` | `/mobile/file` | Read one linked file off the PC as text. |
| `GET` | `/mobile/delivery` | Describe or stream one file an agent delivered. |
| `GET` | `/mobile/preview` | Fetch and reduce one link, on the host. |
| `GET` | `/mobile/events` | Server-Sent Events: the live feed. |

Path parameters are percent-decoded after splitting, so an id containing `/` is sent as `%2F` and stays one segment.
An unrecognised path *under* `/mobile` still authenticates first, so a bad token answers `401`, not `404`
(`lib/index.js:2222`–`2343`).

### POST /mobile/pair

Body `{ code, device }`: `code` is the pairing code the PC printed at boot or minted on demand; `device` is a label,
trimmed, cut to 80 characters, defaulting to `Android device`. Answer `200 { token, device, id, reused }`, plus
`rotated: true` on a rotation (`lib/index.js:2623`).

Two ways in, and **a name is never one of them**:

- **A pairing code.** Single use: retired the instant a device joins, so nothing else can ride it. There is
  deliberately **no expiry** — a short window only punishes a slow restart, while single use is what closes the door
  (`lib/index.js:2233`, `2191`). A spent code is `403 pairing-closed` whatever the body says; a wrong code is `403
  pairing-refused`. Success mints a *new* token for a *new* device, so two phones with the same label are two rows
  with two tokens.
- **The token the caller already holds.** A request carrying a valid bearer token rotates that device's token and
  keeps its `id`, with no code involved: the token was the proof (`lib/index.js:2633`). This is the path back for a
  credential you think leaked, and it needs nothing from the PC.

A phone that has neither — a wiped app, a lost token — comes back through `GET /mobile/pairing-code` on the PC. That
route replaced the old "re-pair by name" shortcut, which handed out an existing device's token to anyone who guessed
the label; the name was doing the job of a password, and it is printed in `/mobile/status`.

- State lives in `$DSH_HOME/storages/mobile-sync.json`, mode `0600`, holding `{ version: 3, setupCode, devices: [{ id,
  name, token, createdAt, lastSeenAt }] }` (`lib/index.js:2243`). An empty `setupCode` means "spent" and is what the
  loader trusts; a byte-order mark is stripped first, because this is exactly the file a person edits by hand. A file
  that does not parse is moved aside as `mobile-sync.json.corrupt-<ts>` and replaced with a file that holds a freshly
  minted code, so log and disk agree (`lib/index.js:2158`).
- Writes are **serialised and atomic**: the table goes to a flushed sibling file and is renamed over the old one
  (`lib/index.js:2233`). A crash mid-write leaves the previous table intact, and two pair requests arriving together
  cannot drop each other's device.
- `id` is 12 hex characters derived from the token at creation and kept across rotations; it is what `/mobile/devices`
  shows and what a revoke names. `lastSeenAt` is updated in memory by every authenticated request and travels to disk
  with the next save, so it means last activity rather than last pairing (`lib/index.js:2437`).

### GET|POST /mobile/pairing-code

Mints a new pairing code and returns `200 { code, pairingOpen }`, logging it as the boot path does
(`lib/index.js:2682`). Refuses anything that is not loopback with `403 loopback-only`: a code minted in reply to a
request from the network is a code the network has already seen. Open `http://127.0.0.1:4380/mobile/pairing-code` on
the PC — or `curl -X POST` it — when a reinstalled app needs to pair without restarting the harness.

### GET /mobile/health

`200 { ok, apiVersion, pluginVersion, appVersion, port, uptimeSeconds, devices, streams, pendingApprovals,
pendingQuestions }` (`lib/index.js:2789`). Nothing here touches the session controller, so it answers on a bad link
and is the cheap "is the PC there, and is it new enough" probe. `appVersion` echoes an `x-dsh-mobile-app` request
header when the client sends one.

### GET /mobile/devices

`200 { items: [{ id, name, createdAt, lastSeenAt, current }] }`, `current` marking the caller; tokens never appear
(`lib/index.js:2697`).

### DELETE /mobile/devices/{id}

`200 { revoked: true, id, self }`. A device may revoke *itself* — that is what unpairing from the phone means — and
`self` says so, because the caller's token stops working on the very next request. An unknown id is `404
device/not-found` (`lib/index.js:2721`).

### GET /mobile/status

No parameters. `200 { ok, apiVersion, pluginVersion, host, port, urls, devices, pairingOpen, sessionCount, liveCount,
requests, uptimeSeconds, version }` (`lib/index.js:2809`). `urls` is one `{ url, address, tailscale }` per reachable
non-loopback IPv4 address, with the Tailscale range (a CGNAT address in `100.64.0.0/10`) flagged because that is the
one that works off the home network (`lib/index.js:352`). `devices` is `{ id, name, createdAt, lastSeenAt, current }`
only — never tokens. `sessionCount` and `liveCount` are `0` when the host mounts no session controller, which is a
fact rather than a failure (`lib/index.js:2812`). `requests` is bounded: unknown paths fold into `/mobile/*` instead
of creating a key each (`lib/index.js:2760`).

### GET /mobile/sessions

`subagents=1` includes child sessions (only the exact string `1` counts); `q` is a case-insensitive substring over
title, cwd, project title and id; `workspaces=0` skips the project lookup and serves every row ungrouped. Answer `200
{ items: [...] }`, each row `id`, `title`, `cwd`, `workspaceId`, `workspaceTitle`, `running`, `agentAvailable`,
`blank`, `updatedAt`, `lastPromptAt`, `parentSessionId`, `origin`, and usually `status` (`lib/index.js:2453`).

`status` is one of `running`, `completed`, `failed`, `stopped`, `idle` — a word, not a colour, because the host says
what happened and the phone decides how to draw it (`lib/index.js:666`). It can be **absent**: a row whose history has
not been read yet arrives without a dot, since an absent dot claims nothing and the next poll fills it in
(`lib/index.js:2602`). A running chat is answered from the host's flag and a blank chat is `idle` with no log read;
every other row is read once per `updatedAt`, and at most 24 reads are started per request, so a cold list is never as
slow as its own worst case (`lib/index.js:2607`, `98`). There is no pagination: the whole list is filtered and sorted
by `updatedAt`, and the project lookup is one workspace read for the page (`lib/index.js:2462`).

### POST /mobile/sessions

Body `{ workspaceId?, cwd?, agentPreset? }`. `workspaceId` takes precedence over `cwd`, and that is the point rather
than a quirk: creating *by project* attaches the chat to the project's session account, which is what makes it appear
in the desktop sidebar immediately, while creating by directory alone leaves it invisible on the PC until the next
project mutation (`lib/index.js:2739`). Answer `201 { sessionId, agentPreset }`. The path shape is not validated here;
the controller's own rules apply, and its refusals travel back through the code classifier.

### GET /mobile/sessions/{id}/messages

`limit` (integer 1–400, default `60`) caps the entries; anything else is `400 invalid-request`. `beforeSeq` (integer ≥
0, absent by default) is the exclusive upper bound, absent meaning the newest page. Answer `200 { sessionId, cursor,
hasMore, entries }`. `cursor` is the log cursor, not the newest message sequence: it comes from the `follow` opening
snapshot precisely because the `page` call reports only the events inside its own window, and using that as
`throughSeq` truncates freshly appended turns (`lib/index.js:2772`).

`entries` are `wireEvent` objects, oldest first; events with nothing a chat client can show are dropped rather than
forwarded, so `entries` can be shorter than the log (`lib/index.js:1361`). Each entry carries `kind`, `seq`, `type`,
an optional `time`, and its kind's own fields: `message` (`role`, `content`, `interrupted`, `source`), `tool-call` /
`tool-result` (`turn`, `step`, `callId`, `name`, `arguments` / `isError`, `content`), `turn-start` / `turn-end`
(`turn`, `reason`), `step-start` / `step-end`, `deliverable` (`files: [{ index, path, description? }]`),
`approval-asked` (`approvalId`, `name`, `callId`, `reason`), `approval-decided` (`approvalId`, `outcome`). Content
blocks reduce to `text`, `reasoning`, `image`, `file`, `tool-call` (`lib/index.js:946`). A session with no readable
history answers `404 session/not-found` (`lib/index.js:2805`).

### POST /mobile/sessions/{id}/messages

| Field | Type | Default | Meaning |
| --- | --- | --- | --- |
| `text` | string | absent | Sent whenever it is not whitespace-only. |
| `mode` | `steer` \| anything | `queue` | Only the exact string `steer` steers. |
| `images` | array ≤ 8 | absent | Inline base64 photos: `mediaType` ∈ `image/png`, `image/jpeg`, `image/webp`, `image/gif`, each decoded ≤ 12 MiB, optional `name`. |
| `files` | array ≤ 8 | absent | `{ receiptId }` from `POST /mobile/files`; the bytes are already the host's. |

Answer `202 { accepted: true }`; a message needs text, at least one image, or at least one file, else `400
invalid-request`. Photos travel inline as base64 image parts on purpose: the harness admits them as image content, so
the model actually sees the picture, whereas a staged receipt would surface as an opaque `file` block — the wrong
modality for a photo. Files are the opposite case and are *named*, not carried, because the controller resolves the
receipt into a stored reference and hands the model one line naming a read-only path, so a twenty-page PDF costs a
line of context instead of twenty pages of it (`lib/index.js:2849`). `prompt` is called with the request's own abort
signal, which the RPC gateway normally injects (`lib/index.js:2894`).

### GET /mobile/sessions/{id}/images/{attachmentId}/raw

`200` with the image's recorded media type as `content-type` and `cache-control: private, max-age=86400, immutable`.
The sibling spelling `…/{attachmentId}/data` matches the same arm and answers `200 { attachmentId, mediaType, width,
height, data }` with base64 `data` instead of bytes (`lib/index.js:2337`, `3010`).

A stored reference cannot be rebuilt from the id alone — a read verifies the recorded bytes and geometry against it —
so the reference is *found* first: across the live agents, then inside the addressed session's durable log, which is
what makes history work for a chat whose agent is not loaded (`lib/index.js:3014`, `3084`). Two honest consequences:
the live-agent scan ignores the session in the path, so `{id}` scopes only the log fallback; and that fallback covers
the newest 400 messages, so an older image answers a truthful `404 not-found` rather than a wrong image
(`lib/index.js:3046`, `3076`).

### POST /mobile/images

Body `{ data, name? }`, `data` being base64 — padded, unpadded and URL-safe alphabets all accepted
(`lib/index.js:204`). Answer `201 { attachmentId, name, bytes, sha256 }`, the digest computed here over the decoded
bytes. Empty data is `400 invalid-request`, over 12 MiB is `413 image-too-large`, and a host with no attachment store
is `503 unavailable` (`lib/index.js:2918`).

### POST /mobile/files

Query parameters, not a JSON body: `sessionId` (required — a file is staged for one session) and `name` (optional).
The request body **is** the file's exact bytes (`application/octet-stream`); the answer is `201 { receiptId,
attachmentId, name, bytes }` (`lib/index.js:2963`).

The ceiling is 32 MiB. A client that declares its size gets the plain `413 file-too-large` before a byte is read; one
that streams without declaring is stopped mid-body, and that interruption is translated back into the same 413 rather
than surfacing as the store's generic "attachment could not be persisted" (`lib/index.js:231`, `2974`). Deliberately
no cancellation signal is handed to the store: the body *is* the upload, and Node emits the request's `close` as soon
as its last chunk has been read, so passing that signal would abort every upload that finished correctly
(`lib/index.js:2984`). The two-step shape is the harness's, not this plugin's invention — a `file` part carries a
receipt minted when the bytes were stored, keyed to the receiving agent, so a receipt cannot be spent on another chat
(`lib/index.js:2939`).

### GET /mobile/dirs

`path` (string, default the host account's home) is an absolute host directory the host itself reported. Answer `200 {
path, home, crumbs, entries, truncated }` (`lib/index.js:2721`). `entries` is `{ name, path, hidden }` and holds
**directories only** — files are filtered out, because the question here is "which folder do I run in", not "what is
in this one" (`lib/index.js:486`). `hidden` flags a dot-name without removing the row. At most 500 child rows are
materialized and `truncated` reports the cut rather than silently hiding rows (`lib/index.js:84`, `500`).

On Windows the level's leading crumbs are prefixed with the machine's other volume roots, each shaped `{ name: "C:",
path: "C:\\", hidden: false, drive: true }`, minus the one the level is already on: a volume root has no parent to
step up to, so without those shortcuts the top of the tree is a dead end (`lib/index.js:441`, `323`). A drive holding
no media, or one the account cannot read, is an empty level rather than an error (`lib/index.js:475`). A path that is
not fully qualified, or a Windows drive-relative spelling such as `C:progetti`, is `400 invalid-request` — a remote
browser must send a path it read from the host, never one that resolves against the host process's current drive
(`lib/index.js:409`). An unreadable target is `404 directory-unreadable`.

### POST /mobile/dirs

Body `{ parent, name }`: an existing absolute directory and a single segment. Answer `201 { created, …listing }`,
where `created` is the new absolute path and the rest is the **parent's** refreshed level, so the client never has to
re-ask what it just changed (`lib/index.js:2721`). A `name` that is empty, `.`, `..` or contains a separator is `400
invalid-request`; an existing child is `409 directory-exists`; any other failure is `409 directory-create-failed`
(`lib/index.js:518`).

### GET /mobile/workspaces

`200 { items: [{ workspaceId, path, title, sessionIds, createdAt, updatedAt }] }`, in the registry's own order — the
order the desktop sidebar shows. The read attaches to the controller's projection stream, takes its `baseline` frame
and detaches, which is what makes it complete with no pagination (`lib/index.js:2506`, `2553`). A host mounting no
workspace controller answers `503 unavailable` rather than an empty list.

### POST /mobile/workspaces

Body `{ path }`, an absolute host directory. Answer `201 { workspaceId, path, title, sessionIds, created: true }` for
a new project, and `200` with `created: false` when that directory already had one (`lib/index.js:2569`): the status
code is the idempotency signal, so a phone that lost track of the list converges instead of creating duplicates. A
relative path is `400 invalid-request`; the controller's own `workspace/invalid-path` and `workspace/name-conflict`
arrive as `409`.

### DELETE /mobile/workspaces/{workspaceId}

`200 { ok: true, deleted: true, workspaceId }` (`lib/index.js:2701`). Non-destructive by contract: the folder, its
files and the chat histories are never touched — the project leaves the list and its chats become ungrouped, which is
what makes it safe to expose to a client that cannot show a confirmation dialog about someone's filesystem.

### GET /mobile/models

`200 { default, routableProviders, groups, failures }`, each group `{ id, name, models }` and each model `{ provider,
id, name, description, reasoning, vision, contextWindow, maxTokens }` (`lib/index.js:3134`). The catalogue is the
session controller's own — the very object the desktop picker renders — so the phone offers the same choices instead
of a second, drifting idea of what is configured. It carries no capability data, so models are enriched from the LLM
registry; that lookup is per-model and can be slow, hence two deliberate bounds: enrichment runs only when the
flattened catalogue is at most 60 models, and a model whose details cannot be resolved stays in the list with `vision:
null`, `contextWindow: null`, `maxTokens: null` rather than disappearing from the picker (`lib/index.js:3144`, `66`).
`reasoning` falls back to the catalogue's own value and must stay absent rather than empty, because the picker shows
an effort chooser only for a model that declares efforts.

### POST /mobile/models

Body `{ sessionId, provider, model, reasoningEffort? }`; the first three are required or the answer is `400
invalid-request`. Answer `200 { selected }` (`lib/index.js:3198`). This is the same call the desktop picker makes,
**including its side effect**: the harness records the choice as the deployment default as well as the session's own
selection, so switching a model from the phone also changes the PC's default, and the code says so rather than hiding
it (`lib/index.js:3184`). A switch does not disturb a step already running; the harness applies the new route to the
next request.

### GET /mobile/approvals

`200 { items: [...] }`, each item `{ id, sessionId, toolName, callId, reason, displayReason, createdAt, outcomes }`,
with `outcomes` always `["allowed-once", "rejected"]` (`lib/index.js:3226`, `70`). `displayReason` is the harness's
locale map reduced to one line, English first (`lib/index.js:1307`). A phone polls, so it cannot rely on catching the
stream frame that announced a request: this read is what makes a card survive a reconnect, a backgrounded app, or a
frame that arrived while nobody was looking (`lib/index.js:3220`). Pending approvals live in memory and are never
persisted, because an approval belongs to one open turn and a harness restart cancels it — a card on disk could be
answered after the request it belonged to is gone (`lib/index.js:1574`).

### POST /mobile/approvals/{id}

Body `{ outcome }`, one of `allowed-once` or `rejected`. Answer `200 { resolved: true, approvalId, outcome }`
(`lib/index.js:3244`). Anything else is `400 invalid-request`: the vocabulary is fail-closed, only `allowed-once`
grants anything, and a typo must never be read as consent. An approval already settled — by this phone, another phone
or the desktop — answers `409 approval/settled` rather than 500, because **losing that race is normal**
(`lib/index.js:3237`). When the phone does not answer within the configured window (`approvalWaitMs`, default 120 s)
this plugin stops waiting and hands the decision back to the chain, which is the behaviour without the plugin and must
not turn into a silent deny (`lib/index.js:1929`, `68`).

### GET /mobile/questions

`200 { items: [...] }`, each item `{ id, sessionId, callId, createdAt, expired, questions }` (`lib/index.js:3268`);
each question is `{ id, header, question, detail, multiSelect, options: [{ label, description }] }`. Fields are copied
rather than forwarded wholesale, so a client never has to defend itself against whatever keys a model invented;
`multi_select` and `multiSelect` are both accepted because both spellings are in the wild, and a question with no id
gets the positional stand-in `q<n>` rather than being dropped (`lib/index.js:1011`).

Live questions come first, then late ones flagged `expired: true`. A question whose harness-side two-minute window has
closed is **not** closed here: the harness marks it `continued`, tells the model to carry on and still accepts an
answer, so the card stays and only the channel changes (`lib/index.js:1778`). At most 20 late questions are
remembered, each for at most 30 minutes. A question that never had a `callId` cannot be addressed by the harness's
RPC, so it is announced as settled with `by: "timeout"` instead of kept as a button that could only return a conflict
(`lib/index.js:1791`, `80`, `82`).

### POST /mobile/questions/{id}

Body `{ answers: [{ id, selected: [labels], custom? }] }`, a non-empty array; anything else is `400 invalid-request`
(`lib/index.js:1087`). `selected` holds labels, not positions, and each answer is keyed by its question's own id.
Answer `200 { resolved: true, questionId, answers, late }`, where `late: true` means it went through the harness's
late channel (`lib/index.js:3285`).

Two channels, in order: while the question is live, resolving the pending record settles the waterfall the tool is
waiting on; after the window has closed the only way in is the question service's own `answer`, which takes the live
Agent rather than the session id because it must reach into that agent's inbox. `409 question/settled` covers every
way of arriving too late — already settled, the asking session no longer live, a reply already queued
(`REPLY_QUEUED`), or the service returning `false` for a question it no longer holds. `503 unavailable` means the host
mounts no question service (`lib/index.js:3302`–`3327`).

### GET /mobile/permissions

`sessionId` (string, absent by default) asks for that chat's current preset; the catalog itself is process-wide either
way. Answer `200 { sessionId, current, defaultPreset, options }`, each option `{ value, name, description, sandbox,
approval }` (`lib/index.js:3360`). `options[].value` is the host's own identifier and the only thing that may be sent
back; the label is presentation and a deployment may word it freely. The sandbox and approval knobs are filled in from
`resolve`, because the catalog carries only the label — without them the phone could offer "Full access" without being
able to say what it does (`lib/index.js:3345`). `sessionId` and `current` are `null` when the client named no session
or the host does not hold that one, since an unknown current value is deliberately not the same as a preset named
"null" (`lib/index.js:3367`). A host with no preset table answers `503 permission/unavailable`.

### POST /mobile/permissions

Body `{ sessionId, preset }`, both required. Answer `200 { sessionId, current }` and broadcast a `permission-changed`
frame (`lib/index.js:3395`). This is the one route that changes what the agent is **allowed** to do rather than what
it is doing, and the two knobs it moves are the sandbox mode and the approval policy — so `full access` here means an
agent that no longer asks before changing files or running commands. Two consequences are deliberate: the preset is
validated against the host's own catalog rather than forwarded, because `set` hands out that ability and a typo must
not become a permission (`400 permission/unknown`); and the switch is recorded, since `set` appends a
`permission/preset` entry to the session log, making a change from a phone auditable on the PC (`lib/index.js:3376`).
`404 permission/no-session` means that chat is not loaded on this harness; `409 permission/refused` carries the host's
own words when its admission check refuses the switch.

### GET /mobile/file

`ref` (string, required) is an absolute path, a `file:` URL, or a path relative to the chat named by `session`
(string, optional, the chat on screen). Answer `200 { path, name, bytes, text, truncated, line }`
(`lib/index.js:3916`). `line` is the 1-based line a `#L35` fragment pointed at, or `null` when the link named none —
the reader still reads the whole file, but saying which line the link meant is the difference between honouring the
link and quietly dropping half of it (`lib/index.js:3513`).

The reference travels exactly as the agent wrote it, because a relative one means the file *next to the chat that
printed it*; the host process's own working directory is the one base that must never be used, since it is the
process's accident and would read a different file with the same name without saying so. A fragment is stripped before
resolution, since it names a place *inside* a file, and a `../` step out of the chat's folder is allowed rather than
fenced: the same device can already browse the whole disk through `/mobile/dirs` (`lib/index.js:733`).

The same freedom is why this route is fenced at all: a reference can name the harness's own installation, and two of
those files are worth more than the conversation. Anything inside `$DSH_HOME` — which is where the device table lives,
tokens included, next to the provider credentials — and any `.credentials*` file is refused with `403 file/forbidden`
before the file is opened, and `protectedPaths` in the plugin configuration adds more (`lib/index.js:3938`). The fence
is on *this* route only: `/mobile/delivery` addresses a declaration out of the harness's log, so its paths are the
agent's choice rather than the client's.

Bounds and refusals: at most 512 KiB is read and at most 120 000 characters are served, with `truncated` reporting
which cut applied; a byte of NUL anywhere in what was read is what makes a file binary here, answering `415
file/binary` (`lib/index.js:848`). `400 file/invalid` covers an empty, over-long or drive-relative reference; `409
file/no-session` a relative reference with no folder to resolve against; `404 file/not-found` and `400
file/not-a-file` the filesystem itself.

### GET /mobile/delivery

`session` (required) names the chat; `seq` (integer ≥ 0, required) is the sequence of the `deliverables/presented`
event; `index` (integer ≥ 0, required) is the file's position in that event's own array; `raw=1` streams the bytes
instead of describing them (`lib/index.js:3568`).

Without `raw`: `200 { path, name, bytes, text, truncated, binary, description? }`. With `raw=1`: the bytes,
`content-type: application/octet-stream`, and `content-disposition: attachment` carrying the name both ASCII-folded
and as an RFC 5987 `filename*`, because the two halves of the ecosystem disagree about which they read
(`lib/index.js:3614`). No media type is guessed: this plugin owns no media-type table, and the honest generic type
plus the file's own name lets the phone decide with the table it already has.

The address is a **coordinate, not a path**: the declaration is read out of the harness's own log, so a phone cannot
ask this route for a file no agent declared, and a coordinate read long ago still resolves to what the log says now. A
relative declaration is resolved against the chat's own folder, exactly as a file link is (`lib/index.js:3544`). A
binary file is an *answer* here (`binary: true`), not a failure — a delivered PDF or slide deck is a file the phone
downloads, and answering `file/binary` would put "cannot read this" on a card whose whole point is that the file is
there and is fine (`lib/index.js:783`). `404 delivery/not-found` means that chat declares no file at that coordinate,
including for a delivery older than the bounded scan; `413 file/too-large` means past the 64 MiB download bound, which
is finite on purpose — the route streams rather than buffers, but an unbounded route invites pulling a disk image over
a metered connection by tapping the wrong row (`lib/index.js:137`, `3667`).

### GET /mobile/preview

`url` (string ≤ 2048 chars, required) must be `http` or `https`. Answer `200 { url, kind, title?, description?, text,
truncated, statusCode? }` (`lib/index.js:3716`). `url` is where the fetch ended after redirects, `kind` is `html` when
the text was reduced from a document and `text` when the source was passed through verbatim, and `title` /
`description` appear only when the page declared them (`lib/index.js:1268`). Text is reduced to at most 30 000
characters, cut on a word boundary where possible, and `truncated` reports either the host's own cut or this
phone-sized one (`lib/index.js:1193`, `112`).

The fetch runs on the host, under the harness's own web policy — public destinations only, DNS pinned, byte and time
caps, no credentials — because that is the one the agent's own `web_fetch` uses, and the phone must not be a second,
softer entrance to the network this machine sits on (`lib/index.js:3479`, `3702`). Answers are cached for 5 minutes,
16 entries, keyed by the canonical URL; the web seam is consulted *before* the cache, so a deployment that has just
stopped offering previews answers `503` rather than serving a remembered page (`lib/index.js:3717`, `110`).

### GET /mobile/events

Server-Sent Events, authenticated with the same bearer header as every other route. The response is
`text/event-stream; charset=utf-8` with `cache-control: no-store, no-transform`, `connection: keep-alive` and
`x-accel-buffering: no`, which is what stops a reverse proxy from buffering frames the phone is waiting for
(`lib/index.js:3758`). Two frames are written immediately — `retry: 3000`, so a dropped client comes back in three
seconds, and `data: {"event":"ready","port":…}` — and every 15 seconds a bare `: keep-alive` comment line follows, a
comment rather than an event so no client has to know to ignore it (`lib/index.js:55`, `3765`).

Every frame is exactly `data: <json>\n\n`, and the JSON always carries an `event` discriminator (`lib/index.js:2164`):

| `event` | Payload fields | Emitted when |
| --- | --- | --- |
| `ready` | `port` | Once per connection, at the head of the stream. |
| `session-event` | `sessionId`, `entry` | Every durable session event with a phone-visible meaning; `entry` has the same shape as one element of `entries` on the history page. |
| `status` | `sessionId`, `running` | The host's own running flag for a chat changes. |
| `approval-request` | `approval` | A tool asks for permission; the same object as one item of `GET /mobile/approvals`. |
| `approval-settled` | `approvalId`, `sessionId`, `outcome`, `by` | An approval is decided. `by` is `phone` or `desktop`; `outcome` is `null` when the request simply went away. |
| `question-request` | `question` | An agent asks a question; the same object as one item of `GET /mobile/questions`. |
| `question-expired` | `questionId`, `sessionId` | The live window closed and the harness still accepts an answer. |
| `question-settled` | `questionId`, `sessionId`, `answer`, `by` | A question is answered or abandoned. `by` is `phone`, `desktop` or `timeout`. |
| `permission-changed` | `sessionId`, `preset` | `POST /mobile/permissions` succeeded. |

What is broadcast, precisely: the host's own `session/event` and `api-session/status` feeds, plus everything this
plugin settles — approvals opened and settled, questions opened, expired and settled, and a permission change
(`lib/index.js:1633`, `1641`, `1997`, `2015`, `1771`, `1807`, `1825`, `3419`). The REST calls do **not** each emit a
frame of their own: sending a prompt produces `session-event` frames only because the host feed produces them. There
is no `id:` line and no replay, so a frame emitted while the phone is disconnected is gone — which is why the approval
and question lists are pollable reads rather than stream-only (`lib/index.js:3220`, `3261`).

A reader that stops reading is dropped rather than buffered: once a connection's unflushed bytes pass 1 MiB the plugin
destroys it, and the client's own `retry: 3000` brings it back (`lib/index.js:2368`). A phone that suspends, loses a
tunnel or flies into a dead cell is one reconnection; without the cap it was one megabyte per stalled client in the
harness's heap.

### Error codes

Every failure uses the envelope above. Codes raised by this plugin:

| Code | Status | Meaning |
| --- | --- | --- |
| `not-found` | 404 | Unknown route, or an image no session log references. |
| `unauthorized` | 401 | No bearer token, an empty one, or a token that is not paired. |
| `pairing-refused` | 403 | The pairing code is not valid. |
| `pairing-closed` | 403 | The code was already spent. Mint a new one on the PC (`/mobile/pairing-code`) or rotate the token the phone still holds. |
| `loopback-only` | 403 | A pairing code was asked for from the network; the route answers this machine only. |
| `rate-limited` | 429 | More pairing attempts from one address than the window allows. |
| `device/not-found` | 404 | No paired device has that id. |
| `file/forbidden` | 403 | The path is the harness's own installation (`$DSH_HOME`, its credentials) and is not readable from a phone. |
| `invalid-request` | 400 | A required parameter or body field is missing or malformed. |
| `invalid-json` | 400 | The body is not valid JSON, or is not a JSON *object*. |
| `invalid-base64` | 400 | Image data is not valid base64. |
| `body-too-large` | 413 | The JSON body exceeds 48 MiB, or a pairing body exceeds 4 KiB. |
| `image-too-large` | 413 | One decoded photo exceeds 12 MiB. |
| `file-too-large` | 413 | An upload exceeds 32 MiB. |
| `file/too-large` | 413 | A delivered file exceeds the 64 MiB download bound. |
| `file/invalid` | 400 | Empty, over-long, drive-relative or unusable `file:` reference. |
| `file/no-session` | 409 | A relative reference with no chat folder to resolve it against. |
| `file/not-found` | 404 | Nothing readable at the resolved path. |
| `file/not-a-file` | 400 | The path is a directory or a device, not a regular file. |
| `file/binary` | 415 | A NUL byte in the file: not text. `GET /mobile/file` only — a delivery reports `binary: true` instead. |
| `directory-unreadable` | 404 | The browse target is not a readable directory. |
| `directory-exists` | 409 | The child directory is already there. |
| `directory-create-failed` | 409 | Creating the child failed for another reason. |
| `delivery/not-found` | 404 | That chat declares no delivered file at that coordinate, or the delivery is older than the bounded scan. |
| `preview/invalid` | 400 | Missing, over-long, malformed or non-`http(s)` URL. |
| `preview/blocked` | 403 | The harness's fetch policy refused the destination or a redirect. |
| `preview/timeout` | 504 | The fetch timed out. |
| `preview/unsupported` | 415 | The destination's content type cannot be reduced to text. |
| `preview/too-large` | 413 | The fetched body exceeded the harness's own cap. |
| `preview/unavailable` | 503 | This harness mounts no web fetch provider. |
| `preview/failed` | 502 | The fetch failed for any other reason. |
| `approval/settled` | 409 | The approval was already settled — a race, not a bug. |
| `question/settled` | 409 | Already settled, the asking session is gone, or a reply is already queued. |
| `permission/unknown` | 400 | No preset by that name in the host's own catalog. |
| `permission/no-session` | 404 | That chat is not loaded on this harness. |
| `permission/refused` | 409 | The host's admission check refused the switch; its own message is forwarded. |
| `permission/unavailable` | 503 | This harness mounts no permission presets. |
| `unavailable` | 503 | A required host service is missing: session controller, workspace controller, attachment store, file uploads or question service. |

Codes the host itself throws are classified rather than replaced, so the harness's own vocabulary survives the hop
(`lib/index.js:907`):

| Code | Status | Meaning |
| --- | --- | --- |
| `session/not-found`, `subagent/not-found`, `workspace/not-found` | 404 | The addressed object does not exist. |
| `gateway/bad-request`, `session/attachment-invalid`, `session/invalid-time-zone` | 400 | The request is invalid as sent. |
| `agent-preset/not-found`, `agent-preset/locked`, `agent-preset/conflict` | 400 | The agent preset cannot be used here. |
| `workspace/name-conflict`, `workspace/move-invalid`, `workspace/invalid-path` | 409 | The project operation conflicts with the registry. |
| `session/agent-busy`, `session/writer-held`, `session/conflict` | 409 | The chat is busy or held — another race. |
| any `session/*unavailable` | 409 | The chat's model or resource is unavailable. |
| anything else, and any error with no `code` | 500 | Unrecognised; the code is forwarded verbatim, or `internal`. |

Two notes that matter for clients. `approval/settled` and `question/settled` are **expected outcomes of a race**, not
bugs: the desktop GUI and every paired phone are listeners on the same first-answer-wins waterfall, so a 409 means
somebody else got there first (`lib/index.js:3237`). And `config.debug === true` changes the table above: a failure
that is not already an `HttpError` answers `500` with the error's own name prefixed to the message and its code
unchanged, skipping the status mapping so the underlying failure is visible to the client (`lib/index.js:915`).

### Where this comes from

Everything above is read from `dsh-plugin-mobile-sync/lib/index.js` at the lines cited inline; the route list and
parameter spellings come from `handle()` at `lib/index.js:2222`–`2343`, and the field names are cross-checked against
the Kotlin decoders in `android/app/src/main/java/dev/dsh/mobile/data/MobileApi.kt` and `Models.kt`. One assumption
this contradicts: the Android client in this repository never opens `GET /mobile/events` — it polls the list routes,
so the SSE vocabulary above is implemented and broadcast but not consumed by the shipped app. Another: `GET
/mobile/permissions` calls a deployment without presets "a fact worth reporting rather than a failure to hide" in its
own comment while answering `503 permission/unavailable` (`lib/index.js:3427`, `3433`).
