package mx.coberturapunto;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.graphics.Color;
import android.location.Address;
import android.location.Geocoder;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.webkit.*;
import android.widget.*;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Executors;
import org.json.JSONObject;

public class MainActivity extends Activity {
    private MapEngine mapEngine;
    private WebEngine webEngine;
    private String ORIGIN;
    private WebView web;
    private CaptureFrame webFrame;
    private EditText input;
    private AddressSuggestions suggestions;
    private SearchMemory searchMemory;
    private Button find;
    private ProgressBar activityProgress;
    private FeedbackSounds feedbackSounds;
    private int backgroundColor,surfaceColor,inkColor,accentColor,onAccentColor;
    private boolean darkTheme;
    private int querySequence;
    private int activeQuery;
    private boolean locationLoading;
    private LocationInput.Point pending;
    private String currentSource="other";
    private String currentCity="";
    private String currentQueryId="";
    private final Runnable locationTimeout=()->{
        if(locationLoading){
            activeQuery=0;
            AppTelemetry.locationError("timeout",currentSource,currentCity,pending!=null?pending.lat():null,pending!=null?pending.lon():null);
            showError("La consulta está tardando. Intenta fijar el punto otra vez.");
        }
    };
    private Button center;
    private Button captureButton;
    private LinearLayout rootLayout;
    private LinearLayout searchBar;
    private LinearLayout actionBar;
    private boolean captureSaving;
    private Runnable captureFeedbackCleanup;
    private int normalOrientation;
    private long captureStartTime;
    private final java.util.concurrent.ExecutorService worker=Executors.newSingleThreadExecutor();
    private int lookupGeneration;
    private boolean ready;
    private int loadGeneration;
    private String mapBootstrap;
    private boolean autoQueryPending;
    private static final long CACHE_BUDGET_BYTES=8L*1024*1024;
    private final android.os.Handler cacheHandler=new android.os.Handler(android.os.Looper.getMainLooper());
    private boolean foreground;
    private final Runnable cacheMaintenance=new Runnable(){
        @Override public void run(){
            if(!foreground)return;
            worker.execute(()->{
                boolean overBudget=cacheBytes(getCacheDir(),CACHE_BUDGET_BYTES)>CACHE_BUDGET_BYTES;
                runOnUiThread(()->{if(foreground && overBudget && web!=null)web.clearCache(true);});
            });
            cacheHandler.postDelayed(this,30000);
        }
    };

