import { nanoid } from 'nanoid';
import { query, tx } from './db.js';
import { DEFAULT_CATEGORIES } from './categorize.js';

export const HOUSEHOLD_ID = 'home';

// Ensure the single V1 household, its two members, and a default grocery list.
export async function seed() {
  await tx(async (c) => {
    await c.query(
      `INSERT INTO households (id, name) VALUES ($1, 'Boet')
       ON CONFLICT (id) DO NOTHING`,
      [HOUSEHOLD_ID]
    );

    for (const name of ['Kalle', 'Klara']) {
      const { rows } = await c.query(
        `SELECT id FROM members WHERE household_id=$1 AND name=$2`,
        [HOUSEHOLD_ID, name]
      );
      if (rows.length === 0) {
        await c.query(
          `INSERT INTO members (id, household_id, name) VALUES ($1, $2, $3)`,
          [name.toLowerCase(), HOUSEHOLD_ID, name]
        );
      }
    }

    // Default grocery list (only if no lists exist at all).
    const { rows: lists } = await c.query(
      `SELECT id FROM lists WHERE household_id=$1`,
      [HOUSEHOLD_ID]
    );
    if (lists.length === 0) {
      const listId = nanoid();
      await c.query(
        `INSERT INTO lists (id, household_id, name, kind, icon, position)
         VALUES ($1, $2, 'Matkasse', 'grocery', 'cart', 0)`,
        [listId, HOUSEHOLD_ID]
      );
      let pos = 0;
      for (const cat of DEFAULT_CATEGORIES) {
        await c.query(
          `INSERT INTO categories (id, list_id, name, position)
           VALUES ($1, $2, $3, $4)`,
          [nanoid(), listId, cat, pos++]
        );
      }
    }
  });
}
