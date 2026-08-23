# External implementation references

## Gemini document understanding
Source: https://ai.google.dev/gemini-api/docs/document-processing

Gemini models can process PDF files with native vision and can understand text, images, diagrams, charts, and tables. The official documentation describes both inline PDF data and the Files API. PDF processing supports up to 50 MB or 1,000 pages, and the model may use native text plus rendered page images. This supports a two-stage ingestion strategy: use conventional text extraction first, then send the original PDF to a vision-capable Gemini document model when the extracted text is empty or below a quality threshold. The extraction prompt must request page-number provenance for every question.

## Gemini embeddings
Source: https://ai.google.dev/gemini-api/docs/embeddings

The current documentation lists `gemini-embedding-2` as the latest multimodal embedding model and `gemini-embedding-001` as the text-only model. `gemini-embedding-2` supports configurable output dimensions, with 768 recommended as a storage/performance option, and supports an embedding task instruction such as `task: clustering` or `task: search result`. The same model and dimension must be used for all indexed questions and queries.

## Supabase semantic search / pgvector
Sources: https://supabase.com/docs/guides/ai/semantic-search and https://supabase.com/docs/guides/database/extensions/pgvector

Supabase uses the `vector` extension for pgvector columns. Cosine distance uses the `<=>` operator. Supabase recommends putting metadata filters such as course code inside the SQL similarity function rather than applying them after an RPC result is limited. The RAG schema therefore stores 768-dimensional question embeddings and exposes a course-filtered similarity function with a bounded result count. Public read policies are limited to intentional PYQ analysis data; service-role credentials remain server-side.