    private AppUpdater appUpdater;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        String startUrl=MapBookmark.get(this);
        if(startUrl.isEmpty()){
            startActivity(new Intent(this,BookmarkActivity.class).putExtra("pending_intent",getIntent()));
            finish();return;
        }
        mapEngine=new MapEngine(startUrl);
        ORIGIN=mapEngine.provider.origin();
        webEngine=new WebEngine(this,mapEngine.provider);
        appUpdater=new AppUpdater(this);
        configurePalette();
        feedbackSounds=new FeedbackSounds(this);
        AppTelemetry.init(this);
        RasterVisualDetector.init(this);
        AppTelemetry.appStarted(this);
        AppTelemetry.checkSession(this);
        normalOrientation=android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
        LinearLayout root=new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(backgroundColor);
        rootLayout=root;root.setFocusableInTouchMode(true);root.requestFocus();
        root.setOnApplyWindowInsetsListener((view,insets)->{view.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());return insets;});
        setContentView(root);
        LinearLayout row=new LinearLayout(this);row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12),dp(8),dp(12),dp(8));searchBar=row;
        LinearLayout field=new LinearLayout(this);field.setGravity(android.view.Gravity.CENTER_VERTICAL);
        field.setBackground(NativeUi.rounded(surfaceColor,dp(28)));
        input=new EditText(this);input.setSingleLine(true);input.setTextSize(14);input.setTextColor(inkColor);
        input.setHintTextColor(darkTheme?0xffa9b5be:0xff596873);input.setHint("Dirección o enlace de ubicación");
        input.setBackground(null);input.setPadding(dp(18),0,dp(8),0);
        input.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setOnTouchListener((v,event)->{
            if(event.getAction()==android.view.MotionEvent.ACTION_DOWN){
                if(LocationInput.isCoordinate(input.getText().toString())){
                    input.setText("");
                }
            }
            return false;
        });
        field.addView(input,new LinearLayout.LayoutParams(0,dp(50),1));
        find=new Button(this);NativeUi.button(find,surfaceColor,accentColor,0,getResources().getDisplayMetrics().density);
        find.setContentDescription("Buscar ubicación");find.setTooltipText("Buscar ubicación");
        field.addView(find,new LinearLayout.LayoutParams(dp(52),dp(48)));
        find.setPadding(dp(15),0,dp(15),0);
        row.addView(field,new LinearLayout.LayoutParams(0,dp(52),1));
        Button menu=new Button(this);menu.setText("⋮");menu.setTextSize(24);
        menu.setContentDescription("Opciones del mapa");
        NativeUi.button(menu,backgroundColor,inkColor,0,getResources().getDisplayMetrics().density);
        menu.setCompoundDrawablesRelativeWithIntrinsicBounds(null,null,null,null);
        menu.setPadding(0,0,0,0);menu.setTextSize(24);
        row.addView(menu,new LinearLayout.LayoutParams(dp(48),dp(48)));
        menu.setOnClickListener(v->{
            PopupMenu options=new PopupMenu(this,menu);
            options.getMenu().add("Cambiar enlace del mapa").setOnMenuItemClickListener(item->{
                startActivity(new Intent(this,BookmarkActivity.class).putExtra("edit",true));return true;
            });options.show();
        });root.addView(row);
        LinearLayout suggestionPanel=new LinearLayout(this);root.addView(suggestionPanel);
        searchMemory=new SearchMemory(this);
        suggestions=new AddressSuggestions(this,input,suggestionPanel,surfaceColor,inkColor,accentColor,searchMemory,address->{
            lookupGeneration++;
            currentCity=address.getLocality()!=null?address.getLocality():"";
            selectAndQuery(new LocationInput.Point(address.getLatitude(),address.getLongitude()),"search");
        });
        suggestions.areaChanged();
        activityProgress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);
        activityProgress.setIndeterminate(true);activityProgress.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(accentColor));
        root.addView(activityProgress,new LinearLayout.LayoutParams(-1,dp(2)));
        webFrame=new CaptureFrame(this);webFrame.setBackgroundColor(backgroundColor);root.addView(webFrame,new LinearLayout.LayoutParams(-1,0,1));
        web=new WebView(this);webFrame.addView(web,new FrameLayout.LayoutParams(-1,-1,android.view.Gravity.CENTER));
        web.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->{
            if(r-l!=or-ol || b-t!=ob-ot){
                Runnable inv=()->web.evaluateJavascript("if(window.map && window.map.invalidateSize)window.map.invalidateSize({animate:false});",null);
                web.post(inv);
                web.postDelayed(inv,300);
            }
        });
        LinearLayout actions=new LinearLayout(this);actions.setPadding(dp(12),dp(10),dp(12),dp(10));actionBar=actions;
        center=new Button(this);center.setText("Fijar centro");center.setContentDescription("Fijar centro y consultar cobertura");center.setEnabled(false);
        NativeUi.button(center,accentColor,onAccentColor,1,getResources().getDisplayMetrics().density);
        LinearLayout.LayoutParams primaryParams=new LinearLayout.LayoutParams(0,dp(52),1.25f);primaryParams.setMarginEnd(dp(10));actions.addView(center,primaryParams);
        captureButton=new Button(this);captureButton.setText("Captura");captureButton.setContentDescription("Capturar mapa");
        NativeUi.button(captureButton,surfaceColor,inkColor,2,getResources().getDisplayMetrics().density);
        actions.addView(captureButton,new LinearLayout.LayoutParams(0,dp(52),1));root.addView(actions);
        captureButton.setOnClickListener(v->{v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);startCapture();});
        if(android.os.Build.VERSION.SDK_INT>=33)getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,()->{if(suggestions.visible()){suggestions.dismiss();input.clearFocus();}else finish();});
        find.setOnClickListener(v->resolveInput(input.getText().toString().trim()));
        input.setOnEditorActionListener((v,a,e)->{if(e!=null && e.getAction()!=android.view.KeyEvent.ACTION_DOWN)return true;resolveInput(input.getText().toString().trim());return true;});
        center.setOnClickListener(v->{
            currentSource="fix_center";
            if(ready) web.evaluateJavascript("!!window.__coverageAdapter && window.__coverageAdapter.center()",r->{if(!"true".equals(r)) showError("El mapa todavía no está disponible.");});
        });
        WebViewOptimizations.apply(web.getSettings());
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) || !WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            AppTelemetry.locationError("webview","other","",null,null);
            showError("Actualiza Android System WebView para usar la app."); return;
        }
        WebViewCompat.addDocumentStartJavaScript(web,webEngine.documentStart(),Collections.singleton(ORIGIN));
        WebViewCompat.addWebMessageListener(web,"PointChannel",Collections.singleton(ORIGIN),(view,message,origin,mainFrame,reply)->{
            if(!mainFrame || !ORIGIN.equals(origin.toString())) return;
            try {
                JSONObject value=new JSONObject(message.getData());
                if(value.optString("type").equals("location-result")){
                    finishLocation(value.optInt("id"),value.optBoolean("ok"),value);return;
                }
                if(value.optString("type").equals("map-moved")){
                    onMapMoved();return;
                }
                if(value.optString("type").equals("point")) {
                    LocationInput.Point point=new LocationInput.Point(value.getDouble("lat"),value.getDouble("lon"));
                    String src=value.optString("source","other");
                    selectAndQuery(point,src.equals("center")?"fix_center":"other");
                }
            } catch (Exception ignored) { }
        });
        web.setWebChromeClient(new WebChromeClient(){
            @Override public boolean onConsoleMessage(ConsoleMessage m) { return true; }
            @Override public void onGeolocationPermissionsShowPrompt(String origin,GeolocationPermissions.Callback callback){callback.invoke(origin,false,false);}
        });
        web.setWebViewClient(new WebViewClient(){
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                if (request != null && request.getUrl() != null) {
                    WebResourceResponse intercepted = mapEngine.intercept(request.getUrl());
                    if (intercepted != null) return intercepted;
                }
                return super.shouldInterceptRequest(view, request);
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest request) {
                // Keep only the coverage site as a top-level page; its resource and CAPTCHA frames still load normally.
                return request.isForMainFrame() && !trusted(request.getUrl());
            }
            @Override public void onPageStarted(WebView view,String url,android.graphics.Bitmap icon){setBusy(true,"Cargando mapa");loadGeneration++;ready=false;center.setEnabled(false);captureButton.setEnabled(true);}
            @Override public void onPageCommitVisible(WebView view,String url){if(trusted(Uri.parse(url)))prepareMap(loadGeneration,0);}
            @Override public void onPageFinished(WebView view,String url){
                if(!trusted(Uri.parse(url))) return;
                prepareMap(loadGeneration,0);
            }
            @Override public void onReceivedError(WebView view,WebResourceRequest req,WebResourceError error){
                if(req.isForMainFrame()){
                    ready=false;center.setEnabled(false);captureButton.setEnabled(false);
                    AppTelemetry.locationError("webview","other","",null,null);
                    showError("No se pudo cargar el mapa. Revisa tu conexión.");
                }
            }
        });
        if(state!=null && state.containsKey("lat")) {select(new LocationInput.Point(state.getDouble("lat"),state.getDouble("lon")));autoQueryPending=true;}
        boolean debug=(getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE)!=0;
        if(debug && getIntent().getBooleanExtra("self_test",false)) {
            web.loadDataWithBaseURL(ORIGIN+"/", "<html><body>Prueba funcional del proveedor virtual<script>"+asset("virtual-location-selftest.js")+"</script></body></html>","text/html","UTF-8",null);
        } else {
            web.loadUrl(startUrl);
            if(state==null)receive(getIntent());
            else input.setText(state.getString("inputText",""));
        }
    }
    private void configurePalette(){
        darkTheme=(getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK)==android.content.res.Configuration.UI_MODE_NIGHT_YES;
        backgroundColor=darkTheme?0xff11171c:0xfff7f9fc;surfaceColor=darkTheme?0xff242e36:0xffe8eef4;
        inkColor=darkTheme?0xffe1e9ef:0xff17232c;accentColor=darkTheme?0xff9acdf1:0xff005f88;onAccentColor=darkTheme?0xff00344d:Color.WHITE;
        if(android.os.Build.VERSION.SDK_INT>=31){
            accentColor=getColor(darkTheme?android.R.color.system_accent1_200:android.R.color.system_accent1_700);
            onAccentColor=getColor(darkTheme?android.R.color.system_accent1_900:android.R.color.system_accent1_0);
        }
        getWindow().setStatusBarColor(backgroundColor);getWindow().setNavigationBarColor(backgroundColor);
        int flags=darkTheme?0:View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        if(!darkTheme && android.os.Build.VERSION.SDK_INT>=27)flags|=View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        getWindow().getDecorView().setSystemUiVisibility(flags);
    }
    private void setBusy(boolean busy,String description){
        if(activityProgress==null)return;
        activityProgress.setVisibility(busy?View.VISIBLE:View.INVISIBLE);
        activityProgress.setContentDescription(description);
        center.setText(locationLoading?"Ubicando…":"Fijar centro");
        find.setContentDescription(busy?description:"Buscar ubicación");
    }
    private void onMapMoved(){
        if(!locationLoading && !isFinishing() && !isDestroyed()){
            center.setText("Fijar centro");
            center.setContentDescription("Fijar centro y consultar cobertura");
            center.setCompoundDrawablesRelativeWithIntrinsicBounds(new NativeUi.Icon(1,onAccentColor,getResources().getDisplayMetrics().density),null,null,null);
        }
    }
    private void finishLocation(int id,boolean success,JSONObject details){
        if(id==0 || id!=activeQuery || !locationLoading)return;
        activeQuery=0;locationLoading=false;cacheHandler.removeCallbacks(locationTimeout);
        setBusy(false,success?"Ubicación lista":"No se pudo consultar");
        if(!success){
            String stage=details.optString("stage","other");
            AppTelemetry.locationError(stage,currentSource,currentCity,pending!=null?pending.lat():null,pending!=null?pending.lon():null);
            showError("No se pudo completar la consulta. Intenta fijar el punto otra vez.");
            return;
        }
        String mapState=details.optString("map_state","ready");
        long totalMs=details.optLong("total_ms",0);
        long colonyMs=details.optLong("colony_ms",0);
        long tilesMs=details.optLong("tiles_ms",0);

        final LocationInput.Point queryPoint = pending;
        final String queryId = currentQueryId;
        final String source = currentSource;
        final String city = currentCity;
        currentQueryId = "";

        if(queryPoint != null){
            new Thread(() -> {
                RasterVisualDetector.VisualEvaluation eval =
                        mapEngine.evaluate(queryPoint.lat(), queryPoint.lon(), queryId);

                if (eval.isYellow()) {
                    AppTelemetry.locationReadyYellow(queryId, queryPoint.lat(), queryPoint.lon(),
                            source, city, totalMs, colonyMs, tilesMs, mapState, eval);
                    runOnUiThread(() -> {
                        if (!isDestroyed() && !locationLoading) {
                            center.setText("Con fibra en el punto");
                            center.setContentDescription("Con fibra óptica en el punto");
                            center.setCompoundDrawablesRelativeWithIntrinsicBounds(new NativeUi.Icon(3, onAccentColor, getResources().getDisplayMetrics().density), null, null, null);
                        }
                    });
                } else if (eval.isWhite()) {
                    AppTelemetry.whiteConfirmed(eval);
                    runOnUiThread(() -> {
                        if (!isDestroyed() && !locationLoading) {
                            center.setText("Sin fibra en el punto");
                            center.setContentDescription("Sin cobertura de fibra en el punto");
                            center.setCompoundDrawablesRelativeWithIntrinsicBounds(new NativeUi.Icon(5, onAccentColor, getResources().getDisplayMetrics().density), null, null, null);
                        }
                    });
                } else {
                    AppTelemetry.locationIndeterminate(eval);
                }
                mapEngine.prefetch(queryPoint.lat(), queryPoint.lon());
            }, "FiberVisualWorker").start();
        }
        if(foreground && hasWindowFocus()){
            feedbackSounds.location();center.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
            center.setText("Ubicación lista");center.setCompoundDrawablesRelativeWithIntrinsicBounds(new NativeUi.Icon(3,onAccentColor,getResources().getDisplayMetrics().density),null,null,null);
            center.announceForAccessibility("Ubicación lista");
        }
        if((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE)!=0)android.util.Log.i("CoverageFeedback","location-ready id="+id+" state="+mapState);
    }
    private void prepareMap(int generation,int attempt){
        if(ready || generation!=loadGeneration || isFinishing() || isDestroyed())return;
        if(mapBootstrap==null)mapBootstrap=webEngine.bootstrap();
        web.evaluateJavascript(mapBootstrap,result->{
            if(ready || generation!=loadGeneration || isFinishing() || isDestroyed())return;
            if("true".equals(result)){
                ready=true;setBusy(false,"Mapa listo");center.setEnabled(true);captureButton.setEnabled(true);
                if(autoQueryPending)query();
                if((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE)!=0 && getIntent().getBooleanExtra("integration_test",false)){
                    getIntent().removeExtra("integration_test");web.evaluateJavascript(asset("integration-test.js"),null);
                }
            }else if(attempt<120)web.postDelayed(()->prepareMap(generation,attempt+1),250);
        });
    }
    // API 33+ uses the OnBackInvokedDispatcher registered in onCreate; this is only the legacy path.
    @android.annotation.SuppressLint("GestureBackNavigation")
    @Override public void onBackPressed(){if(suggestions.visible()){suggestions.dismiss();input.clearFocus();return;}super.onBackPressed();}
    @Override public boolean dispatchTouchEvent(android.view.MotionEvent event){
        if(captureSaving)return true;
        return super.dispatchTouchEvent(event);
    }
    private void startCapture(){
        if(captureSaving)return;
        if(!ready || web==null || web.getWidth()<=0 || web.getHeight()<=0){
            showError("El mapa todavía no está disponible.");return;
        }
        suggestions.dismiss();
        dismissCaptureFeedback();
        input.clearFocus();
        rootLayout.requestFocus();
        ((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(input.getWindowToken(),0);
        captureSaving=true;
        captureStartTime=System.currentTimeMillis();
        captureButton.setEnabled(false);
        center.setEnabled(false);
        find.setEnabled(false);
        activityProgress.setVisibility(View.VISIBLE);
        webFrame.setWideCapture(true);
        web.evaluateJavascript("(function(){var aim=document.getElementById('coverage-center-aim');if(aim){aim.style.visibility='hidden';}})();",ignored->
            web.postOnAnimation(()->web.postOnAnimation(()->{
                if(!captureSaving || isDestroyed())return;
                web.postVisualStateCallback(0,new WebView.VisualStateCallback(){
                    @Override public void onComplete(long requestId){copyCapture(0);}
                });
            })));
    }
    private void copyCapture(int attempt){
        if(!captureSaving || isFinishing() || isDestroyed() || !hasWindowFocus()){
            restoreCaptureUi();return;
        }
        int width=web.getWidth(),height=web.getHeight();
        if(width<=0 || height<=0 || width*16!=height*9 || (webFrame.getWidth()>webFrame.getHeight() && attempt<30)){
            if(attempt<30){web.postDelayed(()->copyCapture(attempt+1),50);return;}
            restoreCaptureUi();
            showError("Espera a que termine de ajustar la imagen e inténtalo de nuevo.");return;
        }
        android.graphics.Bitmap bitmap;
        try{
            bitmap=android.graphics.Bitmap.createBitmap(width,height,android.graphics.Bitmap.Config.ARGB_8888);
        }catch(OutOfMemoryError failure){
            restoreCaptureUi();
            showError("No se pudo preparar la captura.");return;
        }
        int[] position=new int[2];web.getLocationInWindow(position);
        android.graphics.Rect rect=new android.graphics.Rect(position[0],position[1],position[0]+width,position[1]+height);
        try{
            android.view.PixelCopy.request(getWindow(),rect,bitmap,result->{
                webFrame.setWideCapture(false);
                web.evaluateJavascript("(function(){var aim=document.getElementById('coverage-center-aim');if(aim){aim.style.visibility='visible';}})();",null);
                if(isFinishing() || isDestroyed() || !hasWindowFocus()){
                    bitmap.recycle();restoreCaptureUi();return;
                }
                if(result!=android.view.PixelCopy.SUCCESS){
                    bitmap.recycle();restoreCaptureUi();
                    showError("No se pudo capturar el mapa. Inténtalo de nuevo.");return;
                }
                worker.execute(()->{
                    try{
                        Uri saved=ScreenshotStore.save(this,bitmap);
                        if((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE)!=0)
                            android.util.Log.i("CoverageCapture","saved9x16="+width+"x"+height+" uri="+saved);
                        runOnUiThread(()->{
                            if(!isFinishing() && !isDestroyed()){
                                showCaptureFeedback();
                            }else{
                                restoreCaptureUi();
                            }
                        });
                    }catch(Exception failure){
                        runOnUiThread(()->{
                            restoreCaptureUi();
                            showError("No se pudo guardar la captura.");
                        });
                    }finally{
                        bitmap.recycle();
                    }
                });
            },cacheHandler);
        }catch(IllegalArgumentException | IllegalStateException failure){
            bitmap.recycle();
            restoreCaptureUi();
            showError("No se pudo capturar el mapa. Inténtalo de nuevo.");
        }
    }
    private void restoreCaptureUi(){
        if(captureSaving && captureStartTime>0){
            AppTelemetry.screenshotSaved(false,System.currentTimeMillis()-captureStartTime);
            captureStartTime=0;
        }
        captureSaving=false;
        webFrame.setWideCapture(false);
        if(web!=null)web.evaluateJavascript("(function(){var aim=document.getElementById('coverage-center-aim');if(aim){aim.style.visibility='visible';}})();",null);
        feedbackSounds.cancelPendingCapture();
        captureButton.setEnabled(ready);
        captureButton.setText("Captura");
        captureButton.setContentDescription("Capturar mapa");
        captureButton.setCompoundDrawablesRelativeWithIntrinsicBounds(new NativeUi.Icon(2,inkColor,getResources().getDisplayMetrics().density),null,null,null);
        center.setEnabled(ready && !locationLoading);
        find.setEnabled(true);
        activityProgress.setVisibility(locationLoading?View.VISIBLE:View.INVISIBLE);
    }
    private void showCaptureFeedback(){
        long duration=captureStartTime>0?System.currentTimeMillis()-captureStartTime:0;
        captureStartTime=0;
        AppTelemetry.screenshotSaved(true,duration);
        restoreCaptureUi();
        if(!foreground || !hasWindowFocus())return;
        feedbackSounds.capture();
        android.view.ViewGroup decor=(android.view.ViewGroup)getWindow().getDecorView();
        decor.performHapticFeedback(android.os.Build.VERSION.SDK_INT>=30
                ? android.view.HapticFeedbackConstants.CONFIRM
                : android.view.HapticFeedbackConstants.VIRTUAL_KEY);
        View flash=new View(this);
        flash.setBackgroundColor(Color.WHITE);flash.setAlpha(.72f);
        flash.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        flash.setClickable(false);
        decor.addView(flash,new android.view.ViewGroup.LayoutParams(-1,-1));
        captureFeedbackCleanup=()->{
            flash.animate().cancel();decor.removeView(flash);
        };
        flash.animate().alpha(0f).setDuration(220).withEndAction(this::dismissCaptureFeedback).start();
        Toast.makeText(this,"Captura guardada",Toast.LENGTH_SHORT).show();
        if((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE)!=0)
            android.util.Log.i("CoverageCapture","feedback=flash+haptic+sound");
    }
    private void dismissCaptureFeedback(){
        if(captureFeedbackCleanup!=null){Runnable cleanup=captureFeedbackCleanup;captureFeedbackCleanup=null;cleanup.run();}
    }
    private long cacheBytes(java.io.File file,long budget){
        if(!file.isDirectory())return file.length();
        java.io.File[] children=file.listFiles();long total=0;
        if(children!=null)for(java.io.File child:children){total+=cacheBytes(child,budget-total);if(total>budget)break;}
        return total;
    }
    @Override protected void onResume(){super.onResume();if(web!=null)AppTelemetry.checkSession(this);if(appUpdater!=null)appUpdater.onResume();}
    @Override protected void onStart(){super.onStart();foreground=true;cacheHandler.removeCallbacks(cacheMaintenance);cacheHandler.postDelayed(cacheMaintenance,30000);}
    @Override protected void onStop(){foreground=false;if(appUpdater!=null)appUpdater.onPause();dismissCaptureFeedback();if(feedbackSounds!=null)feedbackSounds.cancelPendingCapture();if(suggestions!=null)suggestions.dismiss();cacheHandler.removeCallbacks(cacheMaintenance);if(web!=null)web.clearCache(true);super.onStop();}
    private boolean trusted(Uri u){return mapEngine.trusted(u);}
    private String asset(String name){try(java.io.InputStream s=getAssets().open(name);java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream()){byte[] buffer=new byte[4096];int count;while((count=s.read(buffer))!=-1)out.write(buffer,0,count);return new String(out.toByteArray(),StandardCharsets.UTF_8);}catch(Exception e){throw new IllegalStateException(e);}}
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private void showError(String message){locationLoading=false;activeQuery=0;cacheHandler.removeCallbacks(locationTimeout);setBusy(false,message);if(!isFinishing() && !isDestroyed())Toast.makeText(this,message,Toast.LENGTH_LONG).show();}
    private void select(LocationInput.Point point){autoQueryPending=false;pending=point; center.setEnabled(ready);}
    private void selectAndQuery(LocationInput.Point point,String source){
        if(locationLoading && pending!=null && Math.abs(pending.lat()-point.lat())<1e-5 && Math.abs(pending.lon()-point.lon())<1e-5) return;
        suggestions.dismiss();input.clearFocus();select(point);autoQueryPending=true;
        currentSource=source!=null?source:"other";
        SearchMemory.Area area=searchMemory.area();
        if(currentCity.isEmpty() && area!=null && area.city!=null) currentCity=area.city;
        currentQueryId=java.util.UUID.randomUUID().toString();
        mapEngine.startQuery(currentQueryId);
        query();
    }
    private void query(){
        if(!ready || pending==null) return;
        if(currentQueryId==null || currentQueryId.isEmpty()){
            currentQueryId=java.util.UUID.randomUUID().toString();
            mapEngine.startQuery(currentQueryId);
        }
        autoQueryPending=false;
        final int requestId=++querySequence;activeQuery=requestId;locationLoading=true;
        setBusy(true,"Consultando ubicación");cacheHandler.removeCallbacks(locationTimeout);cacheHandler.postDelayed(locationTimeout,20000);
        ((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(input.getWindowToken(),0);
        String script=String.format(Locale.US,"window.__coverageAdapter ? window.__coverageAdapter.query(%.8f,%.8f,%d) : 'not-ready'",pending.lat(),pending.lon(),requestId);
        web.evaluateJavascript(script,result->{
            if(activeQuery==requestId && !"\"requested\"".equals(result)){
                AppTelemetry.locationError("webview",currentSource,currentCity,pending!=null?pending.lat():null,pending!=null?pending.lon():null);
                showError("No se pudo actualizar el punto. Inténtalo de nuevo.");
            }
        });
    }
    private void receive(Intent i){
        String text=null;
        String src="other";
        if(Intent.ACTION_SEND.equals(i.getAction())){
            text=i.getStringExtra(Intent.EXTRA_TEXT);
            src="shared_maps";
        }else if(Intent.ACTION_VIEW.equals(i.getAction())){
            text=i.getDataString();
            src=(text!=null && (text.startsWith("geo:") || text.startsWith("google.navigation:")))?"geo_uri":"shared_maps";
        }
        if(text!=null){
            currentSource=src;
            input.setText(text);
            resolveInput(text);
        }
    }
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);receive(intent);}
    private void resolveInput(String text){
        if(text.trim().isEmpty()) return;
        suggestions.dismiss();
        input.clearFocus();
        rootLayout.requestFocus();
        final int generation=++lookupGeneration;
        activeQuery=0;locationLoading=false;cacheHandler.removeCallbacks(locationTimeout);setBusy(true,"Buscando ubicación");
        autoQueryPending=false;
        ((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(input.getWindowToken(),0);
        worker.execute(()->{
            try {
                LocationInput.Point p=LocationInput.parse(text);
                URI link=LocationInput.link(text);
                if(p==null && link!=null) p=expand(link);
                if(p!=null){
                    final LocationInput.Point found=p;
                    final String src=LocationInput.isCoordinate(text)?"coordinates":currentSource;
                    runOnUiThread(()->{if(generation==lookupGeneration)selectAndQuery(found,src);});
                    return;
                }
                String geoAddress=LocationInput.geoAddress(text);
                if(text.contains("://") || ((text.startsWith("geo:") || text.startsWith("google.navigation:")) && geoAddress==null))
                    throw new IllegalArgumentException("El enlace no contiene un punto exacto. Comparte un pin de Maps o pega las coordenadas.");
                if(!Geocoder.isPresent())
                    throw new IllegalArgumentException("La búsqueda de direcciones no está disponible en este teléfono; usa coordenadas o un enlace.");
                List<Address> addresses=new Geocoder(this,new Locale("es","MX")).getFromLocationName(geoAddress==null?text:geoAddress,5);
                List<Address> filtered=new ArrayList<>();
                if(addresses!=null){
                    for(Address a:addresses){
                        if(LocationInput.isMexico(a)) filtered.add(a);
                    }
                }
                if(filtered.isEmpty()){
                    AppTelemetry.locationError("mexico_validation","search","",null,null);
                    throw new IllegalArgumentException("No se encontró la dirección en México. Añade ciudad y estado.");
                }
                runOnUiThread(()->{
                    if(generation!=lookupGeneration || isFinishing() || isDestroyed())return;
                    if(filtered.size()==1){
                        Address a=filtered.get(0);
                        searchMemory.remember(a);suggestions.areaChanged();
                        currentCity=a.getLocality()!=null?a.getLocality():"";
                        selectAndQuery(new LocationInput.Point(a.getLatitude(),a.getLongitude()),"search");
                        return;
                    }
                    String[] labels=filtered.stream().map(a->a.getAddressLine(0)).toArray(String[]::new);
                    setBusy(false,"Selecciona el lugar");
                    new AlertDialog.Builder(this).setTitle("Selecciona el lugar").setItems(labels,(d,n)->{
                        if(generation!=lookupGeneration)return;
                        Address a=filtered.get(n);
                        searchMemory.remember(a);suggestions.areaChanged();
                        currentCity=a.getLocality()!=null?a.getLocality():"";
                        selectAndQuery(new LocationInput.Point(a.getLatitude(),a.getLongitude()),"search");
                    }).setNegativeButton("Cancelar",null).show();
                });
            } catch(Exception e){
                runOnUiThread(()->{
                    if(generation==lookupGeneration){
                        AppTelemetry.locationError("geocoder","search","",null,null);
                        showError(e instanceof IllegalArgumentException && e.getMessage()!=null ? e.getMessage() : "No se pudo buscar. Revisa tu conexión e intenta de nuevo.");
                    }
                });
            }
        });
    }
    private LocationInput.Point expand(URI initial) throws Exception {
        URI current=initial;
        for(int n=0;n<6;n++){
            if(!LocationInput.allowed(current))throw new IllegalArgumentException("El enlace redirige fuera de Google Maps.");
            LocationInput.Point point=LocationInput.parse(current.toString()); if(point!=null)return point;
            HttpURLConnection connection=(HttpURLConnection)current.toURL().openConnection();
            connection.setInstanceFollowRedirects(false);connection.setConnectTimeout(8000);connection.setReadTimeout(8000);
            try {
                int code=connection.getResponseCode(); String target=connection.getHeaderField("Location");
                if(code>=300 && code<400 && target!=null){current=current.resolve(target);continue;}
                return null;
            }finally{connection.disconnect();}
        }
        throw new IllegalArgumentException("El enlace tiene demasiadas redirecciones.");
    }
    @Override protected void onSaveInstanceState(Bundle out){if(input!=null)out.putString("inputText",input.getText().toString());out.putInt("normalOrientation",normalOrientation);if(pending!=null){out.putDouble("lat",pending.lat());out.putDouble("lon",pending.lon());out.putBoolean("autoQueryPending",autoQueryPending);}super.onSaveInstanceState(out);}
    @Override protected void onDestroy(){foreground=false;if(appUpdater!=null)appUpdater.close();dismissCaptureFeedback();cacheHandler.removeCallbacks(cacheMaintenance);lookupGeneration++;worker.shutdownNow();if(suggestions!=null)suggestions.close();cacheHandler.removeCallbacks(locationTimeout);if(feedbackSounds!=null)feedbackSounds.close();if(web!=null)web.destroy();super.onDestroy();}
}
