// Deterministic imperial → Swedish-metric unit conversion for recipe ingredients.
// The spike showed LLMs RELABEL units without doing the arithmetic ("1.5 cups" ->
// "1.5 dl"), so quantity math is done here in code, never by the model.

// US volume units, in millilitres, mapped to the Swedish kitchen unit we render in.
const VOLUME_ML = {
  cup: 236.6, cups: 236.6,
  'fl oz': 29.57, floz: 29.57,
  pint: 473.2, pints: 473.2,
  quart: 946.4, quarts: 946.4,
  gallon: 3785.0, gallons: 3785.0,
};
// US mass units, in grams.
const MASS_G = {
  oz: 28.35, ounce: 28.35, ounces: 28.35,
  lb: 453.6, lbs: 453.6, pound: 453.6, pounds: 453.6,
};
// Spoon units differ by < 2% between US and Swedish, so we relabel 1:1 rather
// than rescale (matches how Swedes read recipes).
const SPOON_RELABEL = {
  tablespoon: 'msk', tablespoons: 'msk', tbsp: 'msk', tbsps: 'msk',
  teaspoon: 'tsk', teaspoons: 'tsk', tsp: 'tsk', tsps: 'tsk',
};
// Already-metric units pass through untouched (lower-cased).
const METRIC_PASSTHROUGH = new Set(['g', 'kg', 'hg', 'ml', 'cl', 'dl', 'l', 'krm', 'msk', 'tsk', 'st']);

// Pick a readable Swedish volume unit + quantity from a millilitre amount.
function fromMl(ml) {
  if (ml >= 1000) return { quantity: round(ml / 1000, 2), unit: 'l' };
  if (ml >= 100) return { quantity: round(ml / 100, 1), unit: 'dl' };
  if (ml >= 15) return { quantity: round(ml / 100, 2), unit: 'dl' };
  return { quantity: round(ml, 0), unit: 'ml' };
}

function round(value, decimals) {
  const f = 10 ** decimals;
  return Math.round(value * f) / f;
}

// Convert one ingredient's { quantity, unit } to Swedish metric. Unknown or
// already-metric units (and count-only ingredients) pass through unchanged.
export function convertUnit(quantity, unit) {
  const q = typeof quantity === 'number' && Number.isFinite(quantity) ? quantity : null;
  const u = String(unit || '').trim().toLowerCase();
  if (q === null || !u) return { quantity: q, unit: unit || null };

  if (SPOON_RELABEL[u]) return { quantity: round(q, 2), unit: SPOON_RELABEL[u] };
  if (VOLUME_ML[u]) return fromMl(q * VOLUME_ML[u]);
  if (MASS_G[u]) {
    const g = q * MASS_G[u];
    return g >= 1000 ? { quantity: round(g / 1000, 2), unit: 'kg' } : { quantity: round(g, 0), unit: 'g' };
  }
  if (METRIC_PASSTHROUGH.has(u)) return { quantity: round(q, 2), unit: u };
  // Unknown unit (pinch, clove, can…): keep as the model gave it.
  return { quantity: q, unit: unit };
}

// Render a Swedish quantity number: integers bare, decimals with a comma.
export function formatQty(value) {
  if (value === null || value === undefined) return '';
  return Number.isInteger(value) ? String(value) : String(value).replace('.', ',');
}
