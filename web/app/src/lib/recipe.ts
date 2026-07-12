// Recipe formatting helpers — direct ports of the Android app's
// (ui/recipes/RecipeDetailScreen.kt) so both clients render identically.

import type { RecipeIngredient } from '../api/types';

// Group items by their tags, preserving first-appearance order of tags. An
// item with several tags appears once per tag it carries (sub-recipe headings
// like "Marinad"/"Sås", where the same ingredient can belong to more than
// one). Untagged items come first as a single null-keyed group (no header).
export function groupByTags<T>(items: T[], tagsOf: (item: T) => string[]): Array<[string | null, T[]]> {
  const untagged: T[] = [];
  const byTag = new Map<string, T[]>();
  for (const item of items) {
    const tags = tagsOf(item).map((t) => t.trim()).filter((t) => t.length > 0);
    if (tags.length === 0) untagged.push(item);
    else for (const tag of tags) {
      const group = byTag.get(tag);
      if (group) group.push(item);
      else byTag.set(tag, [item]);
    }
  }
  const result: Array<[string | null, T[]]> = [];
  if (untagged.length > 0) result.push([null, untagged]);
  for (const [tag, group] of byTag) result.push([tag, group]);
  return result;
}

// The full ingredient line, amount scaled (e.g. "3,5 dl vetemjöl"). Manual
// recipes without a numeric quantity fall back to their stored display text.
export function ingredientLine(ing: RecipeIngredient, factor: number): string {
  const q = ing.quantity;
  if (q != null) {
    return [fmtNum(q * factor), ing.unit ?? '', ing.food].filter((s) => s.trim().length > 0).join(' ');
  }
  return ing.display.trim() ? ing.display : ing.food;
}

// Compact chip label for a step's referenced ingredient ("vetemjöl · 3,5 dl").
export function chipLabel(ing: RecipeIngredient, factor: number): string {
  const q = ing.quantity;
  if (q == null) return ing.food;
  const amount = [fmtNum(q * factor), ing.unit ?? ''].filter((s) => s.trim().length > 0).join(' ');
  return amount.trim() ? `${ing.food} · ${amount}` : ing.food;
}

// The quantity string handed to the shopping list when adding an ingredient,
// amount scaled. Measures keep their unit ("3,5 dl"); a bare count > 1 becomes
// "N"; otherwise null (no badge), mirroring normal Boet quantities.
export function addQty(ing: RecipeIngredient, factor: number): string | null {
  const q = ing.quantity;
  if (q == null) return null;
  const scaled = q * factor;
  if (ing.unit && ing.unit.trim()) return `${fmtNum(scaled)} ${ing.unit}`;
  if (scaled > 1) return fmtNum(scaled);
  return null;
}

// Round to 2 decimals, drop a trailing ".0", and use a Swedish decimal comma.
export function fmtNum(value: number): string {
  const r = Math.round(value * 100) / 100;
  return String(r).replace('.', ',');
}
