# Boet 🪹

**Dela. Planera. Få tid tillsammans.** — a calm, self-hosted shared grocery &
household list app for a two-person home (Kalle & Klara).

Boet ("the nest") makes grocery shopping effortless through real-time sync (<1s),
offline-first editing, intelligent Swedish grocery sorting, voice-first item
entry, on-device categorization, and an **AI recipe book** that turns any pasted
recipe — a photo of one, or just a link to one — into structured, Swedish,
ready-to-cook instructions, then drops the ingredients straight onto the shopping
list. Swedish-first (English
optional), running against the household's own Unraid server — no third-party
cloud, no accounts.

## Repository layout

```
Boet/
├── server/              Node.js backend (REST + WebSocket + Postgres)
│   └── translate/       EN→SV recipe translation sidecar (opus-mt)
├── android/             Kotlin / Jetpack Compose app
├── docker-compose.yml   Postgres + server + ollama + translate + faster-whisper, exposes :3020
└── README.md
```

## Backend

Self-hosted on Unraid, listening on **:3020**.

### Local / dev (build from source)

```bash
docker compose up -d --build      # builds & runs Postgres + server on :3020
curl http://localhost:3020/health
```

### Unraid / production (pull prebuilt image from GHCR)

The server image is published to **`ghcr.io/karlmit/boet:latest`** by a GitHub
Action on every push to `main` (and on `v*` tags), so Unraid's "check for
updates" detects new versions.

Drop this `docker-compose.yml` into Unraid (app data lives under
`/mnt/user/appdata/Boet`):

