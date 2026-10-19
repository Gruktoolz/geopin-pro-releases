package mx.coberturapunto;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.location.Address;
import android.os.Bundle;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.GZIPInputStream;
import org.json.*;

/** Offline national gazetteer plus bounded, reusable municipal INEGI downloads. Worker threads only. */
final class SearchCatalog implements AutoCloseable {
    private final Context context;
    private SQLiteDatabase db;
    SearchCatalog(Context context){this.context=context.getApplicationContext();}
    private synchronized SQLiteDatabase database() throws IOException {
        if(db!=null)return db;
        File target=new File(context.getFilesDir(),"mexico-search-v1.db");
        if(!target.exists()){
            File staging=new File(context.getFilesDir(),"mexico-search-v1.tmp");
            try(InputStream in=new GZIPInputStream(context.getAssets().open("search/mexico-v1.db.bin"));OutputStream out=new FileOutputStream(staging)){
                byte[] buffer=new byte[32768];int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);
            }
            if(!staging.renameTo(target))throw new IOException("No se pudo preparar el catálogo");
        }
        db=SQLiteDatabase.openDatabase(target.getPath(),null,SQLiteDatabase.OPEN_READWRITE);
        db.enableWriteAheadLogging();return db;
    }
    void prepare() throws IOException {database();}
    List<Address> search(String text,List<SearchMemory.Area> workAreas) throws IOException {
        String query=SearchPolicy.prefixQuery(text);if(query.isEmpty())return Collections.emptyList();
        SQLiteDatabase sql=database();List<Address> found=new ArrayList<>();
        SearchPolicy.SearchIntent intent=SearchPolicy.intent(text);
        List<SearchMemory.Area> areas=workAreas==null?Collections.emptyList():workAreas;
        for(SearchMemory.Area area:areas){
            int before=found.size();String areaWhere="";String[] areaArgs=new String[0];
            // INEGI rows are municipal names without exact coordinates; keep them scoped by municipality.
            String municipality=nearest(area.lat,area.lon).municipality;
            if(!municipality.isEmpty()){
                areaWhere=" AND ((p.lat BETWEEN ? AND ? AND p.lon BETWEEN ? AND ?) OR p.municipality=?)";
                areaArgs=new String[]{""+(area.lat-.65),""+(area.lat+.65),""+(area.lon-.8),""+(area.lon+.8),municipality};
            }else{
                areaWhere=" AND p.lat BETWEEN ? AND ? AND p.lon BETWEEN ? AND ?";
                areaArgs=new String[]{""+(area.lat-.65),""+(area.lat+.65),""+(area.lon-.8),""+(area.lon+.8)};
            }
            collectIntent(sql,text,query,intent,areaWhere,areaArgs,area,found);
            // Bound each zone's contribution so frequent broad queries stay responsive.
            if(found.size()-before>12)found.subList(before+12,found.size()).clear();
        }
        if(found.size()<12)collectIntent(sql,text,query,intent,"",new String[0],areas.isEmpty()?null:areas.get(0),found);
        return found;
    }
    private void collectIntent(SQLiteDatabase sql,String text,String query,SearchPolicy.SearchIntent intent,String where,String[] args,SearchMemory.Area area,List<Address> found){
        if(intent.includesBoth()){
            String settlementQuery=SearchPolicy.prefixQuery(intent.settlement());
            String streetQuery=SearchPolicy.prefixQuery(intent.street());
            if(!settlementQuery.isEmpty())collect(sql,settlementQuery,where+" AND p.external_id LIKE 'inegi:asen:%'",args,intent.settlement(),area,found);
            if(!streetQuery.isEmpty())collect(sql,streetQuery,where+" AND p.external_id LIKE 'inegi:via:%'",args,intent.street(),area,found);
            if(found.isEmpty())collect(sql,query,where,args,text,area,found);
        }else collect(sql,query,where,args,text,area,found);
    }
    private void collect(SQLiteDatabase sql,String query,String where,String[] extra,String text,SearchMemory.Area area,List<Address> out){
        List<String> args=new ArrayList<>();args.add(query);args.addAll(Arrays.asList(extra));
        String rank=(text.trim().length()<=2?"CASE WHEN p.external_id LIKE 'inegi:asen:%' THEN 0 WHEN p.source='GeoNames' AND p.population>=5000 THEN 1 ELSE 2 END,":"")+"CASE WHEN p.name LIKE ? THEN 0 ELSE 1 END";
        args.add(text.trim()+"%");
        if(area!=null){rank+=", CASE WHEN p.lat IS NULL THEN 0.05 ELSE ((p.lat-?)*(p.lat-?)+(p.lon-?)*(p.lon-?)*?) END";
            args.add(""+area.lat);args.add(""+area.lat);args.add(""+area.lon);args.add(""+area.lon);args.add(""+Math.pow(Math.cos(Math.toRadians(area.lat)),2));}
        try(Cursor c=sql.rawQuery("SELECT p.label,p.city,p.lat,p.lon,p.source,p.name,p.external_id FROM names JOIN places p ON p.id=names.docid WHERE names MATCH ?"+where+" ORDER BY "+rank+",p.population DESC,p.label LIMIT 24",args.toArray(new String[0]))){
            while(c.moveToNext()){
                Address a=new Address(new Locale("es","MX"));a.setAddressLine(0,c.getString(0));a.setLocality(c.getString(1));a.setCountryCode("MX");
                if(!c.isNull(2)&&!c.isNull(3)){a.setLatitude(c.getDouble(2));a.setLongitude(c.getDouble(3));}
                Bundle info=new Bundle();info.putString("source",c.getString(4));info.putString("name",c.getString(5));info.putString("kind",c.getString(6).startsWith("inegi:asen:")?"settlement":c.getString(6).startsWith("inegi:via:")?"street":"place");a.setExtras(info);out.add(a);
            }
        }
    }
    record City(String name,String state,String municipality){}
    City nearest(double lat,double lon) throws IOException {
        SQLiteDatabase sql=database();
        try(Cursor c=sql.rawQuery("SELECT name,state,municipality FROM places WHERE source='GeoNames' AND population>=5000 AND lat BETWEEN ? AND ? AND lon BETWEEN ? AND ? ORDER BY (lat-?)*(lat-?)+(lon-?)*(lon-?)*? LIMIT 1",
                new String[]{""+(lat-.7),""+(lat+.7),""+(lon-.9),""+(lon+.9),""+lat,""+lat,""+lon,""+lon,""+Math.pow(Math.cos(Math.toRadians(lat)),2)})){
            if(c.moveToFirst())return new City(c.getString(0),c.getString(1),c.getString(2));
        }
        return new City("","","");
    }
    void enrich(double lat,double lon) throws Exception {
        City city=nearest(lat,lon);String key=city.municipality;
        if(!key.matches("\\d{5}"))return;
        SQLiteDatabase sql=database();
        try(Cursor c=sql.rawQuery("SELECT downloaded_at FROM downloaded WHERE municipality=?",new String[]{key})){
            if(c.moveToFirst() && System.currentTimeMillis()-c.getLong(0)<30L*24*3600*1000)return;
        }
        // Public documented catalogs. No paid API, no scraping of Google, no per-keystroke requests.
        JSONArray settlements=download("asentamientos/"+key.substring(0,2)+"/"+key.substring(2));
        JSONArray streets=download("vialidades/"+key.substring(0,2)+"/"+key.substring(2));
        sql.beginTransaction();
        try{
            insert(sql,settlements,city,"nom_asen","asen",key);
            insert(sql,streets,city,"nomvial","via",key);
            sql.execSQL("INSERT OR REPLACE INTO downloaded VALUES(?,?)",new Object[]{key,System.currentTimeMillis()});sql.setTransactionSuccessful();
        }finally{sql.endTransaction();}
    }
    private void insert(SQLiteDatabase sql,JSONArray rows,City city,String field,String type,String key) throws JSONException {
        Set<String> seen=new HashSet<>();
        for(int i=0;i<rows.length();i++){
            JSONObject r=rows.getJSONObject(i);String name=r.optString(field,"").trim();
            if(name.isEmpty() || name.equalsIgnoreCase("NINGUNO"))continue;
            String loc=r.optString("cve_loc","");String code=r.optString(type.equals("asen")?"cve_asen":"cvevial","");
            String id="inegi:"+type+":"+key+loc+code;
            if(!seen.add(id))continue;
            String label=name+", "+city.name+", "+city.state;
            // Tabular names have no exact point: never substitute a city's center as the address.
            sql.execSQL("INSERT OR IGNORE INTO places(external_id,name,label,city,state,municipality,source,population,normalized) VALUES(?,?,?,?,?,?,?,0,?)",
                    new Object[]{id,name,label,city.name,city.state,key,"INEGI",SearchPolicy.normalize(label)});
            sql.execSQL("INSERT OR IGNORE INTO names(docid,normalized) SELECT id,normalized FROM places WHERE external_id=?",new Object[]{id});
        }
    }
    private JSONArray download(String suffix) throws Exception {
        HttpURLConnection conn=(HttpURLConnection)new URL("https://gaia.inegi.org.mx/wscatgeo/v2/"+suffix).openConnection();
        conn.setConnectTimeout(12000);conn.setReadTimeout(15000);conn.setInstanceFollowRedirects(false);
        conn.setRequestProperty("User-Agent","GeoPinPro/1.0.10");
        try{
            if(conn.getResponseCode()!=200)throw new IOException("Catálogo no disponible");
            try(InputStream in=conn.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){
                byte[] data=new byte[16384];int n,total=0;while((n=in.read(data))!=-1){total+=n;if(total>24*1024*1024)throw new IOException("Catálogo demasiado grande");out.write(data,0,n);}
                JSONObject value=new JSONObject(out.toString(StandardCharsets.UTF_8.name()));JSONArray rows=value.optJSONArray("datos");
                if(rows==null)throw new IOException("Respuesta sin catálogo");return rows;
            }
        }finally{conn.disconnect();}
    }
    @Override public synchronized void close(){if(db!=null){db.close();db=null;}}
}
