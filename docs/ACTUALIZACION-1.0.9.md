# GeoPin Pro 1.0.9 (34): comparación y verificación

Fecha: 17 de octubre de 2026. Publicación **completada y verificada**: [release v1.0.9](https://github.com/Gruktoolz/geopin-pro-releases/releases/tag/v1.0.9).

## Versiones y origen antes de la actualización

- Repositorio confirmado: https://github.com/Gruktoolz/geopin-pro-releases.
- Remoto `main`: `9fa772f`, manifiestos de 1.0.7/código 32.
- Tags remotos: v1.0.1, v1.0.3, v1.0.4, v1.0.6, v1.0.7. Última release: v1.0.7, publicada el 15 de octubre de 2026 a las 23:04:21 UTC.
- El tag v1.0.7 apunta a `6891120`, que todavía contiene metadatos de 1.0.6. El APK descargado de esa release sí declara 1.0.7/código 32; su SHA-256 coincide con los manifiestos. Un tag por sí solo no identifica el código que produjo ese APK.
- Los APK locales de 1.0.0 a 1.0.7 declaran códigos 25 a 32. El APK existente dentro del proyecto Antigravity también era 1.0.7/32, aunque su Gradle modificado ya declaraba 1.0.8/33.
- Se asignó **1.0.9/código 34**, superior tanto al APK publicado como a la configuración local no publicada.

## Historiales conservados

Antigravity trabajó sobre `cea43a8`, sin remoto configurado, con 6 archivos modificados y 10 archivos nuevos sin seguimiento (9 clases Java y una suite). Antes de la actualización, el repositorio remoto contenía README, workflow y dos manifiestos; no contenía el código Android. No existe ancestro común ni es posible atribuir diferencias Java línea a línea a una rama remota sin fuentes.

- `fd54727`: conserva exactamente los cambios de Antigravity antes de corregirlos.
- `083fcd3`: integra ambos historiales sin reescritura. Conserva el README remoto y el README local como `docs/ENGINE-README.md`.
- `7e34cd9`: correcciones limitadas al motor, pruebas y versión. Este es el commit de código compilado.

La carpeta original de Antigravity permanece intacta. Se trabajó sobre una copia separada. No se forzó ninguna rama ni se eliminaron tags.

## Diferencias reales incluidas

| Área | Cambios locales de Antigravity | Correcciones necesarias realizadas |
|---|---|---|
| Caché | TileStorage compartido, niveles PINNED/HOT/WARM/disco, TTL y metadatos HTTP | MIME web con nombres de campo correctos; claves de pinning compatibles con TileSpec; validación de imagen incluyendo JPEG; comprobar renombre; importar la caché anterior `tiles_storage` sin eliminarla ni rejuvenecer su fecha |
| Red y zonas | NetworkQualityMonitor, cola de prioridades, LastContextStore, ZoneManager y zonas habituales automáticas | Añadir ACCESS_NETWORK_STATE; transporte desconocido y pérdida de Wi-Fi impiden sincronización masiva; prefetch bloqueado en móvil/medido; sincronización usa prioridad de zona y evita encolar repetidamente la misma zona |
| Cobertura | CoverageEvaluator separado del render; marca explícita de fallback | Eliminar la inferencia de «tesela vacía» por tamaño inferior a 350 bytes; corregir extracción de zoom de `EPSG:900913:17` |
| Render | Buffer 24 y actualización a 5 ms; sin redraw repetido al mover; intento de retención de teselas | Usar realmente `allCurrentLoaded` antes de podar; evitar aplicar el parche dos veces; revalidación de caché vencida del viewport en segundo plano |

El código real configura zonas de radio 30 km con niveles de detalle y zooms 12–18. No coincide con el informe de Antigravity que describe 2.5 km y buffer 4. Tampoco se verificó la promesa de 120 FPS. Solo dos de los ocho assets JavaScript difieren entre el APK 1.0.7 y el APK nuevo: `coverage-adapter.js` y `webview-optimizations.js`. Los otros seis son idénticos por SHA-256.

No se añadieron pantallas, menús ni controles. Se conservaron los cambios funcionales ya presentes en Antigravity y se limitaron las correcciones a sus bloqueos de integración.

## Pruebas realizadas

- Gradle 8.13 con Temurin JDK 17.0.20.1: `testReleaseUnitTest assembleRelease lintRelease assembleDebug assembleDebugAndroidTest`, modo offline. BUILD SUCCESSFUL.
- 16 pruebas JUnit: 0 fallos, 0 errores, 0 omitidas.
- 6 suites JavaScript: todas pasan. La suite comprehensive de Antigravity simula el motor; no acredita por sí sola el comportamiento Android. La prueba nueva de buffer ejecuta el JavaScript de producción.
- Android 16: se ejecutaron las clases reales de almacenamiento y evaluación. Verificados importación de caché anterior, recreación de almacenamiento, rechazo de reemplazo inválido, persistencia de metadatos de validación, MIME web, fallback de padre y rechazo de su uso como cobertura precisa, retención PINNED tras exceder ambos LRU, persistencia JPEG y política de red.
- Leaflet 1.9.4 real dentro de WebView: consulta, centrado, zoom y adaptador. Instrumentación final: `INSTRUMENTATION_CODE: -1`.
- Instalación del APK público 1.0.7 y actualización sobre él al APK final 1.0.9 en el emulador: ambas aceptadas; Android informa código 34 y nombre 1.0.9.
- Lint: 0 errores, 34 advertencias. No se corrigieron advertencias ajenas al alcance.
- `git diff --check`: limpio.

El primer intento de compilación con el Java 25 de Android Studio falló por incompatibilidad de Gradle. Se resolvió seleccionando el JDK 17 existente, sin cambiar dependencias del proyecto.

## Firma y APK

- Archivo: `GeoPin-Pro-1.0.9.apk`, 3,962,862 bytes.
- Paquete: `mx.coberturapunto`; versión 1.0.9/34; SDK mínimo 29, objetivo 36.
- SHA-256 del APK: `836352977362cf651b1b61b7a98a7097fb47943196b6d4b8f100c8b561b0effe`.
- Certificado SHA-256: `2f0b678dd0f4ddb337f3614e337ce88d59c1afe3b3cfc294ede7ff6d4b533d00`, idéntico al APK publicado. Un firmante, RSA 2048, firma APK v2 válida.
- Se usó la clave original por variables de entorno del proceso de compilación. No se copió al proyecto de trabajo ni se incluyeron keystore.properties, keystores, contraseñas, local.properties o archivos .env en los commits o archivos de entrega de código.

## Pendiente y límites de la evidencia

No se probó el uso prolongado con el proveedor real ni el apagado y reapertura completos de la app con todos sus recursos web sin conexión. El almacenamiento de HTML no garantiza disponer de todos los JS/CSS de terceros. La conservación de metadatos 304 está probada; no se ejecutó una respuesta HTTP 304 real del proveedor. No se midieron FPS, bytes reales por zona, actualización de todas las zonas tras reconexión, TTL completo ni rendimiento en un teléfono físico. No se afirma funcionamiento offline completo ni ausencia absoluta de huecos visuales.

## Publicación completada

La autenticación por navegador permitió publicar el paquete Git y un importador con SHA-256 y commit fijados. [La importación en Actions terminó correctamente](https://github.com/Gruktoolz/geopin-pro-releases/actions/runs/39049882279). El commit de integración `53d026349b8ed268f7da0b630c3f5918c5fb2b13` contiene ambos historiales y exactamente el mismo código de app, pruebas y configuración que el commit compilado `7e34cd9`. No hubo reescritura ni force push.

- [Commit de código](https://github.com/Gruktoolz/geopin-pro-releases/commit/7e34cd9bb73fc9bbc74d930b32e4d6735c8acf01).
- [Release estable v1.0.9](https://github.com/Gruktoolz/geopin-pro-releases/releases/tag/v1.0.9), vinculada al commit de integración.
- [APK firmado](https://github.com/Gruktoolz/geopin-pro-releases/releases/download/v1.0.9/GeoPin-Pro-1.0.9.apk).
- Se descargó el asset público: su SHA-256, tamaño, certificado, paquete y versión coinciden con el APK local verificado.
- El workflow existente actualizó `updates.json` y `version.json`: ambos anuncian 1.0.9/34, mínimo soportado 34, con SHA y tamaño correctos. Se conservaron las rutas canónica y de migración.

Los intentos iniciales de Git por terminal y del disparador manual no completaron la publicación. La vía de navegador más importación verificada sí la completó; los límites de pruebas descritos arriba permanecen.
