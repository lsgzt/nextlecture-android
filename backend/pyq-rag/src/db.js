import { createClient } from '@supabase/supabase-js';
import { config, hasSupabase } from './config.js';

let client;

export function getDb() {
  if (!hasSupabase()) throw new Error('Supabase server configuration is missing');
  if (!client) {
    client = createClient(config.supabaseUrl, config.supabaseServiceRoleKey, {
      auth: { autoRefreshToken: false, persistSession: false, detectSessionInUrl: false },
      global: { headers: { 'x-application-name': 'gndec-pyq-rag-api' } },
    });
  }
  return client;
}

export async function importPapers(papers) {
  const db = getDb();
  const { data, error } = await db.from('pyq_papers').upsert(papers, { onConflict: 'id', ignoreDuplicates: false }).select('id');
  if (error) throw new Error(`Supabase paper import failed: ${error.message}`);
  return { upserted: data?.length || 0 };
}

export async function getPaper(id) {
  const { data, error } = await getDb().from('pyq_papers').select('id,course_code,course_name,year,exam_session,title,drive_url,source_folder_id,source_file_name,processing_status,processing_error,processed_at,content_hash,page_count').eq('id', id).maybeSingle();
  if (error) throw new Error(`Supabase paper read failed: ${error.message}`);
  return data;
}

export async function claimSpecificPaper(id, force = false) {
  const db = getDb();
  let query = db.from('pyq_papers').update({ processing_status: 'processing', processing_error: null, updated_at: new Date().toISOString() }).eq('id', id);
  if (!force) query = query.in('processing_status', ['pending', 'failed']);
  const { data, error } = await query.select('id,course_code,course_name,year,exam_session,title,drive_url,source_folder_id,source_file_name,processing_status,processing_error,processed_at,content_hash,page_count').maybeSingle();
  if (error) throw new Error(`Supabase paper claim failed: ${error.message}`);
  return data;
}

export async function claimPaperBatch(limit, includeFailed = false) {
  const { data, error } = await getDb().rpc('claim_pyq_papers', { batch_size: limit, include_failed: includeFailed });
  if (error) throw new Error(`Supabase paper batch claim failed: ${error.message}`);
  return data || [];
}

export async function claimCoursePaper(courseCode) {
  const { data, error } = await getDb().rpc('claim_pyq_papers_for_course', { filter_course_code: courseCode, batch_size: 1, include_failed: true });
  if (error) throw new Error(`Supabase course paper claim failed: ${error.message}`);
  return data?.[0] || null;
}

export async function claimCourseRetry(courseCode) {
  const { data, error } = await getDb().rpc('claim_pyq_course_retry', { filter_course_code: courseCode, cooldown_minutes: 20 });
  if (error) throw new Error(`Supabase course retry cooldown failed: ${error.message}`);
  const row = Array.isArray(data) ? data[0] : data;
  return { accepted: Boolean(row?.accepted), cooldownUntil: row?.cooldown_until || null };
}

export async function resetStalePapers() {
  const { data, error } = await getDb().rpc('reset_stale_pyq_papers', { stale_minutes: 45 });
  if (error) throw new Error(`Supabase stale-paper recovery failed: ${error.message}`);
  return Number(data || 0);
}

export async function markPaperCompleted(id, fields) {
  const { error } = await getDb().from('pyq_papers').update({ ...fields, processing_status: 'completed', processing_error: null, processed_at: new Date().toISOString() }).eq('id', id);
  if (error) throw new Error(`Supabase completion update failed: ${error.message}`);
}

export async function markPaperFailed(id, message) {
  const safeMessage = String(message || 'Unknown ingestion error').slice(0, 1000);
  const { error } = await getDb().from('pyq_papers').update({ processing_status: 'failed', processing_error: safeMessage }).eq('id', id);
  if (error) throw new Error(`Supabase failure update failed: ${error.message}`);
}

export async function deleteQuestionsForPaper(id) {
  const { error } = await getDb().from('pyq_questions').delete().eq('paper_id', id);
  if (error) throw new Error(`Supabase question cleanup failed: ${error.message}`);
}

