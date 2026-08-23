create or replace function public.claim_pyq_papers(
  batch_size integer default 5,
  include_failed boolean default false
)
returns setof public.pyq_papers
language sql
volatile
security definer
set search_path = public
as $$
  with candidates as (
    select id
    from public.pyq_papers
    where processing_status = 'pending'
       or (include_failed and processing_status = 'failed')
    order by created_at asc, id asc
    for update skip locked
    limit least(greatest(batch_size, 1), 10)
  )
  update public.pyq_papers p
  set processing_status = 'processing',
      processing_error = null,
      updated_at = now()
  from candidates c
  where p.id = c.id
  returning p.*;
$$;

create or replace function public.reset_stale_pyq_papers(stale_minutes integer default 45)
returns integer
language sql
volatile
security definer
set search_path = public
as $$
  with reset as (
    update public.pyq_papers
    set processing_status = 'pending',
        processing_error = 'Recovered stale processing lease',
        updated_at = now()
    where processing_status = 'processing'
      and updated_at < now() - make_interval(mins => greatest(stale_minutes, 5))
    returning id
  )
  select count(*)::integer from reset;
$$;

revoke all on function public.claim_pyq_papers(integer, boolean) from public, anon, authenticated;
revoke all on function public.reset_stale_pyq_papers(integer) from public, anon, authenticated;
