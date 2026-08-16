from pathlib import Path
import sys

root = Path(sys.argv[1])

def rp(rel): return root / rel
def read(rel): return rp(rel).read_text()
def write(rel,s): rp(rel).write_text(s)

def replace_once(rel, old, new, label):
    s=read(rel); n=s.count(old)
    if n != 1: raise SystemExit(f'{label}: expected 1 match in {rel}, got {n}')
    write(rel, s.replace(old,new,1)); print('OK',label)

# Keep the exact text the user typed independently from the server payload.
for rel, cls in [
    ('smali/com/mbm_soft/aldahyaplay/ui/movies/d.smali','Lcom/mbm_soft/aldahyaplay/ui/movies/d;'),
    ('smali/com/mbm_soft/aldahyaplay/ui/series/d.smali','Lcom/mbm_soft/aldahyaplay/ui/series/d;'),
]:
    s=read(rel)
    field='.field private searchToken:Ljava/lang/String;\n'
    if s.count(field)!=1: raise SystemExit(f'field anchor {rel}: {s.count(field)}')
    s=s.replace(field, '.field private searchFilterQuery:Ljava/lang/String;\n\n'+field,1)
    anchor='# direct methods\n'
    setter=f'''# direct methods\n.method public setSearchFilterQuery(Ljava/lang/String;)V\n    .locals 0\n    iput-object p1, p0, {cls}->searchFilterQuery:Ljava/lang/String;\n    return-void\n.end method\n\n'''
    if s.count(anchor)!=1: raise SystemExit(f'direct anchor {rel}')
    s=s.replace(anchor,setter,1)
    write(rel,s)
    print('OK raw query field/setter',rel)

# Persist all candidates for MY LIST, but display only exact matches for the full query.
replace_once('smali/com/mbm_soft/aldahyaplay/ui/movies/d.smali',
'''    invoke-interface {v0, p2}, Lw6/f;->W(Ljava/util/List;)V\n\n    iget-object v0, p0, Lcom/mbm_soft/aldahyaplay/ui/movies/d;->h:Landroidx/lifecycle/o;\n\n    invoke-virtual {v0, p2}, Landroidx/lifecycle/o;->k(Ljava/lang/Object;)V''',
'''    invoke-interface {v0, p2}, Lw6/f;->W(Ljava/util/List;)V\n\n    iget-object v1, p0, Lcom/mbm_soft/aldahyaplay/ui/movies/d;->searchFilterQuery:Ljava/lang/String;\n    invoke-static {p2, v1}, Lcom/mbm_soft/aldahyaplay/utils/SearchFilter;->movies(Ljava/util/List;Ljava/lang/String;)Ljava/util/List;\n    move-result-object v1\n\n    iget-object v0, p0, Lcom/mbm_soft/aldahyaplay/ui/movies/d;->h:Landroidx/lifecycle/o;\n\n    invoke-virtual {v0, v1}, Landroidx/lifecycle/o;->k(Ljava/lang/Object;)V''',
'movie exact post-filter')
replace_once('smali/com/mbm_soft/aldahyaplay/ui/series/d.smali',
'''    invoke-interface {v0, p2}, Lw6/f;->Q0(Ljava/util/List;)V\n\n    iget-object v0, p0, Lcom/mbm_soft/aldahyaplay/ui/series/d;->h:Landroidx/lifecycle/o;\n\n    invoke-virtual {v0, p2}, Landroidx/lifecycle/o;->k(Ljava/lang/Object;)V''',
'''    invoke-interface {v0, p2}, Lw6/f;->Q0(Ljava/util/List;)V\n\n    iget-object v1, p0, Lcom/mbm_soft/aldahyaplay/ui/series/d;->searchFilterQuery:Ljava/lang/String;\n    invoke-static {p2, v1}, Lcom/mbm_soft/aldahyaplay/utils/SearchFilter;->series(Ljava/util/List;Ljava/lang/String;)Ljava/util/List;\n    move-result-object v1\n\n    iget-object v0, p0, Lcom/mbm_soft/aldahyaplay/ui/series/d;->h:Landroidx/lifecycle/o;\n\n    invoke-virtual {v0, v1}, Landroidx/lifecycle/o;->k(Ljava/lang/Object;)V''',
'series exact post-filter')