export async function insertQuestions(rows) {
  const { data, error } = await getDb().from('pyq_questions').insert(rows).select('id,paper_id,question_number,question_text,normalized_question,source_page');
  if (error) throw new Error(`Supabase question insert failed: ${error.message}`);
  return data || [];
}

export async function updateQuestionEmbedding(id, embedding) {
  const { error } = await getDb().from('pyq_questions').update({ embedding }).eq('id', id);
  if (error) throw new Error(`Supabase embedding update failed: ${error.message}`);
}

export async function findNearestQuestion(question, courseCode, threshold = config.groupUncertainThreshold) {
  const { data, error } = await getDb().rpc('match_pyq_questions', {
    query_embedding: question.embedding,
    match_threshold: threshold,
    match_count: 5,
    filter_course_code: courseCode,
  });
  if (error) throw new Error(`Supabase similarity lookup failed: ${error.message}`);
  return (data || []).filter((row) => Number(row.question_id) !== Number(question.id));
}

export async function createQuestionGroup({ courseCode, title, description, confidence }) {
  const { data, error } = await getDb().from('pyq_question_groups').insert({ course_code: courseCode, representative_title: title, representative_description: description, confidence, frequency: 0 }).select('id,course_code,representative_title,representative_description,confidence').single();
  if (error) throw new Error(`Supabase question group insert failed: ${error.message}`);
  return data;
}

export async function getQuestionGroups(questionId) {
  const { data, error } = await getDb().from('pyq_question_group_members').select('group_id,similarity_score').eq('question_id', questionId).order('similarity_score', { ascending: false }).limit(10);
  if (error) throw new Error(`Supabase question-group lookup failed: ${error.message}`);
  return data || [];
}

export async function addGroupMember(groupId, questionId, similarityScore) {
  const { error } = await getDb().from('pyq_question_group_members').upsert({ group_id: groupId, question_id: questionId, similarity_score: similarityScore }, { onConflict: 'group_id,question_id' });
  if (error) throw new Error(`Supabase group membership insert failed: ${error.message}`);
}

export async function refreshGroupFrequency(groupId) {
  const { error } = await getDb().rpc('refresh_pyq_group_frequency', { target_group_id: groupId });
  if (error) throw new Error(`Supabase group frequency refresh failed: ${error.message}`);
}

export async function getFrequency(courseCode, yearFrom, yearTo, limit) {
  const { data, error } = await getDb().rpc('get_pyq_frequency', { filter_course_code: courseCode, filter_year_from: yearFrom ?? null, filter_year_to: yearTo ?? null, result_limit: limit });
  if (error) throw new Error(`Supabase frequency lookup failed: ${error.message}`);
  return data || [];
}

export async function getCourseCoverage(courseCode) {
  const db = getDb();
  const statuses = ['pending', 'processing', 'completed', 'failed', 'skipped'];
  const counts = {};
  await Promise.all(statuses.map(async (status) => {
    const { count, error } = await db.from('pyq_papers').select('id', { count: 'exact', head: true }).eq('course_code', courseCode).eq('processing_status', status);
    if (error) throw new Error(`Supabase course coverage read failed: ${error.message}`);
    counts[status] = count || 0;
  }));
  const { data: latest, error: latestError } = await db.from('pyq_papers').select('updated_at').eq('course_code', courseCode).order('updated_at', { ascending: false }).limit(1).maybeSingle();
  if (latestError && latestError.code !== 'PGRST116') throw new Error(`Supabase course coverage timestamp failed: ${latestError.message}`);
  const { data: failures, error: failureError } = await db.from('pyq_papers').select('processing_error').eq('course_code', courseCode).eq('processing_status', 'failed').order('updated_at', { ascending: false }).limit(10);
  if (failureError) throw new Error(`Supabase course failure reason read failed: ${failureError.message}`);
  const failureText = (failures || []).map((row) => String(row.processing_error || '').toLowerCase()).join(' ');
  const failureReason = failureText.includes('quota') || failureText.includes('rate limit') || failureText.includes('resource exhausted') ? 'provider_quota' : (failures || []).length ? 'processing_error' : null;
  const total = statuses.reduce((sum, status) => sum + (counts[status] || 0), 0);
  return { total, pending: counts.pending || 0, processing: counts.processing || 0, completed: counts.completed || 0, failed: counts.failed || 0, skipped: counts.skipped || 0, failureReason, updatedAt: latest?.updated_at || null };
}

