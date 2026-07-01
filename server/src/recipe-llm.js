// Pluggable LLM backend for RECIPE parsing only (voice cleaning stays local).
//
// Boet's default is the household-local ollama — nothing leaves home. But recipe
// parsing on a CPU box is slow (a big recipe can take ~70s), so this optionally
// routes recipe LLM calls to NVIDIA's free NIM API (OpenAI-compatible, datacenter
// GPUs, ~seconds, 40 req/min free tier) when NVIDIA_API_KEY is set. This is the
// ONE place Boet may reach a third-party cloud, and only when the user opts in by
// providing a key; unset it and everything is local again.

import { ollamaEnabled, ollamaGenerate, ollamaModel } from './ollama.js';

const NVIDIA_API_KEY = process.env.NVIDIA_API_KEY || '';
const NVIDIA_BASE_URL = (process.env.NVIDIA_BASE_URL || 'https://integrate.api.nvidia.com/v1').replace(/\/$/, '');
// A strong, free instruct model — plenty for structured recipe extraction. Override
// via env (any NIM model id, e.g. meta/llama-3.1-8b-instruct for lower latency).
const NVIDIA_MODEL = process.env.NVIDIA_MODEL || 'meta/llama-3.3-70b-instruct';
const NVIDIA_TIMEOUT_MS = parseInt(process.env.NVIDIA_TIMEOUT_MS || '60000', 10);

const nvidiaEnabled = () => Boolean(NVIDIA_API_KEY);

// True if any recipe LLM backend is available at all.
export const recipeLlmEnabled = () => nvidiaEnabled() || ollamaEnabled();

// Human-readable backend name for diagnostics / the parse response.
export const recipeLlmName = () =>
  nvidiaEnabled() ? `nvidia:${NVIDIA_MODEL}` : (ollamaEnabled() ? ollamaModel() : 'none');

// Generate a JSON reply for a recipe prompt. Prefers NVIDIA NIM when configured,
// else the local ollama. Returns the model's text, or null on any failure so the
// caller degrades (to the local model, or a structural fallback / 503).
export async function recipeGenerate(prompt, { timeoutMs } = {}) {
  if (nvidiaEnabled()) {
    const out = await nvidiaGenerate(prompt, timeoutMs || NVIDIA_TIMEOUT_MS);
    if (out) return out;
    // Cloud failed (rate limit, outage): fall through to local if we have it.
  }
  if (ollamaEnabled()) {
    return ollamaGenerate(prompt, { format: 'json', timeoutMs: timeoutMs || 100000, numCtx: 8192 });
  }
  return null;
}

async function nvidiaGenerate(prompt, timeoutMs) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const res = await fetch(`${NVIDIA_BASE_URL}/chat/completions`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${NVIDIA_API_KEY}`,
      },
      body: JSON.stringify({
        model: NVIDIA_MODEL,
        messages: [{ role: 'user', content: prompt }],
        temperature: 0.1,
        max_tokens: 4096,
        response_format: { type: 'json_object' }, // OpenAI-compatible JSON mode
      }),
      signal: controller.signal,
    });
    if (!res.ok) {
      console.warn(`[boet] nvidia ${res.status}: ${(await res.text()).slice(0, 200)}`);
      return null;
    }
    const data = await res.json();
    return (data?.choices?.[0]?.message?.content || '').trim() || null;
  } catch (e) {
    console.warn(`[boet] nvidia request failed: ${e?.message || e}`);
    return null;
  } finally {
    clearTimeout(timer);
  }
}
