import { WebSocketServer } from 'ws';

// Real-time hub: broadcasts mutations to all connected clients and tracks
// presence (who is active, and what they're doing).
class Hub {
  constructor() {
    this.clients = new Set();        // ws sockets
    this.presence = new Map();       // memberId -> { name, status, listId, ts }
  }

  attach(server) {
    this.wss = new WebSocketServer({ server, path: '/ws' });
    this.wss.on('connection', (ws, req) => this._onConnection(ws, req));
    // Expire stale presence every 15s.
    setInterval(() => this._sweepPresence(), 15000);
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
        this._broadcastPresence();
      }
    });

    // Send current presence snapshot on connect.
    ws.send(JSON.stringify({ type: 'presence', members: this._presenceList() }));
  }

  _setPresence(memberId, data) {
    if (!memberId) return;
    this.presence.set(memberId, { ...data, ts: Date.now() });
    this._broadcastPresence();
  }

  _sweepPresence() {
    const now = Date.now();
    let changed = false;
    for (const [id, p] of this.presence) {
      if (now - p.ts > 30000) { this.presence.delete(id); changed = true; }
    }
    if (changed) this._broadcastPresence();
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
