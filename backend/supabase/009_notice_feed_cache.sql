-- Shared server-side cache for the normalized ERP + GNDEC homepage notice feed.
-- The backend uses the service-role client; public roles receive no table access.
create table if not exists public.notice_feed_cache (
  id text primary key,
  notices jsonb not null default '[]'::jsonb,
  fetched_at timestamptz not null,
  updated_at timestamptz not null default now()
);

alter table public.notice_feed_cache enable row level security;
revoke all on table public.notice_feed_cache from anon, authenticated;
