#!/usr/bin/env python3
from pathlib import Path
import re, sys

ROOT = Path(sys.argv[1])


def read(rel):
    return (ROOT/rel).read_text(encoding='utf-8')

def write(rel, text):
    p=ROOT/rel; p.parent.mkdir(parents=True, exist_ok=True); p.write_text(text, encoding='utf-8')

def replace_once(text, old, new, label):
    n=text.count(old)
    if n != 1:
        raise SystemExit(f'{label}: expected exactly 1 match, got {n}')
    return text.replace(old,new,1)

def replace_method(text, signature, new_method, label):
    start=text.find(signature)
    if start < 0: raise SystemExit(f'{label}: method start not found')
    end=text.find('.end method', start)
    if end < 0: raise SystemExit(f'{label}: method end not found')
    end += len('.end method')
    return text[:start] + new_method.rstrip() + text[end:]

# 1) Search: make UAE match Premium behavior (local search on text change).
MOV_LISTENER='smali/com/mbm_soft/aldahyaplay/ui/movies/MoviesActivity$d.smali'
SER_LISTENER='smali/com/mbm_soft/aldahyaplay/ui/series/SeriesActivity$d.smali'

mov=read(MOV_LISTENER)
mov=replace_method(mov,'.method public a(Ljava/lang/String;)Z',r'''.method public a(Ljava/lang/String;)Z
    .locals 2

    invoke-virtual {p1}, Ljava/lang/String;->isEmpty()Z
    move-result v0
    if-nez v0, :empty

    iget-object v0, p0, Lcom/mbm_soft/aldahyaplay/ui/movies/MoviesActivity$d;->a:Lcom/mbm_soft/aldahyaplay/ui/movies/MoviesActivity;
    iget-object v0, v0, Lcom/mbm_soft/aldahyaplay/ui/movies/MoviesActivity;->G:Lcom/mbm_soft/aldahyaplay/ui/movies/d;
    invoke-virtual {v0, p1}, Lcom/mbm_soft/aldahyaplay/ui/movies/d;->V(Ljava/lang/String;)V
    const/4 p1, 0x0
    return p1

    :empty
    iget-object p1, p0, Lcom/mbm_soft/aldahyaplay/ui/movies/MoviesActivity$d;->a:Lcom/mbm_soft/aldahyaplay/ui/movies/MoviesActivity;
    iget-object v0, p1, Lcom/mbm_soft/aldahyaplay/ui/movies/MoviesActivity;->G:Lcom/mbm_soft/aldahyaplay/ui/movies/d;
    invoke-static {p1}, Lcom/mbm_soft/aldahyaplay/ui/movies/MoviesActivity;->D0(Lcom/mbm_soft/aldahyaplay/ui/movies/MoviesActivity;)Lz6/g;
    move-result-object p1
    invoke-virtual {p1}, Lz6/g;->b()Ljava/lang/String;
    move-result-object p1
    invoke-virtual {v0, p1}, Lcom/mbm_soft/aldahyaplay/ui/movies/d;->X(Ljava/lang/String;)V
    const/4 p1, 0x0
    return p1
.end method''','movies query-change')
mov=replace_method(mov,'.method public b(Ljava/lang/String;)Z',r'''.method public b(Ljava/lang/String;)Z
    .locals 0
    const/4 p1, 0x0
    return p1
.end method''','movies query-submit')
write(MOV_LISTENER,mov)

