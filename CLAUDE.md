# Boet — build notes & feature status

This is the working reference for the Boet repo. **Before deciding what to build
next, consult the feature checklist below** (kept in sync with the README). Update
a box when a feature genuinely lands end-to-end (backend + app + verified).

Legend: ✅ done · 🟡 partial · ⬜ not started

## How things are built / verified

- Backend: Node + Express + ws + Postgres (`server/`), `docker compose up -d --build`, port **3020**. Verified end-to-end.
- Android: Kotlin + Compose (`android/`). **This LXC can now BUILD the APK locally** — far faster than the connector. The connector is only needed for `adb install` + on-device verification (the physical phone is plugged into the workstation, not this LXC).
  - **Local build (preferred):** toolchain is installed in this workspace — JDK 17 (`/usr/lib/jvm/java-17-openjdk-amd64`, apt `openjdk-17-jdk-headless`) and the Android SDK at `/root/android-sdk` (cmdline-tools 12.0, `platforms;android-34`, `build-tools;34.0.0`, `platform-tools`, licenses accepted). `android/local.properties` has `sdk.dir=/root/android-sdk`. To build:
    ```bash
    cd /workspace/Boet/android
    export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ANDROID_HOME=/root/android-sdk
    ./gradlew assembleDebug --no-daemon   # ~1.5 min; APK → app/build/outputs/apk/debug/app-debug.apk
    ```
    Toolchain note: ML Kit GenAI (`genai-prompt`/`genai-common` betas) ship **Kotlin 2.2.0** metadata, so the project is on **Kotlin 2.2.0** + **KSP2** (`ksp.useKSP2=true` in `gradle.properties`) + **Room 2.7.2** (KSP1/Room 2.6.1 fail on Kotlin 2.2). compileSdk 34 is fine. `local.properties` is git-ignored.
  - **Deploy to the device via the "Windows Workstation" connector.** Connected device = **Kalle's OnePlus 15** (`adb` model `CPH2747`, serial `3B161H00NNQ00000`); Klara has a Samsung S24. Workstation staging dir `C:\BoetBuild\` (space-free — Gradle dislikes the `OneDrive - KWA` path). Deploy the locally-built APK:
    1. `opus connector put app/build/outputs/apk/debug/app-debug.apk "Windows Workstation:C:/BoetBuild/app-debug.apk"`
    2. `opus connector run "Windows Workstation" -- cmd "adb install -r C:\BoetBuild\app-debug.apk"`
    3. Drive/verify with `adb shell input` + `screencap`; check the on-device LLM probe with `adb logcat -s BoetLLM` (`status=AVAILABLE/DOWNLOADABLE/UNAVAILABLE` — tells us if Gemini Nano runs on the OnePlus 15).
  - The workstation also has Android Studio's own SDK at `C:\Users\KarlAlmqvist\AppData\Local\Android\Sdk` if a Windows build is ever needed (build there in `C:\BoetBuild\android`, write `local.properties` pointing at it). PowerShell over the connector: pass scripts via `--shell powershell --stdin`; inline `$var` in `opus connector run -- powershell "…"` gets eaten by the local workspace shell. `OneDrive - KWA\Bilder\Apps\Boet` holds only logo art, not code.
- Default app server is `https://boet.jabba.se`; for on-device testing against this workspace, temporarily set `Prefs.DEFAULT_SERVER` to the LAN IP (revert before commit).

### Releasing the app (this is what powers in-app auto-update)

The app is sideloaded (no Play Store) and self-updates via `update/UpdateChecker`,
which reads `https://api.github.com/repos/Karlmit/Boet/releases/latest`, compares
the release version to the installed one, downloads the attached `.apk` (into
`cacheDir/updates/`, served to the installer via the `…fileprovider`), and launches
the system installer. **For that to keep working, every release MUST follow this
convention** — break it and existing installs stop seeing updates:

1. Bump **both** `versionCode` (monotonic int) and `versionName` (dotted, e.g.
   `1.2`) in `android/app/build.gradle.kts`. The updater compares `versionName`
   numerically (`versionFromTag` extracts `\d+(\.\d+)+` from the tag).
2. Build the release APK:
   ```bash
   cd android && export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ANDROID_HOME=/root/android-sdk
   ./gradlew assembleDebug --no-daemon
   ```
