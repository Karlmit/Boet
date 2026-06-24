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

Self-hosted on Unraid, reverse-proxied to **https://boet.jabba.se**.

```bash
docker compose up -d --build      # builds & runs Postgres + server on :3020
curl http://localhost:3020/health
```

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

The default server is `https://boet.jabba.se` (changeable in Settings).

See [`android/README.md`](android/README.md) to build. In short:

```bash
cd android
./gradlew assembleDebug        # requires the Android SDK + JDK 17
```

## V1 scope

Two people, one household, no accounts. On first launch the app asks
*"Vem är du? Kalle / Klara"* and stores the choice locally. Identity drives
presence, "added by", and audit history.
