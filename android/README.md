# Boet — Android app

Kotlin + Jetpack Compose, offline-first. Connects to the Boet backend over REST
and WebSocket at **https://boet.jabba.se** by default (configurable in Settings).

## Build

Requires **JDK 17** and the **Android SDK** (compileSdk 34). Easiest in Android
Studio (Koala / 2024.1+) — open the `android/` folder and Run. From the CLI:

```bash
# point to your SDK (or set ANDROID_HOME)
echo "sdk.dir=/path/to/Android/Sdk" > local.properties

./gradlew assembleDebug          # build app/build/outputs/apk/debug/app-debug.apk
./gradlew installDebug           # build + install on a connected device
```

> The Gradle wrapper (8.9), the Manrope/Cormorant fonts, and the nest launcher
> icon are committed, so a checkout builds without extra setup.

## Architecture

| Layer | What |
| ----- | ---- |
| `ui/theme` | Boet palette, Manrope type, the Cormorant "Boet" wordmark, Shopping-Mode dark scheme |
| `ui/*` | Compose screens: onboarding, list, shopping mode, lists hub, recipe, settings |
| `ui/voice` | On-device `SpeechRecognizer` wrapper + "Add milk, eggs and bananas" parsing |
| `data/local` | Room (lists / categories / items / **outbox**) + DataStore prefs |
| `data/remote` | OkHttp REST `ApiClient`, `RealtimeClient` (WebSocket), DTOs |
| `data/Repository` | Single source of truth: optimistic local writes → outbox → server; WS applies remote changes |

### Offline-first sync

Every mutation writes to Room immediately (the UI is instant), then enqueues a
server call in the **outbox**. The outbox flushes whenever connectivity allows;
the WebSocket pushes other devices' changes back into Room. Because the client
generates ids and the server's creates are idempotent, replaying a queued
operation after reconnect never duplicates data.

## Key features (V1)

Onboarding (Kalle/Klara) · multiple lists + archive · Swedish auto-sorting with
learning · shopping mode (dark, oversized, keep-awake, hide-completed,
completion suggestion) · voice add · favorites · purchase history · recipe →
list with approval · per-list background image settings · presence · sync status
· Swedish/English.
