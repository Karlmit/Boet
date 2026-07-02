// Instagram Reel import orchestration (routes/instagram.js). Kept separate
// from recipe-ai.js, which stays scoped to "structure already-known recipe
// text" — this module owns everything Instagram-specific (fetching a Reel's
// caption/video, deciding whether the caption alone is enough) and ends by
// calling the existing, unmodified parseRecipeText() to do the actual
// structuring/translation/unit-conversion, exactly like a pasted-text import.
//
// Fetching: Instagram blocks essentially all unauthenticated scraping as of
// 2026 (confirmed empirically — even the public embed page redirects to a
// login wall), so this uses the Apify "Instagram Reel Scraper" actor
// (https://apify.com/apify/instagram-reel-scraper) via a plain REST call
// rather than self-hosting a scraper — Apify's own account/proxy pool does
// the authentication problem for us. Only active when APIFY_API_TOKEN is
// set; unset it and Instagram import fails cleanly with a Swedish
// "not configured" style error, same convention as recipe-llm.js's
// "no key -> null, caller degrades" pattern.
//
// The actor occasionally returns a degraded "restricted_page" result
// (caption/thumbnail only, no video) for an otherwise-normal public Reel —
// observed empirically to usually resolve on an immediate retry, so one
// retry is built in before giving up on the video.

import { promises as fs } from 'node:fs';
import path from 'node:path';
import { Readable } from 'node:stream';
import { nanoid } from 'nanoid';
import { recipeGenerateCloud, recipeGenerateLocal, recipeUsingCloud, recipeLlmEnabled } from './recipe-llm.js';
import { parseRecipeText } from './recipe-ai.js';
import { extractRecipeFromVideo } from './gemini-video.js';

const APIFY_API_TOKEN = process.env.APIFY_API_TOKEN || '';
const APIFY_INSTAGRAM_ACTOR = process.env.APIFY_INSTAGRAM_ACTOR || 'apify~instagram-reel-scraper';
const APIFY_TIMEOUT_MS = parseInt(process.env.APIFY_TIMEOUT_MS || '60000', 10);
const REEL_VIDEO_MAX_MB = parseInt(process.env.REEL_VIDEO_MAX_MB || '200', 10);
const REEL_VIDEO_TIMEOUT_SECONDS = parseInt(process.env.REEL_VIDEO_TIMEOUT_SECONDS || '120', 10);
const REEL_IMPORT_CONFIDENCE_THRESHOLD = parseFloat(process.env.REEL_IMPORT_CONFIDENCE_THRESHOLD || '0.75');
const REEL_IMPORT_TEMP_DIR = process.env.REEL_IMPORT_TEMP_DIR || '/tmp/boet-reel-imports';

export const instagramImportEnabled = () => Boolean(APIFY_API_TOKEN);

// --- Apify fetch ------------------------------------------------------------

async function callApifyActor(url, { timeoutMs }) {
  const endpoint = `https://api.apify.com/v2/acts/${APIFY_INSTAGRAM_ACTOR}/run-sync-get-dataset-items?token=${APIFY_API_TOKEN}`;
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const res = await fetch(endpoint, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username: [url], resultsLimit: 1 }),
      signal: controller.signal,
    });
    if (!res.ok) {
      console.warn(`[boet] apify instagram actor ${res.status}: ${(await res.text()).slice(0, 200)}`);
      return null;
    }
    const items = await res.json();
    return Array.isArray(items) && items.length ? items[0] : null;
  } catch (e) {
    console.warn(`[boet] apify instagram actor failed: ${e?.message || e}`);
    return null;
  } finally {
    clearTimeout(timer);
  }
}

// The actor's "restricted_page" degraded result wraps the caption in
// Instagram's og:description style: `123 likes, 45 comments - user on DATE:
// "actual caption text". ` — strip that wrapper when present so downstream
// parsing sees the real caption rather than a metadata preamble; if the shape
// doesn't match (no comments, different locale, etc.) fall back to the raw
// text untouched rather than losing content.
function cleanRestrictedCaption(text) {
  const raw = String(text || '').trim();
  const m = /^[\d,]+\s+likes?(?:,\s*[\d,]+\s+comments?)?\s*-\s*.*?:\s*"([\s\S]*)"\.?\s*$/.exec(raw);
  return (m ? m[1] : raw).trim();
}

function normalizeApifyItem(item) {
  if (!item) return null;
  const caption = item.caption != null ? String(item.caption).trim() : cleanRestrictedCaption(item.description);
  return {
    caption,
    thumbnail: item.displayUrl || item.image || null,
    videoUrl: item.videoUrl || null,
    id: item.shortCode || item.id || null,
  };
}

