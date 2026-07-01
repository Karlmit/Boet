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
// A strong, free NIM model. Override via env with any NIM model id — a lighter one
// (e.g. meta/llama-3.1-8b-instruct) for lower latency, or a reasoning model like
// nvidia/nemotron-3-ultra-550b-a55b for max quality.
const NVIDIA_MODEL = process.env.NVIDIA_MODEL || 'nvidia/nemotron-3-ultra-550b-a55b';
const NVIDIA_TIMEOUT_MS = parseInt(process.env.NVIDIA_TIMEOUT_MS || '90000', 10);
const NVIDIA_MAX_TOKENS = parseInt(process.env.NVIDIA_MAX_TOKENS || '8192', 10);
const NVIDIA_TEMPERATURE = parseFloat(process.env.NVIDIA_TEMPERATURE || '0.2');
// Reasoning models (nemotron, qwq, deepseek-r…) "think" before answering. For
// structured recipe extraction we don't need that, and leaving it on risks the
// reasoning eating the token budget (empty answer) and adds latency — so we turn
// thinking OFF for them by default. Set NVIDIA_THINKING=on to re-enable.
const REASONING_MODEL = /nemotron|reason|thinking|qwq|deepseek-?r/i.test(NVIDIA_MODEL);
const NVIDIA_THINKING = (process.env.NVIDIA_THINKING || 'off').toLowerCase() === 'on';

const nvidiaEnabled = () => Boolean(NVIDIA_API_KEY);

// True if any recipe LLM backend is available at all.
export const recipeLlmEnabled = () => nvidiaEnabled() || ollamaEnabled();

// Whether the cloud (NVIDIA) backend is the one serving requests. Callers use this
// to pick a prompt strategy: the cloud is fast + not CPU/token constrained, so it
// can take the full-context prompt; local ollama gets the token-lean one.
export const recipeUsingCloud = nvidiaEnabled;

// Whether the local ollama backend is available — the caller uses this to decide
// whether a cloud failure has anywhere to fall back to.
export const recipeLocalEnabled = ollamaEnabled;

// Human-readable backend name for diagnostics / the parse response.
export const recipeLlmName = () =>
  nvidiaEnabled() ? `nvidia:${NVIDIA_MODEL}` : (ollamaEnabled() ? ollamaModel() : 'none');

// Generate via NVIDIA only — no local fallback. Callers that need a fallback do
// it themselves (see recipe-ai.js), because the right fallback *strategy* differs
// by prompt: retrying the exact same heavy prompt against local ollama is not
// generally useful (it was already too slow/unreliable there, which is why the
// cloud path exists), so recipe-ai.js switches to a smaller-scoped local strategy
// instead of just replaying this one. Returns null if NVIDIA isn't configured or
// the call fails for any reason (network, timeout, non-2xx, empty completion).
export async function recipeGenerateCloud(prompt, { timeoutMs } = {}) {
  if (!nvidiaEnabled()) return null;
  return nvidiaGenerate(prompt, timeoutMs || NVIDIA_TIMEOUT_MS);
}

// Generate via local ollama, regardless of whether NVIDIA is configured.
export async function recipeGenerateLocal(prompt, { timeoutMs } = {}) {
  if (!ollamaEnabled()) return null;
  return ollamaGenerate(prompt, { format: 'json', timeoutMs: timeoutMs || 100000, numCtx: 8192 });
}

async function nvidiaGenerate(prompt, timeoutMs) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  const body = {
    model: NVIDIA_MODEL,
    messages: [{ role: 'user', content: prompt }],
    temperature: NVIDIA_TEMPERATURE,
    max_tokens: NVIDIA_MAX_TOKENS,
  };
  // Reasoning models: toggle thinking via the chat template. We don't force JSON
  // mode for them (they often reject it); the prompt asks for JSON and
  // parseRecipeObject strips any <think> block and extracts the object.
  if (REASONING_MODEL) {
    body.chat_template_kwargs = { enable_thinking: NVIDIA_THINKING };
  }
  try {
    const res = await fetch(`${NVIDIA_BASE_URL}/chat/completions`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${NVIDIA_API_KEY}`,
      },
      body: JSON.stringify(body),
      signal: controller.signal,
    });
    if (!res.ok) {
      console.warn(`[boet] nvidia ${res.status}: ${(await res.text()).slice(0, 200)}`);
      return null;
    }
    const data = await res.json();
    const choice = data?.choices?.[0] || {};
    const msg = choice.message || {};
    // Prefer the final answer; if a reasoning model put everything in the thinking
    // channel (empty content), fall back to reasoning_content.
    const out = (msg.content || msg.reasoning_content || '').trim();
    if (REASONING_MODEL) {
      // Reasoning models can burn the token budget on <think>ing and leave the
      // real answer truncated — finish_reason "length" + a short/empty content is
      // the signature of that; this is the thing to check first if parsing fails.
      console.log(`[boet] nvidia reply: finish=${choice.finish_reason} content_len=${(msg.content || '').length} reasoning_len=${(msg.reasoning_content || '').length}`);
    }
    return out || null;
  } catch (e) {
    console.warn(`[boet] nvidia request failed: ${e?.message || e}`);
    return null;
  } finally {
    clearTimeout(timer);
  }
}
