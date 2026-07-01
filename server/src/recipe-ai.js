// AI recipe parsing — the multi-step pipeline validated in the spike:
//   1. LLM (qwen3:4b) extracts STRUCTURE in the recipe's ORIGINAL language
//      (ingredients with quantity/unit/food, steps with ingredient refs + timers).
//      Keeping it in-language avoids the model's poor translation.
//   2. Code converts units to Swedish metric (recipe-units.js) — never the LLM.
//   3. opus-mt translates the text fields EN->SV (translate.js) when the source
//      isn't already Swedish.
// Returns a RecipeDoc-shaped object (see Android RecipeDoc), or null if the local
// model is unavailable / the reply can't be parsed (caller falls back to manual).

import { recipeLlmEnabled, recipeGenerate, recipeUsingCloud, recipeLocalEnabled, recipeGenerateLocal } from './recipe-llm.js';
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

// Flatten schema.org recipeInstructions, which may be plain strings, HowToStep
// objects ({text}), or HowToSection objects ({itemListElement:[…]}).
function flattenSchemaInstructions(arr) {
  const out = [];
  for (const it of arr || []) {
    if (typeof it === 'string') out.push(it);
    else if (it && typeof it === 'object') {
      if (Array.isArray(it.itemListElement)) out.push(...flattenSchemaInstructions(it.itemListElement));
      else if (it.text) out.push(String(it.text));
      else if (it.name) out.push(String(it.name));
    }
  }
  return out.map((s) => s.trim()).filter(Boolean);
}

// If the pasted text is a recipe JSON (Mealie export OR a schema.org/Recipe
// JSON-LD blob, which is what most recipe sites embed), pull out ONLY the parts
// that matter — name, description, servings, ingredient lines, step texts — and
// return them structured. All the noise (ids, nutrition, timestamps, settings,
// org_url, images) is dropped. Returns null if it isn't recognizable recipe JSON.
function extractRecipeJson(raw) {
  const t = raw.trim();
  if (!(t.startsWith('{') || t.startsWith('['))) return null;
  let j;
  try { j = JSON.parse(t); } catch { return null; }
  // schema.org JSON-LD can be an array or wrap the recipe in @graph.
  const isRecipe = (x) => x && typeof x === 'object' &&
    (Array.isArray(x.recipe_ingredient) || Array.isArray(x.recipeIngredient) ||
     Array.isArray(x.recipe_instructions) || Array.isArray(x.recipeInstructions));
  if (Array.isArray(j)) j = j.find(isRecipe) || j[0];
  if (j && Array.isArray(j['@graph'])) j = j['@graph'].find(isRecipe) || j;
  if (!isRecipe(j)) return null;

  let ingredientLines = [];
  if (Array.isArray(j.recipe_ingredient)) {
    ingredientLines = j.recipe_ingredient
      .map((x) => x?.display || x?.note || composeDisplay(asNumber(x?.quantity), typeof x?.unit === 'string' ? x.unit : x?.unit?.name, typeof x?.food === 'string' ? x.food : x?.food?.name))
      .map((s) => String(s).trim()).filter(Boolean);
  } else if (Array.isArray(j.recipeIngredient)) {
    ingredientLines = j.recipeIngredient.map((x) => String(x).trim()).filter(Boolean);
  }

  let stepLines = [];
  if (Array.isArray(j.recipe_instructions)) {
    stepLines = j.recipe_instructions.map((s) => String(s?.text ?? '').trim()).filter(Boolean);
  } else if (Array.isArray(j.recipeInstructions)) {
    stepLines = flattenSchemaInstructions(j.recipeInstructions);
  } else if (typeof j.recipeInstructions === 'string') {
    stepLines = [j.recipeInstructions.trim()].filter(Boolean);
  }

  if (ingredientLines.length === 0 && stepLines.length === 0) return null;

  return {
    name: String(j.name ?? '').trim(),
    description: j.description ? String(j.description).trim() : null,
    servings: j.recipe_servings ?? j.recipeYield ?? j.servings ?? null,
    ingredientLines,
    stepLines,
  };
}

// Prompt for the pre-split (JSON) path: the model only TAGS the given lines — it
// parses each ingredient and, for each numbered step, names the ingredient ids
// used and any timer. Crucially it does NOT rewrite the step texts (we already
// have them), which roughly halves the output tokens — the real cost on a slow
// CPU box (the model streams ~11 tok/s, so re-emitting paragraphs is what blows
// the timeout).
function buildStructuredPrompt(ingredientLines, stepLines) {
  return [
    'Below is a recipe as an ingredient list and numbered steps.',
    'Return STRICT JSON. Do NOT translate. Do NOT convert units. Do NOT repeat the step texts.',
    'For each ingredient line parse {id,quantity,unit,food}: id = "i1","i2",… in order;',
    'quantity = the number or null; unit = the unit word exactly as written or null;',
    'food = the ingredient name only, without the amount.',
    'For each step number output {n,ingredientRefs,timerMinutes}: ingredientRefs = ids of the',
    'ingredients used in that step (may be empty); timerMinutes = minutes if the step states a',
    'duration, else null. Also include "lang" = the recipe language code ("en","sv",…).',
    'Output ONLY: {"lang":"en","ingredients":[{"id":"i1","quantity":1.5,"unit":"cups","food":"flour"}],"steps":[{"n":1,"ingredientRefs":["i1"],"timerMinutes":null}]}',
    '',
    'INGREDIENTS:',
    ...ingredientLines.map((l, i) => `${i + 1}. ${l}`),
    '',
    'STEPS:',
    ...stepLines.map((s, i) => `${i + 1}. ${s}`),
  ].join('\n');
}

