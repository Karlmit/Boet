import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { api } from '../api/client';
import type { Recipe } from '../api/types';

export default function RecipeImportByUrl({
  title,
  description,
  endpoint,
  placeholder,
}: {
  title: string;
  description: string;
  endpoint: string;
  placeholder: string;
}) {
  const navigate = useNavigate();
  const [url, setUrl] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function submit() {
    if (!url.trim()) return;
    setSubmitting(true);
    setError(null);
    try {
      const recipe = await api.post<Recipe>(endpoint, { url: url.trim() });
      navigate(`/recipes/${recipe.id}`);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Något gick fel.');
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div style={{ maxWidth: 560 }}>
      <h1 className="headline" style={{ marginBottom: 8 }}>
        {title}
      </h1>
      <p className="body-text" style={{ marginBottom: 16, color: '#6e6c66' }}>
        {description}
      </p>
      <div style={{ display: 'flex', gap: 8 }}>
        <input
          className="input"
          value={url}
          onChange={(e) => setUrl(e.target.value)}
          placeholder={placeholder}
          onKeyDown={(e) => e.key === 'Enter' && submit()}
        />
        <button className="btn-primary" onClick={submit} disabled={submitting || !url.trim()}>
          {submitting ? 'Skickar…' : 'Importera'}
        </button>
      </div>
      {error && (
        <p className="body-text" style={{ color: '#a13a3a', marginTop: 8 }}>
          {error}
        </p>
      )}
    </div>
  );
}
