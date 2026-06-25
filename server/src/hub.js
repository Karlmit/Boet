import { WebSocketServer } from 'ws';
import { notifyOthers } from './push.js';

// How long someone must be continuously in Shopping Mode before the other member
// is notified, and the minimum gap between such notifications per shopper.
const SHOPPING_NOTIFY_AFTER_MS = 60_000;       // 60 seconds
const SHOPPING_NOTIFY_COOLDOWN_MS = 60 * 60_000; // once per hour

// Real-time hub: broadcasts mutations to all connected clients and tracks
// presence (who is active, and what they're doing).
class Hub {
  constructor() {
    this.clients = new Set();        // ws sockets
    this.presence = new Map();       // memberId -> { name, status, listId, ts }
    this.shoppingSince = new Map();  // memberId -> ts shopping (continuously) began
    this.shoppingNotified = new Map(); // memberId -> ts we last sent the 60s notice
  }

  attach(server) {
    this.wss = new WebSocketServer({ server, path: '/ws' });
    this.wss.on('connection', (ws, req) => this._onConnection(ws, req));
    // Expire stale presence every 15s.
    setInterval(() => this._sweepPresence(), 15000);
    // Check for "has been shopping for 60s" every 10s.
    setInterval(() => this._checkShopping(), 10000);
  }

  _onConnection(ws, req) {
    const url = new URL(req.url, 'http://localhost');
    ws.memberId = url.searchParams.get('memberId') || null;
    ws.memberName = url.searchParams.get('name') || null;
    this.clients.add(ws);

    ws.on('message', (raw) => {
      let msg;
      try { msg = JSON.parse(raw.toString()); } catch { return; }
      if (msg.type === 'presence') {
        this._setPresence(ws.memberId, {
          name: msg.name || ws.memberName,
          status: msg.status || 'viewing', // 'viewing' | 'shopping'
          listId: msg.listId || null,
        });
      } else if (msg.type === 'ping') {
        ws.send(JSON.stringify({ type: 'pong' }));
      }
    });

    ws.on('close', () => {
      this.clients.delete(ws);
      if (ws.memberId) {
        this.presence.delete(ws.memberId);
        this.shoppingSince.delete(ws.memberId);
        this._broadcastPresence();
      }
    });

    // Send current presence snapshot on connect.
    ws.send(JSON.stringify({ type: 'presence', members: this._presenceList() }));
  }

  _setPresence(memberId, data) {
    if (!memberId) return;
    const prev = this.presence.get(memberId);
    // Track the moment a *continuous* shopping session began so we can fire the
    // 60s notice. Switching away from shopping (or disconnecting) resets it.
    if (data.status === 'shopping') {
      if (!prev || prev.status !== 'shopping') this.shoppingSince.set(memberId, Date.now());
    } else {
      this.shoppingSince.delete(memberId);
    }
    this.presence.set(memberId, { ...data, ts: Date.now() });
    this._broadcastPresence();
  }

  _sweepPresence() {
    const now = Date.now();
    let changed = false;
    for (const [id, p] of this.presence) {
      if (now - p.ts > 30000) {
        this.presence.delete(id);
        this.shoppingSince.delete(id);
        changed = true;
      }
    }
    if (changed) this._broadcastPresence();
  }

  // Notify the other member once someone has been in Shopping Mode continuously
  // for 60s — at most once per hour per shopper.
  _checkShopping() {
    const now = Date.now();
    for (const [id, p] of this.presence) {
      if (p.status !== 'shopping') continue;
      const since = this.shoppingSince.get(id);
      if (!since || now - since < SHOPPING_NOTIFY_AFTER_MS) continue;
      const last = this.shoppingNotified.get(id) || 0;
      if (now - last < SHOPPING_NOTIFY_COOLDOWN_MS) continue;
      this.shoppingNotified.set(id, now);
      const name = p.name ? p.name.charAt(0).toUpperCase() + p.name.slice(1) : 'Någon';
      notifyOthers(id, `${name} handlar`, `${name} är i butiken just nu`,
        { listId: p.listId || '', type: 'shopping_started' }).catch(() => {});
    }
  }

  _presenceList() {
    return [...this.presence.entries()].map(([memberId, p]) => ({
      memberId, name: p.name, status: p.status, listId: p.listId,
    }));
  }

  _broadcastPresence() {
    this.broadcast({ type: 'presence', members: this._presenceList() });
  }

  // Broadcast a data-change event to every client.
  broadcast(payload, exclude = null) {
    const data = JSON.stringify(payload);
    for (const ws of this.clients) {
      if (ws !== exclude && ws.readyState === ws.OPEN) {
        ws.send(data);
      }
    }
  }

  // Convenience: emit a list-scoped change.
  emit(event, entity, data) {
    this.broadcast({ type: 'change', event, entity, data, ts: Date.now() });
  }
}

export const hub = new Hub();
