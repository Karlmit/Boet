import { Router } from 'express';
import { query } from '../db.js';
import { HOUSEHOLD_ID } from '../seed.js';
import { guessCategory } from '../categorize.js';
import { parseRecipe } from '../ai.js';
import { cleanVoice } from '../voice.js';
import { itemRow, listRow, categoryRow, favoriteRow, recipeRow } from '../serialize.js';

export const knowledge = Router();

// Bootstrap: everything the app needs on launch.
knowledge.get('/bootstrap', async (req, res) => {
  const [house, members, lists, categories, items, learned, favorites, recipes] = await Promise.all([
    query(`SELECT * FROM households WHERE id=$1`, [HOUSEHOLD_ID]),
    query(`SELECT id, name FROM members WHERE household_id=$1 ORDER BY name`, [HOUSEHOLD_ID]),
    query(`SELECT * FROM lists WHERE household_id=$1 ORDER BY position, created_at`, [HOUSEHOLD_ID]),
    query(`SELECT c.* FROM categories c JOIN lists l ON l.id=c.list_id WHERE l.household_id=$1 ORDER BY c.position`, [HOUSEHOLD_ID]),
    query(`SELECT i.* FROM items i JOIN lists l ON l.id=i.list_id WHERE l.household_id=$1 ORDER BY i.position, i.created_at`, [HOUSEHOLD_ID]),
    query(`SELECT item_key, category_name FROM learned_categories WHERE household_id=$1`, [HOUSEHOLD_ID]),
    query(`SELECT * FROM favorites WHERE household_id=$1 ORDER BY position, lower(name)`, [HOUSEHOLD_ID]),
    query(`SELECT * FROM recipes WHERE household_id=$1 ORDER BY position, lower(data->>'name')`, [HOUSEHOLD_ID]),
  ]);
  res.json({
    household: house.rows[0] || null,
    members: members.rows,
    lists: lists.rows.map(listRow),
    categories: categories.rows.map(categoryRow),
    items: items.rows.map(itemRow),
    // Household learned mappings so the app can categorize offline and reflect
    // corrections made on the other device.
    learned: learned.rows.map((r) => ({ key: r.item_key, category: r.category_name })),
    // Standalone favorites catalogue (server-synced; independent of list items).
    favorites: favorites.rows.map(favoriteRow),
    // Household recipes (full documents; the app mirrors them in Room).
    recipes: recipes.rows.map(recipeRow),
  });
});

// Categorize a batch of names (used by the app as a server-side fallback to its
// on-device model). body: { listId, names: [] } -> { name: categoryName }
knowledge.post('/categorize', async (req, res) => {
  const { names = [] } = req.body || {};
  const out = {};
  for (const name of names) {
    const key = (name || '').toLowerCase().trim();
    const learned = await query(
      `SELECT category_name FROM learned_categories WHERE household_id=$1 AND item_key=$2`,
      [HOUSEHOLD_ID, key]
    );
    out[name] = learned.rows[0]?.category_name || guessCategory(name);
  }
  res.json(out);
});

// Frequent / recently purchased items for quick re-add.
knowledge.get('/history', async (req, res) => {
  const limit = Math.min(parseInt(req.query.limit || '40', 10), 200);
  const { rows } = await query(
    `SELECT item_key, name, count, last_added FROM purchase_history
     WHERE household_id=$1 ORDER BY count DESC, last_added DESC LIMIT $2`,
    [HOUSEHOLD_ID, limit]
  );
  res.json(rows.map((r) => ({ key: r.item_key, name: r.name, count: r.count, lastAdded: r.last_added })));
});

// Favorites moved to routes/favorites.js — they are now a standalone catalogue,
// no longer derived from starred list items.

// Clean a raw voice transcript into tidy grocery items using the household's local
// LLM (Ollama / qwen3:4b-instruct), so phones without an on-device model still get
// good cleaning. body: { transcript: [], categories: [] } -> { items: [{name, quantity, category?}], engine }.
knowledge.post('/voice/clean', async (req, res) => {
  const { transcript, categories = [] } = req.body || {};
  res.json(await cleanVoice(transcript, categories));
});

// Recipe -> suggested items (approval flow happens in the app; nothing is added here).
// body: { text } -> { suggestions: [{name, quantity, category}] }
knowledge.post('/recipe/parse', async (req, res) => {
  const { text } = req.body || {};
  const parsed = parseRecipe(text || '');
  const suggestions = parsed.map((p) => ({ ...p, category: guessCategory(p.name) }));
  res.json({ suggestions });
});
