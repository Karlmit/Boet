import { useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { useBoetStore } from '../state/store';
import { useAuth } from '../state/auth';
import { RestaurantIcon } from '../components/icons';
import type { Recipe } from '../api/types';

type FilterMode = 'type' | 'country';

interface RecipeGroup {
  id: string;
  name: string;
  recipes: Recipe[];
}

const UNCATEGORIZED_ID = '__uncategorized';

export default function RecipesGrid() {
  const { recipes, recipeCategories } = useBoetStore();
  const { authenticated } = useAuth();
  const [mode, setMode] = useState<FilterMode>('type');
  const [selectedId, setSelectedId] = useState<string | null>(null);

  const activeOptions = useMemo(
    () => recipeCategories.filter((c) => c.kind === mode).sort((a, b) => a.name.localeCompare(b.name, 'sv')),
    [recipeCategories, mode],
  );

  function changeMode(next: FilterMode) {
    setMode(next);
    // A specific selection only makes sense within the mode it was picked in.
    setSelectedId(null);
  }

  const visible = useMemo(() => {
    if (!selectedId) return recipes;
    return recipes.filter((r) => {
      const catId = (mode === 'type' ? r.typeCategory?.id : r.countryCategory?.id) ?? null;
      return catId === (selectedId === UNCATEGORIZED_ID ? null : selectedId);
    });
  }, [recipes, selectedId, mode]);

  const groups = useMemo<RecipeGroup[]>(() => {
    const grouped = new Map<string | null, Recipe[]>();
    for (const r of visible) {
      const catId = (mode === 'type' ? r.typeCategory?.id : r.countryCategory?.id) ?? null;
      const list = grouped.get(catId);
      if (list) list.push(r);
      else grouped.set(catId, [r]);
    }
    const byId = new Map(activeOptions.map((c) => [c.id, c.name]));
    const named: RecipeGroup[] = [];
    for (const [catId, list] of grouped) {
      if (catId == null) continue;
      const name = byId.get(catId);
      if (!name) continue; // stale/unknown id — shouldn't happen, skip defensively
      named.push({ id: catId, name, recipes: [...list].sort((a, b) => a.name.localeCompare(b.name, 'sv')) });
    }
    named.sort((a, b) => a.name.localeCompare(b.name, 'sv'));
    const uncategorized = grouped.get(null) ?? [];
    if (uncategorized.length === 0) return named;
    return [
      ...named,
      { id: UNCATEGORIZED_ID, name: 'Okategoriserad', recipes: [...uncategorized].sort((a, b) => a.name.localeCompare(b.name, 'sv')) },
    ];
  }, [visible, activeOptions, mode]);

  return (
    <div className="page-paper" style={{ maxWidth: 'var(--content-max-width)' }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', flexWrap: 'wrap', gap: 12, marginBottom: 16 }}>
        <h1 className="headline">Recept</h1>
        {authenticated && (
          <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8 }}>
            <Link className="btn-ghost btn-small" to="/recipes/discover">
              Upptäck
            </Link>
            <Link className="btn-ghost btn-small" to="/recipes/new/url">
              Från länk
            </Link>
            <Link className="btn-ghost btn-small" to="/recipes/new/instagram">
              Från Instagram
            </Link>
            <Link className="btn-ghost btn-small" to="/recipes/new/ai">
              AI-import
            </Link>
            <Link className="btn-primary btn-small" to="/recipes/new">
              Nytt recept
            </Link>
          </div>
        )}
      </div>

      <div className="recipe-filter-row">
        <div className="filter-toggle">
          <button className={`filter-toggle-btn${mode === 'type' ? ' active' : ''}`} onClick={() => changeMode('type')}>
            Typ
          </button>
          <button className={`filter-toggle-btn${mode === 'country' ? ' active' : ''}`} onClick={() => changeMode('country')}>
            Land
          </button>
        </div>
        <select
          className="input"
          style={{ maxWidth: 220 }}
          value={selectedId ?? ''}
          onChange={(e) => setSelectedId(e.target.value || null)}
        >
          <option value="">Alla</option>
          {activeOptions.map((c) => (
            <option key={c.id} value={c.id}>
              {c.name}
            </option>
          ))}
        </select>
      </div>

      {groups.length === 0 && (
        <p className="body-text">{selectedId ? 'Inga recept i den här kategorin.' : 'Inga recept än.'}</p>
      )}

      {groups.map((group) => (
        <div key={group.id} className="recipe-group-card">
          <div className="recipe-group-title">{group.name}</div>
          <div className="recipe-grid">
            {group.recipes.map((recipe) => (
              <Link key={recipe.id} to={`/recipes/${recipe.id}`} className="recipe-card">
                <div className="recipe-card-image">
                  {recipe.image ? <img src={recipe.image} alt="" loading="lazy" /> : <RestaurantIcon size={32} />}
                </div>
                <div className="recipe-card-body">
                  <div className="recipe-card-title">{recipe.name || 'Namnlöst recept'}</div>
                  {recipe.data.aiStatus && !['done', 'degraded'].includes(recipe.data.aiStatus) && (
                    <div className="label" style={{ color: 'var(--moss-deep)', marginTop: 4 }}>
                      {aiStatusLabel(recipe.data.aiStatus)}
                    </div>
                  )}
                </div>
              </Link>
            ))}
          </div>
        </div>
      ))}
    </div>
  );
}

function aiStatusLabel(status: string) {
  switch (status) {
    case 'queued':
      return 'Väntar…';
    case 'parsing_cloud':
    case 'parsing_local':
      return 'Tolkar…';
    case 'translating':
      return 'Översätter…';
    case 'error':
      return 'Fel vid import';
    default:
      return status;
  }
}
