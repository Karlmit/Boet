import { useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { nanoid } from 'nanoid';
import { useBoetStore } from '../state/store';
import { api } from '../api/client';
import { fileToUploadPayload } from '../lib/image';
import { SortableList } from '../components/SortableList';
import type { RecipeDoc, RecipeIngredient, RecipeStep } from '../api/types';

const emptyDoc: RecipeDoc = {
  name: '',
  description: '',
  image: null,
  servings: null,
  totalTime: '',
  sourceUrl: '',
  youtubeUrl: '',
  instagramUrl: '',
  ingredients: [],
  steps: [],
};

export default function RecipeEditor() {
  const { recipeId } = useParams<{ recipeId: string }>();
  const { recipes } = useBoetStore();
  const navigate = useNavigate();
  const existing = recipeId ? recipes.find((r) => r.id === recipeId) : undefined;

  const [doc, setDoc] = useState<RecipeDoc>(existing?.data ?? emptyDoc);
  const [saving, setSaving] = useState(false);
  const [uploadingImage, setUploadingImage] = useState(false);

  useEffect(() => {
    if (existing) setDoc(existing.data);
  }, [existing]);

  function update<K extends keyof RecipeDoc>(key: K, value: RecipeDoc[K]) {
    setDoc((d) => ({ ...d, [key]: value }));
  }

  function addIngredient() {
    const ing: RecipeIngredient = { id: nanoid(), quantity: '', unit: '', food: '', display: '', note: '', sections: [] };
    update('ingredients', [...doc.ingredients, ing]);
  }

  function updateIngredient(id: string, patch: Partial<RecipeIngredient>) {
    update(
      'ingredients',
      doc.ingredients.map((ing) => {
        if (ing.id !== id) return ing;
        const next = { ...ing, ...patch };
        next.display = [next.quantity, next.unit, next.food].filter(Boolean).join(' ').trim() || next.food;
        return next;
      }),
    );
  }

  function removeIngredient(id: string) {
    update(
      'ingredients',
      doc.ingredients.filter((i) => i.id !== id),
    );
    update(
      'steps',
      doc.steps.map((s) => ({ ...s, ingredientRefs: s.ingredientRefs.filter((r) => r !== id) })),
    );
  }

  function reorderIngredients(orderedIds: string[]) {
    const byId = new Map(doc.ingredients.map((i) => [i.id, i]));
    update(
      'ingredients',
      orderedIds.map((id) => byId.get(id)!),
    );
  }

  function addStep() {
    const step: RecipeStep = { id: nanoid(), text: '', ingredientRefs: [], timerSeconds: null, title: '' };
    update('steps', [...doc.steps, step]);
  }

  function updateStep(id: string, patch: Partial<RecipeStep>) {
    update(
      'steps',
      doc.steps.map((s) => (s.id === id ? { ...s, ...patch } : s)),
    );
  }

  function removeStep(id: string) {
    update(
      'steps',
      doc.steps.filter((s) => s.id !== id),
    );
  }

  function reorderSteps(orderedIds: string[]) {
    const byId = new Map(doc.steps.map((s) => [s.id, s]));
    update(
      'steps',
      orderedIds.map((id) => byId.get(id)!),
    );
  }

  function toggleIngredientRef(stepId: string, ingredientId: string) {
    const step = doc.steps.find((s) => s.id === stepId);
    if (!step) return;
    const has = step.ingredientRefs.includes(ingredientId);
    updateStep(stepId, {
      ingredientRefs: has ? step.ingredientRefs.filter((r) => r !== ingredientId) : [...step.ingredientRefs, ingredientId],
    });
  }

  async function onPickImage(e: React.ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0];
    if (!file) return;
    setUploadingImage(true);
    try {
      const payload = await fileToUploadPayload(file);
      const { url } = await api.post<{ url: string }>('/api/media/image', payload);
      update('image', url);
    } finally {
      setUploadingImage(false);
    }
  }

  async function save() {
    if (!doc.name.trim()) return;
    setSaving(true);
    try {
      if (recipeId) {
        await api.patch(`/api/recipes/${recipeId}`, { data: doc });
        navigate(`/recipes/${recipeId}`);
      } else {
        const id = nanoid();
        await api.post('/api/recipes', { id, data: doc });
        navigate(`/recipes/${id}`);
      }
    } finally {
      setSaving(false);
    }
  }

  return (
    <div className="page-paper" style={{ maxWidth: 1100 }}>
      <h1 className="headline" style={{ marginBottom: 16 }}>
        {recipeId ? 'Redigera recept' : 'Nytt recept'}
      </h1>

      <div style={{ display: 'grid', gridTemplateColumns: '320px 1fr', gap: 32 }}>
        {/* Left: image + metadata */}
        <div>
          {doc.image && (
            <img
              src={doc.image}
              alt=""
              style={{ width: '100%', aspectRatio: '4/3', objectFit: 'cover', borderRadius: 'var(--radius-lg)', marginBottom: 12 }}
            />
          )}
          <input type="file" accept="image/*" onChange={onPickImage} disabled={uploadingImage} />

          <label className="label" style={{ display: 'block', marginTop: 20 }}>
            Namn
          </label>
          <input className="input" value={doc.name} onChange={(e) => update('name', e.target.value)} style={{ marginTop: 4 }} />

          <label className="label" style={{ display: 'block', marginTop: 16 }}>
            Beskrivning
          </label>
          <textarea
            className="input"
            value={doc.description ?? ''}
            onChange={(e) => update('description', e.target.value)}
            rows={3}
            style={{ marginTop: 4, resize: 'vertical' }}
          />

          <div style={{ display: 'flex', gap: 12, marginTop: 16 }}>
            <div style={{ flex: 1 }}>
              <label className="label">Portioner</label>
              <input
                className="input"
                type="number"
                min={1}
                value={doc.servings ?? ''}
                onChange={(e) => update('servings', e.target.value ? Number(e.target.value) : null)}
                style={{ marginTop: 4 }}
              />
            </div>
            <div style={{ flex: 1 }}>
              <label className="label">Tid</label>
              <input
                className="input"
                placeholder="1 h 30 min"
                value={doc.totalTime ?? ''}
                onChange={(e) => update('totalTime', e.target.value)}
                style={{ marginTop: 4 }}
              />
            </div>
          </div>

          <label className="label" style={{ display: 'block', marginTop: 16 }}>
            Källänk
          </label>
          <input className="input" value={doc.sourceUrl ?? ''} onChange={(e) => update('sourceUrl', e.target.value)} style={{ marginTop: 4 }} />

          <label className="label" style={{ display: 'block', marginTop: 16 }}>
            YouTube-länk
          </label>
          <input className="input" value={doc.youtubeUrl ?? ''} onChange={(e) => update('youtubeUrl', e.target.value)} style={{ marginTop: 4 }} />
        </div>

        {/* Right: ingredients + steps */}
        <div>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 8 }}>
            <h2 className="title">Ingredienser</h2>
            <button className="btn-ghost" onClick={addIngredient}>
              + Ingrediens
            </button>
          </div>
          <SortableList
            items={doc.ingredients}
            onReorder={reorderIngredients}
            renderItem={(ing, handleProps) => (
              <IngredientRow key={ing.id} ingredient={ing} onChange={updateIngredient} onRemove={removeIngredient} dragHandleProps={handleProps} />
            )}
          />

          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', margin: '32px 0 8px' }}>
            <h2 className="title">Steg</h2>
            <button className="btn-ghost" onClick={addStep}>
              + Steg
            </button>
          </div>
          <SortableList
            items={doc.steps}
            onReorder={reorderSteps}
            renderItem={(step, handleProps) => (
              <StepRow
                key={step.id}
                step={step}
                ingredients={doc.ingredients}
                onChange={updateStep}
                onRemove={removeStep}
                onToggleRef={toggleIngredientRef}
                dragHandleProps={handleProps}
              />
            )}
          />
        </div>
      </div>

      <div style={{ display: 'flex', gap: 12, marginTop: 32 }}>
        <button className="btn-primary" onClick={save} disabled={saving || !doc.name.trim()}>
          {saving ? 'Sparar…' : 'Spara recept'}
        </button>
        <button className="btn-ghost" onClick={() => navigate(-1)}>
          Avbryt
        </button>
      </div>
    </div>
  );
}

