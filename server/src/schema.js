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
      icon        TEXT,
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

    -- Household recipes. The whole recipe document (name, description, image,
    -- servings, ingredients with structured quantity/unit/food + reference ids,
    -- and steps with ingredient_refs + timers) lives in the JSONB data blob —
    -- recipes are read and synced as a single unit, so a document keeps the
    -- Android Room mirror trivial (one row = one JSON string). Only list-view
    -- metadata that's mutated independently of the body (category, manual order)
    -- gets its own column. name/image are derived from data in the serializer.
    CREATE TABLE IF NOT EXISTS recipes (
      id            TEXT PRIMARY KEY,
      household_id  TEXT NOT NULL REFERENCES households(id) ON DELETE CASCADE,
      data          JSONB NOT NULL DEFAULT '{}'::jsonb,
      category_name TEXT,
      position      INTEGER NOT NULL DEFAULT 0,
      selected      BOOLEAN NOT NULL DEFAULT false,
      created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
      updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
    );

    -- Recipe categorization catalogue: two independent dimensions ("type" e.g.
    -- Dessert/Pasta, "country" e.g. Italien) a household builds up over time —
    -- AI-assigned on recipe creation, user-editable via a dropdown that can also
    -- add new entries. lower(name) uniqueness (per household+kind) so the AI and
    -- the manual "+ Ny kategori" flow converge on one row instead of near-dupes.
    CREATE TABLE IF NOT EXISTS recipe_categories (
      id           TEXT PRIMARY KEY,
      household_id TEXT NOT NULL REFERENCES households(id) ON DELETE CASCADE,
      kind         TEXT NOT NULL CHECK (kind IN ('type','country')),
      name         TEXT NOT NULL,
      created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
    );

    CREATE INDEX IF NOT EXISTS idx_items_list ON items(list_id);
    CREATE INDEX IF NOT EXISTS idx_categories_list ON categories(list_id);
    CREATE INDEX IF NOT EXISTS idx_lists_household ON lists(household_id);
    CREATE INDEX IF NOT EXISTS idx_favorites_household ON favorites(household_id);
    CREATE INDEX IF NOT EXISTS idx_recipes_household ON recipes(household_id);
    CREATE INDEX IF NOT EXISTS idx_recipe_categories_household ON recipe_categories(household_id);
    CREATE UNIQUE INDEX IF NOT EXISTS idx_recipe_categories_unique
      ON recipe_categories(household_id, kind, lower(name));
  `);

  // One-time backfill: seed the standalone favorites table from any items that
  // were starred under the old (item-coupled) model, so existing favorites carry
  // over on first boot after the upgrade. Runs only while favorites is empty.
  await query(`
    ALTER TABLE learned_categories
      ADD COLUMN IF NOT EXISTS source TEXT NOT NULL DEFAULT 'manual'
  `);

  await query(`
    ALTER TABLE categories
      ADD COLUMN IF NOT EXISTS icon TEXT
  `);

  // Discover (TheMealDB) import dedup: source_key = "mealdb:<idMeal>" for an
  // imported recipe, NULL for manual/pasted/photo recipes. The partial unique
  // index (only enforced where source_key is set) means re-importing the same
  // meal for a household always hits the existing row instead of creating a
  // duplicate — no backfill needed since existing rows are all NULL already.
  await query(`
    ALTER TABLE recipes ADD COLUMN IF NOT EXISTS source_key TEXT
  `);
  await query(`
    CREATE UNIQUE INDEX IF NOT EXISTS idx_recipes_source_key
      ON recipes(household_id, source_key) WHERE source_key IS NOT NULL
  `);

  // The recipe currently shown on the kitchen display (see POST
  // /recipes/:id/select). The partial unique index enforces "only one selected
  // recipe per household" at the DB level, on top of the application-level
  // clear-then-set transaction in the route.
  await query(`
    ALTER TABLE recipes ADD COLUMN IF NOT EXISTS selected BOOLEAN NOT NULL DEFAULT false
  `);
  await query(`
    CREATE UNIQUE INDEX IF NOT EXISTS idx_recipes_selected
      ON recipes(household_id) WHERE selected = true
  `);

  // Two-axis categorization (Type of food / Country), replacing the old
  // single free-text category_name. category_status tracks the AI job
  // ('queued'|'done'|'error'|'manual' — 'manual' means a person overrode it,
  // so a late-arriving AI result must not clobber it); category_job is an
  // opaque per-job token used as a compare-and-swap guard for that same race
  // (see runCategorize in routes/recipes.js).
  await query(`
    ALTER TABLE recipes ADD COLUMN IF NOT EXISTS type_category_id TEXT
      REFERENCES recipe_categories(id) ON DELETE SET NULL
  `);
  await query(`
    ALTER TABLE recipes ADD COLUMN IF NOT EXISTS country_category_id TEXT
      REFERENCES recipe_categories(id) ON DELETE SET NULL
  `);
  await query(`
    ALTER TABLE recipes ADD COLUMN IF NOT EXISTS category_status TEXT
  `);
  await query(`
    ALTER TABLE recipes ADD COLUMN IF NOT EXISTS category_job TEXT
  `);

  // One-time backfill + cleanup, guarded so it only runs while category_name
  // still exists: fold each distinct old category_name into a "type" catalogue
  // entry (the old field was always food-type-like, e.g. "Dessert"/"Pasta" in
  // this household's actual usage) and point the recipe at it. country stays
  // NULL for pre-existing recipes — the app's "Sortera om" action backfills it
  // via AI, or the user sets it manually.
  await query(`
    DO $$
    BEGIN
      IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'recipes' AND column_name = 'category_name'
      ) THEN
        INSERT INTO recipe_categories (id, household_id, kind, name)
        SELECT gen_random_uuid()::text, household_id, 'type', trim(category_name)
        FROM (
          SELECT DISTINCT household_id, category_name FROM recipes
          WHERE category_name IS NOT NULL AND trim(category_name) <> ''
        ) d
        ON CONFLICT (household_id, kind, lower(name)) DO NOTHING;

        UPDATE recipes r
        SET type_category_id = rc.id
        FROM recipe_categories rc
        WHERE rc.household_id = r.household_id AND rc.kind = 'type'
          AND lower(rc.name) = lower(trim(r.category_name))
          AND r.category_name IS NOT NULL AND trim(r.category_name) <> '';

        ALTER TABLE recipes DROP COLUMN category_name;
      END IF;
    END $$;
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
