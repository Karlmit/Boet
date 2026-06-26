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

const QUANTITY_CUE_RE = new RegExp([
  String.raw`\d+(?:[.,]\d+)?`,
  String.raw`\b(en|ett|två|tre|fyra|fem|sex|sju|åtta|atta|nio|tio|elva|tolv)\b`,
  String.raw`\b(kilo|kilot|kilogram|gram|hekto|hektogram|liter|litern|deciliter|dl|cl|ml|kg|g|hg|l|paket|pack)\b`,
].join('|'), 'iu');

function normalizeLoose(s) {
  return String(s || '')
    .toLowerCase()
    .normalize('NFD')
    .replace(/\p{Diacritic}/gu, '')
    .replace(/[^\p{L}\p{N}]+/gu, '');
}

function hasExplicitQuantityForItem(raw, name) {
  const itemKey = normalizeLoose(name);
  if (!itemKey) return false;
  const segments = String(raw || '')
    .split(/,|;|\boch\b|\band\b|&|\bplus\b/iu)
    .map((s) => s.trim())
    .filter(Boolean);
  for (const segment of segments) {
    const segmentKey = normalizeLoose(segment);
    if ((segmentKey.includes(itemKey) || itemKey.includes(segmentKey)) && QUANTITY_CUE_RE.test(segment)) {
      return true;
    }
  }
  return false;
}

function stripInventedQuantities(items, raw) {
  return items.map((item) => (
    item.quantity && !hasExplicitQuantityForItem(raw, item.name)
      ? { ...item, quantity: null }
      : item
  ));
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

function buildPrompt(raw, categoryNames = []) {
  const categoryLines = categoryNames.length
    ? [
        '- Lägg till fältet "category" för varje vara.',
        '- "category" måste vara exakt en av de tillåtna kategorierna och väljas utifrån betydelse.',
        '- Hitta aldrig på en kategori.',
        '- Exempel: köttbullar ska till en kött/frys-kategori om en sådan finns, inte Bröd, även om ordet innehåller "bullar".',
        'Tillåtna kategorier:',
        ...categoryNames.map((name) => `- ${name}`),
      ]
    : [];
  const exampleItem = categoryNames.length
    ? `{"name":"Fläsk","qty":"1","unit":"kg","category":"${categoryNames[0]}"}`
    : '{"name":"Fläsk","qty":"1","unit":"kg"}';
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
    '- Gissa aldrig standardstorlek, vikt eller volym. Om användaren inte uttryckligen säger mängd/enhet ska qty vara "1" och unit vara "".',
    '- Ex: "Pepsi, Cocacola, grädde" -> qty "1", unit "" för alla tre. Skriv inte 1 l eller 1 kg om det inte sades.',
    '  Ex: "ett kilo fläsk" -> qty "1", unit "kg". "tio gram saffran" -> qty "10", unit "g". "två äpplen" -> qty "2", unit "".',
    '- Slå ihop dubbletter (summera bara rena antal, inte vikter/volymer).',
    ...categoryLines,
    'Svara ENBART med ett JSON-objekt på formen {"items":[...]}, inget annat:',
    `{"items":[${exampleItem},{"name":"Saffran","qty":"10","unit":"g"},{"name":"Äpple","qty":"2","unit":""}]}`,
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
        category: String(d?.category ?? '').trim() || undefined,
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
    order.set(key, existing ? { ...existing, quantity: mergeQuantity(existing.quantity, it.quantity), category: existing.category || it.category } : it);
  }
  return [...order.values()];
}

// --- public API ------------------------------------------------------------

// transcript: array of utterance strings (or a single string). Returns
// { items: [{name, quantity, category?}], engine }. engine names the source for diagnostics.
export async function cleanVoice(transcript, categoryNames = []) {
  const lines = (Array.isArray(transcript) ? transcript : [transcript])
    .map((s) => String(s ?? '').trim())
    .filter(Boolean);
  const raw = lines.join('. ').trim();
  if (!raw) return { items: [], engine: 'empty' };

  const allowedCategories = new Map(
    (categoryNames || []).map((name) => String(name ?? '').trim()).filter(Boolean).map((name) => [name.toLowerCase(), name])
  );

  if (ollamaEnabled()) {
    const out = await ollamaGenerate(buildPrompt(raw, [...allowedCategories.values()]), { format: 'json' });
    const parsed = out && parseModelItems(out);
    if (parsed && parsed.length) {
      const items = stripInventedQuantities(dedup(parsed), raw).map((item) => {
        const category = item.category ? allowedCategories.get(item.category.toLowerCase()) : null;
        return category ? { ...item, category } : { name: item.name, quantity: item.quantity };
      });
      return { items, engine: ollamaModel() };
    }
  }
  return { items: dedup(lines.flatMap(fallbackItems)), engine: 'fallback' };
}
