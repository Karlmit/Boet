import { Router } from 'express';
import { nanoid } from 'nanoid';
import { query, tx } from '../db.js';
import { hub } from '../hub.js';
import { itemRow } from '../serialize.js';
import { resolveCategoryId, learnCategory, recordPurchase } from '../categorizer.js';

export const items = Router();

items.get('/lists/:listId/items', async (req, res) => {
  const { rows } = await query(
    `SELECT * FROM items WHERE list_id=$1 ORDER BY position, created_at`,
    [req.params.listId]
  );
  res.json(rows.map(itemRow));
});

// Add one or many items. body: { items: [{name, quantity, note, categoryId?}], addedBy }
// or a single { name, quantity, note, addedBy }
items.post('/lists/:listId/items', async (req, res) => {
  const listId = req.params.listId;
  const body = req.body || {};
  const incoming = Array.isArray(body.items)
    ? body.items
    : [{ id: body.id, name: body.name, quantity: body.quantity, note: body.note, categoryId: body.categoryId }];
  const addedBy = body.addedBy || null;

  const created = await tx(async (c) => {
    const out = [];
    for (const it of incoming) {
      if (!it.name?.trim()) continue;
      const categoryId = it.categoryId || (await resolveCategoryId(listId, it.name));
      const { rows: pos } = await c.query(
        `SELECT COALESCE(MAX(position), -1)+1 AS p FROM items WHERE list_id=$1 AND COALESCE(category_id,'')=COALESCE($2,'')`,
        [listId, categoryId]
      );
      // Respect a client-provided id so offline-created items stay consistent;
      // ON CONFLICT makes a replayed outbox POST idempotent.
      const { rows } = await c.query(
        `INSERT INTO items (id, list_id, category_id, name, quantity, note, position, added_by, modified_by)
         VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$8)
         ON CONFLICT (id) DO UPDATE SET
           name=EXCLUDED.name, quantity=EXCLUDED.quantity, note=EXCLUDED.note,
           category_id=EXCLUDED.category_id, updated_at=now()
         RETURNING *`,
        [it.id || nanoid(), listId, categoryId, it.name.trim(), it.quantity || null, it.note || null, pos[0].p, addedBy]
      );
      out.push(rows[0]);
    }
    return out;
  });

  // Track purchase history (fire-and-forget after commit).
  for (const r of created) recordPurchase(r.name).catch(() => {});

  const payload = created.map(itemRow);
  for (const p of payload) hub.emit('create', 'item', p);
  res.status(201).json(payload);
});

// Update fields. If categoryId changes, learn the mapping.
items.patch('/items/:id', async (req, res) => {
  const body = req.body || {};
  const map = {
    name: 'name', quantity: 'quantity', note: 'note', checked: 'checked',
    favorite: 'favorite', categoryId: 'category_id', position: 'position',
  };
  const sets = [];
  const vals = [];
  let i = 1;
  for (const [k, v] of Object.entries(body)) {
    if (map[k] && k !== 'modifiedBy') { sets.push(`${map[k]}=$${i++}`); vals.push(v); }
  }
  if (body.modifiedBy !== undefined) { sets.push(`modified_by=$${i++}`); vals.push(body.modifiedBy); }
  if (sets.length === 0) return res.status(400).json({ error: 'no valid fields' });

  vals.push(req.params.id);
  const { rows } = await query(
    `UPDATE items SET ${sets.join(', ')}, updated_at=now() WHERE id=$${i} RETURNING *`,
    vals
  );
  if (rows.length === 0) return res.status(404).json({ error: 'not found' });
  const item = rows[0];

  // Manual category move -> learn it for the household.
  if (body.categoryId !== undefined && item.category_id) {
    const { rows: cat } = await query(`SELECT name FROM categories WHERE id=$1`, [item.category_id]);
    if (cat.length) learnCategory(item.name, cat[0].name).catch(() => {});
  }

  const payload = itemRow(item);
  hub.emit('update', 'item', payload);
  res.json(payload);
});

items.delete('/items/:id', async (req, res) => {
  const { rows } = await query(`DELETE FROM items WHERE id=$1 RETURNING list_id`, [req.params.id]);
  if (rows.length === 0) return res.status(404).json({ error: 'not found' });
  hub.emit('delete', 'item', { id: req.params.id, listId: rows[0].list_id });
  res.json({ ok: true });
});

// Bulk delete/archive completed items. body: { listId, mode: 'remove' }
items.post('/lists/:listId/clear-checked', async (req, res) => {
  const { rows } = await query(
    `DELETE FROM items WHERE list_id=$1 AND checked=true RETURNING id`,
    [req.params.listId]
  );
  hub.emit('bulk-delete', 'item', { listId: req.params.listId, ids: rows.map((r) => r.id) });
  res.json({ removed: rows.length });
});

// Reorder items within a list. body: { order: [id...] }
items.post('/lists/:listId/items/reorder', async (req, res) => {
  const { order } = req.body || {};
  if (!Array.isArray(order)) return res.status(400).json({ error: 'order array required' });
  await tx(async (c) => {
    for (let p = 0; p < order.length; p++) {
      await c.query(`UPDATE items SET position=$1 WHERE id=$2 AND list_id=$3`,
        [p, order[p], req.params.listId]);
    }
  });
  hub.emit('reorder', 'item', { listId: req.params.listId, order });
  res.json({ ok: true });
});
