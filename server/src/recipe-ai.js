// AI recipe parsing — the multi-step pipeline validated in the spike:
//   1. LLM (qwen3:4b) extracts STRUCTURE in the recipe's ORIGINAL language
//      (ingredients with quantity/unit/food, steps with ingredient refs + timers).
//      Keeping it in-language avoids the model's poor translation.
//   2. Code converts units to Swedish metric (recipe-units.js) — never the LLM.
//   3. opus-mt translates the text fields EN->SV (translate.js) when the source
//      isn't already Swedish.
// Returns a RecipeDoc-shaped object (see Android RecipeDoc), or null if the local
// model is unavailable / the reply can't be parsed (caller falls back to manual).

import { recipeLlmEnabled, recipeGenerateCloud, recipeUsingCloud, recipeLocalEnabled, recipeGenerateLocal } from './recipe-llm.js';
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
    'Some recipes group ingredients into a named sub-component — a marinade, a sauce, a spice',
    'paste, a dressing, a filling, a topping. Watch for a LATER step that refers back to such a',
    'group by name even though no earlier step used that name (e.g. one step blends ginger,',
    'garlic, chili and oil with no name given, and a later step says "mix half the spice paste',
    'with the chicken" — the ginger/garlic/chili/oil ingredients belong to a "Kryddpasta"/"spice',
    'paste" tag because a later step named that mixture).',
    'Separately, some ingredients (often potatoes, rice, bread, a side salad) are only served',
    'ALONGSIDE the finished dish rather than cooked into it — tag those "Till servering"/"To',
    'serve" (in the recipe\'s language). Judge this ONLY from what the steps actually do with the',
    'ingredient, never from what it usually is: potato is NOT "Till servering" if a step grates,',
    'mixes, or bakes it into the dish itself (e.g. a potato pancake) — only tag it that way when a',
    'step clearly treats it as a separate accompaniment (e.g. "boil the potatoes and serve',
    'alongside").',
    'Give each such ingredient one or more short tags in "sections" (an array of short labels in',
    'the recipe\'s own language, e.g. ["Kryddpasta"] or ["Marinad","Sås"] for an ingredient used in',
    'both) — most ingredients get [] (no tag at all). Only tag ingredients that clearly belong to',
    'a named group; do not invent one just to have something to put there — most simple recipes',
    'have no such groups.',
    'Similarly, a recipe with distinct phases (e.g. marinate, make the sauce, assemble and',
    'serve) can have a short phase header on the FIRST step of a new phase — set "title" to a',
    'few words in the recipe\'s own language (e.g. "Gör såsen") on that one step only; every other',
    'step gets "title":null. Most recipes are simple enough that no step needs one at all — only',
    'add headers where the phase break is obvious, never one per step.',
    'Output ONLY this JSON shape, nothing else:',
    '{"name":"","description":"","servings":4,"lang":"en",',
    ' "ingredients":[{"id":"i1","quantity":1.5,"unit":"cups","food":"flour","sections":[]}],',
    ' "steps":[{"text":"","ingredientRefs":["i1"],"timerMinutes":null,"title":null}]}',
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
      // Mealie stores a sub-recipe grouping (e.g. "Marinad") as `title` on the
      // first ingredient of the group — carry it through untouched.
      sections: x?.title ? [String(x.title).trim()].filter(Boolean) : [],
    };
  });
  const validIds = new Set(ingredients.map((x) => x.id));

  const steps = rawSteps.map((s, i) => {
    const refs = (Array.isArray(s?.ingredient_references) ? s.ingredient_references : [])
      .map((r) => String(r?.reference_id ?? r)).filter((r) => validIds.has(r));
    // Mealie's own per-step title (a phase header shown above that step) —
    // carried through untouched.
    const title = s?.title ? String(s.title).trim() || null : null;
    return { id: String(s?.id || `s${i + 1}`), text: String(s?.text ?? '').trim(), ingredientRefs: refs, timerSeconds: null, title };
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
// objects ({text}), or HowToSection objects ({itemListElement:[…]}). A
// HowToSection's own `name` becomes a phase-header `title` on the first step
// inside it (schema.org's equivalent of Mealie's per-step `title`).
function flattenSchemaInstructions(arr) {
  const out = [];
  for (const it of arr || []) {
    if (typeof it === 'string') {
      out.push({ text: it, title: null });
    } else if (it && typeof it === 'object') {
      if (Array.isArray(it.itemListElement)) {
        const nested = flattenSchemaInstructions(it.itemListElement);
        if (nested.length > 0 && it.name) nested[0].title = String(it.name).trim() || null;
        out.push(...nested);
      } else if (it.text) {
        out.push({ text: String(it.text), title: null });
      } else if (it.name) {
        out.push({ text: String(it.name), title: null });
      }
    }
  }
  return out.map((x) => ({ text: x.text.trim(), title: x.title })).filter((x) => x.text);
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

  // Parallel to ingredientLines: a Mealie export's own sub-recipe grouping
  // (`title` on an ingredient), or null when unknown/not applicable
  // (schema.org's flat recipeIngredient strings never carry one). Trusted
  // as-is by parseLocalStepwise — no need to ask the AI to re-derive what the
  // source already told us.
  let ingredientLines = [];
  let ingredientSections = [];
  if (Array.isArray(j.recipe_ingredient)) {
    ingredientLines = j.recipe_ingredient
      .map((x) => x?.display || x?.note || composeDisplay(asNumber(x?.quantity), typeof x?.unit === 'string' ? x.unit : x?.unit?.name, typeof x?.food === 'string' ? x.food : x?.food?.name))
      .map((s) => String(s).trim());
    ingredientSections = j.recipe_ingredient.map((x) => (x?.title ? String(x.title).trim() || null : null));
    ingredientSections = ingredientSections.filter((_, i) => Boolean(ingredientLines[i]));
    ingredientLines = ingredientLines.filter(Boolean);
  } else if (Array.isArray(j.recipeIngredient)) {
    ingredientLines = j.recipeIngredient.map((x) => String(x).trim()).filter(Boolean);
    ingredientSections = ingredientLines.map(() => null);
  }

  // Parallel to stepLines: a Mealie export's per-step `title` or a schema.org
  // HowToSection's `name` (see flattenSchemaInstructions) — a phase header
  // shown above that step. Trusted as-is; the AI only fills gaps (see
  // parseLocalStepwise / buildLinkingPrompt).
  let stepLines = [];
  let stepTitles = [];
  if (Array.isArray(j.recipe_instructions)) {
    stepLines = j.recipe_instructions.map((s) => String(s?.text ?? '').trim());
    stepTitles = j.recipe_instructions.map((s) => (s?.title ? String(s.title).trim() || null : null));
    stepTitles = stepTitles.filter((_, i) => Boolean(stepLines[i]));
    stepLines = stepLines.filter(Boolean);
  } else if (Array.isArray(j.recipeInstructions)) {
    const flat = flattenSchemaInstructions(j.recipeInstructions);
    stepLines = flat.map((x) => x.text);
    stepTitles = flat.map((x) => x.title);
  } else if (typeof j.recipeInstructions === 'string') {
    stepLines = [j.recipeInstructions.trim()].filter(Boolean);
    stepTitles = stepLines.map(() => null);
  }

  if (ingredientLines.length === 0 && stepLines.length === 0) return null;

  return {
    name: String(j.name ?? '').trim(),
    description: j.description ? String(j.description).trim() : null,
    servings: j.recipe_servings ?? j.recipeYield ?? j.servings ?? null,
    ingredientLines,
    ingredientSections,
    stepLines,
    stepTitles,
  };
}

// Prompt for the local per-ingredient path (see parseLocalStepwise): parse ONE
// ingredient line at a time. A small CPU model (qwen3:4b) is far more reliable at
// this narrow, tiny-output task than at tagging a whole ingredient list (or a
// whole recipe) in one shot — asking for less per call means less for a small
// model to get wrong, at the cost of one call per ingredient instead of one call
// total (fine here since parsing runs in the background, see /recipes/parse-async).
function buildIngredientPrompt(line) {
  return [
    'Parse this single recipe ingredient line into STRICT JSON. Do NOT translate. Do NOT convert units.',
    'quantity is a number or null; unit is the original unit word exactly as written, or null;',
    'food is the ingredient name only, without the amount.',
    'Output ONLY: {"quantity":1.5,"unit":"cups","food":"flour"}',
    '',
    'LINE:', line,
  ].join('\n');
}

// Second (and last) local call: now that ingredients are already structured, link
// each step to the ingredient ids it uses and pull out any timer. Only needs to
// reason about linking, not also parse quantities, so it's a much narrower ask
// than the old single "tag everything" prompt. Also asks for tags (see
// buildPrompt's comment on named sub-components) for whichever ingredients don't
// already have one from the source (unknownSectionIds) — a Mealie export's own
// groups are trusted as-is and never re-asked about here.
function buildLinkingPrompt(ingredients, stepLines, unknownSectionIds, unknownTitleStepNumbers) {
  return [
    'Below are structured recipe ingredients and numbered steps.',
    'For each step number output {n,ingredientRefs,timerMinutes}: ingredientRefs = ids of the',
    'ingredients used in that step (may be empty); timerMinutes = minutes if the step states a',
    'duration (e.g. "10 minuter", "bake 20 minutes"), else null.',
    ...(unknownSectionIds.length > 0 ? [
      'Some recipes group ingredients into a named sub-component — a marinade, a sauce, a spice',
      'paste, a dressing, a filling, a topping. Watch for a LATER step that refers back to such a',
      'group by name even though no earlier step used that name (e.g. one step blends ginger,',
      'garlic, chili and oil with no name given, and a later step says "mix half the spice paste',
      'with the chicken" — those ingredients belong to a "Kryddpasta"/"spice paste" tag because a',
      'later step named that mixture).',
      'Separately, some ingredients (often potatoes, rice, bread, a side salad) are only served',
      'ALONGSIDE the finished dish rather than cooked into it — tag those "Till servering"/"To',
      'serve". Judge this ONLY from what the steps actually do with the ingredient, never from',
      'what it usually is: potato is NOT "Till servering" if a step grates, mixes, or bakes it',
      'into the dish itself (e.g. a potato pancake) — only tag it that way when a step clearly',
      'treats it as a separate accompaniment.',
      `For ingredient ids ${unknownSectionIds.join(', ')} specifically, if you can tell they`,
      'belong to such a named group, output "sections": a list of {id,tags} where tags is an',
      'array of short labels in the recipe\'s own language (usually just one, e.g. ["Kryddpasta"])',
      '— an ingredient can have more than one. Leave an id out of "sections" entirely if it has no',
      'clear group; most ingredients will have none.',
    ] : []),
    ...(unknownTitleStepNumbers.length > 0 ? [
      'Separately, a recipe with distinct phases (e.g. marinate, make the sauce, assemble and',
      'serve) can have a short phase header on the FIRST step of a new phase — a few words in the',
      `recipe's own language (e.g. "Gör såsen"). For step numbers ${unknownTitleStepNumbers.join(', ')}`,
      'specifically, if a step clearly starts a new phase, output "stepTitles": a list of {n,title}.',
      'Leave a step number out entirely if it doesn\'t start a new phase — most steps won\'t, and',
      'most simple recipes need no headers at all.',
    ] : []),
    'Output ONLY: {"steps":[{"n":1,"ingredientRefs":["i1"],"timerMinutes":null}],"sections":[{"id":"i1","tags":["Marinad"]}],"stepTitles":[{"n":3,"title":"Gör såsen"}]}',
    '',
    'INGREDIENTS:',
    ...ingredients.map((x) => `${x.id}: ${composeDisplay(x.quantity, x.unit, x.food)}`),
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
async function finalize({ name, description, servings, ingredients, steps, full, lang, forceLang, onStatus }) {
  // Detect language on the ORIGINAL paste, never the model's `lang` (qwen echoes
  // the prompt example) nor our cleaned text (it carries Swedish scaffolding).
  // A caller that already KNOWS the source language (e.g. MealDB imports, always
  // English) can pass forceLang to bypass these paste-oriented heuristics entirely.
  const wantTranslate = translateEnabled() && (
    forceLang
      ? String(forceLang).startsWith('en')
      : (!looksSwedish(full) && (looksEnglish(full) || String(lang || '').startsWith('en')))
  );
  if (wantTranslate) {
    onStatus?.('translating');
    const batch = [
      name || '', description || '',
      ...ingredients.map((x) => x.food), ...steps.map((x) => x.text),
      // Flattened so each ingredient's tags translate 1:1, however many it has.
      ...ingredients.flatMap((x) => x.sections || []),
      ...steps.map((x) => x.title || ''),
    ];
    const tr = await translateBatch(batch);
    let k = 0;
    name = tr[k++] || name;
    description = (tr[k++] || '') || description;
    ingredients.forEach((x) => { x.food = tr[k++] ?? x.food; });
    steps.forEach((x) => { x.text = tr[k++] ?? x.text; });
    ingredients.forEach((x) => { x.sections = (x.sections || []).map((orig) => tr[k++] || orig); });
    steps.forEach((x) => { const t = tr[k++]; if (x.title && t) x.title = t; });
  }
  // Compose display AFTER translation so the food word is Swedish.
  ingredients.forEach((x) => { x.display = composeDisplay(x.quantity, x.unit, x.food); });
  return {
    name: name || '', description: description || null, image: null,
    servings, totalTime: null, sourceUrl: null, ingredients, steps,
  };
}

// Local-only JSON path — used both when there's no cloud backend at all, and as
// the fallback once the cloud attempt fails (see parseRecipeText below). Rather
// than replaying the same heavy full-context prompt against a small CPU model (an
// earlier version of this code did exactly that, which meant a cloud failure was
// followed by ANOTHER ~100s local timeout on a prompt already too big for it —
// two multi-minute waits back to back), this parses each ingredient with its own
// tiny prompt, then makes ONE follow-up call to link steps to the now-structured
// ingredients and pull out timers. Degrades to the raw Mealie map only if the
// local model is unreachable entirely (checked via the very first call, so a dead
// backend fails in one short timeout instead of one per ingredient).
async function parseLocalStepwise(ex, full, onStatus, { rawFallback, forceLang } = {}) {
  const fallback = rawFallback || (() => tryMealie(full));
  if (!recipeLocalEnabled()) { onStatus?.('degraded'); return fallback(); }
  onStatus?.('parsing_local');

  const ingredients = [];
  for (let i = 0; i < ex.ingredientLines.length; i++) {
    const line = ex.ingredientLines[i];
    const reply = await recipeGenerateLocal(buildIngredientPrompt(line), { timeoutMs: 30000 });
    if (reply === null && i === 0) {
      console.warn('[boet] recipe parse: local model unreachable, falling back to raw map');
      onStatus?.('degraded');
      return fallback();
    }
    const obj = parseRecipeObject(reply);
    const { quantity, unit } = convertUnit(asNumber(obj?.quantity), obj?.unit);
    const food = String(obj?.food ?? '').trim();
    // Trust a Mealie export's own grouping where it exists; only ask the AI
    // (below) to infer one for ingredients the source didn't already group.
    const mealieTag = ex.ingredientSections?.[i];
    const sections = mealieTag ? [mealieTag] : [];
    ingredients.push({ id: `i${i + 1}`, quantity, unit: unit || null, food: food || line, note: null, sections });
  }
  const validIds = new Set(ingredients.map((x) => x.id));
  const unknownSectionIds = ingredients.filter((x) => x.sections.length === 0).map((x) => x.id);
  // Steps whose title isn't already known from the source (see extractRecipeJson's
  // stepTitles) — only these are offered to the AI for phase-header inference.
  const unknownTitleStepNumbers = ex.stepLines
    .map((_, i) => (ex.stepTitles?.[i] ? null : i + 1))
    .filter((n) => n !== null);

  onStatus?.('linking_steps');
  const reply = await recipeGenerateLocal(
    buildLinkingPrompt(ingredients, ex.stepLines, unknownSectionIds, unknownTitleStepNumbers),
    { timeoutMs: 60000 },
  );
  const obj = parseRecipeObject(reply);
  if (!obj) console.warn('[boet] recipe parse: step-linking call gave nothing usable — ingredients are structured but steps will have no links/timers');
  const tagsById = new Map();
  (Array.isArray(obj?.sections) ? obj.sections : []).forEach((s) => {
    const id = String(s?.id ?? '');
    const tags = (Array.isArray(s?.tags) ? s.tags : []).map((t) => String(t).trim()).filter(Boolean);
    if (id && tags.length > 0) tagsById.set(id, tags);
  });
  ingredients.forEach((x) => { if (x.sections.length === 0) x.sections = tagsById.get(x.id) || []; });
  const byN = new Map();
  (Array.isArray(obj?.steps) ? obj.steps : []).forEach((s) => {
    const n = asNumber(s?.n);
    if (n !== null) byN.set(Math.round(n), s);
  });
  const titleByN = new Map();
  (Array.isArray(obj?.stepTitles) ? obj.stepTitles : []).forEach((s) => {
    const n = asNumber(s?.n);
    const title = typeof s?.title === 'string' ? s.title.trim() : '';
    if (n !== null && title) titleByN.set(Math.round(n), title);
  });
  const steps = ex.stepLines.map((text, i) => {
    const st = byN.get(i + 1);
    const minutes = asNumber(st?.timerMinutes);
    const refs = (Array.isArray(st?.ingredientRefs) ? st.ingredientRefs : [])
      .map((r) => String(r)).filter((r) => validIds.has(r));
    const title = ex.stepTitles?.[i] || titleByN.get(i + 1) || null;
    return { id: `s${i + 1}`, text: String(text).trim(), ingredientRefs: refs, timerSeconds: minutes !== null ? Math.round(minutes * 60) : null, title };
  }).filter((s) => s.text);

  return finalize({
    name: ex.name, description: ex.description, servings: asNumber(ex.servings),
    ingredients, steps, full, lang: '', forceLang, onStatus,
  });
}

// Cap the input the model sees. A real recipe's text fits easily; the cap stops a
// pathological paste (an entire web page) from running CPU inference past the
// request timeout. Kept under the model's context.
const MAX_INPUT_CHARS = 6000;

// Cloud-only structuring attempt (full-context prompt, buildPrompt). Used for
// JSON-sourced recipes when NVIDIA is configured. Deliberately has NO local
// fallback inside it: on failure, parseRecipeText below switches to
// parseLocalStepwise's per-ingredient strategy rather than replaying this same
// heavy prompt against local ollama (which is what used to happen — see that
// function's comment for why that was a real, user-visible bug).
async function generateStructureCloud(text, onStatus) {
  onStatus?.('parsing_cloud');
  const capped = text.slice(0, MAX_INPUT_CHARS);
  const reply = await recipeGenerateCloud(buildPrompt(capped), { timeoutMs: 100000 });
  const obj = parseRecipeObject(reply);
  const usable = Boolean(obj && Array.isArray(obj.ingredients) && obj.ingredients.length > 0);
  if (!usable) {
    console.warn(`[boet] recipe parse: cloud reply unusable (reply len=${reply?.length ?? 0})`);
    return null;
  }
  return obj;
}

// Full-context path for a plain-text paste. There's no pre-split ingredient list
// to fall back to a per-ingredient strategy with here, so a single local retry of
// the SAME prompt is the best available fallback — but only one retry: this used
// to layer on top of recipeGenerate()'s own internal cloud->local fallback,
// so a cloud failure could mean local ollama got the same ~100s-timeout prompt
// TWICE back to back before giving up (up to ~5 minutes of waiting).
async function generateStructurePlainText(text, onStatus) {
  const capped = text.slice(0, MAX_INPUT_CHARS);
  const prompt = buildPrompt(capped);
  onStatus?.(recipeUsingCloud() ? 'parsing_cloud' : 'parsing_local');
  let reply = recipeUsingCloud()
    ? await recipeGenerateCloud(prompt, { timeoutMs: 100000 })
    : await recipeGenerateLocal(prompt, { timeoutMs: 100000 });
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
function docFromObj(obj, { name, description, servings }, full, onStatus, forceLang) {
  const rawIngredients = Array.isArray(obj.ingredients) ? obj.ingredients : [];
  const ingredients = rawIngredients.map((ing, i) => {
    const { quantity, unit } = convertUnit(asNumber(ing?.quantity), ing?.unit);
    const sections = (Array.isArray(ing?.sections) ? ing.sections : []).map((t) => String(t).trim()).filter(Boolean);
    return { id: String(ing?.id || `i${i + 1}`), quantity, unit: unit || null, food: String(ing?.food ?? '').trim(), note: ing?.note ? String(ing.note).trim() : null, sections };
  });
  const validIds = new Set(ingredients.map((x) => x.id));

  const rawSteps = Array.isArray(obj.steps) ? obj.steps : [];
  const steps = rawSteps.map((st, i) => {
    const minutes = asNumber(st?.timerMinutes);
    const refs = (Array.isArray(st?.ingredientRefs) ? st.ingredientRefs : [])
      .map((r) => String(r)).filter((r) => validIds.has(r));
    const title = typeof st?.title === 'string' ? st.title.trim() || null : null;
    return { id: `s${i + 1}`, text: String(st?.text ?? '').trim(), ingredientRefs: refs, timerSeconds: minutes !== null ? Math.round(minutes * 60) : null, title };
  }).filter((s) => s.text);

  return finalize({ name, description, servings, ingredients, steps, full, lang: String(obj.lang ?? '').trim().toLowerCase(), forceLang, onStatus });
}

// Shared entry point for any recipe that's already split into an `ex` shape
// ({name, description, servings, ingredientLines, ingredientSections, stepLines,
// stepTitles}) — used both for a pasted Mealie/schema.org JSON recipe (via
// parseRecipeText below, rawFallback defaulting to tryMealie) and for a MealDB
// import (routes/discover.js, which passes its own rawFallback + forceLang:'en'
// since the source is known-English rather than guessed from pasted text).
export async function structureFromEx(ex, { full = '', rawFallback, forceLang, onStatus } = {}) {
  const fallback = rawFallback || (() => tryMealie(full));
  if (!recipeLlmEnabled()) { onStatus?.('degraded'); return fallback(); }
  if (recipeUsingCloud()) {
    const obj = await generateStructureCloud(renderCleanedRecipeText(ex), onStatus);
    if (obj) {
      return docFromObj(
        obj, { name: ex.name, description: ex.description, servings: asNumber(ex.servings) },
        full, onStatus, forceLang,
      );
    }
    // Cloud failed — switch strategy (per-ingredient local calls) rather than
    // replaying the same heavy prompt against local ollama.
    if (!recipeLocalEnabled()) { onStatus?.('degraded'); return fallback(); }
    onStatus?.('fallback_local');
  }
  return parseLocalStepwise(ex, full, onStatus, { rawFallback: fallback, forceLang });
}

// `onStatus(status)` is called as parsing progresses so a caller can persist/
// broadcast live progress (see routes/recipes.js `/recipes/parse-async`):
// 'parsing_cloud' | 'fallback_local' | 'parsing_local' | 'linking_steps' |
// 'translating' | 'degraded'. 'degraded' means no backend could produce usable
// structure and the raw (unlinked, free-text) Mealie fields were used as-is.
export async function parseRecipeText(text, { onStatus } = {}) {
  const full = String(text || '').trim();
  if (!full) return null;

  // Recipe JSON paste (Mealie export or a site's schema.org/Recipe JSON-LD)?
  // name/description/servings are already authoritative from the JSON — only
  // ingredients/steps need the model. Without any model, map Mealie directly.
  const ex = extractRecipeJson(full);
  if (ex) return structureFromEx(ex, { full, onStatus });

  // Plain-text paste: the model has to split AND structure it, and we have no
  // authoritative name/servings/description to fall back on — no model means no
  // recipe (there's nothing sane to degrade to, unlike the JSON path above).
  if (!recipeLlmEnabled()) return null;
  const obj = await generateStructurePlainText(full, onStatus);
  if (!obj) return null;
  return docFromObj(obj, {
    name: String(obj.name ?? '').trim(),
    description: obj.description ? String(obj.description).trim() : null,
    servings: asNumber(obj.servings),
  }, full, onStatus);
}
