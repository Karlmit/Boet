import { Router } from 'express';
import { query } from '../db.js';
import { hub } from '../hub.js';
import { HOUSEHOLD_ID } from '../seed.js';
import { favoriteRow } from '../serialize.js';

export const favorites = Router();

// Normalized favorite key: collapse case/whitespace so the id is stable and both
// devices + the server converge on a single row per name. Must match the client's
// key (name.trim().lowercase()).
const favKey = (name) => (name || '').trim().toLowerCase();

// All household favorites (the quick-add catalogue). Standalone — never joined to
// list items, so they survive deleting an item from a list.
favorites.get('/favorites', async (req, res) => {
  const { rows } = await query(
    `SELECT * FROM favorites WHERE household_id=$1 ORDER BY position, lower(name)`,
    [HOUSEHOLD_ID]
  );
  res.json(rows.map(favoriteRow));
});

// Add (or refresh) a favorite. Idempotent: the id is derived from the name, so a
// repeat add — even from the other device offline — updates the same row instead
// of duplicating. body: { name, categoryName? }
favorites.post('/favorites', async (req, res) => {
  const { name, categoryName } = req.body || {};
  if (!name || !name.trim()) return res.status(400).json({ error: 'name required' });
  const id = favKey(name);
  const { rows } = await query(
    `INSERT INTO favorites (id, household_id, name, category_name)
     VALUES ($1,$2,$3,$4)
     ON CONFLICT (id) DO UPDATE SET
       name=EXCLUDED.name,
       category_name=COALESCE(EXCLUDED.category_name, favorites.category_name),
       updated_at=now()
     RETURNING *`,
    [id, HOUSEHOLD_ID, name.trim(), categoryName || null]
  );
  const payload = favoriteRow(rows[0]);
  hub.emit('create', 'favorite', payload);
  res.status(201).json(payload);
});

// Remove a favorite by its (normalized-name) id. 4xx-on-missing so an offline
// outbox drops a stale delete instead of retrying forever.
favorites.delete('/favorites/:id', async (req, res) => {
  const { rows } = await query(
    `DELETE FROM favorites WHERE id=$1 AND household_id=$2 RETURNING id`,
    [req.params.id, HOUSEHOLD_ID]
  );
  if (rows.length === 0) return res.status(404).json({ error: 'not found' });
  hub.emit('delete', 'favorite', { id: req.params.id });
  res.json({ ok: true });
});
