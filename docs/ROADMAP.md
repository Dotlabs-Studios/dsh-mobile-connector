# What is left, and how it was planned

Everything in the original improvement list that could be built and verified without
a phone is in `main`. What follows is the remainder, with enough design to be picked
up cold — the shape of the change, the files it touches, and the test that would
prove it. It is deliberately short: a plan that duplicates the code is worse than no
plan.

## 1. Two different PCs (home and office)

**Why it is not done:** the app holds *one* pairing. Address failover covers the same
PC on several addresses (LAN and tailnet); a second PC is a different token, a
different device table and a different session list, and it needs a data-model change
rather than a field.

**Shape of the change:**

- `SessionStore`: replace the four scalar keys (`host`, `token`, `device`, folds,
  `alternate-hosts`) with a table of pairings:
  `id → { label, host, token, deviceName, alternates, lastSeenAt }`, plus
  `activeId`. A one-shot migration reads the old keys into a single row (id
  `default`) when the table is absent, so an installed copy keeps working.
- `AppViewModel`: `switchTo(pairingId)` is the existing `changeHost` path with the
  token taken from the row instead of from `store.token`; the pair flow appends a row
  instead of overwriting. Everything else (`api`, `sync`, poll counters, failover)
  already rebuilds around a client.
- UI: a pairing switcher in Settings above the address; the pairing screen adds
  "Aggiungi un altro PC" and must not clear the existing table.
- Watch mode: the foreground service reads `activeId`; a switch restarts the stream.
- **The test that proves it**: a JVM test over a fake preferences map asserting the
  migration from the v0.3.0 keys, and that switching rows preserves each token.

**Effort**: the largest remaining item. Do it with a device at hand: it touches the
one code path (client construction) that every screen depends on.

## 2. QR pairing and local discovery

**Why it is not done:** both need either a dependency (camera/ZXing or ML Kit) or a
hand-written mDNS responder in Node, and neither can be verified here.

**Suggested split** — cheap first:

- **QR, PC side only**: `GET /mobile/pair-qr` on loopback renders a QR of
  `dsh://pair?host=<one of urls>&code=<setupCode>` as a PNG. Loopback-only keeps the
  code off the network; no phone camera needed to ship it, and the app can gain the
  scanner later without changing the contract.
- **mDNS**: a `dgram` responder advertising `_dsh-mobile._tcp.local` with TXT
  `api=2`, and Android `NsdManager` in the pairing screen listing discovered hosts.
  No dependency on either side; the binary DNS encoder is the work.
- **Scanner**: `androidx.camera` + `com.google.zxing:core` (small, no Play Services)
  behind a "Scansiona" button, feeding the three fields the pairing screen already
  has.

## 3. Make R8 the default

Blocked on one thing only: the device checklist in [VERIFICATION.md](VERIFICATION.md)
run against the shrunk APK.

```powershell
cd android
.\gradlew.bat assembleRelease -PenableR8=true   # 1.72 MB, already measured
adb install -r app\build\outputs\apk\release\app-release.apk
```

If the checklist passes, the change is two lines in `app/build.gradle.kts`: default
`shrink` to `true` and delete the property escape hatch (or invert it to
`-PdisableR8`). The keep rules are already in `proguard-rules.pro`.

## 4. Split `AppViewModel`

2 800 lines, six concerns, one class. The seams are already visible from the file:
session list (state + paging), chat sync ownership (currently delegated to
`ChatSync`), composer/staging, approvals and questions, the sheets (model,
permission, settings), and the readers (file, preview, delivery).

**Do it without behaviour change**: introduce the state holders as plain classes
that take `MobileApi` and a `CoroutineScope`, move one concern per commit, and keep
`AppViewModel` as a facade that the screens still receive. The JVM suite plus
`lintDebug` is the safety net; there are no instrumented tests to lean on, which is
why one concern per commit matters.

## 5. Interrupting a turn without sending a message

**Blocked by the harness, not by this app.** No interrupt/cancel entry point is
reachable from a host plugin: `sessionController` exposes prompt, page, list and
create, and the only documented way to stop a turn is a `steer` prompt. If the
harness ever exposes one, the shape is small:

- `POST /mobile/sessions/{id}/interrupt` in the plugin, forwarding to that call;
- a stop button beside the working indicator, with the lock confirmation when
  `confirmActions` is on;
- `502 interrupt/unsupported` until then, so an app built against a newer contract
  can tell "too old" from "failed".

## Order I would do them

1. **R8 default** — the cheapest, and it is already measured.
2. **Two PCs** — the last substantial product gap.
3. **Split the view model** — before adding anything else to it.
4. **QR/mDNS** — polish on onboarding, best done when the pairing table exists.
5. **Stop** — only if the harness grows the call.
