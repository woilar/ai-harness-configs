#!/usr/bin/env python3
"""Build a standalone release bundle after scripts/build.py."""
from pathlib import Path
import hashlib,zipfile,xml.etree.ElementTree as ET
root=Path(__file__).resolve().parents[1];dist=root/'dist';dist.mkdir(exist_ok=True)
version=ET.parse(root/'intellij/src/main/resources/META-INF/plugin.xml').findtext('version')
source=dist/'sources.zip'
with zipfile.ZipFile(source,'w',zipfile.ZIP_DEFLATED) as z:
    for base in ['intellij','opencode','scripts','docs']:
        for p in sorted((root/base).rglob('*')):
            if p.is_file() and '__pycache__' not in p.parts:z.write(p,p.relative_to(root))
    for name in ['README.md','LICENSE']:z.write(root/name,name)
entries={f'opencode-review-{version}.zip':dist/f'opencode-review-{version}.zip','sources.zip':source,'install.py':root/'scripts/install.py','README.md':root/'README.md','LICENSE':root/'LICENSE','VALIDATION.txt':root/'docs/VALIDATION.txt'}
for name in ['install.command','install.cmd','install.sh']:entries[name]=root/name
for p in sorted((root/'opencode').glob('*')):
    if p.is_file():entries['opencode/'+p.name]=p
sums=''.join(hashlib.sha256(p.read_bytes()).hexdigest()+'  '+name+'\n' for name,p in entries.items())
checksum=dist/'SHA256SUMS.txt';checksum.write_text(sums);entries[checksum.name]=checksum
archive=dist/f'opencode-review-bundle-{version}.zip'
with zipfile.ZipFile(archive,'w',zipfile.ZIP_DEFLATED) as z:
    for name,p in entries.items():z.write(p,'opencode-review/'+name)
with zipfile.ZipFile(archive) as z:
    assert z.testzip() is None
    for line in sums.splitlines():
        expected,name=line.split('  ',1);assert hashlib.sha256(z.read('opencode-review/'+name)).hexdigest()==expected
print(str(archive),archive.stat().st_size,'bytes — CRC and hashes verified')