# Odd lengths >1 use the previous even-length prefix on the UAE server, then the
# callback above filters candidates with the exact full query. Even lengths stay intact.
for rel, listener, activity, vm, method in [
    ('smali/com/mbm_soft/aldahyaplay/ui/movies/MoviesActivity$d.smali','Lcom/mbm_soft/aldahyaplay/ui/movies/MoviesActivity$d;','Lcom/mbm_soft/aldahyaplay/ui/movies/MoviesActivity;','Lcom/mbm_soft/aldahyaplay/ui/movies/d;','S'),
    ('smali/com/mbm_soft/aldahyaplay/ui/series/SeriesActivity$d.smali','Lcom/mbm_soft/aldahyaplay/ui/series/SeriesActivity$d;','Lcom/mbm_soft/aldahyaplay/ui/series/SeriesActivity;','Lcom/mbm_soft/aldahyaplay/ui/series/d;','T'),
]:
    s=read(rel)
    start=s.index('.method public c(Ljava/lang/String;)V')
    end=s.index('.end method',start)+len('.end method')
    new=f'''.method public c(Ljava/lang/String;)V\n    .locals 5\n\n    iget-object v0, p0, {listener}->a:{activity}\n\n    const/4 v1, 0x0\n    iput-boolean v1, v0, {activity}->myListActive:Z\n\n    iget-object v1, v0, {activity}->G:{vm}\n    invoke-virtual {{v1, p1}}, {vm}->setSearchFilterQuery(Ljava/lang/String;)V\n\n    move-object v4, p1\n    invoke-virtual {{p1}}, Ljava/lang/String;->length()I\n    move-result v2\n    const/4 v3, 0x1\n    if-le v2, v3, :encode_query\n    and-int/lit8 v3, v2, 0x1\n    if-eqz v3, :encode_query\n    add-int/lit8 v2, v2, -0x1\n    const/4 v3, 0x0\n    invoke-virtual {{p1, v3, v2}}, Ljava/lang/String;->substring(II)Ljava/lang/String;\n    move-result-object v4\n\n    :encode_query\n    invoke-static {{v4}}, La8/g;->a(Ljava/lang/String;)Ljava/lang/String;\n    move-result-object v1\n\n    new-instance v2, Ljava/lang/StringBuilder;\n    invoke-direct {{v2}}, Ljava/lang/StringBuilder;-><init>()V\n    const-string v3, "base64:"\n    invoke-virtual {{v2, v3}}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;\n    invoke-virtual {{v2, v1}}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;\n    invoke-virtual {{v2}}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;\n    move-result-object v1\n\n    iget-object v0, v0, {activity}->G:{vm}\n    invoke-virtual {{v0, v1}}, {vm}->{method}(Ljava/lang/String;)V\n    return-void\n.end method'''
    write(rel,s[:start]+new+s[end:])
    print('OK parity-safe listener',rel)

# Small non-focusable brand signature in the bottom-right of the activation page.
layout='res/layout/activity_intro.xml'
s=read(layout)
needle='''    <ProgressBar android:theme="@style/ProgressBarTheme" android:id="@id/loading"'''
brand='''    <TextView android:textSize="10.0sp" android:textColor="#99ffffff" android:layout_width="wrap_content" android:layout_height="wrap_content" android:layout_marginEnd="16.0dp" android:layout_marginBottom="10.0dp" android:text="Powered by : Shaikh Alkar" android:focusable="false" android:clickable="false" app:layout_constraintBottom_toBottomOf="parent" app:layout_constraintEnd_toEndOf="parent" />\n'''
if s.count(needle)!=1: raise SystemExit(f'activation layout anchor: {s.count(needle)}')
s=s.replace(needle,brand+needle,1)
write(layout,s)
print('OK activation branding')

print('V82_PATCH_OK')
