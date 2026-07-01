// Translation for AI recipe import. Two backends:
//   1. Preferred when configured: the same cloud LLM used for recipe structuring
//      (recipe-llm.js / NVIDIA NIM) — translation-quality spot checks showed
//      opus-mt mistranslating ordinary recipe vocabulary (e.g. "sauerkraut",
//      "goulash") on real-world recipes; a large general model given explicit
//      recipe context does noticeably better.
//   2. Fallback: the opus-mt-en-sv sidecar (server/translate/, TRANSLATE_URL) —
//      still useful when no cloud key is configured, or if the cloud reply can't
//      be parsed back into the exact same number of lines.
// Disabled (returns input unchanged) unless one of the two is available, so the
// server still runs — recipes just stay in their parsed language — when neither
// is wired up, matching the "degrade gracefully, no hard cloud dependency" rule.

import { recipeUsingCloud, recipeGenerateCloud } from './recipe-llm.js';

const TRANSLATE_URL = (process.env.TRANSLATE_URL || '').replace(/\/$/, '');
const TRANSLATE_TIMEOUT_MS = parseInt(process.env.TRANSLATE_TIMEOUT_MS || '30000', 10);

export const translateEnabled = () => recipeUsingCloud() || Boolean(TRANSLATE_URL);

// Cap how much text goes into one cloud translate call — a real recipe's fields
// fit easily; this just stops a pathological batch from producing a slow/huge
// prompt (falls back to opus-mt/no-op instead of trying anyway).
const MAX_CLOUD_CHARS = 8000;

function buildTranslatePrompt(lines) {
  return [
    'Translate the following numbered lines from English to Swedish. They are',
    "fields from a food recipe — the recipe's name/description, ingredient names,",
    'and step-by-step cooking instructions. Translate them as a native Swedish',
    'home cook would write them (natural culinary vocabulary, correct units and',
    'cooking terms), not a literal word-for-word translation.',
    'Output ONLY the translated lines, in the same order, with the same numbering,',
    'and exactly one line per input line — no explanations, no extra lines, no',
    'blank lines, nothing else.',
    '',
    ...lines.map((t, i) => `${i + 1}. ${t}`),
  ].join('\n');
}

// Parse a numbered-line reply back into an array aligned to `expected` count.
// Returns null (caller falls back) unless every line came back non-empty — a
// partial/garbled reply must never be used, since a shifted index would silently
// mislabel an ingredient or step with the wrong translation.
function parseNumberedLines(reply, expected) {
  if (!reply) return null;
  const cleaned = reply.replace(/<think>[\s\S]*?<\/think>/gi, '').trim();
  const out = new Array(expected).fill(null);
  for (const raw of cleaned.split('\n')) {
    const line = raw.trim();
    if (!line) continue;
    const m = line.match(/^(\d+)[.):]?\s*(.*)$/);
    if (!m) continue;
    const n = parseInt(m[1], 10);
    if (n >= 1 && n <= expected && m[2].trim()) out[n - 1] = m[2].trim();
  }
  return out.every((x) => x !== null) ? out : null;
}

async function translateBatchCloud(payload) {
  if (!recipeUsingCloud()) return null;
  // Recipe text rarely has embedded newlines by this point, but guard anyway —
  // one would otherwise break the numbered-line format this parser relies on.
  const lines = payload.map((t) => t.replace(/\s*\n+\s*/g, ' '));
  if (lines.join('\n').length > MAX_CLOUD_CHARS) return null;
  const reply = await recipeGenerateCloud(buildTranslatePrompt(lines), { timeoutMs: 60000 });
  return parseNumberedLines(reply, lines.length);
}

async function translateBatchSidecar(payload) {
  if (!TRANSLATE_URL) return null;
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), TRANSLATE_TIMEOUT_MS);
  try {
    const res = await fetch(`${TRANSLATE_URL}/translate`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ texts: payload }),
      signal: controller.signal,
    });
    if (!res.ok) {
      console.warn(`[boet] translate ${res.status}`);
      return null;
    }
    const data = await res.json();
    const out = data?.translations;
    return Array.isArray(out) && out.length === payload.length ? out : null;
  } catch (e) {
    console.warn(`[boet] translate request failed: ${e?.message || e}`);
    return null;
  } finally {
    clearTimeout(timer);
  }
}

// Translate a batch of strings EN->SV in one call (order preserved). On any
// failure returns the inputs unchanged so a recipe import never hard-fails on
// translation. Empty strings are passed through without a round-trip.
export async function translateBatch(texts) {
  const list = Array.isArray(texts) ? texts : [texts];
  if (list.length === 0) return list;

  // Only send non-empty strings; stitch the results back by index.
  const idx = [];
  const payload = [];
  list.forEach((t, i) => {
    if (typeof t === 'string' && t.trim()) { idx.push(i); payload.push(t); }
  });
  if (payload.length === 0) return list;

  const out = (await translateBatchCloud(payload)) || (await translateBatchSidecar(payload));
  if (!out) return list;
  const result = [...list];
  idx.forEach((origIndex, j) => { result[origIndex] = out[j]; });
  return result;
}
