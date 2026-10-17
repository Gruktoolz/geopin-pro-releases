package mx.coberturapunto;
import org.junit.Test;
import org.json.JSONObject;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import static org.junit.Assert.*;
public class TelemetryTransportTest {
 @Test public void sendsExistingEnvelopeWithoutMutatingEvent()throws Exception {
  try(ServerSocket server=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))){
   server.setSoTimeout(6000);ExecutorService worker=Executors.newSingleThreadExecutor();
   Future<String[]> request=worker.submit(()->{
    try(Socket socket=server.accept()){
     socket.setSoTimeout(6000);InputStream in=socket.getInputStream();StringBuilder headers=new StringBuilder();int b;
     while((b=in.read())!=-1){headers.append((char)b);if(headers.toString().endsWith("\r\n\r\n"))break;}
     int size=0;for(String line:headers.toString().split("\r\n")){if(line.toLowerCase().startsWith("content-length:"))size=Integer.parseInt(line.substring(15).trim());}
     byte[] bytes=new byte[size];new DataInputStream(in).readFully(bytes);
     socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}".getBytes(StandardCharsets.UTF_8));
     return new String[]{headers.toString(),new String(bytes,StandardCharsets.UTF_8)};
    }
   });
   try{
    TelemetryBackend backend=new JsonHttpTelemetryBackend("http://127.0.0.1:"+server.getLocalPort()+"/events","fixture-token");
    assertFalse(backend.enabled()); // Direct local transport test; production enqueue requires HTTPS.
    JSONObject event=new JSONObject().put("event","location_ready").put("payload",new JSONObject().put("lat",20.5).put("lon",-103.2));
    backend.send(event);String[] captured=request.get(6,TimeUnit.SECONDS);JSONObject received=new JSONObject(captured[1]);
    assertTrue(captured[0].startsWith("POST /events HTTP/1.1"));assertTrue(captured[0].toLowerCase().contains("content-type: application/json; charset=utf-8"));
    assertEquals("fixture-token",received.getString("key"));assertEquals("location_ready",received.getString("event"));
    assertEquals(event.getJSONObject("payload").toString(),received.getJSONObject("payload").toString());assertFalse(event.has("key"));
   }finally{worker.shutdownNow();}
  }
 }
 @Test public void backendEnablementRequiresConfiguredHttps(){assertFalse(new JsonHttpTelemetryBackend("","x").enabled());assertTrue(new JsonHttpTelemetryBackend("https://events.example","x").enabled());}
}
