import json
from pathlib import Path

schema = Path('/home/ubuntu/apk-work/backend/supabase/003_pyq_ingestion_helpers.sql').read_text(encoding='utf-8')
payload = {
    'project_id': 'dwxsrudypzismkrfsizy',
    'name': 'pyq_ingestion_helpers_v1',
    'query': schema,
}
Path('/home/ubuntu/apk-work/backend/supabase/migration_003_input.json').write_text(
    json.dumps(payload, ensure_ascii=False), encoding='utf-8'
)
print('migration payload ready')
