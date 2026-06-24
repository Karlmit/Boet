import http from 'http';
import express from 'express';
import { waitForDb } from './db.js';
import { initSchema } from './schema.js';
import { seed } from './seed.js';
import { hub } from './hub.js';
import { lists } from './routes/lists.js';
import { categories } from './routes/categories.js';
import { items } from './routes/items.js';
import { knowledge } from './routes/knowledge.js';

const PORT = parseInt(process.env.PORT || '3020', 10);

const app = express();
app.use(express.json({ limit: '2mb' }));

// Permissive CORS — single household, self-hosted, behind the user's own server.
app.use((req, res, next) => {
  res.header('Access-Control-Allow-Origin', '*');
  res.header('Access-Control-Allow-Methods', 'GET,POST,PATCH,DELETE,OPTIONS');
  res.header('Access-Control-Allow-Headers', 'Content-Type');
  if (req.method === 'OPTIONS') return res.sendStatus(204);
  next();
});

app.get('/health', (req, res) => res.json({ ok: true, service: 'boet', ts: Date.now() }));

app.use('/api', knowledge);
app.use('/api', lists);
app.use('/api', categories);
app.use('/api', items);

// Central error handler so a thrown query never crashes the process.
app.use((err, req, res, next) => {
  console.error('[error]', err);
  res.status(500).json({ error: 'internal', message: err.message });
});

const server = http.createServer(app);
hub.attach(server);

async function main() {
  console.log('[boet] waiting for database…');
  await waitForDb();
  console.log('[boet] initializing schema…');
  await initSchema();
  await seed();
  server.listen(PORT, '0.0.0.0', () => {
    console.log(`[boet] server listening on :${PORT} (REST + /ws)`);
  });
}

main().catch((err) => {
  console.error('[boet] fatal startup error', err);
  process.exit(1);
});
