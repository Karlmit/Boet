import { Router } from 'express';
import fs from 'fs';
import path from 'path';
import { nanoid } from 'nanoid';
import { query } from '../db.js';
import { hub } from '../hub.js';
import { listRow } from '../serialize.js';
import { registerDevice } from '../push.js';

export const media = Router();

export const UPLOAD_DIR = process.env.UPLOAD_DIR || path.resolve('data/uploads');
fs.mkdirSync(UPLOAD_DIR, { recursive: true });

const EXT = { 'image/jpeg': 'jpg', 'image/png': 'png', 'image/webp': 'webp' };

// Upload a shared background image for a list. body: { dataBase64, contentType }
media.post('/lists/:id/background', async (req, res) => {
  const { dataBase64, contentType = 'image/jpeg' } = req.body || {};
  if (!dataBase64) return res.status(400).json({ error: 'dataBase64 required' });
  const ext = EXT[contentType] || 'jpg';
  const file = `${nanoid()}.${ext}`;
  try {
    fs.writeFileSync(path.join(UPLOAD_DIR, file), Buffer.from(dataBase64, 'base64'));
  } catch (err) {
    return res.status(500).json({ error: 'write failed', message: err.message });
  }
  const url = `/uploads/${file}`;
  const { rows } = await query(
    `UPDATE lists SET bg_image_url=$1, updated_at=now() WHERE id=$2 RETURNING *`,
    [url, req.params.id]
  );
  if (rows.length === 0) return res.status(404).json({ error: 'not found' });
  const payload = listRow(rows[0]);
  hub.emit('update', 'list', payload);
  res.status(201).json(payload);
});

// Register an FCM device token.
media.post('/devices', async (req, res) => {
  const { token, memberId, platform } = req.body || {};
  if (!token) return res.status(400).json({ error: 'token required' });
  await registerDevice(token, memberId, platform);
  res.status(201).json({ ok: true });
});
