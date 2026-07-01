// Thin client for TheMealDB v2 (paid tier) — the "Discover" recipe-import source.
// Follows the same fetch+AbortController+env-gated-degrade convention as
// ollama.js/translate.js: any failure (timeout, non-2xx, bad JSON) yields
// null/[] rather than throwing, so a flaky third party never 500s the app.
// MEALDB_API_KEY defaults to TheMealDB's public test key ('1') so Discover still
// works (rate-limited, single-ingredient-filter only) with zero configuration;
// set a real paid key to unlock multi-ingredient filtering and the full catalogue.

const MEALDB_KEY = process.env.MEALDB_API_KEY || '1';
const MEALDB_BASE = `https://www.themealdb.com/api/json/v2/${MEALDB_KEY}`;
const MEALDB_TIMEOUT_MS = parseInt(process.env.MEALDB_TIMEOUT_MS || '10000', 10);
// Categories/areas/ingredients are near-static reference lists — cache them
// in-memory so the Discover browse chips don't re-hit MealDB on every open.
// Actual meal search/browse/random results are never cached (always live).
const REF_TTL_MS = parseInt(process.env.MEALDB_REF_TTL_MS || String(24 * 60 * 60 * 1000), 10);

async function mealdbGet(path) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), MEALDB_TIMEOUT_MS);
  try {
    const res = await fetch(`${MEALDB_BASE}/${path}`, { signal: controller.signal });
    if (!res.ok) {
      console.warn(`[boet] mealdb ${res.status}: ${path}`);
      return null;
    }
    return await res.json();
  } catch (e) {
    console.warn(`[boet] mealdb request failed (${path}): ${e?.message || e}`);
    return null;
  } finally {
    clearTimeout(timer);
  }
}

const refCache = new Map();
async function cached(key, loader) {
  const hit = refCache.get(key);
  if (hit && hit.expiresAt > Date.now()) return hit.value;
  const value = await loader();
  refCache.set(key, { value, expiresAt: Date.now() + REF_TTL_MS });
  return value;
}

export async function random() {
  const data = await mealdbGet('random.php');
  return data?.meals?.[0] || null;
}

export async function randomSelection() {
  const data = await mealdbGet('randomselection.php');
  return Array.isArray(data?.meals) ? data.meals : [];
}

export async function lookupMeal(id) {
  const data = await mealdbGet(`lookup.php?i=${encodeURIComponent(id)}`);
  return data?.meals?.[0] || null;
}

export async function searchByName(q) {
  const data = await mealdbGet(`search.php?s=${encodeURIComponent(q)}`);
  return Array.isArray(data?.meals) ? data.meals : [];
}

export async function searchByLetter(f) {
  const data = await mealdbGet(`search.php?f=${encodeURIComponent(f)}`);
  return Array.isArray(data?.meals) ? data.meals : [];
}

export async function filterByIngredients(arr) {
  const data = await mealdbGet(`filter.php?i=${arr.map(encodeURIComponent).join(',')}`);
  return Array.isArray(data?.meals) ? data.meals : [];
}

export async function filterByCategory(c) {
  const data = await mealdbGet(`filter.php?c=${encodeURIComponent(c)}`);
  return Array.isArray(data?.meals) ? data.meals : [];
}

export async function filterByArea(a) {
  const data = await mealdbGet(`filter.php?a=${encodeURIComponent(a)}`);
  return Array.isArray(data?.meals) ? data.meals : [];
}

export function listCategories() {
  return cached('categories', async () => {
    const data = await mealdbGet('categories.php');
    return Array.isArray(data?.categories)
      ? data.categories.map((c) => ({
          name: c.strCategory,
          thumb: c.strCategoryThumb || null,
          description: c.strCategoryDescription || null,
        }))
      : [];
  });
}

export function listAreas() {
  return cached('areas', async () => {
    const data = await mealdbGet('list.php?a=list');
    return Array.isArray(data?.meals) ? data.meals.map((a) => a.strArea).filter(Boolean) : [];
  });
}

