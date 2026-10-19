#!/usr/bin/env python3
"""Build an offline index from original open data. No invented names/coordinates.
Usage: build-search-catalog.py MX.zip output.db.bin [asentamientos.json vialidades.geojson]
Writes two ordered chunks (.001 and .002) for browser uploads, each under 25 MiB.
"""
import sys, json, sqlite3, gzip, zipfile, unicodedata, re, tempfile, pathlib, hashlib

def norm(s):
    return re.sub(r'[^a-z0-9]+', ' ', ''.join(c for c in unicodedata.normalize('NFD', s.lower()) if not unicodedata.combining(c))).strip()

def build():
    source, output = map(pathlib.Path, sys.argv[1:3])
    with zipfile.ZipFile(source) as z:
        records = [line.split('\t') for line in z.read('MX.txt').decode('utf-8').splitlines()]
    states = {r[10]: r[1].removeprefix('Estado de ') for r in records if r[7] == 'ADM1'}
    municipalities = {(r[10],r[11]):r[1] for r in records if r[7] == 'ADM2'}
    inegi_state = {'05':'07','06':'08','07':'05','08':'06'} # GeoNames uses FIPS, INEGI uses its own keys.
    rows=[]
    for r in records:
        if r[6] != 'P' or r[7] in ('PPLQ','PPLW','PPLH'): continue
        state=states.get(r[10], '')
        city=municipalities.get((r[10],r[11]),r[1])
        mun=inegi_state.get(r[10],r[10])+r[11].zfill(3) if r[11].isdigit() else ''
        label=', '.join(dict.fromkeys(x for x in (r[1],city,state) if x))
        rows.append(('gn:'+r[0],r[1],label,city,state,float(r[4]),float(r[5]),mun,'GeoNames',int(r[14] or 0),norm(' '.join((r[1],r[3],city,state)))))
    if len(sys.argv)>3:
        for r in json.load(open(sys.argv[3]))['datos']:
            name=r['nom_asen'];label=name+', Tijuana, Baja California'
            rows.append(('inegi:asen:'+r['cvegeo'],name,label,'Tijuana','Baja California',None,None,'02004','INEGI',0,norm(label)))
    if len(sys.argv)>4:
        streets={}
        for f in json.load(open(sys.argv[4]))['features']:
            p=f['properties'];g=f.get('geometry') or {};lines=g.get('coordinates',[])
            if not lines or not p.get('nomvial') or p['nomvial'].lower()=='ninguno':continue
            key=p['cve_loc']+p['cvevial']
            line=max(lines,key=len) if g['type']=='MultiLineString' else lines
            if key not in streets or len(line)>len(streets[key][1]):streets[key]=(p,line)
        for key,(p,line) in streets.items():
            lon,lat=line[len(line)//2][:2];name=p['nomvial'];label=p['tipovial']+' '+name+', Tijuana, Baja California'
            rows.append(('inegi:via:02004'+key,name,label,'Tijuana','Baja California',lat,lon,'02004','INEGI',0,norm(label)))
    with tempfile.TemporaryDirectory() as tmp:
        db=sqlite3.connect(str(pathlib.Path(tmp)/'catalog.db'))
        db.executescript('''PRAGMA page_size=4096;
CREATE TABLE places(id INTEGER PRIMARY KEY, external_id TEXT UNIQUE NOT NULL, name TEXT NOT NULL, label TEXT NOT NULL, city TEXT, state TEXT, lat REAL, lon REAL, municipality TEXT, source TEXT, population INTEGER, normalized TEXT);
CREATE VIRTUAL TABLE names USING fts4(normalized, tokenize=simple);
CREATE INDEX place_lat ON places(lat);
CREATE INDEX place_mun ON places(municipality);
CREATE TABLE downloaded(municipality TEXT PRIMARY KEY, downloaded_at INTEGER);
PRAGMA user_version=1;''')
        db.executemany('INSERT OR IGNORE INTO places(external_id,name,label,city,state,lat,lon,municipality,source,population,normalized) VALUES(?,?,?,?,?,?,?,?,?,?,?)',rows)
        db.execute('INSERT INTO names(docid,normalized) SELECT id,normalized FROM places')
        db.commit();count=db.execute('SELECT COUNT(*) FROM places').fetchone()[0]
        db.execute('VACUUM');db.close()
        output.parent.mkdir(parents=True,exist_ok=True)
        compressed = pathlib.Path(tmp)/'catalog.db.gz'
        with open(pathlib.Path(tmp)/'catalog.db','rb') as f, open(compressed,'wb') as raw:
            with gzip.GzipFile(filename='',fileobj=raw,mode='wb',mtime=0) as g:g.write(f.read())
        data=compressed.read_bytes()
        midpoint=(len(data)+1)//2
        parts=(data[:midpoint],data[midpoint:])
        for index,part in enumerate(parts,1):
            output.with_name(output.name+f'.{index:03d}').write_bytes(part)
    print(json.dumps({'entries':count,'compressedBytes':len(data),'sha256':hashlib.sha256(data).hexdigest(),'parts':[{'file':output.name+f'.{i:03d}','bytes':len(part),'sha256':hashlib.sha256(part).hexdigest()} for i,part in enumerate(parts,1)]}))
if __name__=='__main__':build()
