create or replace function public.claim_pyq_papers_for_course(
  filter_course_code text,
  batch_size integer default 1,
  include_failed boolean default true
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
    where course_code = upper(filter_course_code)
      and (
        processing_status = 'pending'
        or (include_failed and processing_status = 'failed')
      )
    order by
      case when processing_status = 'failed' then 0 else 1 end,
      updated_at asc,
      id asc
    for update skip locked
    limit least(greatest(batch_size, 1), 1)
  )
  update public.pyq_papers p
  set processing_status = 'processing',
      processing_error = null,
      updated_at = now()
  from candidates c
  where p.id = c.id
  returning p.*;
$$;

revoke all on function public.claim_pyq_papers_for_course(text, integer, boolean) from public, anon, authenticated;
