import { useMemo, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { useBoetStore } from '../state/store';
import { api } from '../api/client';
import { getIdentity, displayName } from '../state/identity';
import { SortableList } from '../components/SortableList';
import type { Item } from '../api/types';

export default function Home() {
  const { lists, categories, items, favorites, loaded, presence } = useBoetStore();
  const identity = getIdentity()!;
  const [searchParams, setSearchParams] = useSearchParams();
  const [addValue, setAddValue] = useState('');
  const [addQty, setAddQty] = useState('');
  const [showFavorites, setShowFavorites] = useState(false);

  const activeLists = useMemo(() => lists.filter((l) => !l.archived).sort((a, b) => a.position - b.position), [lists]);
  const listId = searchParams.get('list') || activeLists[0]?.id || null;
  const list = activeLists.find((l) => l.id === listId) || null;

  function selectList(id: string) {
    setSearchParams({ list: id });
  }

  const listCategories = useMemo(
    () => categories.filter((c) => c.listId === listId).sort((a, b) => a.position - b.position),
    [categories, listId],
  );
  const listItems = useMemo(() => items.filter((i) => i.listId === listId), [items, listId]);

  const shoppers = presence.filter((p) => p.status === 'shopping' && p.listId === listId);

  if (!loaded) return <p className="body-text">Laddar…</p>;
  if (!list) {
    return (
      <div>
        <p className="body-text">Inga listor än.</p>
        <Link className="btn-primary" to="/lists" style={{ display: 'inline-flex', marginTop: 16 }}>
          Skapa en lista
        </Link>
      </div>
    );
  }

  async function addItem() {
    const name = addValue.trim();
    if (!name || !listId) return;
    setAddValue('');
    const qty = addQty.trim();
    setAddQty('');
    await api.post(`/api/lists/${listId}/items`, { name, quantity: qty || undefined, addedBy: displayName(identity) });
  }

  async function toggleChecked(item: Item) {
    await api.patch(`/api/items/${item.id}`, { checked: !item.checked, modifiedBy: displayName(identity) });
  }

  async function deleteItem(item: Item) {
    await api.delete(`/api/items/${item.id}`);
  }

  async function clearChecked() {
    if (!listId) return;
    await api.post(`/api/lists/${listId}/clear-checked`, { mode: 'remove' });
  }

  function isFavorite(name: string) {
    return favorites.some((f) => f.name.toLowerCase() === name.toLowerCase());
  }

  async function toggleFavorite(item: Item) {
    const existing = favorites.find((f) => f.name.toLowerCase() === item.name.toLowerCase());
    if (existing) {
      await api.delete(`/api/favorites/${existing.id}`);
    } else {
      await api.post('/api/favorites', { name: item.name });
    }
  }

  async function addFavorite(name: string) {
    if (!listId) return;
    await api.post(`/api/lists/${listId}/items`, { name, addedBy: displayName(identity) });
  }

  async function removeFavorite(id: string) {
    await api.delete(`/api/favorites/${id}`);
  }

  async function reorderItems(categoryId: string | null, orderedIds: string[]) {
    if (!listId) return;
    // The backend reorders within the whole list by position index — since
    // categories are always contiguous blocks in the sort, reordering within
    // one category's slice and sending just those ids is safe: only those
    // items' `position` values change relative to each other.
    void categoryId;
    await api.post(`/api/lists/${listId}/items/reorder`, { order: orderedIds });
  }

  function itemsFor(categoryId: string | null) {
    return listItems
      .filter((i) => (i.categoryId || null) === categoryId && !i.checked)
      .sort((a, b) => a.position - b.position);
  }

  const checkedItems = listItems.filter((i) => i.checked);
  const uncategorized = itemsFor(null);

  return (
    <div style={{ maxWidth: 640 }}>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 16 }}>
        <div>
          <h1 className="headline">{list.name}</h1>
          {shoppers.length > 0 && (
            <p className="body-text" style={{ color: 'var(--moss-deep)' }}>
              {shoppers.map((s) => s.name).join(', ')} handlar
            </p>
          )}
        </div>
        <div style={{ display: 'flex', gap: 8 }}>
          {activeLists.length > 1 && (
            <select className="input" value={listId ?? ''} onChange={(e) => selectList(e.target.value)} style={{ width: 'auto' }}>
              {activeLists.map((l) => (
                <option key={l.id} value={l.id}>
                  {l.name}
                </option>
              ))}
            </select>
          )}
          <Link className="btn-ghost" to={`/lists/${list.id}/categories`}>
            Kategorier
          </Link>
          <Link className="btn-ghost" to={`/lists/${list.id}/settings`}>
            Inställningar
          </Link>
          <Link className="btn-primary" to={`/lists/${list.id}/shopping`}>
            Handla
          </Link>
        </div>
      </div>

      <div style={{ display: 'flex', gap: 8, marginBottom: 24 }}>
        <input
          className="input"
          placeholder="Lägg till vara…"
          value={addValue}
          onChange={(e) => setAddValue(e.target.value)}
          onKeyDown={(e) => e.key === 'Enter' && addItem()}
        />
        <input
          className="input"
          placeholder="Antal"
          value={addQty}
          onChange={(e) => setAddQty(e.target.value)}
          onKeyDown={(e) => e.key === 'Enter' && addItem()}
          style={{ width: 100 }}
        />
        <button className="btn-primary" onClick={addItem}>
          Lägg till
        </button>
        <button className="btn-ghost" onClick={() => setShowFavorites((v) => !v)}>
          Favoriter
        </button>
      </div>

      {showFavorites && (
        <div className="card" style={{ marginBottom: 24 }}>
          <div className="label" style={{ marginBottom: 8 }}>
            Favoriter
          </div>
          {favorites.length === 0 && <p className="body-text">Inga favoriter än — klicka på stjärnan på en vara.</p>}
          <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8 }}>
            {favorites.map((f) => (
              <span key={f.id} className="badge" style={{ display: 'inline-flex', alignItems: 'center', gap: 6 }}>
                <button onClick={() => addFavorite(f.name)} style={{ background: 'none', border: 'none', cursor: 'pointer', padding: 0 }}>
                  {f.name}
                </button>
                <button
                  onClick={() => removeFavorite(f.id)}
                  aria-label="Ta bort favorit"
                  style={{ background: 'none', border: 'none', cursor: 'pointer', color: '#6e6c66', padding: 0 }}
                >
                  ×
                </button>
              </span>
            ))}
          </div>
        </div>
      )}

      {listCategories.map((cat) => {
        const catItems = itemsFor(cat.id);
        if (catItems.length === 0) return null;
        return (
          <section key={cat.id} style={{ marginBottom: 8 }}>
            <div className="label">{cat.name}</div>
            <SortableList
              items={catItems}
              onReorder={(order) => reorderItems(cat.id, order)}
              renderItem={(item, handleProps) => (
                <ItemRow
                  item={item}
                  dragHandleProps={handleProps}
                  onToggle={toggleChecked}
                  onDelete={deleteItem}
                  isFavorite={isFavorite(item.name)}
                  onToggleFavorite={toggleFavorite}
                />
              )}
            />
          </section>
        );
      })}

      {uncategorized.length > 0 && (
        <section style={{ marginBottom: 8 }}>
          <div className="label">Övrigt</div>
          <SortableList
            items={uncategorized}
            onReorder={(order) => reorderItems(null, order)}
            renderItem={(item, handleProps) => (
              <ItemRow
                item={item}
                dragHandleProps={handleProps}
                onToggle={toggleChecked}
                onDelete={deleteItem}
                isFavorite={isFavorite(item.name)}
                onToggleFavorite={toggleFavorite}
              />
            )}
          />
        </section>
      )}

      {checkedItems.length > 0 && (
        <section style={{ marginTop: 24 }}>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
            <div className="label">Klara ({checkedItems.length})</div>
            <button className="btn-ghost" onClick={clearChecked}>
              Rensa klara
            </button>
          </div>
          {checkedItems.map((item) => (
            <ItemRow
              key={item.id}
              item={item}
              onToggle={toggleChecked}
              onDelete={deleteItem}
              isFavorite={isFavorite(item.name)}
              onToggleFavorite={toggleFavorite}
            />
          ))}
        </section>
      )}
    </div>
  );
}

