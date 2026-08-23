import json
from pathlib import Path

sql = Path(__file__).with_name('004_course_retry_claim.sql').read_text()
Path('/tmp/pyq_004_migration.json').write_text(json.dumps({
    'project_id': 'dwxsrudypzismkrfsizy',
    'name': 'course_retry_claim',
    'query': sql,
}))
print('/tmp/pyq_004_migration.json')