ser=read(SER_LISTENER)
ser=replace_method(ser,'.method public a(Ljava/lang/String;)Z',r'''.method public a(Ljava/lang/String;)Z
    .locals 2

    invoke-virtual {p1}, Ljava/lang/String;->isEmpty()Z
    move-result v0
    if-nez v0, :empty

    iget-object v0, p0, Lcom/mbm_soft/aldahyaplay/ui/series/SeriesActivity$d;->a:Lcom/mbm_soft/aldahyaplay/ui/series/SeriesActivity;
    iget-object v0, v0, Lcom/mbm_soft/aldahyaplay/ui/series/SeriesActivity;->G:Lcom/mbm_soft/aldahyaplay/ui/series/d;
    invoke-virtual {v0, p1}, Lcom/mbm_soft/aldahyaplay/ui/series/d;->U(Ljava/lang/String;)V
    const/4 p1, 0x0
    return p1

    :empty
    iget-object p1, p0, Lcom/mbm_soft/aldahyaplay/ui/series/SeriesActivity$d;->a:Lcom/mbm_soft/aldahyaplay/ui/series/SeriesActivity;
    iget-object v0, p1, Lcom/mbm_soft/aldahyaplay/ui/series/SeriesActivity;->G:Lcom/mbm_soft/aldahyaplay/ui/series/d;
    invoke-static {p1}, Lcom/mbm_soft/aldahyaplay/ui/series/SeriesActivity;->D0(Lcom/mbm_soft/aldahyaplay/ui/series/SeriesActivity;)Lz6/k;
    move-result-object p1
    invoke-virtual {p1}, Lz6/k;->b()Ljava/lang/String;
    move-result-object p1
    invoke-virtual {v0, p1}, Lcom/mbm_soft/aldahyaplay/ui/series/d;->X(Ljava/lang/String;)V
    const/4 p1, 0x0
    return p1
.end method''','series query-change')
ser=replace_method(ser,'.method public b(Ljava/lang/String;)Z',r'''.method public b(Ljava/lang/String;)Z
    .locals 0
    const/4 p1, 0x0
    return p1
.end method''','series query-submit')
write(SER_LISTENER,ser)

# Force normal category loads to remote path so the repurposed local DAO functions are search-only.
for rel, clazz, remote in [
 ('smali/com/mbm_soft/aldahyaplay/ui/movies/d.smali','Lcom/mbm_soft/aldahyaplay/ui/movies/d;','T'),
 ('smali/com/mbm_soft/aldahyaplay/ui/series/d.smali','Lcom/mbm_soft/aldahyaplay/ui/series/d;','V')]:
    t=read(rel)
    sig='.method private synthetic N(Ljava/lang/String;Ljava/lang/Boolean;)V'
    new=f'''{sig}\n    .locals 0\n    .annotation system Ldalvik/annotation/Throws;\n        value = {{\n            Ljava/lang/Exception;\n        }}\n    .end annotation\n\n    invoke-{'direct' if remote in ('T','V') else 'virtual'} {{p0, p1}}, {clazz}->{remote}(Ljava/lang/String;)V\n    return-void\n.end method'''
    t=replace_method(t,sig,new,rel+' category route')
    write(rel,t)

# Patch both possible local category DAO queries into name searches; category calls above no longer use them.
for p in ROOT.rglob('*.smali'):
    s=p.read_text(encoding='utf-8', errors='ignore')
    old=s
    s=s.replace('SELECT * from movie_table where categoryId=? and favorite ==0 ORDER BY CAST(viewOrder As integer) asc', "SELECT * from movie_table where streamDisplayName LIKE '%'|| ? || '%' ORDER BY CAST(viewOrder As integer) asc")
    s=s.replace('SELECT * from movie_table where categoryId=? ORDER BY CAST(viewOrder As integer) asc', "SELECT * from movie_table where streamDisplayName LIKE '%'|| ? || '%' ORDER BY CAST(viewOrder As integer) asc")
    s=s.replace('SELECT * from series_table where catid=? and favorite ==0 ORDER BY CAST(viewOrder As integer) asc', "SELECT * from series_table where title LIKE '%'|| ? || '%' ORDER BY CAST(viewOrder As integer) asc")
    s=s.replace('SELECT * from series_table where catid=? ORDER BY CAST(viewOrder As integer) asc', "SELECT * from series_table where title LIKE '%'|| ? || '%' ORDER BY CAST(viewOrder As integer) asc")
    if s != old: p.write_text(s,encoding='utf-8')

