// AI categorization of a recipe into the household's two-axis catalogue
// (Type of food, Country) — run once at creation and again on demand ("Sortera
// om"). Reuses the same cloud-first/local-ollama-fallback backend as recipe
// structuring (recipe-llm.js) — no new LLM plumbing — but is its own small,
// single-call prompt since classifying is far cheaper than full structuring.

import { query } from './db.js';
import { HOUSEHOLD_ID } from './seed.js';
import { recipeLlmEnabled, recipeGenerateCloud, recipeGenerateLocal } from './recipe-llm.js';
import { parseRecipeObject } from './recipe-ai.js';
import { findOrCreateCategory } from './routes/recipe-categories.js';

async function existingNames(kind) {
  const { rows } = await query(
    `SELECT name FROM recipe_categories WHERE household_id=$1 AND kind=$2 ORDER BY lower(name)`,
    [HOUSEHOLD_ID, kind]
  );
  return rows.map((r) => r.name);
}

function buildPrompt(doc, types, countries) {
  const ingredients = (doc.ingredients || []).map((x) => x.food).filter(Boolean).join(', ');
  // Steps can be long; a handful of texts is plenty of signal for classification
  // and keeps the prompt small (unlike structuring, which needs every word).
  const steps = (doc.steps || []).slice(0, 8).map((x) => x.text).filter(Boolean).join(' ');
  return [
    'You sort a home recipe into a household\'s recipe catalogue, on two independent axes.',
    'Answer in Swedish, as STRICT JSON only: {"type":"...","country":"..."}',
    '"type" = the kind of dish (e.g. "Dessert", "Pasta", "Soppa", "Bakverk", "Sallad").',
    '"country" = the country/region of culinary origin (e.g. "Italien", "Thailand", "Sverige"),',
    'or null if the recipe has no distinct origin (e.g. a generic international dish).',
    'PREFER reusing one of these EXISTING values (case-insensitive) over inventing a new one —',
    'only propose something new if nothing existing genuinely fits. Keep any new value short',
    '(1-3 words).',
    `Existing type values: ${types.length ? types.join(', ') : '(none yet)'}`,
    `Existing country values: ${countries.length ? countries.join(', ') : '(none yet)'}`,
    '',
    `Recipe name: ${doc.name || ''}`,
    `Description: ${doc.description || ''}`,
    `Ingredients: ${ingredients}`,
    `Steps: ${steps}`,
  ].join('\n');
}

// Returns { typeCategoryId, countryCategoryId } (either may be null), or null
// if no LLM backend is available / the reply couldn't be parsed at all.
export async function categorizeRecipe(doc) {
  if (!recipeLlmEnabled()) return null;
  const [types, countries] = await Promise.all([existingNames('type'), existingNames('country')]);
  const prompt = buildPrompt(doc, types, countries);

  let reply = await recipeGenerateCloud(prompt, { timeoutMs: 30000 });
  if (reply === null) reply = await recipeGenerateLocal(prompt, { timeoutMs: 30000 });
  const obj = parseRecipeObject(reply);
  if (!obj) return null;

  const typeName = typeof obj.type === 'string' ? obj.type.trim() : '';
  const countryName = typeof obj.country === 'string' ? obj.country.trim() : '';
  const [typeCat, countryCat] = await Promise.all([
    typeName ? findOrCreateCategory('type', typeName) : Promise.resolve(null),
    countryName ? findOrCreateCategory('country', countryName) : Promise.resolve(null),
  ]);
  return {
    typeCategoryId: typeCat?.id || null,
    countryCategoryId: countryCat?.id || null,
  };
}
