// Translation for AI recipe import. Two backends:
//   1. Preferred when configured: a cloud LLM via nvidiaChat (recipe-llm.js) —
//      its OWN independent endpoint/key/model config (TRANSLATE_LLM_*), separate
//      from the one used for recipe STRUCTURING (NVIDIA_*), since the best model
//      for extracting structure isn't necessarily the best at EN->SV translation.
//      Falls back to the structuring config's key/endpoint if the translate-
//      specific ones aren't set, but defaults to a different, non-reasoning model
//      (meta/llama-3.3-70b-instruct) — plain instruct models translate recipe
//      vocabulary noticeably better than the reasoning model used for structuring.
//   2. Fallback: the opus-mt-en-sv sidecar (server/translate/, TRANSLATE_URL) —
//      still useful when no cloud key is configured, or if the cloud reply can't
//      be parsed back into the exact same number of lines.
// Disabled (returns input unchanged) unless one of the two is available, so the
// server still runs — recipes just stay in their parsed language — when neither
// is wired up, matching the "degrade gracefully, no hard cloud dependency" rule.

import { nvidiaChat } from './recipe-llm.js';

const TRANSLATE_URL = (process.env.TRANSLATE_URL || '').replace(/\/$/, '');
const TRANSLATE_TIMEOUT_MS = parseInt(process.env.TRANSLATE_TIMEOUT_MS || '30000', 10);

// Falls back to the recipe-structuring NVIDIA key/endpoint (same account) if a
// translate-specific one isn't set, but the MODEL defaults independently — the
// point of this second config is to let translation use a different model.
const TRANSLATE_LLM_API_KEY = process.env.TRANSLATE_LLM_API_KEY || process.env.NVIDIA_API_KEY || '';
const TRANSLATE_LLM_BASE_URL = process.env.TRANSLATE_LLM_BASE_URL || process.env.NVIDIA_BASE_URL || 'https://integrate.api.nvidia.com/v1';
const TRANSLATE_LLM_MODEL = process.env.TRANSLATE_LLM_MODEL || 'meta/llama-3.3-70b-instruct';
const TRANSLATE_LLM_TIMEOUT_MS = parseInt(process.env.TRANSLATE_LLM_TIMEOUT_MS || '60000', 10);

const translateCloudEnabled = () => Boolean(TRANSLATE_LLM_API_KEY);
export const translateEnabled = () => translateCloudEnabled() || Boolean(TRANSLATE_URL);

// Cap how much text goes into one cloud translate call — a real recipe's fields
// fit easily; this just stops a pathological batch from producing a slow/huge
// prompt (falls back to opus-mt/no-op instead of trying anyway).
const MAX_CLOUD_CHARS = 8000;

function buildTranslatePrompt(lines) {
  return [
    'Translate the following numbered lines from English to Swedish. They are',
    "fields from a food recipe — the recipe's name/description, ingredient names,",
    'and step-by-step cooking instructions.',
    '',
    'Rules:',
    '- This is for a food recipe. Use natural Swedish cooking/culinary',
    '  terminology, not a literal dictionary translation.',
    '- Many English words have multiple meanings — always pick the food/cooking',
    '  sense. For example "lard" is a cooking fat and MUST be translated as',
    '  "ister", never any unrelated other meaning of the word.',
    '- If you are not confident of the correct Swedish culinary term for a word,',
    '  return that word unchanged in English rather than guessing.',
    '- Output ONLY the translated lines, in the same order, with the same',
    '  numbering, and exactly one line per input line — no explanations, no',
    '  extra lines, no blank lines, nothing else.',
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
  if (!translateCloudEnabled()) return null;
  // Recipe text rarely has embedded newlines by this point, but guard anyway —
  // one would otherwise break the numbered-line format this parser relies on.
  const lines = payload.map((t) => t.replace(/\s*\n+\s*/g, ' '));
  if (lines.join('\n').length > MAX_CLOUD_CHARS) return null;
  const reply = await nvidiaChat(buildTranslatePrompt(lines), {
    apiKey: TRANSLATE_LLM_API_KEY, baseUrl: TRANSLATE_LLM_BASE_URL, model: TRANSLATE_LLM_MODEL,
    timeoutMs: TRANSLATE_LLM_TIMEOUT_MS,
  });
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
