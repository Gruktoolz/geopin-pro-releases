package mx.coberturapunto;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
/** Central runtime endpoint catalog. Encoding is packaging, not secrecy or authentication. */
final class EndpointCatalog {
 private EndpointCatalog() {}
 private static String decode(String value) { return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8); }
 static String map_origin() { return decode("aHR0cHM6Ly9jb2JlcnR1cmEubWVnYWNhYmxlLmNvbS5teA=="); }
 static String overlay_tiles() { return decode("aHR0cHM6Ly9zZXJ2aWNpb3MuZ2VvbWVnYS5jb20ubXgvZ3djX2Nubi9zZXJ2aWNlL3dtdHM/"); }
 static String base_tiles() { return decode("aHR0cHM6Ly9tdDEuZ29vZ2xlLmNvbS92dC9seXJzPW0="); }
 static String telemetry() { return decode("aHR0cHM6Ly9zY3JpcHQuZ29vZ2xlLmNvbS9tYWNyb3Mvcy9BS2Z5Y2J5RmdwLUtiZXhCU3lkV05abEJ1OTlFa253Z1hVc2ZRdUlEUTJObExrMW5od1M4eldybTdqbnZDb1puWmd4dnV4UzYvZXhlYw=="); }
 static String ingest() { return decode("Y3BfdGVsZW1ldHJ5X2tleV8yMDI2"); }
 static String releases() { return decode("aHR0cHM6Ly9naXRodWIuY29tL0dydWt0b29sei9nZW9waW4tcHJvLXJlbGVhc2VzLw=="); }
 static String updates() { return decode("aHR0cHM6Ly9yYXcuZ2l0aHVidXNlcmNvbnRlbnQuY29tL0dydWt0b29sei9nZW9waW4tcHJvLXJlbGVhc2VzL21haW4vdXBkYXRlcy5qc29u"); }
 static String leaflet_origin() { return decode("aHR0cHM6Ly9sZWFmbGV0anMuY29t"); }
}