// Returns { caption, thumbnail, videoUrl, id } or null (not configured, or
// the actor call itself failed outright — a "restricted_page" partial result
// still returns normally, just with videoUrl: null).
export async function fetchInstagramMeta(url, { timeoutMs = APIFY_TIMEOUT_MS } = {}) {
  if (!instagramImportEnabled()) return null;
  let item = await callApifyActor(url, { timeoutMs });
  if (item?.error === 'restricted_page' && !item?.videoUrl) {
    const retry = await callApifyActor(url, { timeoutMs });
    if (retry) item = retry;
  }
  return normalizeApifyItem(item);
}

// --- Video download ----------------------------------------------------

// Streams the actor-provided direct CDN video URL to a temp file, capping at
// maxMb (checked against Content-Length up front when present, and against
// actual bytes read as a backstop since Content-Length isn't guaranteed).
// Returns the local path, or null on any failure — caller cleans the temp
// file up after use regardless of outcome.
export async function downloadInstagramVideo(videoUrl, { tempDir = REEL_IMPORT_TEMP_DIR, maxMb = REEL_VIDEO_MAX_MB, timeoutMs = REEL_VIDEO_TIMEOUT_SECONDS * 1000 } = {}) {
  if (!videoUrl) return null;
  await fs.mkdir(tempDir, { recursive: true });
  const destPath = path.join(tempDir, `${nanoid()}.mp4`);
  const maxBytes = maxMb * 1024 * 1024;
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const res = await fetch(videoUrl, { signal: controller.signal });
    if (!res.ok || !res.body) {
      console.warn(`[boet] instagram video download: HTTP ${res.status}`);
      return null;
    }
    const contentLength = Number(res.headers.get('content-length') || 0);
    if (contentLength && contentLength > maxBytes) {
      console.warn(`[boet] instagram video download: ${contentLength} bytes exceeds ${maxBytes} cap`);
      return null;
    }
    const fh = await fs.open(destPath, 'w');
    let total = 0;
    try {
      for await (const chunk of Readable.fromWeb(res.body)) {
        total += chunk.length;
        if (total > maxBytes) throw new Error(`video exceeds ${maxMb}MB cap`);
        await fh.write(chunk);
      }
    } finally {
      await fh.close();
    }
    return destPath;
  } catch (e) {
    await fs.rm(destPath, { force: true });
    console.warn(`[boet] instagram video download failed: ${e?.message || e}`);
    return null;
  } finally {
    clearTimeout(timer);
  }
}

// --- Caption-completeness gate ------------------------------------------

function safeParse(s) {
  try { return JSON.parse(s); } catch { return null; }
}

// Tolerant JSON extraction — same small technique recipe-ai.js's own
// parseRecipeObject uses (strip a <think> block and code fences, then take
// the outermost {...}), duplicated here rather than exported from
// recipe-ai.js to keep that file's export surface scoped to recipe
// structuring only.
function parseJsonObject(text) {
  if (!text) return null;
  let t = text.replace(/<think>[\s\S]*?<\/think>/gi, '').trim();
  t = t.replace(/^```(?:json)?\s*/i, '').replace(/\s*```$/i, '').trim();
  const start = t.indexOf('{');
  const end = t.lastIndexOf('}');
  if (start < 0 || end <= start) return null;
  return safeParse(t.slice(start, end + 1));
}

function completenessPrompt(caption) {
  return `You are deciding whether a social-media recipe caption contains enough
information to structure into a full recipe WITHOUT watching the video.

Caption:
"""
${caption}
"""

Answer strictly and conservatively — it is better to say the caption is
NOT enough than to let a vague/incomplete caption through. A caption that
only teases the dish ("full recipe in the video!", "recipe below" with no
actual list) must be marked incomplete.

Output ONLY this JSON:
{"hasRecipe":true,"hasIngredients":true,"hasIngredientQuantities":true,
 "hasSteps":true,"completeEnoughForParsing":true,"confidence":0.0,
 "missing":[]}

completeEnoughForParsing may only be true if hasRecipe, hasIngredients, and
hasSteps are all true. confidence is your own certainty in this judgment,
0.0-1.0.`;
}

// Reuses whichever recipe-structuring LLM backend the household already has
// configured (no separate provider) — this is a tiny prompt, cheap on either
// backend. Returns null if no backend is configured or the call/parse fails;
// callers treat null exactly like "not complete enough" (fail toward the
// more-thorough video path per the spec's "better to continue to video
// fallback than import a bad recipe").
export async function checkCaptionCompleteness(caption) {
  if (!caption || !recipeLlmEnabled()) return null;
  const prompt = completenessPrompt(caption);
  const reply = recipeUsingCloud()
    ? await recipeGenerateCloud(prompt, { timeoutMs: 30000 })
    : await recipeGenerateLocal(prompt, { timeoutMs: 30000 });
  const obj = parseJsonObject(reply);
  if (!obj || typeof obj.completeEnoughForParsing !== 'boolean') return null;
  return obj;
}

function captionCompleteEnough(check) {
  return Boolean(check?.completeEnoughForParsing && Number(check.confidence) >= REEL_IMPORT_CONFIDENCE_THRESHOLD);
}

// --- Markdown evidence bridge (video path only) -------------------------

