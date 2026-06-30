import { Router } from 'express';
import { query } from '../db.js';
import { hub } from '../hub.js';
import { HOUSEHOLD_ID } from '../seed.js';
import { recipeRow } from '../serialize.js';
import { parseRecipeText } from '../recipe-ai.js';

export const recipes = Router();

// AI parse: free recipe text -> structured RecipeDoc (ingredients with units
// converted + step↔ingredient refs + timers, translated to Swedish). The app
// reviews/edits the result in the editor before saving — nothing is persisted
// here. 503 when the local model is unavailable so the app can fall back to the
// manual editor. body: { text } -> { recipe }
recipes.post('/recipes/parse', async (req, res) => {
  const text = (req.body || {}).text;
  if (!text || !String(text).trim()) return res.status(400).json({ error: 'text required' });
  const recipe = await parseRecipeText(text);
  if (!recipe) return res.status(503).json({ error: 'parser unavailable' });
  res.json({ recipe });
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
