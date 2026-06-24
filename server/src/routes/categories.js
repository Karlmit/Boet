import { Router } from 'express';
import { nanoid } from 'nanoid';
import { query, tx } from '../db.js';
import { hub } from '../hub.js';
import { categoryRow } from '../serialize.js';

export const categories = Router();

categories.get('/lists/:listId/categories', async (req, res) => {
  const { rows } = await query(
    `SELECT * FROM categories WHERE list_id=$1 ORDER BY position`,
    [req.params.listId]
  );
  res.json(rows.map(categoryRow));
});

categories.post('/lists/:listId/categories', async (req, res) => {
  const { name } = req.body || {};
  if (!name?.trim()) return res.status(400).json({ error: 'name required' });
  const { rows: pos } = await query(
    `SELECT COALESCE(MAX(position), -1)+1 AS p FROM categories WHERE list_id=$1`,
    [req.params.listId]
  );
  const { rows } = await query(
    `INSERT INTO categories (id, list_id, name, position) VALUES ($1,$2,$3,$4) RETURNING *`,
    [nanoid(), req.params.listId, name.trim(), pos[0].p]
  );
  const payload = categoryRow(rows[0]);
  hub.emit('create', 'category', payload);
  res.status(201).json(payload);
});

categories.patch('/categories/:id', async (req, res) => {
  const { name } = req.body || {};
  if (!name?.trim()) return res.status(400).json({ error: 'name required' });
  const { rows } = await query(
    `UPDATE categories SET name=$1 WHERE id=$2 RETURNING *`,
    [name.trim(), req.params.id]
  );
  if (rows.length === 0) return res.status(404).json({ error: 'not found' });
  const payload = categoryRow(rows[0]);
  hub.emit('update', 'category', payload);
  res.json(payload);
});

categories.delete('/categories/:id', async (req, res) => {
  const { rows } = await query(`DELETE FROM categories WHERE id=$1 RETURNING list_id`, [req.params.id]);
  if (rows.length === 0) return res.status(404).json({ error: 'not found' });
  hub.emit('delete', 'category', { id: req.params.id, listId: rows[0].list_id });
  res.json({ ok: true });
});

// Reorder categories (store-layout learning happens client-side then persists here).
// body { order: [id, id, ...] }
categories.post('/lists/:listId/categories/reorder', async (req, res) => {
  const { order } = req.body || {};
  if (!Array.isArray(order)) return res.status(400).json({ error: 'order array required' });
  await tx(async (c) => {
    for (let p = 0; p < order.length; p++) {
      await c.query(`UPDATE categories SET position=$1 WHERE id=$2 AND list_id=$3`,
        [p, order[p], req.params.listId]);
    }
  });
  hub.emit('reorder', 'category', { listId: req.params.listId, order });
  res.json({ ok: true });
});
