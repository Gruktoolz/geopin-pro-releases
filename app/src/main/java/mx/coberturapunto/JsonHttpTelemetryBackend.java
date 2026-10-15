package mx.coberturapunto;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;
final class JsonHttpTelemetryBackend implements TelemetryBackend {
 private final String endpoint,key;
 JsonHttpTelemetryBackend(String endpoint,String key){this.endpoint=endpoint;this.key=key;}
 public boolean enabled(){return endpoint!=null && endpoint.startsWith("https://");}
 public void send(JSONObject event) throws Exception {
  JSONObject item=new JSONObject(event.toString());item.put("key",key);
            HttpURLConnection conn = null;
            try {
                URL url = new URL(endpoint);
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                conn.setConnectTimeout(6000);
                conn.setReadTimeout(6000);
                conn.setDoOutput(true);
                byte[] bytes = item.toString().getBytes(StandardCharsets.UTF_8);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(bytes);
                    os.flush();
                }
                int code = conn.getResponseCode();
                if (code >= 300 && code < 400) {
                    String redirectUrl = conn.getHeaderField("Location");
                    if (redirectUrl != null && !redirectUrl.isEmpty()) {
                        HttpURLConnection redirectConn = null;
                        try {
                            redirectConn = (HttpURLConnection) new URL(redirectUrl).openConnection();
                            redirectConn.setRequestMethod("GET");
                            redirectConn.setConnectTimeout(6000);
                            redirectConn.setReadTimeout(6000);
                            redirectConn.getResponseCode();
                            readStream(redirectConn.getInputStream());
                            // Response consumed; endpoint and server body never logged.
                        } finally {
                            if (redirectConn != null) redirectConn.disconnect();
                        }
                    }
                } else {
                            readStream(conn.getInputStream());
                    // Response consumed; endpoint and server body never logged.
                }
            } catch (Exception e) {
                throw e;
            } finally {
                if (conn != null) conn.disconnect();
            }
 }
    private static String readStream(java.io.InputStream in) {
        if (in == null) return "";
        try (java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            byte[] buf = new byte[1024];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8).trim();
        } catch (Exception e) {
            return "";
        }
    }

}
