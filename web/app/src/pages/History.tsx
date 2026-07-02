import { useEffect, useState } from 'react';
import { useBoetStore } from '../state/store';
import { api } from '../api/client';
import { getIdentity, displayName } from '../state/identity';

interface HistoryEntry {
  key: string;
  name: string;
  count: number;
  lastAdded: string;
}

export default function History() {
  const { lists } = useBoetStore();
  const identity = getIdentity()!;
  const [entries, setEntries] = useState<HistoryEntry[]>([]);
  const groceryList = lists.find((l) => l.kind === 'grocery' && !l.archived) || lists.find((l) => !l.archived);

  useEffect(() => {
    api.get<HistoryEntry[]>('/api/history?limit=60').then(setEntries);
  }, []);

  async function addAgain(name: string) {
    if (!groceryList) return;
    await api.post(`/api/lists/${groceryList.id}/items`, { name, addedBy: displayName(identity) });
  }

  return (
    <div className="page-paper" style={{ maxWidth: 560 }}>
      <h1 className="headline" style={{ marginBottom: 16 }}>
        Historik
      </h1>
      {entries.length === 0 && <p className="body-text">Inget köpt än.</p>}
      {entries.map((e) => (
        <div key={e.key} className="card" style={{ display: 'flex', alignItems: 'center', gap: 14, marginBottom: 6 }}>
          <span className="title" style={{ flex: 1 }}>
            {e.name}
          </span>
          <span className="badge">×{e.count}</span>
          <button className="btn-ghost" onClick={() => addAgain(e.name)} disabled={!groceryList} style={{ padding: '6px 12px' }}>
            Lägg till
          </button>
        </div>
      ))}
    </div>
  );
}
