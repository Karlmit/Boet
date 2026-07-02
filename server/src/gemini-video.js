// Video-understanding fallback for Instagram Reel import (instagram-import.js)
// — used only when the Reel's caption alone isn't a complete recipe. Uploads
// the downloaded Reel video to Gemini's Files API, waits for it to finish
// server-side processing, then asks Gemini to extract structured recipe
// EVIDENCE (not a final Boet recipe — that's still `recipe-ai.js`'s job) as
// JSON. This is the one place Boet talks to Google's cloud, and only when
// GEMINI_API_KEY is set; unset it and this whole module is a no-op (mirrors
// recipe-llm.js's "no key -> null, caller degrades" convention).
//
// Uses the official `@google/genai` SDK rather than hand-rolling Gemini's
// resumable-upload protocol (unlike recipe-llm.js's nvidiaChat, which is a
// plain POST and easy to hand-roll — file upload is a meaningfully more
// complex protocol not worth reimplementing).

import { GoogleGenAI, createUserContent, createPartFromUri } from '@google/genai';

const GEMINI_API_KEY = process.env.GEMINI_API_KEY || '';
// "-latest" alias so this tracks Google's current default Flash model rather
// than a pinned version that later gets deprecated — model ids/availability/
// pricing drift over time (see docker-compose.yml comment), so this MUST stay
// configurable rather than hardcoded.
const GEMINI_RECIPE_VIDEO_MODEL = process.env.GEMINI_RECIPE_VIDEO_MODEL || 'gemini-flash-latest';
const REEL_VIDEO_TIMEOUT_SECONDS = parseInt(process.env.REEL_VIDEO_TIMEOUT_SECONDS || '120', 10);
const GEMINI_VIDEO_CONFIDENCE_THRESHOLD = parseFloat(process.env.GEMINI_VIDEO_CONFIDENCE_THRESHOLD || '0.65');

export const geminiVideoEnabled = () => Boolean(GEMINI_API_KEY);

const PROMPT = `You are extracting a cooking recipe from an Instagram Reel video.

Analyze the full video, including speech, on-screen text, visible
ingredients, actions, temperatures, timers, and the final result.

Return JSON only. Do not include markdown formatting or code fences.

Rules:
- Do not invent exact quantities.
- If a quantity is unclear or not shown, use null.
- If a step is inferred from visuals but not spoken/written, mark its
  evidence as "inferred".
- Preserve uncertainty — do not guess to fill gaps.
- Keep ingredient/step text in whatever language is spoken/shown in the
  video (do not translate — translation happens later in the pipeline).
- Include all evidence that would help another parser create a clean recipe.

Return exactly this JSON structure:
{
  "recipeDetected": true,
  "title": null,
  "description": null,
  "ingredients": [
    {"name": "", "quantity": null, "unit": null, "preparation": null,
     "evidence": "spoken | on_screen_text | visual | inferred", "confidence": 0.0}
  ],
  "steps": [
    {"order": 1, "text": "", "time": null, "temperature": null,
     "evidence": "spoken | on_screen_text | visual | inferred", "confidence": 0.0}
  ],
  "servings": null,
  "totalTime": null,
  "notes": [],
  "missing": [],
  "confidence": 0.0
}`;

function validateEvidence(obj) {
  if (!obj || obj.recipeDetected !== true) return false;
  if (!Array.isArray(obj.ingredients) || obj.ingredients.length === 0) return false;
  if (!Array.isArray(obj.steps) || obj.steps.length === 0) return false;
  if (!(Number(obj.confidence) >= GEMINI_VIDEO_CONFIDENCE_THRESHOLD)) return false;
  return true;
}

async function waitForActive(ai, name, deadline) {
  let file = await ai.files.get({ name });
  while (file.state === 'PROCESSING') {
    if (Date.now() > deadline) throw new Error('gemini file processing timed out');
    await new Promise((r) => setTimeout(r, 2000));
    file = await ai.files.get({ name });
  }
  return file;
}

// Uploads filePath to Gemini, waits for processing, asks for structured
// recipe evidence, validates it, and always cleans up the Gemini-side file.
// Returns the validated evidence object, or null on any failure (missing
// API key, upload/processing/generation failure, invalid/low-confidence
// result) — callers treat null exactly like "video analysis found nothing
// usable" and produce their own user-facing error.
export async function extractRecipeFromVideo(filePath, { timeoutMs } = {}) {
  if (!geminiVideoEnabled()) return null;
  const budget = timeoutMs || REEL_VIDEO_TIMEOUT_SECONDS * 1000;
  const deadline = Date.now() + budget;
  const ai = new GoogleGenAI({ apiKey: GEMINI_API_KEY });

  let uploaded;
  try {
    uploaded = await ai.files.upload({ file: filePath, config: { mimeType: 'video/mp4' } });
    const active = await waitForActive(ai, uploaded.name, deadline);
    if (active.state !== 'ACTIVE') {
      console.warn(`[boet] gemini-video: file ${uploaded.name} ended in state ${active.state}`);
      return null;
    }

    const controller = new AbortController();
    const remaining = Math.max(1000, deadline - Date.now());
    const timer = setTimeout(() => controller.abort(), remaining);
    let response;
    try {
      response = await ai.models.generateContent({
        model: GEMINI_RECIPE_VIDEO_MODEL,
        contents: createUserContent([createPartFromUri(active.uri, active.mimeType), PROMPT]),
        config: { responseMimeType: 'application/json', temperature: 0.2, abortSignal: controller.signal },
      });
    } finally {
      clearTimeout(timer);
    }

    const text = (response?.text || '').trim();
    if (!text) {
      console.warn('[boet] gemini-video: empty response text');
      return null;
    }
    let obj;
    try {
      obj = JSON.parse(text);
    } catch {
      console.warn(`[boet] gemini-video: non-JSON response (len=${text.length})`);
      return null;
    }
    if (!validateEvidence(obj)) {
      console.warn(`[boet] gemini-video: evidence failed validation (confidence=${obj?.confidence})`);
      return null;
    }
    return obj;
  } catch (e) {
    console.warn(`[boet] gemini-video: failed: ${e?.message || e}`);
    return null;
  } finally {
    if (uploaded?.name) {
      await ai.files.delete({ name: uploaded.name }).catch(() => {});
    }
  }
}
