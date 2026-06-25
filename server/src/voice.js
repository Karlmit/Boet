// Server-side voice cleaning. Turns a raw Swedish voice transcript into tidy
// shopping items using the household's local LLM (see ollama.js), so every phone
// gets the same quality regardless of whether it has an on-device model (Klara's
// Samsung S24 has no Gemini Nano; Kalle's OnePlus does). Mirrors the app's
// se.jabba.boet.ai.VoiceCleaner prompt + rules; falls back to a deterministic
// split when the model is unavailable.

import { ollamaEnabled, ollamaGenerate, ollamaModel } from './ollama.js';

// Grocery-friendly unit subset — keep in sync with ai/Quantity.kt UNITS.
const UNITS = ['kg', 'g', 'hg', 'l', 'dl', 'paket'];

// --- quantity helpers (ported from ai/Quantity.kt) -------------------------

function formatNumber(value) {
  return Number.isInteger(value) ? String(value) : String(value).replace('.', ',');
}

// Build the stored quantity string. Counts: >1 -> "N", else null (no badge).
// Measures: always "<number> <unit>".
function composeQuantity(value, unit) {
  const u = UNITS.includes(unit) ? unit : null;
  if (!u) return value > 1 ? formatNumber(value) : null;
  return `${formatNumber(value)} ${u}`;
}

function parseQuantity(s) {
  const t = (s || '').trim();
  if (!t) return { value: 1, unit: null };
  const m = t.match(/^\s*(\d+(?:[.,]\d+)?)\s*(\p{L}+)?\s*$/u);
  if (!m) return { value: 1, unit: null };
  const value = parseFloat(m[1].replace(',', '.'));
  const rawUnit = (m[2] || '').toLowerCase();
  return { value: Number.isFinite(value) ? value : 1, unit: UNITS.includes(rawUnit) ? rawUnit : null };
}

// Merge two quantities for the same item. Both bare counts -> sum; if either side
// carries a unit, prefer the incoming measure, else keep existing (never add
// across units).
function mergeQuantity(existing, incoming) {
  const a = parseQuantity(existing);
  const b = parseQuantity(incoming);
  if (b.unit) return composeQuantity(b.value, b.unit);
  if (a.unit) return composeQuantity(a.value, a.unit);
  return composeQuantity(a.value + b.value, null);
}

// --- model path ------------------------------------------------------------

function buildPrompt(raw) {
  return [
    'Du städar en röstinspelad svensk inköpslista.',
    'Råtext (kan ha taligenkänningsfel, utfyllnadsord och sånt som inte är varor):',
    `"${raw}"`,
    'Regler:',
    '- Behåll bara riktiga inköpsvaror (mat, dryck, hushåll). Ta bort allt annat.',
    '- Rätta uppenbara taligenkänningsfel till rätt varunamn (ex: "tonjäst" -> "torrjäst").',
    '- Använd singular grundform med stor första bokstav (ex: "citroner" -> "Citron").',
    '- "qty" = antalet/mängden som siffra om det sägs (ex: "två" -> "2", "tio" -> "10"), annars "1".',
    '- "unit" = enheten om en vikt/volym/förpackning sägs, en av [kg, g, hg, l, dl, paket]. Annars tom sträng "".',
    '  Ex: "ett kilo fläsk" -> qty "1", unit "kg". "tio gram saffran" -> qty "10", unit "g". "två äpplen" -> qty "2", unit "".',
    '- Slå ihop dubbletter (summera bara rena antal, inte vikter/volymer).',
    'Svara ENBART med ett JSON-objekt på formen {"items":[...]}, inget annat:',
    '{"items":[{"name":"Fläsk","qty":"1","unit":"kg"},{"name":"Saffran","qty":"10","unit":"g"},{"name":"Äpple","qty":"2","unit":""}]}',
  ].join('\n');
}

// Pull the item array out of the model reply, tolerant of stray prose, ```json
// fences, optional <think> blocks, or a bare array vs. a {"items":[...]} object.
function parseModelItems(text) {
  if (!text) return null;
  let t = text.replace(/<think>[\s\S]*?<\/think>/gi, '').trim();

  let arr = null;
  // Prefer an explicit "items": [ ... ] block.
  const itemsKey = t.match(/"items"\s*:\s*(\[[\s\S]*\])/);
  if (itemsKey) {
    arr = safeParse(itemsKey[1]);
  }
  if (!arr) {
    const start = t.indexOf('[');
    const end = t.lastIndexOf(']');
    if (start >= 0 && end > start) arr = safeParse(t.slice(start, end + 1));
  }
  if (!Array.isArray(arr)) return null;

  return arr
    .map((d) => {
      const name = String(d?.name ?? '').trim();
      if (!name || name.length > 40) return null;
      let value = parseFloat(String(d?.qty ?? '1').replace(',', '.'));
      if (!Number.isFinite(value)) value = 1;
      value = Math.min(Math.max(value, 0), 9999);
      const unit = String(d?.unit ?? '').trim().toLowerCase();
      return {
        name: name.charAt(0).toUpperCase() + name.slice(1),
        quantity: composeQuantity(value, UNITS.includes(unit) ? unit : null),
      };
    })
    .filter(Boolean);
}

function safeParse(s) {
  try {
    return JSON.parse(s);
  } catch {
    return null;
  }
}

// --- deterministic fallback (ported from VoiceCleaner.regexItems) ----------

function fallbackItems(utterance) {
  let text = (utterance || '').trim().toLowerCase();
  text = text.replace(/^(lägg\s+till|lägg\s+i|addera|add|put|sätt\s+upp)\s+/, '');
  return text
    .split(/,|;|\boch\b|\band\b|&|\bplus\b/)
    .map((s) => s.trim().replace(/^och\s+/, '').replace(/^and\s+/, '').trim())
    .filter((s) => s.length >= 1 && s.length <= 40)
    .map((s) => ({ name: s.charAt(0).toUpperCase() + s.slice(1), quantity: null }));
}

// Merge same-named items (case-insensitive), preserving first-seen order.
function dedup(items) {
  const order = new Map();
  for (const it of items) {
    const key = it.name.toLowerCase();
    const existing = order.get(key);
    order.set(key, existing ? { ...existing, quantity: mergeQuantity(existing.quantity, it.quantity) } : it);
  }
  return [...order.values()];
}

// --- public API ------------------------------------------------------------

// transcript: array of utterance strings (or a single string). Returns
// { items: [{name, quantity}], engine }. engine names the source for diagnostics.
export async function cleanVoice(transcript) {
  const lines = (Array.isArray(transcript) ? transcript : [transcript])
    .map((s) => String(s ?? '').trim())
    .filter(Boolean);
  const raw = lines.join('. ').trim();
  if (!raw) return { items: [], engine: 'empty' };

  if (ollamaEnabled()) {
    const out = await ollamaGenerate(buildPrompt(raw), { format: 'json' });
    const parsed = out && parseModelItems(out);
    if (parsed && parsed.length) return { items: dedup(parsed), engine: ollamaModel() };
  }
  return { items: dedup(lines.flatMap(fallbackItems)), engine: 'fallback' };
}
