# InsightJournal

Android journaling app. Two goals: a polished portfolio app, and a learning project
for AI engineering (the owner is a CS senior and must be able to explain the
RAG/embeddings work in an interview).

## Stack

- Kotlin + Jetpack Compose (Material 3), MVVM with a repository layer
- Firebase: Auth (email/password), Firestore, Cloud Storage
- Summaries: Anthropic API (small cheap model, e.g. Haiku) — Phase 2
- Embeddings: OpenAI `text-embedding-3-small` — Phase 3
- OCR: ML Kit on device, multimodal API fallback — Phase 4
- Voice: Android `SpeechRecognizer` — Phase 4
- Web companion: React on the same Firebase backend — Phase 5

## Roadmap and progress

1. **Core journal app** — done. Owner confirmed on the emulator against real
   Firebase: sign-up/sign-in, Firestore read/write, offline sync, per-user
   isolation, month grouping.
2. **Quick notes with AI summarization** — done; owner confirmed summaries,
   retry, and no repeat calls.
   **2b. Day's notes -> journal entry** — code complete and builds. **Not yet run
   end to end** (the emulator was signed out, so the draft call, the editor
   hand-off and note linking are untested). Awaiting the owner's manual test.
   **2c. Branding, keyword search, mood, insights, themes** (built 2026-11-16 in one
   unattended session at the owner's request, outside the usual phase-by-phase
   rhythm). Builds, 38 unit tests pass, and every screen was run on the emulator
   in demo mode (screenshots in `docs/screenshots/`). **Not yet run against real
   Firebase**: the `mood` field round-trip and the `insights/weeklySummary`
   document are untested there. Awaiting the owner's manual test.
   **2d. End-to-end encryption, fingerprint unlock, AI consent, forgot password**
   (built 2026-11-18 on branch `feature/e2e-encryption`, not committed). Builds;
   50 unit tests and 4 emulator tests (`DeviceKeyStoreTest`) pass; the sign-in
   screen and "Forgot password?" were checked on the emulator. **Nothing after
   sign-in has been run** (no test account was available): setup, unlock,
   recovery, migration of old plaintext documents, fingerprint unlock, Settings
   and the AI consent dialog are untested. Awaiting the owner's manual test.
3. Embeddings + RAG semantic search (key resume milestone) — not started.
   Depends on 2d: embeddings are stored encrypted, so similarity search runs
   on the device.
   **Quick-note widget** (2026-11-16): built and run on the emulator in demo mode
   (added from the widget picker, tap opens the pop-up, saved note appears in
   Quick notes). Not yet run signed in against real Firebase.
4. Voice input, camera/OCR — not started
5. React web app — not started

Work phase by phase. After each phase: build, tell the owner what to test manually,
then stop and wait for confirmation before the next phase.

## Architecture

```
app/src/main/java/com/adam/insightjournal/
  InsightJournalApp.kt     Application + AppContainer (manual DI)
  MainActivity.kt          Splash overlay, theme, auth gate, NavHost (entries, notes, insights,
                           search, appearance, editor?entryId=) + three bottom tabs
  data/
    model/Models.kt        JournalEntry, QuickNote, Attachment (no Firebase types)
    AuthRepository.kt      interface + FirebaseAuthRepository + LocalAuthRepository
    JournalRepository.kt   interface + InMemoryJournalRepository (demo mode)
    FirestoreJournalRepository.kt   Firestore impl + hand-written document mapping
    QuickNoteRepository.kt interface + Firestore impl + in-memory impl
    WeeklySummaryRepository.kt   latest AI weekly summary: Firestore impl + in-memory impl
  search/
    JournalSearch.kt       keyword search over loaded entries + notes (pure Kotlin, no Android)
  insights/
    MoodStats.kt           mood averages, per-day points, weekday/distribution, streaks (pure)
    WeekDigest.kt          the past 7 days' material, its numbers and its fingerprint (pure)
  ai/
    JournalAi.kt           interface + AnthropicJournalAi (official Java SDK):
                           summarize(note), draftEntry(day's notes), summarizeWeek(digest);
                           parseDraft, weekPrompt
    EntryDraftHolder.kt    passes an AI draft from Quick notes to the editor
    QuickNoteSummaries.kt  when to summarize, in-progress/failed status per note
  crypto/
    JournalCrypto.kt       AES-256-GCM seal/open, PBKDF2, wrap/unwrap data key,
                           RecoveryCode, passphraseProblem (pure, JVM-tested)
    DeviceKeyStore.kt      this phone's copy of the data key, wrapped by Android Keystore
                           (plain mode or biometric-only key)
    KeyManager.kt          VaultState machine; setup/unlock/recover/biometric/change/lock/
                           delete-everything; ContentCipher (what repositories use)
  data/EncryptedFields.kt  `enc` field helpers, float packing, whileUnlocked()
  ai/AiSettings.kt         AI on/off switch (off by default, per phone)
  widget/
    QuickNoteWidget.kt     home screen widget (AppWidgetProvider + RemoteViews, static layout)
    QuickNoteActivity.kt   translucent pop-up the widget opens; saves like the Quick notes tab
  ui/
    auth/      AuthScreen + AuthViewModel
    entries/   EntryListScreen + EntryListViewModel (groupByMonth)
    editor/    EntryEditorScreen + EntryEditorViewModel
    notes/     QuickNotesScreen + QuickNotesViewModel
    search/    SearchScreen + SearchViewModel
    insights/  InsightsScreen + InsightsViewModel + MoodCharts (Canvas charts)
    settings/  SettingsScreen (security, AI toggle, account) + AppearanceScreen (theme picker)
    security/  VaultScreens (VaultGate: setup, unlock, recovery, reset) + Biometrics (prompt)
    ai/        AiConsent (AiGate + consent dialog wrapping every AI action)
    splash/    SplashScreen (full-screen loading artwork)
    theme/     Palettes (color schemes from hues), ThemeSettings (+ repository), Theme, Type
```

