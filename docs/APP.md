## Inside the app

The Android client is one module (`dev.dsh.mobile`), one activity, and two packages: `data/` holds every
rule that can be decided without a screen, `ui/` holds the Compose tree and the single state holder.
There is no DI framework and no repository layer — `AppViewModel` is constructed by `viewModel()` in
`MainActivity.kt:78` and is the only object the screens talk to. Paths below are relative to the
repository root, and the Kotlin sources live under `android/app/src/main/java/dev/dsh/mobile/`.

### Where each responsibility lives

| Module | Responsibility |
| --- | --- |
| `MainActivity.kt` | The single activity: pins the language in `attachBaseContext`, resolves the stored theme before the first frame, dispatches to Pairing, Sessions or Chat. |
| `data/MobileApi.kt` | The whole wire contract: one OkHttp stack, JSON in and out, every `/mobile/…` route, the host's error envelope turned into `ApiException`. Uploads and delivery downloads run on derived clients with a ten-minute budget instead of the control plane's 45 seconds (`MobileApi.kt:60`, `MobileApi.kt:74`). |
| `data/Models.kt` | The `@Serializable` wire DTOs, plus the few derived fields the UI reads directly (display title, status word). |
| `data/ChatSync.kt` | The transcript window state machine — newest page, append-only refresh, paging window, last error. Also the visibility filter and the one-line tool summary. |
| `data/Transcript.kt` | Folds the flat session log into renderable rows; card summaries, edit recovery, indented argument disclosures. |
| `data/Markdown.kt` | The markdown parser: blocks (`MdBlock`) and inline runs (`MdInline`). No rendering. |
| `data/Diff.kt` | An old/new text pair into `DiffLine`s: shortest edit script, three lines of context, `⋯` gap markers, totals. |
| `data/Questions.kt` | Parses `ask_user_question` arguments into questions and options, and reads the chosen labels back out of a tool result. |
| `data/Permissions.kt` | The permission presets, and one rule: what a preset risks is read from its approval knob, never from its name. |
| `data/Delivery.kt`, `data/FileAttach.kt`, `data/ImageSniffing.kt` | The delivered-file model with the name and media type Android needs; the attachment bound and the staged-file record; image media types sniffed from file signatures. |
| `data/Links.kt`, `data/HostFile.kt`, `data/Preview.kt` | Whether a tapped link is a page or a file, and the two shapes those readers return. |
| `data/Folders.kt`, `data/Workspaces.kt`, `data/ChatStatus.kt` | The browse pane's decisions (which folder a typed name means, where a create landed); the chat list's structure without words (section keys, the five-chat slice); the five chat statuses with no labels attached, and the one rule that decides whether a row still draws one. |
| `data/LanguageMode.kt`, `data/LanguageChoice.kt`, `data/ThemeMode.kt` | The two stored choices and their consequences: which tile is ticked, and whether the activity has to be rebuilt. |
| `data/ImageSizing.kt` | Two arithmetic rules, pure for testing: how far a picked photo is scaled before upload, and which power-of-two subsample a transcript image is decoded with. |
| `data/PollBackoff.kt` | How long to wait before the next poll: the same arithmetic for both loops, with a cap and a saturating shift. |
| `data/HostFailover.kt` | Which address to try when the current one stops answering: the address in use is never its own fallback, duplicates and blanks are dropped, and a tried address is not retried. |
| `data/TranscriptSearch.kt` | Flattens a transcript row to the text a search should look at — including the command inside a collapsed card — and filters the rows already held. |
| `data/MessageQuote.kt` | The quoted form of a message: one `>` per line, cut at a readable length with the cut visible. |
| `data/SeenCodec.kt` | The storage format of the unread map, `id=millis` lines, kept out of the preferences class so it can be tested without Android. |
| `data/SeenMap.kt` | The one rule the unread map obeys: a chat's mark only moves forward, so a late poll cannot reopen a chat that has been read. |
| `data/ApprovalPreview.kt` | What an approval card may show about the call it is asking for: the command, the file, the text about to be written — never a guess. |
| `data/SessionStore.kt` | Everything that survives a restart (see below). |
| `data/TextRef.kt` | A message that has been decided but not spelled: resource id plus arguments. |
| `ui/AppViewModel.kt` | The single state holder: all observable state, both polling loops, sending, staging, and the open/closed state of every sheet. |
| `ui/PairingScreen.kt`, `ui/SessionsScreen.kt`, `ui/ChatScreen.kt` | The three screens, plus the new-chat picker inside the list screen. |
| `ui/TranscriptViews.kt` | One composable per transcript row: tool card, tool group, delivery, approval, question, working indicator, problem banner. |
| `ui/MarkdownView.kt`, `ui/Components.kt`, `ui/Inputs.kt`, `ui/Theme.kt`, `ui/Text.kt` | Rendering and kit: markdown drawing, image loading and lightbox, IN/OUT and code cards, palette and type scale, and `TextRef` resolution inside a composition. |
| `ui/SettingsSheet.kt`, `ui/ModelPicker.kt`, `ui/PermissionPicker.kt` | The three sheets that change a setting. |
| `ui/WatchSection.kt` | The watch-mode switch, the notification permission it asks for, and the sentence shown when the system refused; plus the lock-confirmation switch. |
| `ui/DiagnosticsDialog.kt` | The connection report: both versions, the address, the measured round trip and the queues, copied as text. |
| `ui/Confirm.kt` | The device-credential prompt behind *Ask before acting*: a tap that changes what the PC may do waits for the phone's own lock. |
| `watch/WatchService.kt` | The foreground service behind watch mode: one SSE connection, three kinds of notification, a reconnect backoff. |
| `ui/AppVisibility.kt` | One volatile flag saying whether the app is on screen; the watch service reads it to skip the notification about a turn the reader is already watching end. |
| `ui/LinkPreview.kt`, `ui/FileSheet.kt`, `ui/DeliverySheet.kt`, `ui/DeliveryFiles.kt` | The readers and the ways out: a page, a linked file, a delivered file with open, download and share. |
| `ui/PhotoStaging.kt`, `ui/ImageDownscale.kt`, `ui/FileStaging.kt` | Picking an image or a file, checking it, shrinking a photo to what a model reads (on an IO thread), and getting it onto the host. |

