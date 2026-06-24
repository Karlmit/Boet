import { Router } from 'express';
import { query } from '../db.js';
import { HOUSEHOLD_ID } from '../seed.js';
import { guessCategory } from '../categorize.js';
import { parseRecipe } from '../ai.js';
import { itemRow } from '../serialize.js';

export const knowledge = Router();

// Bootstrap: everything the app needs on launch.
knowledge.get('/bootstrap', async (req, res) => {
  const [house, members, lists, categories, items] = await Promise.all([
    query(`SELECT * FROM households WHERE id=$1`, [HOUSEHOLD_ID]),
    query(`SELECT id, name FROM members WHERE household_id=$1 ORDER BY name`, [HOUSEHOLD_ID]),
    query(`SELECT * FROM lists WHERE household_id=$1 ORDER BY position, created_at`, [HOUSEHOLD_ID]),
    query(`SELECT c.* FROM categories c JOIN lists l ON l.id=c.list_id WHERE l.household_id=$1 ORDER BY c.position`, [HOUSEHOLD_ID]),
    query(`SELECT i.* FROM items i JOIN lists l ON l.id=i.list_id WHERE l.household_id=$1 ORDER BY i.position, i.created_at`, [HOUSEHOLD_ID]),
  ]);
  res.json({
    household: house.rows[0] || null,
    members: members.rows,
    lists: lists.rows,
    categories: categories.rows,
    items: items.rows.map(itemRow),
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

// Favorites across all lists.
knowledge.get('/favorites', async (req, res) => {
  const { rows } = await query(
    `SELECT DISTINCT ON (lower(name)) * FROM items i
     WHERE i.list_id IN (SELECT id FROM lists WHERE household_id=$1) AND favorite=true
     ORDER BY lower(name), updated_at DESC`,
    [HOUSEHOLD_ID]
  );
  res.json(rows.map(itemRow));
});

// Recipe -> suggested items (approval flow happens in the app; nothing is added here).
// body: { text } -> { suggestions: [{name, quantity, category}] }
knowledge.post('/recipe/parse', async (req, res) => {
  const { text } = req.body || {};
  const parsed = parseRecipe(text || '');
  const suggestions = parsed.map((p) => ({ ...p, category: guessCategory(p.name) }));
  res.json({ suggestions });
});
