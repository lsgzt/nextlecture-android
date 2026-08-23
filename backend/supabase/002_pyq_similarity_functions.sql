create index if not exists pyq_questions_embedding_hnsw_idx
on public.pyq_questions using hnsw (embedding vector_cosine_ops);

create or replace function public.match_pyq_questions(
  query_embedding vector(768),
  match_threshold double precision,
  match_count integer,
  filter_course_code text
)
returns table (
  question_id bigint,
  paper_id text,
  question_number text,
  question_text text,
  source_page integer,
  exam_year integer,
  exam_session text,
  paper_title text,
  drive_url text,
  similarity double precision
)
language sql
stable
security invoker
as $$
  select
    q.id as question_id,
    q.paper_id,
    q.question_number,
    q.question_text,
    q.source_page,
    p.year as exam_year,
    p.exam_session,
    p.title as paper_title,
    p.drive_url,
    1 - (q.embedding <=> query_embedding) as similarity
  from public.pyq_questions q
  join public.pyq_papers p on p.id = q.paper_id
  where q.embedding is not null
    and p.course_code = filter_course_code
    and 1 - (q.embedding <=> query_embedding) >= greatest(least(match_threshold, 1), -1)
  order by q.embedding <=> query_embedding asc
  limit least(greatest(match_count, 1), 50);
$$;

create or replace function public.get_pyq_frequency(
  filter_course_code text,
  filter_year_from integer default null,
  filter_year_to integer default null,
  result_limit integer default 50
)
returns table (
  group_id bigint,
  representative_title text,
  representative_description text,
  frequency bigint,
  confidence numeric
)
language sql
stable
security invoker
as $$
  select
    g.id as group_id,
    g.representative_title,
    g.representative_description,
    count(distinct q.paper_id) as frequency,
    g.confidence
  from public.pyq_question_groups g
  join public.pyq_question_group_members gm on gm.group_id = g.id
  join public.pyq_questions q on q.id = gm.question_id
  join public.pyq_papers p on p.id = q.paper_id
  where g.course_code = filter_course_code
    and (filter_year_from is null or p.year >= filter_year_from)
    and (filter_year_to is null or p.year <= filter_year_to)
  group by g.id, g.representative_title, g.representative_description, g.confidence
  order by count(distinct q.paper_id) desc, g.confidence desc nulls last, g.id asc
  limit least(greatest(result_limit, 1), 200);
$$;

create or replace function public.refresh_pyq_group_frequency(target_group_id bigint)
returns integer
language sql
volatile
security definer
set search_path = public
as $$
  update public.pyq_question_groups g
  set frequency = (
    select count(distinct q.paper_id)::integer
    from public.pyq_question_group_members gm
    join public.pyq_questions q on q.id = gm.question_id
    where gm.group_id = target_group_id
  ), updated_at = now()
  where g.id = target_group_id
  returning frequency;
$$;

revoke all on function public.refresh_pyq_group_frequency(bigint) from public, anon, authenticated;