function IngredientRow({
  ingredient,
  onChange,
  onRemove,
  dragHandleProps,
}: {
  ingredient: RecipeIngredient;
  onChange: (id: string, patch: Partial<RecipeIngredient>) => void;
  onRemove: (id: string) => void;
  dragHandleProps: Record<string, unknown>;
}) {
  return (
    <div className="card" style={{ display: 'flex', gap: 8, alignItems: 'center', marginBottom: 6 }}>
      <input
        className="input"
        placeholder="Mängd"
        value={ingredient.quantity ?? ''}
        onChange={(e) => onChange(ingredient.id, { quantity: e.target.value })}
        style={{ width: 70 }}
      />
      <input
        className="input"
        placeholder="Enhet"
        value={ingredient.unit ?? ''}
        onChange={(e) => onChange(ingredient.id, { unit: e.target.value })}
        style={{ width: 80 }}
      />
      <input
        className="input"
        placeholder="Ingrediens"
        value={ingredient.food}
        onChange={(e) => onChange(ingredient.id, { food: e.target.value })}
        style={{ flex: 1 }}
      />
      <input
        className="input"
        placeholder="Anteckning"
        value={ingredient.note ?? ''}
        onChange={(e) => onChange(ingredient.id, { note: e.target.value })}
        style={{ width: 140 }}
      />
      <button onClick={() => onRemove(ingredient.id)} className="btn-ghost" aria-label="Ta bort ingrediens">
        ×
      </button>
      <span {...dragHandleProps} style={{ cursor: 'grab', color: 'var(--stone)' }}>
        ≡
      </span>
    </div>
  );
}