export function listIngredients() {
  return cached('ingredients', async () => {
    const data = await mealdbGet('list.php?i=list');
    return Array.isArray(data?.meals)
      ? data.meals.map((i) => ({ name: i.strIngredient, description: i.strDescription || null }))
      : [];
  });
}

// --- Mapping helpers: keep MealDB's flat/quirky shape out of routes/recipe-ai ---

export function toSummary(m) {
  return { id: m.idMeal, name: m.strMeal, thumb: m.strMealThumb || null };
}

// {measure,food}[] from the flat strIngredient1..20/strMeasure1..20 fields,
// skipping the many blank/null slots past however many the recipe actually has.
function pairs(m) {
  const out = [];
  for (let i = 1; i <= 20; i++) {
    const food = (m[`strIngredient${i}`] || '').toString().trim();
    if (!food) continue;
    const measure = (m[`strMeasure${i}`] || '').toString().trim();
    out.push({ measure, food });
  }
  return out;
}

export function toDetail(m) {
  return {
    id: m.idMeal,
    name: m.strMeal || '',
    category: m.strCategory || null,
    area: m.strArea || null,
    thumb: m.strMealThumb || null,
    tags: (m.strTags || '').split(',').map((t) => t.trim()).filter(Boolean),
    youtube: m.strYoutube || null,
    instructions: m.strInstructions || '',
    ingredients: pairs(m).map((x) => ({ measure: x.measure, food: x.food })),
  };
}

// MealDB instructions are prose, usually one paragraph per step separated by
// \r\n, but not always cleanly enumerated. Split on blank lines first (the
// common case); only fall back to sentence-splitting a lone giant blob so a
// recipe never collapses into a single unreadable step.
export function splitInstructions(text) {
  const normalized = String(text || '').replace(/\r\n/g, '\n');
  let lines = normalized
    .split(/\n+/)
    .map((l) => l.trim())
    .map((l) => l.replace(/^\s*(?:step\s*)?\d+[.):]?\s*/i, '').trim())
    .filter(Boolean);
  if (lines.length <= 1 && (lines[0]?.length || 0) > 300) {
    lines = normalized
      .split(/(?<=[.!?])\s+(?=[A-Z])/)
      .map((l) => l.trim())
      .filter(Boolean);
  }
  return lines;
}

// The `ex` shape recipe-ai.js's structureFromEx() already consumes for
// JSON-sourced (Mealie/schema.org) recipes — MealDB has no sub-recipe grouping
// or phase headers, so those parallel arrays are all-null.
export function mealToEx(m) {
  const p = pairs(m);
  const stepLines = splitInstructions(m.strInstructions);
  return {
    name: m.strMeal || '',
    description: null,
    servings: null, // MealDB has no serving-count field anywhere
    ingredientLines: p.map((x) => [x.measure, x.food].filter(Boolean).join(' ')),
    ingredientSections: p.map(() => null),
    stepLines,
    stepTitles: stepLines.map(() => null),
  };
}

// No-LLM degraded RecipeDoc, analogous to recipe-ai.js's tryMealie(): used when
// no backend is configured, or as the last-resort raw fallback on total failure.
// image/sourceUrl are left null here — the import route in routes/discover.js
// owns setting those (uniformly for both this path and the AI-structured path).
export function mealToRawDoc(m) {
  const p = pairs(m);
  const stepLines = splitInstructions(m.strInstructions);
  return {
    name: m.strMeal || '',
    description: null,
    image: null,
    servings: null,
    totalTime: null,
    sourceUrl: null,
    ingredients: p.map((x, i) => ({
      id: `i${i + 1}`,
      quantity: null,
      unit: null,
      food: x.food,
      display: [x.measure, x.food].filter(Boolean).join(' '),
      note: null,
      sections: [],
    })),
    steps: stepLines.map((text, i) => ({
      id: `s${i + 1}`,
      text,
      ingredientRefs: [],
      timerSeconds: null,
      title: null,
    })),
  };
}
