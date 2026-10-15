package mx.coberturapunto;
import org.json.JSONObject;
/** Replaceable telemetry transport. The event facade has no dependency on a hosting service. */
public interface TelemetryBackend {
 boolean enabled();
 void send(JSONObject event) throws Exception;
}
