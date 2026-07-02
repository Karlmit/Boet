import { useState } from 'react';
import { useParams } from 'react-router-dom';
import { nanoid } from 'nanoid';
import { useBoetStore } from '../state/store';
import { api } from '../api/client';
import { SortableList } from '../components/SortableList';
import type { Category } from '../api/types';

export default function CategoryManage() {
  const { listId } = useParams<{ listId: string }>();
  const { categories, lists } = useBoetStore();
  const [name, setName] = useState('');

  const list = lists.find((l) => l.id === listId);
  const listCategories = categories.filter((c) => c.listId === listId).sort((a, b) => a.position - b.position);

  async function addCategory() {
    const trimmed = name.trim();
    if (!trimmed || !listId) return;
    setName('');
    await api.post(`/api/lists/${listId}/categories`, { id: nanoid(), name: trimmed });
  }

  async function rename(id: string, newName: string) {
    await api.patch(`/api/categories/${id}`, { name: newName });
  }

  async function remove(id: string) {
    await api.delete(`/api/categories/${id}`);
  }

  async function reorder(orderedIds: string[]) {
    if (!listId) return;
    await api.post(`/api/lists/${listId}/categories/reorder`, { order: orderedIds });
  }

  if (!list) return <p className="body-text">Listan hittades inte.</p>;

  return (
    <div style={{ maxWidth: 480 }}>
      <h1 className="headline" style={{ marginBottom: 16 }}>
        Kategorier — {list.name}
      </h1>

      <SortableList
        items={listCategories}
        onReorder={reorder}
        renderItem={(cat, handleProps) => <CategoryRow key={cat.id} category={cat} onRename={rename} onDelete={remove} dragHandleProps={handleProps} />}
      />

      <div style={{ display: 'flex', gap: 8, marginTop: 16 }}>
        <input
          className="input"
          placeholder="Ny kategori…"
          value={name}
          onChange={(e) => setName(e.target.value)}
          onKeyDown={(e) => e.key === 'Enter' && addCategory()}
        />
        <button className="btn-primary" onClick={addCategory}>
          Lägg till
        </button>
      </div>
    </div>
  );
}

function CategoryRow({
  category,
  onRename,
  onDelete,
  dragHandleProps,
}: {
  category: Category;
  onRename: (id: string, name: string) => void;
  onDelete: (id: string) => void;
  dragHandleProps: Record<string, unknown>;
}) {
  const [value, setValue] = useState(category.name);

  return (
    <div className="card" style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 6 }}>
      <input
        className="input"
        value={value}
        onChange={(e) => setValue(e.target.value)}
        onBlur={() => value.trim() && value !== category.name && onRename(category.id, value.trim())}
        style={{ flex: 1 }}
      />
      <button className="btn-ghost" onClick={() => onDelete(category.id)}>
        Ta bort
      </button>
      <span {...dragHandleProps} style={{ cursor: 'grab', color: 'var(--stone)' }}>
        ≡
      </span>
    </div>
  );
}
