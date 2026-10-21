package com.juandegates.multipantalla;

// Servicio que corre con permisos de shell (vía Shizuku).
interface IMultiService {

    // Código reservado por Shizuku para cerrar el servicio.
    void destroy() = 16777114;

    // Ejecuta un comando de shell y devuelve su salida.
    String exec(String command) = 1;

    // Abre una app como ventana libre con un tamaño exacto.
    // Devuelve "ok:<taskId>" o "error:<detalle>".
    String launchFreeform(String component, int left, int top, int right, int bottom) = 2;

    // Id de la tarea (ventana) de una app, o -1.
    int findTask(String packageName) = 3;

    // Pasa el control del mando a esa ventana.
    boolean focusTask(int taskId) = 4;

    boolean resizeTask(int taskId, int left, int top, int right, int bottom) = 5;

    // Volumen propio de la app: 0.0 a 1.0. Se vuelve a aplicar solo a reproductores nuevos.
    String setAppVolume(String packageName, float volume) = 6;

    // "toggle", "play" o "pause". Devuelve qué método funcionó.
    String media(String packageName, String action) = 7;

    // Manda una tecla a una pantalla (0 = principal, va a la ventana activa).
    boolean injectKey(int displayId, int action, int keyCode, int repeatCount, int metaState) = 8;

    String lastError() = 9;

    // Diagnóstico de audio: reproductores activos y su app.
    String audioInfo() = 10;

    // Reparto de audio: todas las apps lo reciben y ninguna se entera de que "lo perdió".
    // Se quita solo si este servicio muere. Devuelve "ok" o el error.
    String setShareAudio(boolean on) = 11;

    // Lugar real de una ventana: {izq, arriba, der, abajo} o vacío.
    int[] taskBounds(int taskId) = 12;
}
