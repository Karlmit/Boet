# Boet Display API

Endpoints built for an external kitchen display (e.g. an **ESP32-S3 + E-ink**,
or a kitchen tablet) that just needs plain JSON to render — not the full app
sync/offline protocol. Same server as the rest of Boet, default base URL:

- Local: `http://<server-lan-ip>:3020`
- Production: `https://boet.jabba.se`

No authentication — same trust boundary as the rest of this self-hosted,
single-household API (CORS is wide open in `server/src/index.js`).

## `GET /api/display/shopping-list`

The household's default grocery list ("Matkasse"), **unchecked items only**,
grouped by category in shelf order.

```json
{
  "list": { "id": "abc123", "name": "Matkasse" },
  "categories": [
    { "name": "Mejeri", "items": [{ "name": "Mjölk", "quantity": "1 l", "note": null }] },
    { "name": "Frukt & grönt", "items": [{ "name": "Bananer", "quantity": null, "note": null }] }
  ],
  "itemCount": 2
}
```

- `list` is `null` and `categories` is `[]` if the household has no grocery list yet.
- `quantity`/`note` are `null` when not set on the item.
- Checked-off items are omitted entirely (nothing to buy = nothing to show).

## `GET /api/display/recipe`

The recipe currently marked **"selected"** in the app (the pin icon at the top
of a recipe's detail screen, next to the keep-screen-awake bulb — only one
recipe can be selected at a time). Flattened to plain text; no ingredient
IDs/refs/timers/AI-status, since an e-ink display just renders lines of text.

```json
{
  "recipe": {
    "id": "25721fbe-...",
    "name": "Pannkakor",
    "servings": 4,
    "totalTime": "20 min",
    "image": "https://boet.jabba.se/uploads/xxx.jpg",
    "ingredients": ["2 dl mjölk", "3 ägg"],
    "steps": ["Blanda allt.", "Stek i panna."]
  }
}
```

- `{"recipe": null}` when no recipe is currently selected.
- `servings`/`totalTime`/`image` are `null` when the recipe doesn't have them.
- `image` is always an absolute URL (relative upload paths are resolved against
  the request's own host), or `null`.

## Selecting a recipe

Selection happens from the Boet Android app (tap the pin icon on a recipe's
detail screen). It's a normal REST call if you ever need to trigger it from
somewhere else too:

```
POST /api/recipes/:id/select
Content-Type: application/json

{ "selected": true }
```

Selecting a recipe automatically deselects whatever was previously selected —
only one recipe is ever "current" for the display. Send `{"selected": false}`
to clear it.

## `POST /api/voice/add-from-audio`

Voice-add for a device too slow to do speech recognition itself, or to run the
phone app's usual "record → show suggestions → confirm" flow (the kitchen
tablet). Send a raw audio clip; the server transcribes it locally (a
[faster-whisper-server](https://github.com/speaches-ai/speaches) sidecar, see
`docker-compose.yml`'s `faster-whisper` service and `WHISPER_URL`), runs the
transcript through the same text-cleaning pipeline the phone apps use for
server-side voice cleaning, and **adds every resulting item straight to the
default grocery list** — no approval step, since the device can't practically
show one.

```
POST /api/voice/add-from-audio
Content-Type: application/json

{ "audioBase64": "<base64-encoded audio clip>", "contentType": "audio/wav" }
```

`contentType` is optional (defaults to `audio/wav`); also accepts
`audio/webm`, `audio/ogg`, `audio/mpeg` (mp3), `audio/mp4` (m4a), `audio/aac`.
Whatever your device records/encodes most cheaply is fine — a short clip is
plenty, don't over-think fidelity.

Response:

```json
{
  "transcript": "Lägg till två liter mjölk, sex ägg och bananer",
  "items": [
    { "id": "...", "name": "Mjölk", "quantity": "2 l", "categoryId": "...", "...": "..." },
    { "id": "...", "name": "Ägg", "quantity": "6", "categoryId": "...", "...": "..." },
    { "id": "...", "name": "Bananer", "quantity": null, "categoryId": "...", "...": "..." }
  ],
  "engine": "qwen3:4b-instruct"
}
```

- `items` is the same shape `GET /lists/:listId/items` returns for each newly
  created item (full item row, not the display-simplified shape above).
- `engine` names what cleaned the transcript — the configured Ollama model
  name, or `"fallback"` if Ollama isn't reachable (a simple deterministic
  comma/"och"-split still runs, so voice-add still works, just less smart
  about quantities/categories).
- `503 {"error":"speech-to-text not configured"}` if `WHISPER_URL` isn't set
  on the server. `503 {"error":"transcription failed"}` if the sidecar is
  unreachable or returned nothing usable. `400` if `audioBase64` is missing.
- Every added item's `addedBy` is `"Köksskärmen"` ("the kitchen screen") — not
  a real household member — so the existing push-notification code (which
  skips notifying the actor) notifies **both** Kalle and Klara's phones, not
  just one, when the tablet adds something.

## Suggested ESP32 usage

- Poll `GET /api/display/shopping-list` and `GET /api/display/recipe` on
  whatever interval suits the E-ink refresh budget (these are cheap reads, but
  E-ink hardware usually wants minutes, not seconds, between refreshes anyway).
- Two pages: a shopping-list page (loop `categories[].items`) and a recipe page
  (name/servings/time header, ingredients list, numbered steps).
- Both endpoints return `200` with an empty/`null` body shape rather than
  `404`, so the device doesn't need special-case error handling for "nothing
  to show yet".
