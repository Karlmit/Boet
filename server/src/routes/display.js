import { Router } from 'express';
import { query } from '../db.js';
import { HOUSEHOLD_ID } from '../seed.js';

export const display = Router();

// Read-only endpoints for external, non-app displays — built for a kitchen
// e-ink display (ESP32-S3) that just needs plain data to render, not the app's
// full sync/offline machinery. No auth: same trust boundary as the rest of this
// self-hosted, single-household API (see index.js's permissive CORS comment).

// An ingredient's plain display line, amount included when known. Mirrors the
// app's own `ingredientLine` (RecipeDetailScreen.kt) so the two never disagree.
function ingredientLine(ing) {
  if (ing.quantity != null) {
    return [String(ing.quantity), ing.unit, ing.food].filter(Boolean).join(' ');
  }
  return ing.display || ing.food || '';
}

// A stored image path is relative ("/uploads/xxx.jpg"); resolve it against this
// request's own host so an ESP32 can fetch it directly. Already-absolute URLs
// (Discover imports, URL scrapes) pass through untouched.
function resolveImageUrl(req, path) {
  if (!path) return null;
  if (/^https?:\/\//i.test(path)) return path;
  return `${req.protocol}://${req.get('host')}${path}`;
}

// The household's shopping list, unchecked items only, grouped by category in
// shelf order — everything an e-ink display needs to render a "what to buy"
// page without pulling in the full lists/categories/items bootstrap shape.
display.get('/display/shopping-list', async (req, res) => {
  const { rows: listRows } = await query(
    `SELECT id, name FROM lists WHERE household_id=$1 AND kind='grocery' AND archived=false
     ORDER BY position, created_at LIMIT 1`,
    [HOUSEHOLD_ID]
  );
  const list = listRows[0];
  if (!list) return res.json({ list: null, categories: [], itemCount: 0 });

  const { rows: items } = await query(
    `SELECT i.name, i.quantity, i.note, c.name AS category_name, c.position AS category_position
     FROM items i LEFT JOIN categories c ON c.id = i.category_id
     WHERE i.list_id=$1 AND i.checked=false
     ORDER BY COALESCE(c.position, 999), i.position, i.created_at`,
    [list.id]
  );

  const byCategory = new Map();
  for (const it of items) {
    const key = it.category_name || 'Övrigt';
    if (!byCategory.has(key)) byCategory.set(key, []);
    byCategory.get(key).push({ name: it.name, quantity: it.quantity, note: it.note });
  }
  res.json({
    list: { id: list.id, name: list.name },
    categories: Array.from(byCategory, ([name, categoryItems]) => ({ name, items: categoryItems })),
    itemCount: items.length,
  });
});

// The currently selected recipe (see POST /api/recipes/:id/select), flattened
// to plain text fields — an e-ink display renders lines of text, not the app's
// structured RecipeDoc (ingredient refs, timers, i18n status, etc).
display.get('/display/recipe', async (req, res) => {
  const { rows } = await query(
    `SELECT * FROM recipes WHERE household_id=$1 AND selected=true LIMIT 1`,
    [HOUSEHOLD_ID]
  );
  const row = rows[0];
  if (!row) return res.json({ recipe: null });
  const data = row.data || {};
  res.json({
    recipe: {
      id: row.id,
      name: data.name || '',
      servings: data.servings ?? null,
      totalTime: data.totalTime || null,
      image: resolveImageUrl(req, data.image),
      ingredients: (data.ingredients || []).map(ingredientLine),
      steps: (data.steps || []).map((s) => s.text || ''),
    },
  });
});
