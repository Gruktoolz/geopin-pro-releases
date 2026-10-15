# GeoPin Pro Engine

Motor Android de geoposición virtual dentro de una WebView. El enlace se configura al iniciar; no modifica el GPS del dispositivo.

## Arquitectura

- MapEngine: sesión y capacidades del proveedor seleccionado.
- ProviderRegistry / MapProvider: registro explícito por origen HTTPS, adaptador y capacidades. Incluye un perfil de cobertura raster y un adaptador general para Leaflet, MapLibre/Mapbox y OpenLayers, sin verificación de cobertura. Permite guardar nuevos enlaces HTTPS sin archivos de configuración. La integración requiere un objeto de mapa accesible o una instancia Leaflet descubierta al inicializarse; los mapas privados encapsulados o dentro de iframes de otro origen se abren para navegar y capturar, sin prometer posicionamiento.
- WebEngine: composición de scripts y aislamiento del puente por origen.
- WebViewOptimizations / webview-optimizations.js: reglas comunes de rendimiento.
- CoverageTileProfile: construcción de solicitudes y reconocimiento de teselas del perfil raster.
- AppTelemetry / TelemetryBackend: eventos y transporte intercambiable. JsonHttpTelemetryBackend mantiene el contrato JSON existente. La telemetría de puntos conserva coordenadas exactas, identificador de instalación y estado de verificación; no debe presentarse como anónima.
- EndpointCatalog: valores codificados, reconstruidos en ejecución. La codificación no es cifrado ni hace secretos los destinos o las credenciales incluidas en una aplicación cliente.

## Compilación privada

JDK 17, Android SDK 36. Configura local.properties fuera de Git. Para release usa GEOPIN_STORE_FILE, GEOPIN_STORE_PASSWORD, GEOPIN_KEY_ALIAS y GEOPIN_KEY_PASSWORD, o keystore.properties ignorado. La clave de producción no se incluye en este proyecto exportado. Ejecuta ./gradlew :app:assembleRelease :app:testDebugUnitTest :app:lintRelease.

La identidad Android y la firma de producción se conservan para permitir actualización. El canal de actualización existente conserva su contrato y verificación de integridad; esta carpeta no publica ninguna release ni fuente. No se ha trasladado historial del proyecto anterior.

## Nuevos enlaces

Usa el mismo menú «Cambiar enlace del mapa», pega un enlace HTTPS y guarda. Solo hay un mapa activo, como antes; cambiar el enlace selecciona automáticamente el adaptador. No se importan archivos ni se añaden pantallas.

El adaptador general admite posicionamiento en instancias Leaflet detectadas y objetos de mapa accesibles de MapLibre/Mapbox u OpenLayers (EPSG:4326 y EPSG:3857). La navegación y las capturas siguen disponibles para páginas cuyos mapas no pueden controlarse. Mapas encapsulados, proyecciones diferentes, controles propios y contenido dentro de iframes de otro origen pueden requerir un adaptador específico. No se interpreta su color como cobertura.

Pruebas JS: ejecuta los cuatro archivos tests/*.test.cjs con Node. La prueba Android offline usa la biblioteca oficial Leaflet 1.9.4, incluida solo en la variante debug, y no envía telemetría ni descarga teselas.
