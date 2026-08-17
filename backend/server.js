/**
 * GNDEC Timetable Normalizer backend.
 *
 * Purpose: allow the Android app to normalize ambiguous timetable cells
 * without embedding a Groq API key in the APK.
 *
 * This service exposes ONLY /normalize. It is NOT a general Groq proxy:
 *  - fixed system prompt
 *  - fixed request shape ({ cell, model })
 *  - strict response validation
 *
 * The Groq key lives ONLY in the GROQ_API_KEY environment variable.
 */
const express = require("express");

const GROQ_API_KEY = process.env.GROQ_API_KEY;
const PORT = process.env.PORT || 8080;
const ALLOWED_MODELS = (process.env.ALLOWED_MODELS || "")
  .split(",").map((s) => s.trim()).filter(Boolean);

const app = express();
app.use(express.json({ limit: "32kb" }));

const SYSTEM_PROMPT = [
  "You normalize a single college timetable cell into structured JSON.",
  "Respond with ONLY a JSON object with exactly these keys:",
  '{"subject": string|null, "teacher": string|null, "venue": string|null, "lecture_type": string|null}',
  "Rules: never invent information; use null when uncertain; preserve original naming;",
  "distinguish rooms (e.g. F106, S205, LAB names) from teacher names; lecture_type is one of Lecture, Practical, Tutorial or null.",
].join(" ");

const ALLOWED_KEYS = ["subject", "teacher", "venue", "lecture_type"];

function validateFields(obj) {
  if (obj === null || typeof obj !== "object" || Array.isArray(obj)) return null;
  const out = {};
  for (const k of Object.keys(obj)) {
    if (!ALLOWED_KEYS.includes(k)) return null; // hallucinated key -> reject
    const v = obj[k];
    out[k] = typeof v === "string" && v.trim().length > 0 && v.length <= 120 ? v.trim() : null;
  }
  return out;
}

app.get("/health", (_req, res) => res.json({ ok: true }));

app.post("/normalize", async (req, res) => {
  if (!GROQ_API_KEY) return res.status(500).json({ error: "server misconfigured" });
  const { cell, model } = req.body || {};
  if (typeof cell !== "string" || cell.trim().length === 0 || cell.length > 2000) {
    return res.status(400).json({ error: "invalid cell" });
  }
  const chosenModel = typeof model === "string" && model.length > 0 ? model : "llama-3.1-8b-instant";
  if (ALLOWED_MODELS.length > 0 && !ALLOWED_MODELS.includes(chosenModel)) {
    return res.status(400).json({ error: "model not allowed" });
  }
  try {
    const r = await fetch("https://api.groq.com/openai/v1/chat/completions", {
      method: "POST",
      headers: { "Authorization": `Bearer ${GROQ_API_KEY}`, "Content-Type": "application/json" },
      body: JSON.stringify({
        model: chosenModel,
        temperature: 0,
        response_format: { type: "json_object" },
        messages: [
          { role: "system", content: SYSTEM_PROMPT },
          { role: "user", content: cell },
        ],
      }),
    });
    if (!r.ok) return res.status(502).json({ error: `groq error ${r.status}` });
    const data = await r.json();
    const content = data?.choices?.[0]?.message?.content;
    if (typeof content !== "string") return res.status(502).json({ error: "empty groq response" });
    let parsed;
    try { parsed = JSON.parse(content); } catch { return res.status(502).json({ error: "invalid groq json" }); }
    const fields = validateFields(parsed);
    if (!fields) return res.status(502).json({ error: "groq response failed validation" });
    return res.json(fields);
  } catch (e) {
    return res.status(502).json({ error: "groq unreachable" });
  }
});

app.listen(PORT, () => console.log(`normalizer listening on :${PORT}`));
