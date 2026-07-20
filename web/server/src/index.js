import http from 'http';
import path from 'path';
import { fileURLToPath } from 'url';
import express from 'express';
import cookieParser from 'cookie-parser';
import { createProxyMiddleware } from 'http-proxy-middleware';
import { issueSession, clearSession, requireSession, isRequestAuthorized, hasValidSession } from './auth.js';
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

// Public: lets the SPA know whether it's running anonymous (read-only
// recipes) or signed-in (full household app).
app.get('/auth/me', (req, res) => {
  res.set('Cache-Control', 'no-store');
  res.json({ authenticated: hasValidSession(req) });
});

// PUBLIC allow-list — exactly these reads are reachable without a session,
// so recipes can be shared with people outside the household. Everything
// else on /api and /uploads stays behind the PIN below. recipe-categories is
// read-only category names/ids (no personal data) needed for the public
// recipe pages' Type/Country grouping + filter to render the same as for
// signed-in users.
const isPublicApiPath = (pathname, req) =>
  (req.method === 'GET' || req.method === 'HEAD') &&
  (pathname === '/api/recipes' || pathname === '/api/recipe-categories' || pathname.startsWith('/uploads/'));

const publicProxy = createProxyMiddleware({
  target: API_URL,
  changeOrigin: true,
  pathFilter: isPublicApiPath,
});
app.use(publicProxy);

// Everything else on /api and /uploads requires a valid session cookie.
// (Scoped with a wrapper instead of a mount path so the proxy keeps seeing
// the unmodified request URL.)
app.use((req, res, next) => {
  if (req.path.startsWith('/api') || req.path.startsWith('/uploads')) {
    return requireSession(req, res, next);
  }
  next();
});
const apiProxy = createProxyMiddleware({
  target: API_URL,
  changeOrigin: true,
  pathFilter: ['/api/**', '/uploads/**'],
});
app.use(apiProxy);

// The SPA shell itself is public — anonymous visitors get the read-only
// recipe pages; the SPA's own route guards send them to /login elsewhere.
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
