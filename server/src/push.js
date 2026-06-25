import fs from 'fs';
import { query } from './db.js';

// Push notifications via Firebase Cloud Messaging. Entirely optional: if
// FCM_SERVICE_ACCOUNT is not set (or firebase-admin can't load), every call is a
// silent no-op, so the server runs fully self-hosted without Google services.

let messaging = null;
let initTried = false;

async function getMessaging() {
  if (initTried) return messaging;
  initTried = true;
  const path = process.env.FCM_SERVICE_ACCOUNT;
  if (!path || !fs.existsSync(path)) {
    console.log('[push] FCM_SERVICE_ACCOUNT not configured — push disabled');
    return null;
  }
  try {
    const admin = (await import('firebase-admin')).default;
    const serviceAccount = JSON.parse(fs.readFileSync(path, 'utf8'));
    const app = admin.apps.length
      ? admin.apps[0]
      : admin.initializeApp({ credential: admin.credential.cert(serviceAccount) });
    messaging = app.messaging();
    console.log('[push] FCM initialized');
  } catch (err) {
    console.error('[push] FCM init failed — push disabled', err.message);
    messaging = null;
  }
  return messaging;
}

export async function registerDevice(token, memberId, platform) {
  if (!token) return;
  await query(
    `INSERT INTO device_tokens (token, member_id, platform, updated_at)
     VALUES ($1,$2,$3,now())
     ON CONFLICT (token) DO UPDATE SET member_id=$2, platform=$3, updated_at=now()`,
    [token, memberId || null, platform || 'android']
  );
}

// Notify every household member EXCEPT the actor.
export async function notifyOthers(actorMemberId, title, body, data = {}) {
  const fcm = await getMessaging();
  if (!fcm) return;
  // Exclude the actor's own devices. Tokens register their member_id lowercased,
  // but actor ids arrive verbatim from the client (e.g. "Kalle"), so compare
  // case-insensitively — otherwise the actor notifies themselves.
  const { rows } = await query(
    `SELECT token FROM device_tokens WHERE lower(member_id) IS DISTINCT FROM lower($1)`,
    [actorMemberId]
  );
  const tokens = rows.map((r) => r.token);
  if (tokens.length === 0) return;
  try {
    const res = await fcm.sendEachForMulticast({
      tokens,
      notification: { title, body },
      data: Object.fromEntries(Object.entries(data).map(([k, v]) => [k, String(v)])),
      android: { priority: 'high', notification: { channelId: 'boet_activity' } },
    });
    // Prune tokens FCM reports as permanently invalid.
    res.responses.forEach((r, i) => {
      const code = r.error?.code;
      if (code === 'messaging/registration-token-not-registered' || code === 'messaging/invalid-argument') {
        query(`DELETE FROM device_tokens WHERE token=$1`, [tokens[i]]).catch(() => {});
      }
    });
  } catch (err) {
    console.error('[push] send failed', err.message);
  }
}
