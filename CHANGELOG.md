# Changelog

## Unreleased

### Android client — `dev.dsh.mobile`

- **Polling is incremental and quieter.** The open chat asks for entries after the newest one it holds (`afterSeq`)
  instead of re-downloading the newest sixty every 2.5 s; both loops slow down when no turn is running and back off to
  a minute on failure. One owner — the activity — starts and stops them: the list used to keep polling behind an open
  chat, because a per-screen stop-effect never ran for the screen that was not composed.
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
- Search is debounced (one request per pause, not per keystroke), and the queue-or-steer choice appears only when there
  is something to send.

### Plugin — `dsh-plugin-mobile-sync`

**Security**

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
