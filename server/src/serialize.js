// Row -> client-facing shapes. camelCase + ISO timestamps.

export function listRow(r) {
  return {
    id: r.id,
    name: r.name,
    kind: r.kind,
    icon: r.icon,
    position: r.position,
    archived: r.archived,
    sortPrompt: r.sort_prompt,
    bgImageUrl: r.bg_image_url,
    bgBlur: r.bg_blur,
    bgOverlay: r.bg_overlay,
    createdAt: r.created_at,
    updatedAt: r.updated_at,
  };
}

export function categoryRow(r) {
  return {
    id: r.id,
    listId: r.list_id,
    name: r.name,
    icon: r.icon,
    position: r.position,
  };
}

export function recipeCategoryRow(r) {
  return {
    id: r.id,
    kind: r.kind,
    name: r.name,
  };
}

export function favoriteRow(r) {
  return {
    id: r.id,
    name: r.name,
    categoryName: r.category_name,
    position: r.position,
    updatedAt: r.updated_at,
  };
}

export function recipeRow(r) {
  const data = r.data || {};
  return {
    id: r.id,
    // name + image are derived from the document so the app's recipe grid can
    // render without parsing the full `data` blob; `data` carries the canonical
    // recipe content (ingredients, steps, refs, timers).
    name: data.name || '',
    image: data.image || null,
    // Denormalized {id,name} (not just an id) so clients can group/filter/
    // display without a second lookup or waiting on the catalogue to sync —
    // requires the SELECT this row comes from to LEFT JOIN recipe_categories
    // twice, aliased tc_id/tc_name (type) and cc_id/cc_name (country); see
    // the recipeSelect() query builder in routes/recipes.js.
    typeCategory: r.tc_id ? { id: r.tc_id, name: r.tc_name } : null,
    countryCategory: r.cc_id ? { id: r.cc_id, name: r.cc_name } : null,
    categoryStatus: r.category_status || null,
    position: r.position,
    selected: !!r.selected,
    data,
    sourceKey: r.source_key || null,
    createdAt: r.created_at,
    updatedAt: r.updated_at,
  };
}

export function itemRow(r) {
  return {
    id: r.id,
    listId: r.list_id,
    categoryId: r.category_id,
    name: r.name,
    quantity: r.quantity,
    note: r.note,
    checked: r.checked,
    position: r.position,
    addedBy: r.added_by,
    modifiedBy: r.modified_by,
    createdAt: r.created_at,
    updatedAt: r.updated_at,
  };
}
