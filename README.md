# MultiPantalla TV

App personal para Android TV que divide la pantalla en 4 cuadros y abre una app
distinta en cada uno (YouTube, Twitch, etc.), con control y sonido por cuadro.

## Cómo funciona

- Cada cuadro es una **pantalla virtual** de Android (1280×720) que la app dibuja
  dentro de su propia cuadrícula.
- Las pantallas se crean con permisos de shell a través de **Shizuku**, con la
  misma técnica que usa scrcpy para `--new-display`.
- El sonido de cada app se silencia con `appops PLAY_AUDIO`.

## Controles

- **Flechas:** elegir cuadro.
- **OK:** elegir app (cuadro vacío) o controlar la app del cuadro.
- **OK largo / Menú:** opciones del cuadro (silenciar, cambiar, cerrar, diagnóstico).
- **En modo control:** todas las teclas van a esa app. **Mantén Atrás** para salir.

## Descarga

Último APK (siempre el mismo link):
https://github.com/Juandegates/multipantalla-tv/releases/download/latest/MultiPantallaTV.apk

## Requisitos en la TV

1. Shizuku instalado e iniciado (por ADB desde Termux).
2. Depuración inalámbrica activada en Opciones para desarrolladores.

## Créditos

`FakeContext` y `Workarounds` están adaptados de
[scrcpy](https://github.com/Genymobile/scrcpy) (Apache 2.0).
La llave `keystore/debug.keystore` es solo de pruebas, para que cada APK nuevo
se instale encima del anterior.
