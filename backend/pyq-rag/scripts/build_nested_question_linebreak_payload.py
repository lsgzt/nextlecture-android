import json
from pathlib import Path

pattern = r"[[:space:]]+([(]?[ivxIVX]+[)]|[a-z][)])"
marker_literal = "chr(92) || '1'"


def repair_expression(source_column: str) -> str:
    labels = """case ord
          when 2 then 'i) '
          when 3 then 'ii) '
          when 4 then 'iii) '
          when 5 then 'iv) '
          when 6 then 'v) '
          when 7 then 'vi) '
          when 8 then 'vii) '
          else '[subquestion] '
        end"""
    return f"""(
      select string_agg(
        case
          when ord = 1 then rtrim(piece)
          else chr(10) || {labels} || ltrim(piece)
        end,
        '' order by ord
      )
      from unnest(string_to_array({source_column}, {marker_literal})) with ordinality as s(piece, ord)
    )"""


question_repair = repair_expression("q.question_text")
group_repair = repair_expression("g.representative_title")

query = f"""
begin;
with question_candidates as (
  select q.id,
    regexp_replace(
      case
        when position({marker_literal} in q.question_text) > 0 then {question_repair}
        else regexp_replace(q.question_text, '{pattern}', chr(10) || chr(92) || '1', 'gi')
      end,
      E'\\n[[:space:]]*\\n+', chr(10), 'g'
    ) as formatted_text
  from public.pyq_questions q
  where position({marker_literal} in q.question_text) > 0
     or q.question_text ~* '{pattern}'
  limit 2000
), updated_questions as (
  update public.pyq_questions q
  set question_text = c.formatted_text,
      normalized_question = trim(regexp_replace(regexp_replace(lower(c.formatted_text), '[^a-z0-9\\s]', ' ', 'g'), '\\s+', ' ', 'g')),
      updated_at = now()
  from question_candidates c
  where q.id = c.id
    and q.question_text is distinct from c.formatted_text
  returning q.id
)
select count(*)::int as changed_question_rows from updated_questions limit 1;

with group_candidates as (
  select g.id,
    regexp_replace(
      case
        when position({marker_literal} in g.representative_title) > 0 then {group_repair}
        else regexp_replace(g.representative_title, '{pattern}', chr(10) || chr(92) || '1', 'gi')
      end,
      E'\\n[[:space:]]*\\n+', chr(10), 'g'
    ) as formatted_title
  from public.pyq_question_groups g
  where position({marker_literal} in g.representative_title) > 0
     or g.representative_title ~* '{pattern}'
  limit 2000
), updated_groups as (
  update public.pyq_question_groups g
  set representative_title = c.formatted_title,
      updated_at = now()
  from group_candidates c
  where g.id = c.id
    and g.representative_title is distinct from c.formatted_title
  returning g.id
)
select count(*)::int as changed_group_rows from updated_groups limit 1;

update public.pyq_course_analysis_cache c
set invalidated_at = now()
where c.course_code in (
  select distinct p.course_code
  from public.pyq_papers p
  join public.pyq_questions q on q.paper_id = p.id
  where q.question_text ~* '{pattern}'
  limit 100
);
commit;
"""

Path("/home/ubuntu/.mcp/pyq_nested_question_linebreak_input.json").write_text(
    json.dumps({"project_id": "dwxsrudypzismkrfsizy", "query": query}, ensure_ascii=False)
)
print(json.dumps({"queryBytes": len(query.encode("utf-8")), "inputPath": "/home/ubuntu/.mcp/pyq_nested_question_linebreak_input.json"}, indent=2))