### State and data flow

`AppViewModel` is an `AndroidViewModel` whose state is plain Compose state — `mutableStateOf` properties
with private setters (`ui/AppViewModel.kt:140`, `:152`, `:255`), read directly by the screens. There is no
Flow or LiveData in it, and no second copy of anything: a screen redraws because the state object it read
changed. The one piece of state that is *not* Compose state is the transcript itself, which lives in
`ChatSync` (a plain class, `data/ChatSync.kt:16`) and is mirrored into `entries` after every read
(`ui/AppViewModel.kt:2171`).

**Freshness is polling, not streaming.** The host does expose a Server-Sent-Events route — `/mobile/events`,
registered at `dsh-plugin-mobile-sync/lib/index.js:2269` and served by `routeEvents`
(`index.js:3758`) — but this client never opens it. Nothing in `android/` subscribes to an event stream;
sync is driven entirely by two polling loops, and the code says so plainly (`ui/AppViewModel.kt:132`,
`data/MobileApi.kt:434`). The trade-off is deliberate: the mobile surface is read-mostly, the payloads are
small, and a poll survives the phone sleeping, changing networks and reconnecting to Tailscale without a
bespoke reconnect state machine. The cost is up to one interval of latency, and requests that return
nothing when nothing has changed.

The session list polls every 4 s (`LIST_POLL_MS`, `ui/AppViewModel.kt:2193`): `loadSessions()` then
`delay` (`:2051`), started from `init` when a pairing exists (`:507`) and restarted on pairing (`:610`).
One list poll also refreshes the PC's project registry, the pending approvals and the pending questions
(`:2102`, `:2105`, `:2106`) — approvals ride the list poll because an unanswered one blocks the agent on
the PC.

