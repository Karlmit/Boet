import http from 'http';
import path from 'path';
import { fileURLToPath } from 'url';
import express from 'express';
import cookieParser from 'cookie-parser';
import { createProxyMiddleware } from 'http-proxy-middleware';
import { issueSession, clearSession, requireSession, isRequestAuthorized } from './auth.js';
import { isLocked, recordFailure, recordSuccess } from './loginAttempts.js';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const PORT = parseInt(process.env.PORT || '3021', 10);
const API_URL = process.env.API_URL || 'http://server:3020';
const WEB_PIN = process.env.WEB_PIN;
const SESSION_SECRET = process.env.SESSION_SECRET;

if (!WEB_PIN) throw new Error('WEB_PIN env var is required');
if (!SESSION_SECRET) throw new Error('SESSION_SECRET env var is required');

const app = express();
app.set('trust proxy', 1); // behind Nginx Proxy Manager

app.use(cookieParser(SESSION_SECRET));
// NOT applied globally: express.json() drains the request stream, and
// http-proxy-middleware needs that stream intact to forward POST/PATCH
// bodies to the backend — parse JSON only on the BFF's own routes below.

// Login page + its assets are the only things reachable pre-auth.
app.use(express.static(path.join(__dirname, '../public')));

app.get('/login', (req, res) => {
  res.sendFile(path.join(__dirname, '../public/login.html'));
});

app.post('/auth/login', express.json(), (req, res) => {
  const ip = req.ip;
  if (isLocked(ip)) {
    return res.status(429).json({ error: 'too_many_attempts' });
  }
  const pin = req.body?.pin;
  if (typeof pin !== 'string' || pin.length === 0 || pin !== WEB_PIN) {
    recordFailure(ip);
    return res.status(401).json({ error: 'invalid_pin' });
  }
  recordSuccess(ip);
  issueSession(res);
  res.json({ ok: true });
});

app.post('/auth/logout', (req, res) => {
  clearSession(res);
  res.json({ ok: true });
});

app.use(requireSession);

// Everything below requires a valid session cookie.
const apiProxy = createProxyMiddleware({
  target: API_URL,
  changeOrigin: true,
  pathFilter: ['/api/**', '/uploads/**'],
});
app.use(apiProxy);

const spaDist = path.join(__dirname, '../../app/dist');
app.use(express.static(spaDist));
// Client-side routing: any unmatched GET falls through to the SPA shell.
app.get('*', (req, res) => {
  res.sendFile(path.join(spaDist, 'index.html'));
});

const server = http.createServer(app);

const wsProxy = createProxyMiddleware({ target: API_URL, ws: true, changeOrigin: true });
server.on('upgrade', (req, socket, head) => {
  if (!req.url.startsWith('/ws') || !isRequestAuthorized(req)) {
    socket.destroy();
    return;
  }
  wsProxy.upgrade(req, socket, head);
});

server.listen(PORT, '0.0.0.0', () => {
  console.log(`[boet-web] listening on :${PORT}`);
});
