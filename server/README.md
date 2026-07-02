# Boet Server

Self-hosted backend for **Boet** — the shared grocery & household list app for a
two-person home. REST + WebSocket, backed by PostgreSQL. No accounts, no cloud
dependency.

## Run

```bash
# from repo root
docker compose up -d --build
```

The server listens on **:3020** (mapped to the host). Postgres data persists in
the `boet-db` volume. Put a reverse proxy in front for HTTPS/remote access if you
want it (enable Websockets Support on the proxy).

## Endpoints (REST, prefix `/api`)

| Method | Path | Purpose |
| ------ | ---- | ------- |
| GET  | `/health` | Liveness |
| GET  | `/api/bootstrap` | Household, members, lists, categories, items in one call |
| GET/POST | `/api/lists` | List the lists / create one |
| PATCH/DELETE | `/api/lists/:id` | Update / archive (soft) a list |
| POST | `/api/lists/:id/restore` | Un-archive |
| POST | `/api/lists/reorder` | `{order:[id…]}` |
| GET/POST | `/api/lists/:id/categories` | Categories of a list |
| PATCH/DELETE | `/api/categories/:id` | Rename / delete category |
| POST | `/api/lists/:id/categories/reorder` | Store-layout learning |
| GET/POST | `/api/lists/:id/items` | Items (POST accepts one or `{items:[…]}`) |
| PATCH/DELETE | `/api/items/:id` | Edit / delete (category change → learned) |
| POST | `/api/lists/:id/clear-checked` | Remove completed items |
| POST | `/api/lists/:id/items/reorder` | Reorder |
| POST | `/api/categorize` | `{names:[…]}` → guessed categories (learned + KB) |
| GET  | `/api/history` | Frequently / recently purchased |
| GET  | `/api/favorites` | Favorited items |
| POST | `/api/recipe/parse` | Recipe text → suggested items (approval in app) |
| GET/POST | `/api/recipes` | Household recipe book (JSON documents) |
| PATCH/DELETE | `/api/recipes/:id` | Edit / delete a recipe |
| POST | `/api/recipes/parse-async` | AI recipe import (pasted text/JSON) — instant placeholder, status over WS |
| GET | `/api/discover/random`, `/random-selection`, `/meal/:id` | TheMealDB: single/ten-random/lookup |
| GET | `/api/discover/search`, `/filter` | TheMealDB: text/letter search, ingredient(s)/category/area filter |
| GET | `/api/discover/categories`, `/areas`, `/ingredients` | TheMealDB reference lists (cached) |
| POST | `/api/discover/import` | Import a MealDB meal into the recipe book — same async pattern as `/parse-async`, deduped |
| POST | `/api/recipes/scrape-async` | Import a recipe from a URL — JSON-LD/text/headless-render cascade, same async pattern as `/parse-async`, deduped per URL |
| POST | `/api/recipes/instagram-async` | Import a recipe from an Instagram Reel URL — caption-first, Gemini video-understanding fallback, same async pattern as `/parse-async`, deduped per Reel |

## WebSocket

Connect to `/ws?memberId=<id>&name=<name>`.

- Server pushes `{type:"change", event, entity, data}` for every mutation
  (real-time sync, <1s).
- Server pushes `{type:"presence", members:[…]}` when presence changes.
- Client sends `{type:"presence", status:"viewing|shopping", listId}` while the
  app is open, and `{type:"ping"}` for keep-alive.

## Intelligence

- **Categorization** (`src/categorize.js`): a Swedish-supermarket keyword KB maps
  item names to the nine default categories, tuned for ICA/Coop/Hemköp/Willys.
- **Learning** (`src/categorizer.js`): when a user moves an item, the
  `name → category` mapping is saved per household and overrides the KB for all
  devices.
- **Recipe / sort prompts** (`src/ai.js`): deterministic parsing so the feature
  works with no cloud model; the app's on-device AI handles the rest.
