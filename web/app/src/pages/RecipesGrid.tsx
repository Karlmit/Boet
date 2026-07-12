import { Link } from 'react-router-dom';
import { useBoetStore } from '../state/store';
import { useAuth } from '../state/auth';
import { RestaurantIcon } from '../components/icons';

export default function RecipesGrid() {
  const { recipes } = useBoetStore();
  const { authenticated } = useAuth();
  const sorted = [...recipes].sort((a, b) => a.name.localeCompare(b.name, 'sv'));

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

      {sorted.length === 0 && <p className="body-text">Inga recept än.</p>}

      <div className="recipe-grid">
        {sorted.map((recipe) => (
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
