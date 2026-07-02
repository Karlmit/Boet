import { Link, useNavigate, useParams } from 'react-router-dom';
import { useBoetStore } from '../state/store';
import { api } from '../api/client';
import { getIdentity, displayName } from '../state/identity';

export default function RecipeDetail() {
  const { recipeId } = useParams<{ recipeId: string }>();
  const { recipes, lists } = useBoetStore();
  const navigate = useNavigate();
  const identity = getIdentity()!;
  const recipe = recipes.find((r) => r.id === recipeId);
  const groceryList = lists.find((l) => l.kind === 'grocery' && !l.archived) || lists.find((l) => !l.archived);

  if (!recipe) return <p className="body-text">Receptet hittades inte.</p>;
  const activeRecipe = recipe;
  const doc = activeRecipe.data;

  async function addIngredientToList(name: string) {
    if (!groceryList) return;
    await api.post(`/api/lists/${groceryList.id}/items`, { name, addedBy: displayName(identity) });
  }

  async function addAllToList() {
    if (!groceryList) return;
    const items = doc.ingredients.filter((i) => i.food.trim()).map((i) => ({ name: i.food, quantity: i.quantity || undefined }));
    if (items.length === 0) return;
    await api.post(`/api/lists/${groceryList.id}/items`, { items, addedBy: displayName(identity) });
  }

  async function toggleSelected() {
    await api.post(`/api/recipes/${activeRecipe.id}/select`, { selected: !activeRecipe.selected });
  }

  async function remove() {
    if (!confirm(`Ta bort ${doc.name}?`)) return;
    await api.delete(`/api/recipes/${activeRecipe.id}`);
    navigate('/recipes');
  }

  const ingredientById = new Map(doc.ingredients.map((i) => [i.id, i]));
  // AI linking sometimes attaches the same ingredient to every step it's still
  // physically present in (e.g. "all ingredients in the pot") instead of just the
  // step it's first added — only show a chip the first time it's referenced.
  const firstStepForIngredient = new Map<string, number>();
  doc.steps.forEach((step, i) => step.ingredientRefs.forEach((ref) => {
    if (!firstStepForIngredient.has(ref)) firstStepForIngredient.set(ref, i);
  }));

  return (
    <div className="page-paper" style={{ maxWidth: 760 }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', marginBottom: 16 }}>
        <h1 className="headline">{doc.name || 'Namnlöst recept'}</h1>
        <div style={{ display: 'flex', gap: 8 }}>
          <button className="btn-ghost" onClick={toggleSelected}>
            {recipe.selected ? 'Avmarkera' : 'Visa på köksskärm'}
          </button>
          <Link className="btn-ghost" to={`/recipes/${recipe.id}/edit`}>
            Redigera
          </Link>
          <button className="btn-ghost" onClick={remove}>
            Ta bort
          </button>
        </div>
      </div>

      {doc.aiStatus && !['done', 'degraded'].includes(doc.aiStatus) && (
        <p className="body-text" style={{ color: 'var(--moss-deep)' }}>
          {doc.aiStatus === 'error' ? `AI-import misslyckades: ${doc.aiError ?? ''}` : 'AI:n bearbetar receptet…'}
        </p>
      )}

      {doc.image && (
        <img src={doc.image} alt="" style={{ width: '100%', maxHeight: 360, objectFit: 'cover', borderRadius: 'var(--radius-lg)', marginBottom: 16 }} />
      )}

      <div style={{ display: 'flex', gap: 24, marginBottom: 16 }}>
        {doc.servings && <span className="body-text">{doc.servings} portioner</span>}
        {doc.totalTime && <span className="body-text">{doc.totalTime}</span>}
        {doc.sourceUrl && (
          <a className="body-text" href={doc.sourceUrl} target="_blank" rel="noreferrer">
            Källa
          </a>
        )}
        {doc.youtubeUrl && (
          <a className="body-text" href={doc.youtubeUrl} target="_blank" rel="noreferrer">
            YouTube
          </a>
        )}
      </div>

      {doc.description && <p className="body-text" style={{ marginBottom: 24, maxWidth: '70ch' }}>{doc.description}</p>}

      <div style={{ display: 'grid', gridTemplateColumns: '280px 1fr', gap: 32 }}>
        <div>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 8 }}>
            <h2 className="title">Ingredienser</h2>
            <button className="btn-ghost" onClick={addAllToList} disabled={!groceryList}>
              Lägg alla
            </button>
          </div>
          {doc.ingredients.map((ing) => (
            <div key={ing.id} className="card" style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 6 }}>
              <span className="body-text">{ing.display || ing.food}</span>
              <button className="btn-ghost" style={{ padding: '6px 12px' }} onClick={() => addIngredientToList(ing.food)} disabled={!groceryList}>
                +
              </button>
            </div>
          ))}
        </div>

        <div>
          <h2 className="title" style={{ marginBottom: 8 }}>
            Steg
          </h2>
          {doc.steps.map((step, i) => (
            <div key={step.id} className="card" style={{ marginBottom: 10 }}>
              {step.title && <div className="label" style={{ marginBottom: 4 }}>{step.title}</div>}
              <p className="body-text">
                <strong>{i + 1}.</strong> {step.text}
              </p>
              {step.timerSeconds ? (
                <span className="badge" style={{ marginTop: 8, display: 'inline-block' }}>
                  {Math.round(step.timerSeconds / 60)} min
                </span>
              ) : null}
              {step.ingredientRefs.some((ref) => firstStepForIngredient.get(ref) === i) && (
                <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6, marginTop: 8 }}>
                  {step.ingredientRefs.map((ref) => {
                    if (firstStepForIngredient.get(ref) !== i) return null;
                    const ing = ingredientById.get(ref);
                    if (!ing) return null;
                    return (
                      <span key={ref} className="badge">
                        {ing.display || ing.food}
                      </span>
                    );
                  })}
                </div>
              )}
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}