3. Commit the version bump, then publish a GitHub Release:
   ```bash
   cp app/build/outputs/apk/debug/app-debug.apk /tmp/boet-<version>.apk
   gh release create app-v<version> /tmp/boet-<version>.apk -R Karlmit/Boet \
     --title "Boet <version>" --notes "…"
   ```
   - **Tag = `app-v<version>`** (e.g. `app-v1.2`). Do **not** use a `v*` tag — that
     would trigger `docker-publish.yml` and rebuild/publish the *server* image.
   - **Asset name = `boet-<version>.apk`.** The updater picks the first `*.apk`
     asset on the latest release, so any `.apk` works, but keep the name consistent.
4. The first install on a new phone is always manual (the updater ships *inside*
   the app); subsequent updates are in-app. The APK is signed with the **debug**
   key — fine for sideloading, but signing key must stay consistent or installs
   fail with a signature mismatch (don't switch to a release keystore mid-stream
   without coordinating a reinstall).

## Known design-fidelity gaps vs `.planning/Design/`

In place: palette, Manrope, serif wordmark, nest icon, **compact grouped category
cards** (hairline dividers, leading icons, collapse chevrons), per-item **drag
handles** (long-press reorder within a category), the **banner** (raised title +
shopping presence beneath), header-band bg image, full-screen Shopping Mode bg,
collapsible Klara section, and the **background-settings live preview**. Still off:
- ✅ Prominent full-width **"Lägg till med röst"** pill button (opens the full-screen
  continuous voice session `ui/voice/VoiceSessionSheet`: live transcription + running added-list)
- ✅ Move an item **between** categories — via the `ItemEditSheet` category-chip picker
  (drag-between-categories still not a gesture; reorder is within-category only)
- 🟡 DropdownMenu surface is the default lavender (theme it WarmWhite)
- 🟡 Final density/spacing polish pass

## Feature checklist (from `.planning/Boet_Project_Specs.md`)

### Core goals
- ✅ Shared grocery shopping between two people
- ✅ Extremely fast list updates (optimistic local writes)
- ✅ Works offline (Room + outbox, replays on reconnect)
- ✅ Intelligent grocery sorting (Swedish KB)
- ✅ Voice-first item entry
- ✅ Self-hosted backend (Docker)
- ✅ Simple onboarding, no accounts

### Identity (V1)
- ✅ "Vem är du? Kalle / Klara", stored locally
- ✅ Drives presence, "added by", purchase history, push target
- 🟡 Audit fields (added/modified by + timestamps) **stored** but not surfaced in UI

### Localization
- ✅ Swedish default, English optional, switch in Settings (Activity-level locale)

### Lists
- ✅ Multiple lists, create custom lists
- ✅ Default grocery list (Matkasse) auto-created
- 🟡 Archive / restore lists ✅; "archived remain **searchable**" ⬜ (no search UI)

### Grocery categories
- ✅ 9 default categories, each with a glanceable icon
- ✅ Add / rename / remove / reorder categories
- ✅ Compact grouped cards; collapse/expand per category (expanded by default)
- ✅ Manual item reorder via per-item drag handle (within a category) — **immediate
  drag** from a 44dp grab target (no long-press wait) with a haptic on grab

### Intelligent sorting
- ✅ Auto-assign items to categories
- ✅ Learning: manual move saved per-household, overrides KB for all devices
- ✅ Backend shared knowledge base
- ✅ **On-device AI** categorization — hybrid `CategoryEngine` (learned mapping → Kotlin
  keyword KB → on-device LLM → Övrigt). LLM = **Gemini Nano via ML Kit GenAI Prompt API**
  (`se.jabba.boet.ai.MlKitClassifier`), runs only for items the KB can't place. Learned
  mappings sync to the device via bootstrap (Room `learned_categories`); adds categorize
  instantly/offline then upgrade via the LLM in the background; **Sortera** re-sorts on-device.
  Verified on the OnePlus 15 (logcat `BoetLLM status=AVAILABLE`; "saffran" → Torrvaror).
- ✅ Manual category change: chip picker in `ItemEditSheet` → `moveItem` → PATCH → server
  `learnCategory` (auto-moves pass `autosort:true` to skip learning) → syncs household-wide.

### Custom sorting rules (non-grocery lists)
- 🟡 Natural-language prompt → generated categories at list creation (deterministic)
- ⬜ AI-generated per-item categorization rules saved for the list

### Store-layout learning
- ✅ Default Swedish layout; users can reorder categories (persists per list)
- ⬜ Detect repeated reorders and **suggest** updating the store layout

### Shopping Mode
- ✅ Dark theme, **compact** type (~20% smaller), large targets, keep-screen-awake, full-screen bg image
- ✅ **Pocket detection** — proximity wake lock turns the screen off when covered (like a phone call)
- ✅ Purposeful tap feedback (emil): whole-row press-dip + haptic; animated check pop
- ✅ Checked items stay **in place** (struck-through + dimmed); "Dölj klara" hides them
  into the separate Klara list, and the toggle is **remembered** (Prefs)
