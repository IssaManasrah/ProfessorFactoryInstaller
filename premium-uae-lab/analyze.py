#!/usr/bin/env python3
import argparse, json, os, re, shutil
from pathlib import Path

KEYWORDS = [
    'MoviesActivity','Movie','SeriesActivity','Series','Search','search',
    'PlayerActivity','Player','ExoPlayer','SimpleExoPlayer','MediaPlayer',
    'seekTo','getCurrentPosition','currentPosition','playbackPosition',
    'SharedPreferences','SQLite','streamDisplayName','title','categoryId','catid',
    'episode','Episode','season','Season','resume','Resume','history','History'
]
PATTERN = re.compile('|'.join(re.escape(x) for x in KEYWORDS), re.I)


def iter_text(root: Path):
    if not root.exists(): return
    for p in root.rglob('*'):
        if p.is_file() and p.suffix.lower() in {'.smali','.java','.xml','.json','.txt'}:
            try:
                txt = p.read_text(errors='ignore')
            except Exception:
                continue
            yield p, txt


def score(txt: str):
    found = sorted({m.group(0).lower() for m in PATTERN.finditer(txt)})
    return len(found), found


def collect(label, root, outroot):
    rows=[]
    for p,txt in iter_text(Path(root)):
        s, found = score(txt)
        if s >= 2 or re.search(r'(MoviesActivity|SeriesActivity|PlayerActivity|ExoPlayer|seekTo|getCurrentPosition)', txt, re.I):
            rel = p.relative_to(root)
            rows.append({'file':str(rel),'score':s,'keywords':found[:40]})
            # only copy manageable focused files
            if s >= 3:
                dst = Path(outroot)/'sources'/label/rel
                dst.parent.mkdir(parents=True,exist_ok=True)
                shutil.copy2(p,dst)
    rows.sort(key=lambda r:(-r['score'],r['file']))
    return rows[:500]


def grep_lines(root, expressions):
    hits=[]
    for p,txt in iter_text(Path(root)):
        for i,line in enumerate(txt.splitlines(),1):
            if any(re.search(e,line,re.I) for e in expressions):
                hits.append({'file':str(p.relative_to(root)),'line':i,'text':line.strip()[:500]})
                if len(hits)>=1500: return hits
    return hits


def manifest_info(root):
    p=Path(root)/'AndroidManifest.xml'
    if not p.exists(): return {}
    t=p.read_text(errors='ignore')
    pkg=re.search(r'package="([^"]+)"',t)
    return {'package':pkg.group(1) if pkg else None}


def main():
    a=argparse.ArgumentParser()
    a.add_argument('--uae-smali',required=True);a.add_argument('--premium-smali',required=True)
    a.add_argument('--uae-java',required=True);a.add_argument('--premium-java',required=True)
    a.add_argument('--out',required=True)
    args=a.parse_args(); out=Path(args.out); out.mkdir(parents=True,exist_ok=True)

    data={'manifest':{
        'uae':manifest_info(args.uae_smali),'premium':manifest_info(args.premium_smali)},
        'focused':{}}
    for label,root in [('uae-smali',args.uae_smali),('premium-smali',args.premium_smali),('uae-java',args.uae_java),('premium-java',args.premium_java)]:
        data['focused'][label]=collect(label,root,out)

    expr=[r'seekTo',r'getCurrentPosition',r'SharedPreferences',r'MoviesActivity',r'SeriesActivity',r'ExoPlayer',r'query\(',r'LIKE',r'streamDisplayName',r'categoryId',r'catid']
    data['critical_lines']={
        'uae':grep_lines(args.uae_smali,expr),
        'premium':grep_lines(args.premium_smali,expr)
    }
    (out/'analysis.json').write_text(json.dumps(data,indent=2,ensure_ascii=False))

    md=['# Premium UAE focused analysis','',f"UAE package: `{data['manifest']['uae'].get('package')}`",f"Premium package: `{data['manifest']['premium'].get('package')}`",'']
    for label in ['uae-smali','premium-smali','uae-java','premium-java']:
        md += [f'## {label}','']
        for r in data['focused'][label][:80]:
            md.append(f"- **{r['score']}** `{r['file']}` — {', '.join(r['keywords'][:12])}")
        md.append('')
    md += ['## Critical UAE lines','']
    for h in data['critical_lines']['uae'][:300]:
        md.append(f"- `{h['file']}:{h['line']}` `{h['text']}`")
    (out/'REPORT.md').write_text('\n'.join(md),encoding='utf-8')

if __name__=='__main__': main()