// Render the extracted (structured, noise-free) recipe fields back into a plain
// text block for the full-context prompt below — used for JSON-sourced recipes
// when the backend is the fast cloud API, which doesn't need the token-lean
// pre-split/tagging prompt (that was built for a slow local CPU model).
function renderCleanedRecipeText(ex) {
  const lines = [];
  if (ex.name) lines.push(ex.name);
  if (ex.description) lines.push(ex.description);
  if (ex.servings) lines.push(`Servings: ${ex.servings}`);
  lines.push('', 'Ingredients:');
  ex.ingredientLines.forEach((l) => lines.push(`- ${l}`));
  lines.push('', 'Steps:');
  ex.stepLines.forEach((l, i) => lines.push(`${i + 1}. ${l}`));
  return lines.join('\n');
}

// Shared tail: translate (EN->SV, gated on the original paste), compose display
// lines, and assemble the RecipeDoc. Both the structured and plain-text paths end
// here so translation/gating logic lives in one place.
async function finalize({ name, description, servings, ingredients, steps, full, lang, onStatus }) {
  // Detect language on the ORIGINAL paste, never the model's `lang` (qwen echoes
  // the prompt example) nor our cleaned text (it carries Swedish scaffolding).
  const wantTranslate = translateEnabled() && !looksSwedish(full) &&
    (looksEnglish(full) || String(lang || '').startsWith('en'));
  if (wantTranslate) {
    onStatus?.('translating');
    const batch = [name || '', description || '', ...ingredients.map((x) => x.food), ...steps.map((x) => x.text)];
    const tr = await translateBatch(batch);
    let k = 0;
    name = tr[k++] || name;
    description = (tr[k++] || '') || description;
    ingredients.forEach((x) => { x.food = tr[k++] ?? x.food; });
    steps.forEach((x) => { x.text = tr[k++] ?? x.text; });
  }
  // Compose display AFTER translation so the food word is Swedish.
  ingredients.forEach((x) => { x.display = composeDisplay(x.quantity, x.unit, x.food); });
  return {
    name: name || '', description: description || null, image: null,
    servings, totalTime: null, sourceUrl: null, ingredients, steps,
  };
}

// Local-only JSON path: tag the pre-split lines (terse output), then pair the
// model's per-step refs/timers back onto OUR step texts by step number. This is
// the token-lean prompt built for a slow local CPU model; the cloud path below
// doesn't need it. Falls back to the direct structural map if the model is
// unavailable or returns nothing usable.
async function parseStructured(ex, full, onStatus) {
  onStatus?.('parsing_local');
  const reply = await recipeGenerate(buildStructuredPrompt(ex.ingredientLines, ex.stepLines), { timeoutMs: 100000 });
  const obj = parseRecipeObject(reply);
  const rawIng = obj && Array.isArray(obj.ingredients) ? obj.ingredients : [];
  if (rawIng.length === 0) {
    console.warn(`[boet] recipe parse: local model gave no usable ingredients (reply len=${reply?.length ?? 0}), falling back to raw Mealie map`);
    onStatus?.('degraded');
    return tryMealie(full);
  }

  const ingredients = rawIng.map((ing, i) => {
    const { quantity, unit } = convertUnit(asNumber(ing?.quantity), ing?.unit);
    return { id: String(ing?.id || `i${i + 1}`), quantity, unit: unit || null, food: String(ing?.food ?? '').trim(), note: null };
  });
  const validIds = new Set(ingredients.map((x) => x.id));

  // Model steps keyed by their number, paired onto our authoritative step texts.
  const byN = new Map();
  (Array.isArray(obj.steps) ? obj.steps : []).forEach((s) => {
    const n = asNumber(s?.n);
    if (n !== null) byN.set(Math.round(n), s);
  });
  const steps = ex.stepLines.map((text, i) => {
    const st = byN.get(i + 1);
    const minutes = asNumber(st?.timerMinutes);
    const refs = (Array.isArray(st?.ingredientRefs) ? st.ingredientRefs : [])
      .map((r) => String(r)).filter((r) => validIds.has(r));
    return { id: `s${i + 1}`, text: String(text).trim(), ingredientRefs: refs, timerSeconds: minutes !== null ? Math.round(minutes * 60) : null };
  }).filter((s) => s.text);

  return finalize({
    name: ex.name, description: ex.description, servings: asNumber(ex.servings),
    ingredients, steps, full, lang: String(obj.lang ?? '').trim().toLowerCase(), onStatus,
  });
}