function ItemRow({
  item,
  dragHandleProps,
  onToggle,
  onDelete,
  isFavorite,
  onToggleFavorite,
}: {
  item: Item;
  dragHandleProps?: Record<string, unknown>;
  onToggle: (item: Item) => void;
  onDelete: (item: Item) => void;
  isFavorite?: boolean;
  onToggleFavorite?: (item: Item) => void;
}) {
  return (
    <div className="card" style={{ display: 'flex', alignItems: 'center', gap: 14, marginBottom: 6 }}>
      <span
        role="checkbox"
        aria-checked={item.checked}
        onClick={() => onToggle(item)}
        style={{
          width: 24,
          height: 24,
          borderRadius: 'var(--radius-sm)',
          border: item.checked ? 'none' : '2px solid var(--stone)',
          background: item.checked ? 'var(--moss)' : 'transparent',
          flex: 'none',
          cursor: 'pointer',
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          color: 'var(--warm-white)',
          fontSize: 14,
        }}
      >
        {item.checked ? '✓' : ''}
      </span>
      <span
        className="title"
        style={{
          flex: 1,
          textDecoration: item.checked ? 'line-through' : 'none',
          color: item.checked ? '#6e6c66' : 'var(--charcoal)',
        }}
      >
        {item.name}
        {item.note && (
          <span className="body-text" style={{ display: 'block', color: '#6e6c66', fontSize: '0.875rem' }}>
            {item.note}
          </span>
        )}
      </span>
      {item.quantity && <span className="badge">×{item.quantity}</span>}
      {onToggleFavorite && (
        <button
          onClick={() => onToggleFavorite(item)}
          aria-label="Favorit"
          style={{
            background: 'none',
            border: 'none',
            cursor: 'pointer',
            color: isFavorite ? 'var(--moss-deep)' : 'var(--stone)',
            fontSize: '1.1rem',
          }}
        >
          {isFavorite ? '★' : '☆'}
        </button>
      )}
      <button
        onClick={() => onDelete(item)}
        aria-label="Ta bort"
        style={{ background: 'none', border: 'none', color: '#6e6c66', cursor: 'pointer', fontSize: '1.1rem' }}
      >
        ×
      </button>
      {dragHandleProps && (
        <span {...dragHandleProps} style={{ cursor: 'grab', color: 'var(--stone)' }}>
          ≡
        </span>
      )}
    </div>
  );
}
