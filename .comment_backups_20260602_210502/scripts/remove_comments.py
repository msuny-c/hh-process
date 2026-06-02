#!/usr/bin/env python3
import re
import sys
import shutil
from pathlib import Path
from datetime import datetime

ROOT = Path(__file__).resolve().parents[1]
BACKUP_DIR = ROOT / (".comment_backups_" + datetime.now().strftime('%Y%m%d_%H%M%S'))
SKIP_DIRS = {'.git', 'target', 'node_modules', BACKUP_DIR.name}

c_like_exts = {'.java', '.js', '.ts', '.css', '.scss', '.less', '.cpp', '.c', '.h', '.gradle', '.groovy', '.kt'}
xml_exts = {'.xml', '.bpmn', '.html', '.xhtml', '.xsd', '.wsdl', '.form'}
hash_exts = {'.yml', '.yaml', '.sh', '.properties', '.env', '.ini', '.conf'}
sql_exts = {'.sql'}
md_exts = {'.md'}
other_files = {'Dockerfile', 'Makefile'}

print(f'Root: {ROOT}')
print('Creating backup at', BACKUP_DIR)
BACKUP_DIR.mkdir(parents=True, exist_ok=True)

modified = []

for path in ROOT.rglob('*'):
    if path.is_dir():
        if path.name in SKIP_DIRS:
            # skip walking into these dirs
            for _ in path.rglob('*'):
                break
        continue
    rel = path.relative_to(ROOT)
    # skip files in backup dir
    if BACKUP_DIR in path.parents:
        continue
    # skip binary files heuristics
    try:
        text = path.read_text(encoding='utf-8')
    except Exception:
        continue
    # decide handling
    ext = path.suffix.lower()
    name = path.name
    original = text
    new = text
    if name in other_files or ext in hash_exts:
        lines = []
        for i, line in enumerate(original.splitlines()):
            if i == 0 and line.startswith('#!'):
                lines.append(line)
                continue
            stripped = line.lstrip()
            if stripped.startswith('#') or stripped.startswith('!'):
                # drop line
                continue
            # remove inline # comments
            if '#' in line:
                idx = line.find('#')
                line = line[:idx].rstrip()
            lines.append(line)
        new = '\n'.join(lines) + ("\n" if original.endswith('\n') else '')
    elif ext in c_like_exts:
        # remove /* */ and //
        new = re.sub(r'/\*.*?\*/', '', original, flags=re.S)
        new = re.sub(r'//.*$', '', new, flags=re.M)
    elif ext in xml_exts or ext in md_exts:
        new = re.sub(r'<!--.*?-->', '', original, flags=re.S)
    elif ext in sql_exts:
        new = re.sub(r'/\*.*?\*/', '', original, flags=re.S)
        new = re.sub(r'--.*$', '', new, flags=re.M)
    else:
        # fallback: remove lines that start with # or // or <!--
        lines = []
        for i,line in enumerate(original.splitlines()):
            if i == 0 and line.startswith('#!'):
                lines.append(line); continue
            s=line.lstrip()
            if s.startswith('#') or s.startswith('//') or s.startswith('<!--') or s.startswith('--'):
                continue
            # try strip inline // or #
            if '//' in line:
                line=line.split('//')[0].rstrip()
            if '#' in line:
                line=line.split('#')[0].rstrip()
            if '<!--' in line and '-->' in line:
                line=re.sub(r'<!--.*?-->','',line)
            lines.append(line)
        new='\n'.join(lines) + ("\n" if original.endswith('\n') else '')
    if new != original:
        # backup original
        dest = BACKUP_DIR / rel
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(path, dest)
        path.write_text(new, encoding='utf-8')
        modified.append(str(rel))

print('\nModified files:')
for m in modified:
    print(m)
print('\nBackup of originals stored in', BACKUP_DIR)
print('Done')
