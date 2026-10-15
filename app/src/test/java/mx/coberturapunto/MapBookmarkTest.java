package mx.coberturapunto;
import org.junit.Test;
import java.net.URI;
import static org.junit.Assert.*;
public class MapBookmarkTest {
 @Test public void keepsCanonicalPathAndQuery(){String origin=EndpointCatalog.map_origin();
  assertEquals(origin+"/",MapBookmark.normalize("  "+origin+"  "));
  assertEquals(origin+"/mapa?x=1#punto",MapBookmark.normalize(origin.toUpperCase()+":443/mapa?x=1#punto"));}
 @Test public void rejectsUnsafeLinks(){String host=URI.create(EndpointCatalog.map_origin()).getHost();
  for(String value:new String[]{"","http://"+host,"https://"+host+".example.com/","https://user:pass@"+host,"https://"+host+":8443/","javascript:alert(1)","file:///tmp/mapa"}){
   try{MapBookmark.normalize(value);fail("Unsafe origin accepted");}catch(IllegalArgumentException expected){}}}
 @Test public void secondProviderHasIndependentCapabilities(){MapProvider p=ProviderRegistry.DEFAULT.resolve(URI.create(EndpointCatalog.leaflet_origin()+"/examples/quick-start/example.html"));
  assertFalse(p.coverageEnabled());assertEquals("leaflet-adapter.js",p.adapterAsset());assertFalse(p.accepts(URI.create(EndpointCatalog.map_origin())));}
 @Test public void registryRejectsDuplicateOrigins(){ProviderRegistry r=new ProviderRegistry();
  r.register(new ConfiguredMapProvider("one","https://map.example","leaflet-adapter.js",null));
  try{r.register(new ConfiguredMapProvider("two","https://map.example","leaflet-adapter.js",null));fail();}catch(IllegalArgumentException expected){} }
}
