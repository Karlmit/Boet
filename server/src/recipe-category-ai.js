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

// Recipe content comes FIRST and the existing-value lists LAST, right before
// the answer instruction, with an explicit "decide freely, THEN check for a
// match" framing and a concrete anti-example. This ordering matters a lot in
// practice: an earlier version put the existing lists first, which made
// smaller local models (qwen3:4b) anchor hard on them — e.g. classifying a
// Japanese sushi recipe as country "Mexiko" simply because Mexiko was the
// only existing country value, rather than inventing "Japan". Verified fixed
// against the real local model with this structure (sushi -> Japan/Nigirisushi,
// unrelated tacos still correctly reuses the existing Mexiko).
function buildPrompt(doc, types, countries) {
  const ingredients = (doc.ingredients || []).map((x) => x.food).filter(Boolean).join(', ');
  // Steps can be long; a handful of texts is plenty of signal for classification
  // and keeps the prompt small (unlike structuring, which needs every word).
  const steps = (doc.steps || []).slice(0, 8).map((x) => x.text).filter(Boolean).join(' ');
  return [
    `Recipe name: ${doc.name || ''}`,
    `Description: ${doc.description || ''}`,
    `Ingredients: ${ingredients}`,
    `Steps: ${steps}`,
    '',
    'Based ONLY on the recipe above, decide the TRUE food type (e.g. "Dessert", "Pasta",',
    '"Soppa", "Bakverk", "Sallad") and the TRUE country/region of culinary origin (or null if',
    'the recipe has no distinct origin, e.g. a generic international dish) — ignore the',
    'existing-value lists below while you decide this.',
    'Only AFTER deciding the true answer, check: does one of the existing values below mean the',
    'exact same thing (just possibly different spelling/case)? If yes, use that existing value\'s',
    'exact spelling. If no existing value matches, output your own true answer as a new short',
    'Swedish value (1-3 words) — inventing a new value is completely normal and expected',
    'whenever nothing existing genuinely fits; do NOT force-fit an existing value that is',
    'factually wrong for this recipe.',
    'Example: existing country values are just ["Mexiko"], and the recipe is Japanese sushi —',
    'the correct country is "Japan" (a brand-new value), NOT "Mexiko", because Mexiko does not',
    'match.',
    '',
    `Existing type values: ${types.length ? types.join(', ') : '(none yet)'}`,
    `Existing country values: ${countries.length ? countries.join(', ') : '(none yet)'}`,
    '',
    'Answer in Swedish, STRICT JSON only: {"type":"...","country":"..."}',
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
