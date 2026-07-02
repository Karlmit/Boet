import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { api } from '../api/client';
import type { Recipe } from '../api/types';

export default function RecipeImportAi() {
  const navigate = useNavigate();
  const [text, setText] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function submit() {
    if (!text.trim()) return;
    setSubmitting(true);
    setError(null);
    try {
      const recipe = await api.post<Recipe>('/api/recipes/parse-async', { text });
      navigate(`/recipes/${recipe.id}`);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Något gick fel.');
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div style={{ maxWidth: 640 }}>
      <h1 className="headline" style={{ marginBottom: 8 }}>
        Importera med AI
      </h1>
      <p className="body-text" style={{ marginBottom: 16, color: '#6e6c66' }}>
        Klistra in receptet som text (t.ex. från ett mejl eller en anteckning) — AI:n strukturerar ingredienser och steg åt
        dig. Du ser förloppet live på receptets sida efter du skickat.
      </p>
      <textarea
        className="input"
        rows={14}
        value={text}
        onChange={(e) => setText(e.target.value)}
        placeholder="Klistra in receptet här…"
        style={{ resize: 'vertical' }}
      />
      {error && (
        <p className="body-text" style={{ color: '#a13a3a', marginTop: 8 }}>
          {error}
        </p>
      )}
      <button className="btn-primary" onClick={submit} disabled={submitting || !text.trim()} style={{ marginTop: 16 }}>
        {submitting ? 'Skickar…' : 'Importera'}
      </button>
    </div>
  );
}
