package mx.coberturapunto;

import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.widget.*;
import androidx.core.content.FileProvider;
import org.json.*;
import okhttp3.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/** Public metadata only. Signing keys and GitHub credentials never belong in this app. */
final class AppUpdater implements AutoCloseable {
 static final String REPO=EndpointCatalog.releases();
 static final String MANIFEST=EndpointCatalog.updates();
 static final String CERT="2f0b678dd0f4ddb337f3614e337ce88d59c1afe3b3cfc294ede7ff6d4b533d00";
 static final long MAX_APK=200L*1024*1024;
 final Activity activity;final Handler ui=new Handler(Looper.getMainLooper());final ExecutorService io=Executors.newSingleThreadExecutor();
 final OkHttpClient client=new OkHttpClient.Builder().connectTimeout(10,TimeUnit.SECONDS).readTimeout(25,TimeUnit.SECONDS).callTimeout(90,TimeUnit.SECONDS).build();
 final SharedPreferences prefs;final long installed;boolean closed,foreground,busy,downloading,pendingPermission;long lastCheck;Release release;AlertDialog dialog;TextView message;File ready;
 final Runnable ticker=new Runnable(){public void run(){if(!foreground||closed)return;check();ui.postDelayed(this,60000);}};
 static final class Release {
  final long latest,minimum,size;final String name,url,sha,notes;final JSONObject json;
  Release(JSONObject j)throws Exception{
   if(j.optInt("schemaVersion")!=1||!"mx.coberturapunto".equals(j.optString("packageName")))throw new IOException("Formato de actualización inválido");
   latest=integer(j,"latestVersionCode");minimum=integer(j,"minSupportedVersionCode");size=integer(j,"sizeBytes");
   name=j.getString("latestVersionName");url=j.getString("downloadUrl");sha=j.getString("sha256");notes=j.optString("notes","");
   if(latest<1||minimum<1||minimum>latest||size<1||size>MAX_APK||name.isBlank()||name.length()>40||!sha.matches("[a-f0-9]{64}")||notes.length()>2000)throw new IOException("Datos de versión inválidos");
   java.net.URI u=new java.net.URI(url);if(!"https".equals(u.getScheme())||!"github.com".equals(u.getHost())||u.getPort()!=-1||u.getRawUserInfo()!=null||!u.getPath().startsWith((java.net.URI.create(REPO).getPath()+"releases/download/"))||!u.getPath().endsWith(".apk")||u.getQuery()!=null||u.getFragment()!=null)throw new IOException("Origen de actualización inválido");json=j;
  }
  static long integer(JSONObject j,String key)throws Exception{Object v=j.get(key);if(!(v instanceof Number))throw new IOException("Número de versión inválido");double d=((Number)v).doubleValue();long n=((Number)v).longValue();if(!Double.isFinite(d)||d!=n||n<0)throw new IOException("Número de versión inválido");return n;}
 }
 AppUpdater(Activity a){activity=a;prefs=a.getSharedPreferences("updates",0);long v=Long.MAX_VALUE;try{v=a.getPackageManager().getPackageInfo(a.getPackageName(),0).getLongVersionCode();}catch(Exception ignored){}installed=v;try{release=new Release(new JSONObject(prefs.getString("verified_release","{}")));}catch(Exception ignored){}}
 void onResume(){foreground=true;if(closed)return;if(release!=null&&installed<release.minimum)show();if(pendingPermission){pendingPermission=false;if(activity.getPackageManager().canRequestPackageInstalls()&&ready!=null)install();else setMessage("Permite instalar actualizaciones de GeoPin Pro y vuelve a intentarlo.");}ui.removeCallbacks(ticker);ui.post(ticker);}
 void onPause(){foreground=false;ui.removeCallbacks(ticker);}
 boolean required(){return release!=null&&installed<release.minimum;}
 void check(){if(closed||busy||downloading||SystemClock.elapsedRealtime()-lastCheck<55000&&lastCheck!=0)return;lastCheck=SystemClock.elapsedRealtime();busy=true;io.execute(()->{try{
  Request req=new Request.Builder().url(MANIFEST+"?check="+System.currentTimeMillis()/60000).header("Cache-Control","no-cache").header("User-Agent","GeoPinPro-Updater/1").build();Release candidate;
  try(Response response=client.newCall(req).execute()){if(!response.isSuccessful()||response.body()==null)throw new IOException("No se pudo consultar la versión");candidate=new Release(new JSONObject(new String(readLimited(response.body().byteStream(),16384),StandardCharsets.UTF_8)));}
  if(candidate.latest>installed){try(Response response=client.newCall(new Request.Builder().url(candidate.url).head().build()).execute()){if(!response.isSuccessful()||!downloadHost(response.request().url().host()))throw new IOException("Actualización no disponible");long length=response.body()!=null?response.body().contentLength():-1;String h=response.header("Content-Length");if(h!=null&&Long.parseLong(h)!=candidate.size)throw new IOException("Tamaño de actualización inesperado");}}
  ui.post(()->{busy=false;if(closed)return;boolean changed=release==null||!release.sha.equals(candidate.sha)||release.minimum!=candidate.minimum;release=candidate;prefs.edit().putString("verified_release",candidate.json.toString()).apply();if(changed){ready=null;if(dialog!=null)dialog.dismiss();dialog=null;}if(candidate.latest>installed&&foreground)show();else if(dialog!=null){dialog.dismiss();dialog=null;}});
 }catch(Exception e){ui.post(()->{busy=false;if(!closed&&required()&&foreground){show();setMessage("Esta versión necesita actualizarse. No pudimos consultar el servidor; puedes reintentar la descarga.");}});}});}
 static boolean downloadHost(String h){return h.equals("github.com")||h.equals("release-assets.githubusercontent.com")||h.equals("objects.githubusercontent.com");}
 void show(){if(closed||!foreground||release==null||release.latest<=installed||activity.isFinishing()||activity.isDestroyed())return;if(dialog!=null&&dialog.isShowing())return;
  LinearLayout box=new LinearLayout(activity);box.setOrientation(LinearLayout.VERTICAL);int p=(int)(24*activity.getResources().getDisplayMetrics().density);box.setPadding(p,p/2,p,p/2);message=new TextView(activity);message.setTextSize(16);message.setText("Hay una nueva versión de GeoPin Pro: "+release.name+".\n\n"+(required()?"Actualiza para continuar usando la aplicación.":"Puedes instalarla ahora.")+(release.notes.isEmpty()?"":"\n\n"+release.notes));box.addView(message);
  dialog=new AlertDialog.Builder(activity).setTitle(required()?"Actualización necesaria":"Nueva versión disponible").setView(box).setPositiveButton("Actualizar",null).setNegativeButton(required()?"Salir":"Más tarde",(d,w)->{if(required())activity.finishAndRemoveTask();}).create();dialog.setCancelable(!required());dialog.setCanceledOnTouchOutside(!required());dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{if(ready!=null)install();else download();}));dialog.show();
 }
 void setMessage(String s){if(message!=null)message.setText(s);}
 void download(){if(closed||downloading||release==null)return;downloading=true;Release expected=release;dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);setMessage("Descargando GeoPin Pro "+expected.name+"…");io.execute(()->{File part=null;try{File dir=new File(activity.getFilesDir(),"updates");if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException("No se pudo preparar la descarga");part=new File(dir,"update.part");File apk=new File(dir,"update.apk");MessageDigest digest=MessageDigest.getInstance("SHA-256");long total=0;int lastPercent=-1;
  try(Response response=client.newCall(new Request.Builder().url(expected.url).build()).execute()){if(!response.isSuccessful()||response.body()==null||!downloadHost(response.request().url().host()))throw new IOException("Descarga no disponible");try(InputStream in=response.body().byteStream();FileOutputStream out=new FileOutputStream(part)){byte[] buffer=new byte[32768];int n;while((n=in.read(buffer))!=-1){if(Thread.currentThread().isInterrupted()||closed)throw new InterruptedIOException();total+=n;if(total>expected.size||total>MAX_APK)throw new IOException("Tamaño de descarga inválido");digest.update(buffer,0,n);out.write(buffer,0,n);int percent=(int)(total*100/expected.size);if(percent!=lastPercent){lastPercent=percent;ui.post(()->{if(!closed)setMessage("Descargando actualización… "+percent+" %");});}}out.getFD().sync();}}
  if(total!=expected.size||!hex(digest.digest()).equals(expected.sha))throw new IOException("La descarga no pasó la verificación");verifyApk(part,expected);if(!part.renameTo(apk))throw new IOException("No se pudo guardar la actualización");
  ui.post(()->{downloading=false;if(closed)return;ready=apk;dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);dialog.getButton(AlertDialog.BUTTON_POSITIVE).setText("Instalar actualización");setMessage("Descarga comprobada. Android te pedirá confirmar la instalación.");if(foreground)install();});
 }catch(Exception e){if(part!=null)part.delete();ui.post(()->{downloading=false;if(closed)return;if(dialog!=null)dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);setMessage("No se pudo descargar una actualización válida. Revisa tu conexión y pulsa Actualizar para reintentar.");});}});}
 void verifyApk(File apk,Release expected)throws Exception{PackageManager pm=activity.getPackageManager();PackageInfo info=pm.getPackageArchiveInfo(apk.getAbsolutePath(),PackageManager.GET_SIGNING_CERTIFICATES);if(info==null||!activity.getPackageName().equals(info.packageName)||info.getLongVersionCode()!=expected.latest||info.signingInfo==null)throw new IOException("El archivo no corresponde a GeoPin Pro");Signature[] signers=info.signingInfo.getApkContentsSigners();if(signers.length!=1||!CERT.equals(hex(MessageDigest.getInstance("SHA-256").digest(signers[0].toByteArray()))))throw new IOException("Firma de actualización no válida");PackageInfo self=pm.getPackageInfo(activity.getPackageName(),PackageManager.GET_SIGNING_CERTIFICATES);if(self.signingInfo==null||self.signingInfo.getApkContentsSigners().length!=1||!Arrays.equals(self.signingInfo.getApkContentsSigners()[0].toByteArray(),signers[0].toByteArray()))throw new IOException("Firma incompatible con la app instalada");}
 void install(){if(ready==null||closed||!foreground)return;try{if(!activity.getPackageManager().canRequestPackageInstalls()){pendingPermission=true;setMessage("Permite que GeoPin Pro instale esta actualización y regresa aquí. Android pedirá confirmar la instalación.");activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+activity.getPackageName())));return;}Uri uri=FileProvider.getUriForFile(activity,activity.getPackageName()+".updates",ready);Intent intent=new Intent(Intent.ACTION_INSTALL_PACKAGE).setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);intent.setClipData(ClipData.newRawUri("Actualización GeoPin Pro",uri));activity.startActivity(intent);}catch(Exception e){setMessage("No se pudo abrir el instalador de Android. Pulsa Instalar actualización para reintentar.");}}
 static byte[] readLimited(InputStream in,int limit)throws IOException{ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[2048];int n;while((n=in.read(b))!=-1){if(out.size()+n>limit)throw new IOException("Respuesta demasiado grande");out.write(b,0,n);}return out.toByteArray();}
 static String hex(byte[] bytes){StringBuilder s=new StringBuilder();for(byte b:bytes)s.append(String.format(Locale.US,"%02x",b&255));return s.toString();}
 @Override public void close(){closed=true;foreground=false;ui.removeCallbacksAndMessages(null);client.dispatcher().cancelAll();io.shutdownNow();if(dialog!=null){dialog.dismiss();dialog=null;}}
}
