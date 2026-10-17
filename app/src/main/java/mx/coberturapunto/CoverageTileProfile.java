package mx.coberturapunto;
/** Compatibility profile for the registered raster overlay; no network or UI logic. */
final class CoverageTileProfile implements RasterTileProfile {
 public String cacheNamespace(){return "";}
 public boolean primary(String url){return url.contains("layer=cobertura_fibra_cob");}
 public boolean secondary(String url){return url.contains("layer=cobertura_hfc");}
 public boolean base(String url){return url.contains("google.com/vt") || url.contains("google.com/maps/vt");}
 public boolean alternate(String url){return url.contains("inegi.org.mx");}
 public String overlay(int z,int col,int row){return EndpointCatalog.overlay_tiles()+
  "layer=cobertura_fibra_cob&style=&tilematrixset=EPSG:900913&Service=WMTS"+
  "&Request=GetTile&Version=1.0.0&Format=image/png&TileMatrix=EPSG:900913:"+z+"&TileCol="+col+"&TileRow="+row;}
 public String baseTile(int z,int col,int row){return EndpointCatalog.base_tiles()+"&x="+col+"&y="+row+"&z="+z;}
}
