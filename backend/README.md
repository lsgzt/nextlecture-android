# GNDEC Timetable Normalizer Backend

Restricted backend used when the app user has NOT provided their own Groq API key.

- Stores `GROQ_API_KEY` as a server-side environment variable only (never committed).
- Exposes a single endpoint: `POST /normalize` with body `{ "cell": "...", "model": "..." }`.
- Not a general-purpose Groq proxy: fixed prompt, fixed schema, validated output.
- Optional `ALLOWED_MODELS` env var (comma-separated) restricts which models clients may request.

## Run

```bash
cp .env.example .env   # fill in your real key
npm install
npm start
```

Deploy anywhere (Render/Fly/Railway/VPS) and set the resulting URL in the app under
Settings → AI & Timetable Parsing → Backend URL.
