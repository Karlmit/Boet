// Mirrors android/app/src/main/java/se/jabba/boet/data/remote/RealtimeClient.kt:
// exponential backoff reconnect (1s -> 15s cap, doubling), re-send the last
// presence on reopen, and treat any reconnect after the first as "we may have
// missed deltas" — the caller re-fetches /api/bootstrap wholesale rather than
// trying to patch in whatever was missed.
export interface ChangeMessage {
  type: 'change';
  event: 'create' | 'update' | 'delete' | 'reorder' | 'bulk-delete';
  entity: 'list' | 'category' | 'item' | 'favorite' | 'recipe';
  data: unknown;
  ts: number;
}

export interface PresenceMember {
  memberId: string;
  name: string;
  status: 'viewing' | 'shopping';
  listId: string | null;
}

interface PresenceMessage {
  type: 'presence';
  members: PresenceMember[];
}

type ServerMessage = ChangeMessage | PresenceMessage | { type: 'pong' };

export interface BoetSocketHandlers {
  onChange: (msg: ChangeMessage) => void;
  onPresence: (members: PresenceMember[]) => void;
  onReconnect: () => void;
}

const PING_INTERVAL_MS = 20_000;
const MAX_RECONNECT_DELAY_MS = 15_000;
const INITIAL_RECONNECT_DELAY_MS = 1_000;

export class BoetSocket {
  private ws: WebSocket | null = null;
  private reconnectDelay = INITIAL_RECONNECT_DELAY_MS;
  private reconnectTimer: number | null = null;
  private pingInterval: number | null = null;
  private hasConnectedBefore = false;
  private closedByUser = false;
  private lastPresence: { status: 'viewing' | 'shopping'; listId: string | null } | null = null;
  private memberId: string;
  private name: string;
  private handlers: BoetSocketHandlers;

  constructor(memberId: string, name: string, handlers: BoetSocketHandlers) {
    this.memberId = memberId;
    this.name = name;
    this.handlers = handlers;
  }

  connect() {
    this.closedByUser = false;
    const proto = window.location.protocol === 'https:' ? 'wss' : 'ws';
    const url = `${proto}://${window.location.host}/ws?memberId=${encodeURIComponent(this.memberId)}&name=${encodeURIComponent(this.name)}`;
    const ws = new WebSocket(url);
    this.ws = ws;

    ws.onopen = () => {
      this.reconnectDelay = INITIAL_RECONNECT_DELAY_MS;
      if (this.hasConnectedBefore) this.handlers.onReconnect();
      this.hasConnectedBefore = true;
      if (this.lastPresence) this.sendPresence(this.lastPresence.status, this.lastPresence.listId);
      this.pingInterval = window.setInterval(() => {
        if (ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify({ type: 'ping' }));
      }, PING_INTERVAL_MS);
    };

    ws.onmessage = (event) => {
      let msg: ServerMessage;
      try {
        msg = JSON.parse(event.data);
      } catch {
        return;
      }
      if (msg.type === 'change') this.handlers.onChange(msg);
      else if (msg.type === 'presence') this.handlers.onPresence(msg.members);
    };

    ws.onclose = () => {
      if (this.pingInterval) window.clearInterval(this.pingInterval);
      if (this.closedByUser) return;
      this.scheduleReconnect();
    };

    ws.onerror = () => ws.close();
  }

  private scheduleReconnect() {
    if (this.reconnectTimer) return;
    this.reconnectTimer = window.setTimeout(() => {
      this.reconnectTimer = null;
      this.connect();
    }, this.reconnectDelay);
    this.reconnectDelay = Math.min(this.reconnectDelay * 2, MAX_RECONNECT_DELAY_MS);
  }

  sendPresence(status: 'viewing' | 'shopping', listId: string | null) {
    this.lastPresence = { status, listId };
    if (this.ws?.readyState === WebSocket.OPEN) {
      this.ws.send(JSON.stringify({ type: 'presence', status, listId, name: this.name }));
    }
  }

  disconnect() {
    this.closedByUser = true;
    if (this.reconnectTimer) window.clearTimeout(this.reconnectTimer);
    if (this.pingInterval) window.clearInterval(this.pingInterval);
    this.ws?.close();
  }
}
