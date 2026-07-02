import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../api/client';
import type { MealSummary, MealDetail } from '../api/types';

interface Category {
  name: string;
  thumb: string | null;
  description: string | null;
}

export default function Discover() {
  const [featured, setFeatured] = useState<MealDetail | null>(null);
  const [grid, setGrid] = useState<MealSummary[]>([]);
  const [categories, setCategories] = useState<Category[]>([]);
  const [query, setQuery] = useState('');
  const [results, setResults] = useState<MealSummary[] | null>(null);
  const [activeCategory, setActiveCategory] = useState<string | null>(null);

  useEffect(() => {
    api.get<MealDetail | null>('/api/discover/random').then(setFeatured);
    shuffleGrid();
    api.get<Category[]>('/api/discover/categories').then(setCategories);
  }, []);

  function shuffleGrid() {
    api.get<MealSummary[]>('/api/discover/random-selection').then(setGrid);
  }

  async function search() {
    if (!query.trim()) {
      setResults(null);
      return;
    }
    setActiveCategory(null);
    const meals = await api.get<MealSummary[]>(`/api/discover/search?q=${encodeURIComponent(query.trim())}`);
    setResults(meals);
  }

  async function browseCategory(name: string) {
    setQuery('');
    setActiveCategory(name);
    const meals = await api.get<MealSummary[]>(`/api/discover/filter?category=${encodeURIComponent(name)}`);
    setResults(meals);
  }

  const shown = results ?? grid;

  return (
    <div className="page-paper" style={{ maxWidth: 'var(--content-max-width)' }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 16 }}>
        <h1 className="headline">Upptäck</h1>
        <Link className="btn-ghost" to="/recipes">
          Tillbaka till recept
        </Link>
      </div>

      {featured && (
        <Link
          to={`/recipes/discover/meal/${featured.id}`}
          className="card-floating"
          style={{ display: 'flex', gap: 16, textDecoration: 'none', color: 'var(--charcoal)', marginBottom: 24 }}
        >
          {featured.thumb && (
            <img src={featured.thumb} alt="" style={{ width: 160, height: 120, objectFit: 'cover', borderRadius: 'var(--radius-md)' }} />
          )}
          <div>
            <div className="label">Dagens slump</div>
            <div className="headline" style={{ marginTop: 4 }}>
              {featured.name}
            </div>
          </div>
        </Link>
      )}

      <div style={{ display: 'flex', gap: 8, marginBottom: 16 }}>
        <input
          className="input"
          placeholder="Sök recept…"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          onKeyDown={(e) => e.key === 'Enter' && search()}
        />
        <button className="btn-primary" onClick={search}>
          Sök
        </button>
        {results && (
          <button
            className="btn-ghost"
            onClick={() => {
              setResults(null);
              setQuery('');
              setActiveCategory(null);
            }}
          >
            Rensa
          </button>
        )}
      </div>

      <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8, marginBottom: 24 }}>
        {categories.map((c) => (
          <button
            key={c.name}
            onClick={() => browseCategory(c.name)}
            className="badge"
            style={{
              border: 'none',
              cursor: 'pointer',
              background: activeCategory === c.name ? 'var(--moss)' : 'var(--leaf)',
              color: activeCategory === c.name ? 'var(--warm-white)' : 'var(--charcoal)',
            }}
          >
            {c.name}
          </button>
        ))}
      </div>

      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 8 }}>
        <h2 className="title">{results ? `Resultat (${results.length})` : 'Slumpade förslag'}</h2>
        {!results && (
          <button className="btn-ghost" onClick={shuffleGrid}>
            Blanda
          </button>
        )}
      </div>

      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(160px, 1fr))', gap: 16 }}>
        {shown.map((meal) => (
          <Link
            key={meal.id}
            to={`/recipes/discover/meal/${meal.id}`}
            className="card"
            style={{ textDecoration: 'none', color: 'var(--charcoal)', padding: 0, overflow: 'hidden' }}
          >
            <div style={{ aspectRatio: '1/1', background: meal.thumb ? `center/cover no-repeat url(${meal.thumb})` : 'var(--leaf)' }} />
            <div style={{ padding: 10 }} className="title">
              {meal.name}
            </div>
          </Link>
        ))}
      </div>
    </div>
  );
}
