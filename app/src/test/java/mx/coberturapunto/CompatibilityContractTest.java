package mx.coberturapunto;
import org.junit.Test;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;
public class CompatibilityContractTest {
 static String digest(String value)throws Exception{return AppUpdater.hex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
 @Test public void runtimeValuesMatchExistingWireContract()throws Exception{
  assertEquals("d92a2d83c6d3dc8ced8a8ca224c9173c7ef9fe7d0923597e1c2d4de6da54a742",digest(EndpointCatalog.map_origin()));
  assertEquals("ba5885af04cc3bfcf6ee3d23f75ab41e576b14a0529fe6996d46e4630ca7e03f",digest(EndpointCatalog.telemetry()));
  assertEquals("47d117939929df56df4de18092b0a4081af520db7233aefda7684ce1e88d41a1",digest(EndpointCatalog.ingest()));
  assertEquals("5cec27064c390b13a04702cdbcff145850ef74ccaf1a08adf328fd1e994261c7",digest(new CoverageTileProfile().overlay(16,123,456)));
  assertEquals("029ca7407af62edffde79afa3cc9b64293efadf9e036e2f816fdd79860f89ae1",digest(new CoverageTileProfile().baseTile(16,123,456)));
  assertEquals("78905a5a310762a3ae01087efb97d6f7aea2170e5682f36d607a826cd0745ecc",digest(new CoverageTileProfile().overlay(17,123,456)));
  assertEquals("43eca4a5f387644f612ac6e278b0e5c241b5873012dd3c63c163116ab93d7d4c",digest(new CoverageTileProfile().baseTile(17,123,456)));
 }
 @Test public void rasterClassificationAndCacheRemainCompatible(){RasterTileProfile p=new CoverageTileProfile();
  assertEquals("",p.cacheNamespace());assertTrue(p.primary(p.overlay(17,123,456)));assertTrue(p.base(p.baseTile(17,123,456)));
  assertFalse(p.primary("https://map.example/no-tile"));
 }
}
