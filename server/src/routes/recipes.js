import { Router } from 'express';
import { nanoid } from 'nanoid';
import { query, tx } from '../db.js';
import { hub } from '../hub.js';
import { HOUSEHOLD_ID } from '../seed.js';
import { recipeRow } from '../serialize.js';
import { parseRecipeText } from '../recipe-ai.js';
import { recipeLlmEnabled } from '../recipe-llm.js';
import { categorizeRecipe } from '../recipe-category-ai.js';

export const recipes = Router();

// Shared SELECT that joins in the two-axis category catalogue (type/country),
// aliased tc_*/cc_* — recipeRow() (serialize.js) reads those to embed
// {id,name} objects on every recipe row. Used by every read path (GET
// /recipes, bootstrap in routes/knowledge.js) and by recipeRowById() below,
// which every write path uses to build its response/broadcast so a plain
// `UPDATE ... RETURNING *` (which lacks the joined columns) never gets
// serialized directly and silently nulls out a recipe's category display.
export const RECIPE_SELECT = `
  SELECT r.*, tc.id AS tc_id, tc.name AS tc_name, cc.id AS cc_id, cc.name AS cc_name
  FROM recipes r
  LEFT JOIN recipe_categories tc ON tc.id = r.type_category_id
  LEFT JOIN recipe_categories cc ON cc.id = r.country_category_id
`;

export async function recipeRowById(id) {
  const { rows } = await query(`${RECIPE_SELECT} WHERE r.id=$1`, [id]);
  return rows[0] ? recipeRow(rows[0]) : null;
}

// Runs (or re-runs) AI categorization for a recipe in the background — called
// on creation (when the client didn't already supply explicit category ids)
// and from POST /recipes/:id/resort-categories ("mark for re-sorting").
// category_job is an opaque per-call token used as a compare-and-swap guard:
// the final write only lands if the token is still current, so a manual
// PATCH override (which bumps the token) or a newer resort call landing while
// this one is still in flight silently wins instead of being clobbered late.
export async function runCategorize(id) {
  if (!recipeLlmEnabled()) return;
  const token = nanoid();
  try {
    const { rows: qrows } = await query(
      `UPDATE recipes SET category_status='queued', category_job=$2, updated_at=now()
       WHERE id=$1 RETURNING id`,
      [id, token]
    );
    if (qrows.length === 0) return;
    const queuedRow = await recipeRowById(id);
    if (queuedRow) hub.emit('update', 'recipe', queuedRow);

    let result = null;
    const { rows: drows } = await query(`SELECT data FROM recipes WHERE id=$1`, [id]);
    if (drows[0]) result = await categorizeRecipe(drows[0].data || {});

    const { rows: urows } = await query(
      `UPDATE recipes SET
         type_category_id = COALESCE($3, type_category_id),
         country_category_id = COALESCE($4, country_category_id),
         category_status = $5,
         updated_at = now()
       WHERE id=$1 AND category_job=$2
       RETURNING id`,
      [id, token, result?.typeCategoryId || null, result?.countryCategoryId || null, result ? 'done' : 'error']
    );
    if (urows.length === 0) return; // superseded by a manual override or a newer resort call
    const finalRow = await recipeRowById(id);
    if (finalRow) hub.emit('update', 'recipe', finalRow);
  } catch (e) {
    console.error(`[boet] recipe categorize (${id}) threw:`, e);
    await query(
      `UPDATE recipes SET category_status='error', updated_at=now() WHERE id=$1 AND category_job=$2`,
      [id, token]
    );
    const errRow = await recipeRowById(id);
    if (errRow) hub.emit('update', 'recipe', errRow);
  }
}

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
  await query(
    `INSERT INTO recipes (id, household_id, data, position) VALUES ($1,$2,$3,0)`,
    [id, HOUSEHOLD_ID, placeholder]
  );
  const created = await recipeRowById(id);
  hub.emit('create', 'recipe', created);
  res.status(202).json(created);
  if (!recipeLlmEnabled()) return;

  // Fire-and-forget: the HTTP response is already sent above. Progress and the
  // final result reach the client purely over the WebSocket broadcast.
  let lastStatus = 'queued';
  const setStatus = async (aiStatus) => {
    lastStatus = aiStatus;
    await query(
      `UPDATE recipes SET data = data || $2::jsonb, updated_at = now() WHERE id=$1`,
      [id, JSON.stringify({ aiStatus })]
    );
    const r = await recipeRowById(id);
    if (r) hub.emit('update', 'recipe', r);
  };
  try {
    const doc = await parseRecipeText(text, { onStatus: setStatus });
    // 'degraded' (both backends failed and the raw unlinked Mealie fields were
    // used as a last resort) is itself a terminal state set via onStatus above —
    // don't clobber it with 'done'.
    const finalData = doc
      ? { ...doc, aiStatus: lastStatus === 'degraded' ? 'degraded' : 'done', aiError: null }
      : { ...placeholder, aiStatus: 'error', aiError: 'AI:n kunde inte tolka receptet.' };
    await query(`UPDATE recipes SET data=$2, updated_at=now() WHERE id=$1`, [id, finalData]);
    const r = await recipeRowById(id);
    if (r) hub.emit('update', 'recipe', r);
    if (doc) runCategorize(id);
  } catch (e) {
    console.error(`[boet] recipe ai parse (${id}) threw:`, e);
    await query(
      `UPDATE recipes SET data = data || $2::jsonb, updated_at = now() WHERE id=$1`,
      [id, JSON.stringify({ aiStatus: 'error', aiError: String(e?.message || e) })]
    );
    const r = await recipeRowById(id);
    if (r) hub.emit('update', 'recipe', r);
  }
});

