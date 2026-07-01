import { Router } from 'express';
import { nanoid } from 'nanoid';
import { query } from '../db.js';
import { hub } from '../hub.js';
import { HOUSEHOLD_ID } from '../seed.js';
import { recipeRow } from '../serialize.js';
import { parseRecipeText } from '../recipe-ai.js';
import { recipeLlmEnabled } from '../recipe-llm.js';

export const recipes = Router();

// AI parse (legacy, synchronous): free recipe text -> structured RecipeDoc in one
// blocking request. Kept for older app builds; new ones use /recipes/parse-async
// below, which doesn't hold the connection open for the whole parse+fallback
// chain (a cloud attempt followed by a local retry can together run well past a
// mobile client's read timeout, which made this endpoint look like a hard
// failure even when the server went on to finish successfully).
// body: { text } -> { recipe }
recipes.post('/recipes/parse', async (req, res) => {
  const text = (req.body || {}).text;
  if (!text || !String(text).trim()) return res.status(400).json({ error: 'text required' });
  const recipe = await parseRecipeText(text);
  if (!recipe) return res.status(503).json({ error: 'parser unavailable' });
  res.json({ recipe });
});

// AI parse (async): creates a placeholder recipe immediately (so it shows up in
// the recipe list right away, offline-sync-style) and returns its id without
// waiting for the model. Parsing continues in the background; each phase change
// (asking the cloud model, falling back to local, translating, done, degraded,
// error) is written to the recipe's `data.aiStatus`/`aiError` and broadcast over
// the same WebSocket channel as any other recipe update, so every connected
// device — including the one that started the request — sees live progress via
// the normal Room/Flow sync path. body: { text } -> 202 { ...recipeRow }
recipes.post('/recipes/parse-async', async (req, res) => {
  const text = (req.body || {}).text;
  if (!text || !String(text).trim()) return res.status(400).json({ error: 'text required' });

  const id = nanoid();
  const placeholder = {
    name: '', description: null, image: null, servings: null, totalTime: null, sourceUrl: null,
    ingredients: [], steps: [],
    aiStatus: recipeLlmEnabled() ? 'queued' : 'error',
    aiError: recipeLlmEnabled() ? null : 'Ingen AI konfigurerad på servern.',
  };
  const { rows } = await query(
    `INSERT INTO recipes (id, household_id, data, position) VALUES ($1,$2,$3,0) RETURNING *`,
    [id, HOUSEHOLD_ID, placeholder]
  );
  const created = recipeRow(rows[0]);
  hub.emit('create', 'recipe', created);
  res.status(202).json(created);
  if (!recipeLlmEnabled()) return;

  // Fire-and-forget: the HTTP response is already sent above. Progress and the
  // final result reach the client purely over the WebSocket broadcast.
  let lastStatus = 'queued';
  const setStatus = async (aiStatus) => {
    lastStatus = aiStatus;
    const { rows: r } = await query(
      `UPDATE recipes SET data = data || $2::jsonb, updated_at = now() WHERE id=$1 RETURNING *`,
      [id, JSON.stringify({ aiStatus })]
    );
    if (r[0]) hub.emit('update', 'recipe', recipeRow(r[0]));
  };
  try {
    const doc = await parseRecipeText(text, { onStatus: setStatus });
    // 'degraded' (both backends failed and the raw unlinked Mealie fields were
    // used as a last resort) is itself a terminal state set via onStatus above —
    // don't clobber it with 'done'.
    const finalData = doc
      ? { ...doc, aiStatus: lastStatus === 'degraded' ? 'degraded' : 'done', aiError: null }
      : { ...placeholder, aiStatus: 'error', aiError: 'AI:n kunde inte tolka receptet.' };
    const { rows: r } = await query(
      `UPDATE recipes SET data=$2, updated_at=now() WHERE id=$1 RETURNING *`,
      [id, finalData]
    );
    if (r[0]) hub.emit('update', 'recipe', recipeRow(r[0]));
  } catch (e) {
    console.error(`[boet] recipe ai parse (${id}) threw:`, e);
    const { rows: r } = await query(
      `UPDATE recipes SET data = data || $2::jsonb, updated_at = now() WHERE id=$1 RETURNING *`,
      [id, JSON.stringify({ aiStatus: 'error', aiError: String(e?.message || e) })]
    );
    if (r[0]) hub.emit('update', 'recipe', recipeRow(r[0]));
  }
});

// All household recipes, ordered for the grid (manual position, then name).
recipes.get('/recipes', async (req, res) => {
  const { rows } = await query(
    `SELECT * FROM recipes WHERE household_id=$1 ORDER BY position, lower(data->>'name')`,
    [HOUSEHOLD_ID]
  );
  res.json(rows.map(recipeRow));
});

// Create or replace a recipe. The client provides the id (so an offline-created
// recipe keeps a stable id) and the full document in `data`; ON CONFLICT makes a
// replayed outbox POST idempotent and doubles as the "save edits" path.
// body: { id, data, categoryName?, position? }
recipes.post('/recipes', async (req, res) => {
  const { id, data, categoryName, position } = req.body || {};
  if (!id || typeof data !== 'object' || data === null) {
    return res.status(400).json({ error: 'id and data required' });
  }
  const { rows } = await query(
    `INSERT INTO recipes (id, household_id, data, category_name, position)
     VALUES ($1,$2,$3,$4,COALESCE($5,0))
     ON CONFLICT (id) DO UPDATE SET
       data=EXCLUDED.data,
       category_name=EXCLUDED.category_name,
       position=COALESCE($5, recipes.position),
       updated_at=now()
     RETURNING *`,
    [id, HOUSEHOLD_ID, data, categoryName || null, position]
  );
  const payload = recipeRow(rows[0]);
  hub.emit('create', 'recipe', payload);
  res.status(201).json(payload);
});

// Patch a recipe's body and/or its list-view metadata. Any subset of
// { data, categoryName, position } may be sent. COALESCE keeps untouched fields.
recipes.patch('/recipes/:id', async (req, res) => {
  const { data, categoryName, position } = req.body || {};
  const { rows } = await query(
    `UPDATE recipes SET
       data=COALESCE($3, data),
       category_name=COALESCE($4, category_name),
       position=COALESCE($5, position),
       updated_at=now()
     WHERE id=$1 AND household_id=$2
     RETURNING *`,
    [req.params.id, HOUSEHOLD_ID, data ?? null, categoryName ?? null, position ?? null]
  );
  // 4xx-on-missing so an offline outbox drops a stale patch instead of retrying.
  if (rows.length === 0) return res.status(404).json({ error: 'not found' });
  const payload = recipeRow(rows[0]);
  hub.emit('update', 'recipe', payload);
  res.json(payload);
});

// Delete a recipe. 4xx-on-missing for the same outbox-drain reason as above.
recipes.delete('/recipes/:id', async (req, res) => {
  const { rows } = await query(
    `DELETE FROM recipes WHERE id=$1 AND household_id=$2 RETURNING id`,
    [req.params.id, HOUSEHOLD_ID]
  );
  if (rows.length === 0) return res.status(404).json({ error: 'not found' });
  hub.emit('delete', 'recipe', { id: req.params.id });
  res.json({ ok: true });
});
