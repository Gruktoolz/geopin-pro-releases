package mx.coberturapunto;

import android.content.Context;
import android.content.SharedPreferences;
import android.location.Address;
import java.text.Normalizer;
import java.util.*;
import org.json.*;

/** A bounded local preference: city-size regions and eight chosen addresses. */
final class SearchMemory {
    static final class Area {
        final double lat,lon;final String city;
        Area(double lat,double lon,String city){this.lat=lat;this.lon=lon;this.city=city;}
        String key(){return lat+":"+lon;}
        double south(){return Math.max(-90,lat-.4);}double north(){return Math.min(90,lat+.4);}
        double west(){return Math.max(-180,lon-.5);}double east(){return Math.min(180,lon+.5);}
    }
    private final SharedPreferences prefs;
    private final List<Address> recent=new ArrayList<>();
    private final List<JSONObject> areas=new ArrayList<>();
    SearchMemory(Context context){
        prefs=context.getSharedPreferences("search-area",Context.MODE_PRIVATE);
        try{
            JSONArray list=new JSONArray(prefs.getString("recent","[]"));
            for(int i=0;i<Math.min(8,list.length());i++){
                JSONObject row=list.getJSONObject(i);Address a=new Address(new Locale("es","MX"));
                a.setAddressLine(0,row.getString("label"));a.setLatitude(row.getDouble("lat"));a.setLongitude(row.getDouble("lon"));a.setLocality(row.optString("city",""));recent.add(a);
            }
            JSONArray stored=new JSONArray(prefs.getString("areas","[]"));for(int i=0;i<Math.min(6,stored.length());i++)areas.add(stored.getJSONObject(i));
        }catch(JSONException ignored){}
    }
    Area area(){
        JSONObject best=null;
        for(JSONObject row:areas)if(best==null || row.optInt("count")>best.optInt("count") || (row.optInt("count")==best.optInt("count") && row.optLong("time")>best.optLong("time")))best=row;
        if(best!=null)return new Area(best.optDouble("lat"),best.optDouble("lon"),best.optString("city",""));
        return null;
    }
    boolean hasLearnedArea(){return !areas.isEmpty();}
    void remember(Address address){
        if(!address.hasLatitude() || !address.hasLongitude() || address.getAddressLine(0)==null)return;
        recent.removeIf(a->a.getAddressLine(0).equals(address.getAddressLine(0)));recent.add(0,address);if(recent.size()>8)recent.remove(8);
        double lat=Math.round(address.getLatitude()*2)/2d,lon=Math.round(address.getLongitude()*2)/2d;
        String city=address.getLocality()==null?"":address.getLocality();
        try{
            JSONObject match=null;
            for(JSONObject row:areas)if(row.optDouble("lat")==lat && row.optDouble("lon")==lon){match=row;break;}
            if(match==null){match=new JSONObject().put("lat",lat).put("lon",lon).put("count",0);areas.add(match);}
            match.put("count",Math.min(100,match.optInt("count")+1)).put("time",System.currentTimeMillis());if(!city.isEmpty())match.put("city",city);
            areas.sort((a,b)->{int count=Integer.compare(b.optInt("count"),a.optInt("count"));return count!=0?count:Long.compare(b.optLong("time"),a.optLong("time"));});
            if(areas.size()>6)areas.remove(6);
            JSONArray list=new JSONArray();for(Address a:recent)list.put(new JSONObject().put("label",a.getAddressLine(0)).put("lat",a.getLatitude()).put("lon",a.getLongitude()).put("city",a.getLocality()==null?"":a.getLocality()));
            prefs.edit().putString("recent",list.toString()).putString("areas",new JSONArray(areas).toString()).apply();
        }catch(JSONException ignored){}
    }
    List<Address> matching(String text){List<Address> list=new ArrayList<>();for(Address a:recent)if(matches(a.getAddressLine(0),text))list.add(a);return list;}
    static String normalize(String text){return Normalizer.normalize(text,Normalizer.Form.NFD).replaceAll("\\p{M}+","").toLowerCase(Locale.ROOT).trim();}
    static boolean matches(String label,String text){String hay=normalize(label);for(String word:normalize(text).split("\\s+"))if(!hay.contains(word))return false;return true;}
}
