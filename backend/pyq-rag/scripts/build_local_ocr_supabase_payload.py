import json
from pathlib import Path

DATA = json.loads(Path("/tmp/local_ocr_dataset.json").read_text())
COURSES = ["ESC-101", "ESC-103", "BSC-103", "BSC-102", "HSMC-101"]
MARKER = "Grouped locally from page-preserved OCR using conservative lexical overlap; no Gemini or embeddings were used."

def dollar_json(value, tag):
    text = json.dumps(value, ensure_ascii=False, separators=(",", ":"))
    assert f"${tag}$" not in text
    return f"${tag}${text}${tag}$::jsonb"

papers = [{key: row.get(key) for key in ("id", "course_code", "page_count", "content_hash", "source_file_name", "question_count")} for row in DATA["papers"]]
questions = DATA["questions"]
groups = []
members = []
for group_key, group in enumerate(DATA["groups"], 1):
    groups.append({
        "group_key": group_key,
        "course_code": group["course_code"],
        "representative_title": group["representative_title"],
        "representative_description": MARKER,
        "confidence": group["confidence"],
    })
    for member in group["members"]:
        members.append({
            "group_key": group_key,
            "question_ref": member["question_ref"],
            "similarity_score": member["similarity_score"],
        })

def sql_text(value):
    return "'" + str(value).replace("'", "''") + "'"

course_sql = ",".join(sql_text(course) for course in COURSES)
query = f"""
begin;
create temp table local_ocr_papers (
  id text primary key,
  course_code text not null,
  page_count integer,
  content_hash text,
  source_file_name text,
  question_count integer not null
) on commit drop;
insert into local_ocr_papers
select * from jsonb_to_recordset({dollar_json(papers, 'local_papers')})
  as x(id text, course_code text, page_count integer, content_hash text, source_file_name text, question_count integer);

create temp table local_ocr_questions (
  question_ref text primary key,
  paper_id text not null,
  course_code text not null,
  question_number text not null,
  question_text text not null,
  normalized_question text not null,
  section text,
  marks numeric,
  unit text,
  source_page integer not null,
  extraction_method text not null,
  extraction_confidence numeric
) on commit drop;
insert into local_ocr_questions
select * from jsonb_to_recordset({dollar_json(questions, 'local_questions')})
  as x(question_ref text, paper_id text, course_code text, question_number text, question_text text, normalized_question text, section text, marks numeric, unit text, source_page integer, extraction_method text, extraction_confidence numeric);

create temp table local_ocr_groups (
  group_key integer primary key,
  course_code text not null,
  representative_title text not null,
  representative_description text not null,
  confidence numeric not null
) on commit drop;
insert into local_ocr_groups
select * from jsonb_to_recordset({dollar_json(groups, 'local_groups')})
  as x(group_key integer, course_code text, representative_title text, representative_description text, confidence numeric);

create temp table local_ocr_members (
  group_key integer not null,
  question_ref text not null,
  similarity_score numeric not null
) on commit drop;
insert into local_ocr_members
select * from jsonb_to_recordset({dollar_json(members, 'local_members')})
  as x(group_key integer, question_ref text, similarity_score numeric);

-- Only papers in this validated local dataset are replaced. Already completed HSMC-101 rows are excluded from the dataset and remain untouched.
delete from public.pyq_question_group_members gm
using public.pyq_question_groups g
where g.id = gm.group_id
  and g.representative_description = {sql_text(MARKER)}
  and g.course_code in ({course_sql});
delete from public.pyq_question_groups
where representative_description = {sql_text(MARKER)}
  and course_code in ({course_sql});
delete from public.pyq_questions q
using local_ocr_papers p
where q.paper_id = p.id;

insert into public.pyq_questions (
  paper_id, question_number, question_text, normalized_question, section, marks, unit,
  source_page, extraction_method, extraction_confidence
)
select paper_id, question_number, question_text, normalized_question, section, marks, unit,
       source_page, extraction_method, extraction_confidence
from local_ocr_questions;

create temp table local_ocr_question_map as
select l.question_ref, q.id as question_id
from local_ocr_questions l
join public.pyq_questions q
  on q.paper_id = l.paper_id
 and q.question_number = l.question_number
 and q.source_page = l.source_page
 and q.normalized_question = l.normalized_question;

update public.pyq_papers p
set processing_status = 'completed',
    processing_error = null,
    processed_at = now(),
    content_hash = l.content_hash,
    page_count = l.page_count
from local_ocr_papers l
where p.id = l.id;

insert into public.pyq_question_groups (
  course_code, representative_title, representative_description, frequency, confidence
)
select course_code, representative_title, representative_description, 0, confidence
from local_ocr_groups;

create temp table local_ocr_group_map as
select l.group_key, g.id as group_id
from local_ocr_groups l
join public.pyq_question_groups g
  on g.course_code = l.course_code
 and g.representative_title = l.representative_title
 and g.representative_description = l.representative_description;

insert into public.pyq_question_group_members (group_id, question_id, similarity_score)
select gm.group_id, qm.question_id, m.similarity_score
from local_ocr_members m
join local_ocr_group_map gm on gm.group_key = m.group_key
join local_ocr_question_map qm on qm.question_ref = m.question_ref;

update public.pyq_question_groups g
set frequency = counts.paper_count,
    updated_at = now()
from (
  select gm.group_id, count(distinct q.paper_id)::integer as paper_count
  from public.pyq_question_group_members gm
  join public.pyq_questions q on q.id = gm.question_id
  where gm.group_id in (select group_id from local_ocr_group_map)
  group by gm.group_id
) counts
where g.id = counts.group_id;

update public.pyq_course_analysis_cache
set invalidated_at = now()
where course_code in ({course_sql});

select p.course_code, p.processing_status, count(*)::integer as paper_count
from public.pyq_papers p
where p.course_code in ({course_sql})
group by p.course_code, p.processing_status
order by p.course_code, p.processing_status
limit 100;
commit;
"""

payload = {"project_id": "dwxsrudypzismkrfsizy", "query": query}
Path("/home/ubuntu/.mcp/local_ocr_insert_input.json").write_text(json.dumps(payload, ensure_ascii=False))
print(json.dumps({"papers": len(papers), "questions": len(questions), "groups": len(groups), "members": len(members), "queryBytes": len(query.encode('utf-8')), "inputPath": "/home/ubuntu/.mcp/local_ocr_insert_input.json"}, indent=2))
