const integerEnv = (name, fallback, min, max) => {
  const value = Number.parseInt(process.env[name] ?? '', 10);
  if (!Number.isFinite(value)) return fallback;
  return Math.max(min, Math.min(max, value));
};

const floatEnv = (name, fallback, min, max) => {
  const value = Number.parseFloat(process.env[name] ?? '');
  if (!Number.isFinite(value)) return fallback;
  return Math.max(min, Math.min(max, value));
};

const geminiApiKeys = [...new Set([
  process.env.GEMINI_API_KEY,
  ...Array.from({ length: 5 }, (_, index) => process.env[`GEMINI_API_KEY${index + 1}`]),
].map((value) => value?.trim()).filter(Boolean))];

export const config = Object.freeze({
  port: integerEnv('PORT', 8080, 1, 65535),
  supabaseUrl: process.env.SUPABASE_URL?.trim() || '',
  supabaseServiceRoleKey: process.env.SUPABASE_SERVICE_ROLE_KEY?.trim() || '',
  geminiApiKey: geminiApiKeys[0] || '',
  geminiApiKeys,
  geminiDocumentModel: process.env.GEMINI_DOCUMENT_MODEL?.trim() || 'gemini-3.6-flash',
  geminiEmbeddingModel: process.env.GEMINI_EMBEDDING_MODEL?.trim() || 'gemini-embedding-2',
  geminiEmbeddingDimension: integerEnv('GEMINI_EMBEDDING_DIMENSION', 768, 768, 768),
  adminToken: process.env.PYQ_ADMIN_TOKEN?.trim() || '',
  allowedOrigins: (process.env.ALLOWED_ORIGINS || '').split(',').map((value) => value.trim()).filter(Boolean),
  timetableUrl: process.env.Timetable_url?.trim() || process.env.TIMETABLE_URL?.trim() || '',
  publicRateLimitPerMinute: integerEnv('PUBLIC_RATE_LIMIT_PER_MINUTE', 60, 10, 300),
  askRateLimitPerMinute: integerEnv('ASK_RATE_LIMIT_PER_MINUTE', 20, 1, 100),
  maxPdfBytes: integerEnv('MAX_PDF_BYTES', 50 * 1024 * 1024, 1 * 1024 * 1024, 50 * 1024 * 1024),
  maxPdfPages: integerEnv('MAX_PDF_PAGES', 1000, 1, 1000),
  maxQuestionsPerPaper: integerEnv('MAX_QUESTIONS_PER_PAPER', 300, 1, 1000),
  batchMax: integerEnv('BATCH_MAX', 5, 1, 10),
  groupStrongThreshold: floatEnv('GROUP_STRONG_THRESHOLD', 0.88, 0.8, 0.99),
  groupUncertainThreshold: floatEnv('GROUP_UNCERTAIN_THRESHOLD', 0.78, 0.5, 0.95),
  groupKeywordOverlapMin: floatEnv('GROUP_KEYWORD_OVERLAP_MIN', 0.25, 0, 1),
});

export function assertConfig(...names) {
  const missing = names.filter((name) => !config[name]);
  if (missing.length) throw new Error(`Missing server configuration: ${missing.join(', ')}`);
}

export function hasSupabase() {
  return Boolean(config.supabaseUrl && config.supabaseServiceRoleKey);
}

export function hasGemini() {
  return config.geminiApiKeys.length > 0;
}