export async function getGroup(groupId) {
  const { data, error } = await getDb().from('pyq_question_groups').select('id,course_code,representative_title,representative_description,frequency,confidence,created_at,updated_at').eq('id', groupId).maybeSingle();
  if (error) throw new Error(`Supabase group read failed: ${error.message}`);
  return data;
}

export async function getGroupQuestions(groupId) {
  const { data, error } = await getDb().from('pyq_question_group_members').select('similarity_score,question:pyq_questions(id,paper_id,question_number,question_text,source_page,extraction_method,extraction_confidence,paper:pyq_papers(id,title,course_code,year,exam_session,drive_url))').eq('group_id', groupId).order('similarity_score', { ascending: false }).limit(200);
  if (error) throw new Error(`Supabase group detail read failed: ${error.message}`);
  return data || [];
}

export async function getCache(courseCode, yearFrom, yearTo) {
  let query = getDb().from('pyq_course_analysis_cache').select('course_code,year_from,year_to,analysis,processed_paper_count,generated_at,invalidated_at').eq('course_code', courseCode);
  query = yearFrom == null ? query.is('year_from', null) : query.eq('year_from', yearFrom);
  query = yearTo == null ? query.is('year_to', null) : query.eq('year_to', yearTo);
  const { data, error } = await query.maybeSingle();
  if (error && error.code !== 'PGRST116') throw new Error(`Supabase cache read failed: ${error.message}`);
  return data;
}

export async function writeCache(courseCode, yearFrom, yearTo, analysis, processedPaperCount) {
  const { error } = await getDb().from('pyq_course_analysis_cache').upsert({ course_code: courseCode, year_from: yearFrom ?? null, year_to: yearTo ?? null, analysis, processed_paper_count: processedPaperCount, generated_at: new Date().toISOString(), invalidated_at: null }, { onConflict: 'course_code' });
  if (error) throw new Error(`Supabase cache write failed: ${error.message}`);
}

export async function getProcessedPaperCount(courseCode) {
  const { count, error } = await getDb().from('pyq_papers').select('id', { count: 'exact', head: true }).eq('course_code', courseCode).eq('processing_status', 'completed');
  if (error) throw new Error(`Supabase processed-paper count failed: ${error.message}`);
  return count || 0;
}

export async function invalidateCourseCache(courseCode) {
  const { error } = await getDb().from('pyq_course_analysis_cache').update({ invalidated_at: new Date().toISOString() }).eq('course_code', courseCode);
  if (error && error.code !== 'PGRST116') throw new Error(`Supabase cache invalidation failed: ${error.message}`);
}

export async function retryFailedPapers(limit) {
  const db = getDb();
  const { data: failed, error: readError } = await db.from('pyq_papers').select('id').eq('processing_status', 'failed').order('updated_at', { ascending: true }).limit(limit);
  if (readError) throw new Error(`Supabase failed-paper retry lookup failed: ${readError.message}`);
  const ids = (failed || []).map((row) => row.id);
  if (!ids.length) return 0;
  const { error } = await db.from('pyq_papers').update({ processing_status: 'pending', processing_error: null }).in('id', ids);
  if (error) throw new Error(`Supabase failed-paper retry failed: ${error.message}`);
  return ids.length;
}

export async function getStatusCounts() {
  const statuses = ['pending', 'processing', 'completed', 'failed', 'skipped'];
  const counts = {};
  for (const status of statuses) {
    const { count, error } = await getDb().from('pyq_papers').select('id', { count: 'exact', head: true }).eq('processing_status', status);
    if (error) throw new Error(`Supabase status read failed: ${error.message}`);
    counts[status] = count || 0;
  }
  const { count: questionCount, error: questionError } = await getDb().from('pyq_questions').select('id', { count: 'exact', head: true });
  if (questionError) throw new Error(`Supabase question count failed: ${questionError.message}`);
  return { papers: counts, questions: questionCount || 0 };
}
