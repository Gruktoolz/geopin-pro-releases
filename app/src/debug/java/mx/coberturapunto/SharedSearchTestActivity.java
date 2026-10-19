package mx.coberturapunto;
import android.app.Activity;
import android.content.*;
import android.os.*;
import android.webkit.*;
import android.widget.*;
import android.location.Address;
import androidx.webkit.WebViewCompat;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.function.BooleanSupplier;
import org.json.*;
/** Actual EditText, SQLite catalog, WebView and jQuery UI integration. Source HTTP is a local fixture. */
public final class SharedSearchTestActivity extends Activity {
 public static volatile String result="pending";
 private final Handler handler=new Handler(Looper.getMainLooper());
 private AddressSuggestions suggestions;private EditText input;private LinearLayout panel;private WebView web;
 private JSONArray rows=new JSONArray();private String latestText="";private int latestId,selectedCount;private Address chosen;
 private String asset(String name)throws Exception{try(java.io.InputStream in=getAssets().open(name);java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream()){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)out.write(b,0,n);return out.toString(StandardCharsets.UTF_8.name());}}
 private void script(String call){web.evaluateJavascript("window.__searchMirror && window.__searchMirror."+call,null);}
 private void check(boolean value,String message){if(!value)throw new AssertionError(message);}
 private void fail(Throwable e){result="FAIL:shared-search:"+e.getMessage();android.util.Log.e("SharedSearchTest",result,e);}
 private void waitFor(BooleanSupplier predicate,Runnable then,int attempts){
  try{if(predicate.getAsBoolean()){then.run();return;}if(attempts==0)throw new AssertionError("timeout for "+latestText+" rows="+rows.length());handler.postDelayed(()->waitFor(predicate,then,attempts-1),60);}catch(Throwable e){fail(e);}
 }
 @Override public void onCreate(Bundle state){super.onCreate(state);result="pending";
  LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);setContentView(root);
  input=new EditText(this);input.setSingleLine();root.addView(input);
  panel=new LinearLayout(this);root.addView(panel);
  Context memoryContext=new ContextWrapper(this){@Override public SharedPreferences getSharedPreferences(String name,int mode){return SharedSearchTestActivity.this.getSharedPreferences("ui-check-"+name,mode);}};
  SearchMemory memory=new SearchMemory(memoryContext);memory.setActiveArea(32.53246,-117.01891,"Tijuana");
  suggestions=new AddressSuggestions(this,input,panel,0xffeeeeee,0xff111111,0xff005f88,memory,a->{selectedCount++;chosen=a;});
  web=new WebView(this);root.addView(web,new LinearLayout.LayoutParams(-1,0,1));WebViewOptimizations.apply(web.getSettings());
  String origin="https://search-fixture.example";
  WebViewCompat.addWebMessageListener(web,"PointChannel",Collections.singleton(origin),(view,message,url,main,reply)->{
   if(!main)return;try{JSONObject row=new JSONObject(message.getData());switch(row.optString("type")){
    case "search-edit":suggestions.fromWeb(row.optString("text"));break;
    case "search-results":suggestions.receiveWebsite(row.optInt("generation"),row.optJSONArray("rows"));break;
    case "search-select":suggestions.chooseWeb(row.optInt("generation"),row.optInt("row"));break;
    case "search-blur":suggestions.webBlurred();break;
   }}catch(Exception e){fail(e);}
  });
  suggestions.setMirror(new AddressSuggestions.Mirror(){
   public void request(String text,int id,boolean fromWeb){latestText=text;latestId=id;script("request("+JSONObject.quote(text)+","+id+","+fromWeb+")");}
   public void results(String text,int id,JSONArray values){rows=values;script("results("+JSONObject.quote(text)+","+id+","+values+")");}
   public void selected(String label){script("selected("+JSONObject.quote(label)+")");}
   public void dismiss(){script("dismiss()");}
  });
  web.setWebViewClient(new WebViewClient(){@Override public void onPageFinished(WebView view,String url){try{
   web.evaluateJavascript(asset("search-bridge.js")+";window.__searchMirror.ready()",ready->{if(!"true".equals(ready)){fail(new AssertionError("bridge ready"));return;}nativePhase();});
  }catch(Exception e){fail(e);}}});
  try{
   String html="<!doctype html><meta name='viewport' content='width=device-width, initial-scale=1'><style>input{font-size:20px;width:90%}ul{background:white;color:black}small{font-size:11px}.ui-helper-hidden-accessible{display:none}</style><input id='domGeo' placeholder='Busca tu colonia'><script>"+asset("jquery-test.js")+"</script><script>"+asset("jquery-ui-test.js")+"</script><script>window.__mapProvider={origin:'"+origin+"',blurElement:'domGeo'};window.fixtureRequests=0;$('#domGeo').autocomplete({minLength:3,source:function(request,response){window.fixtureRequests++;setTimeout(function(){response([{label:'Villa de prueba, Tijuana',locacion:'32.532,-117.019'}]);},30);}});</script>";
   web.loadDataWithBaseURL(origin+"/",html,"text/html","UTF-8",null);
  }catch(Exception e){fail(e);}
 }
 private void nativePhase(){
  input.requestFocus();input.setText("A");
  waitFor(()->latestText.equals("A")&&rows.length()>=5,()->{
   check(panel.getVisibility()==android.view.View.VISIBLE,"native panel opens from A");
   web.evaluateJavascript("document.getElementById('domGeo').value",value->{try{check("\"A\"".equals(value),"native text mirrored");webPhase();}catch(Throwable e){fail(e);}});
  },160);
 }
 private void webPhase(){
  web.requestFocus();web.evaluateJavascript("var el=document.getElementById('domGeo');el.focus();el.value='V';jQuery(el).autocomplete('search','V');",v->{});
  waitFor(()->latestText.equals("V")&&rows.length()>=5,()->{
   check(input.getText().toString().equals("V"),"web text mirrored to native");check(panel.getVisibility()==android.view.View.GONE,"one suggestion surface per focus");
   web.evaluateJavascript("(function(){var i=jQuery('#domGeo').autocomplete('instance');return JSON.stringify({pending:i.pending,count:i.menu.element.find('li').length,remote:fixtureRequests});})()",value->{try{
    JSONObject status=new JSONObject(new JSONTokener(value).nextValue().toString());
    check(status.getInt("pending")==0,"jQuery response balance");check(status.getInt("count")>=5,"actual jQuery menu from first letter");check(status.getInt("remote")==0,"one letter never calls web service");
    remotePhase();
   }catch(Throwable e){fail(e);}});
  },160);
 }
 private void remotePhase(){
  int stale=latestId;
  web.evaluateJavascript("el.value='Villa';jQuery(el).autocomplete('search','Villa');",v->{});
  waitFor(()->latestText.equals("Villa")&&rows.toString().contains("Villa de prueba"),()->{
   try{suggestions.receiveWebsite(stale,new JSONArray("[{\"label\":\"Stale injected result\",\"lat\":32.5,\"lon\":-117}]"));check(!rows.toString().contains("Stale injected"),"old results ignored");}catch(Exception e){fail(e);return;}
   web.evaluateJavascript("var i=jQuery('#domGeo').autocomplete('instance');var row=i.menu.element.find('li').first().data('ui-autocomplete-item');i._trigger('select',null,{item:row});",v->{});
   waitFor(()->selectedCount==1,()->{
    check(chosen!=null,"shared selection reaches native handler");check(!suggestions.visible(),"selection closes both menus");
    suggestions.dismiss();handler.postDelayed(()->{
     try{check(selectedCount==1,"no duplicate selection");result="PASS:real-EditText-SQLite-WebView-jQuery-first-letter-native-web-mirror-merge-selection-stale-response";}catch(Throwable e){fail(e);}
    },500);
   },60);
  },160);
 }
 @Override public void onDestroy(){if(suggestions!=null)suggestions.close();if(web!=null)web.destroy();handler.removeCallbacksAndMessages(null);super.onDestroy();}
}
