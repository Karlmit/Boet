import { query } from './db.js';
import { hub } from './hub.js';
import { recipeRow } from './serialize.js';
import { mirrorImage } from './routes/media.js';
import { fetchInstagramMeta, instagramImportEnabled } from './instagram-import.js';

// One-shot startup repair for recipes whose image is a raw Instagram CDN URL.
// Imports made before routes/instagram.js started mirroring thumbnails stored
// the CDN URL directly — those links are signed with an expiry, so they all
// eventually 403 and the recipe silently loses its picture. For each such row:
// try mirroring the stored URL (works if it hasn't expired yet), else re-fetch
// fresh meta for the Reel and mirror that thumbnail. Once a row's image is a
// local /uploads path it no longer matches the query, so this converges to a
// no-op; rows that can't be repaired (e.g. Apify unconfigured) are left
// untouched and retried on the next boot.
export async function backfillInstagramImages() {
  const { rows } = await query(
    `SELECT * FROM recipes
     WHERE data->>'image' ~ 'cdninstagram\\.com|fbcdn\\.net'`
  );
  if (rows.length === 0) return;
  console.log(`[boet] image backfill: ${rows.length} recipe(s) with raw Instagram CDN images`);

  for (const row of rows) {
    const data = row.data || {};
    let mirrored = await mirrorImage(data.image);
    if (!mirrored && data.instagramUrl && instagramImportEnabled()) {
      try {
        const meta = await fetchInstagramMeta(data.instagramUrl);
        mirrored = await mirrorImage(meta?.thumbnail);
      } catch (e) {
        console.warn(`[boet] image backfill: meta re-fetch failed for ${row.id}:`, e.message || e);
      }
    }
    if (!mirrored) {
      console.warn(`[boet] image backfill: could not repair ${row.id} (${data.name || 'namnlöst'}), leaving as-is`);
      continue;
    }
    const { rows: updated } = await query(
      `UPDATE recipes SET data = data || $2::jsonb, updated_at = now() WHERE id=$1 RETURNING *`,
      [row.id, JSON.stringify({ image: mirrored })]
    );
    if (updated[0]) hub.emit('update', 'recipe', recipeRow(updated[0]));
    console.log(`[boet] image backfill: repaired ${row.id} (${data.name || 'namnlöst'}) -> ${mirrored}`);
  }
}
