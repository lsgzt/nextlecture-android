-- Allow a reinstall to recover the same attendance owner from the opaque
-- saved-profile fingerprint without exposing student identity fields.
create index if not exists attendance_students_profile_fingerprint_idx
  on public.attendance_students(profile_fingerprint, last_seen_at desc);
