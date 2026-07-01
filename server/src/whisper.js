// Thin client for a local speech-to-text sidecar (faster-whisper-server, an
// OpenAI Whisper-API-compatible container) — see docker-compose.yml's
// `faster-whisper` service. Used by the kitchen display's voice-add flow
// (routes/voice.js): the tablet is too slow to run on-device speech
// recognition, or even a normal record-transcript/review/confirm round trip,
// so it just records a clip and ships the raw audio here. Runs on the user's
// own Unraid box next to ollama/translate, keeping the "no third-party cloud"
// promise (PRODUCT.md). Disabled (returns null) unless WHISPER_URL is set.

const WHISPER_URL = (process.env.WHISPER_URL || '').replace(/\/$/, '');
// Must be a full "repo/name" Hugging Face model id — faster-whisper's built-in
// short names (large-v3, large-v2, ...) don't include a "turbo" entry, so the
// bare string "large-v3-turbo" 400s. Keep this in sync with the
// faster-whisper-server container's own WHISPER__MODEL env (docker-compose.yml)
// — override via env if you point WHISPER_URL at a container running a
// different model.
const WHISPER_MODEL = process.env.WHISPER_MODEL || 'deepdml/faster-whisper-large-v3-turbo-ct2';
// Household is Swedish-first; pinning the language skips language-detection
// overhead and avoids the model guessing wrong on a short/noisy kitchen clip.
const WHISPER_LANGUAGE = process.env.WHISPER_LANGUAGE || 'sv';
// CPU inference of a short voice-add clip should be quick, but give it room.
const WHISPER_TIMEOUT_MS = parseInt(process.env.WHISPER_TIMEOUT_MS || '30000', 10);

const EXT_BY_CONTENT_TYPE = {
  'audio/wav': 'wav', 'audio/x-wav': 'wav', 'audio/wave': 'wav',
  'audio/webm': 'webm', 'audio/ogg': 'ogg', 'audio/mpeg': 'mp3',
  'audio/mp4': 'm4a', 'audio/aac': 'aac',
};

export const whisperEnabled = () => Boolean(WHISPER_URL);

// Transcribe one audio clip. Returns the transcript string, or null on any
// failure (unset URL, timeout, non-2xx, empty reply) so callers can fail the
// request clearly rather than silently adding nothing.
export async function whisperTranscribe(buffer, contentType = 'audio/wav') {
  if (!WHISPER_URL) return null;
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), WHISPER_TIMEOUT_MS);
  try {
    const ext = EXT_BY_CONTENT_TYPE[contentType] || 'wav';
    const form = new FormData();
    form.append('file', new Blob([buffer], { type: contentType }), `clip.${ext}`);
    form.append('model', WHISPER_MODEL);
    form.append('language', WHISPER_LANGUAGE);
    form.append('response_format', 'json');
    const res = await fetch(`${WHISPER_URL}/v1/audio/transcriptions`, {
      method: 'POST',
      body: form,
      signal: controller.signal,
    });
    if (!res.ok) {
      console.warn(`[boet] whisper ${res.status}: ${(await res.text()).slice(0, 200)}`);
      return null;
    }
    const data = await res.json();
    const text = (data?.text || '').trim();
    return text || null;
  } catch (e) {
    console.warn(`[boet] whisper request failed: ${e?.message || e}`);
    return null;
  } finally {
    clearTimeout(timer);
  }
}
