import { Router } from 'express';
import { nanoid } from 'nanoid';
import { query } from '../db.js';
import { hub } from '../hub.js';
import { HOUSEHOLD_ID } from '../seed.js';
import { recipeCategoryRow } from '../serialize.js';

export const recipeCategories = Router();

const VALID_KINDS = new Set(['type', 'country']);

recipeCategories.get('/recipe-categories', async (req, res) => {
  const kind = req.query.kind;
  if (!VALID_KINDS.has(kind)) return res.status(400).json({ error: 'kind must be type or country' });
  const { rows } = await query(
    `SELECT * FROM recipe_categories WHERE household_id=$1 AND kind=$2 ORDER BY lower(name)`,
    [HOUSEHOLD_ID, kind]
  );
  res.json(rows.map(recipeCategoryRow));
});

// Case-insensitive find-or-create, shared by the manual "+ Ny kategori" UI
// (via the POST route below) and the AI categorizer (recipe-category-ai.js) —
// so the two never mint near-duplicate rows for the same name, AND so an
// AI-minted category (created directly by categorizeRecipe, with no client
// ever having POSTed it) still reaches every synced device: this function
// itself broadcasts 'create' whenever it actually inserts a new row, rather
// than leaving that to the POST route below, which the AI path never calls.
export async function findOrCreateCategory(kind, name) {
  const trimmed = (name || '').trim();
  if (!VALID_KINDS.has(kind) || !trimmed) return null;
  const { rows: existing } = await query(
    `SELECT * FROM recipe_categories WHERE household_id=$1 AND kind=$2 AND lower(name)=lower($3)`,
    [HOUSEHOLD_ID, kind, trimmed]
  );
  if (existing[0]) return existing[0];
  const { rows } = await query(
    `INSERT INTO recipe_categories (id, household_id, kind, name) VALUES ($1,$2,$3,$4)
     ON CONFLICT (household_id, kind, lower(name)) DO UPDATE SET name=recipe_categories.name
     RETURNING id, (xmax = 0) AS inserted`,
    [nanoid(), HOUSEHOLD_ID, kind, trimmed]
  );
  const { rows: full } = await query(`SELECT * FROM recipe_categories WHERE id=$1`, [rows[0].id]);
  if (rows[0].inserted) hub.emit('create', 'recipeCategory', recipeCategoryRow(full[0]));
  return full[0];
}

recipeCategories.post('/recipe-categories', async (req, res) => {
  const { kind, name } = req.body || {};
  if (!VALID_KINDS.has(kind)) return res.status(400).json({ error: 'kind must be type or country' });
  if (!name?.trim()) return res.status(400).json({ error: 'name required' });
  const row = await findOrCreateCategory(kind, name);
  res.status(201).json(recipeCategoryRow(row));
});
