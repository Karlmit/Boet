// URL-scrape recipe import — Boet's own take on the same approach Mealie's
// scraper uses (extract schema.org/Recipe JSON-LD, which most modern recipe
// sites embed for Google's rich-result cards), plus a plain-text fallback for
// sites that have the content but not the markup, plus a headless-render tier
// for sites that only populate the recipe client-side via JS.
//
// Two-tier cascade, cheapest first:
//   1. Static fetch + cheerio. Look for JSON-LD Recipe -> feed it through
//      recipe-ai.js's own extractRecipeJson() (already understands schema.org
//      shape) to get an `ex`, so the existing structureFromEx() pipeline does
//      all the actual AI structuring/translation/unit-conversion — nothing
//      about that pipeline needs to change for this feature. No JSON-LD
//      Recipe? Try readable body text through the existing plain-text AI path
//      (parseRecipeText) instead of giving up.
//   2. Headless (Playwright) render, only if tier 1 found nothing usable — re-
//      runs the exact same extraction against the rendered DOM. Some sites
//      (WP Recipe Maker's lazy-load, full client-rendered SPAs) simply have no
//      recipe content in the static HTML at all.
//
// Every outbound request in both tiers is validated by ssrf-guard.js first —
// see that file's header comment for why.

import * as cheerio from 'cheerio';
import { assertUrlAllowed, SsrfBlockedError } from './ssrf-guard.js';
import { extractRecipeJson } from './recipe-ai.js';

const FETCH_TIMEOUT_MS = parseInt(process.env.SCRAPE_TIMEOUT_MS || '15000', 10);
const NAV_TIMEOUT_MS = parseInt(process.env.SCRAPE_NAV_TIMEOUT_MS || '20000', 10);
const MAX_BYTES = parseInt(process.env.SCRAPE_MAX_BYTES || String(5 * 1024 * 1024), 10);
const MAX_REDIRECTS = 5;
const MIN_TEXT_LEN = 400;
const USER_AGENT =
  'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36';

export class ScrapeError extends Error {}

// Canonical form used only for the recipes.source_key dedup key — not the
// value stored as the recipe's sourceUrl (that stays exactly what the user
// typed). Strips query/hash/trailing-slash and lowercases scheme+host so
// trivial variants (tracking params, http vs https-typo, trailing slash)
// dedupe to the same row.
export function normalizeUrl(inputUrl) {
  let u;
  try {
    u = new URL(inputUrl);
  } catch {
    throw new ScrapeError('invalid url');
  }
  if (u.protocol !== 'http:' && u.protocol !== 'https:') throw new ScrapeError('invalid url');
  const path = u.pathname.replace(/\/+$/, '') || '/';
  return `${u.protocol}//${u.hostname.toLowerCase()}${path}`;
}

async function fetchStatic(startUrl) {
  let url = startUrl;
  for (let hop = 0; hop <= MAX_REDIRECTS; hop++) {
    await assertUrlAllowed(url);
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), FETCH_TIMEOUT_MS);
    let res;
    try {
      res = await fetch(url, {
        redirect: 'manual',
        headers: { 'User-Agent': USER_AGENT, 'Accept-Language': 'sv,en;q=0.5' },
        signal: controller.signal,
      });
    } finally {
      clearTimeout(timer);
    }
    if (res.status >= 300 && res.status < 400 && res.headers.get('location')) {
      url = new URL(res.headers.get('location'), url).toString();
      continue;
    }
    if (!res.ok) throw new ScrapeError(`fetch failed: ${res.status}`);

    const reader = res.body?.getReader();
    if (!reader) return { html: await res.text(), finalUrl: url };
    const chunks = [];
    let total = 0;
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      total += value.length;
      if (total > MAX_BYTES) {
        await reader.cancel();
        throw new ScrapeError('response too large');
      }
      chunks.push(value);
    }
    const html = Buffer.concat(chunks.map((c) => Buffer.from(c))).toString('utf-8');
    return { html, finalUrl: url };
  }
  throw new ScrapeError('too many redirects');
}

// Playwright has no manual-redirect fetch equivalent, so route interception is
// how each hop (and every subresource, incidentally — harmless, just extra
// SSRF-safety) gets validated instead of only the initial navigation URL.
async function fetchHeadless(startUrl) {
  const { chromium } = await import('playwright');
  const browser = await chromium.launch({
    args: ['--no-sandbox', '--disable-setuid-sandbox', '--disable-dev-shm-usage'],
  });
  try {
    const context = await browser.newContext({ userAgent: USER_AGENT, locale: 'sv-SE' });
    const page = await context.newPage();
    await page.route('**/*', async (route) => {
      try {
        await assertUrlAllowed(route.request().url());
        await route.continue();
      } catch {
        await route.abort();
      }
    });
    await page.goto(startUrl, { waitUntil: 'networkidle', timeout: NAV_TIMEOUT_MS });
    const html = await page.content();
    return { html, finalUrl: page.url() };
  } finally {
    await browser.close();
  }
}

// --- JSON-LD extraction --------------------------------------------------

function typesOf(obj) {
  const t = obj?.['@type'];
  return Array.isArray(t) ? t : t ? [t] : [];
}

function findRecipe(node, seen = new Set()) {
  if (!node || typeof node !== 'object' || seen.has(node)) return null;
  seen.add(node);
  if (Array.isArray(node)) {
    for (const item of node) {
      const found = findRecipe(item, seen);
      if (found) return found;
    }
    return null;
  }
  if (typesOf(node).includes('Recipe')) return node;
  if (Array.isArray(node['@graph'])) {
    const found = findRecipe(node['@graph'], seen);
    if (found) return found;
  }
  return null;
}

