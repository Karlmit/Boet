import http from 'http';
import express from 'express';
import { waitForDb } from './db.js';
import { initSchema } from './schema.js';
import { seed } from './seed.js';
import { hub } from './hub.js';
import { lists } from './routes/lists.js';
import { categories } from './routes/categories.js';
import { items } from './routes/items.js';
import { favorites } from './routes/favorites.js';
import { knowledge } from './routes/knowledge.js';
import { recipes } from './routes/recipes.js';
import { recipeCategories } from './routes/recipe-categories.js';
import { discover } from './routes/discover.js';
import { scrape } from './routes/scrape.js';
import { instagram } from './routes/instagram.js';
import { media, UPLOAD_DIR } from './routes/media.js';
import { display } from './routes/display.js';
import { voice } from './routes/voice.js';
import { backfillInstagramImages } from './backfill-images.js';

const PORT = parseInt(process.env.PORT || '3020', 10);

const app = express();
// Larger limit so base64 background images fit.
app.use(express.json({ limit: '15mb' }));

// Serve uploaded background images.
app.use('/uploads', express.static(UPLOAD_DIR, { maxAge: '7d' }));

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
app.use('/api', favorites);
app.use('/api', recipes);
app.use('/api', recipeCategories);
app.use('/api', discover);
app.use('/api', scrape);
app.use('/api', instagram);
app.use('/api', media);
app.use('/api', display);
app.use('/api', voice);

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
  // Best-effort, non-blocking: repair pre-mirroring Instagram recipe images.
  backfillInstagramImages().catch((err) => {
    console.warn('[boet] image backfill failed:', err.message || err);
  });
}

main().catch((err) => {
  console.error('[boet] fatal startup error', err);
  process.exit(1);
});