function fmtIngredient(ing) {
  const bits = [ing.quantity, ing.unit, ing.name].filter((v) => v != null && String(v).trim()).join(' ');
  const prep = ing.preparation ? ` (${ing.preparation})` : '';
  const tag = ing.evidence ? ` [${ing.evidence}]` : '';
  return `- ${bits || ing.name || '?'}${prep}${tag}`;
}

function fmtStep(step) {
  const time = step.time ? ` [${step.time}]` : '';
  const temp = step.temperature ? ` [${step.temperature}]` : '';
  const tag = step.evidence ? ` [${step.evidence}]` : '';
  return `${step.order ?? ''}. ${step.text || ''}${time}${temp}${tag}`;
}

// Builds a plain-text/markdown blob combining the (possibly-incomplete)
// caption as context with Gemini's video evidence, then handed to the
// existing parseRecipeText() — it has no JSON-LD/Mealie shape, so it takes
// the plain-text structuring path, same as a pasted-text recipe.
export function buildEvidenceMarkdown({ url, caption, evidence }) {
  const lines = ['# Instagram Reel Recipe', '', `Source: ${url}`, ''];
  if (caption) lines.push('## Caption', '', caption, '');
  lines.push('## Extracted from video', '');
  if (evidence.title) lines.push(`Title: ${evidence.title}`);
  if (evidence.description) lines.push(evidence.description);
  lines.push('', 'Ingredients:');
  for (const ing of evidence.ingredients || []) lines.push(fmtIngredient(ing));
  lines.push('', 'Steps:');
  for (const step of evidence.steps || []) lines.push(fmtStep(step));
  if (evidence.servings) lines.push('', `Servings: ${evidence.servings}`);
  if (evidence.totalTime) lines.push(`Total time: ${evidence.totalTime}`);
  if (Array.isArray(evidence.notes) && evidence.notes.length) {
    lines.push('', 'Notes:');
    for (const n of evidence.notes) lines.push(`- ${n}`);
  }
  if (Array.isArray(evidence.missing) && evidence.missing.length) {
    lines.push('', 'Missing/uncertain:');
    for (const m of evidence.missing) lines.push(`- ${m}`);
  }
  return lines.join('\n');
}

// --- Orchestrator --------------------------------------------------------

// The full pipeline for routes/instagram.js: fetch caption -> decide if it's
// enough -> caption path (existing parseRecipeText) OR video path (download
// -> Gemini -> markdown -> existing parseRecipeText). Returns
// { doc, thumbnail } where doc may be null (caller produces aiStatus:'error'),
// or throws an Error with `userFacing: true` and a Swedish message for the
// specific failures the user can act on (no video available, download
// failed, video analysis found nothing).
export async function importInstagramReel(url, { onStatus } = {}) {
  if (!instagramImportEnabled()) {
    throw Object.assign(new Error('Instagram-import är inte konfigurerat på servern.'), { userFacing: true });
  }

  onStatus?.('fetching_caption');
  const meta = await fetchInstagramMeta(url);
  if (!meta) {
    throw Object.assign(new Error('Kunde inte hämta Instagram-inlägget. Prova igen om en stund.'), { userFacing: true });
  }
  const caption = meta.caption || '';

  let useVideoPath = true;
  if (caption) {
    onStatus?.('checking_caption');
    const check = await checkCaptionCompleteness(caption);
    if (captionCompleteEnough(check)) useVideoPath = false;
  }

  let doc = null;
  if (!useVideoPath) {
    doc = await parseRecipeText(caption, { onStatus });
    // A null doc (no usable structure at all) falls through to the video
    // path below as a last resort; a non-null 'degraded' doc is still a
    // usable result and is kept as-is.
  }

  if ((useVideoPath || !doc) && meta.videoUrl) {
    onStatus?.('downloading_video');
    const videoPath = await downloadInstagramVideo(meta.videoUrl);
    if (!videoPath) {
      throw Object.assign(new Error('Kunde inte ladda ner videon från Instagram. Klistra in texten manuellt istället.'), { userFacing: true });
    }
    try {
      onStatus?.('analyzing_video');
      const evidence = await extractRecipeFromVideo(videoPath, { timeoutMs: REEL_VIDEO_TIMEOUT_SECONDS * 1000 });
      if (!evidence) {
        throw Object.assign(new Error('AI:n kunde inte hitta ett recept i videon.'), { userFacing: true });
      }
      const markdown = buildEvidenceMarkdown({ url, caption, evidence });
      doc = await parseRecipeText(markdown, { onStatus });
    } finally {
      await fs.rm(videoPath, { force: true });
    }
  } else if ((useVideoPath || !doc) && !meta.videoUrl) {
    throw Object.assign(new Error('Texten till Instagram-inlägget räckte inte och ingen video kunde hämtas. Klistra in receptet manuellt istället.'), { userFacing: true });
  }

  return { doc, thumbnail: meta.thumbnail };
}
