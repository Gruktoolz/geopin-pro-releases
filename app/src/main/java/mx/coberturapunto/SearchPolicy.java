package mx.coberturapunto;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Shared matching rules, independent of UI and Android services. */
final class SearchPolicy {
    private SearchPolicy() {}
    record SearchIntent(String street,String settlement) {
        boolean includesBoth(){return !street.isEmpty()&&!settlement.isEmpty();}
    }
    private static final Pattern INTENT_MARKER=Pattern.compile("\\b(calle|calles|avenida|avda|av|boulevard|blvd|calzada|cerrada|circuito|andador|privada|prolongacion|paseo|carretera|camino|retorno|colonia|colonias|col|fraccionamiento|fracc|barrio|unidad|residencial)\\b");
    static String normalize(String text) {
        if(text==null)return "";
        return Normalizer.normalize(text,Normalizer.Form.NFD).replaceAll("\\p{M}+","")
                .toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+"," ").trim();
    }
    static SearchIntent intent(String text) {
        String value=normalize(text),street="",settlement="";
        Matcher matcher=INTENT_MARKER.matcher(value);
        java.util.List<String> markers=new java.util.ArrayList<>();
        java.util.List<Integer> starts=new java.util.ArrayList<>(),ends=new java.util.ArrayList<>();
        while(matcher.find()){markers.add(matcher.group());starts.add(matcher.start());ends.add(matcher.end());}
        for(int i=0;i<markers.size();i++){
            int end=i+1<markers.size()?starts.get(i+1):value.length();
            String part=value.substring(ends.get(i),end).trim();
            String marker=markers.get(i);
            if(isStreetMarker(marker))street=part;else settlement=part;
        }
        return new SearchIntent(street,settlement);
    }
    private static boolean isStreetMarker(String marker){
        return marker.equals("calle")||marker.equals("calles")||marker.equals("avenida")||marker.equals("avda")||marker.equals("av")||marker.equals("boulevard")||marker.equals("blvd")||marker.equals("calzada")||marker.equals("cerrada")||marker.equals("circuito")||marker.equals("andador")||marker.equals("privada")||marker.equals("prolongacion")||marker.equals("paseo")||marker.equals("carretera")||marker.equals("camino")||marker.equals("retorno");
    }
    static boolean eligible(String text) {
        if(text==null || text.length()>180 || text.contains(":") || text.contains("/") || text.contains("\n"))return false;
        return !normalize(text).isEmpty();
    }
    static String prefixQuery(String text) {
        String normalized=normalize(text);
        if(normalized.isEmpty())return "";
        StringBuilder query=new StringBuilder();
        for(String token:normalized.split(" +")){if(query.length()>0)query.append(' ');query.append(token).append('*');}
        return query.toString();
    }
    static boolean matches(String label,String query) {
        String value=normalize(label);
        for(String token:normalize(query).split(" +"))if(!value.contains(token))return false;
        return true;
    }
    static double distance(double lat,double lon,double a,double b) {
        double x=(lon-b)*Math.cos(Math.toRadians((lat+a)/2));
        double y=lat-a;return x*x+y*y;
    }
}