// All household recipes, ordered for the grid (manual position, then name).
recipes.get('/recipes', async (req, res) => {
  const { rows } = await query(
    `${RECIPE_SELECT} WHERE r.household_id=$1 ORDER BY r.position, lower(r.data->>'name')`,
    [HOUSEHOLD_ID]
  );
  res.json(rows.map(recipeRow));
});

// Create or replace a recipe. The client provides the id (so an offline-created
// recipe keeps a stable id) and the full document in `data`; ON CONFLICT makes a
// replayed outbox POST idempotent and doubles as the "save edits" path. If the
// client supplies typeCategoryId/countryCategoryId this is a manual assignment
// (skips AI categorization); otherwise a brand-new recipe (never an edit-replay
// of an existing one — checked via the `xmax=0` insert marker) is queued for
// AI auto-sort. body: { id, data, position?, typeCategoryId?, countryCategoryId? }
recipes.post('/recipes', async (req, res) => {
  const { id, data, position, typeCategoryId, countryCategoryId } = req.body || {};
  if (!id || typeof data !== 'object' || data === null) {
    return res.status(400).json({ error: 'id and data required' });
  }
  const manual = typeCategoryId !== undefined || countryCategoryId !== undefined;
  const { rows } = await query(
    `INSERT INTO recipes (id, household_id, data, position, type_category_id, country_category_id, category_status)
     VALUES ($1,$2,$3,COALESCE($4,0),$5,$6,$7)
     ON CONFLICT (id) DO UPDATE SET
       data=EXCLUDED.data,
       position=COALESCE($4, recipes.position),
       type_category_id=CASE WHEN $8 THEN EXCLUDED.type_category_id ELSE recipes.type_category_id END,
       country_category_id=CASE WHEN $8 THEN EXCLUDED.country_category_id ELSE recipes.country_category_id END,
       category_status=CASE WHEN $8 THEN EXCLUDED.category_status ELSE recipes.category_status END,
       updated_at=now()
     RETURNING id, (xmax = 0) AS inserted`,
    [id, HOUSEHOLD_ID, data, position, typeCategoryId || null, countryCategoryId || null,
     manual ? 'manual' : null, manual]
  );
  const wasInserted = !!rows[0]?.inserted;
  const payload = await recipeRowById(id);
  hub.emit(wasInserted ? 'create' : 'update', 'recipe', payload);
  res.status(201).json(payload);
  if (wasInserted && !manual) runCategorize(id);
});

