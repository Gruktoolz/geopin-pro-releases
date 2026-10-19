package mx.coberturapunto;
import android.app.Instrumentation;
import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
public final class EngineInstrumentation extends Instrumentation {
 @Override public void onCreate(Bundle arguments){super.onCreate(arguments);start();}
 @Override public void onStart(){Bundle output=new Bundle();Activity activity=null;
  try{
   String offline = OfflineEngineChecks.run(getTargetContext())+"; "+SharedSearchChecks.run(getTargetContext());
   Intent i=new Intent(getTargetContext(),EngineTestActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
   activity=startActivitySync(i);
   long deadline=System.currentTimeMillis()+20000;
   while("pending".equals(EngineTestActivity.result)&&System.currentTimeMillis()<deadline)Thread.sleep(100);
   String engineResult=EngineTestActivity.result;
   Activity first=activity;runOnMainSync(first::finish);
   activity=startActivitySync(new Intent(getTargetContext(),SharedSearchTestActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
   deadline=System.currentTimeMillis()+45000;
   while("pending".equals(SharedSearchTestActivity.result)&&System.currentTimeMillis()<deadline)Thread.sleep(100);
   String shared=SharedSearchTestActivity.result;
   Activity second=activity;runOnMainSync(second::finish);activity=null;
   String main=MainWorkZoneChecks.run(this);
   output.putString("result",offline+"; "+engineResult+"; "+shared+"; "+main);
   finish(engineResult.contains("PASS:")&&shared.contains("PASS:")?-1:0,output);
  }catch(Throwable e){output.putString("result","FAIL:"+e.getClass().getSimpleName()+":"+e.getMessage());finish(0,output);}
  finally{if(activity!=null){Activity a=activity;runOnMainSync(a::finish);}}
 }
}
