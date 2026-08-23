create table if not exists public.pyq_course_retry_requests (
  course_code text primary key,
  requested_at timestamptz not null default now()
);

alter table public.pyq_course_retry_requests enable row level security;
revoke all on table public.pyq_course_retry_requests from public, anon, authenticated;

create or replace function public.claim_pyq_course_retry(
  filter_course_code text,
  cooldown_minutes integer default 20
)
returns table(accepted boolean, cooldown_until timestamptz)
language plpgsql
volatile
security definer
set search_path = public
as $$
declare
  normalized_course text := upper(trim(filter_course_code));
  window_minutes integer := least(greatest(coalesce(cooldown_minutes, 20), 15), 30);
  previous_request timestamptz;
  inserted_request timestamptz;
begin
  if normalized_course is null or normalized_course !~ '^[A-Z]{2,12}-[0-9]{2,4}$' then
    raise exception 'invalid course code';
  end if;

  insert into public.pyq_course_retry_requests(course_code, requested_at)
  values (normalized_course, now())
  on conflict (course_code) do nothing
  returning requested_at into inserted_request;

  if found then
    return query select true, inserted_request + make_interval(mins => window_minutes);
    return;
  end if;

  select requested_at
    into previous_request
    from public.pyq_course_retry_requests
   where course_code = normalized_course
   for update;

  if previous_request <= now() - make_interval(mins => window_minutes) then
    update public.pyq_course_retry_requests
       set requested_at = now()
     where course_code = normalized_course
     returning requested_at into inserted_request;
    return query select true, inserted_request + make_interval(mins => window_minutes);
    return;
  end if;

  return query select false, previous_request + make_interval(mins => window_minutes);
end;
$$;

revoke all on function public.claim_pyq_course_retry(text, integer) from public, anon, authenticated;

comment on table public.pyq_course_retry_requests is 'Server-side cooldown state for the public one-paper-per-course retry action.';
comment on function public.claim_pyq_course_retry(text, integer) is 'Atomically accepts at most one public retry request per course during a bounded 15-30 minute cooldown.';

