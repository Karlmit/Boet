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
  - **Deploy through GitHub, not through an Opus connector or ADB.** Bump `versionCode` and `versionName`, commit, and push to `main`. The Android release workflow publishes the signed APK, and the phones install it through the app's auto-updater.
  - Use a device connector only when the user explicitly requests on-device debugging or verification; it is not part of the normal deployment path.
- Default app server is `https://boet.jabba.se`; for on-device testing against this workspace, temporarily set `Prefs.DEFAULT_SERVER` to the LAN IP (revert before commit).

### Releasing the app (this is what powers in-app auto-update)

The app is sideloaded (no Play Store) and self-updates via `update/UpdateChecker`,
which reads `https://api.github.com/repos/Karlmit/Boet/releases/latest`, compares
the release version to the installed one, downloads the attached `.apk` (into
`cacheDir/updates/`, served to the installer via the `…fileprovider`), and launches
the system installer. **For that to keep working, every release MUST follow this
convention** — break it and existing installs stop seeing updates.

**Normal path — automated via CI.** `.github/workflows/android-release.yml` builds
the signed APK and publishes the release for you. To ship a new version:
1. Bump **both** `versionCode` (monotonic int) and `versionName` (dotted, e.g.
   `1.3`) in `android/app/build.gradle.kts`. The updater compares `versionName`
   numerically (`versionFromTag` extracts `\d+(\.\d+)+` from the tag).
2. Commit + push to `main`. The push (it touches `build.gradle.kts`) triggers the
   workflow, which reads the version, signs with the keystore from repo secrets,
   tags **`app-v<version>`**, and uploads **`boet-<version>.apk`**. A release is
   created only if one for that version doesn't already exist (re-runs just
   rebuild + verify). `workflow_dispatch` runs it manually.
   - CI secrets (set once via `gh secret set`): `KEYSTORE_BASE64` (= `base64 -w0
     android/boet-release.jks`), `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.
     If you ever rotate the keystore, update these too.
   - Tags are `app-v*`, **not** `v*`, so they don't trigger `docker-publish.yml`
     (the server image workflow).

**Manual fallback** (e.g. CI down), from this workspace which has the keystore:
```bash
cd android && export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ANDROID_HOME=/root/android-sdk
./gradlew assembleDebug --no-daemon
cp app/build/outputs/apk/debug/app-debug.apk /tmp/boet-<version>.apk
gh release create app-v<version> /tmp/boet-<version>.apk -R Karlmit/Boet \
  --title "Boet <version>" --notes "…"
