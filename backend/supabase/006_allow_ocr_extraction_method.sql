alter table public.pyq_questions
  drop constraint if exists pyq_questions_extraction_method_check;

alter table public.pyq_questions
  add constraint pyq_questions_extraction_method_check
  check (extraction_method in ('text', 'vision', 'ocr'));
