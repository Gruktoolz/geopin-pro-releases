package mx.coberturapunto;

import android.content.Context;
import java.net.URI;

/** User-selected start page. Resolved through the registered provider allowlist. */
final class MapBookmark {
    private static final String PREFS = "map_bookmark";
    private static final String URL = "start_url";

    static String normalize(String value) {
        try {
            URI uri = new URI(value.trim());
            MapProvider provider=ProviderRegistry.DEFAULT.resolve(uri);
            String path = uri.getRawPath();
            return provider.origin() + (path == null || path.isEmpty() ? "/" : path)
                    + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery())
                    + (uri.getRawFragment() == null ? "" : "#" + uri.getRawFragment());
        } catch (Exception e) {
            throw new IllegalArgumentException("Introduce el enlace HTTPS del mapa de cobertura compatible.");
        }
    }

    static String get(Context context) {
        String value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(URL, "");
        try { return normalize(value); } catch (IllegalArgumentException e) { return ""; }
    }

    static boolean save(Context context, String value) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(URL, normalize(value)).commit();
    }
}