```yaml
services:
  db:
    image: postgres:16-alpine
    restart: unless-stopped
    environment:
      POSTGRES_USER: boet
      POSTGRES_PASSWORD: boet            # change me
      POSTGRES_DB: boet
    volumes:
      - /mnt/user/appdata/Boet/db:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U boet"]
      interval: 5s
      timeout: 3s
      retries: 10

  server:
    image: ghcr.io/karlmit/boet:latest
    restart: unless-stopped
    depends_on:
      db:
        condition: service_healthy
    environment:
      PORT: 3020
      PGHOST: db
      PGPORT: 5432
      PGUSER: boet
      PGPASSWORD: boet                   # match the db password above
      PGDATABASE: boet
      UPLOAD_DIR: /data/uploads
      # Local LLM for voice cleaning — keeps the "no third-party cloud" promise.
      # Points at the ollama service below; unset OLLAMA_URL to disable (the server
      # then falls back to deterministic cleaning).
      OLLAMA_URL: http://ollama:11434
      OLLAMA_MODEL: qwen3:4b-instruct
      # Keep the model resident so a long gap between shops doesn't cold-start the
      # next voice add (a cold load can outrun the request timeout and silently
      # fall back to deterministic cleaning). "-1" = always warm (~2.5–3 GB RAM
      # held); "5m" = unload after 5 min idle to free RAM at the cost of a slow
      # first request afterwards.
      OLLAMA_KEEP_ALIVE: "-1"
      # EN->SV translation sidecar for AI recipe import (opus-mt). Unset to disable
      # (imported recipes then stay in their original language).
      TRANSLATE_URL: http://translate:7000
      # OPT-IN: parse recipes on NVIDIA's free cloud GPUs (seconds, not a minute of
      # local CPU). Get a key at https://build.nvidia.com. Recipe parsing only —
      # voice cleaning always stays local. Leave unset to stay fully local.
      # NVIDIA_API_KEY: nvapi-xxxxxxxx
      # NVIDIA_MODEL: nvidia/nemotron-3-ultra-550b-a55b
      # OPT-IN: point recipe STRUCTURING (the "first AI" tried, before falling
      # back to local ollama) at a DIFFERENT cloud provider than NVIDIA_* above
      # — any OpenAI-compatible endpoint works, e.g. OpenAI itself. Unset =
      # falls back to NVIDIA_API_KEY/NVIDIA_MODEL above.
      # STRUCTURE_LLM_API_KEY: sk-xxxxxxxx
      # STRUCTURE_LLM_BASE_URL: https://api.openai.com/v1
      # STRUCTURE_LLM_MODEL: gpt-5.5
      # OPT-IN: a SEPARATE cloud model just for EN->SV translation (independent
      # of STRUCTURE_LLM_*/NVIDIA_MODEL above, which structures the recipe) — a
      # plain instruct model translates recipe vocabulary better than a
      # reasoning model. Unset TRANSLATE_LLM_API_KEY to reuse the NVIDIA key/
      # endpoint above with a different model by default; unset both to fall
      # back to opus-mt.
      # TRANSLATE_LLM_API_KEY: nvapi-xxxxxxxx
      # TRANSLATE_LLM_BASE_URL: https://integrate.api.nvidia.com/v1
      # TRANSLATE_LLM_MODEL: meta/llama-3.3-70b-instruct
      # Discover: browse/search/import recipes from TheMealDB. Unset = public
      # test key '1' (rate-limited, single-ingredient filter only); a paid key
      # (themealdb.com/api.php) unlocks the full catalogue + multi-ingredient search.
      # MEALDB_API_KEY: your-paid-key
      # Optional — enable push notifications by mounting a Firebase service
      # account and pointing here; leave unset to run WebSocket-only.
      # FCM_SERVICE_ACCOUNT: /secrets/fcm.json
      # Local speech-to-text for the kitchen display's voice-add flow (record a
      # clip on the tablet, transcribe here, auto-add via the existing voice-
      # cleaning pipeline). Unset WHISPER_URL to disable (the endpoint then
      # returns 503 instead of silently doing nothing).
      WHISPER_URL: http://faster-whisper:8000
      WHISPER_MODEL: deepdml/faster-whisper-large-v3-turbo-ct2
    volumes:
      - /mnt/user/appdata/Boet/uploads:/data/uploads
      # - /mnt/user/appdata/Boet/fcm.json:/secrets/fcm.json:ro
    ports:
      - "3020:3020"

  # EN->SV recipe translation sidecar (Helsinki-NLP/opus-mt-en-sv). The model
  # (~300 MB) is baked into the image at build time, so it needs no network at
  # runtime and holds well under 1 GB RAM. No prebuilt image is published, so it
  # builds from the repo on first `up` (a few minutes).
  translate:
    build: https://github.com/Karlmit/Boet.git#main:server/translate
    restart: unless-stopped

  # Household-local LLM (Ollama) that cleans voice input into tidy items, so phones
  # without an on-device model still get good results. Stays on the LAN — nothing
  # leaves home. If the Unraid box has an NVIDIA GPU + container toolkit, add a
  # `deploy.resources.reservations.devices: [{capabilities: [gpu]}]` block for much
  # faster inference.
  ollama:
    image: ollama/ollama:latest
    restart: unless-stopped
    volumes:
      - /mnt/user/appdata/Boet/ollama:/root/.ollama

  # One-shot: waits for Ollama, then pulls the voice model on every `up` and exits.
  # `ollama pull` is idempotent (a no-op once the model is present), so this both
  # provisions a fresh box AND self-heals — if a stack recreate or volume wipe ever
  # drops the model, the next `up` re-pulls it instead of voice silently degrading
  # to the deterministic fallback. Shows as "exited (0)" in the Docker tab when done.
  ollama-pull:
    image: ollama/ollama:latest
    depends_on:
      - ollama
    environment:
      OLLAMA_HOST: http://ollama:11434   # pull runs as a client into the ollama server's volume
    entrypoint: ["/bin/sh", "-c"]
    command: "until ollama list >/dev/null 2>&1; do sleep 1; done; ollama pull qwen3:4b-instruct"
    restart: "no"

  # Local speech-to-text sidecar (faster-whisper-server, an OpenAI Whisper-API
  # compatible server) for the kitchen display's voice-add flow — see
  # BOET-API.md. CPU-only int8 for reasonable latency. WHISPER__MODEL must be
  # a full "repo/name" HF model id — the bare "large-v3-turbo" isn't always a
  # recognized short name (depends on the faster-whisper version this image
  # happens to be built against), so pin the actual repo to avoid surprises.
  faster-whisper:
    image: fedirz/faster-whisper-server:latest-cpu
    restart: unless-stopped
    volumes:
      - /mnt/user/appdata/Boet/whisper:/root/.cache/huggingface
    environment:
      WHISPER__MODEL: deepdml/faster-whisper-large-v3-turbo-ct2
      WHISPER__DEVICE: cpu
      WHISPER__COMPUTE_TYPE: int8
```

