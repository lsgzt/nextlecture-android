-- Additive attendance classification. Existing rows cannot be reliably
-- separated, so they remain explicitly unspecified; old clients continue to
-- work because the API defaults missing lectureType to unspecified.
alter table public.attendance_records
  add column if not exists lecture_type text not null default 'unspecified';

update public.attendance_records
set lecture_type = 'unspecified'
where lecture_type is null;

alter table public.attendance_records
  drop constraint if exists attendance_records_lecture_type_check;
alter table public.attendance_records
  add constraint attendance_records_lecture_type_check
  check (lecture_type in ('lecture', 'practical', 'tutorial', 'unspecified'));

create index if not exists attendance_records_student_subject_type_idx
  on public.attendance_records(student_id, subject, lecture_type);
