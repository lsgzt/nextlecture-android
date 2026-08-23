import fs from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const catalogPath = path.join(path.dirname(fileURLToPath(import.meta.url)), '..', 'data', 'previous_year_papers.json');

export function normalizeCourseCode(value) {
  if (typeof value !== 'string') return null;
  const source = value.toUpperCase().replace(/[–—]/g, '-');
  const matches = source.match(/[A-Z]{2,12}[-_ ]?\d{2,4}/g) || [];
  const normalized = matches.map((match) => {
    const parts = match.match(/^([A-Z]{2,12})[-_ ]?(\d{2,4})$/);
    return parts ? `${parts[1]}-${parts[2]}` : null;
  }).filter(Boolean);
  return normalized[0] || null;
}

export function extractYear(...values) {
  for (const value of values) {
    if (typeof value !== 'string') continue;
    const match = value.match(/\b(20\d{2})\b/);
    if (match) return Number(match[1]);
  }
  return null;
}

export function normalizePaper(raw) {
  const id = typeof raw?.id === 'string' ? raw.id.trim() : '';
  const title = typeof raw?.title === 'string' ? raw.title.trim() : '';
  const fileName = typeof raw?.fileName === 'string' ? raw.fileName.trim() : '';
  const session = typeof raw?.session === 'string' ? raw.session.trim() : '';
  const driveUrl = typeof raw?.pdfUrl === 'string' ? raw.pdfUrl.trim() : '';
  if (!id || !title || !fileName || !session || !driveUrl) return null;
  const courseCode = normalizeCourseCode(`${fileName} ${title}`) || 'UNKNOWN';
  return {
    id,
    course_code: courseCode,
    course_name: null,
    year: extractYear(session, title, fileName),
    exam_session: session,
    title,
    drive_url: driveUrl,
    source_folder_id: '11ywkOKyeixCPihsCzqZDyzy2msLXxx6w',
    source_file_name: fileName,
  };
}

export async function readCatalog() {
  const raw = JSON.parse(await fs.readFile(catalogPath, 'utf8'));
  if (!Array.isArray(raw?.papers)) throw new Error('Catalog must contain a papers array');
  const papers = raw.papers.map(normalizePaper).filter(Boolean);
  return { sourceFolderUrl: raw.sourceFolderUrl || null, papers };
}

export function isApprovedDriveUrl(value) {
  try {
    const url = new URL(value);
    return url.protocol === 'https:' && (url.hostname === 'drive.google.com' || url.hostname === 'www.drive.google.com') && url.pathname.startsWith('/file/d/');
  } catch {
    return false;
  }
}

export function toDownloadUrl(value) {
  const match = value.match(/^https:\/\/drive\.google\.com\/file\/d\/([^/]+)\//i);
  if (!match) throw new Error('Stored URL is not an approved Google Drive file URL');
  return `https://drive.google.com/uc?export=download&id=${encodeURIComponent(match[1])}`;
}
