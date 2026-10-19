package mx.coberturapunto;
import android.app.*;
import android.content.*;
import android.webkit.WebView;
import android.widget.Button;
import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
/** Exercises production Fix center and successful PixelCopy -> ScreenshotStore -> work-zone commit. */
final class MainWorkZoneChecks {
 private static Object field(Object owner,String name)throws Exception{Field f=MainActivity.class.getDeclaredField(name);f.setAccessible(true);return f.get(owner);}
 private static void ready(MainActivity activity)throws Exception{Field f=MainActivity.class.getDeclaredField("ready");f.setAccessible(true);f.setBoolean(activity,true);((Button)field(activity,"center")).setEnabled(true);}
 private static String evaluate(Instrumentation runner,WebView web,String script)throws Exception{
  CountDownLatch latch=new CountDownLatch(1);String[] result={""};
  runner.runOnMainSync(()->web.evaluateJavascript(script,value->{result[0]=value;latch.countDown();}));
  check(latch.await(5,TimeUnit.SECONDS),"WebView callback");return result[0];
 }
 private static void check(boolean ok,String msg){if(!ok)throw new AssertionError(msg);}
 static String run(Instrumentation runner)throws Exception{
  Context context=runner.getTargetContext();String original=MapBookmark.get(context);
  MapBookmark.save(context,"https://main-fixture.example/");
  MainActivity activity=(MainActivity)runner.startActivitySync(new Intent(context,MainActivity.class).putExtra("self_test",true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
  try{
   WebView loaded=(WebView)field(activity,"web");
   long loadDeadline=System.currentTimeMillis()+10000;
   while(System.currentTimeMillis()<loadDeadline && !"true".equals(evaluate(runner,loaded,"location.origin==='https://main-fixture.example' && document.readyState==='complete'")))Thread.sleep(100);
   CountDownLatch prepared=new CountDownLatch(1);
   runner.runOnMainSync(()->{try{WebView web=(WebView)field(activity,"web");web.evaluateJavascript("window.__coverageAdapter={ready:()=>true,center:()=>{window.PointChannel.postMessage(JSON.stringify({type:'point',source:'center',lat:20.5,lon:-103.2}));return true;},query:()=> 'requested',snapshot:()=>({lat:19.432612345,lon:-99.133212345,zoom:14})};true",value->{try{ready(activity);prepared.countDown();}catch(Exception e){throw new RuntimeException(e);}});}catch(Exception e){throw new RuntimeException(e);}});
   check(prepared.await(10,TimeUnit.SECONDS),"Main fixture loads");
   runner.runOnMainSync(()->{try{((Button)field(activity,"center")).performClick();}catch(Exception e){throw new RuntimeException(e);}});
   long deadline=System.currentTimeMillis()+10000;
   while(System.currentTimeMillis()<deadline){LocationInput.Point p=LastContextStore.getLastLocation(context);if(p!=null&&p.lat()==20.5)break;Thread.sleep(50);}
   check(LastContextStore.getLastLocation(context).lat()==20.5,"Fix center persists zone");
   check(new SearchMemory(context).area().lat==20.5,"Fix center changes recommendation context");
   runner.runOnMainSync(()->{try{((Button)field(activity,"captureButton")).performClick();}catch(Exception e){throw new RuntimeException(e);}});
   deadline=System.currentTimeMillis()+15000;
   while(System.currentTimeMillis()<deadline){LocationInput.Point p=LastContextStore.getLastLocation(context);if(p!=null&&Math.abs(p.lat()-19.432612345)<1e-10)break;Thread.sleep(70);}
   LocationInput.Point point=LastContextStore.getLastLocation(context);
   check(Math.abs(point.lat()-19.432612345)<1e-10,"Successful capture commits actual snapshot center");
   check(LastContextStore.getLastZoom(context)==14,"Capture keeps snapshot zoom");
   check(Math.abs(new SearchMemory(context).area().lon+99.133212345)<1e-10,"Capture and suggestions share exact zone");
   CountDownLatch checked=new CountDownLatch(1);final boolean[] matches={false};
   runner.runOnMainSync(()->{try{LocationInput.Point pending=(LocationInput.Point)field(activity,"pending");matches[0]=Math.abs(pending.lat()-19.432612345)<1e-10;checked.countDown();}catch(Exception e){throw new RuntimeException(e);}});
   check(checked.await(2,TimeUnit.SECONDS)&&matches[0],"Capture updates recreation point, not an old search");
   return "PASS:production-fix-center-and-successful-PixelCopy-capture-share-work-zone-exact-point-real-zoom";
  }finally{runner.runOnMainSync(activity::finish);if(!original.isEmpty())MapBookmark.save(context,original);}
 }
}