- **Demo mode**: if `app/google-services.json` is missing, the Google Services
  plugin is not applied, `FirebaseApp` has no instance, and `AppContainer` swaps in
  the local auth and in-memory repositories. The list screen shows a banner.
- **Firestore layout**: `users/{uid}/entries/{entryId}` and
  `users/{uid}/quickNotes/{noteId}`. Attachment metadata is embedded in the entry;
  files go to Storage at `users/{uid}/attachments/{entryId}/{attachmentId}`.
- **Rules**: `firestore.rules` and `storage.rules` allow a user only under their own
  `users/{uid}/` path. Deploy with `firebase deploy --only firestore:rules,storage`.
- **Writes are fire-and-forget** (`saveEntry`/`deleteEntry` are not suspend):
  Firestore writes to its offline cache at once and syncs later; awaiting would hang
  offline. Server rejections surface through `JournalRepository.writeErrors`.
- **Mapping is manual** (`toMap` / `toJournalEntry`), not reflection, so the document
  shape is explicit and models stay free of Firebase types.

- **Summaries**: a quick note is saved first, then summarized on the app-wide
  coroutine scope and the result written to the note's `summary` field. Rules, all
  in `QuickNoteSummaries`: only notes of 200+ characters, only when `summary` is
  null, never twice. Failures leave the note intact with a Retry button. Model
  `claude-haiku-5-5` at low effort via `com.anthropic:anthropic-java`.
- **Notes -> entry**: Quick notes are grouped by day. "Create journal entry" sends
  the day's unused notes (raw text, in time order) to the model, which returns
  "title, blank line, body". The draft opens in the normal editor via
  `EntryDraftHolder`; nothing is written until the user saves. On save the entry
  gets `source = "quick_notes"` and `sourceNoteIds`, and each note gets `entryId`.
  Notes are kept; days fully in the journal start collapsed. Deleting the entry
  clears `entryId` on its notes. Generation is user-triggered and reviewed on
  purpose; do not make it automatic.
- **API key exposure**: `ANTHROPIC_API_KEY` is compiled into the APK through
  BuildConfig. Fine for a personal build; before distributing the app, move the
  call behind a server (e.g. a Cloud Function, which needs the Blaze plan).

- **Mood**: `JournalEntry.mood` is an optional 1..5 (`Mood` in Models.kt), picked
  with five faces in the editor and stored as a number on the entry document.
  Old entries simply have none; nothing needs migrating.
- **Keyword search** (`search/JournalSearch.kt`): scans the entries and quick
  notes already in memory on each keystroke. Every word must appear (any order,
  any case, inside longer words); title matches rank higher; the snippet is cut
  around the first match with highlight ranges. It is plain text matching on
  purpose and is NOT the Phase 3 semantic search, which can later add its hits
  as a second source of `SearchHit`s.
- **Weekly summary**: the Insights tab shows the past 7 days (today included).
  `WeekDigest.fingerprint` hashes the week's texts and moods; the stored summary
  keeps the fingerprint it was written from, so the screen knows when it is out
  of date and offers "Update summary". Generation is button-only (never on
  open), one call per press, each text cut to 4000 chars. Stored as one document
  at `users/{uid}/insights/weeklySummary` (covered by the existing rules).
- **Mood trends**: all numbers come from `moodStats()` / `journalingStreaks()`,
  computed on the device from loaded entries; charts are drawn with Canvas, no
  chart library.
