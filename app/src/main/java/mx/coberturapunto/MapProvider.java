package mx.coberturapunto;
import java.net.URI;
interface MapProvider {
 String id(); String origin(); String adapterAsset();
 default String blurElement(){return "";}
 default String locateControl(){return "";}
 RasterTileProfile tileProfile();
 default boolean coverageEnabled(){return tileProfile()!=null;}
 default boolean accepts(URI uri) {
  URI allowed=URI.create(origin());
  return "https".equalsIgnoreCase(uri.getScheme()) && allowed.getHost().equalsIgnoreCase(uri.getHost())
    && uri.getRawUserInfo()==null && (uri.getPort()==-1 || uri.getPort()==443);
 }
}
