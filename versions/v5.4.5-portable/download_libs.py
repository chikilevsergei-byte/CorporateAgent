"""Fetch the version's locked Maven dependencies and verify SHA-256."""
from pathlib import Path
import hashlib, json, urllib.request

root=Path(__file__).resolve().parent
(root/'lib').mkdir(exist_ok=True)
for dependency in json.loads((root/'dependency-lock.json').read_text(encoding='utf-8-sig')):
    target=root/'lib'/dependency['file']
    if not target.exists() or hashlib.sha256(target.read_bytes()).hexdigest()!=dependency['sha256']:
        request=urllib.request.Request(dependency['url'],headers={'User-Agent':'CorporateAgent-build'})
        with urllib.request.urlopen(request,timeout=90) as response:
            data=response.read()
        if hashlib.sha256(data).hexdigest()!=dependency['sha256']:
            raise RuntimeError('SHA-256 mismatch: '+dependency['file'])
        target.write_bytes(data)
    print('SHA-256 OK:',dependency['file'])