- **Themes**: six built-in palettes plus wallpaper colors (Android 12+), a
  light/dark/match-phone switch and three fonts. A palette is a few hues and a
  saturation; `paletteScheme()` derives every Material role from them, and
  darkens light-theme accents until they reach 4.6:1 contrast (`PalettesTest`
  checks every palette). Choices live in SharedPreferences (`appearance`), per
  device, not in Firestore. Default is Sunset, the logo's colors.
- **Logo and loading screen**: launcher icon is an adaptive icon (bitmap
  foreground in `mipmap-*/ic_launcher_foreground.webp` on a solid
  `ic_launcher_background` color; no monochrome layer). Android 12+ shows its
  own launch screen (icon on `splash_background`); the app then shows
  `drawable-nodpi/splash_art.webp` full screen for 1.6s while the journal loads
  underneath. Both images were cropped from one 1254px picture, so they are a
  little soft on high-density screens; higher-resolution originals would drop
  straight in (update `ART_ASPECT` in SplashScreen.kt if the proportions change).

- **Widget**: a static 3x1 bar (`res/layout/widget_quick_note.xml`) whose only
  job is to open `QuickNoteActivity`, a see-through activity that draws a card
  above the keyboard. Saving calls `quickNoteRepository.addNote` then
  `quickNoteSummaries.summarize`, the same as the tab. Signed out, it shows a
  message with an "Open app" button. The pop-up is not exported, so
  `adb shell am start` cannot open it; test it from a placed widget. Its scrim
  uses `pointerInput`, not `clickable`, so it never takes focus from the field.

## End-to-end encryption (phase 2d, built as designed below)

Implementation notes beyond the design: the setup screen withholds the unlock
until the user ticks "I've saved my recovery code" (`finishSetup`); `setUp`
checks the server (`Source.SERVER`) so it can never overwrite an existing key;
signing out forgets the device copy; with fingerprint unlock on, the app
re-locks after 5 minutes in the background (MainActivity onStop/onStart);
legacy plaintext documents are rewritten encrypted after every unlock
(AppContainer); a document that fails to decrypt is shown as "Can't decrypt"
and the editor refuses to open it. MainActivity is a FragmentActivity because
BiometricPrompt needs one. PBKDF2 at 600,000 iterations took ~4.3 s on the x86
emulator; real phones should be faster, but watch unlock time.

**Why**: Firestore rules only stop other app users. Anyone with console access
(the developer) can read every entry. The goal is that the database holds only
ciphertext and the developer cannot read journals, even with full console access.
It does NOT hide text from the AI providers (Anthropic, OpenAI), which receive
plaintext from the device for summaries, drafts, weekly insights and embeddings,
and it does not protect a compromised or unlocked phone. State this in any privacy
policy.

**Scheme (envelope encryption, platform crypto only, no new dependency)**
- A random 256-bit data key (DEK) encrypts all content with AES-256-GCM
  (`javax.crypto`, 12-byte random nonce per write). The document id is passed as
  AAD so a ciphertext cannot be moved to another document.
- The DEK is stored wrapped, never raw, in `users/{uid}/keys/main`, once per way
  of unlocking it: (1) a key derived from the user's passphrase with
  PBKDF2-HMAC-SHA256 (600,000 iterations, random salt; WebCrypto has PBKDF2 too,
  which keeps the Phase 5 web app compatible), and (2) a key derived from a random
  recovery code shown once at setup. A wrong passphrase shows up as a GCM tag
  failure on unwrap. Changing the passphrase re-wraps the DEK; no data is rewritten.
- Each device keeps the DEK wrapped by an Android Keystore key (in app-private
  storage, excluded from backup), so the passphrase is typed once per device.
  Sign-out or "lock" removes it.
- Lost passphrase AND recovery code = data is unrecoverable. The unlock screen
  offers "reset and delete all data" as the only way out.

**What is encrypted** (one blob per document, field `enc` = version + nonce +
ciphertext, Base64, over a JSON payload)
- Entries: title, body, summary, mood, embedding (as packed floats), embeddingModel,
  attachment metadata.
- Quick notes: text, summary, embedding, embeddingModel.
- `insights/weeklySummary`: the summary text.
- Attachment files (when built): encrypted bytes before upload to Storage.
- Left readable on purpose: `entryDate`, `monthKey`, `createdAt`, `updatedAt`,
  `source`, `sourceNoteIds` / `entryId` (random ids), so ordering and the existing
  queries keep working. Embeddings are encrypted because they can be partly
  inverted back to text.

**Code layout**
- `crypto/Crypto.kt`: pure functions (derive key, wrap/unwrap DEK, seal/open with
  AAD). JVM unit tests: round trip, wrong key, tampered ciphertext, wrong AAD.
