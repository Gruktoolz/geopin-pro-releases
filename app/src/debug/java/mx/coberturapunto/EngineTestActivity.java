package mx.coberturapunto;
import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.webkit.WebViewCompat;
import java.util.Collections;
import java.nio.charset.StandardCharsets;
/** Offline WebView integration fixture; never contacts production services. */
public final class EngineTestActivity extends Activity {
 public static volatile String result="pending";
 private WebView web;private WebEngine engine;
 @Override public void onCreate(Bundle state){super.onCreate(state);result="pending";
  String url="https://engine-fixture.example/";MapEngine maps=new MapEngine(url);engine=new WebEngine(this,maps.provider);
  web=new WebView(this);setContentView(web);WebViewOptimizations.apply(web.getSettings());
  WebViewCompat.addDocumentStartJavaScript(web,engine.documentStart(),Collections.singleton(maps.provider.origin()));
  web.setWebViewClient(new WebViewClient(){@Override public void onPageFinished(WebView view,String loaded){attempt(0);}});
  try{String library;try(java.io.InputStream in=getAssets().open("leaflet-test-library.js");java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream()){byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1)out.write(b,0,n);library=out.toString(StandardCharsets.UTF_8.name());}
   String html="<!doctype html><html><head><meta name='viewport' content='width=device-width,initial-scale=1'><style>html,body,#map{height:100%;width:100%;margin:0}</style></head><body><div id='map'></div><script>"+library+"</script><script>(function(){const hiddenMap=L.map('map').setView([19.4,-99.1],12);})();</script></body></html>";
   web.loadDataWithBaseURL(url,html,"text/html","UTF-8",null);
  }catch(Exception e){result="FAIL:fixture";}
 }
 private void attempt(int retry){web.evaluateJavascript(engine.bootstrap(),value->{
  if("true".equals(value)){
   web.evaluateJavascript("(function(){try{const a=window.__coverageAdapter;if(a.query(20.5,-103.2,77)!=='requested')return 'FAIL:query';const m=window.__mapDiscovery.find();const p=m.getCenter();if(Math.abs(p.lat-20.5)>0.00001||Math.abs(p.lng+103.2)>0.00001)return 'FAIL:center';if(m.getZoom()!==18||m.getMinZoom()!==5)return 'FAIL:zoom';if(!a.center())return 'FAIL:fix-center';return 'PASS:real-leaflet-webview';}catch(e){return 'FAIL:'+e.name;}})()",r->result=r);
  }else if(retry<50)new Handler(getMainLooper()).postDelayed(()->attempt(retry+1),100);
  else result="FAIL:adapter-not-ready";
 });}
 @Override public void onDestroy(){if(web!=null)web.destroy();super.onDestroy();}
}
