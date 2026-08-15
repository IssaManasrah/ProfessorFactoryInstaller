#!/usr/bin/env python3
from pathlib import Path
import sys
root = Path(sys.argv[1])
files = [
    root/'smali/com/mbm_soft/aldahyaplay/ui/vod_exo/VodActivity.smali',
    root/'smali/com/mbm_soft/aldahyaplay/ui/vod_vlc/VodVlcActivity.smali',
]
old = 'invoke-static {p0, v1, v2, v3, v4, v5}, Lcom/mbm_soft/aldahyaplay/utils/ResumeStore;->savePosition(Landroid/content/Context;Ljava/lang/String;JJ)V'
new = 'move-object v0, p0\n    invoke-static/range {v0 .. v5}, Lcom/mbm_soft/aldahyaplay/utils/ResumeStore;->savePosition(Landroid/content/Context;Ljava/lang/String;JJ)V'
total = 0
for p in files:
    s = p.read_text(encoding='utf-8')
    n = s.count(old)
    if n != 1:
        raise SystemExit(f'{p}: expected exactly one 6-register savePosition invoke, got {n}')
    p.write_text(s.replace(old, new, 1), encoding='utf-8')
    total += n
print(f'RANGE_FIX_OK:{total}')
