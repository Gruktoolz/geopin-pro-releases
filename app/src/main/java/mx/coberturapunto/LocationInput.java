package mx.coberturapunto;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class LocationInput {
    record Point(double lat, double lon) {
        Point {
            if (!Double.isFinite(lat) || !Double.isFinite(lon) || Math.abs(lat)>90 || Math.abs(lon)>180)
                throw new IllegalArgumentException("Coordenadas fuera de rango");
        }
    }
    private static final String N = "(-?\\d+(?:\\.\\d+)?)";
    private static final Pattern PAIR = Pattern.compile("^\\s*"+N+"\\s*[,;]\\s*"+N+"\\s*$");
    private static final Pattern URL_IN_TEXT = Pattern.compile("https?://[^\\s<>]+", Pattern.CASE_INSENSITIVE);
    static URI link(String input) {
        Matcher m = URL_IN_TEXT.matcher(input);
        if (!m.find()) return null;
        try { URI u = URI.create(m.group()); if("http".equalsIgnoreCase(u.getScheme()))u=URI.create("https"+u.toString().substring(4));return allowed(u) ? u : null; }
        catch (IllegalArgumentException e) { return null; }
    }
    static boolean allowed(URI uri) {
        String host = uri.getHost();
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getUserInfo()!=null || (uri.getPort()!=-1 && uri.getPort()!=443)) return false;
        return host!=null && (host.equalsIgnoreCase("maps.app.goo.gl") || host.equalsIgnoreCase("goo.gl") ||
            host.equalsIgnoreCase("google.com") || host.equalsIgnoreCase("www.google.com") ||
            host.equalsIgnoreCase("maps.google.com") || host.equalsIgnoreCase("www.google.com.mx") || host.equalsIgnoreCase("maps.google.com.mx"));
    }
    static boolean isCoordinate(String input) {
        if (input == null) return false;
        String trimmed = input.trim();
        if (trimmed.isEmpty() || link(trimmed) != null) return false;
        try {
            return parse(trimmed) != null;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
    static Point parse(String input) {
        Matcher plain = PAIR.matcher(input);
        if (plain.matches()) return new Point(Double.parseDouble(plain.group(1)),Double.parseDouble(plain.group(2)));
        if (input.startsWith("geo:") || input.startsWith("google.navigation:")) {
            String geo = input.substring(input.indexOf(':')+1);
            String query=geoQuery(input);
            if (query!=null) {
                String value = query.replaceFirst("\\(.*$", "");
                Matcher p = PAIR.matcher(value);
                if(p.matches()) return new Point(Double.parseDouble(p.group(1)),Double.parseDouble(p.group(2)));
                return null; // geo:0,0?q=address uses 0,0 as a placeholder, never as the destination.
            }
            if(input.startsWith("google.navigation:"))return null;
            return parse(geo.split("[?;]",2)[0]);
        }
        URI uri = link(input);
        if (uri == null) return null;
        String raw = decode(uri.toString());
        // Place coordinates take precedence over the viewport's @latitude,longitude.
        Matcher place = Pattern.compile("!3d"+N+"!4d"+N).matcher(raw);
        if (place.find()) return new Point(Double.parseDouble(place.group(1)),Double.parseDouble(place.group(2)));
        if (uri.getRawQuery()!=null) for (String parameter : uri.getRawQuery().split("&")) {
            String[] parts = parameter.split("=",2);
            if(parts.length!=2) continue;
            if (parts[0].equals("q") || parts[0].equals("query") || parts[0].equals("ll") || parts[0].equals("destination")) {
                Matcher p = PAIR.matcher(decode(parts[1]));
                if(p.matches()) return new Point(Double.parseDouble(p.group(1)),Double.parseDouble(p.group(2)));
            }
        }
        // Do not silently treat a map viewport or route origin as the shared destination.
        return null;
    }
    static String geoAddress(String input){
        if(!input.startsWith("geo:") && !input.startsWith("google.navigation:"))return null;
        String query=geoQuery(input);
        if(query==null || query.trim().isEmpty() || PAIR.matcher(query.replaceFirst("\\(.*$", "")).matches())return null;
        return query;
    }
    private static String geoQuery(String input){
        String body=input.substring(input.indexOf(':')+1);
        int start=body.indexOf('?');
        if(start>=0)body=body.substring(start+1);
        else if(!input.startsWith("google.navigation:"))return null;
        for(String part:body.split("&")){String[] pair=part.split("=",2);if(pair.length==2 && pair[0].equals("q"))return decode(pair[1]);}
        return null;
    }
    static boolean isMexico(android.location.Address a) {
        if(a==null)return false;
        String cc=a.getCountryCode();
        if(cc!=null && !cc.trim().isEmpty())return "MX".equalsIgnoreCase(cc.trim());
        String cn=a.getCountryName();
        if(cn!=null && !cn.trim().isEmpty()){
            String norm=java.text.Normalizer.normalize(cn,java.text.Normalizer.Form.NFD)
                    .replaceAll("\\p{M}+","").toLowerCase(java.util.Locale.ROOT);
            if(norm.contains("mexico"))return true;
        }
        if(a.hasLatitude() && a.hasLongitude()){
            double lat=a.getLatitude(),lon=a.getLongitude();
            return lat>=14.53 && lat<=32.72 && lon>=-118.40 && lon<=-86.70;
        }
        return false;
    }
    private static String decode(String text) { try { return URLDecoder.decode(text, "UTF-8"); } catch(java.io.UnsupportedEncodingException impossible) { throw new AssertionError(impossible); } }
}