# 2) Local resume/last episode helper.
HELPER=r'''.class public final Lcom/mbm_soft/aldahyaplay/utils/ResumeStore;
.super Ljava/lang/Object;
.source "ResumeStore.java"

.method private constructor <init>()V
    .locals 0
    invoke-direct {p0}, Ljava/lang/Object;-><init>()V
    return-void
.end method

.method private static prefs(Landroid/content/Context;)Landroid/content/SharedPreferences;
    .locals 2
    const-string v0, "premium_uae_resume"
    const/4 v1, 0x0
    invoke-virtual {p0, v0, v1}, Landroid/content/Context;->getSharedPreferences(Ljava/lang/String;I)Landroid/content/SharedPreferences;
    move-result-object v0
    return-object v0
.end method

.method private static key(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;
    .locals 1
    if-nez p1, :ok
    const-string p1, ""
    :ok
    new-instance v0, Ljava/lang/StringBuilder;
    invoke-direct {v0}, Ljava/lang/StringBuilder;-><init>()V
    invoke-virtual {v0, p0}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
    invoke-virtual {v0, p1}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
    invoke-virtual {v0}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;
    move-result-object v0
    return-object v0
.end method

.method public static getPosition(Landroid/content/Context;Ljava/lang/String;)J
    .locals 4
    invoke-static {p0}, Lcom/mbm_soft/aldahyaplay/utils/ResumeStore;->prefs(Landroid/content/Context;)Landroid/content/SharedPreferences;
    move-result-object v0
    const-string v1, "resume_"
    invoke-static {v1, p1}, Lcom/mbm_soft/aldahyaplay/utils/ResumeStore;->key(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;
    move-result-object v1
    const-wide/16 v2, 0x0
    invoke-interface {v0, v1, v2, v3}, Landroid/content/SharedPreferences;->getLong(Ljava/lang/String;J)J
    move-result-wide v0
    return-wide v0
.end method

.method public static savePosition(Landroid/content/Context;Ljava/lang/String;JJ)V
    .locals 6
    if-eqz p1, :done
    invoke-virtual {p1}, Ljava/lang/String;->isEmpty()Z
    move-result v0
    if-nez v0, :done
    const-wide/16 v0, 0x1388
    cmp-long v2, p2, v0
    if-lez v2, :done

    invoke-static {p0}, Lcom/mbm_soft/aldahyaplay/utils/ResumeStore;->prefs(Landroid/content/Context;)Landroid/content/SharedPreferences;
    move-result-object v0
    invoke-interface {v0}, Landroid/content/SharedPreferences;->edit()Landroid/content/SharedPreferences$Editor;
    move-result-object v0
    const-string v1, "resume_"
    invoke-static {v1, p1}, Lcom/mbm_soft/aldahyaplay/utils/ResumeStore;->key(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;
    move-result-object v1

    const-wide/16 v2, 0x0
    cmp-long v4, p4, v2
    if-lez v4, :store
    const-wide/32 v2, 0xea60
    sub-long v2, p4, v2
    cmp-long v4, p2, v2
    if-ltz v4, :store
    invoke-interface {v0, v1}, Landroid/content/SharedPreferences$Editor;->remove(Ljava/lang/String;)Landroid/content/SharedPreferences$Editor;
    move-result-object v0
    invoke-interface {v0}, Landroid/content/SharedPreferences$Editor;->apply()V
    return-void

    :store
    invoke-interface {v0, v1, p2, p3}, Landroid/content/SharedPreferences$Editor;->putLong(Ljava/lang/String;J)Landroid/content/SharedPreferences$Editor;
    move-result-object v0
    invoke-interface {v0}, Landroid/content/SharedPreferences$Editor;->apply()V
    :done
    return-void
.end method

.method public static saveSeason(Landroid/content/Context;Ljava/lang/String;I)V
    .locals 2
    invoke-static {p0}, Lcom/mbm_soft/aldahyaplay/utils/ResumeStore;->prefs(Landroid/content/Context;)Landroid/content/SharedPreferences;
    move-result-object v0
    invoke-interface {v0}, Landroid/content/SharedPreferences;->edit()Landroid/content/SharedPreferences$Editor;
    move-result-object v0
    const-string v1, "season_"
    invoke-static {v1, p1}, Lcom/mbm_soft/aldahyaplay/utils/ResumeStore;->key(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;
    move-result-object v1
    invoke-interface {v0, v1, p2}, Landroid/content/SharedPreferences$Editor;->putInt(Ljava/lang/String;I)Landroid/content/SharedPreferences$Editor;
    move-result-object v0
    invoke-interface {v0}, Landroid/content/SharedPreferences$Editor;->apply()V
    return-void
.end method

.method public static saveEpisode(Landroid/content/Context;Ljava/lang/String;II)V
    .locals 2
    invoke-static {p0, p1, p2}, Lcom/mbm_soft/aldahyaplay/utils/ResumeStore;->saveSeason(Landroid/content/Context;Ljava/lang/String;I)V
    invoke-static {p0}, Lcom/mbm_soft/aldahyaplay/utils/ResumeStore;->prefs(Landroid/content/Context;)Landroid/content/SharedPreferences;
    move-result-object v0
    invoke-interface {v0}, Landroid/content/SharedPreferences;->edit()Landroid/content/SharedPreferences$Editor;
    move-result-object v0
    const-string v1, "episode_"
    invoke-static {v1, p1}, Lcom/mbm_soft/aldahyaplay/utils/ResumeStore;->key(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;
    move-result-object v1
    invoke-interface {v0, v1, p3}, Landroid/content/SharedPreferences$Editor;->putInt(Ljava/lang/String;I)Landroid/content/SharedPreferences$Editor;
    move-result-object v0
    invoke-interface {v0}, Landroid/content/SharedPreferences$Editor;->apply()V
    return-void
.end method

.method public static getSeason(Landroid/content/Context;Ljava/lang/String;)I
    .locals 3
    invoke-static {p0}, Lcom/mbm_soft/aldahyaplay/utils/ResumeStore;->prefs(Landroid/content/Context;)Landroid/content/SharedPreferences;
    move-result-object v0
    const-string v1, "season_"
    invoke-static {v1, p1}, Lcom/mbm_soft/aldahyaplay/utils/ResumeStore;->key(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;
    move-result-object v1
    const/4 v2, 0x0
    invoke-interface {v0, v1, v2}, Landroid/content/SharedPreferences;->getInt(Ljava/lang/String;I)I
    move-result v0
    return v0
.end method

.method public static getEpisode(Landroid/content/Context;Ljava/lang/String;)I
    .locals 3
    invoke-static {p0}, Lcom/mbm_soft/aldahyaplay/utils/ResumeStore;->prefs(Landroid/content/Context;)Landroid/content/SharedPreferences;
    move-result-object v0
    const-string v1, "episode_"
    invoke-static {v1, p1}, Lcom/mbm_soft/aldahyaplay/utils/ResumeStore;->key(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;
    move-result-object v1
    const/4 v2, 0x0
    invoke-interface {v0, v1, v2}, Landroid/content/SharedPreferences;->getInt(Ljava/lang/String;I)I
    move-result v0
    return v0
.end method
'''
write('smali/com/mbm_soft/aldahyaplay/utils/ResumeStore.smali',HELPER)

