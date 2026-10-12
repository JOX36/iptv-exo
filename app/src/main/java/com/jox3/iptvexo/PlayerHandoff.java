package com.jox3.iptvexo;

/**
 * Pasa la lista de canales/episodios de MainActivity a PlayerActivity por memoria.
 *
 * Antes viajaba como extra del Intent, pero Android limita esos datos a ~1 MB en total:
 * con categorías grandes (cientos o miles de canales) la app se cerraba con
 * TransactionTooLargeException al abrir el reproductor.
 */
public final class PlayerHandoff {

    private static String channelsJson;

    private PlayerHandoff() {}

    public static synchronized void put(String json) {
        channelsJson = json;
    }

    /** Devuelve la lista guardada (se conserva para recreaciones del reproductor). */
    public static synchronized String get() {
        return channelsJson;
    }
}