- ✅ Quick check, collapsed empty categories, remaining counter
- ⬜ Fast **jump navigation** between categories

### Completed / "Klara" items
- ✅ Checked items move to a collapsible Klara section (bottom of list)
- ✅ Shopping Mode shows the 10 most recent; auto-prune beyond 50 completed

### Offline & realtime
- ✅ Add/edit/remove/check offline; auto-sync on reconnect; sync status chip
- ✅ Real-time sync <1s over WebSocket (verified two-way)

### Push notifications
- 🟡 FCM wired; server now sends on **item-add**, **item-checked** (PATCH), and
  **shopping-for-60s** (hub presence timer, once/hour per shopper). Delivery still
  needs a real `google-services.json` + FCM service account (`FCM_SERVICE_ACCOUNT`).
- ✅ In-app "User is shopping" presence line under the list title (banner)
- ⬜ Only notify when the other user is **inactive**
- 🟡 Configurable: Settings toggle exists but does not yet gate server sends

### Items
- ✅ Name, ✅ quantity, ✅ notes
- ✅ Quantity supports **units** (count *or* measure): freeform `quantity` string
  ("2", "1 kg", "10 g"), shared `ai/Quantity.kt` helper; voice captures qty+unit;
  edit sheet has a unit-chip row; badge shows `×N` for counts, verbatim for measures.

### Voice input
- ✅ Quick voice add ("Lägg till mjölk, ägg och bananer")
- ✅ Continuous voice mode (long-press mic)
- ✅ Swedish + English, prefers on-device, no always-listening

### Background images
- ✅ Per-list shared image (upload), blur, dark overlay
- ✅ Placement: header band on the main list (top only), full-screen in Shopping Mode

### Presence
- ✅ Who's active, "handlar"/"tittar", near real-time, only while app open

### Audit history
- 🟡 Fields stored; ⬜ no per-item history/"added by" surfaced in UI

### Shopping completion detection
- 🟡 Suggests when all done (spec says "most"); options Remove ✅ / Keep ✅ / **Archive** ⬜
- ✅ Never auto-removes

### Favorites
- ✅ Mark item as favorite
- ⬜ Quick-add favorites UI / `GET /api/favorites` not surfaced

### Purchase history
- 🟡 Backend `GET /api/history` + repo call exist; ⬜ no UI to view / re-add frequent items

### Recipe → grocery list
- ⏸ **Deferred to V2** — removed from the UI (didn't work as intended). The
  RecipeScreen + `/api/recipe/parse` code still exist, unused. Its slot now holds
  the **Auto-sortera** button (→ `/autosort`, placeholder for the future local LLM).

### Interactions (added during review)
- ✅ Hamburger drawer with lists + settings cog (no edge-swipe to open)
- ✅ Swipe-left-to-delete (position-based, animated) — now **wired on the main list**
  rows; deliberate drag past halfway commits, a flick springs back
- ✅ Shopping Mode entry moved to a pill in the **top banner** (under the sync chip);
  the bottom bar is now just the **voice pill** (with a tap ripple, emil) + add field.
  The standalone **Sortera** button was removed (sorting is automatic on add)
- ✅ Add bar lifts above the keyboard (imePadding)
- ✅ Self-healing sync: flush-then-pull + reconcile; server returns 404 (not 500)
  for items on a missing list so the outbox drains

### Infra / delivery
- ✅ Dockerized backend on :3020
- ✅ GHCR publish workflow → `ghcr.io/karlmit/boet:latest` (Unraid update detection)
- ✅ Unraid compose example (`/mnt/user/appdata/Boet`)
- ✅ App builds & runs on a real device (verified)
- ✅ **In-app self-update** (sideload-friendly): `update/UpdateChecker` reads the
  repo's GitHub `releases/latest`, compares versions, downloads the `.apk` asset
  and launches the system installer (REQUEST_INSTALL_PACKAGES + FileProvider).
  Prompt on launch + manual check in Settings → About. Release tags are
  `app-v<version>` (not `v*`, to avoid triggering the server image workflow);
  attach the APK as `boet-<version>.apk`.

## Suggested next priorities

1. Design-fidelity pass on the list screen (drag handles, voice pill, header band).
2. Purchase-history + favorites quick-add UI (data already exists server-side).
3. Surface audit info ("Tillagd av Kalle") on items.
4. Gate push by recipient activity + honor the notifications toggle.
5. On-device categorization model (currently KB-only).