# Series season click: remember selected season.
rel='smali/com/mbm_soft/aldahyaplay/ui/series_info/SeriesInfoActivity$a.smali'
t=read(rel)
needle='''    invoke-static {p1, p2}, Lcom/mbm_soft/aldahyaplay/ui/series_info/SeriesInfoActivity;->y0(Lcom/mbm_soft/aldahyaplay/ui/series_info/SeriesInfoActivity;Ljava/util/List;)V\n\n    return-void'''
repl='''    invoke-static {p1, p2}, Lcom/mbm_soft/aldahyaplay/ui/series_info/SeriesInfoActivity;->y0(Lcom/mbm_soft/aldahyaplay/ui/series_info/SeriesInfoActivity;Ljava/util/List;)V\n\n    iget-object p1, p0, Lcom/mbm_soft/aldahyaplay/ui/series_info/SeriesInfoActivity$a;->k:Lcom/mbm_soft/aldahyaplay/ui/series_info/SeriesInfoActivity;\n    invoke-virtual {p1}, Landroid/app/Activity;->getIntent()Landroid/content/Intent;\n    move-result-object p2\n    const-string p4, "id"\n    invoke-virtual {p2, p4}, Landroid/content/Intent;->getStringExtra(Ljava/lang/String;)Ljava/lang/String;\n    move-result-object p2\n    invoke-static {p1, p2, p3}, Lcom/mbm_soft/aldahyaplay/utils/ResumeStore;->saveSeason(Landroid/content/Context;Ljava/lang/String;I)V\n\n    return-void'''
t=replace_once(t,needle,repl,'save season')
write(rel,t)

