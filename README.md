# GeoPin Pro Engine

Motor Android de geoposición virtual dentro de una WebView. El enlace se configura al iniciar; no modifica el GPS del dispositivo.

## Arquitectura

- MapEngine: sesión y capacidades del proveedor seleccionado.
- ProviderRegistry / MapProvider: registro explícito por origen HTTPS, adaptador y capacidades. Incluye un perfil de cobertura raster y un adaptador Leaflet de navegación sin verificación de cobertura. El segundo admite páginas Leaflet compatibles que exponen window.map; no cualquier sitio web.
- WebEngine: composición de scripts y aislamiento del puente por origen.
- WebViewOptimizations / webview-optimizations.js: reglas comunes de rendimiento.
- CoverageTileProfile: construcción de solicitudes y reconocimiento de teselas del perfil raster.
- AppTelemetry / TelemetryBackend: eventos y transporte intercambiable. JsonHttpTelemetryBackend mantiene el contrato JSON existente. La telemetría de puntos conserva coordenadas exactas, identificador de instalación y estado de verificación; no debe presentarse como anónima.
- EndpointCatalog: valores codificados, reconstruidos en ejecución. La codificación no es cifrado ni hace secretos los destinos o las credenciales incluidas en una aplicación cliente.

## Compilación privada

JDK 17, Android SDK 36. Configura local.properties fuera de Git. Para release usa GEOPIN_STORE_FILE, GEOPIN_STORE_PASSWORD, GEOPIN_KEY_ALIAS y GEOPIN_KEY_PASSWORD, o keystore.properties ignorado. La clave de producción no se incluye en este proyecto exportado. Ejecuta ./gradlew :app:assembleRelease :app:testDebugUnitTest :app:lintRelease.

La identidad Android y la firma de producción se conservan para permitir actualización. El canal de actualización existente conserva su contrato y verificación de integridad; esta carpeta no publica ninguna release ni fuente. No se ha trasladado historial del proyecto anterior.
