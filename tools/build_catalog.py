import json,re,hashlib
o=json.load(open('muto_piano.json'))
# nom affiché : (français, russe)
C={'AdamA':('A. Adam','А. Адан'),'AlbenizIMF':('I. Albéniz','И. Альбенис'),'AlkanCV':('C.-V. Alkan','Ш.-В. Алкан'),'AndreJ':('J. André','Й. Андре'),
'Anonymous':('Anonyme','Аноним'),'AscherJ':('J. Ascher','Й. Ашер'),'BachCPE':('C. P. E. Bach','К. Ф. Э. Бах'),'BachJS':('J.-S. Bach','И. С. Бах'),
'BartokB':('B. Bartók','Б. Барток'),'BeethovenLv':('L. van Beethoven','Л. ван Бетховен'),'BehrF':('F. Behr','Ф. Бер'),'BendaJA':('J. A. Benda','Я. А. Бенда'),
'BizetG':('G. Bizet','Ж. Бизе'),'BlumenthalJ':('J. Blumenthal','Я. Блюменталь'),'BrahmsJ':('J. Brahms','И. Брамс'),'BrownCJ':('C. J. Brown','К. Дж. Браун'),
'BurgmullerJFF':('F. Burgmüller','Ф. Бургмюллер'),'ChopinFF':('F. Chopin','Ф. Шопен'),'ClementiM':('M. Clementi','М. Клементи'),'ComeauO':('O. Comeau','О. Комо'),
'CzernyC':('C. Czerny','К. Черни'),'DebussyC':('C. Debussy','К. Дебюсси'),'DoonanSC':('S. C. Doonan','С. К. Дунан'),'DussekJL':('J. L. Dussek','Я. Л. Дусик'),
'FieldJ':('J. Field','Дж. Филд'),'FischerJKF':('J. K. F. Fischer','И. К. Ф. Фишер'),'FrobergerJJ':('J. J. Froberger','И. Я. Фробергер'),'GobbaertsL':('L. Gobbaerts','Л. Гоббертс'),
'GottschalkLM':('L. M. Gottschalk','Л. М. Готшалк'),'GriegE':('E. Grieg','Э. Григ'),'HandelGF':('G. F. Haendel','Г. Ф. Гендель'),'HaydnFJ':('J. Haydn','Й. Гайдн'),
'HunterC':('C. Hunter','К. Хантер'),'IlievGK':('G. K. Iliev','Г. К. Илиев'),'J. Kuhnau (1660-1722)':('J. Kuhnau','И. Кунау'),'JeffreysJ':('J. Jeffreys','Дж. Джеффрис'),
'JoplinS':('S. Joplin','С. Джоплин'),'Kruetzer':('Kreutzer','Крейцер'),'KuhlauF':('F. Kuhlau','Ф. Кулау'),'KumarR':('R. Kumar','Р. Кумар'),'LisztF':('F. Liszt','Ф. Лист'),
'MagnenatS':('S. Magnenat','С. Маньена'),'Mendelssohn-BartholdyF':('F. Mendelssohn','Ф. Мендельсон'),'MinkusLA':('L. Minkus','Л. Минкус'),'MozartWA':('W. A. Mozart','В. А. Моцарт'),
'MuellerAE':('A. E. Müller','А. Э. Мюллер'),'MussorgskyM':('M. Moussorgski','М. Мусоргский'),'PachelbelJ':('J. Pachelbel','И. Пахельбель'),'PeaseAH':('A. H. Pease','А. Х. Пиз'),
'PejacsevichD':('D. Pejačević','Д. Пеячевич'),'PleyelIJ':('I. Pleyel','И. Плейель'),'RachmaninoffS':('S. Rachmaninov','С. Рахманинов'),'RameauJP':('J.-P. Rameau','Ж.-Ф. Рамо'),
'Rimsky-KorsakovN':('N. Rimski-Korsakov','Н. Римский-Корсаков'),'RootGF':('G. F. Root','Дж. Ф. Рут'),'SatieE':('E. Satie','Э. Сати'),'ScarlattiD':('D. Scarlatti','Д. Скарлатти'),
'SchubertF':('F. Schubert','Ф. Шуберт'),'SchumannR':('R. Schumann','Р. Шуман'),'ScriabinA':('A. Scriabine','А. Скрябин'),'SidwellA':('A. Sidwell','А. Сидуэлл'),
'SmallwoodW':('W. Smallwood','У. Смоллвуд'),'SousaJP':('J. P. Sousa','Дж. Ф. Суза'),'Spagnoletti':('Spagnoletti','Спаньолетти'),'StanchinskyAV':('A. Stantchinski','А. Станчинский'),
'StraussJJ':('J. Strauss','И. Штраус'),'TchaikovskyPI':('P. I. Tchaïkovski','П. И. Чайковский'),'Traditional':('Traditionnel','Народная музыка'),'TurpinT':('T. Turpin','Т. Тёрпин'),
'VerdiG':('G. Verdi','Дж. Верди'),'WanhalJ':('J. B. Vanhal','Я. К. Ванхаль'),'WilliamsRH':('R. H. Williams','Р. Х. Уильямс'),'Yaniewicz':('F. Janiewicz','Ф. Яневич'),
'Balakirev':('M. Balakirev','М. Балакирев'),'Glinka':('M. Glinka','М. Глинка'),'Prokofiev':('S. Prokofiev','С. Прокофьев'),'Ravel':('M. Ravel','М. Равель'),
'Haydn':('J. Haydn','Й. Гайдн'),'Bach':('J.-S. Bach','И. С. Бах'),'Beethoven':('L. van Beethoven','Л. ван Бетховен'),'Brahms':('J. Brahms','И. Брамс'),'Chopin':('F. Chopin','Ф. Шопен'),
'Debussy':('C. Debussy','К. Дебюсси'),'Liszt':('F. Liszt','Ф. Лист'),'Mozart':('W. A. Mozart','В. А. Моцарт'),'Rachmaninoff':('S. Rachmaninov','С. Рахманинов'),
'Schubert':('F. Schubert','Ф. Шуберт'),'Schumann':('R. Schumann','Р. Шуман'),'Scriabin':('A. Scriabine','А. Скрябин'),
}
STY={'Baroque':'baroque','Classical':'classique','Romantic':'romantique','Romantique':'romantique','Folk':'folk','Jazz':'jazz','Technique':'technique',
 'Avant-garde':'moderne','Modern':'moderne','March':'marche','Popular / Dance':'danse','Hymn':'hymne','Song':'chanson',None:'classique'}