# Episode click: remember exact episode within remembered season.
rel='smali/com/mbm_soft/aldahyaplay/ui/series_info/SeriesInfoActivity$b.smali'
t=read(rel)
t=replace_once(t,'.method public onItemClick(Landroid/widget/AdapterView;Landroid/view/View;IJ)V\n    .locals 2','.method public onItemClick(Landroid/widget/AdapterView;Landroid/view/View;IJ)V\n    .locals 4','episode locals')
needle='''    check-cast p1, Lz6/a;\n\n    iget-object p2, p1, Lz6/a;->c:Ljava/lang/String;'''
repl='''    check-cast p1, Lz6/a;\n\n    iget-object v0, p0, Lcom/mbm_soft/aldahyaplay/ui/series_info/SeriesInfoActivity$b;->j:Lcom/mbm_soft/aldahyaplay/ui/series_info/SeriesInfoActivity;\n    invoke-virtual {v0}, Landroid/app/Activity;->getIntent()Landroid/content/Intent;\n    move-result-object v1\n    const-string v2, "id"\n    invoke-virtual {v1, v2}, Landroid/content/Intent;->getStringExtra(Ljava/lang/String;)Ljava/lang/String;\n    move-result-object v1\n    invoke-static {v0, v1}, Lcom/mbm_soft/aldahyaplay/utils/ResumeStore;->getSeason(Landroid/content/Context;Ljava/lang/String;)I\n    move-result v2\n    invoke-static {v0, v1, v2, p3}, Lcom/mbm_soft/aldahyaplay/utils/ResumeStore;->saveEpisode(Landroid/content/Context;Ljava/lang/String;II)V\n\n    iget-object p2, p1, Lz6/a;->c:Ljava/lang/String;'''
t=replace_once(t,needle,repl,'save episode')
write(rel,t)

