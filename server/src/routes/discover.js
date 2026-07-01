import { Router } from 'express';
import { nanoid } from 'nanoid';
import { query } from '../db.js';
import { hub } from '../hub.js';
import { HOUSEHOLD_ID } from '../seed.js';
import { recipeRow } from '../serialize.js';
import { structureFromEx } from '../recipe-ai.js';
import * as mealdb from '../mealdb.js';

export const discover = Router();

// All read endpoints degrade to an empty result (200) rather than a 5xx on any
// upstream MealDB failure — Discover just shows an empty state, same "degrade,
// never throw" convention as ollama.js/translate.js underneath.

discover.get('/discover/random', async (req, res) => {
  const meal = await mealdb.random();
  res.json(meal ? mealdb.toDetail(meal) : null);
});

discover.get('/discover/random-selection', async (req, res) => {
  const meals = await mealdb.randomSelection();
  res.json(meals.map(mealdb.toDetail));
});

// The one detail lookup path for every summary-only browse surface below.
discover.get('/discover/meal/:id', async (req, res) => {
  const meal = await mealdb.lookupMeal(req.params.id);
  if (!meal) return res.status(404).json({ error: 'not found' });
  res.json(mealdb.toDetail(meal));
});

discover.get('/discover/search', async (req, res) => {
  const { q, letter } = req.query;
  const meals = letter
    ? await mealdb.searchByLetter(String(letter))
    : q
    ? await mealdb.searchByName(String(q))
    : [];
  res.json(meals.map(mealdb.toSummary));
});

discover.get('/discover/filter', async (req, res) => {
  const { ingredients, category, area } = req.query;
  let meals = [];
  if (ingredients) {
    meals = await mealdb.filterByIngredients(String(ingredients).split(',').map((s) => s.trim()).filter(Boolean));
  } else if (category) {
    meals = await mealdb.filterByCategory(String(category));
  } else if (area) {
    meals = await mealdb.filterByArea(String(area));
  } else {
    return res.status(400).json({ error: 'ingredients, category, or area required' });
  }
  res.json(meals.map(mealdb.toSummary));
});

discover.get('/discover/categories', async (req, res) => {
  res.json(await mealdb.listCategories());
});

discover.get('/discover/areas', async (req, res) => {
  res.json(await mealdb.listAreas());
});

discover.get('/discover/ingredients', async (req, res) => {
  res.json(await mealdb.listIngredients());
});

// Import a MealDB meal into the household's recipe book. Mirrors
// /recipes/parse-async (routes/recipes.js): placeholder insert -> 202 + hub
// 'create' -> fire-and-forget background structuring -> onStatus merge-writes
// -> final UPDATE + hub 'update'. Deduplicated per household via recipes.source_key
// so re-tapping "add" on an already-imported meal is instant and never
// duplicates or re-triggers a parse (except to retry a previously-errored one).
discover.post('/discover/import', async (req, res) => {
  const mealId = (req.body || {}).mealId;
  if (!mealId) return res.status(400).json({ error: 'mealId required' });
  const sourceKey = `mealdb:${mealId}`;

  const { rows: existingRows } = await query(
    `SELECT * FROM recipes WHERE household_id=$1 AND source_key=$2 LIMIT 1`,
    [HOUSEHOLD_ID, sourceKey]
  );
  const existing = existingRows[0];

  if (existing && existing.data?.aiStatus !== 'error') {
    // Already queued/parsing/done/degraded — instant, no duplicate background work.
    return res.status(200).json(recipeRow(existing));
  }

  const meal = await mealdb.lookupMeal(mealId);
  if (!meal) return res.status(502).json({ error: 'mealdb lookup failed' });

  let id;
  let created;
  if (existing) {
    // Retry a previously-failed import: reuse the same row/id, reset status.
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
      name: meal.strMeal || '',
      description: null,
      image: meal.strMealThumb || null,
      servings: null,
      totalTime: null,
      sourceUrl: meal.strSource || null,
      ingredients: [],
      steps: [],
      aiStatus: 'queued',
      aiError: null,
    };
    const { rows } = await query(
      `INSERT INTO recipes (id, household_id, data, source_key, position)
       VALUES ($1,$2,$3,$4,0)
       ON CONFLICT (household_id, source_key) WHERE source_key IS NOT NULL DO NOTHING
       RETURNING *`,
      [id, HOUSEHOLD_ID, placeholder, sourceKey]
    );
    if (rows.length === 0) {
      // Lost a race with a concurrent import of the same meal — return that one.
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
  // over the WebSocket broadcast, same as /recipes/parse-async.
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
    const ex = mealdb.mealToEx(meal);
    const doc = await structureFromEx(ex, {
      full: ex.stepLines.join('\n'),
      rawFallback: () => mealdb.mealToRawDoc(meal),
      forceLang: 'en', // MealDB is always English — skip the paste-oriented lang heuristics
      onStatus: setStatus,
    });
    // structureFromEx/finalize always return image:null/sourceUrl:null — reapply
    // the real thumbnail/source here so the saved recipe keeps its MealDB photo.
    const finalData = doc
      ? {
          ...doc,
          image: meal.strMealThumb || null,
          sourceUrl: meal.strSource || null,
          aiStatus: lastStatus === 'degraded' ? 'degraded' : 'done',
          aiError: null,
        }
      : {
          name: meal.strMeal || '', description: null, image: meal.strMealThumb || null,
          servings: null, totalTime: null, sourceUrl: meal.strSource || null,
          ingredients: [], steps: [], aiStatus: 'error', aiError: 'Kunde inte tolka receptet.',
        };
    const { rows: r } = await query(
      `UPDATE recipes SET data=$2, updated_at=now() WHERE id=$1 RETURNING *`,
      [id, finalData]
    );
    if (r[0]) hub.emit('update', 'recipe', recipeRow(r[0]));
  } catch (e) {
    console.error(`[boet] mealdb import (${id}) threw:`, e);
    const { rows: r } = await query(
      `UPDATE recipes SET data = data || $2::jsonb, updated_at = now() WHERE id=$1 RETURNING *`,
      [id, JSON.stringify({ aiStatus: 'error', aiError: String(e?.message || e) })]
    );
    if (r[0]) hub.emit('update', 'recipe', recipeRow(r[0]));
  }
});
