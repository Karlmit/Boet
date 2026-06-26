import { query } from './db.js';

// Idempotent schema creation. Runs on every boot.
export async function initSchema() {
  await query(`
    CREATE TABLE IF NOT EXISTS households (
      id          TEXT PRIMARY KEY,
      name        TEXT NOT NULL DEFAULT 'Boet',
      created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
    );

    CREATE TABLE IF NOT EXISTS members (
      id           TEXT PRIMARY KEY,
      household_id TEXT NOT NULL REFERENCES households(id) ON DELETE CASCADE,
      name         TEXT NOT NULL,
      created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
    );

    CREATE TABLE IF NOT EXISTS lists (
      id            TEXT PRIMARY KEY,
      household_id  TEXT NOT NULL REFERENCES households(id) ON DELETE CASCADE,
      name          TEXT NOT NULL,
      kind          TEXT NOT NULL DEFAULT 'grocery',   -- 'grocery' | 'custom'
      icon          TEXT,
      position      INTEGER NOT NULL DEFAULT 0,
      archived      BOOLEAN NOT NULL DEFAULT false,
      sort_prompt   TEXT,                              -- natural-language sorting rule for custom lists
      bg_image_url  TEXT,
      bg_blur       INTEGER NOT NULL DEFAULT 0,        -- 0..100
      bg_overlay    INTEGER NOT NULL DEFAULT 0,        -- 0..100
      created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
      updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
    );

    CREATE TABLE IF NOT EXISTS categories (
      id          TEXT PRIMARY KEY,
      list_id     TEXT NOT NULL REFERENCES lists(id) ON DELETE CASCADE,
      name        TEXT NOT NULL,
      position    INTEGER NOT NULL DEFAULT 0,
      created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
    );

    CREATE TABLE IF NOT EXISTS items (
      id            TEXT PRIMARY KEY,
      list_id       TEXT NOT NULL REFERENCES lists(id) ON DELETE CASCADE,
      category_id   TEXT REFERENCES categories(id) ON DELETE SET NULL,
      name          TEXT NOT NULL,
      quantity      TEXT,
      note          TEXT,
      checked       BOOLEAN NOT NULL DEFAULT false,
      favorite      BOOLEAN NOT NULL DEFAULT false,
      position      INTEGER NOT NULL DEFAULT 0,
      added_by      TEXT,
      modified_by   TEXT,
      created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
      updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
    );

    -- Household favorites — a standalone quick-add catalogue, NOT tied to any
    -- list item. Deleting "Mjölk" from a list never touches the favorite. The id
    -- is the normalized (lower/trim) name so both devices and the server converge
    -- on one row per name without coordination. category_name (not id) is stored
    -- because favorites are household-wide while categories are per-list.
    CREATE TABLE IF NOT EXISTS favorites (
      id            TEXT PRIMARY KEY,     -- normalized name key (lower, trimmed)
      household_id  TEXT NOT NULL REFERENCES households(id) ON DELETE CASCADE,
      name          TEXT NOT NULL,        -- display name
      category_name TEXT,
      position      INTEGER NOT NULL DEFAULT 0,
      created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
      updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
    );

    -- Learned name -> category-name mappings, shared across the household.
    CREATE TABLE IF NOT EXISTS learned_categories (
      household_id  TEXT NOT NULL REFERENCES households(id) ON DELETE CASCADE,
      item_key      TEXT NOT NULL,        -- normalized item name
      category_name TEXT NOT NULL,
      hits          INTEGER NOT NULL DEFAULT 1,
      updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
      PRIMARY KEY (household_id, item_key)
    );

    -- Per-list remembered category ordering (store layout learning).
    CREATE TABLE IF NOT EXISTS purchase_history (
      household_id  TEXT NOT NULL REFERENCES households(id) ON DELETE CASCADE,
      item_key      TEXT NOT NULL,
      name          TEXT NOT NULL,
      count         INTEGER NOT NULL DEFAULT 1,
      last_added    TIMESTAMPTZ NOT NULL DEFAULT now(),
      PRIMARY KEY (household_id, item_key)
    );

    -- FCM device tokens for push notifications (optional feature).
    CREATE TABLE IF NOT EXISTS device_tokens (
      token        TEXT PRIMARY KEY,
      member_id    TEXT,
      platform     TEXT,
      updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
    );

    CREATE INDEX IF NOT EXISTS idx_items_list ON items(list_id);
    CREATE INDEX IF NOT EXISTS idx_categories_list ON categories(list_id);
    CREATE INDEX IF NOT EXISTS idx_lists_household ON lists(household_id);
    CREATE INDEX IF NOT EXISTS idx_favorites_household ON favorites(household_id);
  `);

  // One-time backfill: seed the standalone favorites table from any items that
  // were starred under the old (item-coupled) model, so existing favorites carry
  // over on first boot after the upgrade. Runs only while favorites is empty.
  await query(`
    ALTER TABLE learned_categories
      ADD COLUMN IF NOT EXISTS source TEXT NOT NULL DEFAULT 'manual'
  `);

  await query(`
    INSERT INTO favorites (id, household_id, name, category_name)
    SELECT DISTINCT ON (lower(trim(i.name)))
           lower(trim(i.name)), l.household_id, trim(i.name), c.name
    FROM items i
    JOIN lists l ON l.id = i.list_id
    LEFT JOIN categories c ON c.id = i.category_id
    WHERE i.favorite = true AND NOT EXISTS (SELECT 1 FROM favorites)
    ORDER BY lower(trim(i.name)), i.updated_at DESC
    ON CONFLICT (id) DO NOTHING
  `);
}
