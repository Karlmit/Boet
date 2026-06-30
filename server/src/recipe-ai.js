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

// Whether the source text is Swedish. opus-mt-en-sv hallucinates training-corpus
// garbage (EU/Europarl boilerplate) when fed non-English input, so translation
// must run ONLY on clearly-English text — never on the LLM's self-reported `lang`,
// which qwen tends to echo from the prompt example.
function looksSwedish(text) {
  const t = (text || '').toLowerCase();
  if (/[åäö]/.test(t)) return true;
  const sv = [' och ', ' med ', ' tills ', ' eller ', ' rör ', ' vispa', ' stek', ' koka',
    ' portioner', ' enligt ', ' samt ', ' under ', ' skala', ' tsk', ' msk', ' dl'];
  return sv.filter((w) => t.includes(w)).length >= 2;
}

function looksEnglish(text) {
  const t = (text || '').toLowerCase();
  const en = [' the ', ' and ', ' with ', ' until ', ' minutes', ' cups', ' cup ',
    ' tablespoon', ' teaspoon', ' bake', ' stir', ' add ', ' preheat', ' oven', ' heat '];
  return en.filter((w) => t.includes(w)).length >= 2;
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

// Fast path: the input is already a Mealie recipe export (the format Boet's own
// document derives from), so map it directly — no LLM, no translation, instant and
// exact. Returns a RecipeDoc, or null if the text isn't a Mealie recipe.
function tryMealie(raw) {
  if (!raw.includes('recipe_ingredient') && !raw.includes('recipe_instructions')) return null;
  let j;
  try { j = JSON.parse(raw); } catch { return null; }
  if (!j || typeof j !== 'object') return null;
  const rawIng = Array.isArray(j.recipe_ingredient) ? j.recipe_ingredient : [];
  const rawSteps = Array.isArray(j.recipe_instructions) ? j.recipe_instructions : [];
  if (rawIng.length === 0 && rawSteps.length === 0) return null;

  const ingredients = rawIng.map((x, i) => {
    const id = String(x?.reference_id || `i${i + 1}`);
    const q = asNumber(x?.quantity);
    const unit = typeof x?.unit === 'string' ? x.unit : (x?.unit?.abbreviation || x?.unit?.name || null);
    const food = typeof x?.food === 'string' ? x.food : (x?.food?.name || null);
    const display = String(x?.display || x?.note || food || '').trim();
    return {
      id,
      quantity: q && q > 0 ? q : null,
      unit: unit || null,
      food: food || display,
      display: display || composeDisplay(q && q > 0 ? q : null, unit, food),
      note: x?.note ? String(x.note).trim() : null,
    };
  });
  const validIds = new Set(ingredients.map((x) => x.id));

  const steps = rawSteps.map((s, i) => {
    const refs = (Array.isArray(s?.ingredient_references) ? s.ingredient_references : [])
      .map((r) => String(r?.reference_id ?? r)).filter((r) => validIds.has(r));
    return { id: String(s?.id || `s${i + 1}`), text: String(s?.text ?? '').trim(), ingredientRefs: refs, timerSeconds: null };
  }).filter((s) => s.text);

  return {
    name: String(j.name ?? '').trim(),
    description: j.description ? String(j.description).trim() : null,
    image: null,
    servings: asNumber(j.recipe_servings),
    totalTime: j.total_time ? String(j.total_time).trim() : null,
    sourceUrl: j.org_url ? String(j.org_url).trim() : null,
    ingredients,
    steps,
  };
}

// Cap the input the model sees. A real recipe's text fits easily; the cap stops a
// pathological paste (an entire web page, or a 5 KB structured-JSON export) from
// running CPU inference past the request timeout. Kept under the model's context.
const MAX_INPUT_CHARS = 6000;

export async function parseRecipeText(text) {
  const full = String(text || '').trim();
  if (!full) return null;

  // Already a Mealie export? Map it directly — instant and exact, no model needed.
  const mealie = tryMealie(full);
  if (mealie) return mealie;

  const raw = full.slice(0, MAX_INPUT_CHARS);
  if (!ollamaEnabled()) return null;

  // Recipe parsing is heavier than voice cleaning, so give it a bigger context and
  // a longer-but-bounded timeout. The bound stays under the app's 120s read timeout
  // (plus translation/network headroom) so a slow parse fails fast as a 503 instead
  // of the app hanging then erroring anyway.
  const reply = await ollamaGenerate(buildPrompt(raw), { format: 'json', timeoutMs: 90000, numCtx: 8192 });
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

  // --- translation (EN->SV) only for clearly-English source -------------
  // Gate on the raw text, not the model's `lang`: translate only when the source
  // is English-looking and NOT Swedish-looking, so a Swedish recipe is never fed
  // through the EN->SV model (which would hallucinate it into garbage).
  const wantTranslate = translateEnabled() && !looksSwedish(raw) &&
    (looksEnglish(raw) || lang.startsWith('en'));
  if (wantTranslate) {
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
