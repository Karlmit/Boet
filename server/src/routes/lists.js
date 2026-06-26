import { Router } from 'express';
import { nanoid } from 'nanoid';
import { query, tx } from '../db.js';
import { hub } from '../hub.js';
import { HOUSEHOLD_ID } from '../seed.js';
import { DEFAULT_CATEGORIES } from '../categorize.js';
import { listRow, categoryRow } from '../serialize.js';
import { parseSortPrompt } from '../ai.js';

export const lists = Router();

const CATEGORY_ICONS = {
  'Frukt & grönt': 'leaf',
  'Bröd': 'bread',
  'Mejeri': 'dairy',
  'Kött & fisk': 'protein',
  'Frys': 'frozen',
  'Torrvaror': 'pantry',
  'Snacks': 'snacks',
  'Hushåll': 'home',
  'Övrigt': 'other',
};

// All lists (optionally including archived).
lists.get('/lists', async (req, res) => {
  const includeArchived = req.query.archived === 'true';
  const { rows } = await query(
    `SELECT * FROM lists WHERE household_id=$1 ${includeArchived ? '' : 'AND archived=false'}
     ORDER BY position, created_at`,
    [HOUSEHOLD_ID]
  );
  res.json(rows.map(listRow));
});

// Create a list. For custom lists, an optional sortPrompt seeds categories.
lists.post('/lists', async (req, res) => {
  const { id: clientId, name, kind = 'custom', icon = null, sortPrompt = null } = req.body || {};
  if (!name?.trim()) return res.status(400).json({ error: 'name required' });

  const result = await tx(async (c) => {
    const { rows: posRows } = await c.query(
      `SELECT COALESCE(MAX(position), -1) + 1 AS p FROM lists WHERE household_id=$1`,
      [HOUSEHOLD_ID]
    );
    const id = clientId || nanoid();
    const { rows } = await c.query(
      `INSERT INTO lists (id, household_id, name, kind, icon, position, sort_prompt)
       VALUES ($1,$2,$3,$4,$5,$6,$7)
       ON CONFLICT (id) DO UPDATE SET name=EXCLUDED.name, icon=EXCLUDED.icon, updated_at=now()
       RETURNING *`,
      [id, HOUSEHOLD_ID, name.trim(), kind, icon, posRows[0].p, sortPrompt]
    );

    // Seed categories — but only for a genuinely new list (a replayed outbox
    // POST hits ON CONFLICT and must not duplicate categories).
    const { rows: existing } = await c.query(
      `SELECT 1 FROM categories WHERE list_id=$1 LIMIT 1`, [id]
    );
    let cats = [];
    if (existing.length === 0) {
      if (kind === 'grocery') cats = DEFAULT_CATEGORIES;
      else if (sortPrompt) cats = parseSortPrompt(sortPrompt);
    }
    const catRows = [];
    let pos = 0;
    for (const cat of cats) {
      const { rows: cr } = await c.query(
        `INSERT INTO categories (id, list_id, name, icon, position) VALUES ($1,$2,$3,$4,$5) RETURNING *`,
        [nanoid(), id, cat, CATEGORY_ICONS[cat] || null, pos++]
      );
      catRows.push(cr[0]);
    }
    return { list: rows[0], categories: catRows };
  });

  const payload = { ...listRow(result.list), categories: result.categories.map(categoryRow) };
  hub.emit('create', 'list', payload);
  res.status(201).json(payload);
});

lists.patch('/lists/:id', async (req, res) => {
  const allowed = ['name', 'icon', 'position', 'archived', 'sort_prompt', 'bg_image_url', 'bg_blur', 'bg_overlay'];
  const map = { sortPrompt: 'sort_prompt', bgImageUrl: 'bg_image_url', bgBlur: 'bg_blur', bgOverlay: 'bg_overlay' };
  const sets = [];
  const vals = [];
  let i = 1;
  for (const [k, v] of Object.entries(req.body || {})) {
    const col = map[k] || k;
    if (allowed.includes(col)) { sets.push(`${col}=$${i++}`); vals.push(v); }
  }
  if (sets.length === 0) return res.status(400).json({ error: 'no valid fields' });
  vals.push(req.params.id);
  const { rows } = await query(
    `UPDATE lists SET ${sets.join(', ')}, updated_at=now() WHERE id=$${i} RETURNING *`,
    vals
  );
  if (rows.length === 0) return res.status(404).json({ error: 'not found' });
  const payload = listRow(rows[0]);
  hub.emit('update', 'list', payload);
  res.json(payload);
});

// Archive (soft).
lists.delete('/lists/:id', async (req, res) => {
  const { rows } = await query(
    `UPDATE lists SET archived=true, updated_at=now() WHERE id=$1 RETURNING *`,
    [req.params.id]
  );
  if (rows.length === 0) return res.status(404).json({ error: 'not found' });
  hub.emit('update', 'list', listRow(rows[0]));
  res.json(listRow(rows[0]));
});

lists.post('/lists/:id/restore', async (req, res) => {
  const { rows } = await query(
    `UPDATE lists SET archived=false, updated_at=now() WHERE id=$1 RETURNING *`,
    [req.params.id]
  );
  if (rows.length === 0) return res.status(404).json({ error: 'not found' });
  hub.emit('update', 'list', listRow(rows[0]));
  res.json(listRow(rows[0]));
});

// Reorder lists: body { order: [id, id, ...] }
lists.post('/lists/reorder', async (req, res) => {
  const { order } = req.body || {};
  if (!Array.isArray(order)) return res.status(400).json({ error: 'order array required' });
  await tx(async (c) => {
    for (let p = 0; p < order.length; p++) {
      await c.query(`UPDATE lists SET position=$1 WHERE id=$2`, [p, order[p]]);
    }
  });
  hub.emit('reorder', 'list', { order });
  res.json({ ok: true });
});