// Patch a recipe's body, list-view metadata, and/or manual category
// assignment. Any subset of { data, position, typeCategoryId, countryCategoryId }
// may be sent; typeCategoryId/countryCategoryId may be explicit null (clear
// that assignment). Supplying either marks category_status 'manual' and bumps
// category_job so a same-in-flight AI categorize run becomes a no-op instead
// of overwriting the user's choice (see runCategorize's compare-and-swap).
recipes.patch('/recipes/:id', async (req, res) => {
  const { data, position, typeCategoryId, countryCategoryId } = req.body || {};
  const setType = typeCategoryId !== undefined;
  const setCountry = countryCategoryId !== undefined;
  const manual = setType || setCountry;
  const { rows } = await query(
    `UPDATE recipes SET
       data=COALESCE($3, data),
       position=COALESCE($4, position),
       type_category_id=CASE WHEN $5 THEN $6 ELSE type_category_id END,
       country_category_id=CASE WHEN $7 THEN $8 ELSE country_category_id END,
       category_status=CASE WHEN $9 THEN 'manual' ELSE category_status END,
       category_job=CASE WHEN $9 THEN $10 ELSE category_job END,
       updated_at=now()
     WHERE id=$1 AND household_id=$2
     RETURNING id`,
    [req.params.id, HOUSEHOLD_ID, data ?? null, position ?? null,
     setType, typeCategoryId ?? null, setCountry, countryCategoryId ?? null, manual, manual ? nanoid() : null]
  );
  // 4xx-on-missing so an offline outbox drops a stale patch instead of retrying.
  if (rows.length === 0) return res.status(404).json({ error: 'not found' });
  const payload = await recipeRowById(req.params.id);
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

// Mark a recipe for AI re-sorting ("Sortera om") — re-runs categorizeRecipe
// against its current content and overwrites type/country (unless a manual
// PATCH lands first, per the compare-and-swap in runCategorize). Async, same
// shape as the other background AI endpoints: 202 now, result over WebSocket.
recipes.post('/recipes/:id/resort-categories', async (req, res) => {
  const { rows } = await query(
    `SELECT id FROM recipes WHERE id=$1 AND household_id=$2`,
    [req.params.id, HOUSEHOLD_ID]
  );
  if (rows.length === 0) return res.status(404).json({ error: 'not found' });
  res.status(202).json({ ok: true });
  runCategorize(req.params.id);
});

// Select (or deselect) a recipe as "the current recipe" — surfaced in the app's
// detail screen (the pin icon next to keep-awake) and read by the kitchen
// display API (GET /api/display/recipe). Only one recipe can be selected per
// household: selecting one clears any previously-selected recipe in the same
// transaction, and the DB's partial unique index (schema.js) backs that up.
// Any other device holding the old selected recipe needs its own broadcast to
// un-highlight it, so both the cleared row(s) and the target row are emitted.
// body: { selected: boolean } -> recipeRow
recipes.post('/recipes/:id/select', async (req, res) => {
  const selected = !!(req.body || {}).selected;
  const { clearedIds, targetId } = await tx(async (c) => {
    let clearedIds = [];
    if (selected) {
      const { rows } = await c.query(
        `UPDATE recipes SET selected=false, updated_at=now()
         WHERE household_id=$1 AND selected=true AND id<>$2 RETURNING id`,
        [HOUSEHOLD_ID, req.params.id]
      );
      clearedIds = rows.map((r) => r.id);
    }
    const { rows: targetRows } = await c.query(
      `UPDATE recipes SET selected=$3, updated_at=now() WHERE id=$1 AND household_id=$2 RETURNING id`,
      [req.params.id, HOUSEHOLD_ID, selected]
    );
    return { clearedIds, targetId: targetRows[0]?.id || null };
  });
  if (!targetId) return res.status(404).json({ error: 'not found' });
  for (const cid of clearedIds) {
    const r = await recipeRowById(cid);
    if (r) hub.emit('update', 'recipe', r);
  }
  const payload = await recipeRowById(targetId);
  hub.emit('update', 'recipe', payload);
  res.json(payload);
});
