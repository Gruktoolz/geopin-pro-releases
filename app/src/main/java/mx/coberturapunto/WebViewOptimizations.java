package mx.coberturapunto;
import android.webkit.WebSettings;
/** Shared WebView performance and isolation policy, independent of origin. */
final class WebViewOptimizations {
 static void apply(WebSettings settings){
  settings.setJavaScriptEnabled(true);settings.setDomStorageEnabled(true);
  settings.setDatabaseEnabled(true);settings.setCacheMode(WebSettings.LOAD_CACHE_ELSE_NETWORK);
  settings.setGeolocationEnabled(false);settings.setAllowFileAccess(false);settings.setAllowContentAccess(false);
  settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
 }
}
