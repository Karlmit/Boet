// AI recipe parsing — the multi-step pipeline validated in the spike:
//   1. LLM (qwen3:4b) extracts STRUCTURE in the recipe's ORIGINAL language
//      (ingredients with quantity/unit/food, steps with ingredient refs + timers).
//      Keeping it in-language avoids the model's poor translation.
//   2. Code converts units to Swedish metric (recipe-units.js) — never the LLM.
//   3. opus-mt translates the text fields EN->SV (translate.js) when the source
//      isn't already Swedish.
// Returns a RecipeDoc-shaped object (see Android RecipeDoc), or null if the local
// model is unavailable / the reply can't be parsed (caller falls back to manual).

import { ollamaEnabled, ollamaGenerate } from './ollama.js';
import { convertUnit, formatQty } from './recipe-units.js';
import { translateEnabled, translateBatch } from './translate.js';

function buildPrompt(text) {
  return [
    'Extract the recipe below into STRICT JSON. Do NOT translate. Do NOT convert units.',
    'Keep all text in the original language and keep the original unit words.',
    'Give each ingredient an id "i1","i2",… For each step, ingredientRefs lists the ids',
    'of the ingredients used in that step (may be empty). If a step states a duration',
    '(e.g. "bake 20 minutes", "sjud 15 min"), set timerMinutes to that number, else null.',
    'quantity is a number (use null if none); unit is the original unit word or null;',
    'food is just the ingredient name without the amount.',
    'Output ONLY this JSON shape, nothing else:',
    '{"name":"","description":"","servings":4,"lang":"en",',
    ' "ingredients":[{"id":"i1","quantity":1.5,"unit":"cups","food":"flour"}],',
    ' "steps":[{"text":"","ingredientRefs":["i1"],"timerMinutes":null}]}',
    '',
    'RECIPE:',
    text,
  ].join('\n');
}

function safeParse(s) {
  try { return JSON.parse(s); } catch { return null; }
}

// Pull the JSON object out of the model reply, tolerant of <think> blocks,
// ```json fences, or stray prose around it.
function parseRecipeObject(text) {
  if (!text) return null;
  let t = text.replace(/<think>[\s\S]*?<\/think>/gi, '').trim();
  t = t.replace(/^```(?:json)?\s*/i, '').replace(/\s*```$/i, '').trim();
  const start = t.indexOf('{');
  const end = t.lastIndexOf('}');
  if (start < 0 || end <= start) return null;
  return safeParse(t.slice(start, end + 1));
}

function asNumber(v) {
  if (typeof v === 'number' && Number.isFinite(v)) return v;
  const n = parseFloat(String(v ?? '').replace(',', '.'));
  return Number.isFinite(n) ? n : null;
}

// Build the human-readable ingredient line from converted parts (Swedish).
function composeDisplay(quantity, unit, food) {
  return [formatQty(quantity), unit || '', food || ''].map((s) => String(s).trim()).filter(Boolean).join(' ');
}

export async function parseRecipeText(text) {
  const raw = String(text || '').trim();
  if (!raw || !ollamaEnabled()) return null;

  const reply = await ollamaGenerate(buildPrompt(raw), { format: 'json' });
  const obj = parseRecipeObject(reply);
  if (!obj || typeof obj !== 'object') return null;

  // --- ingredients: assign stable ids, convert units --------------------
  const rawIngredients = Array.isArray(obj.ingredients) ? obj.ingredients : [];
  const ingredients = rawIngredients.map((ing, i) => {
    const id = String(ing?.id || `i${i + 1}`);
    const { quantity, unit } = convertUnit(asNumber(ing?.quantity), ing?.unit);
    return {
      id,
      quantity,
      unit: unit || null,
      food: String(ing?.food ?? '').trim(),
      note: ing?.note ? String(ing.note).trim() : null,
    };
  });
  const validIds = new Set(ingredients.map((x) => x.id));

  // --- steps: keep only refs that point at a real ingredient ------------
  const rawSteps = Array.isArray(obj.steps) ? obj.steps : [];
  const steps = rawSteps.map((st, i) => {
    const minutes = asNumber(st?.timerMinutes);
    const refs = (Array.isArray(st?.ingredientRefs) ? st.ingredientRefs : [])
      .map((r) => String(r)).filter((r) => validIds.has(r));
    return {
      id: `s${i + 1}`,
      text: String(st?.text ?? '').trim(),
      ingredientRefs: refs,
      timerSeconds: minutes !== null ? Math.round(minutes * 60) : null,
    };
  }).filter((s) => s.text);

  let name = String(obj.name ?? '').trim();
  let description = obj.description ? String(obj.description).trim() : null;
  const servings = asNumber(obj.servings);
  const lang = String(obj.lang ?? '').trim().toLowerCase();

  // --- translation (EN->SV) only when needed ----------------------------
  if (lang && lang !== 'sv' && translateEnabled()) {
    // One batched call: [name, description, ...ingredient foods, ...step texts].
    const batch = [name, description || '', ...ingredients.map((x) => x.food), ...steps.map((x) => x.text)];
    const tr = await translateBatch(batch);
    let k = 0;
    name = tr[k++] ?? name;
    description = (tr[k++] || '') || description;
    ingredients.forEach((x) => { x.food = tr[k++] ?? x.food; });
    steps.forEach((x) => { x.text = tr[k++] ?? x.text; });
  }

  // Compose display lines AFTER translation so the food word is Swedish.
  ingredients.forEach((x) => { x.display = composeDisplay(x.quantity, x.unit, x.food); });

  return {
    name,
    description: description || null,
    image: null,
    servings,
    totalTime: null,
    sourceUrl: null,
    ingredients,
    steps,
  };
}