```
The updater picks the first `*.apk` asset on the latest release, so the asset name
is flexible, but keep `boet-<version>.apk` for consistency.
4. The first install on a new phone is always manual (the updater ships *inside*
   the app); subsequent updates are in-app.

**Signing (critical for auto-update continuity).** Since `app-v1.2` the build is
signed with a **stable release keystore**, not the per-machine debug key. The
`debug` build type's `signingConfig` is pointed at the release key, so the shipped
`assembleDebug` APK is release-signed **and** still `debuggable` (full `adb`/
debugger access preserved). Android treats a different key as a different app, so
the key must never change or updates fail with a signature mismatch.
- Keystore: `android/boet-release.jks`; credentials: `android/keystore.properties`
  (alias `boet`). **Both are git-ignored** (`*.jks`, `keystore.properties`) and
  exist **only in this workspace** — back them up off-site. Losing them means you
  can never ship a compatible update again (forced uninstall+reinstall for everyone).
- Release cert SHA-256 `ED:D5:31:1F:9C:03:0B:9F:4B:96:FE:F5:81:6B:48:59:73:EF:A2:DB:B8:81:6C:86:FC:92:BC:6D:0C:98:1A:1A`.
- A fresh clone without `keystore.properties` falls back to debug signing
  (`build.gradle.kts` guards on the file's presence) — fine for local dev, but
  **release builds must be done in this workspace** (or wherever the keystore is).
- The `1.1 → 1.2` bump was the one-time key switch: it required uninstall+reinstall
  on both phones (data is server-synced, so only a re-onboarding). Future updates
  are seamless again.

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
- ✅ Favorites are a **standalone, server-synced catalogue** (`favorites` table,
  household-scoped) — **decoupled from list items**. Deleting an item from a list
  never removes the favorite. The favorite id is the normalized name (lower/trim)
  so adds are idempotent across devices; `category_name` is stored (not id) since
  favorites are household-wide while categories are per-list.
- ✅ Star/unstar an item adds/removes the favorite by name (`POST/DELETE
  /api/favorites`); a list item shows the star when a favorite with its name exists.
- ✅ Real-time sync: create/delete broadcast over WebSocket (`entity:"favorite"`)
  and included in `/api/bootstrap`; Android mirrors them in Room (`FavoriteEntity`),
  so the quick-add sheet stays live. One-time backfill migrates legacy starred items.
- ✅ Quick-add favorites sheet (grouped by category), opens from the empty + on the add bar.

### Purchase history
- 🟡 Backend `GET /api/history` + repo call exist; ⬜ no UI to view / re-add frequent items

### Recipes (V2) — recipe book + AI import
- ✅ **Recipe book** reached from the drawer (shopping stays home). Recipes are JSON
  documents in a `recipes` table (`/api/recipes` CRUD + `/api/recipes/parse`), synced
  offline-first like everything else (Room mirror, bootstrap + WebSocket `entity:"recipe"`).
- ✅ **Create**: manual editor · AI from pasted text · AI from a photo (on-device ML Kit OCR → text).
- ✅ **AI pipeline** (`recipe-ai.js`): local LLM (`qwen3:4b`) extracts STRUCTURE in the
  original language → **units converted in code** (`recipe-units.js`: cups→dl, tbsp→msk,
  oz/lb→g — never the LLM, which relabels without doing the math) → **opus-mt EN→SV**
  translation via the `translate` sidecar (`server/translate/`, `TRANSLATE_URL`). Degrades
  to no-translation if the sidecar is unset.
- ✅ Mealie-style detail (image/description/ingredients/steps), **inline ingredient amounts
  in steps** that **scale with servings**, add-to-Matkasse per ingredient (reuses
  `CategoryEngine`), per-step **timers**, keep-screen-awake, optional categories.
- ✅ **Discover** (TheMealDB browse/search/import): `server/src/mealdb.js` (v2 paid-API
  client, `MEALDB_API_KEY` env, defaults to public test key `'1'`) + `routes/discover.js`
  — random/random-10/search-by-name-or-letter/filter-by-ingredient(s)/category/area,
  category+area+ingredient reference lists (24h in-memory cache). `POST
  /api/discover/import {mealId}` mirrors `/recipes/parse-async` (instant placeholder →
  202 + WS broadcast → background AI structuring → live status), deduped per household
  via a new `recipes.source_key` column (`mealdb:<id>`, partial unique index) so
  re-tapping "add" is instant and never duplicates — retries a prior `error` in place.
  `recipe-ai.js`'s structured pipeline was refactored into exported `structureFromEx()`
  so pasted-JSON import and MealDB import share one implementation (MealDB forces
  `forceLang:'en'` and supplies its own no-AI raw fallback). Android: `ui/discover/`
  (DiscoverScreen: featured random + reshufflable 10-grid + text/multi-ingredient
  search + category/area chips; MealDetailScreen: read-only meal view + import
  button), entry point is the drawer (below "Recept", not a RecipesScreen icon —
  moved there after first shipping for easier discovery). Verified end-to-end
  against the real paid key (10/10 random-selection, 11-result multi-ingredient
  filter, race-safe dedup) and the degraded (no-LLM) fallback path; server changes
  deployed the same way as the rest of the backend (compose pull/up).
  - FIXED (same day): "Dagens slump" was re-randomizing on every screen load
    instead of being an actual once-a-day pick — now persisted via `Prefs.dailyMeal()`
    (DataStore), stable across visits/restarts until the date rolls over or the
    user taps its own shuffle icon. The random-10 grid was silently re-fetching
    whenever you opened a meal and pressed back (Navigation-Compose discards a
    screen's plain `remember` state on navigate-away) — moved to a process-scoped
    `DiscoverBrowseState` cache so only an explicit shuffle changes it. Added
    `RecipeDoc.youtubeUrl` (server sets it from MealDB's `strYoutube` on import,
    survives manual edits) rendered via a shared `YoutubeLinkRow` composable used
    on both the MealDB preview and the real recipe detail screen. Recipe
    translation now prefers a cloud LLM (explicit food-recipe context in the
    prompt, incl. specific corrections like "lard"→"ister" and "return the
    English word unchanged if unsure") over the local opus-mt sidecar — opus-mt
    was mistranslating ordinary recipe vocabulary; falls back safely (never
    misaligns an ingredient's translation) if the cloud reply's line count
    doesn't match.
  - FOLLOW-UP (same day): translation now has its OWN independent LLM config
    (`TRANSLATE_LLM_API_KEY`/`_BASE_URL`/`_MODEL` in `translate.js`, refactored
    the NVIDIA request/response/reasoning-model handling out of `recipe-llm.js`
    into an exported generic `nvidiaChat()` both configs call) — separate from
    `NVIDIA_MODEL` used for recipe structuring, since a plain instruct model
    (defaults to `meta/llama-3.3-70b-instruct`) translates recipe vocabulary
    better than the reasoning model (nemotron) used for structuring. Falls back
    to reusing `NVIDIA_API_KEY`/`NVIDIA_BASE_URL` (same account) if
    `TRANSLATE_LLM_API_KEY` is unset, but still with its own model default.
    Verified via a mock NIM endpoint: fallback-to-NVIDIA-key path, fully
    independent endpoint path (translation succeeds even with the structuring
    endpoint unreachable), and no regression to recipe-structuring calls.
  - FOLLOW-UP (same day): `RecipesScreen` and `DiscoverScreen` now host their own
    drawer instance (`ListsDrawer`, made public — was private to `ListScreen.kt`)
    with a hamburger in the top bar instead of a back arrow, matching the
    shopping-list home screen, so switching between lists/recipes/discover never
    requires backing out first (system back still works; these are drawer-level
    destinations, not a back-arrow chain). Removed the per-card trash-can delete
    button from the recipe grid — deleting a recipe is now only reachable via the
    editor's existing confirm-delete flow, to cut down on accidental taps.
- ✅ **URL scrape**: `POST /api/recipes/scrape-async {url}` (`routes/scrape.js`),
  same instant-placeholder/202/background/WebSocket-broadcast shape as
  `/recipes/parse-async` and `/discover/import`, deduped via `recipes.source_key`
  (`url:<normalized>`). Two-tier fetch cascade in `server/src/scrape.js`:
  (1) static `fetch()` + `cheerio` — extract schema.org/Recipe JSON-LD (handles
  `@graph`, bare top-level arrays of typed objects, CDATA-wrapped blocks) and
  feed it through `recipe-ai.js`'s own (now-exported) `extractRecipeJson()` so
  the existing `structureFromEx()` pipeline does the actual AI structuring —
  no JSON-LD Recipe found? fall back to readable body text through the
  existing plain-text `parseRecipeText()` path; (2) if neither tier-1 path
  found anything usable, launch a headless Chromium (**Playwright** — the
  server's Docker base image is now `mcr.microsoft.com/playwright:v1.61.1-jammy`
  instead of `node:20-alpine`, ~1.5-2GB vs ~150MB, only affects disk/pull time
  since the browser launches lazily per-scrape, not resident) and re-run the
  same extraction against the rendered DOM — handles WP-Recipe-Maker-style
  lazy-loaded recipe cards and fully client-rendered SPA pages. Also pulls
  `image` (string/array/ImageObject shapes) and `totalTime` (ISO-8601 duration
  → "1 h 30 min") straight off the JSON-LD when present. Every outbound
  request (both tiers, every redirect hop) is validated by `ssrf-guard.js`
  (scheme + DNS-resolved private/loopback/link-local IP block) since this
  endpoint fetches arbitrary user-supplied URLs server-side; validated
  synchronously before the placeholder row is even created, so an obviously-
  blocked host gets an immediate 400. Android: third FAB entry "Importera
  från länk" → `ui/recipes/RecipeUrlScreen.kt` (single URL field, mirrors
  `RecipeAiScreen`'s structure) → `Repository.startUrlScrape()` →
  `ApiClient.scrapeRecipe()`, same `onParsed` navigation contract as the
  AI-paste and MealDB-import flows. Verified end-to-end against the 10 real
  URLs in `server/src/test-urls.txt`: 6 clean-JSON-LD sites, 1 JSON-LD-wrong-
  type-but-full-body-text site (landleyskok.se, tier-1 text fallback), 2
  sites needing the headless tier (zeinaskitchen.se's WPRM lazy-load — though
  it turned out its ingredient list is actually present as plain body text,
  so tier 1 alone handles it in practice; coop.se's fully client-rendered SPA
  genuinely needs headless) — dedup (re-POST → instant 200, same id),
  retry-in-place on a prior `error`, and the SSRF guard (172.x/localhost/bad
  scheme → 400) all confirmed against the real endpoint via `docker compose
  up`. The AI-structuring step itself showed `degraded`/`error` in this
  session's local test stack only because that stack's `ollama` container has
  a pre-existing, unrelated bug (`OLLAMA_KEEP_ALIVE=-1` sent without a unit
  suffix → ollama 400s on every call) — not something this feature introduced;
  worth fixing separately since it silently degrades the whole recipe-AI
  pipeline, not just URL scrape.
- ✅ **Selected recipe + kitchen display API**: a household can mark one recipe
  "selected" — pin icon (`Icons.Default.PushPin`) in `RecipeDetailScreen.kt`'s
  top bar, left of the keep-awake bulb, same toggle/highlight pattern. Backed by
  `recipes.selected` (schema.js, partial unique index enforces one-per-household
  at the DB level) and `POST /api/recipes/:id/select {selected}` (clears any
  prior selection in the same transaction, broadcasts both changed rows over
  WS). Built for an external ESP32-S3 + E-ink kitchen display: new unauthenticated
  read-only `server/src/routes/display.js` — `GET /api/display/shopping-list`
  (unchecked items, grouped by category) and `GET /api/display/recipe` (the
  selected recipe flattened to plain name/servings/time/image/ingredients/steps).
  Documented for the ESP32 side in `BOET-API.md`. Room bumped v5→v6 (`selected`
  column on `RecipeEntity`, `fallbackToDestructiveMigration` already in place).
  Verified via `docker compose up db server` + curl (shopping-list live-updates,
  select/deselect/re-select exclusivity, `display/recipe` null when none
  selected) and a clean `./gradlew assembleDebug`. **Not yet committed or
  device-tested** — no physical Android device or ESP32 in this LXC.
- ✅ **Voice-add from audio (kitchen display)**: `POST /api/voice/add-from-audio`
  (`server/src/routes/voice.js`) — the kitchen tablet is too slow for on-device
  speech recognition or the phone app's usual record/review/confirm flow, so it
  just uploads a raw audio clip (`{audioBase64, contentType?}`). The server
  transcribes it locally via a new `faster-whisper-server` sidecar
  (`server/src/whisper.js`, `WHISPER_URL`/`WHISPER_MODEL` env, new
  `docker-compose.yml`/`.prod.yml` service — CPU int8, `large-v3-turbo`
  transcribes Swedish well per the user's own local testing), runs the
  transcript through the **existing** `cleanVoice()` pipeline (`voice.js`,
  already used by `/api/voice/clean` for the phone apps), and — unlike the phone
  flow — **adds every resulting item straight to the default grocery list**, no
  approval step. Refactored `items.js`'s bulk-insert body (category resolution,
  purchase-history tracking, hub broadcast, push notify) into an exported
  `createItems(listId, items, addedBy)` so both the normal add-item route and
  this new flow share it. `addedBy` is the literal string `"Köksskärmen"` (not a
  real household member), so the existing "notify others" push logic notifies
  **both** phones rather than skipping one as if a member added it themselves.
  Gotcha found + fixed during verification: `WHISPER__MODEL=large-v3-turbo` (as
  a bare string) 500s — that shorthand isn't one of faster-whisper's built-in
  model names (only large-v1/v2/v3); fixed to the full HF repo id
  `deepdml/faster-whisper-large-v3-turbo-ct2` in both compose files and
  `whisper.js`'s default. Documented in `BOET-API.md`. Verified end-to-end via a
  full local `docker compose up db server faster-whisper` (+ `ollama` briefly,
  to confirm the Ollama-backed cleaning path is reached too — that specific
  local test hit the already-known, pre-existing, unrelated `OLLAMA_KEEP_ALIVE=-1`
  bug and fell back to the deterministic cleaner, which is itself the correct
  degrade-gracefully behavior): a synthesized Swedish `espeak-ng` clip
  transcribed correctly-ish (robotic TTS input, not real speech) and its items
  landed in the shopping list with categories resolved and a push fan-out to
  both members. **Not yet committed, not tested against real human speech or a
  real ESP32/tablet.**
- **NOT yet device-tested**: AI parse against the real household ollama, on-device
  OCR, and the new Discover screens (compiles/builds clean; no on-device run in
  this LXC — see repo CLAUDE.md's Android section). URL scrape is likewise not
  yet device-tested (verified via `docker compose` + curl + the Android build
  compiling clean, not an on-device tap-through).

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
