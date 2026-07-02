import { useEffect, useMemo, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { useBoetStore } from '../state/store';
import { api } from '../api/client';
import { getIdentity, displayName } from '../state/identity';
import type { Item } from '../api/types';

export default function ShoppingMode() {
  const { listId } = useParams<{ listId: string }>();
  const { lists, categories, items, sendPresence } = useBoetStore();
  const identity = getIdentity()!;
  const [hideDone, setHideDone] = useState(true);

  const list = lists.find((l) => l.id === listId);
  const listCategories = categories.filter((c) => c.listId === listId).sort((a, b) => a.position - b.position);
  const listItems = items.filter((i) => i.listId === listId);
  const remaining = listItems.filter((i) => !i.checked).length;

  useEffect(() => {
    if (!listId) return;
    sendPresence('shopping', listId);
    return () => sendPresence('viewing', null);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [listId]);

  const grouped = useMemo(() => {
    const byCategory = new Map<string | null, Item[]>();
    for (const cat of listCategories) byCategory.set(cat.id, []);
    byCategory.set(null, []);
    for (const item of listItems) {
      if (hideDone && item.checked) continue;
      const key = item.categoryId && byCategory.has(item.categoryId) ? item.categoryId : null;
      byCategory.get(key)!.push(item);
    }
    for (const arr of byCategory.values()) arr.sort((a, b) => a.position - b.position);
    return byCategory;
  }, [listItems, listCategories, hideDone]);

  async function toggle(item: Item) {
    await api.patch(`/api/items/${item.id}`, { checked: !item.checked, modifiedBy: displayName(identity) });
  }

  if (!list) return <p className="body-text">Listan hittades inte.</p>;

  return (
    <div
      style={{
        position: 'fixed',
        inset: 0,
        background: 'var(--night-base)',
        color: '#ddd9d2',
        overflowY: 'auto',
        padding: 24,
      }}
    >
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 24 }}>
        <Link to={`/?list=${list.id}`} className="btn-ghost" style={{ background: 'var(--night-surface)', color: '#ddd9d2', border: 'none' }}>
          Stäng
        </Link>
        <span className="shopping-text">{remaining} kvar</span>
        <button
          className="btn-ghost"
          style={{ background: 'var(--night-surface)', color: '#ddd9d2', border: 'none' }}
          onClick={() => setHideDone((v) => !v)}
        >
          {hideDone ? 'Visa klara' : 'Dölj klara'}
        </button>
      </div>

      {listCategories.map((cat) => {
        const catItems = grouped.get(cat.id) || [];
        if (catItems.length === 0) return null;
        return (
          <section key={cat.id} style={{ marginBottom: 24 }}>
            <div className="label" style={{ color: 'var(--sage)' }}>
              {cat.name}
            </div>
            {catItems.map((item) => (
              <ShoppingRow key={item.id} item={item} onToggle={toggle} />
            ))}
          </section>
        );
      })}

      {(grouped.get(null) || []).length > 0 && (
        <section style={{ marginBottom: 24 }}>
          <div className="label" style={{ color: 'var(--sage)' }}>
            Övrigt
          </div>
          {(grouped.get(null) || []).map((item) => (
            <ShoppingRow key={item.id} item={item} onToggle={toggle} />
          ))}
        </section>
      )}
    </div>
  );
}

function ShoppingRow({ item, onToggle }: { item: Item; onToggle: (item: Item) => void }) {
  return (
    <div
      onClick={() => onToggle(item)}
      data-done={item.checked}
      style={{
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'space-between',
        background: 'var(--night-surface)',
        padding: '20px 24px',
        borderRadius: 'var(--radius-md)',
        marginBottom: 8,
        cursor: 'pointer',
      }}
    >
      <span
        className="shopping-text"
        style={{
          textDecoration: item.checked ? 'line-through' : 'none',
          color: item.checked ? '#6e7a68' : '#ddd9d2',
        }}
      >
        {item.name}
      </span>
      {item.quantity && <span style={{ fontSize: '1.125rem', color: 'var(--sage)', fontWeight: 600 }}>×{item.quantity}</span>}
    </div>
  );
}