EASY_COMPOSERS={'BurgmullerJFF','Traditional','BehrF','KuhlauF'}
HARD_COMPOSERS={'LisztF','AlkanCV','ScriabinA','RachmaninoffS','GottschalkLM','StanchinskyAV'}
def level(x):
    p=(x['path']+' '+(x['opus'] or '')+' '+(x['title'] or '')).lower()
    if x['composer'] in HARD_COMPOSERS or x['size']>15000: return 3
    if x['composer'] in EASY_COMPOSERS or re.search(r'anh|anna|op\.? ?39|op\.? ?68|gymnop|kinder|album|children|enfant|sonatin|easy|petit|minuet|menuet',p) or x['size']<4300: return 1
    return 2
MU='https://www.mutopiaproject.org/ftp/'
items=[]
for x in o:
    cfr,cru=C.get(x['composer'],(x['composer'],x['composer']))
    t=(x['title'] or x['name']).replace('\\"','"')
    base=MU+x['path']+'/'+x['name']
    items.append(dict(id='mu_'+hashlib.md5(x['path'].encode()).hexdigest()[:10],t=t,o=x['opus'] or '',cf=cfr,cr=cru,s=STY.get(x['style'],'classique'),l=level(x),
        src='mutopia',mid=base+'.mid',pdf=base+'-a4.pdf',img=base+'-preview.png',lic=x['license'] or 'Public Domain'))
# ASAP : partitions MusicXML des grandes œuvres (niveau avancé)
AS='https://raw.githubusercontent.com/fosfrancesco/asap-dataset/master/'
NAMES={'Piano_Sonatas':'Sonate','Keyboard_Sonatas':'Sonate','Fugue':'Fugue','Prelude':'Prélude','Ballades':'Ballade','Etudes_op_10':'Étude op. 10','Etudes_op_25':'Étude op. 25',
 'Scherzos':'Scherzo','Polonaises':'Polonaise op.','Impromptu_op.90_D.899':'Impromptu D. 899','Impromptu_op142':'Impromptu op. 142','Preludes_op_23':'Prélude op. 23','Preludes_op_32':'Prélude op. 32',
 'Transcendental_Etudes':'Étude d’exécution transcendante','Kreisleriana':'Kreisleriana','Hungarian_Rhapsodies':'Rhapsodie hongroise','Etudes_op_8':'Étude op. 8','Sonatas':'Sonate',
 'Six_Pieces_op_118':'Klavierstücke op. 118','Concert_Etude_S145':'Étude de concert S. 145','Sonata_2':'Sonate n° 2','Sonata_3':'Sonate n° 3'}
for line in open('asap_tree.txt'):
    p=line.strip()
    if not p.endswith('xml_score.musicxml'): continue
    d=p.rsplit('/',1)[0]
    if 'no_repeat' in d or d.endswith('_repeat') or 'no_trio' in d or 'no_first' in d or 'no_2' in d: continue
    parts=d.split('/'); comp=parts[0]
    rest=parts[1:]
    if len(rest)==1: title=rest[0].replace('_',' ')
    else:
        g=NAMES.get(rest[0],rest[0].replace('_',' ')); n=rest[1].replace('_',' ')
        if rest[0] in ('Fugue','Prelude'): title=f"{g} BWV {n.replace('bwv ','')}"
        elif rest[0] in ('Piano_Sonatas','Keyboard_Sonatas','Sonatas'):
            a,b=(n.split('-')+[''])[:2]; title=f"{g} n° {a}" + (f", mvt {b}" if b else '')
        elif rest[0].startswith('Sonata_'): title=f"{g}, {n}"
        else: title=f"{g} n° {n}" if n[:1].isdigit() else f"{g} – {n}"
    cfr,cru=C.get(comp,(comp,comp))
    items.append(dict(id='as_'+hashlib.md5(d.encode()).hexdigest()[:10],t=title,o='',cf=cfr,cr=cru,s='concert',l=3,src='asap',xml=AS+d+'/xml_score.musicxml',lic='CC BY-NC-SA 4.0 (ASAP)'))
items.sort(key=lambda i:(i['l'],i['cf'],i['t']))
cat={'version':1,'sources':{'mutopia':'Mutopia Project – mutopiaproject.org (domaine public / Creative Commons)','asap':'ASAP dataset – github.com/fosfrancesco/asap-dataset (CC BY-NC-SA 4.0)'},'items':items}
json.dump(cat,open('catalog.json','w'),ensure_ascii=False,separators=(',',':'))
import collections; print(len(items), collections.Counter(i['l'] for i in items), collections.Counter(i['src'] for i in items))
print([ (i['cf'],i['t']) for i in items if i['l']==1][:40])
