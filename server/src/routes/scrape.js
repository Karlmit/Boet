import { Router } from 'express';
import { nanoid } from 'nanoid';
import { query } from '../db.js';
import { hub } from '../hub.js';
import { HOUSEHOLD_ID } from '../seed.js';
import { structureFromEx, parseRecipeText } from '../recipe-ai.js';
import { recipeRowById, runCategorize } from './recipes.js';
import { scrapeUrl, normalizeUrl, exToRawDoc, ScrapeError } from '../scrape.js';
import { assertUrlAllowed, SsrfBlockedError } from '../ssrf-guard.js';

export const scrape = Router();

// Import a recipe by URL. Mirrors /discover/import (routes/discover.js):
// placeholder insert -> 202 + hub 'create' -> fire-and-forget background
// fetch+structure -> onStatus merge-writes -> final UPDATE + hub 'update'.
// Deduplicated per household via recipes.source_key ("url:<normalized>"), same
// mechanism MealDB import uses, so re-submitting the same link is instant and
// never duplicates — retries a prior `error` in place.
// body: { url } -> 202/200 { ...recipeRow }
scrape.post('/recipes/scrape-async', async (req, res) => {
  const inputUrl = String((req.body || {}).url || '').trim();
  if (!inputUrl) return res.status(400).json({ error: 'url required' });

  let normalized;
  try {
    normalized = normalizeUrl(inputUrl);
  } catch {
    return res.status(400).json({ error: 'invalid url' });
  }
  // Validate synchronously (scheme + SSRF check) before creating any row — an
  // obviously-blocked host should fail fast, not leave a doomed placeholder.
  try {
    await assertUrlAllowed(inputUrl);
  } catch (e) {
    return res.status(400).json({ error: e instanceof SsrfBlockedError ? 'url not allowed' : 'invalid url' });
  }

  const sourceKey = `url:${normalized}`;
  const { rows: existingRows } = await query(
    `SELECT * FROM recipes WHERE household_id=$1 AND source_key=$2 LIMIT 1`,
    [HOUSEHOLD_ID, sourceKey]
  );
  const existing = existingRows[0];

  if (existing && existing.data?.aiStatus !== 'error') {
    return res.status(200).json(await recipeRowById(existing.id));
  }

  let id;
  let created;
  if (existing) {
    id = existing.id;
    await query(
      `UPDATE recipes SET data = data || $2::jsonb, updated_at = now() WHERE id=$1`,
      [id, JSON.stringify({ aiStatus: 'queued', aiError: null })]
    );
    created = await recipeRowById(id);
    hub.emit('update', 'recipe', created);
  } else {
    id = nanoid();
    const placeholder = {
      name: '', description: null, image: null, servings: null, totalTime: null,
      sourceUrl: inputUrl, ingredients: [], steps: [],
      aiStatus: 'queued', aiError: null,
    };
    const { rows } = await query(
      `INSERT INTO recipes (id, household_id, data, source_key, position)
       VALUES ($1,$2,$3,$4,0)
       ON CONFLICT (household_id, source_key) WHERE source_key IS NOT NULL DO NOTHING
       RETURNING id`,
      [id, HOUSEHOLD_ID, placeholder, sourceKey]
    );
    if (rows.length === 0) {
      // Lost a race with a concurrent import of the same URL.
      const { rows: r2 } = await query(
        `SELECT id FROM recipes WHERE household_id=$1 AND source_key=$2 LIMIT 1`,
        [HOUSEHOLD_ID, sourceKey]
      );
      return res.status(200).json(await recipeRowById(r2[0].id));
    }
    created = await recipeRowById(id);
    hub.emit('create', 'recipe', created);
  }
  res.status(202).json(created);

  // Fire-and-forget: response already sent. Progress/result reach clients only
  // over the WebSocket broadcast, same as /recipes/parse-async and /discover/import.
  let lastStatus = 'queued';
  const setStatus = async (aiStatus) => {
    lastStatus = aiStatus;
    await query(
      `UPDATE recipes SET data = data || $2::jsonb, updated_at = now() WHERE id=$1`,
      [id, JSON.stringify({ aiStatus })]
    );
    const r = await recipeRowById(id);
    if (r) hub.emit('update', 'recipe', r);
  };
  try {
    const scraped = await scrapeUrl(inputUrl, { onStatus: setStatus });
    if (!scraped) {
      throw Object.assign(
        new Error('Kunde inte hitta ett recept på sidan. Prova att klistra in texten eller ta ett foto istället.'),
        { userFacing: true }
      );
    }
    const doc = scraped.kind === 'jsonld'
      ? await structureFromEx(scraped.ex, {
          full: [scraped.ex.name, scraped.ex.description, ...scraped.ex.stepLines].filter(Boolean).join('\n'),
          rawFallback: () => exToRawDoc(scraped.ex),
          forceLang: scraped.forceLang,
          onStatus: setStatus,
        })
      : await parseRecipeText(scraped.text, { onStatus: setStatus });

    // structureFromEx/parseRecipeText always return image/sourceUrl/totalTime
    // as null — reapply the real scraped image, the URL the user typed, and
    // any duration pulled from JSON-LD here, same post-hoc pattern
    // routes/discover.js uses for MealDB's thumbnail/source/youtube.
    const finalData = doc
      ? {
          ...doc,
          image: scraped.image || null,
          sourceUrl: inputUrl,
          totalTime: (scraped.kind === 'jsonld' ? scraped.totalTime : null) ?? doc.totalTime ?? null,
          aiStatus: lastStatus === 'degraded' ? 'degraded' : 'done',
          aiError: null,
        }
      : {
          name: '', description: null, image: scraped.image || null, servings: null,
          totalTime: scraped.kind === 'jsonld' ? scraped.totalTime : null, sourceUrl: inputUrl,
          ingredients: [], steps: [],
          aiStatus: 'error', aiError: 'AI:n kunde inte tolka receptet.',
        };
    await query(`UPDATE recipes SET data=$2, updated_at=now() WHERE id=$1`, [id, finalData]);
    const r = await recipeRowById(id);
    if (r) hub.emit('update', 'recipe', r);
    if (doc) runCategorize(id);
  } catch (e) {
    console.error(`[boet] url scrape (${id}) threw:`, e);
    const msg = e?.userFacing ? e.message : (e instanceof ScrapeError || e instanceof SsrfBlockedError)
      ? 'Kunde inte hämta sidan. Kontrollera länken.'
      : String(e?.message || e);
    await query(
      `UPDATE recipes SET data = data || $2::jsonb, updated_at = now() WHERE id=$1`,
      [id, JSON.stringify({ aiStatus: 'error', aiError: msg })]
    );
    const r = await recipeRowById(id);
    if (r) hub.emit('update', 'recipe', r);
  }
});