# Restore season and episode selection when opening a series.
rel='smali/com/mbm_soft/aldahyaplay/ui/series_info/SeriesInfoActivity.smali'
t=read(rel)
t=replace_once(t,'.method private D0(Lc7/b;)V\n    .locals 3','.method private D0(Lc7/b;)V\n    .locals 6','restore locals')
old='''    invoke-virtual {p1}, Lc7/b;->b()Ljava/util/List;\n\n    move-result-object v0\n\n    invoke-interface {v0}, Ljava/util/List;->size()I\n\n    move-result v0\n\n    if-lez v0, :cond_1\n\n    invoke-virtual {p1}, Lc7/b;->b()Ljava/util/List;\n\n    move-result-object p1\n\n    invoke-interface {p1, v1}, Ljava/util/List;->get(I)Ljava/lang/Object;\n\n    move-result-object p1\n\n    check-cast p1, Lz6/i;\n\n    invoke-virtual {p1}, Lz6/i;->a()Ljava/util/List;\n\n    move-result-object p1\n\n    invoke-direct {p0, p1}, Lcom/mbm_soft/aldahyaplay/ui/series_info/SeriesInfoActivity;->C0(Ljava/util/List;)V\n\n    :cond_1'''
new='''    invoke-virtual {p1}, Lc7/b;->b()Ljava/util/List;\n    move-result-object v0\n    invoke-interface {v0}, Ljava/util/List;->size()I\n    move-result v0\n    if-lez v0, :cond_1\n\n    invoke-virtual {p0}, Landroid/app/Activity;->getIntent()Landroid/content/Intent;\n    move-result-object v2\n    const-string v3, "id"\n    invoke-virtual {v2, v3}, Landroid/content/Intent;->getStringExtra(Ljava/lang/String;)Ljava/lang/String;\n    move-result-object v2\n    invoke-static {p0, v2}, Lcom/mbm_soft/aldahyaplay/utils/ResumeStore;->getSeason(Landroid/content/Context;Ljava/lang/String;)I\n    move-result v3\n    if-ltz v3, :season_zero\n    if-lt v3, v0, :season_ok\n    :season_zero\n    const/4 v3, 0x0\n    :season_ok\n\n    invoke-virtual {p1}, Lc7/b;->b()Ljava/util/List;\n    move-result-object p1\n    invoke-interface {p1, v3}, Ljava/util/List;->get(I)Ljava/lang/Object;\n    move-result-object p1\n    check-cast p1, Lz6/i;\n    invoke-virtual {p1}, Lz6/i;->a()Ljava/util/List;\n    move-result-object p1\n    invoke-direct {p0, p1}, Lcom/mbm_soft/aldahyaplay/ui/series_info/SeriesInfoActivity;->C0(Ljava/util/List;)V\n\n    iget-object v4, p0, Lcom/mbm_soft/aldahyaplay/ui/series_info/SeriesInfoActivity;->H:Ld7/m;\n    iget-object v4, v4, Ld7/m;->d0:Landroid/widget/ListView;\n    invoke-virtual {v4, v3}, Landroid/widget/ListView;->setSelection(I)V\n\n    invoke-static {p0, v2}, Lcom/mbm_soft/aldahyaplay/utils/ResumeStore;->getEpisode(Landroid/content/Context;Ljava/lang/String;)I\n    move-result v4\n    invoke-interface {p1}, Ljava/util/List;->size()I\n    move-result v5\n    if-ltz v4, :episode_zero\n    if-lt v4, v5, :episode_ok\n    :episode_zero\n    const/4 v4, 0x0\n    :episode_ok\n    iget-object v5, p0, Lcom/mbm_soft/aldahyaplay/ui/series_info/SeriesInfoActivity;->H:Ld7/m;\n    iget-object v5, v5, Ld7/m;->M:Landroid/widget/ListView;\n    invoke-virtual {v5, v4}, Landroid/widget/ListView;->setSelection(I)V\n\n    :cond_1'''
t=replace_once(t,old,new,'restore last episode')
write(rel,t)

