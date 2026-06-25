# Boet 🪹

**Dela. Planera. Få tid tillsammans.** — a calm, self-hosted shared grocery &
household list app for a two-person home (Kalle & Klara).

Boet ("the nest") makes grocery shopping effortless through real-time sync (<1s),
offline-first editing, intelligent Swedish grocery sorting, voice-first item
entry, and on-device categorization. Swedish-first (English optional), running
against the household's own Unraid server — no third-party cloud, no accounts.

## Repository layout

```
Boet/
├── server/              Node.js backend (REST + WebSocket + Postgres)
├── android/             Kotlin / Jetpack Compose app
├── docker-compose.yml   Postgres + server, exposes :3020
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
      # Optional — enable push notifications by mounting a Firebase service
      # account and pointing here; leave unset to run WebSocket-only.
      # FCM_SERVICE_ACCOUNT: /secrets/fcm.json
    volumes:
      - /mnt/user/appdata/Boet/uploads:/data/uploads
      # - /mnt/user/appdata/Boet/fcm.json:/secrets/fcm.json:ro
    ports:
      - "3020:3020"

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
```

```bash
docker compose pull && docker compose up -d
# Pull the voice model once (~2.5 GB, stored in the ollama volume):
docker compose exec ollama ollama pull qwen3:4b-instruct
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
- **Recipe → list** and **natural-language sort rules**, parsed deterministically.
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
- ⏸ Recipe → list deferred to V2 (removed from UI)

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
