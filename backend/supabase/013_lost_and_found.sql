-- 013_lost_and_found.sql
-- Lost & Found platform backend (used directly by the Android app via the
-- Supabase REST API with the project's publishable key; no app-side auth).
--
-- Table public.lost_found:
--   kind        'lost' | 'found'
--   title       1..120 chars
--   description <= 1000 chars
--   image_path  storage object path like 'found/<uuid>.jpg' (optional)
--
-- RLS: everyone may read; anonymous inserts are allowed but constrained in
-- shape (kind/title/description lengths, image_path pattern). There is NO
-- anonymous update or delete: moderation happens with the service key via
-- the Supabase dashboard.
--
-- Storage: public bucket 'lostfound'. Uploads land under lost/ or found/
-- prefixes only, are restricted to image MIME types and capped at 5 MB per
-- object (enforced server-side via storage.buckets.file_size_limit). The
-- app uploads two objects per photo: '<uuid>.jpg' (<=1280px, q72) and
-- '<uuid>_t.jpg' (<=480px, q60) so list scrolling never pulls full images.

create table if not exists public.lost_found (
    id          uuid primary key default gen_random_uuid(),
    kind        text not null check (kind in ('lost', 'found')),
    title       text not null check (char_length(title) between 1 and 120),
    description text not null default '' check (char_length(description) <= 1000),
    image_path  text,
    created_at  timestamptz not null default now()
);

create index if not exists lost_found_kind_created_idx
    on public.lost_found (kind, created_at desc);

alter table public.lost_found enable row level security;

drop policy if exists "lost_found_public_read" on public.lost_found;
create policy "lost_found_public_read"
    on public.lost_found for select
    using (true);

drop policy if exists "lost_found_public_insert" on public.lost_found;
create policy "lost_found_public_insert"
    on public.lost_found for insert
    with check (
        kind in ('lost', 'found')
        and char_length(title) between 1 and 120
        and char_length(coalesce(description, '')) <= 1000
        and (
            image_path is null
            or image_path ~ '^(lost|found)/[A-Za-z0-9_.-]+\.(jpg|jpeg|png|webp)$'
        )
    );

insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values (
    'lostfound', 'lostfound', true, 5242880,
    array['image/jpeg', 'image/png', 'image/webp']
)
on conflict (id) do update
    set public = true,
        file_size_limit = 5242880,
        allowed_mime_types = array['image/jpeg', 'image/png', 'image/webp'];

drop policy if exists "lostfound_public_read" on storage.objects;
create policy "lostfound_public_read"
    on storage.objects for select
    using (bucket_id = 'lostfound');

drop policy if exists "lostfound_public_upload" on storage.objects;
create policy "lostfound_public_upload"
    on storage.objects for insert
    with check (
        bucket_id = 'lostfound'
        and (storage.foldername(name))[1] in ('lost', 'found')
    );
