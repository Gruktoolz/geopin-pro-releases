package mx.coberturapunto;
import android.net.Uri;
import android.webkit.WebResourceResponse;
import java.net.URI;
/** Per-view provider boundary. Coverage processing is opt-in and never applied to unrelated maps. */
final class MapEngine {
 final MapProvider provider;
 private final RasterVisualDetector evaluator=new RasterVisualDetector();
 void startQuery(String id){evaluator.startQuery(id);}
 MapEngine(String url){provider=ProviderRegistry.DEFAULT.resolve(URI.create(url));}
 boolean trusted(Uri uri){try{return provider.accepts(URI.create(uri.toString()));}catch(Exception e){return false;}}
 WebResourceResponse intercept(Uri uri){return provider.coverageEnabled()?RasterVisualDetector.shouldIntercept(uri,provider.tileProfile()):null;}
 RasterVisualDetector.VisualEvaluation evaluate(double lat,double lon,String id){
  return provider.coverageEnabled()?evaluator.evaluate(lat,lon,id,provider.tileProfile()):
   new RasterVisualDetector.VisualEvaluation("unknown","provider_without_coverage",0,0,false);
 }
 void prefetch(double lat,double lon){if(provider.coverageEnabled())RasterVisualDetector.prefetchNeighbors(lat,lon,provider.tileProfile());}
}
