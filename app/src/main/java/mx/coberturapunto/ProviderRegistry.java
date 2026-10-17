package mx.coberturapunto;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
/** Explicit origin allowlist; registration never broadens another provider's bridge. */
final class ProviderRegistry {
 private final Map<String,MapProvider> providers=new LinkedHashMap<>();
 static final ProviderRegistry DEFAULT=createDefault();
 private static ProviderRegistry createDefault(){
  ProviderRegistry r=new ProviderRegistry();
  r.register(new ConfiguredMapProvider("coverage-v1",EndpointCatalog.map_origin(),"coverage-adapter.js",new CoverageTileProfile(),"domGeo",".leaflet-control-locate a"));
  r.register(new ConfiguredMapProvider("leaflet-v1",EndpointCatalog.leaflet_origin(),"map-adapter.js",null));
  return r;
 }
 synchronized void register(MapProvider provider){
  URI u=URI.create(provider.origin());
  if(!provider.accepts(u) || !provider.origin().equals("https://"+u.getHost()) || providers.containsKey(provider.id()))
   throw new IllegalArgumentException("Configuración de proveedor inválida");
  for(MapProvider p:providers.values()) {
   if(p.origin().equalsIgnoreCase(provider.origin()))throw new IllegalArgumentException("Origen ya registrado");
   if(p.tileProfile()!=null && provider.tileProfile()!=null && p.tileProfile().cacheNamespace().equals(provider.tileProfile().cacheNamespace()))
    throw new IllegalArgumentException("El proveedor requiere un espacio de caché independiente");
  }
  providers.put(provider.id(),provider);
 }
 synchronized MapProvider resolve(URI uri){
  for(MapProvider provider:providers.values())if(provider.accepts(uri))return provider;
  if(!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost()==null || uri.getHost().isBlank()
     || uri.getRawUserInfo()!=null || (uri.getPort()!=-1 && uri.getPort()!=443))
   throw new IllegalArgumentException("Introduce un enlace HTTPS válido para el mapa.");
  String origin="https://"+uri.getHost().toLowerCase(java.util.Locale.ROOT);
  return new ConfiguredMapProvider("generic-web",origin,"map-adapter.js",null);
 }
}
final class ConfiguredMapProvider implements MapProvider {
 private final String id,origin,asset; private final RasterTileProfile tiles; private final String blur,locate;
 ConfiguredMapProvider(String id,String origin,String asset,RasterTileProfile tiles){this(id,origin,asset,tiles,"","");}
 ConfiguredMapProvider(String id,String origin,String asset,RasterTileProfile tiles,String blur,String locate){this.id=id;this.origin=origin;this.asset=asset;this.tiles=tiles;this.blur=blur;this.locate=locate;}
 public String blurElement(){return blur;} public String locateControl(){return locate;}
 public String id(){return id;} public String origin(){return origin;} public String adapterAsset(){return asset;}
 public RasterTileProfile tileProfile(){return tiles;}
}
