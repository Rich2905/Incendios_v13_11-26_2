# Monitor de Incendios Forestales (app Android)

App que muestra el tablero y descarga los datos **directamente** de la tarjeta semanal de CONAFOR
(monitor_incendios.cnf.gob.mx). Funciona sin Claude, sin pegar paquetes y sin tareas programadas.

- Al abrirse muestra los últimos datos guardados (de fábrica trae el corte del 6 nov 2026) y consulta CONAFOR.
- Botón **↻ Actualizar** para consultar de nuevo; también se actualiza sola al volver a la app tras 30 min.
- Sin conexión, usa la última descarga exitosa.
- Excel, PPT, PDF y PNG se guardan en *Descargas/Monitor Incendios*.

## Compilar el APK

### Opción A: GitHub (sin instalar nada)
1. Crea un repositorio nuevo (puede ser privado) y sube **todo** el contenido de esta carpeta, incluida `.github/`.
2. Ve a la pestaña **Actions** → «Compilar APK». Corre solo al subir; si no, pulsa **Run workflow**.
3. Al terminar (unos 5 min), abre la ejecución y descarga **MonitorIncendios-apk** (un .zip con el .apk).

### Opción B: Android Studio
1. *File → Open* y elige esta carpeta. Espera a que sincronice Gradle.
2. *Build → Build App Bundle(s) / APK(s) → Build APK(s)*.
3. El archivo queda en `app/build/outputs/apk/debug/app-debug.apk`.

## Instalar en el celular
Copia el .apk al teléfono, ábrelo y permite «Instalar apps desconocidas» para la app con la que lo abriste.

## Notas
- El tablero está en `app/src/main/assets/index.html`. Para cambiarlo, edita ese archivo y vuelve a compilar.
- Requiere Android 7.0 o superior.
