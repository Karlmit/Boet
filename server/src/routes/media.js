import { Router } from 'express';
import fs from 'fs';
import path from 'path';
import { nanoid } from 'nanoid';
import { query } from '../db.js';
import { hub } from '../hub.js';
import { listRow } from '../serialize.js';
import { registerDevice } from '../push.js';
import { assertUrlAllowed } from '../ssrf-guard.js';

export const media = Router();

export const UPLOAD_DIR = process.env.UPLOAD_DIR || path.resolve('data/uploads');
fs.mkdirSync(UPLOAD_DIR, { recursive: true });

const EXT = { 'image/jpeg': 'jpg', 'image/png': 'png', 'image/webp': 'webp' };

// Download an external image and store it locally, returning its /uploads
// URL — used to mirror CDN thumbnails (e.g. Instagram's) that a browser's
// <img> tag can't hotlink directly. Some CDNs send a Cross-Origin-Resource-
// Policy header that blocks cross-origin embeds; a phone app fetching the
// same URL over plain HTTP isn't affected, so this only showed up once the
// web app started rendering these recipes. Best-effort: never throws, so a
// mirror failure degrades to a missing image rather than blocking the import.
export async function mirrorImage(url) {
  if (!url) return null;
  try {
    await assertUrlAllowed(url);
    const res = await fetch(url);
    if (!res.ok) return null;
    const contentType = (res.headers.get('content-type') || 'image/jpeg').split(';')[0].trim();
    const ext = EXT[contentType] || 'jpg';
    const buf = Buffer.from(await res.arrayBuffer());
    const file = `${nanoid()}.${ext}`;
    fs.writeFileSync(path.join(UPLOAD_DIR, file), buf);
    return `/uploads/${file}`;
  } catch {
    return null;
  }
}

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

// Upload any image and get back its URL, independent of any specific entity —
// used by the recipe editor, where a brand-new recipe may not have a server row
// yet when the user picks a photo (unlike the list-background upload above,
// which is always tied to an existing list id). The client (Images.kt
// compressImageToBase64) downscales + JPEG-compresses before this ever runs, so
// there's no server-side image processing to keep this endpoint dependency-free.
media.post('/media/image', async (req, res) => {
  const { dataBase64, contentType = 'image/jpeg' } = req.body || {};
  if (!dataBase64) return res.status(400).json({ error: 'dataBase64 required' });
  const ext = EXT[contentType] || 'jpg';
  const file = `${nanoid()}.${ext}`;
  try {
    fs.writeFileSync(path.join(UPLOAD_DIR, file), Buffer.from(dataBase64, 'base64'));
  } catch (err) {
    return res.status(500).json({ error: 'write failed', message: err.message });
  }
  res.status(201).json({ url: `/uploads/${file}` });
});

// Register an FCM device token.
media.post('/devices', async (req, res) => {
  const { token, memberId, platform } = req.body || {};
  if (!token) return res.status(400).json({ error: 'token required' });
  await registerDevice(token, memberId, platform);
  res.status(201).json({ ok: true });
});
