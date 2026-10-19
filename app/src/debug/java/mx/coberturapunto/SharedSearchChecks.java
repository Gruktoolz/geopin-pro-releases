package mx.coberturapunto;
import android.content.*;
import android.location.Address;
import java.util.*;
final class SharedSearchChecks {
 private static void check(boolean ok,String msg){if(!ok)throw new AssertionError(msg);}
 static String run(Context base) throws Exception {
  Context context=new ContextWrapper(base){@Override public SharedPreferences getSharedPreferences(String name,int mode){return base.getSharedPreferences("search-check-"+name,mode);}};
  SearchCatalog catalog=new SearchCatalog(context);
  long started=System.currentTimeMillis();catalog.prepare();long prepared=System.currentTimeMillis()-started;
  SearchMemory.Area tj=new SearchMemory.Area(32.53246,-117.01891,"Tijuana");
  StringBuilder times=new StringBuilder();
  for(String query:new String[]{"A","V","S","7","San","Valle","Fontana","Garcia"}){
   long t=System.currentTimeMillis();List<Address> rows=catalog.search(query,tj);
   check(!rows.isEmpty(),"Offline suggestions for "+query);
   times.append(query).append('=').append(System.currentTimeMillis()-t).append("ms,");
   for(Address a:rows)check(a.getAddressLine(0)!=null,"Catalog labels exist");
  }
  check(catalog.search("Valle Verde",tj).stream().anyMatch(a->a.getAddressLine(0).contains("Tijuana")),"Municipal colony catalog included");
  check(catalog.search("Villa Fontana",tj).stream().anyMatch(a->!a.hasLatitude()),"Tabular colonies never use invented coordinates");
  check(catalog.search("De la Plata",tj).stream().anyMatch(Address::hasLatitude),"Street has real segment point");
  check(catalog.search("San",new SearchMemory.Area(19.43,-99.13,"Ciudad de México")).stream().anyMatch(a->a.hasLatitude()&&Math.abs(a.getLatitude()-19.43)<1),"New zone changes offline priority");
  check(catalog.search("zzzznotaplace",tj).isEmpty(),"No invented matches");
  check(catalog.nearest(32.53,-117.01).municipality().equals("02004"),"FIPS to INEGI municipal mapping");
  SearchMemory memory=new SearchMemory(context);memory.setActiveArea(32.5324612345,-117.018912345,"Tijuana");
  memory.setActiveArea(19.432612345,-99.133212345,"");
  SearchMemory reloaded=new SearchMemory(context);check(reloaded.area().city.isEmpty(),"No stale city after manual point");
  check(Math.abs(reloaded.area().lat-19.432612345)<1e-10,"Work zone exact coordinates survive recreation");
  SharedPreferences pref=context.getSharedPreferences("work_zone_context",Context.MODE_PRIVATE);
  pref.edit().putFloat("last_lat",32.5f).putFloat("last_lon",-117f).commit();
  check(LastContextStore.getLastLocation(context).lat()==32.5,"Old float work zones migrate");
  LastContextStore.save(context,19.432612345,-99.133212345,"",16);
  check(Math.abs(LastContextStore.getLastLocation(context).lat()-19.432612345)<1e-10,"Exact last-point precision");
  check(LastContextStore.getLastZoom(context)==16,"Real viewport zoom persists");
  catalog.close();
  return "PASS:offline-national-first-character-numbers-accents-colonies-streets-zone-migration; prepare="+prepared+"ms; "+times;
 }
}
