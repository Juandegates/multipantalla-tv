package com.juandegates.multipantalla;

import android.view.Surface;

// Servicio que corre con permisos de shell (vía Shizuku).
interface IMultiService {

    // Código reservado por Shizuku para cerrar el servicio.
    void destroy() = 16777114;

    // Crea una pantalla virtual que dibuja dentro de 'surface'. Devuelve su id o -1.
    int createDisplay(String name, int width, int height, int dpi, in Surface surface) = 1;

    // Cambia (o quita con null) la superficie donde dibuja la pantalla.
    void setDisplaySurface(int displayId, in Surface surface) = 2;

    void releaseDisplay(int displayId) = 3;

    // Ejecuta un comando de shell y devuelve su salida.
    String exec(String command) = 4;

    // Manda una tecla a una pantalla concreta.
    boolean injectKey(int displayId, int action, int keyCode, int repeatCount, int metaState) = 5;

    // Último error interno, para mostrarlo en pantalla.
    String lastError() = 6;
}
