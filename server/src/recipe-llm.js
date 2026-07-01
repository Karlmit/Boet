// Pluggable LLM backend for RECIPE parsing only (voice cleaning stays local).
//
// Boet's default is the household-local ollama — nothing leaves home. But recipe
// parsing on a CPU box is slow (a big recipe can take ~70s), so this optionally
// routes recipe LLM calls to NVIDIA's free NIM API (OpenAI-compatible, datacenter
// GPUs, ~seconds, 40 req/min free tier) when NVIDIA_API_KEY is set. This is the
// ONE place Boet may reach a third-party cloud, and only when the user opts in by
// providing a key; unset it and everything is local again.
//
// `nvidiaChat` below is the generic OpenAI-style chat-completions caller — it's
// exported so translate.js can point a SEPARATE model/endpoint at translation
// (e.g. a general-purpose model that translates recipe vocabulary better than a
// reasoning model tuned for structured extraction) without duplicating the
// request/response/reasoning-model handling.

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
const NVIDIA_THINKING = (process.env.NVIDIA_THINKING || 'off').toLowerCase() === 'on';

const nvidiaEnabled = () => Boolean(NVIDIA_API_KEY);
const isReasoningModel = (model) => /nemotron|reason|thinking|qwq|deepseek-?r/i.test(model || '');

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
  return nvidiaChat(prompt, {
    apiKey: NVIDIA_API_KEY, baseUrl: NVIDIA_BASE_URL, model: NVIDIA_MODEL,
    timeoutMs: timeoutMs || NVIDIA_TIMEOUT_MS, maxTokens: NVIDIA_MAX_TOKENS,
    temperature: NVIDIA_TEMPERATURE, thinking: NVIDIA_THINKING,
  });
}

// Generate via local ollama, regardless of whether NVIDIA is configured.
export async function recipeGenerateLocal(prompt, { timeoutMs } = {}) {
  if (!ollamaEnabled()) return null;
  return ollamaGenerate(prompt, { format: 'json', timeoutMs: timeoutMs || 100000, numCtx: 8192 });
}

// Some providers — notably OpenAI's newer constrained-sampling models (the
// gpt-5 line, o1/o3/…) — reject request params that NVIDIA NIM always accepts:
// `max_tokens` (wants `max_completion_tokens` instead) and a non-default
// `temperature` (only 1, the model's default, is accepted). Rather than guess
// by model name (fragile — providers change this over time, and it's not
// specific to any one model id), detect each SPECIFIC 400 error shape as it
// comes back and adjust the request body, retrying until it's accepted or an
// unrecognized error stops the loop. NVIDIA NIM never triggers either of these,
// so this is a no-op extra round-trip there, never the common case.
function adjustForError(body, errorText) {
  let err;
  try {
    err = JSON.parse(errorText)?.error;
  } catch {
    return null;
  }
  if (!err || err.type !== 'invalid_request_error') return null;
  if (err.param === 'max_tokens' && 'max_tokens' in body && /max_completion_tokens/i.test(err.message || '')) {
    const { max_tokens, ...rest } = body;
    return { next: { ...rest, max_completion_tokens: max_tokens }, note: 'max_tokens -> max_completion_tokens' };
  }
  if (err.param === 'temperature' && 'temperature' in body && /default/i.test(err.message || '')) {
    const { temperature, ...rest } = body;
    return { next: rest, note: 'dropping unsupported temperature (using model default)' };
  }
  return null;
}

async function chatOnce(body, { apiKey, baseUrl, timeoutMs }) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs || NVIDIA_TIMEOUT_MS);
  try {
    const res = await fetch(`${(baseUrl || NVIDIA_BASE_URL).replace(/\/$/, '')}/chat/completions`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${apiKey}`,
      },
      body: JSON.stringify(body),
      signal: controller.signal,
    });
    if (!res.ok) {
      const text = await res.text();
      return { ok: false, status: res.status, text };
    }
    return { ok: true, data: await res.json() };
  } catch (e) {
    return { ok: false, error: e };
  } finally {
    clearTimeout(timer);
  }
}

// Generic OpenAI-compatible chat-completions call against any NIM-style (or
// actual OpenAI) endpoint. Returns null on missing apiKey/model or any failure
// (network, timeout, non-2xx after adjustment retries exhausted, empty
// completion) — callers must have their own fallback. `thinking` only applies
// when `model` matches a known reasoning-model pattern; passed explicitly
// per-call (rather than read from env here) so a caller with its OWN
// model/config (see translate.js) controls it independently of the
// recipe-structuring config.
export async function nvidiaChat(prompt, { apiKey, baseUrl, model, timeoutMs, maxTokens, temperature, thinking } = {}) {
  if (!apiKey || !model) return null;
  const reasoning = isReasoningModel(model);
  let body = {
    model,
    messages: [{ role: 'user', content: prompt }],
    temperature: temperature ?? NVIDIA_TEMPERATURE,
    max_tokens: maxTokens || NVIDIA_MAX_TOKENS,
  };
  // Reasoning models: toggle thinking via the chat template. We don't force JSON
  // mode for them (they often reject it); callers that need JSON strip any
  // <think> block and extract the object themselves.
  if (reasoning) {
    body.chat_template_kwargs = { enable_thinking: thinking ?? false };
  }

  let result;
  const MAX_ATTEMPTS = 4; // generous headroom for more than one adjustment in sequence
  for (let attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
    result = await chatOnce(body, { apiKey, baseUrl, timeoutMs });
    if (result.ok || result.status !== 400) break;
    const adjustment = adjustForError(body, result.text);
    if (!adjustment) break;
    console.warn(`[boet] nvidia(${model}) adjusting request: ${adjustment.note}`);
    body = adjustment.next;
  }
  if (!result.ok) {
    if (result.error) console.warn(`[boet] nvidia(${model}) request failed: ${result.error?.message || result.error}`);
    else console.warn(`[boet] nvidia(${model}) ${result.status}: ${(result.text || '').slice(0, 200)}`);
    return null;
  }

  const choice = result.data?.choices?.[0] || {};
  const msg = choice.message || {};
  // Prefer the final answer; if a reasoning model put everything in the thinking
  // channel (empty content), fall back to reasoning_content.
  const out = (msg.content || msg.reasoning_content || '').trim();
  if (reasoning) {
    // Reasoning models can burn the token budget on <think>ing and leave the
    // real answer truncated — finish_reason "length" + a short/empty content is
    // the signature of that; this is the thing to check first if parsing fails.
    console.log(`[boet] nvidia(${model}) reply: finish=${choice.finish_reason} content_len=${(msg.content || '').length} reasoning_len=${(msg.reasoning_content || '').length}`);
  }
  return out || null;
}
