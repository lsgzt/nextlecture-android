-- Shared server-side cache for the official GNDEC holiday list.
-- The backend uses the service-role client; public roles receive no table access.
create table if not exists public.holiday_feed_cache (
  id text primary key,
  holidays jsonb not null default '[]'::jsonb,
  fetched_at timestamptz not null,
  updated_at timestamptz not null default now()
);

alter table public.holiday_feed_cache enable row level security;
revoke all on table public.holiday_feed_cache from anon, authenticated;
