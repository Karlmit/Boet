import { Router } from 'express';
import { nanoid } from 'nanoid';
import { query } from '../db.js';
import { hub } from '../hub.js';
import { HOUSEHOLD_ID } from '../seed.js';
import { recipeRow } from '../serialize.js';
import { normalizeUrl } from '../scrape.js';
import { validateInstagramUrl, InstagramUrlError } from '../instagram.js';
import { importInstagramReel } from '../instagram-import.js';

export const instagram = Router();

// Import a recipe from an Instagram Reel. Mirrors /recipes/scrape-async
// (routes/scrape.js): placeholder insert -> 202 + hub 'create' -> fire-and-
// forget background fetch+structure -> onStatus merge-writes -> final UPDATE
// + hub 'update'. Deduplicated per household via recipes.source_key
// ("instagram:<normalized>"), same mechanism/index url-scrape and MealDB
// import use, so re-sharing the same Reel is instant and never duplicates —
// retries a prior `error` in place.
// body: { url } -> 202/200 { ...recipeRow }
instagram.post('/recipes/instagram-async', async (req, res) => {
  const inputUrl = String((req.body || {}).url || '').trim();
  if (!inputUrl) return res.status(400).json({ error: 'url required' });

  try {
    validateInstagramUrl(inputUrl);
  } catch (e) {
    return res.status(400).json({ error: e instanceof InstagramUrlError ? 'not an instagram reel url' : 'invalid url' });
  }

  let normalized;
  try {
    normalized = normalizeUrl(inputUrl);
  } catch {
    return res.status(400).json({ error: 'invalid url' });
  }

  const sourceKey = `instagram:${normalized}`;
  const { rows: existingRows } = await query(
    `SELECT * FROM recipes WHERE household_id=$1 AND source_key=$2 LIMIT 1`,
    [HOUSEHOLD_ID, sourceKey]
  );
  const existing = existingRows[0];

  if (existing && existing.data?.aiStatus !== 'error') {
    return res.status(200).json(recipeRow(existing));
  }

  let id;
  let created;
  if (existing) {
    id = existing.id;
    const { rows } = await query(
      `UPDATE recipes SET data = data || $2::jsonb, updated_at = now() WHERE id=$1 RETURNING *`,
      [id, JSON.stringify({ aiStatus: 'queued', aiError: null })]
    );
    created = recipeRow(rows[0]);
    hub.emit('update', 'recipe', created);
  } else {
    id = nanoid();
    const placeholder = {
      name: '', description: null, image: null, servings: null, totalTime: null,
      sourceUrl: inputUrl, instagramUrl: inputUrl, ingredients: [], steps: [],
      aiStatus: 'queued', aiError: null,
    };
    const { rows } = await query(
      `INSERT INTO recipes (id, household_id, data, source_key, position)
       VALUES ($1,$2,$3,$4,0)
       ON CONFLICT (household_id, source_key) WHERE source_key IS NOT NULL DO NOTHING
       RETURNING *`,
      [id, HOUSEHOLD_ID, placeholder, sourceKey]
    );
    if (rows.length === 0) {
      // Lost a race with a concurrent import of the same Reel.
      const { rows: r2 } = await query(
        `SELECT * FROM recipes WHERE household_id=$1 AND source_key=$2 LIMIT 1`,
        [HOUSEHOLD_ID, sourceKey]
      );
      return res.status(200).json(recipeRow(r2[0]));
    }
    created = recipeRow(rows[0]);
    hub.emit('create', 'recipe', created);
  }
  res.status(202).json(created);

  // Fire-and-forget: response already sent. Progress/result reach clients only
  // over the WebSocket broadcast, same as /recipes/scrape-async.
  let lastStatus = 'queued';
  const setStatus = async (aiStatus) => {
    lastStatus = aiStatus;
    const { rows: r } = await query(
      `UPDATE recipes SET data = data || $2::jsonb, updated_at = now() WHERE id=$1 RETURNING *`,
      [id, JSON.stringify({ aiStatus })]
    );
    if (r[0]) hub.emit('update', 'recipe', recipeRow(r[0]));
  };
  try {
    const result = await importInstagramReel(inputUrl, { onStatus: setStatus });
    const doc = result?.doc;
    const finalData = doc
      ? {
          ...doc,
          image: doc.image || result.thumbnail || null,
          sourceUrl: inputUrl,
          instagramUrl: inputUrl,
          aiStatus: lastStatus === 'degraded' ? 'degraded' : 'done',
          aiError: null,
        }
      : {
          name: '', description: null, image: result?.thumbnail || null, servings: null, totalTime: null,
          sourceUrl: inputUrl, instagramUrl: inputUrl, ingredients: [], steps: [],
          aiStatus: 'error', aiError: 'AI:n kunde inte tolka receptet från Instagram-inlägget.',
        };
    const { rows: r } = await query(
      `UPDATE recipes SET data=$2, updated_at=now() WHERE id=$1 RETURNING *`,
      [id, finalData]
    );
    if (r[0]) hub.emit('update', 'recipe', recipeRow(r[0]));
  } catch (e) {
    console.error(`[boet] instagram import (${id}) threw:`, e);
    const msg = e?.userFacing ? e.message : 'Kunde inte importera från Instagram. Försök igen.';
    const { rows: r } = await query(
      `UPDATE recipes SET data = data || $2::jsonb, updated_at = now() WHERE id=$1 RETURNING *`,
      [id, JSON.stringify({ aiStatus: 'error', aiError: msg })]
    );
    if (r[0]) hub.emit('update', 'recipe', recipeRow(r[0]));
  }
});