- `crypto/KeyManager.kt`: state `NeedsSetup | Locked | Unlocked`, holds the DEK in
  memory only, owns the Keystore-wrapped device copy and the `keys/main` document.
- `FirestoreJournalRepository`, `QuickNoteRepository`, `WeeklySummaryRepository`
  seal in `toMap` and open in the reader; they only emit while Unlocked. A document
  that fails to decrypt shows a placeholder instead of crashing. Documents with no
  `enc` field are read as plaintext (old data), so migration can be lazy or a
  one-time "encrypt existing entries" pass that rewrites each doc with a full `set`.
- `ui/security/`: setup screen (passphrase + recovery code), unlock screen,
  settings (change passphrase, new recovery code, lock now). `MainActivity` gets a
  key gate after the auth gate. The widget pop-up shows "Open the app to unlock"
  when no key is on the device.
- Demo mode (no Firebase) skips all of this.
- No plaintext in logs, ever.

**Biometric unlock**: built (Settings > Unlock with fingerprint). The data key is
wrapped by a Keystore key that needs a strong biometric for every use and is
invalidated when fingerprints change; the plain copy is then removed.

**AI privacy (2026-11-18; layer 1 built, layers 2-4 still proposals)**: layer 1 =
`AiSettings` (off by default) + `AiGate` consent dialog on first use; automatic
note summaries never run while off. there is no
"incognito" mode for the Anthropic or OpenAI APIs. Per their docs both say API
data is not used for training by default and is kept up to ~30 days for abuse
monitoring; zero data retention is by application and not for individuals.
Proposed layers: (1) AI features opt-in with a consent screen and per-feature
toggles, including the automatic note summaries; (2) Phase 3 embeddings computed
on the device (e.g. EmbeddingGemma via MediaPipe/LiteRT) instead of OpenAI, after
a side-by-side quality check on real entries, so semantic search never leaves the
phone; (3) later, an on-device option for summaries/drafts behind the existing
`JournalAi` interface, with Claude as the labelled cloud option; (4) before any
distribution, a server proxy that does not log content.
Login has a "Forgot password?" link (`AuthRepository.sendPasswordReset`). It
resets the account password only; the journal passphrase is separate.

## Design decisions

- Phase 2/3 fields (`summary`, `embedding`, `embeddingModel`) are nullable stubs on
  the models so no schema migration is needed later.
- Generate summaries and embeddings once on write, store them in Firestore, never
  regenerate unless the text changed. Embed on write, not on read. Target API cost
  well under $5/month.
- Open question for Phase 3: `embedding` on the entry document is loaded with every
  list read (~1536 numbers per entry). Consider a separate chunks subcollection.
  With encryption (2d) the embedding lives inside the encrypted blob and similarity
  runs on the device, so Firestore's server-side vector search is not an option.
- Data/repository layer before UI. Manual DI via `AppContainer`; add Hilt only if it
  earns its place.
- Keep retrieval logic for Phase 3 isolated in its own readable module.

## Conventions

- Never hard-code API keys. Keys go in `local.properties` and reach code through
  `BuildConfig`. Phase 2 needs `ANTHROPIC_API_KEY=...` in `local.properties`. `local.properties` and
  `app/google-services.json` are gitignored.
- All dependency versions live in `gradle/libs.versions.toml`.
- Diagnose build failures at the root cause; no workaround piles.
- Simple, idiomatic code over clever abstractions. Handle offline, API failure and
  empty states.
- Explanations to the owner: short and concrete; show by building and running.

## Build and run

`java` is not on PATH; use Android Studio's JDK:

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
./gradlew assembleDebug
```

compileSdk 36, targetSdk 35, minSdk 26. Kotlin 2.3.0 (Firebase BoM 35.x needs
Kotlin 2.3+; BoM 35.1.0 failed to compile on 2.0.21), google-services 4.5.0. Emulator AVD: `Medium_Phone_API_36.0`.
Git: `main` tracks `origin` (https://github.com/ajbeus/InsightJournal). A copy of the source from before
the 2026-11-16 session is in `.backup/pre-overhaul-2026-11-16.tar.gz` (gitignored).

Unit tests (pure logic: search, mood stats, week digest, palettes, crypto): `./gradlew testDebugUnitTest`.
Device tests (Keystore, PBKDF2 timing): `ANDROID_SERIAL=emulator-5554 ./gradlew connectedDebugAndroidTest`
(set the serial: the owner's phone is sometimes plugged in too).

Emulator notes: headless (`-no-window`) `adb exec-out screencap` returns blank
frames; use `adb emu screenrecord screenshot <dir>` instead. `adb shell input
text` only reaches Compose fields reliably after
`adb shell settings put secure show_ime_with_hard_keyboard 0`.
