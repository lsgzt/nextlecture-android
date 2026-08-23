import path from 'node:path';
import { fileURLToPath } from 'node:url';
import express from 'express';
import { config, hasGemini, hasSupabase } from './config.js';
import { buildRouter } from './routes.js';

const app = express();
app.disable('x-powered-by');
app.set('trust proxy', true);
app.use((req, res, next) => {
  const origin = req.get('origin');
  if (origin && !config.allowedOrigins.includes(origin)) return res.status(403).json({ error: 'origin not allowed' });
  if (origin) res.set('access-control-allow-origin', origin);
  res.set('access-control-allow-headers', 'content-type, authorization, x-pyq-admin-token');
  res.set('access-control-allow-methods', 'GET,POST,OPTIONS');
  if (req.method === 'OPTIONS') return res.sendStatus(204);
  return next();
});
app.use(express.json({ limit: '64kb', strict: true }));
app.get('/health', (_req, res) => res.json({ ok: true, service: 'gndec-pyq-rag-api', version: '1.0.0', dependencies: { supabase: hasSupabase(), gemini: hasGemini() } }));
const apiRouter = buildRouter();
app.use('/api', apiRouter);
app.use(apiRouter);
app.use((_req, res) => res.status(404).json({ error: 'not found' }));
app.use((error, _req, res, _next) => {
  console.error('[PYQ] unhandled request error', error?.message || error);
  return res.status(500).json({ error: 'internal server error' });
});

export default app;

const currentFile = fileURLToPath(import.meta.url);
if (!process.env.VERCEL && path.resolve(process.argv[1] || '') === currentFile) {
  app.listen(config.port, () => console.log(`pyq rag api listening on :${config.port}`));
}
