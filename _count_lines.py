#!/usr/bin/env python3
import os

files = []
for root, dirs, fnames in os.walk('.'):
    dirs[:] = [d for d in dirs if d not in ('build', '.gradle', 'gradle', '.git')]
    for f in fnames:
        if f.endswith('.kt'):
            path = os.path.join(root, f).replace('\\', '/')
            rpath = path[2:] if path.startswith('./') else path
            with open(path, 'r', encoding='utf-8', errors='replace') as fp:
                lines = sum(1 for _ in fp)
            files.append((rpath, lines))

files.sort(key=lambda x: -x[1])
total = sum(x[1] for x in files)

print(f'Total .kt files: {len(files)}, Total lines: {total}')
print()
print(f'{"Lines":>6s}  File')
print('-' * 80)

for path, lines in files:
    flag = ' *** LARGE' if lines > 500 else ''
    print(f'{lines:>6d}  {path}{flag}')
