import { useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { api } from '../api/client';
import type { MealDetail as MealDetailType, Recipe } from '../api/types';

export default function MealDetail() {
  const { mealId } = useParams<{ mealId: string }>();
  const navigate = useNavigate();
  const [meal, setMeal] = useState<MealDetailType | null>(null);
  const [importing, setImporting] = useState(false);

  useEffect(() => {
    if (!mealId) return;
    api.get<MealDetailType>(`/api/discover/meal/${mealId}`).then(setMeal);
  }, [mealId]);

  async function importMeal() {
    if (!mealId) return;
    setImporting(true);
    try {
      const recipe = await api.post<Recipe>('/api/discover/import', { mealId });
      navigate(`/recipes/${recipe.id}`);
    } finally {
      setImporting(false);
    }
  }

  if (!meal) return <p className="body-text">Laddar…</p>;

  return (
    <div className="page-paper" style={{ maxWidth: 640 }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', marginBottom: 16 }}>
        <h1 className="headline">{meal.name}</h1>
        <button className="btn-primary" onClick={importMeal} disabled={importing}>
          {importing ? 'Importerar…' : 'Importera recept'}
        </button>
      </div>

      {meal.thumb && <img src={meal.thumb} alt="" style={{ width: '100%', maxHeight: 320, objectFit: 'cover', borderRadius: 'var(--radius-lg)', marginBottom: 16 }} />}

      <div style={{ display: 'flex', gap: 16, marginBottom: 16 }}>
        {meal.category && <span className="badge">{meal.category}</span>}
        {meal.area && <span className="badge">{meal.area}</span>}
        {meal.tags.map((t) => (
          <span key={t} className="badge">
            {t}
          </span>
        ))}
      </div>

      <h2 className="title" style={{ marginBottom: 8 }}>
        Ingredienser
      </h2>
      {meal.ingredients.map((ing, i) => (
        <div key={i} className="body-text" style={{ marginBottom: 4 }}>
          {ing.measure} {ing.food}
        </div>
      ))}

      <h2 className="title" style={{ margin: '24px 0 8px' }}>
        Instruktioner
      </h2>
      <p className="body-text" style={{ whiteSpace: 'pre-wrap', maxWidth: '70ch' }}>
        {meal.instructions}
      </p>

      {meal.youtube && (
        <a className="body-text" href={meal.youtube} target="_blank" rel="noreferrer" style={{ display: 'block', marginTop: 16 }}>
          Se på YouTube
        </a>
      )}
    </div>
  );
}
