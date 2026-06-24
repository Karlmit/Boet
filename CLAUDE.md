# Boet — build notes & feature status

This is the working reference for the Boet repo. **Before deciding what to build
next, consult the feature checklist below** (kept in sync with the README). Update
a box when a feature genuinely lands end-to-end (backend + app + verified).

Legend: ✅ done · 🟡 partial · ⬜ not started

## How things are built / verified

- Backend: Node + Express + ws + Postgres (`server/`), `docker compose up -d --build`, port **3020**. Verified end-to-end.
- Android: Kotlin + Compose (`android/`). **Build/test via the "Windows Workstation" Opus connector** (Android Studio SDK at `%LOCALAPPDATA%\Android\Sdk`, JDK = Android Studio JBR). Pattern: `opus connector put` changed files → `gradlew.bat assembleDebug` → `adb install -r` → drive with `adb shell input` + `screencap`. A physical device is attached.
- Default app server is `https://boet.jabba.se`; for on-device testing against this workspace, temporarily set `Prefs.DEFAULT_SERVER` to the LAN IP (revert before commit).

## Known design-fidelity gaps vs `.planning/Design/`

In place: palette, Manrope, serif wordmark, nest icon, **compact grouped category
cards** (hairline dividers, leading icons, collapse chevrons), per-item **drag
handles** (long-press reorder within a category), the **banner** (raised title +
shopping presence beneath), header-band bg image, full-screen Shopping Mode bg,
collapsible Klara section, and the **background-settings live preview**. Still off:
- ⬜ Prominent full-width **"Lägg till med röst"** pill button (currently a mic icon)
- ⬜ Drag to move an item **between** categories (reorder is within-category only)
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
- ✅ Manual item reorder via per-item drag handle (within a category)

### Intelligent sorting
- ✅ Auto-assign items to categories
- ✅ Learning: manual move saved per-household, overrides KB for all devices
- ✅ Backend shared knowledge base
- ⬜ **On-device AI** categorization (only deterministic KB + server fallback today)

### Custom sorting rules (non-grocery lists)
- 🟡 Natural-language prompt → generated categories at list creation (deterministic)
- ⬜ AI-generated per-item categorization rules saved for the list

### Store-layout learning
- ✅ Default Swedish layout; users can reorder categories (persists per list)
- ⬜ Detect repeated reorders and **suggest** updating the store layout

### Shopping Mode
- ✅ Dark theme, oversized type, large targets, keep-screen-awake, full-screen bg image
- ✅ Quick check, collapsed empty categories, "Dölj klara" toggle, remaining counter
- ✅ Completed section (10 most recent) separate from active items
- ⬜ Fast **jump navigation** between categories

### Completed / "Klara" items
- ✅ Checked items move to a collapsible Klara section (bottom of list)
- ✅ Shopping Mode shows the 10 most recent; auto-prune beyond 50 completed

### Offline & realtime
- ✅ Add/edit/remove/check offline; auto-sync on reconnect; sync status chip
- ✅ Real-time sync <1s over WebSocket (verified two-way)

### Push notifications
- 🟡 FCM wired (server sends on item-add via firebase-admin; needs real `google-services.json` + service account)
- ⬜ Only notify when the other user is **inactive**
- 🟡 Configurable: Settings toggle exists but does not yet gate server sends

### Items
- ✅ Name, ✅ quantity, ✅ notes

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
- ✅ Swipe-left-to-delete (position-based, animated)
- ✅ Add bar lifts above the keyboard (imePadding)
- ✅ Self-healing sync: flush-then-pull + reconcile; server returns 404 (not 500)
  for items on a missing list so the outbox drains

### Infra / delivery
- ✅ Dockerized backend on :3020
- ✅ GHCR publish workflow → `ghcr.io/karlmit/boet:latest` (Unraid update detection)
- ✅ Unraid compose example (`/mnt/user/appdata/Boet`)
- ✅ App builds & runs on a real device (verified)

## Suggested next priorities

1. Design-fidelity pass on the list screen (drag handles, voice pill, header band).
2. Purchase-history + favorites quick-add UI (data already exists server-side).
3. Surface audit info ("Tillagd av Kalle") on items.
4. Gate push by recipient activity + honor the notifications toggle.
5. On-device categorization model (currently KB-only).
