# MultiPantalla TV

App personal para Android TV que acomoda varias apps a la vez (YouTube, Twitch,
etc.) en 2, 3 o 4 ventanas, con control, volumen y pausa por ventana.

## Cómo funciona

- Usa las **ventanas libres** de Android (un ajuste oculto que la app activa
  desde la revisión inicial; necesita un reinicio de la TV la primera vez).
- Cada app se abre como ventana con su tamaño exacto, a través de **Shizuku**
  (permisos de shell, sin root).
- **Volumen por ventana:** cambia el volumen propio de los reproductores de
  cada app (la misma API que usa el volumen por app de MIUI). Android 13+.
- **Pausa por ventana:** usa la sesión multimedia de la app; si no tiene,
  pausa sus reproductores de audio.
- **Silenciar:** `appops PLAY_AUDIO deny` / `allow`.
  Ojo: en `PLAY_AUDIO` y `TAKE_AUDIO_FOCUS` el modo `default` equivale a
  **negado** (la app queda muda). Siempre se restaura con `allow`.

## Controles

Durante la sesión, en cualquier app:

- **CH ▲ / CH ▼:** pasar el control a la ventana siguiente / anterior
  (aparece un borde naranja en la ventana activa).
- **ⓘ:** menú rápido de la ventana activa: pausa, volumen (◀ ▶), silenciar,
  que solo suene esa, pantalla completa, cambiar diseño, cerrar todo.

**Salir:** presionar **Inicio** durante la sesión, o **Atrás** en el panel,
cierra las ventanas, restaura audio y volumen y deja las apps en pantalla
completa. Fuera de la sesión el servicio de teclas no revisa ninguna tecla.

Las teclas se pueden cambiar en **Asignar teclas**. El **Probador de teclas**
muestra qué botones llegan a la app (algunos los usa la TV y no llegan).

## Descarga

Último APK (siempre el mismo link):
https://github.com/Juandegates/multipantalla-tv/releases/download/latest/MultiPantallaTV.apk

## Iniciar Shizuku (cada vez que se reinicia la TV)

Desde Termux, con la TV conectada por `adb connect`:

```
adb shell 'P=$(pm path moe.shizuku.privileged.api | head -1 | cut -d: -f2); S=$(ls $(dirname $P)/lib/*/libshizuku.so 2>/dev/null | head -1); if [ -n "$S" ]; then $S; else sh /storage/emulated/0/Android/data/moe.shizuku.privileged.api/start.sh; fi'
```

## Créditos

`FakeContext` y `Workarounds` están adaptados de
[scrcpy](https://github.com/Genymobile/scrcpy) (Apache 2.0).
La idea del volumen por app viene de
[NoMixer](https://github.com/0NLyM/AppMixer).
La llave `keystore/debug.keystore` es solo de pruebas, para que cada APK nuevo
se instale encima del anterior.
