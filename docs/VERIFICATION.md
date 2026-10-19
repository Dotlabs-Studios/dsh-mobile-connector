# On-device verification

Everything in this repository is compiled and covered by JVM or Node tests, but a
few features only exist on a phone: a foreground service, the platform's credential
prompt, notifications, and the way a real network behaves when it dies. This is the
checklist that closes that gap. It is not a substitute for the suites — run those
first — it is the part they cannot see.

Each row says what to do, what must happen, and what a failure usually means. Keep a
`dsh --profile desktop` log open while you do it: the plugin's own lines are the
other half of every scenario.

## Before you start

- Build and install the current commit: `.\gradlew.bat assembleDebug`, then
  `adb install -r app\build\outputs\apk\debug\app-debug.apk`.
- Pair the phone once, with the code from the harness log.
- Have a second way to reach the PC (Tailscale on both ends, or a second LAN
  address) if you want to test failover.

## Watch mode

| Step | Expected | If it fails |
|---|---|---|
| Settings → Alerts → switch on | Android asks for the notification permission (13+); a fixed "Listening to the PC" notification appears | No service: `adb logcat | grep WatchService`; the process may have been force-stopped |
| Close the app, then ask the agent (from the PC) to run a command that needs approval | A notification arrives within a few seconds, naming the tool | Check that the ongoing notification is still there; a phone that killed the app also killed the service |
| Tap the notification | The app opens on the session list | Notification tap: `MainActivity` launchMode is `singleTop` |
| Stop from the notification's action | The service stops and the ongoing notification goes away | — |
| Turn the switch off, then on again | Exactly one ongoing notification, no duplicates | Two services: `adb shell dumpsys activity services dev.dsh.mobile` |
| Unpair while watch is on | The service stops by itself; no more notifications | A watch without a pairing is a reconnect loop against a 401 |

## Lock confirmation

| Step | Expected | If it fails |
|---|---|---|
| Settings → Alerts → Ask before acting on | The switch stays on | — |
| Tap *Allow once* on an approval | The system credential prompt appears before anything is sent | The phone has no lock set: the action proceeds by design |
| Enter the PIN / biometric | The approval reaches the host; the card settles | `dsh` log shows the approval only after the prompt |
| Cancel the prompt | Nothing is sent; the card stays answerable | — |
| Switch a chat to *Full access* | The same prompt, then the host's acknowledgement dialog | — |

## Diagnostics

| Step | Expected | If it fails |
|---|---|---|
| Settings → Diagnostics | Plugin version, wire `apiVersion`, address, latency, uptime, queue counts | A very old plugin answers `404`: the screen names that instead |
| Stop the harness on the PC, reopen Diagnostics | The same screen shows the failure, not a spinner forever | — |
| Copy | The report lands on the clipboard as plain text | — |

## Offline send (outbox)

| Step | Expected | If it fails |
|---|---|---|
| Turn the PC's Wi-Fi off (or stop the harness), type a message, send | The message moves to the queued strip with the reason; the composer clears | It stayed in the composer: the failure was not a transport error |
| Turn the network back on and wait one poll interval | The message is delivered once — exactly once — and the queued strip empties | A duplicate means the `clientMessageId` did not survive the retry |
| Queue two messages, restore the network | Both are delivered, in order | — |
| Queue a message, then close the app before the network returns | The queue is gone (it is in memory by design) and the text is not | That is the documented trade-off, not a bug |

## Address failover

| Step | Expected | If it fails |
|---|---|---|
| On Wi-Fi, open a chat and wait for a poll | The app learns the LAN and tailnet addresses (`/mobile/status` once) | Old plugin: no `urls` in the status payload |
| Turn off Wi-Fi so only Tailscale remains, then wait | The next poll fails once, the client switches address, and the chat continues | Check `mobile-sync: failing over` in logcat; only addresses the host reported are candidates |
| Come back to Wi-Fi | The app keeps the working address until it fails; the manual *Change address* still works | Failover is one-way until something fails again |

## Media

| Step | Expected | If it fails |
|---|---|---|
| Send a 12 MP photo | The upload is a fraction of the original; the model still sees it | Check logcat for the staging line; a GIF is never re-encoded |
| Scroll a transcript with photos up and down | Images come from the cache, without re-downloading | A 24 MB cache is bounded; scrolling far past it re-fetches |

## What is *not* on this list

- **R8/resource shrinking** is not enabled in the release build. The keep rules it
  would need are already in `android/app/proguard-rules.pro` (kotlinx.serialization's
  generated serializers and companions, OkHttp's optional platform classes), so
  turning it on is one line — but it needs a device run of the whole checklist above
  first: a stripped serializer fails at the first decode, not at build time.
- **Two different PCs** (home and office) are not supported: the app holds one
  pairing, with as many addresses for it as the host reports.
- **QR/mDNS pairing** does not exist yet; the address is typed once and the pairing
  code is read from the PC.
- **Interrupting a turn without a message** is not possible: the harness exposes no
  such call, so *Interrupt and steer* remains the only way to stop one.
