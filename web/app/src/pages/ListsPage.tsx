import { useState } from 'react';
import { Link } from 'react-router-dom';
import { nanoid } from 'nanoid';
import { useBoetStore } from '../state/store';
import { api } from '../api/client';
import { SortableList } from '../components/SortableList';
import type { BoetList } from '../api/types';

export default function ListsPage() {
  const { lists } = useBoetStore();
  const [name, setName] = useState('');
  const [sortPrompt, setSortPrompt] = useState('');
  const [showArchived, setShowArchived] = useState(false);

  const active = lists.filter((l) => !l.archived).sort((a, b) => a.position - b.position);
  const archived = lists.filter((l) => l.archived);

  async function createList() {
    const trimmed = name.trim();
    if (!trimmed) return;
    setName('');
    const prompt = sortPrompt.trim();
    setSortPrompt('');
    await api.post('/api/lists', { id: nanoid(), name: trimmed, kind: 'custom', sortPrompt: prompt || undefined });
  }

  async function archive(id: string) {
    await api.delete(`/api/lists/${id}`);
  }

  async function restore(id: string) {
    await api.post(`/api/lists/${id}/restore`);
  }

  async function reorder(orderedIds: string[]) {
    await api.post('/api/lists/reorder', { order: orderedIds });
  }

  return (
    <div style={{ maxWidth: 560 }}>
      <h1 className="headline" style={{ marginBottom: 16 }}>
        Listor
      </h1>

      <SortableList
        items={active}
        onReorder={reorder}
        renderItem={(list, handleProps) => <ListRow key={list.id} list={list} onArchive={archive} dragHandleProps={handleProps} />}
      />

      <div className="card" style={{ marginTop: 16, display: 'flex', flexDirection: 'column', gap: 8 }}>
        <input className="input" placeholder="Ny lista…" value={name} onChange={(e) => setName(e.target.value)} />
        <input
          className="input"
          placeholder="Valfritt: beskriv hur den ska sorteras (t.ex. 'IKEA-avdelningar')"
          value={sortPrompt}
          onChange={(e) => setSortPrompt(e.target.value)}
        />
        <button className="btn-primary" onClick={createList} style={{ alignSelf: 'flex-start' }}>
          Skapa lista
        </button>
      </div>

      {archived.length > 0 && (
        <div style={{ marginTop: 24 }}>
          <button className="btn-ghost" onClick={() => setShowArchived((v) => !v)}>
            {showArchived ? 'Dölj arkiverade' : `Visa arkiverade (${archived.length})`}
          </button>
          {showArchived &&
            archived.map((list) => (
              <div key={list.id} className="card" style={{ display: 'flex', justifyContent: 'space-between', marginTop: 8 }}>
                <span className="title">{list.name}</span>
                <button className="btn-ghost" onClick={() => restore(list.id)}>
                  Återställ
                </button>
              </div>
            ))}
        </div>
      )}
    </div>
  );
}

function ListRow({
  list,
  onArchive,
  dragHandleProps,
}: {
  list: BoetList;
  onArchive: (id: string) => void;
  dragHandleProps: Record<string, unknown>;
}) {
  return (
    <div className="card" style={{ display: 'flex', alignItems: 'center', gap: 14, marginBottom: 6 }}>
      <Link to={`/?list=${list.id}`} className="title" style={{ flex: 1, color: 'var(--charcoal)', textDecoration: 'none' }}>
        {list.name}
      </Link>
      <Link className="btn-ghost" to={`/lists/${list.id}/categories`}>
        Kategorier
      </Link>
      <Link className="btn-ghost" to={`/lists/${list.id}/settings`}>
        Inställningar
      </Link>
      {list.kind !== 'grocery' && (
        <button className="btn-ghost" onClick={() => onArchive(list.id)}>
          Arkivera
        </button>
      )}
      <span {...dragHandleProps} style={{ cursor: 'grab', color: 'var(--stone)' }}>
        ≡
      </span>
    </div>
  );
}