# ExoPlayer: load saved position after media source is set, save before release.
rel='smali/com/mbm_soft/aldahyaplay/ui/vod_exo/VodActivity.smali'
t=read(rel)
t=replace_once(t,'.method private F0(Landroid/net/Uri;)V\n    .locals 4','.method private F0(Landroid/net/Uri;)V\n    .locals 8','exo F0 locals')
needle='''    iget-object v0, p0, Lcom/mbm_soft/aldahyaplay/ui/vod_exo/VodActivity;->E:Ly2/b1;\n\n    invoke-virtual {v0, p1}, Ly2/b1;->z0(Lx3/j;)V\n\n    iget-object p1, p0, Lcom/mbm_soft/aldahyaplay/ui/vod_exo/VodActivity;->playerView:Lcom/google/android/exoplayer2/ui/PlayerView;'''
repl='''    iget-object v0, p0, Lcom/mbm_soft/aldahyaplay/ui/vod_exo/VodActivity;->E:Ly2/b1;\n\n    invoke-virtual {v0, p1}, Ly2/b1;->z0(Lx3/j;)V\n\n    invoke-virtual {p0}, Landroid/app/Activity;->getIntent()Landroid/content/Intent;\n    move-result-object v4\n    const-string v5, "stream_link"\n    invoke-virtual {v4, v5}, Landroid/content/Intent;->getStringExtra(Ljava/lang/String;)Ljava/lang/String;\n    move-result-object v4\n    invoke-static {p0, v4}, Lcom/mbm_soft/aldahyaplay/utils/ResumeStore;->getPosition(Landroid/content/Context;Ljava/lang/String;)J\n    move-result-wide v4\n    const-wide/16 v6, 0x1388\n    cmp-long v6, v4, v6\n    if-lez v6, :no_resume\n    iget-object v6, p0, Lcom/mbm_soft/aldahyaplay/ui/vod_exo/VodActivity;->E:Ly2/b1;\n    const/4 v7, 0x0\n    invoke-virtual {v6, v7, v4, v5}, Ly2/b1;->f(IJ)V\n    :no_resume\n\n    iget-object p1, p0, Lcom/mbm_soft/aldahyaplay/ui/vod_exo/VodActivity;->playerView:Lcom/google/android/exoplayer2/ui/PlayerView;'''
t=replace_once(t,needle,repl,'exo load resume')
t=replace_once(t,'.method private I0()V\n    .locals 1','.method private I0()V\n    .locals 6','exo I0 locals')
needle='''    iget-object v0, p0, Lcom/mbm_soft/aldahyaplay/ui/vod_exo/VodActivity;->E:Ly2/b1;\n\n    if-eqz v0, :cond_0\n\n    invoke-direct {p0}, Lcom/mbm_soft/aldahyaplay/ui/vod_exo/VodActivity;->K0()V'''
repl='''    iget-object v0, p0, Lcom/mbm_soft/aldahyaplay/ui/vod_exo/VodActivity;->E:Ly2/b1;\n\n    if-eqz v0, :cond_0\n\n    invoke-virtual {p0}, Landroid/app/Activity;->getIntent()Landroid/content/Intent;\n    move-result-object v1\n    const-string v2, "stream_link"\n    invoke-virtual {v1, v2}, Landroid/content/Intent;->getStringExtra(Ljava/lang/String;)Ljava/lang/String;\n    move-result-object v1\n    iget-object v2, p0, Lcom/mbm_soft/aldahyaplay/ui/vod_exo/VodActivity;->E:Ly2/b1;\n    invoke-virtual {v2}, Ly2/b1;->getCurrentPosition()J\n    move-result-wide v2\n    iget-object v4, p0, Lcom/mbm_soft/aldahyaplay/ui/vod_exo/VodActivity;->E:Ly2/b1;\n    invoke-virtual {v4}, Ly2/b1;->getDuration()J\n    move-result-wide v4\n    invoke-static {p0, v1, v2, v3, v4, v5}, Lcom/mbm_soft/aldahyaplay/utils/ResumeStore;->savePosition(Landroid/content/Context;Ljava/lang/String;JJ)V\n\n    invoke-direct {p0}, Lcom/mbm_soft/aldahyaplay/ui/vod_exo/VodActivity;->K0()V'''
t=replace_once(t,needle,repl,'exo save resume')
write(rel,t)

