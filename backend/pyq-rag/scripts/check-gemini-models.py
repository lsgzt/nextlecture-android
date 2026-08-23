import json
import os
import urllib.request

key = os.environ['GEMINI_API_KEY']
request = urllib.request.Request(
    'https://generativelanguage.googleapis.com/v1beta/models',
    headers={'x-goog-api-key': key},
)
with urllib.request.urlopen(request, timeout=30) as response:
    payload = json.load(response)
for model in payload.get('models', []):
    name = model.get('name', '').removeprefix('models/')
    methods = ','.join(model.get('supportedGenerationMethods', []))
    if 'gemini' in name.lower() or 'embedding' in name.lower():
        print(f'{name}\t{methods}')