// Cap the input the model sees. A real recipe's text fits easily; the cap stops a
// pathological paste (an entire web page) from running CPU inference past the
// request timeout. Kept under the model's context.
const MAX_INPUT_CHARS = 6000;

// Full-context path: ask the model to parse AND re-emit the whole recipe in one
// shot (buildPrompt). Used for every plain-text paste, and for JSON-sourced
// recipes when the cloud backend is serving — it isn't CPU/token constrained like
// the local model, so there's no need for the pre-split tagging prompt there.
// If the active backend is the cloud one and its reply doesn't parse into a usable
// recipe, retries once against local ollama (if configured) before giving up —
// a garbled-but-non-empty cloud reply would otherwise never reach that fallback,
// since recipeGenerate() only falls through to ollama on a hard network failure.
async function generateStructure(text, onStatus) {
  const capped = text.slice(0, MAX_INPUT_CHARS);
  const prompt = buildPrompt(capped);
  onStatus?.(recipeUsingCloud() ? 'parsing_cloud' : 'parsing_local');
  let reply = await recipeGenerate(prompt, { timeoutMs: 100000 });
  let obj = parseRecipeObject(reply);
  let usable = Boolean(obj && Array.isArray(obj.ingredients) && obj.ingredients.length > 0);
  if (!usable && recipeUsingCloud() && recipeLocalEnabled()) {
    console.warn(`[boet] recipe parse: cloud reply unusable (reply len=${reply?.length ?? 0}), retrying via local ollama`);
    onStatus?.('fallback_local');
    reply = await recipeGenerateLocal(prompt, { timeoutMs: 100000 });
    obj = parseRecipeObject(reply);
    usable = Boolean(obj && Array.isArray(obj.ingredients) && obj.ingredients.length > 0);
  }
  if (!usable) {
    console.warn(`[boet] recipe parse: no usable structure from any backend (last reply len=${reply?.length ?? 0})`);
    return null;
  }
  return obj;
}

// Build the ingredients/steps arrays out of a parsed model reply and hand off to
// finalize(). Shared by the plain-text and JSON+cloud paths in parseRecipeText.
function docFromObj(obj, { name, description, servings }, full, onStatus) {
  const rawIngredients = Array.isArray(obj.ingredients) ? obj.ingredients : [];
  const ingredients = rawIngredients.map((ing, i) => {
    const { quantity, unit } = convertUnit(asNumber(ing?.quantity), ing?.unit);
    return { id: String(ing?.id || `i${i + 1}`), quantity, unit: unit || null, food: String(ing?.food ?? '').trim(), note: ing?.note ? String(ing.note).trim() : null };
  });
  const validIds = new Set(ingredients.map((x) => x.id));

  const rawSteps = Array.isArray(obj.steps) ? obj.steps : [];
  const steps = rawSteps.map((st, i) => {
    const minutes = asNumber(st?.timerMinutes);
    const refs = (Array.isArray(st?.ingredientRefs) ? st.ingredientRefs : [])
      .map((r) => String(r)).filter((r) => validIds.has(r));
    return { id: `s${i + 1}`, text: String(st?.text ?? '').trim(), ingredientRefs: refs, timerSeconds: minutes !== null ? Math.round(minutes * 60) : null };
  }).filter((s) => s.text);

  return finalize({ name, description, servings, ingredients, steps, full, lang: String(obj.lang ?? '').trim().toLowerCase(), onStatus });
}

// `onStatus(status)` is called as parsing progresses so a caller can persist/
// broadcast live progress (see routes/recipes.js `/recipes/parse-async`):
// 'parsing_cloud' | 'parsing_local' | 'fallback_local' | 'translating' | 'degraded'.
// 'degraded' means both backends failed to produce usable structure and the raw
// (unlinked, free-text) Mealie fields were used as a last resort.
export async function parseRecipeText(text, { onStatus } = {}) {
  const full = String(text || '').trim();
  if (!full) return null;

  // Recipe JSON paste (Mealie export or a site's schema.org/Recipe JSON-LD)?
  // name/description/servings are already authoritative from the JSON — only
  // ingredients/steps need the model. Without any model, map Mealie directly.
  const ex = extractRecipeJson(full);
  if (ex) {
    if (!recipeLlmEnabled()) { onStatus?.('degraded'); return tryMealie(full); }
    if (recipeUsingCloud()) {
      const obj = await generateStructure(renderCleanedRecipeText(ex), onStatus);
      if (!obj) { onStatus?.('degraded'); return tryMealie(full); }
      return docFromObj(obj, { name: ex.name, description: ex.description, servings: asNumber(ex.servings) }, full, onStatus);
    }
    return parseStructured(ex, full, onStatus);
  }

  // Plain-text paste: the model has to split AND structure it, and we have no
  // authoritative name/servings/description to fall back on — no model means no
  // recipe (there's nothing sane to degrade to, unlike the JSON path above).
  if (!recipeLlmEnabled()) return null;
  const obj = await generateStructure(full, onStatus);
  if (!obj) return null;
  return docFromObj(obj, {
    name: String(obj.name ?? '').trim(),
    description: obj.description ? String(obj.description).trim() : null,
    servings: asNumber(obj.servings),
  }, full, onStatus);
}