function StepRow({
  step,
  ingredients,
  onChange,
  onRemove,
  onToggleRef,
  dragHandleProps,
}: {
  step: RecipeStep;
  ingredients: RecipeIngredient[];
  onChange: (id: string, patch: Partial<RecipeStep>) => void;
  onRemove: (id: string) => void;
  onToggleRef: (stepId: string, ingredientId: string) => void;
  dragHandleProps: Record<string, unknown>;
}) {
  return (
    <div className="card" style={{ marginBottom: 10 }}>
      <div style={{ display: 'flex', gap: 8, alignItems: 'flex-start' }}>
        <textarea
          className="input"
          placeholder="Steg…"
          value={step.text}
          onChange={(e) => onChange(step.id, { text: e.target.value })}
          rows={2}
          style={{ flex: 1, resize: 'vertical' }}
        />
        <input
          className="input"
          type="number"
          placeholder="min"
          value={step.timerSeconds ? Math.round(step.timerSeconds / 60) : ''}
          onChange={(e) => onChange(step.id, { timerSeconds: e.target.value ? Number(e.target.value) * 60 : null })}
          style={{ width: 70 }}
          title="Timer (minuter)"
        />
        <button onClick={() => onRemove(step.id)} className="btn-ghost" aria-label="Ta bort steg">
          ×
        </button>
        <span {...dragHandleProps} style={{ cursor: 'grab', color: 'var(--stone)' }}>
          ≡
        </span>
      </div>
      {ingredients.length > 0 && (
        <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6, marginTop: 8 }}>
          {ingredients
            .filter((i) => i.food.trim())
            .map((ing) => {
              const active = step.ingredientRefs.includes(ing.id);
              return (
                <button
                  key={ing.id}
                  onClick={() => onToggleRef(step.id, ing.id)}
                  className="badge"
                  style={{
                    border: 'none',
                    cursor: 'pointer',
                    background: active ? 'var(--moss)' : 'var(--stone)',
                    color: active ? 'var(--warm-white)' : 'var(--charcoal)',
                  }}
                >
                  {ing.food}
                </button>
              );
            })}
        </div>
      )}
    </div>
  );
}
