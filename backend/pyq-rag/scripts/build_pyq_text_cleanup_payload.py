import json
from pathlib import Path

courses = ["ESC-101", "ESC-103", "BSC-103", "BSC-102", "HSMC-101"]
course_literals = ",".join("'" + course + "'" for course in courses)
private_map = [
    (61480, "("), (61481, ")"), (61483, "+"), (61485, "−"), (61501, "="),
    (61548, "λ"), (61549, "μ"), (61552, "π"), (61602, "·"), (61605, "∞"),
    (61614, "→"), (61670, "("), (61671, "("), (61672, ")"), (61673, "("),
    (61674, "["), (61675, "["), (61682, "∫"), (61686, "["), (61687, "−"),
    (61688, "]"), (61689, ")"), (61690, "]"), (61691, "]"),
]
from_expr = " || ".join(f"chr({code})" for code, _ in private_map)
to_expr = " || ".join("'" + value.replace("'", "''") + "'" for _, value in private_map)
query = f"""
begin;
with source as (
  select q.id,
    translate(q.question_text, {from_expr}, {to_expr}) as mapped_text
  from public.pyq_questions q
  join public.pyq_papers p on p.id = q.paper_id
  where q.extraction_method = 'ocr'
    and p.course_code in ({course_literals})
), cleaned as (
  select id,
    trim(
      replace(replace(replace(replace(replace(
        regexp_replace(
          regexp_replace(
            regexp_replace(
              replace(mapped_text, chr(92) || '1', ' '),
              '[' || chr(57344) || '-' || chr(63743) || ']', '?', 'g'
            ),
            '\\s+(?:page\\s+[0-9ilIL]+(?:\\s*of\\s*[0-9ilIL]+)?|morning|evening|section\\s*[-—~ ]*[abc])\\b.*$', '', 'gi'
          ),
          '\\s+(?:R|K|H|A){{5,}}\\s*$', ''
        ),
        ' ,', ','), ' .', '.'), ' ;', ';'), ' :', ':'), ' ?', '?')
    ) as question_text
  from source
), normalized as (
  select id,
    question_text,
    trim(regexp_replace(regexp_replace(lower(question_text), '[^a-z0-9\\s]', ' ', 'g'), '\\s+', ' ', 'g')) as normalized_question
  from cleaned
)
update public.pyq_questions q
set question_text = n.question_text,
    normalized_question = n.normalized_question,
    updated_at = now()
from normalized n
where q.id = n.id
  and q.question_text is distinct from n.question_text;

update public.pyq_question_groups g
set updated_at = now()
where g.course_code in ({course_literals})
  and g.representative_description = 'Grouped locally from page-preserved OCR using conservative lexical overlap; no Gemini or embeddings were used.';

update public.pyq_course_analysis_cache
set invalidated_at = now()
where course_code in ({course_literals});

select p.course_code, count(*)::int as question_count
from public.pyq_questions q
join public.pyq_papers p on p.id = q.paper_id
where q.extraction_method = 'ocr'
  and p.course_code in ({course_literals})
group by p.course_code
order by p.course_code
limit 20;
commit;
"""
Path("/home/ubuntu/.mcp/pyq_text_cleanup_input.json").write_text(json.dumps({"project_id": "dwxsrudypzismkrfsizy", "query": query}, ensure_ascii=False))
print(json.dumps({"queryBytes": len(query.encode('utf-8')), "inputPath": "/home/ubuntu/.mcp/pyq_text_cleanup_input.json"}, indent=2))
