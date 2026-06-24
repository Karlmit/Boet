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
      # Optional — enable push notifications by mounting a Firebase service
      # account and pointing here; leave unset to run WebSocket-only.
      # FCM_SERVICE_ACCOUNT: /secrets/fcm.json
    volumes:
      - /mnt/user/appdata/Boet/uploads:/data/uploads
      # - /mnt/user/appdata/Boet/fcm.json:/secrets/fcm.json:ro
    ports:
      - "3020:3020"
```

```bash
docker compose pull && docker compose up -d
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
- **Recipe → list** and **natural-language sort rules**, parsed deterministically
  (no cloud-AI dependency).
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
- 🟡 Archived lists searchable ⬜ · per-row drag handle (≡) ⬜

**Intelligence**
- ✅ Auto-categorization (Swedish supermarket KB)
- ✅ Learning: manual moves teach the whole household (shared KB)
- ✅ Recipe → list with approval flow (accept all / per-item)
- 🟡 Natural-language sort rules for custom lists (deterministic; no AI yet)
- ⬜ On-device AI categorization · ⬜ store-layout "suggest update" detection

**Voice**
- ✅ Quick voice add · ✅ continuous voice mode · ✅ sv/en, on-device-preferred

**Shopping Mode**
- ✅ Dark, oversized type, large targets, keep-awake, "Dölj klara", remaining count
- 🟡 Completion suggestion (Remove / Keep ✅, Archive ⬜) · ⬜ category jump-nav

**Personalization**
- ✅ Per-list background image + blur + dark overlay

**Push & history**
- 🟡 FCM push wired (needs Firebase config) · ⬜ only-when-inactive · ⬜ toggle gating
- 🟡 Purchase history + favorites: backend ready · ⬜ no quick-add UI yet
- 🟡 Audit fields (added/modified by, timestamps) stored · ⬜ not shown in UI

**Delivery**
- ✅ GHCR image `ghcr.io/karlmit/boet:latest` (Unraid update detection)
- ✅ Builds & runs on a real Android device (verified)

**Closest gaps to the design mockup:** per-row drag handles, a prominent
"Lägg till med röst" pill, list header band/imagery, and a density/polish pass.
