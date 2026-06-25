// Thin client for the household's own local LLM, served by an Ollama container on
// the Unraid box. This keeps Boet's "depends on no third-party cloud" promise
// (PRODUCT.md): the only network hop is to OLLAMA_URL on the LAN — nothing leaves
// home. Disabled (returns null) unless OLLAMA_URL is set, so the server still runs
// fully deterministic when no model is wired up.

const OLLAMA_URL = (process.env.OLLAMA_URL || '').replace(/\/$/, '');
// Swedish-capable small instruct model; override via env without a code change.
const OLLAMA_MODEL = process.env.OLLAMA_MODEL || 'qwen3:4b-instruct';
// Local CPU inference can be slow; generous default, tunable per deployment.
const OLLAMA_TIMEOUT_MS = parseInt(process.env.OLLAMA_TIMEOUT_MS || '30000', 10);
// Context window. Our prompt + reply is a few hundred tokens, so the default is
// plenty; smaller = a little less KV-cache RAM. (Model weights dominate RAM use.)
const OLLAMA_NUM_CTX = parseInt(process.env.OLLAMA_NUM_CTX || '4096', 10);
// How long Ollama keeps the model resident after the last request. This is the
// real RAM/latency lever: "30m"/"-1" keeps it warm (snappy, ~2.5 GB held); "0"
// unloads immediately (frees RAM, cold-start on next use). Default: a short idle
// window — warm during a shopping burst, freed afterwards.
const OLLAMA_KEEP_ALIVE = process.env.OLLAMA_KEEP_ALIVE || '5m';

export const ollamaEnabled = () => Boolean(OLLAMA_URL);
export const ollamaModel = () => OLLAMA_MODEL;

// Single-shot generation. Returns the model's text, or null on any failure
// (unset URL, timeout, non-2xx, malformed body) so callers fall back gracefully.
export async function ollamaGenerate(prompt, { format } = {}) {
  if (!OLLAMA_URL) return null;
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), OLLAMA_TIMEOUT_MS);
  try {
    const res = await fetch(`${OLLAMA_URL}/api/generate`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        model: OLLAMA_MODEL,
        prompt,
        stream: false,
        format: format || undefined, // 'json' nudges the model to emit valid JSON
        keep_alive: OLLAMA_KEEP_ALIVE,
        // temperature low -> near-deterministic, which is what we want for
        // structured JSON output rather than creative text.
        options: { temperature: 0.1, num_ctx: OLLAMA_NUM_CTX },
      }),
      signal: controller.signal,
    });
    if (!res.ok) {
      console.warn(`[boet] ollama ${res.status}: ${(await res.text()).slice(0, 200)}`);
      return null;
    }
    const data = await res.json();
    const text = (data?.response || '').trim();
    return text || null;
  } catch (e) {
    console.warn(`[boet] ollama request failed: ${e?.message || e}`);
    return null;
  } finally {
    clearTimeout(timer);
  }
}