# VLC: restore shortly after play, save on pause/back/stop.
rel='smali/com/mbm_soft/aldahyaplay/ui/vod_vlc/VodVlcActivity.smali'
t=read(rel)
t=replace_once(t,'.method private K0(Landroid/net/Uri;)V\n    .locals 2','.method private K0(Landroid/net/Uri;)V\n    .locals 5','vlc K0 locals')
needle='''    iget-object p1, p0, Lcom/mbm_soft/aldahyaplay/ui/vod_vlc/VodVlcActivity;->G:Lorg/videolan/libvlc/MediaPlayer;\n\n    invoke-virtual {p1}, Lorg/videolan/libvlc/MediaPlayer;->play()V\n\n    invoke-direct {p0}, Lcom/mbm_soft/aldahyaplay/ui/vod_vlc/VodVlcActivity;->M0()V'''
repl='''    iget-object p1, p0, Lcom/mbm_soft/aldahyaplay/ui/vod_vlc/VodVlcActivity;->G:Lorg/videolan/libvlc/MediaPlayer;\n\n    invoke-virtual {p1}, Lorg/videolan/libvlc/MediaPlayer;->play()V\n\n    invoke-virtual {p0}, Landroid/app/Activity;->getIntent()Landroid/content/Intent;\n    move-result-object v2\n    const-string v3, "stream_link"\n    invoke-virtual {v2, v3}, Landroid/content/Intent;->getStringExtra(Ljava/lang/String;)Ljava/lang/String;\n    move-result-object v2\n    invoke-static {p0, v2}, Lcom/mbm_soft/aldahyaplay/utils/ResumeStore;->getPosition(Landroid/content/Context;Ljava/lang/String;)J\n    move-result-wide v2\n    const-wide/16 v4, 0x1388\n    cmp-long v4, v2, v4\n    if-lez v4, :vlc_no_resume\n    iget-object v4, p0, Lcom/mbm_soft/aldahyaplay/ui/vod_vlc/VodVlcActivity;->G:Lorg/videolan/libvlc/MediaPlayer;\n    invoke-virtual {v4, v2, v3}, Lorg/videolan/libvlc/MediaPlayer;->setTime(J)J\n    :vlc_no_resume\n\n    invoke-direct {p0}, Lcom/mbm_soft/aldahyaplay/ui/vod_vlc/VodVlcActivity;->M0()V'''
t=replace_once(t,needle,repl,'vlc load resume')

# Add private saver method before K0.
saver=r'''.method private S0()V
    .locals 6
    iget-object v0, p0, Lcom/mbm_soft/aldahyaplay/ui/vod_vlc/VodVlcActivity;->G:Lorg/videolan/libvlc/MediaPlayer;
    if-eqz v0, :done
    invoke-virtual {p0}, Landroid/app/Activity;->getIntent()Landroid/content/Intent;
    move-result-object v1
    const-string v2, "stream_link"
    invoke-virtual {v1, v2}, Landroid/content/Intent;->getStringExtra(Ljava/lang/String;)Ljava/lang/String;
    move-result-object v1
    invoke-virtual {v0}, Lorg/videolan/libvlc/MediaPlayer;->getTime()J
    move-result-wide v2
    invoke-virtual {v0}, Lorg/videolan/libvlc/MediaPlayer;->getLength()J
    move-result-wide v4
    invoke-static {p0, v1, v2, v3, v4, v5}, Lcom/mbm_soft/aldahyaplay/utils/ResumeStore;->savePosition(Landroid/content/Context;Ljava/lang/String;JJ)V
    :done
    return-void
.end method

'''
idx=t.find('.method private K0(Landroid/net/Uri;)V')
if idx<0: raise SystemExit('VLC K0 insertion not found')
t=t[:idx]+saver+t[idx:]
for sig, anchor in [
('.method public onBackPressed()V','    .locals 1\n'),
('.method protected onPause()V','    .locals 1\n'),
('.method protected onStop()V','    .locals 1\n')]:
    start=t.find(sig)
    if start<0: raise SystemExit(sig+' missing')
    pos=t.find(anchor,start)
    if pos<0: raise SystemExit(sig+' locals missing')
    pos += len(anchor)
    t=t[:pos]+'\n    invoke-direct {p0}, Lcom/mbm_soft/aldahyaplay/ui/vod_vlc/VodVlcActivity;->S0()V\n'+t[pos:]
write(rel,t)

# Static verification
checks={
 'movie_search': "streamDisplayName LIKE '%'|| ? || '%'",
 'series_search': "title LIKE '%'|| ? || '%'",
 'resume_helper': 'Lcom/mbm_soft/aldahyaplay/utils/ResumeStore;',
}
whole='\n'.join(p.read_text(errors='ignore') for p in ROOT.rglob('*.smali'))
for name,needle in checks.items():
    if needle not in whole: raise SystemExit(f'verification failed: {name}')
print('PATCH_OK')
