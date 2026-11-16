-- 014_lost_found_contact_and_delete.sql
-- Lost & Found: contact info + student self-service delete.
--
-- What changed vs 013:
--   * lost_found.contact_info — how other students reach the poster
--     (phone / email / handle). Required for new posts, blank for the few
--     pre-existing demo rows (the app hides the row when it is blank).
--   * public.lost_found_delete_pins — a hidden table (RLS on, no policies,
--     anon grants revoked) holding SHA-256(pin) per post. The PIN is chosen
--     by the poster when creating the post; it is the only way to delete the
--     post later ("item collected" / "founder returned it").
--   * public.create_lost_found_post(...) — the only insert path now. Validates
--     everything 013's anonymous insert policy validated, plus contact info
--     and a 4-8 digit PIN, writes the row and its pin hash atomically, and
--     returns the created row as JSON.
--   * public.delete_lost_found_post(id, pin) — verifies the PIN hash and
--     removes the row and its pin row (cascade). Image cleanup happens in a
--     second step by the app: Supabase's storage.protect_delete() trigger
--     forbids direct SQL deletes on storage.objects, so the migration adds an
--     RLS policy that allows deleting a lostfound image through the Storage
--     API only once no post references it anymore — i.e. after the RPC has
--     removed the row. Returns false on any wrong pin.
--   * Direct anonymous INSERT on the table is revoked (and the old insert
--     policy dropped) so every post is guaranteed to have a delete PIN.
--     Anonymous DELETE was already impossible (no delete policy; RLS on).
--
-- The 3 demo rows seeded by 013 get PIN 4321 so the delete flow can be tried
-- immediately; anyone can delete those demos, real posts are protected by
-- their own PINs.

-- 1) Contact info column ---------------------------------------------------
alter table public.lost_found
    add column if not exists contact_info text not null default ''
    check (char_length(contact_info) <= 120);

comment on column public.lost_found.contact_info is
    'How to reach the poster: phone, email or handle. Public, max 120 chars.';

-- 2) Hidden delete-PIN table ------------------------------------------------
create table if not exists public.lost_found_delete_pins (
    post_id    uuid primary key references public.lost_found(id) on delete cascade,
    pin_hash   text not null,
    created_at timestamptz not null default now()
);

alter table public.lost_found_delete_pins enable row level security;

-- No policies at all: invisible to anon/authenticated through PostgREST.
revoke all on public.lost_found_delete_pins from anon, authenticated;

-- 3) Create RPC (replaces the anonymous insert policy) -----------------------
create or replace function public.create_lost_found_post(
    p_kind        text,
    p_title       text,
    p_description text,
    p_contact     text,
    p_pin         text,
    p_image_path  text default null
) returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
    v_row public.lost_found;
begin
    if p_kind is null or p_kind not in ('lost', 'found') then
        raise exception 'kind must be lost or found';
    end if;
    if p_title is null or btrim(p_title) = '' or char_length(btrim(p_title)) > 120 then
        raise exception 'A title is required (max 120 characters)';
    end if;
    if char_length(coalesce(p_description, '')) > 1000 then
        raise exception 'Description too long (max 1000 characters)';
    end if;
    if p_contact is null or btrim(p_contact) = '' or char_length(btrim(p_contact)) > 120 then
        raise exception 'Contact info is required (max 120 characters)';
    end if;
    if p_pin is null or p_pin !~ '^[0-9]{4,8}$' then
        raise exception 'Delete PIN must be 4-8 digits';
    end if;
    if p_image_path is not null
       and p_image_path !~ '^(lost|found)/[A-Za-z0-9_.-]+\.(jpg|jpeg|png|webp)$' then
        raise exception 'Invalid image path';
    end if;

    insert into public.lost_found (kind, title, description, contact_info, image_path)
    values (p_kind, btrim(p_title), coalesce(p_description, ''), btrim(p_contact), p_image_path)
    returning * into v_row;

    insert into public.lost_found_delete_pins (post_id, pin_hash)
    values (v_row.id, encode(sha256(p_pin::bytea), 'hex'));

    return to_jsonb(v_row);
end;
$$;

revoke all on function public.create_lost_found_post(text, text, text, text, text, text)
    from public, anon, authenticated;
grant execute on function public.create_lost_found_post(text, text, text, text, text, text)
    to anon, authenticated;

-- 4) Delete RPC --------------------------------------------------------------
-- Only the poster's PIN unlocks deletion. On success the row and its pin
-- row (via the cascade) are removed. The two image objects are then cleaned
-- up by the app through the Storage API, guarded by the orphan-only delete
-- policy below; the guarded SQL attempt inside stays for environments
-- without Supabase's storage.protect_delete() trigger.
create or replace function public.delete_lost_found_post(
    p_id  uuid,
    p_pin text
) returns boolean
language plpgsql
security definer
set search_path = public
as $$
declare
    v_hash  text;
    v_image text;
    v_thumb text;
    v_rows  bigint;
begin
    if p_pin is null or p_pin !~ '^[0-9]{4,8}$' then
        return false;
    end if;

    select pin_hash into v_hash from public.lost_found_delete_pins where post_id = p_id;
    if v_hash is null or v_hash <> encode(sha256(p_pin::bytea), 'hex') then
        return false;
    end if;

    select image_path into v_image from public.lost_found where id = p_id;

    delete from public.lost_found where id = p_id;
    get diagnostics v_rows = row_count;
    if v_rows = 0 then
        return false;  -- gone already / unknown id
    end if;

    begin
        if v_image is not null then
            v_thumb := case
                when v_image ~ '\.[A-Za-z0-9]+$'
                    then regexp_replace(v_image, '(\.[A-Za-z0-9]+)$', '_t\1')
                else v_image || '_t'
            end;
            delete from storage.objects
            where bucket_id = 'lostfound'
              and name in (v_image, v_thumb);
        end if;
    exception when others then
        null;  -- orphaned objects stay unreachable; the post is still gone
    end;

    return true;
end;
$$;

revoke all on function public.delete_lost_found_post(uuid, text)
    from public, anon, authenticated;
grant execute on function public.delete_lost_found_post(uuid, text)
    to anon, authenticated;

-- 5) Close the anonymous insert path ----------------------------------------
drop policy if exists "lost_found_public_insert" on public.lost_found;
revoke insert, update, delete, truncate on public.lost_found from anon, authenticated;

-- 6) Orphan-only image cleanup -----------------------------------------------
-- After delete_lost_found_post() removes the post, its images reference
-- nothing. A delete policy scoped to exactly that state lets the app (with
-- the publishable key only) remove the now-orphaned objects via the Storage
-- API. While a post still references an image — full or thumbnail — this
-- policy refuses, so nobody can wipe images of live posts.
drop policy if exists "lostfound_orphan_image_delete" on storage.objects;
create policy "lostfound_orphan_image_delete"
    on storage.objects for delete to anon, authenticated
    using (
        bucket_id = 'lostfound'
        and not exists (
            select 1 from public.lost_found l
            where l.image_path = name
               or regexp_replace(l.image_path, '(\.[A-Za-z0-9]+)$', '_t\1') = name
        )
    );

-- 7) The demo rows seeded by 013 get a known PIN so they can be tried and
--    cleaned up (they predate contact info, hence they stay blank).
insert into public.lost_found_delete_pins (post_id, pin_hash)
select id, encode(sha256('4321'::bytea), 'hex')
from public.lost_found
on conflict (post_id) do nothing;
