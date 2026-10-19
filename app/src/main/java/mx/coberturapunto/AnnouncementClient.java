package mx.coberturapunto;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Retrieves the single current in-app message and remembers only the dismissal locally. */
final class AnnouncementClient {
 private static final ExecutorService IO=Executors.newSingleThreadExecutor();
 private AnnouncementClient() {}

 static void check(Activity activity) {
  IO.execute(() -> {
   JSONObject announcement=read();
   if(announcement==null || activity.isFinishing() || activity.isDestroyed())return;
   String id=announcement.optString("id","").trim();
   String message=announcement.optString("message","").trim();
   if(id.isEmpty() || message.isEmpty() || message.length()>500)return;
   if(id.equals(activity.getSharedPreferences("announcements",Context.MODE_PRIVATE).getString("dismissed_id","")))return;
   new Handler(Looper.getMainLooper()).post(() -> show(activity,id,message));
  });
 }

 private static JSONObject read() {
  HttpURLConnection connection=null;
  try {
   URL url=new URL(EndpointCatalog.telemetry()+"?action=announcement");
   connection=(HttpURLConnection)url.openConnection();
   connection.setRequestMethod("GET");connection.setConnectTimeout(7000);connection.setReadTimeout(9000);connection.setInstanceFollowRedirects(true);
   connection.setRequestProperty("Accept","application/json");
   if(connection.getResponseCode()!=200)return null;
   try(InputStream in=connection.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){
    byte[] buffer=new byte[2048];int n;while((n=in.read(buffer))!=-1){out.write(buffer,0,n);if(out.size()>65536)return null;}
    JSONObject root=new JSONObject(out.toString(StandardCharsets.UTF_8));
    return root.optBoolean("ok")?root.optJSONObject("announcement"):null;
   }
  }catch(Exception ignored){return null;}
  finally{if(connection!=null)connection.disconnect();}
 }

 private static void show(Activity activity,String id,String message) {
  if(activity.isFinishing()||activity.isDestroyed())return;
  Runnable dismissed=()->activity.getSharedPreferences("announcements",Context.MODE_PRIVATE).edit().putString("dismissed_id",id).apply();
  AlertDialog dialog=new AlertDialog.Builder(activity)
   .setTitle("Mensaje de GeoPin Pro")
   .setMessage(message)
   .setPositiveButton("Cerrar",(d,w)->dismissed.run())
   .create();
  dialog.setCancelable(true);
  dialog.setOnCancelListener(d->dismissed.run());
  dialog.show();
 }
}