The open chat polls faster: `resumeChatPolling()` reads the newest page once and then refreshes every
2.5 s (`CHAT_POLL_MS`, `ui/AppViewModel.kt:2194`; loop at `:728`). `refresh()` calls
`ChatSync.refresh`, which reads the newest page and appends **only** entries whose `seq` is above the
newest already held — it never replaces the window (`data/ChatSync.kt:101`, append at `:111`). That rule
is what makes paging backwards safe: the log is append-only, so history the reader paged back through
cannot be thrown away by a slid-forward tail page. The chat poll also re-reads approvals and questions
(`ui/AppViewModel.kt:2160`), because it is the faster of the two loops. Both loops follow the app's own
visibility: the list loop is paused and resumed by `SessionsScreen`'s lifecycle effect
(`ui/SessionsScreen.kt:112`), the transcript loop by `ChatScreen`'s (`ui/ChatScreen.kt:109`). The
"running" flag on the chat screen is not read off the transcript — it comes from the session list row
(`ui/AppViewModel.kt:2141`), which is why the list loop keeps running behind an open chat.

**Sending while the agent is working.** When the open session is running, the composer shows a two-way
choice rather than guessing: *Queue behind the turn* or *Interrupt and steer*
(`ui/ChatScreen.kt:868`), defaulting to queue (`ui/ChatScreen.kt:851`). Both end in the same call,
`sendStaged(text, steer)` (`ui/AppViewModel.kt:1157`), which posts to `/mobile/sessions/{id}/messages`;
`MobileApi.send` writes `"mode": "steer"` only when steering and omits the field otherwise
(`data/MobileApi.kt:319`), and the host resolves anything that is not `steer` to `queue`
(`dsh-plugin-mobile-sync/lib/index.js:2900`). Two refusals get their own sentences instead of a raw code:
`session/writer-held` (the desktop GUI holds the harness's single writer for that session) and
`agent-busy` (`ui/AppViewModel.kt:1188`).

**Attachments are staged before the prompt, in two different ways.** A photo stays in memory as canonical
base64 (`stagedPhotos`, `ui/AppViewModel.kt:466`) and rides inline with the message, because the harness
admits inline image parts as real image content while a stored file reference would reach the model as an
opaque handle (`data/MobileApi.kt:310`). `PhotoStaging.kt` reads the picked URI, sniffs the media type
from the bytes rather than trusting the provider (`data/ImageSniffing.kt:24`), refuses anything over
12 MB, and encodes with `java.util.Base64` rather than `android.util.Base64` so the whole staging path
stays exercisable on the JVM (`ui/PhotoStaging.kt:53`). A file is uploaded the moment it is picked:
`stageFile()` POSTs the bytes to `/mobile/files?sessionId=…&name=…`, the host stores them and answers with
a receipt, and what waits in `stagedFiles` is that receipt (`ui/AppViewModel.kt:1259`, `:1270`;
`data/MobileApi.kt:383`). The prompt then carries only `receiptId` (`data/MobileApi.kt:334`) — a receipt is
bound to one chat and spent by one message, which is also why opening another chat clears the staged list
(`ui/AppViewModel.kt:682`). Sending is refused while an upload is still in flight (`:1167`): a message
that named a receipt which does not exist yet would silently lose the file. Caps are 8 photos
(`:2205`), 8 files and 32 MB per file (`data/FileAttach.kt:15`), enforced twice for files — from the size
the provider reports before reading, and again from the bytes actually read
(`ui/FileStaging.kt:46`, `:54`).

**What is persisted.** `SessionStore` is the only durable state, and it is a plain `SharedPreferences`
file named `dsh-mobile` (`Context.MODE_PRIVATE`, `data/SessionStore.kt:14`) — not encrypted; there is no
`EncryptedSharedPreferences` or keystore use anywhere in the app, so the bearer token is protected by the
app sandbox rather than by encryption at rest. Six values live there: the host base URL (`:17`), the
device token issued at pairing (`:22`), the device label shown on the harness side (`:27`), the theme mode
(`:38`), the language mode (`:51`), and the set of collapsed project keys (`:70`). They are read
synchronously because the first frame needs them: the theme decides the first frame's colours, and the
language is read a second time *before* `onCreate`, from `languageOf`, while the activity is wrapping its
own context (`data/SessionStore.kt:136`, `MainActivity.kt:47`). `clear()` on unpair drops all six at once
(`data/SessionStore.kt:85`), deliberately including the folds, which are host project ids. Nothing else is
persisted: staged photos and files, the transcript window, pending approvals and questions, the model
choice, which projects are expanded, delivery notices and the session list are all in-memory and are gone
when the process is. The distinction is intentional — the fold is a decision about this phone's list and
is remembered, while expanding a project is a look at it right now and is not
(`ui/AppViewModel.kt:181`, `:197`).

### A `data/` layer that decides words but never spells them

No user-facing sentence lives in a `.kt` file. Where a decision produces a message — which refusal an
error code means, what a state is called — the data layer returns a `TextRef`: either `TextRef.Res`, a
string resource id plus positional arguments, or `TextRef.Raw`, the host's own sentence passed through
unchanged (`data/TextRef.kt:25`). It is resolved where it is drawn, by `textResource(ref)` in a composable
(`ui/Text.kt:32`), which is also what re-reads it when the language changes; the paths that have a
`Context` but no composition call `TextRef.resolve(context)` instead (`data/TextRef.kt:89`).

`Res` is not a `String` in disguise, and that is the point: nothing can be read out of it without a
resource table, so a hard-coded sentence cannot travel this way (`data/TextRef.kt:17`). `Raw` exists for
the one kind of text this app does not write — whatever the host said when it failed — because inventing a
replacement for another program's error reads as confidently as a correct explanation.

The consequence is testability. Because the decision is a resource id rather than a sentence, the rule
("this refusal is the size cap", "this preset is the one that stops the agent asking") is a JVM assertion
that needs no device and no resource table — `FileAttachTest` asserts `R.string.file_attach_too_large`
directly, and `ChatSyncTest`, `TranscriptTest` and `MarkdownTest` never touch `android.*` at all. The few
promises that are genuinely about wording are checked by reading the resource file as XML through the
test-source helper `StringsXml.kt`, whose two candidate paths exist because Gradle runs unit tests from
the module directory (`StringsXml.kt:41`).

### The transcript model

The session log is flat and append-only: a tool call and its result are two separate events, approvals are
two more, and the turn and step markers around them are bookkeeping. `buildTranscript(entries)`
(`data/Transcript.kt:215`) rebuilds the harness's card structure:

- a `tool-call` opens a `ToolStep` and the `tool-result` with the same `callId` closes it; when either
  half carries no id the pair is closed in arrival order instead, and a result whose call was never seen
  stands on its own rather than being attached to the wrong card (`data/Transcript.kt:238`);
- a call whose result has not arrived stays in the list as running — the normal state while the agent
  works (`data/Transcript.kt:206`);
- an `approval-asked` is paired with its `approval-decided` under the same approval id, and both carry the
  tool call id, which is what lets a live approval held by the host be matched to the card in the log
  (`data/Transcript.kt:158`, `:247`);
- a `deliverable` event becomes its own row carrying the files the agent declared, and one that declares
  nothing readable is dropped rather than drawn as an empty card (`data/Transcript.kt:266`);
- `message` events become bubbles; other message kinds that are not conversational are filtered out
  earlier, in `visibleTranscript` (`data/ChatSync.kt:174`), which drops `system`/`developer` roles and the
  harness-injected `user` sources — the system prompt and runtime context are real model input and dwarf
  the conversation, and the desktop GUI is the place to audit them (`data/ChatSync.kt:139`);
- runs of two or more consecutive tool calls fold into one `ToolGroup` accordion; a lone call stays a card
  (`data/Transcript.kt:274`, `:285`).

A `ToolStep` computes its own duration from the two timestamps and reports none when either is missing
(`data/Transcript.kt:80`); a group sums what is known rather than presenting a partial total as the group's
cost (`:117`). The one line under a card's title is `cardSummary`, which picks the identifying argument —
`command` first for a shell call, then a fixed key order — and caps it at 160 characters
(`data/Transcript.kt:330`). A replace-style edit is recovered into an `EditPreview` so the card can be
drawn as a diff instead of a JSON blob (`:378`), and the disclosure under the card re-indents the raw
arguments without ever hiding a payload that fails to parse (`:401`). `data/Diff.kt` does the drawing
work: a real shortest-edit-script search with three lines of context, and a hard cap on edit distance
(`MAX_EDIT_DISTANCE`, `data/Diff.kt:34`) beyond which the middle is reported as replaced — an approximate
diff is worse than none, since a wrong one is indistinguishable from a right one at a glance.

Markdown arrives as markdown and is parsed, not shown raw. `parseMarkdown` (`data/Markdown.kt:108`)
produces headings, paragraphs, fenced code, lists, quotes, rules and tables; `parseInline`
(`data/Markdown.kt:376`) produces code spans, strong, emphasis, strikethrough and links. Two decisions in
that parser are about this app rather than the specification: an unclosed fence renders as code to the end
of the message, because a half-delivered reply is the normal state of a message that is still being
written and rendering its body as prose would make the text jump on every poll
(`data/Markdown.kt:100`); and an image reduces to its alt text, because a path a phone cannot fetch is not
an image. `MarkdownBody` parses once per text and draws the blocks with Compose text
(`ui/MarkdownView.kt:72`).

### Watch mode

Every screen in this app is reactive: it shows what the last poll said. Watch mode is
the one thing that runs while there is no screen at all. `watch/WatchService.kt` is a
foreground service that holds `/mobile/events` — the same route the data layer never
uses — and turns exactly three frames into notifications: an approval waiting, a
question asked, a `status` frame that says a turn stopped. Nothing else is announced,
and a frame that cannot be parsed is dropped rather than guessed at.

The parts that decide *whether* something is worth a notification are Android-free
and tested: `data/SseParser.kt` reads the frames (comments, `retry:`, multi-line
`data:`), `data/WatchEvents.kt` maps a payload to one of the three events. The service
itself is the thin part: one OkHttp streaming client, a reconnect backoff capped at a
minute, a bounded set of ids already announced so a reconnect cannot buzz twice, and a
short cooldown per session so one finished turn is one notification. `ui/AppVisibility`
is a single volatile flag the service reads to skip the notification about a turn the
reader is watching end.

It is off by default, it survives the app being closed, it asks for
`POST_NOTIFICATIONS` the first time it is switched on, and it is stopped by unpairing —
a watch with no pairing would otherwise sit in a reconnect loop against a host that
answers `401`.

### Theme and language

The palette is a choice, not a system echo: `ThemeMode` is `System`, `Light` or `Dark`
(`data/ThemeMode.kt:14`), applied by `MainActivity` after the ViewModel has been created and before
anything is drawn, so a stored choice colours the first frame (`MainActivity.kt:78`). Platform dynamic
colour is deliberately not used — it would repaint the app in colours unrelated to the brand
(`ui/Theme.kt:222`).

Italian is the base language and lives in `res/values`, not `res/values-it`, so a phone set to a language
this app does not ship falls back to the language the app was written in rather than to resource names;
English is an overlay in `res/values-en` (`data/LanguageMode.kt:12`). Both are declared in
`res/xml/locales_config.xml` and filtered at build time by `localeFilters`
(`android/app/build.gradle.kts:41`), which also keeps AndroidX's own translations out of the APK. The
choice is `System`, `Italian` or `English` and is stored by id (`data/LanguageMode.kt:30`); because a
`Context`'s locale is fixed when the context is made, it is applied in `attachBaseContext`
(`MainActivity.kt:47`, `data/SessionStore.kt:155`) and changing it recreates the activity
(`ui/AppViewModel.kt:574`, `data/LanguageChoice.kt:36`).

Both XML files are generated, never edited: the source is the 16 tab-separated tables under `tools/l10n/` (key, Italian, English), and `tools/l10n-build.py` writes `res/values/strings.xml` and
`res/values-en/strings.xml` — 368 resources each today. The generator refuses duplicate keys, empty
values, a placeholder that exists in one language and not the other, plural forms a language cannot ask
for, and characters that would not survive XML (`tools/l10n-build.py:15`). `TranslationParityTest` then
holds the two files against each other on the JVM, which is the only cheap moment to notice that Android
would otherwise answer a missing translation with one Italian sentence in the middle of an English
screen.

### Screens

| Screen | What it does |
| --- | --- |
| Pairing (`ui/PairingScreen.kt:45`) | The first run: host address, the pairing code from the harness log, and the device label. |
| Session list (`ui/SessionsScreen.kt:109`) | Every chat the harness knows, grouped into the PC's projects, with a status dot per row, a fold per project, a strip of pending approvals, search and a subagent toggle. |
| New-chat picker (`ui/SessionsScreen.kt:908`) | Two faces: the PC's recent projects, or a browse of the PC's own folder tree, with a create-and-start action. |
| Chat (`ui/ChatScreen.kt:106`) | The transcript, the composer, the queue-or-steer choice, attachment chips, and the model and permission chips. |
| Settings (`ui/SettingsSheet.kt:61`) | Appearance (three theme tiles, three language tiles) and the pairing itself, including unpair. |
| Model picker (`ui/ModelPicker.kt:59`) | The host's own model catalog, with capability badges shown only when the host could resolve them, and the reasoning effort per model. |
| Permission picker (`ui/PermissionPicker.kt:148`) | The host's permission presets, each row stating its consequence, with the full-access grant behind the same acknowledgement the desktop demands. |
| Link preview (`ui/LinkPreview.kt:65`) | What the harness read when the phone tapped a link: the page's words, fetched under the harness's own web policy, with the system browser one tap away. |
| File sheet (`ui/FileSheet.kt:66`) | A linked file read from the PC's disk by the host, rendered as markdown when it is markdown and shown verbatim when it is anything else. |
| Delivery sheet (`ui/DeliverySheet.kt:63`) | One file an agent handed over, plus the three things a phone can do with it: open in an app, download to the Download folder, or share. |
| Image lightbox (`ui/Components.kt:564`) | A transcript image at full size, loaded with the bearer token. |
| Problem banner (`ui/TranscriptViews.kt:1261`) | One dismissible line for the current connection or host problem, wherever it happened. |

### What the JVM suite holds down

The tests are plain JUnit 4 in the app module's `test` source set (`android/app/build.gradle.kts:121`);
there is no `androidTest` source set, no Robolectric, and no device in the loop.

| Test | The invariant it holds |
| --- | --- |
| `ChatSyncTest.kt` | Refresh appends only what is new and never replaces the window; a slid-forward page cannot erase history; a failed poll keeps the held transcript; injected system and developer text stays out; tool arguments are summarised rather than dumped. |
| `TranscriptTest.kt` | Calls pair with results by id, then by arrival order; an orphan result stands alone; an unpaired call stays running; approvals pair with their verdicts; a delivery is its own card; durations come from both timestamps; runs of two or more calls fold, a lone call does not. |
| `MarkdownTest.kt` | The parser's rules: headings need the space, a table needs a delimiter row, an unclosed fence is code, list indentation nests, markers without partners stay literal, arithmetic is not emphasis, and every sample keeps all of its words. |
| `MobileApiTest.kt` | The wire contract against a stub server: routes, query encoding, the exact JSON a send produces, `steer` versus queue, and that each host error code survives as its own `ApiException`. |
| `DiffTest.kt` | Diffs keep context, fold distant lines behind a gap, argue a replacement as two lines rather than a whole block, report totals that match the rows, and produce nothing for identical texts. |
| `QuestionsTest.kt` | Question and answer parsing: both multi-select spellings, the recommended marker read and removed, unparsable input yielding nothing, and an answer being empty only when nothing was chosen. |
| `PermissionsTest.kt` | Preset risk comes from the approval knob rather than the name, an unknown policy is not a grant, and an invented preset keeps the host's own words instead of a near guess. |
| `DeliveryTest.kt` | Delivered file names are repaired for Android rather than dropped, each extension gets the right media type or an honest generic one, and declarations decode with their indices. |
| `FileAttachTest.kt` | The 32 MB bound is refused exactly one byte past it, with the sentence that names the bound, and the label never has a dangling separator. |
| `FoldersTest.kt` | A create names the folder it made rather than the level that came back, an existing child is entered rather than re-created, and "up" is the ancestor the host reported. |
| `LinksTest.kt` | A bare name is a file, a drive letter is not a scheme, pages stay pages, and an address with no host is prose. |
| `WorkspacesTest.kt` | Removing a project reads as leaving the list and never as deleting files; a section is keyed by its project; the five-chat slice keeps the five newest and counts the rest. |
| `ChatStatusTest.kt` | Every wire word maps to one status, an unknown word draws nothing rather than a near verdict, and the five states say what happened rather than what colour they are. |
| `LanguageModeTest.kt`, `ThemeModeTest.kt` | Stored ids round-trip and are stable, an unknown or absent value falls back instead of failing, and only a real change asks for the activity to be rebuilt. |
| `TranslationParityTest.kt` | The two languages define exactly the same keys, plurals stay plurals, no sentence is empty, no placeholder is dropped or invented, and no escape would break the build. |
