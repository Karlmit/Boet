// Boet grocery categorization knowledge base.
// Maps Swedish (and common English) grocery names to the default categories.
// This is the deterministic baseline; learned_categories overrides it, and the
// on-device AI in the app handles anything still unknown.

export const DEFAULT_CATEGORIES = [
  'Frukt & grönt',
  'Bröd',
  'Mejeri',
  'Kött & fisk',
  'Frys',
  'Torrvaror',
  'Snacks',
  'Hushåll',
  'Övrigt',
];

// Swedish supermarket aisle order (ICA/Coop/Hemköp/Willys friendly default).
export const DEFAULT_CATEGORY_ORDER = DEFAULT_CATEGORIES;

// keyword (substring, lowercased) -> category name
const KEYWORDS = {
  'Frukt & grönt': [
    'banan', 'äpple', 'apple', 'päron', 'apelsin', 'orange', 'citron', 'lime',
    'tomat', 'gurka', 'sallad', 'sallat', 'lök', 'vitlök', 'morot', 'morötter',
    'potatis', 'paprika', 'avokado', 'avocado', 'broccoli', 'blomkål', 'spenat',
    'champinjon', 'svamp', 'zucchini', 'aubergin', 'ingefära', 'chili', 'lime',
    'druvor', 'vindruvor', 'jordgubb', 'blåbär', 'hallon', 'melon', 'mango',
    'persika', 'nektarin', 'kiwi', 'ananas', 'lök', 'purjo', 'rödlök', 'palsternacka',
    'rödbeta', 'kål', 'grönsaker', 'frukt', 'örter', 'basilika', 'persilja', 'dill',
  ],
  'Bröd': [
    'bröd', 'bread', 'baguette', 'fralla', 'frallor', 'limpa', 'rågbröd',
    'knäckebröd', 'tunnbröd', 'pitabröd', 'tortilla', 'bulle', 'bullar',
    'croissant', 'kanelbulle', 'toast', 'hamburgerbröd', 'korvbröd', 'frukostbröd',
  ],
  'Mejeri': [
    'mjölk', 'milk', 'filmjölk', 'filbunke', 'yoghurt', 'yogurt', 'kvarg', 'grädde',
    'crème fraiche', 'creme fraiche', 'gräddfil', 'smör', 'butter', 'margarin',
    'ost', 'cheese', 'halloumi', 'fetaost', 'mozzarella', 'parmesan', 'keso',
    'ägg', 'egg', 'cottage', 'kefir', 'havremjölk', 'oatly', 'sojamjölk',
    'laktosfri', 'cheddar', 'philadelphia', 'färskost', 'kvargt',
  ],
  'Kött & fisk': [
    'kyckling', 'chicken', 'kött', 'köttfärs', 'färs', 'nötkött', 'fläsk',
    'bacon', 'korv', 'sausage', 'skinka', 'ham', 'salami', 'biff', 'steak',
    'fisk', 'fish', 'lax', 'salmon', 'torsk', 'räkor', 'shrimp', 'tonfisk',
    'tuna', 'fiskpinnar', 'pannbiff', 'leverpastej', 'pålägg', 'fläskfilé',
    'kotlett', 'revben', 'kalkon', 'turkey', 'kassler', 'falukorv', 'prinskorv',
  ],
  'Frys': [
    'frys', 'frozen', 'glass', 'ice cream', 'pizza', 'frysta', 'fryst',
    'fiskpinnar', 'pommes', 'pommes frites', 'ärtor', 'spenat fryst',
    'köttbullar', 'lövbiff', 'våfflor', 'bär fryst',
  ],
  'Torrvaror': [
    'pasta', 'spaghetti', 'makaroner', 'ris', 'rice', 'mjöl', 'flour', 'socker',
    'sugar', 'salt', 'peppar', 'kryddor', 'olja', 'oil', 'olivolja', 'vinäger',
    'ketchup', 'senap', 'majonnäs', 'mayo', 'bönor', 'linser', 'kikärtor',
    'krossade tomater', 'passata', 'buljong', 'havregryn', 'müsli', 'cornflakes',
    'flingor', 'kaffe', 'coffee', 'te', 'tea', 'kakao', 'honung', 'sylt',
    'nötter', 'mandel', 'russin', 'jäst', 'bakpulver', 'vaniljsocker', 'couscous',
    'bulgur', 'quinoa', 'nudlar', 'noodles', 'soja', 'sojasås', 'curry', 'tacokrydda',
    'tacoskal', 'pesto', 'oliver', 'tahini', 'kokosmjölk', 'sirap',
  ],
  'Snacks': [
    'chips', 'godis', 'candy', 'choklad', 'chocolate', 'kex', 'kakor', 'cookies',
    'popcorn', 'nötter snacks', 'läsk', 'soda', 'cola', 'saft', 'juice',
    'energidryck', 'snacks', 'ostbågar', 'lakrits', 'tuggummi', 'glasspinne',
    'bubbel', 'festis', 'dipp',
  ],
  'Hushåll': [
    'toalettpapper', 'toapapper', 'hushållspapper', 'papper', 'disktrasa',
    'diskmedel', 'tvättmedel', 'sköljmedel', 'tvål', 'soap', 'schampo', 'shampoo',
    'balsam', 'tandkräm', 'tandborste', 'deodorant', 'rengöring', 'allrent',
    'soppåsar', 'fryspåsar', 'plastfolie', 'aluminiumfolie', 'bakplåtspapper',
    'servetter', 'blöjor', 'bindor', 'tamponger', 'batterier', 'glödlampa',
    'ljus', 'tändstickor', 'kattmat', 'hundmat', 'rakblad', 'bomull', 'städ',
  ],
};

export function normalizeKey(name) {
  return (name || '')
    .toLowerCase()
    .trim()
    .replace(/\s+/g, ' ')
    // strip a leading/trailing quantity like "2 kg" or "x3"
    .replace(/\b\d+([.,]\d+)?\s*(kg|g|l|dl|cl|ml|st|pack|paket|x)?\b/g, '')
    .replace(/\bx\s*\d+\b/g, '')
    .trim();
}

// Returns the best-guess category name for a grocery item, or 'Övrigt'.
export function guessCategory(name) {
  const key = normalizeKey(name);
  if (!key) return 'Övrigt';
  for (const [category, words] of Object.entries(KEYWORDS)) {
    for (const w of words) {
      if (key.includes(w)) return category;
    }
  }
  return 'Övrigt';
}
