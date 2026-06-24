// Lightweight, deterministic text helpers. The spec mandates no cloud-AI
// dependency; on-device AI lives in the app. The backend provides robust
// rule-based fallbacks so the feature works even with no model present.

// Parse a natural-language sorting prompt into an ordered category list.
// e.g. "Sort by documents, electronics, clothes, toiletries, medicine and other"
//   -> ['Documents','Electronics','Clothes','Toiletries','Medicine','Other']
export function parseSortPrompt(prompt) {
  if (!prompt) return [];
  let text = prompt;

  // Drop a leading "sort by" / "sortera efter" / "this is a ... list." clause.
  text = text.replace(/^.*?\b(sort(era)?\s*(by|efter|på)?|kategorier|categories)\s*[:\-]?\s*/i, '');

  // Split on commas, "and"/"och", semicolons, newlines, slashes.
  const parts = text
    .split(/,|;|\n|\/|\boch\b|\band\b/gi)
    .map((s) => s.replace(/[.!]+$/g, '').trim())
    .filter((s) => s.length > 0 && s.length < 40);

  // Title-case each.
  const cats = parts.map((p) => p.charAt(0).toUpperCase() + p.slice(1));

  // De-dupe preserving order.
  const seen = new Set();
  const out = [];
  for (const c of cats) {
    const k = c.toLowerCase();
    if (!seen.has(k)) { seen.add(k); out.push(c); }
  }
  return out.length ? out : [];
}

// Extract candidate grocery items from free recipe / ingredient text.
// Returns [{ name, quantity }]. Conservative: one item per meaningful line.
export function parseRecipe(text) {
  if (!text) return [];
  const lines = text
    .split(/\n|•|•|;/g)
    .map((l) => l.trim())
    .filter(Boolean);

  const items = [];
  const seen = new Set();
  const qtyRe = /^\s*([\d¼½¾]+([.,]\d+)?\s*(kg|g|hg|l|dl|cl|ml|msk|tsk|krm|st|paket|burk|förp|clove|cup|tbsp|tsp)?\b)?\s*/i;
  // Lines beginning with a cooking verb are instructions, not ingredients.
  const instructionRe = /^(vispa|grädda|stek|koka|blanda|rör|häll|tillsätt|servera|sätt|skär|hacka|mixa|forma|krydda|låt|värm|baka|stir|whisk|bake|boil|mix|fry|heat|add|pour|cook|serve|cut|chop|preheat)\b/i;

  for (const raw of lines) {
    // Skip instruction-like sentences (too long, or verb-led).
    if (raw.length > 60) continue;
    if (instructionRe.test(raw.replace(/^[-*•·]\s+/, ''))) continue;
    // Strip list markers only: bullets, or "1." / "1)" ordered markers — but
    // never a bare quantity number (handled next).
    let line = raw.replace(/^[-*•·]\s+/, '').replace(/^\d+[.)]\s+/, '');
    const qm = line.match(qtyRe);
    let quantity = null;
    if (qm && qm[1]) {
      quantity = qm[1].trim();
      line = line.slice(qm[0].length).trim();
    }
    // Remove parenthetical notes.
    const name = line.replace(/\(.*?\)/g, '').replace(/\bav\b.*$/i, '').trim();
    if (!name || name.length < 2) continue;
    const key = name.toLowerCase();
    if (seen.has(key)) continue;
    seen.add(key);
    items.push({ name, quantity });
  }
  return items;
}
