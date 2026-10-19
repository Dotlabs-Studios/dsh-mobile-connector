# Changelog

## Unreleased

### Android client — `dev.dsh.mobile`

- **Polling is incremental and quieter.** The open chat asks for entries after the newest one it holds (`afterSeq`)
  instead of re-downloading the newest sixty every 2.5 s; both loops slow down when no turn is running and back off to
  a minute on failure. One owner — the activity — starts and stops them: the list used to keep polling behind an open
  chat, because a per-screen stop-effect never ran for the screen that was not composed.
- **The list screen reads the pulse.** Sessions, projects, approvals and questions arrive in one request (`/mobile/pulse`)
  when the host offers it, with the four separate reads still serving a host that predates the route.
- **A refused send no longer eats the draft.** The composer's text lives in the view model and is cleared only once the
  host accepts the message; a `sending` flag stops a second tap from re-posting spent receipts.
- **A gap in the tail is recovered instead of spliced.** If events were appended while the app was away and fell out of
  the host's newest window, the window is replaced with a contiguous one; "load older" now clears its own flag through
  the state machine, so an empty page stops coming back as a button on the next poll.
- **Strings follow the language you chose.** Titles and headings read through a locale-pinned context; the application
  context had been answering in the phone's language, which is how an Italian list grew a "No project" heading.
- **Devices are manageable from the phone.** Settings lists every paired device with when it was last seen, and revokes
  one — including this one, which returns to pairing with the address kept. Unpairing no longer resets the theme and the
  language: those are this phone's choices, not the harness's.
- **A contract test now holds the two halves together.** The plugin suite writes the real answers of seven routes into
  `test/contract/` with volatile values normalised, and fails when one of them changes without a regenerated fixture; a
  JVM test decodes those files with the client's own serializers. A renamed field on the host breaks a test here instead
  of a screen on a phone.
- **Settings → Diagnostics answers "is the PC there, and is it new enough".** One read of `/mobile/health` shows the app
  version, the plugin version with the wire `apiVersion`, the address, the measured round-trip, the uptime and both
  pending counts, with a copy button for the report. An older host that has no such route is named as one.
- **The problem banner offers Retry.** A link that may simply have come back is one tap from a fresh read instead of up
  to a minute of backoff; an expired token keeps the Reconnect action it needs instead.
- **A message composed while the link is down waits instead of dying.** A transport failure moves it to a small in-memory  outbox with the reason shown above the composer; the next poll that succeeds sends it. The sender mints a
  `clientMessageId`, and the host answers a repeat as `duplicate: true` without prompting twice — so a retry after a lost
  answer is the same message, not a second one. A refusal that is not a transport failure is dropped with its reason,
  because retrying a `writer-held` forever is a queue that never drains.
- **The plugin remembers a prompt id for a while.** `POST /mobile/sessions/{id}/messages` accepts `clientMessageId` and
  answers a repeat with `200 { accepted: true, duplicate: true }`, in memory and bounded (256), which is what makes the
  phone's retry safe.
- **The open chat is searchable.** The magnifier filters the transcript the phone already holds — message text, a
  tool's command and output, the file a delivery declares, the note beside an approval — and reports how many rows
  matched. It reads one already-built list, so a search never asks the host and never shows a row the reader could not
  have scrolled to; the match rule is a pure function with seven JVM tests.
- **A row says what changed since you last looked.** One dot on a chat whose `updatedAt` is newer than the last time
  this phone read it, decided from the host's own clock so a phone with a skewed clock cannot light every row, kept
  across restarts, and cleared by reading the chat (the open chat is never marked unread).
- **Watch mode: the phone tells you when the agent needs you.** An optional foreground service holds `/mobile/events`  while the app is closed and posts a notification for the three frames that need a person — an approval waiting, a
  question asked, a turn that stopped. Turning it on asks for the notification permission, unpairing turns it off, and
  the ongoing notification carries its own Stop. The three frames that earn a notification are decided by a pure,
  JVM-tested decoder, and a reconnect backoff keeps a dead link from becoming a busy loop.
- **The phone's lock can stand between a tap and the PC.** With *Ask before acting* on, approving a tool and granting
  *full access* open the platform's device-credential prompt (PIN, pattern, password or the biometric guarding them)
  instead of acting at once. No new dependency and no new permission: it is the same secret the phone already uses, and
  a phone with no lock configured proceeds rather than becoming unusable.
- **Long-press a message to copy it or hand it to another app** — two actions, no export format to own.
- **The PC address is editable without pairing again.** The token names this device in the harness's table, not an
  address, so a PC that moved (DHCP, a new router, a different tailnet address) is a field to change in Settings →
  Connection instead of a pairing to redo.
- **An approval card shows what is being approved.** The command about to run, or the file and the text about to be
  written, is drawn inside the card from the tool call the transcript already holds — the buttons used to be the only
  thing on it that named the act.
- **Photos are downscaled before upload.** A twelve-megapixel JPEG is sampled on decode, scaled to a 1568-pixel long
  edge and re-encoded on an IO thread; small photos keep their original bytes, GIF is untouched, and transcript images
  decode at the size they are drawn instead of full resolution.
- Search is debounced (one request per pause, not per keystroke), and the queue-or-steer choice appears only when there
  is something to send.

