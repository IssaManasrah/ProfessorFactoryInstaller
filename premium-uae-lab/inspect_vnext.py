#!/usr/bin/env python3
from pathlib import Path
import sys, re

smali = Path(sys.argv[1])
java = Path(sys.argv[2])
out = Path(sys.argv[3])
out.parent.mkdir(parents=True, exist_ok=True)

sections=[]
def add(title, path):
    p=Path(path)
    if p.exists() and p.is_file():
        txt=p.read_text(encoding='utf-8', errors='ignore')
        sections.append(f'\n===== {title}: {p} =====\n{txt}\n')

def add_matches(root, title, predicates, limit=30):
    found=[]
    for p in root.rglob('*'):
        if not p.is_file() or p.suffix not in ('.java','.smali','.xml'):
            continue
        try: txt=p.read_text(encoding='utf-8', errors='ignore')
        except: continue
        score=sum(1 for q in predicates if q.lower() in txt.lower() or q.lower() in p.name.lower())
        if score:
            found.append((score, len(txt), p, txt))
    found.sort(key=lambda x:(-x[0], x[1]))
    for score,_,p,txt in found[:limit]:
        sections.append(f'\n===== {title} score={score}: {p} =====\n{txt}\n')

# Exact high-value Java sources
exact_java=[
 'sources/com/mbm_soft/aldahyaplay/ui/movies/MoviesActivity.java',
 'sources/com/mbm_soft/aldahyaplay/ui/movies/d.java',
 'sources/com/mbm_soft/aldahyaplay/ui/series/SeriesActivity.java',
 'sources/com/mbm_soft/aldahyaplay/ui/series/d.java',
 'sources/com/mbm_soft/aldahyaplay/ui/series_info/SeriesInfoActivity.java',
 'sources/com/mbm_soft/aldahyaplay/ui/vod_exo/VodActivity.java',
 'sources/com/mbm_soft/aldahyaplay/ui/vod_vlc/VodVlcActivity.java',
 'sources/com/mbm_soft/aldahyaplay/data/local/db/AppDatabase_Impl.java',
 'resources/res/layout/activity_movies.xml',
 'resources/res/layout/activity_series.xml',
 'resources/res/layout/activity_series_info.xml',
 'resources/res/layout/movie_item.xml',
 'resources/res/values/strings.xml',
 'resources/res/values-ar/strings.xml',
]
for rel in exact_java: add('EXACT', java/rel)

# Candidate adapters/view holders and models
add_matches(java, 'ADAPTER_CANDIDATE', [
    'BaseAdapter','RecyclerView.Adapter','getView(','onBindViewHolder','movie_item','streamDisplayName','episodeName','setText('
], limit=45)
add_matches(java/'sources/z6' if (java/'sources/z6').exists() else java, 'MODEL_CANDIDATE', [
    'streamDisplayName','streamIcon','seriesId','episode','season','categoryId','favorite'
], limit=30)
add_matches(java, 'SEARCH_REPOSITORY', [
    'getVodStreams','getSeries','categoryId','catid','search','streamDisplayName','title'
], limit=35)

# Exact smali that patch touches + likely database implementations
for rel in [
 'smali/com/mbm_soft/aldahyaplay/ui/movies/d.smali',
 'smali/com/mbm_soft/aldahyaplay/ui/movies/MoviesActivity$d.smali',
 'smali/com/mbm_soft/aldahyaplay/ui/series/d.smali',
 'smali/com/mbm_soft/aldahyaplay/ui/series/SeriesActivity$d.smali',
 'smali/com/mbm_soft/aldahyaplay/ui/series_info/SeriesInfoActivity.smali',
 'smali/com/mbm_soft/aldahyaplay/ui/series_info/SeriesInfoActivity$a.smali',
 'smali/com/mbm_soft/aldahyaplay/ui/series_info/SeriesInfoActivity$b.smali',
 'smali/com/mbm_soft/aldahyaplay/ui/vod_exo/VodActivity.smali',
 'smali/com/mbm_soft/aldahyaplay/ui/vod_vlc/VodVlcActivity.smali',
]: add('SMALI_EXACT', smali/rel)
add_matches(smali, 'DAO_SMALI', [
    'SELECT * from movie_table','SELECT * from series_table','streamDisplayName','categoryId','catid','favorite =='
], limit=25)

out.write_text(''.join(sections), encoding='utf-8')
print(f'wrote {out} with {len(sections)} sections')
