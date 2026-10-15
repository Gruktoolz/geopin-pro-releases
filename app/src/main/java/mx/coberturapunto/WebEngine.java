package mx.coberturapunto;
import android.content.Context;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;
/** Per-WebView script composition. Only the selected origin receives the native bridge. */
final class WebEngine {
 private final Context context; private final MapProvider provider;
 WebEngine(Context context,MapProvider provider){this.context=context;this.provider=provider;}
 String documentStart(){
  String config="Object.defineProperty(window,'__mapProvider',{value:Object.freeze({origin:"
   +JSONObject.quote(provider.origin())+",blurElement:"+JSONObject.quote(provider.blurElement())+",locateControl:"+JSONObject.quote(provider.locateControl())+"}),configurable:false});\n";
  return "if(location.origin==="+JSONObject.quote(provider.origin())+"){"+config+asset("virtual-location.js")+";\n"+asset("webview-optimizations.js")+"}";
 }
 String bootstrap(){return asset("viewport-stability.js")+";\n"+asset("location-feedback.js")+";\n"+asset(provider.adapterAsset())+";!!(window.__coverageAdapter && window.__coverageAdapter.ready());";}
 private String asset(String name){
  try(java.io.InputStream in=context.getAssets().open(name);java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream()){
   byte[] buffer=new byte[4096];int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);return out.toString(StandardCharsets.UTF_8.name());
  }catch(Exception e){throw new IllegalStateException("No se pudo cargar el motor web",e);}
 }
}
