import { useState } from 'react';
import { api } from '../api/client';
import type { RecipeCategory } from '../api/types';

// A <select> of existing category values for one axis (kind "type" |
// "country") plus an inline "+ Ny kategori…" flow that creates the category
// server-side (case-insensitive find-or-create, see routes/recipe-categories.js)
// and selects the returned id — mirrors the Android CategoryFieldPicker.
interface Props {
  label: string;
  kind: 'type' | 'country';
  options: RecipeCategory[];
  value: string | null;
  onChange: (id: string | null) => void;
}

const NEW_VALUE = '__new__';

export function CategorySelect({ label, kind, options, value, onChange }: Props) {
  const [adding, setAdding] = useState(false);
  const [newName, setNewName] = useState('');
  const [saving, setSaving] = useState(false);
  const sorted = [...options].sort((a, b) => a.name.localeCompare(b.name, 'sv'));

  async function createAndSelect() {
    const name = newName.trim();
    if (!name) {
      setAdding(false);
      return;
    }
    setSaving(true);
    try {
      const created = await api.post<RecipeCategory>('/api/recipe-categories', { kind, name });
      onChange(created.id);
    } finally {
      setSaving(false);
      setAdding(false);
      setNewName('');
    }
  }

  if (adding) {
    return (
      <div style={{ display: 'flex', gap: 6, alignItems: 'center', marginTop: 4 }}>
        <input
          className="input"
          autoFocus
          placeholder={label}
          value={newName}
          onChange={(e) => setNewName(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter') createAndSelect();
            if (e.key === 'Escape') setAdding(false);
          }}
          style={{ flex: 1 }}
        />
        <button className="btn-ghost" onClick={createAndSelect} disabled={saving}>
          {saving ? '…' : 'Lägg till'}
        </button>
        <button className="btn-ghost" onClick={() => setAdding(false)}>
          Avbryt
        </button>
      </div>
    );
  }

  return (
    <select
      className="input"
      value={value ?? ''}
      onChange={(e) => {
        if (e.target.value === NEW_VALUE) setAdding(true);
        else onChange(e.target.value || null);
      }}
      style={{ marginTop: 4 }}
    >
      <option value="">Ingen</option>
      {sorted.map((opt) => (
        <option key={opt.id} value={opt.id}>
          {opt.name}
        </option>
      ))}
      <option value={NEW_VALUE}>+ Ny kategori…</option>
    </select>
  );
}