```bash
docker compose pull && docker compose up -d
# The ollama-pull sidecar downloads the voice model (~2.5 GB) on first start —
# give it a few minutes. Follow along with:
docker compose logs -f ollama-pull
```

Put a reverse proxy in front if you want HTTPS/remote access; enable
**Websockets Support** on the proxy host (required for real-time sync and
presence). In the Android app, set the server address in **Settings** to wherever
the backend is reachable.

### Push notifications (optional)

Push uses Firebase Cloud Messaging and is entirely optional — without it the app
still syncs in real time over WebSocket while open. To enable background push:

1. Create a Firebase project, add an Android app with package `se.jabba.boet`,
   and download its `google-services.json` → replace `android/app/google-services.json`.
2. Generate a service-account key (Project settings → Service accounts) and mount
   it into the server, then set `FCM_SERVICE_ACCOUNT=/secrets/fcm.json`
   (see the commented volume in `docker-compose.prod.yml`).

See [`server/README.md`](server/README.md) for the full API. Highlights:

- **REST + WebSocket** real-time sync with presence ("Kalle handlar").
- **Swedish grocery categorization** knowledge base (ICA/Coop/Hemköp/Willys).
- **Learning**: moving an item teaches the household; all devices benefit.
- **AI recipe import** (`POST /api/recipes/parse`) turns free recipe text — or a
  pasted Mealie / schema.org recipe JSON — into a structured recipe. A multi-step
  pipeline that plays to each tool's strength: the LLM extracts structure
  (ingredients, steps, step↔ingredient links, timers) in the original language,
  **units are converted in code** (cups→dl, tbsp→msk, oz→g — never trusted to the
  LLM, which relabels without doing the math), and the text is translated EN→SV.
  For a pasted recipe JSON the step texts are kept as-is and the model only
  *tags* them (which ingredients + timers), so it stays fast even on a CPU box.
  Recipes are stored as JSON documents (`/api/recipes` CRUD) and synced like
  everything else.
  - **LLM backend** is the local ollama by default (nothing leaves home). Optionally,
    set a free **NVIDIA NIM** key (`NVIDIA_API_KEY`, from https://build.nvidia.com) to
    run *recipe parsing only* on datacenter GPUs — seconds instead of a minute of local
    CPU inference. Voice cleaning always stays local. This is the one place Boet reaches
    a third-party cloud, and only when you opt in with a key. Structuring can be pointed
    at a **different** cloud provider than NVIDIA_* via `STRUCTURE_LLM_API_KEY`/
    `STRUCTURE_LLM_BASE_URL`/`STRUCTURE_LLM_MODEL` (e.g. OpenAI) — falls back to
    `NVIDIA_API_KEY`/`NVIDIA_BASE_URL`/`NVIDIA_MODEL` if unset.
  - **Translation** prefers a cloud LLM (`TRANSLATE_LLM_MODEL`, defaults to
    `meta/llama-3.3-70b-instruct` — falls back to the `NVIDIA_API_KEY`/
    `NVIDIA_BASE_URL` above if `TRANSLATE_LLM_API_KEY` is unset, but with its own
    model, since a general-purpose instruct model translates recipe vocabulary
    better than a reasoning model tuned for structuring), with an explicit
    food-recipe prompt (e.g. "lard" → "ister", not a literal dictionary
    translation) and a strict same-line-count check so a garbled reply is
    discarded rather than risking a misaligned ingredient. Falls back to the
    **opus-mt sidecar** (`TRANSLATE_URL`) if no cloud key is set, or no-op if
    neither is configured.
- **Discover** (`GET /api/discover/*`, `POST /api/discover/import`) browses and
  searches **TheMealDB** — random pick, a reshufflable ten, text search,
  multi-ingredient search, and category/area browsing — and imports a chosen meal
  through the same AI pipeline as a pasted recipe (structure → unit conversion →
  EN→SV translation). Import is async/dedup'd just like AI paste: an instant
  placeholder appears in the recipe grid, the real content arrives over the
  WebSocket, and re-importing an already-added meal is a no-op rather than a
  duplicate. Uses TheMealDB's free tier by default; set `MEALDB_API_KEY` for the
  paid tier's full catalogue and multi-ingredient filtering.
- **URL scrape** (`POST /api/recipes/scrape-async`) imports a recipe straight from
  a link. Extracts schema.org/`Recipe` JSON-LD when the page has it (most modern
  recipe sites embed this for Google's rich-result cards) and feeds it through the
  same structure/unit-conversion/translation pipeline as a pasted recipe — cheaper
  and more reliable than asking the LLM to parse raw HTML. Falls back to the
  plain-text AI path when a page has no JSON-LD but does have the recipe as
  readable body text, and as a last resort renders the page in a **headless
  Chromium** (Playwright) for sites that only populate the recipe client-side via
  JavaScript. Same instant-placeholder/async/WebSocket shape as AI paste and
  Discover import, deduped per URL. Every fetch is checked against an SSRF guard
  (blocks private/loopback/link-local addresses and validates each redirect hop)
  since this endpoint fetches an arbitrary user-supplied URL server-side.
- **Natural-language sort rules**, parsed deterministically.
- **Voice cleaning** (`POST /api/voice/clean`) turns a raw Swedish transcript into
  tidy items via the household's **own local LLM** (Ollama / `qwen3:4b-instruct`),
  so phones without an on-device model get the same quality — and no third-party
  cloud is involved. Degrades to a deterministic split if the model is offline.
- **Offline-friendly**: every create accepts a client-supplied id and is
  idempotent, so the app's outbox can safely replay queued operations.

## Android app

Kotlin + Jetpack Compose, offline-first (Room + an outbox), OkHttp REST &
WebSocket, on-device `SpeechRecognizer` voice input, the Boet design system
(Manrope + the Cormorant "Boet" wordmark), and the nest logo as the launcher
icon.

The server address is configurable in **Settings**, so point the app at wherever
you host the backend.

See [`android/README.md`](android/README.md) to build. In short:

```bash
cd android
./gradlew assembleDebug        # requires the Android SDK + JDK 17
```

### Install on your phone (sideload)

Boet isn't on the Play Store — you install the APK directly. Grab the latest
`boet-*.apk` from the [**Releases**](https://github.com/Karlmit/Boet/releases)
page, then on first launch pick **Kalle** or **Klara**; both phones sync through
the same backend automatically.

**On a Samsung phone (Galaxy S24, etc.)** there's one extra step — Samsung's
**Auto Blocker** silently blocks sideloaded apps, so turn it off first:

1. **Settings → Security and privacy → Auto Blocker → turn it off.**
   (You can switch it back on afterwards.)
2. Download `boet-latest.apk` from the Releases page (e.g. open the link in
   Chrome, or send the file to yourself via Drive/email).
3. Open the file from **My Files → Downloads** and tap it. When asked, **allow
   installs from this source**.
4. If **Play Protect** warns about an unknown app, tap **More details →
   Install anyway**.

On other Android phones it's the same minus the Auto Blocker step: just allow
"install unknown apps" for whichever app opens the APK.

### Updating

The app updates itself. On launch it checks the latest GitHub Release and, if a
newer version is available, offers to download and install it — no need to
re-sideload manually. You can also trigger a check any time from
**Settings → Om/About → Sök efter uppdatering**. (The very first install still
has to be sideloaded by hand, since the in-app updater ships *inside* the app.)

> **Cutting a release** (maintainer): bump `versionCode`/`versionName` in
> [`android/app/build.gradle.kts`](android/app/build.gradle.kts), build the APK,
> then publish a GitHub Release whose tag contains the version (e.g. `app-v1.1`)
> with the APK attached as `boet-<version>.apk`. The in-app updater reads
> `releases/latest` and picks up the first `.apk` asset. Tags are deliberately
> **not** named `v*` so they don't trigger the server image workflow.

## V1 scope

Two people, one household, no accounts. On first launch the app asks
*"Vem är du? Kalle / Klara"* and stores the choice locally. Identity drives
presence, "added by", and audit history.

## Feature status

Tracking the spec in `.planning/Boet_Project_Specs.md`.
Legend: ✅ done · 🟡 partial · ⬜ not started.

**Foundations**
- ✅ Self-hosted backend (Docker, :3020) · REST + WebSocket + Postgres
- ✅ Onboarding (Kalle / Klara), no accounts, identity stored locally
- ✅ Swedish-first, English optional (switch in Settings)
- ✅ Offline-first (Room + outbox, replays on reconnect) + sync-status chip
- ✅ Real-time sync <1s + presence ("handlar" / "tittar")

**Lists & items**
- ✅ Multiple lists, default **Matkasse** grocery list, archive / restore
- ✅ Items: name, quantity, notes; favorite toggle
- ✅ 9 default categories; add / rename / remove / reorder categories
- ✅ Swipe-left-to-delete (animated); hamburger drawer with settings cog
- ✅ Compact grouped category cards: leading icons, hairline dividers, collapse/expand
- ✅ Per-item drag handle to reorder within a category
- ✅ Completed items move to a collapsible "Klara" section (auto-prune past 50)
- ✅ Add bar lifts above the keyboard
- ✅ Self-healing sync (switching servers / DB reset reconciles cleanly)
- 🟡 Archived lists searchable ⬜ · drag between categories ⬜

**Intelligence**
- ✅ Auto-categorization (Swedish supermarket KB)
- ✅ Learning: manual moves teach the whole household (shared KB)
- ✅ **Auto-sortera** button → re-categorizes via the KB (placeholder for a future local LLM)
- 🟡 Natural-language sort rules for custom lists (deterministic; no AI yet)
- ⬜ On-device AI categorization · ⬜ store-layout "suggest update" detection

**Recipes (V2)**
- ✅ Recipe book reached from the drawer; shopping stays the home screen
- ✅ Create three ways: manual editor · **AI from pasted text** · **AI from a photo** (on-device OCR)
- ✅ AI pipeline: local LLM structures the recipe → **units converted in code** → **opus-mt EN→SV translation**
- ✅ Mealie-style detail view (image, description, ingredients, numbered steps)
- ✅ **Inline ingredient amounts in steps** (AI-linked) that **scale with the serving count**
- ✅ Add ingredients to the **Matkasse** list (one tap, runs through the same categorization)
- ✅ Per-step **timers** (AI-detected or set by hand) · keep-screen-awake while cooking
- ✅ Optional recipe categories · stored as JSON documents, synced offline-first
- ✅ **Discover**: browse/search TheMealDB (random pick, reshufflable ten, text +
  multi-ingredient search, category/area browsing) and import into the recipe book
  through the same AI pipeline; deduped so re-adding a meal never duplicates it
- ✅ **URL scrape**: import from a recipe link — extracts schema.org/Recipe JSON-LD
  where present, falls back to readable-text AI parsing, and to a headless-browser
  render as a last resort for JS-only recipe cards; deduped per URL, SSRF-guarded

**Voice**
- ✅ Quick voice add · ✅ continuous voice mode · ✅ sv/en, on-device-preferred

**Shopping Mode**
- ✅ Dark, oversized type, large targets, keep-awake, "Dölj klara", remaining count
- ✅ Full-screen background image; completed section (10 most recent)
- 🟡 Completion suggestion (Remove / Keep ✅, Archive ⬜) · ⬜ category jump-nav

**Personalization**
- ✅ Per-list background image + blur + dark overlay (with a live preview while adjusting)
- ✅ Placement: header band on the main list, full-screen in Shopping Mode

**Push & history**
- 🟡 FCM push wired (needs Firebase config) · ⬜ only-when-inactive · ⬜ toggle gating
- 🟡 Purchase history + favorites: backend ready · ⬜ no quick-add UI yet
- 🟡 Audit fields (added/modified by, timestamps) stored · ⬜ not shown in UI

**Delivery**
- ✅ GHCR image `ghcr.io/karlmit/boet:latest` (Unraid update detection)
- ✅ Builds & runs on a real Android device (verified)

**Closest gaps to the design mockup:** a prominent full-width "Lägg till med röst"
pill (currently a mic icon), drag-between-categories, and a final density/polish pass.
