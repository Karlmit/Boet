import { query } from './db.js';
import { guessCategory, normalizeKey } from './categorize.js';
import { HOUSEHOLD_ID } from './seed.js';

// Resolve the best category_id for an item within a given list.
// Priority: learned household mapping -> keyword guess -> 'Övrigt' -> any/null.
export async function resolveCategoryId(listId, name) {
  const key = normalizeKey(name);

  const { rows: cats } = await query(
    `SELECT id, name FROM categories WHERE list_id=$1`,
    [listId]
  );
  if (cats.length === 0) return null;
  const byName = new Map(cats.map((c) => [c.name.toLowerCase(), c.id]));

  // 1. Learned mapping for the household.
  if (key) {
    const learned = await learnedCategoryFor(name);
    if (learned) {
      const id = byName.get(learned.toLowerCase());
      if (id) return id;
    }
  }

  // 2. Keyword guess (only meaningful for grocery-style lists).
  const guessed = guessCategory(name);
  const guessedId = byName.get(guessed.toLowerCase());
  if (guessedId) return guessedId;

  // 3. Fall back to 'Övrigt' if present, else the last category.
  return byName.get('övrigt') || cats[cats.length - 1].id;
}

// Persist a learned mapping when a user manually moves an item.
export async function learnCategory(name, categoryName) {
  const key = normalizeKey(name);
  if (!key || !categoryName) return;
  await query(
    `INSERT INTO learned_categories (household_id, item_key, category_name, source, hits)
     VALUES ($1,$2,$3,'manual',1)
     ON CONFLICT (household_id, item_key)
     DO UPDATE SET category_name=$3, source='manual', hits=learned_categories.hits+1, updated_at=now()`,
    [HOUSEHOLD_ID, key, categoryName]
  );
}

export async function learnCategoryAI(name, categoryName) {
  const key = normalizeKey(name);
  if (!key || !categoryName) return;
  await query(
    `INSERT INTO learned_categories (household_id, item_key, category_name, source, hits)
     VALUES ($1,$2,$3,'llm',1)
     ON CONFLICT (household_id, item_key) DO NOTHING`,
    [HOUSEHOLD_ID, key, categoryName]
  );
}

export async function learnedCategoryFor(name) {
  const key = normalizeKey(name);
  if (!key) return null;
  const { rows } = await query(
    `SELECT category_name FROM learned_categories WHERE household_id=$1 AND item_key=$2`,
    [HOUSEHOLD_ID, key]
  );
  return rows[0]?.category_name || null;
}

export async function recordPurchase(name) {
  const key = normalizeKey(name);
  if (!key) return;
  await query(
    `INSERT INTO purchase_history (household_id, item_key, name, count)
     VALUES ($1,$2,$3,1)
     ON CONFLICT (household_id, item_key)
     DO UPDATE SET count=purchase_history.count+1, name=$3, last_added=now()`,
    [HOUSEHOLD_ID, key, name.trim()]
  );
}
