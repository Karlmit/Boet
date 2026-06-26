import { ollamaEnabled, ollamaGenerate } from './ollama.js';

const OTHER_CATEGORY = 'övrigt';

function buildPrompt(names, categoryNames) {
  const primaryCategories = categoryNames.filter((name) => name.toLowerCase() !== OTHER_CATEGORY);
  const fallbackCategories = categoryNames.filter((name) => name.toLowerCase() === OTHER_CATEGORY);
  return [
    'Du kategoriserar varor i en svensk inköpslista.',
    'Vanliga kategorier:',
    ...primaryCategories.map((name) => `- ${name}`),
    ...(fallbackCategories.length ? ['', 'Fallback-kategori, bara när inget annat passar:', ...fallbackCategories.map((name) => `- ${name}`)] : []),
    '',
    'Varor:',
    ...names.map((name) => `- ${name}`),
    '',
    'Regler:',
    '- Tilldela varje vara exakt en av de tillåtna kategorierna utifrån betydelse.',
    '- Hitta aldrig på en ny kategori.',
    '- Använd kategorinamnen exakt som de står i listan.',
    '- Använd Övrigt endast som sista utväg när ingen annan kategori passar. Övrigt betyder osorterat, inte en riktig varugrupp.',
    '- Drycker som cola, läsk, juice, öl, vin och vatten ska till Drycker om den kategorin finns.',
    '- Exempel: köttbullar ska till en kött/frys-kategori om en sådan finns, inte Bröd, även om ordet innehåller "bullar".',
    `Svara ENBART med JSON på formen {"items":[{"name":"köttbullar","category":"${primaryCategories[0] || categoryNames[0]}"}]}.`,
  ].join('\n');
}

function safeParse(s) {
  try {
    return JSON.parse(s);
  } catch {
    return null;
  }
}

function parseAssignments(text) {
  if (!text) return null;
  let t = text.replace(/<think>[\s\S]*?<\/think>/gi, '').trim();
  t = t.replace(/^```(?:json)?\s*/i, '').replace(/\s*```$/i, '').trim();

  const objectStart = t.indexOf('{');
  const objectEnd = t.lastIndexOf('}');
  if (objectStart >= 0 && objectEnd > objectStart) {
    const obj = safeParse(t.slice(objectStart, objectEnd + 1));
    if (Array.isArray(obj?.items)) return obj.items;
    if (obj && typeof obj === 'object') {
      return Object.entries(obj).map(([name, category]) => ({ name, category }));
    }
  }

  const arrayStart = t.indexOf('[');
  const arrayEnd = t.lastIndexOf(']');
  if (arrayStart >= 0 && arrayEnd > arrayStart) {
    const arr = safeParse(t.slice(arrayStart, arrayEnd + 1));
    if (Array.isArray(arr)) return arr;
  }

  return null;
}

export async function llmCategorize(names, categoryNames) {
  const cleanNames = [...new Set((names || []).map((n) => String(n ?? '').trim()).filter(Boolean))];
  const cleanCategories = (categoryNames || []).map((n) => String(n ?? '').trim()).filter(Boolean);
  if (!ollamaEnabled() || cleanNames.length === 0 || cleanCategories.length === 0) return {};

  const allowed = new Map(cleanCategories.map((name) => [name.toLowerCase(), name]));
  const requested = new Set(cleanNames.map((name) => name.toLowerCase()));
  const out = await ollamaGenerate(buildPrompt(cleanNames, cleanCategories), { format: 'json' });
  const parsed = parseAssignments(out);
  if (!Array.isArray(parsed)) return {};

  const assignments = {};
  for (const row of parsed) {
    const name = String(row?.name ?? row?.item ?? '').trim();
    const category = String(row?.category ?? row?.kategori ?? '').trim();
    if (!name || !requested.has(name.toLowerCase())) continue;
    const validCategory = allowed.get(category.toLowerCase());
    if (validCategory && validCategory.toLowerCase() !== OTHER_CATEGORY) assignments[name] = validCategory;
  }
  return assignments;
}
