import { Router } from 'express';
import { query } from '../db.js';
import { HOUSEHOLD_ID } from '../seed.js';
import { whisperEnabled, whisperTranscribe } from '../whisper.js';
import { cleanVoice } from '../voice.js';
import { createItems } from './items.js';

export const voice = Router();

// Kitchen-display voice add: the ESP32/tablet is too slow for on-device speech
// recognition, or even the phone app's usual record → show-suggestions →
// confirm flow, so it just records a clip and ships the raw audio here. The
// server transcribes it locally (faster-whisper sidecar, see WHISPER_URL) and
// runs the transcript through the exact same cleaning pipeline the phone apps
// use for server-side voice cleaning (`cleanVoice`, ../voice.js) — but instead
// of returning suggestions for a human to approve, every cleaned item is added
// to the default grocery list immediately (no round trip the display could get
// stuck waiting on). body: { audioBase64, contentType? } -> { transcript, items, engine }
voice.post('/voice/add-from-audio', async (req, res) => {
  if (!whisperEnabled()) return res.status(503).json({ error: 'speech-to-text not configured' });

  const { audioBase64, contentType = 'audio/wav' } = req.body || {};
  if (!audioBase64) return res.status(400).json({ error: 'audioBase64 required' });

  const transcript = await whisperTranscribe(Buffer.from(audioBase64, 'base64'), contentType);
  if (!transcript) return res.status(503).json({ error: 'transcription failed' });

  const { rows: listRows } = await query(
    `SELECT id FROM lists WHERE household_id=$1 AND kind='grocery' AND archived=false
     ORDER BY position, created_at LIMIT 1`,
    [HOUSEHOLD_ID]
  );
  const list = listRows[0];
  if (!list) return res.status(404).json({ error: 'no grocery list found' });

  const { rows: cats } = await query(`SELECT id, name FROM categories WHERE list_id=$1`, [list.id]);
  const categoryIdByName = new Map(cats.map((c) => [c.name.toLowerCase(), c.id]));

  const { items, engine } = await cleanVoice(transcript, cats.map((c) => c.name));
  const incoming = items.map((it) => ({
    name: it.name,
    quantity: it.quantity,
    categoryId: it.category ? categoryIdByName.get(it.category.toLowerCase()) : undefined,
  }));
  const created = await createItems(list.id, incoming, 'Köksskärmen');

  res.status(201).json({ transcript, items: created, engine });
});
