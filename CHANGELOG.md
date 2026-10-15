# Changelog

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