### Plugin — `dsh-plugin-mobile-sync`

**Security**

- **`allowRemoteFullAccess: false`** reserves *full access* to the PC: a phone switching a chat to a preset whose
  resolved approval knob is `never` is answered `403 permission/remote-forbidden` before the host is touched. The check
  reads what the preset does, not what it is called; the default keeps the old behaviour.
- **Pairing no longer accepts a device name as a credential.** With the code spent, `POST /mobile/pair` used to hand
  out the token of any device whose label the caller guessed — the label is printed in `/mobile/status`, so on a shared
  network it was a password made of a phone model. The two ways in are now a single-use code, or the token the caller
  already holds (rotation, same device id).
- A wiped phone comes back through `GET|POST /mobile/pairing-code`, which mints a code and answers **loopback only**;
  a device that still holds a token can rotate it without a code.
- Pairing attempts are rate limited per address (ten a minute) and refusals are logged.
- `GET /mobile/devices` and `DELETE /mobile/devices/{id}`: revoke a phone from the app instead of editing a file. A
  device may revoke itself (that is unpairing); the reply says `self: true`.
- `/mobile/file` refuses anything inside `$DSH_HOME` — where the device table and the provider credentials live — and
  any `.credentials*` file, with `403 file/forbidden`. `protectedPaths` in the plugin row adds more fences.
- `/mobile/pair` has its own 4 KiB body cap instead of the 48 MiB the upload routes need.

**Robustness**

- The state file is written atomically and serialised (flushed temp file, then rename), so a crash mid-write or two
  simultaneous pairings cannot erase the device table. A file that no longer parses is kept aside as
  `mobile-sync.json.corrupt-<ts>` and replaced with one that holds a freshly minted code, so log and disk agree.
- SSE readers are dropped past 1 MiB of unflushed bytes instead of buffering forever; a stalled phone can no longer
  grow the harness's heap without bound.
- `host` defaults to `127.0.0.1` (no phone admitted until it is set), `port` is validated at boot, header and request
  timeouts are set, and the disposer resolves pending approvals and questions.
- Directory browsing and file reads no longer create request-counter keys from unauthenticated paths.
- `GET /mobile/health` reports `apiVersion`, `pluginVersion` and the plugin's own counters; `/mobile/status` carries the
  same versions and the device ids. Tokens never appear in either.
- The state file is version 3: devices gain a stable `id` (derived from the token at creation, kept across rotations),
  and a table written by an older build is read and upgraded in place.

**Synchronisation**

- `GET /mobile/sessions/{id}/messages` accepts `afterSeq`: only entries newer than the cursor come back, so a poll with
  nothing new answers `[]` instead of the newest sixty entries. `nextBeforeSeq` is always present, even on an empty
  page, so a window that is all bookkeeping no longer leaves the older history unreachable.
- `GET /mobile/pulse` answers sessions, projects, approvals and questions in one payload: the list screen's four polls
  become one request.
- History, pulse, session list, approvals and questions carry an **entity tag** and honour `if-none-match` with `304`;
  `cache-control` is `no-cache` so a client can revalidate what it already holds.
- `limit`, `beforeSeq` and `afterSeq` are parsed strictly (`60abc` is a `400`, not `60`), and the two cursors are
  mutually exclusive.
- The link-preview fetch takes the request's abort signal, so a phone that gives up stops the host fetching too.

## v0.2.0 — 2026-10-15

First public release: the host plugin and the Android client, together.

### Plugin — `dsh-plugin-mobile-sync`

- Its own HTTP listener on the configured `host`/`port`, with a bearer token per paired device and a single-use pairing code that never expires.
- Routes for sessions, projects and directory browsing; transcript paging and prompting; photo and file uploads; attachment images; model selection; tool approvals; agent questions; permission presets; linked files; delivered artefacts; link previews.
- `GET /mobile/events` (Server-Sent Events) for clients that would rather be pushed to. The bundled Android client polls instead, deliberately.
- No `@deepseek-ai/*` dependency: every host service is injected through the Cordis context, so the plugin has no version peer to keep in step with the harness.
- The Web GUI, its loopback server and its cookie model are untouched.

### Android client — `dev.dsh.mobile`, versionCode 3, versionName 0.2.0

- Session list grouped by project, with folds that survive a restart, a five-chat slice per project, and a status dot read from the harness's own last durable event.
- Transcript with one card per tool call (wait, duration, outcome), diffs, rendered markdown, images, and the artefacts an agent declared with `present`.
- Sending text, photos and files; queue behind a running turn, or interrupt and steer.
- Tool approvals, agent questions with real option buttons, permission presets, and the model picker.
- Link previews and linked files read on the PC, not by the phone.
- System / light / dark theme with two complete palettes; Italian and English.

### Notes

- `minSdk` 26 (Android 8.0), `targetSdk` 36, `compileSdk` 36.
- The published APK is signed with the standard Android debug key: a sideload build, not a store build. See *Signing* in the README to build with your own key.

### Verified on this release

- 300 JVM unit tests (`gradlew testReleaseUnitTest`)
- 397 offline plugin checks (`node test/plugin-check.mjs`)
- 33 status-mapping checks (`node test/status-map-check.mjs`)
- Generated strings in sync with the tables in `tools/l10n/`
