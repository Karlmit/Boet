import { Link } from 'react-router-dom';
import { useBoetStore } from '../state/store';

export default function RecipesGrid() {
  const { recipes } = useBoetStore();
  const sorted = [...recipes].sort((a, b) => a.name.localeCompare(b.name, 'sv'));

  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 16 }}>
        <h1 className="headline">Recept</h1>
        <div style={{ display: 'flex', gap: 8 }}>
          <Link className="btn-ghost" to="/recipes/discover">
            Upptäck
          </Link>
          <Link className="btn-ghost" to="/recipes/new/url">
            Från länk
          </Link>
          <Link className="btn-ghost" to="/recipes/new/instagram">
            Från Instagram
          </Link>
          <Link className="btn-ghost" to="/recipes/new/ai">
            AI-import
          </Link>
          <Link className="btn-primary" to="/recipes/new">
            Nytt recept
          </Link>
        </div>
      </div>

      {sorted.length === 0 && <p className="body-text">Inga recept än.</p>}

      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(200px, 1fr))', gap: 16 }}>
        {sorted.map((recipe) => (
          <Link
            key={recipe.id}
            to={`/recipes/${recipe.id}`}
            className="card"
            style={{ textDecoration: 'none', color: 'var(--charcoal)', padding: 0, overflow: 'hidden' }}
          >
            <div
              style={{
                aspectRatio: '4/3',
                background: recipe.image ? `center/cover no-repeat url(${recipe.image})` : 'var(--leaf)',
              }}
            />
            <div style={{ padding: 12 }}>
              <div className="title">{recipe.name || 'Namnlöst recept'}</div>
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
