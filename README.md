# Mi Launcher (Subaru Outback)

## Compilar (sin instalar nada)
1. Crea un repositorio en github.com y sube TODO el contenido de esta carpeta (incluida `.github`).
2. Pestaña **Actions** -> "Build APK" -> espera ~5 min.
3. Entra a la ejecución terminada -> **Artifacts** -> descarga `MiAutoLauncher-apk`.

## Instalar
USB -> instala el APK -> Home -> "Mi Launcher" -> "Siempre".

## Foto del auto
Ya usa la foto de tu diseño. De noche: `drawable-night-nodpi/car_photo.png`; de día: `drawable-nodpi/car_photo.png` (puedes cambiarlas por fotos tuyas, ~1240x600).

## Canbus (puertas / reversa)
VEHICLE o el engrane: reglas `EVENTO|acción|clave|valor` y visor de broadcasts en vivo.

## ELM327 (refrigerante y ATF del CVT)
1. Empareja el ELM327 Bluetooth en **Ajustes de Android** (Bluetooth), no solo en el menú de la radio, clave 1234 o 0000. Si no está emparejado, el launcher intenta emparejarlo solo.
2. En VEHICLE pon tipo `bt` y el nombre del ELM (déjalo vacío para que busque uno que diga OBD/ELM/V-LINK), y GUARDA.
3. PIDs por defecto, ambos hacia la TCM (`7E1`, sin cambiar de cabecera): refrigerante `COOLANT|7E1|0105|A-40` (en vivo) y ATF `ATF|7E1|221286|A-40`. Si un PID responde NO DATA se reintenta una vez.
4. **El ATF no es en vivo**: la TCM guarda su temperatura al arrancar el motor y esa lectura queda fija durante el viaje. El cuadro dice "ATF CVT (INICIO)" por eso. Mantiene el último valor leído.
5. Conexión: prueba 4 formas de abrir el socket (insegura, segura y dos por reflexión), reintenta cada pocos segundos para siempre, y si el ELM deja de responder se reconecta solo. El texto pequeño de la esquina del héroe muestra el estado o el motivo del fallo.
6. El ELM327 solo acepta un cliente a la vez: cierra Car Scanner o la app del teléfono antes de usarlo en la radio.
7. Si guardaste PIDs en una versión anterior, esta versión los reinicia una sola vez a los nuevos.

## Reproductor Bluetooth
Toca el panel de Bluetooth Audio la primera vez: te lleva a "Acceso a notificaciones" (actívalo para Mi Launcher). Eso permite leer la canción y controlar anterior / play / siguiente. En Android 13+ puede que antes debas ir a Info de la app -> menú ⋮ -> "Permitir ajustes restringidos".
Si tu radio usa su propia app de Bluetooth, pon su paquete en VEHICLE ("Paquete de la app de música Bluetooth") para que los botones le lleguen directo.

## Cambiar los accesos de abajo
Mantén presionado cualquiera de los 6 y elige una app. Para volver al original, elige "Restaurar predeterminado".

## Mini mapa
Va en la esquina inferior derecha. **Gira con el rumbo del GPS** (el frente del auto siempre hacia arriba, con una flecha en el centro) y muestra arriba la dirección en que avanzas (N, NE, E…). Solo gira en movimiento (desde 4 km/h); parado conserva la última orientación. El zoom es el doble que antes (nivel 17). Necesita internet. Para ocultarlo: VEHICLE -> "Mini mapa" -> `no`.

## Diagnóstico en pantalla (v1.2)
- Esquina superior izquierda del héroe: estado del ELM y, debajo, la última respuesta cruda de cada PID (por ejemplo `ATF: 62 12 86 68`, `NO DATA` o `(vacio)`).
- Dentro del mapa: si no carga, escribe el motivo (sin internet, error SSL, HTTP 403…).