function extractJsonLdRecipe($) {
  const scripts = $('script[type="application/ld+json"]');
  for (const el of scripts.toArray()) {
    let raw = $(el).contents().text() || '';
    raw = raw.replace(/^\s*\/\*<!\[CDATA\[\*\/|\/\*\]\]>\*\/\s*$/g, '').trim();
    if (!raw) continue;
    let j;
    try {
      j = JSON.parse(raw);
    } catch {
      continue;
    }
    const recipe = findRecipe(j);
    if (recipe) return recipe;
  }
  return null;
}

function extractReadableText($) {
  const $body = $('body').clone();
  $body.find('script,style,nav,header,footer,aside,form,noscript,template').remove();
  const text = $body.text().replace(/\s+/g, ' ').trim();
  const ogImage = $('meta[property="og:image"]').attr('content') || null;
  return { text, ogImage };
}

// schema.org `image`: string | string[] | ImageObject | ImageObject[].
function extractImage(recipe) {
  const img = recipe?.image;
  const candidates = Array.isArray(img) ? img : [img];
  for (const c of candidates) {
    if (typeof c === 'string' && c.trim()) return c.trim();
    if (c && typeof c === 'object' && typeof c.url === 'string' && c.url.trim()) return c.url.trim();
  }
  return null;
}

// --- ISO-8601 duration -> Swedish display string --------------------------

function isoDurationToMinutes(iso) {
  if (typeof iso !== 'string') return null;
  const m = iso.match(/^P(?:[\d.]+Y)?(?:[\d.]+M)?(?:[\d.]+D)?(?:T(?:([\d.]+)H)?(?:([\d.]+)M)?(?:[\d.]+S)?)?$/);
  if (!m) return null;
  const hours = parseFloat(m[1] || '0');
  const minutes = parseFloat(m[2] || '0');
  const total = Math.round(hours * 60 + minutes);
  return total > 0 ? total : null;
}

function formatDurationSv(totalMinutes) {
  if (!totalMinutes || totalMinutes <= 0) return null;
  const h = Math.floor(totalMinutes / 60);
  const m = totalMinutes % 60;
  if (h === 0) return `${m} min`;
  return m === 0 ? `${h} h` : `${h} h ${m} min`;
}

function extractTotalTime(recipe) {
  const direct = isoDurationToMinutes(recipe?.totalTime);
  if (direct !== null) return formatDurationSv(direct);
  const prep = isoDurationToMinutes(recipe?.prepTime) || 0;
  const cook = isoDurationToMinutes(recipe?.cookTime) || 0;
  return prep + cook > 0 ? formatDurationSv(prep + cook) : null;
}

// A schema.org `inLanguage` tag (e.g. "sv-SE", "en") is far more reliable than
// heuristically guessing from text — normalize to just the primary subtag so
// it matches structureFromEx's forceLang contract ("en"/"sv" prefix check).
function normalizeLang(inLanguage) {
  const s = Array.isArray(inLanguage) ? inLanguage[0] : inLanguage;
  if (typeof s !== 'string' || !s.trim()) return undefined;
  return s.trim().slice(0, 2).toLowerCase();
}

// Scrape-specific degraded-mode fallback (used as structureFromEx's
// rawFallback only if every AI backend is unavailable/fails) — mirrors
// mealdb.js's mealToRawDoc, built off the already-flattened `ex` fields since
// extractRecipeJson has already done the schema.org-shape flattening for us.
export function exToRawDoc(ex) {
  const ingredients = ex.ingredientLines.map((line, i) => ({
    id: `i${i + 1}`,
    quantity: null,
    unit: null,
    food: line,
    display: line,
    note: null,
    sections: ex.ingredientSections?.[i] ? [ex.ingredientSections[i]] : [],
  }));
  const steps = ex.stepLines.map((text, i) => ({
    id: `s${i + 1}`,
    text,
    ingredientRefs: [],
    timerSeconds: null,
    title: ex.stepTitles?.[i] || null,
  }));
  return {
    name: ex.name || '',
    description: ex.description || null,
    image: null,
    servings: ex.servings ?? null,
    totalTime: null,
    sourceUrl: null,
    ingredients,
    steps,
  };
}

export function extractFromHtml(html) {
  const $ = cheerio.load(html);
  const recipe = extractJsonLdRecipe($);
  if (recipe) {
    const ex = extractRecipeJson(JSON.stringify(recipe));
    if (ex) {
      return {
        kind: 'jsonld',
        ex,
        image: extractImage(recipe),
        totalTime: extractTotalTime(recipe),
        forceLang: normalizeLang(recipe.inLanguage),
      };
    }
  }
  const { text, ogImage } = extractReadableText($);
  if (text.length >= MIN_TEXT_LEN) {
    return { kind: 'text', text, image: ogImage };
  }
  return null;
}

// Returns {kind:'jsonld', ex, image, totalTime, forceLang} |
// {kind:'text', text, image} | null (nothing usable from either tier).
export async function scrapeUrl(inputUrl, { onStatus } = {}) {
  onStatus?.('fetching');
  await assertUrlAllowed(inputUrl); // fail fast before any work

  let staticResult = null;
  try {
    const { html } = await fetchStatic(inputUrl);
    staticResult = extractFromHtml(html);
  } catch (e) {
    if (e instanceof SsrfBlockedError) throw e;
    console.warn(`[boet] scrape: static fetch failed (${inputUrl}): ${e?.message || e}`);
  }
  if (staticResult) return staticResult;

  onStatus?.('fetching_headless');
  try {
    const { html } = await fetchHeadless(inputUrl);
    return extractFromHtml(html);
  } catch (e) {
    if (e instanceof SsrfBlockedError) throw e;
    console.warn(`[boet] scrape: headless fetch failed (${inputUrl}): ${e?.message || e}`);
    return null;
  }
}
