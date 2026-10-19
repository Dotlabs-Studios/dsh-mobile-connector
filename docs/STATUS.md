# Where each item stands

One row per line of the improvement list this project has been working through: where
it lives, and what proves it. The point is that "done" here means *a test fails if it
breaks*, not that somebody wrote it down.

Legend: **done** — implemented and covered by a suite that runs in CI; **partial** —
implemented, with a named gap; **pending** — not written; **blocked** — nothing this
project can do until the harness grows the call.

## Last verified

2026-10-19, on `main`, with every gate run in one pass:

| Gate | Result |
|---|---|
| `node test/plugin-check.mjs` | 484 checks |
| `node test/status-map-check.mjs` | 33/33 |
| `node test/ui-stub-check.mjs` | 13 checks |
| `gradlew testDebugUnitTest` | 367 tests |
| `gradlew lintDebug` | 0 errors |
| `python tools/l10n-build.py --check` | 369 keys, both files in sync |
| `python tools/docrefs.py --check` | no line coordinates |
| `gradlew assembleDebug` / `assembleRelease` | 19.01 MB / 11.98 MB |
| `assembleRelease -PenableR8=true` | 1.72 MB, serializers present in the dex |


## Security

| Item | State | Where | Proof |
|---|---|---|---|
| Pairing token bypass | done | code-only pairing in `routePair`; a paired device cannot recover a token by naming itself | pairing block of `test/plugin-check.mjs` |
| `/mobile/file` fence | done | `assertPhoneReadable`: `$DSH_HOME` and `.credentials*`, applied to what a reference resolves to | fence checks, including the `file:` spelling |
| Atomic runtime-file writes | done | temp file, `fsync`, `rename`, serialised writer; an unreadable file is kept aside | atomic-write and corrupt-state checks |
| SSE backpressure | done | a subscriber past 1 MiB is dropped instead of buffered forever | stalled-subscriber check |
| Pairing rate limit | done | per-address window in `pairAttempts` | rate-limit check |
| Device ids, list, revoke | done | `id` on every device row; `GET /mobile/devices`, `POST /mobile/devices/{id}/revoke` | devices and revoke checks |
| Loopback pairing-code route | done | `GET`/`POST /mobile/pairing-code`, refused for anything but loopback | loopback checks |

## Synchronisation

| Item | State | Where | Proof |
|---|---|---|---|
| `afterSeq` incremental messages | done | route parameter plus `ChatSync.refresh` | plugin check and `ChatSyncTest` |
| ETag / 304 | done | `sendJsonEtag` on history, pulse, sessions, approvals, questions | revalidation checks |
| Single pulse endpoint | done | `GET /mobile/pulse`, with the client falling back to four reads on 404 | `pulse` contract fixture, `MobileApiTest` |
| Adaptive poll backoff | done | `pollDelay` (pure, both loops share it), capped at 60 s, saturating after four doublings | `PollBackoffTest` |
| SSE as invalidation signal | done | `SseParser` + watch service; the app still polls | `WatchFeedTest` |

## Client correctness

| Item | State | Where | Proof |
|---|---|---|---|
| Transactional send | done | the draft is cleared only on the host's answer | `AppViewModel.sendStaged` |
| Transcript gap backfill | done | a seq jump reloads the newest page instead of splicing | `ChatSyncTest` |
| `hasOlder` | done | owned by `ChatSync`, consumed by the list | `ChatSyncTest` |
| Locale-pinned strings | done | `localized()`; application-level strings replaced | `TranslationParityTest` plus the l10n build |
| Single poll-visibility owner | done | `AppVisibility` + the view model's foreground flag | compiled; `MainActivity` is the only writer |
| Photo downscale, off the main thread | done | `ImageSizing` (pure) + `PhotoStaging` on `Dispatchers.IO` | `ImageSizingTest` |
| Image cache | done | bounded in-memory cache with bounded decode | `Components.rememberImageLoad` |
| Search debounce | **not applicable** | in-chat search reads the list the phone already holds; there is no request to debounce | `TranscriptSearchTest` |

## Product

| Item | State | Where | Proof |
|---|---|---|---|
| Watch-mode notifications | done | foreground `remoteMessaging` service on the SSE feed | `WatchFeedTest`; device checklist |
| Approval context | done | `ApprovalPreview`: command, file, text about to be written | `ApprovalPreviewTest` |
| Lock gate on approvals | done | `rememberDeviceConfirmation` | compiled; device checklist |
| Remote full-access switch | done | `allowRemoteFullAccess`, resolved to a `403 permission/remote-forbidden` | permission checks in the plugin suite |
| Stop a turn | **blocked** | no interrupt entry point is reachable from a host plugin; `steer` is the only way | — |
| Message actions | done | copy, share, quote | `MessageQuoteTest` |
| In-chat search | done | `TranscriptSearch` over the held rows | `TranscriptSearchTest` |
| Unread tracking | done | `SeenCodec` + the dot on a row | `SeenCodecTest` |
| Offline outbox | done | in-memory queue + `clientMessageId` dedupe on the host | plugin checks and `MobileApiTest` |
| Editable address | done | `changeHost` keeps the token | `normalizeHost` tests |
| Two different PCs | **pending** | one pairing per install; several *addresses* per pairing | design in [ROADMAP.md](ROADMAP.md) |
| QR / mDNS pairing | **pending** | address typed once, code read from the PC | design in [ROADMAP.md](ROADMAP.md) |

## Engineering

| Item | State | Where | Proof |
|---|---|---|---|
| CI | done | plugin checks, status map, stub smoke, l10n, JVM tests, lint, APK | `.github/workflows/ci.yml` |
| Version handshake | done | `API_VERSION` vs `PLUGIN_VERSION`, reported by `/mobile/health` | health checks |
| `/mobile/health` + diagnostics | done | route plus the copyable report screen | health checks; device checklist |
| Contract test | done | fixtures recorded by the plugin suite, decoded by the JVM | `ContractFixturesTest` |
| Static analysis | done | `lintDebug` as a CI gate | lint run |
| API reference cannot rot | done | citations name the file; `tools/docrefs.py --check` in CI | CI step |
| R8 / resource shrinking | **partial** | rules written; `-PenableR8=true` builds 1.72 MB against 11.98 MB; every wire type and its serializer machinery verified present in the shrunk dex (`tools/dex-keep-check.py`) | measured; default off until the device checklist passes |
| Split the view model | **pending** | one 2 800-line class, six concerns | design in [ROADMAP.md](ROADMAP.md) |

## What the suites do not cover

`docs/VERIFICATION.md` is the list: a foreground service, the platform's credential
prompt, notifications, a network that actually dies, and the shrunk build. Everything
in this table that says "compiled" or "device checklist" in the Proof column is
waiting on that run.
