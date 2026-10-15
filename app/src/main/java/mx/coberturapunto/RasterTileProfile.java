package mx.coberturapunto;
interface RasterTileProfile {
 boolean primary(String url); boolean secondary(String url); boolean base(String url); boolean alternate(String url);
 String overlay(int z,int col,int row); String baseTile(int z,int col,int row);
 /** Empty only for the original profile to retain existing private cache compatibility. */
 String cacheNamespace();
}
