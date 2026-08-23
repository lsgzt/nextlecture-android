# PYQ RAG deployment validation

- GitHub repository: https://github.com/lsgzt/nextlecture-android
- Production branch: `main`
- Vercel deployment records are created by `vercel[bot]` after main pushes.
- Successful deployment URL observed for commit `00d6aed`: `https://gndec-pyq-rag-di3l1j5j1-lsgzts-projects.vercel.app`
- Stable alias observed after deployment: `https://gndec-pyq-rag-api.vercel.app`
- Initial deployment returned `FUNCTION_INVOCATION_FAILED` because PDF.js loaded at cold start; the implementation now lazy-loads PDF.js.
- Initial live route behavior showed `/health` working while `/api/...` returned platform 404 because Vercel mounted `api/index.js` at function root; the router is now mounted both at `/api` and root, and both forms pass locally.
- Vercel Hobby documentation search on 2026-08-23 returned a 300 second maximum duration for Functions; `vercel.json` uses `maxDuration: 300`, and `BATCH_MAX` defaults to 1 for resumable processing.
- The Vercel management connector could not list the manually connected team project, but GitHub deployment records confirm the Git integration is active.

No credentials are stored in this file.
