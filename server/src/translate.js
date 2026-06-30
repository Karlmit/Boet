// Thin client for the opus-mt translation sidecar (Helsinki-NLP/opus-mt-en-sv),
// served by the `translate` container (see server/translate/ + docker-compose).
// The spike showed opus-mt is far better at EN->SV than the general LLM, so the
// pipeline keeps the LLM for structure and uses this for the words.
//
// Disabled (returns input unchanged) unless TRANSLATE_URL is set, so the server
// still runs — recipes just stay in their parsed language — when no sidecar is
// wired up, matching the "degrade gracefully, no hard cloud dependency" rule.

const TRANSLATE_URL = (process.env.TRANSLATE_URL || '').replace(/\/$/, '');
const TRANSLATE_TIMEOUT_MS = parseInt(process.env.TRANSLATE_TIMEOUT_MS || '30000', 10);

export const translateEnabled = () => Boolean(TRANSLATE_URL);

// Translate a batch of strings EN->SV in one call (order preserved). On any
// failure returns the inputs unchanged so a recipe import never hard-fails on
// translation. Empty strings are passed through without a round-trip.
export async function translateBatch(texts) {
  const list = Array.isArray(texts) ? texts : [texts];
  if (!TRANSLATE_URL || list.length === 0) return list;

  // Only send non-empty strings; stitch the results back by index.
  const idx = [];
  const payload = [];
  list.forEach((t, i) => {
    if (typeof t === 'string' && t.trim()) { idx.push(i); payload.push(t); }
  });
  if (payload.length === 0) return list;

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
      return list;
    }
    const data = await res.json();
    const out = data?.translations;
    if (!Array.isArray(out) || out.length !== payload.length) return list;
    const result = [...list];
    idx.forEach((origIndex, j) => { result[origIndex] = out[j]; });
    return result;
  } catch (e) {
    console.warn(`[boet] translate request failed: ${e?.message || e}`);
    return list;
  } finally {
    clearTimeout(timer);
  }
}
