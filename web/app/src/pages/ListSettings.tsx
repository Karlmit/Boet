import { useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { useBoetStore } from '../state/store';
import { api } from '../api/client';
import { fileToUploadPayload } from '../lib/image';

export default function ListSettings() {
  const { listId } = useParams<{ listId: string }>();
  const { lists } = useBoetStore();
  const navigate = useNavigate();
  const list = lists.find((l) => l.id === listId);
  const [name, setName] = useState(list?.name ?? '');
  const [uploading, setUploading] = useState(false);

  if (!list) return <p className="body-text">Listan hittades inte.</p>;

  async function saveName() {
    const trimmed = name.trim();
    if (!trimmed || !listId) return;
    await api.patch(`/api/lists/${listId}`, { name: trimmed });
  }

  async function onPickImage(e: React.ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0];
    if (!file || !listId) return;
    setUploading(true);
    try {
      const payload = await fileToUploadPayload(file);
      await api.post(`/api/lists/${listId}/background`, payload);
    } finally {
      setUploading(false);
    }
  }

  async function setBlur(bgBlur: number) {
    if (!listId) return;
    await api.patch(`/api/lists/${listId}`, { bgBlur });
  }

  async function setOverlay(bgOverlay: number) {
    if (!listId) return;
    await api.patch(`/api/lists/${listId}`, { bgOverlay });
  }

  async function archiveList() {
    if (!listId) return;
    await api.delete(`/api/lists/${listId}`);
    navigate('/lists');
  }

  return (
    <div className="page-paper" style={{ maxWidth: 480 }}>
      <h1 className="headline" style={{ marginBottom: 16 }}>
        Listinställningar
      </h1>

      <label className="label" htmlFor="list-name">
        Namn
      </label>
      <div style={{ display: 'flex', gap: 8, marginTop: 4, marginBottom: 24 }}>
        <input id="list-name" className="input" value={name} onChange={(e) => setName(e.target.value)} />
        <button className="btn-primary" onClick={saveName}>
          Spara
        </button>
      </div>

      <label className="label">Bakgrundsbild</label>
      <div style={{ marginTop: 4, marginBottom: 16 }}>
        {list.bgImageUrl && (
          <img
            src={list.bgImageUrl}
            alt=""
            style={{ width: '100%', maxHeight: 160, objectFit: 'cover', borderRadius: 'var(--radius-md)', marginBottom: 8 }}
          />
        )}
        <input type="file" accept="image/*" onChange={onPickImage} disabled={uploading} />
      </div>

      {list.bgImageUrl && (
        <>
          <label className="label">Suddighet</label>
          <input
            type="range"
            min={0}
            max={20}
            value={list.bgBlur ?? 0}
            onChange={(e) => setBlur(Number(e.target.value))}
            style={{ width: '100%', marginBottom: 16 }}
          />
          <label className="label">Mörk overlay</label>
          <input
            type="range"
            min={0}
            max={100}
            value={list.bgOverlay ?? 0}
            onChange={(e) => setOverlay(Number(e.target.value))}
            style={{ width: '100%', marginBottom: 24 }}
          />
        </>
      )}

      {list.kind !== 'grocery' && (
        <button className="btn-ghost" onClick={archiveList}>
          Arkivera lista
        </button>
      )}
    </div>
  );
}
