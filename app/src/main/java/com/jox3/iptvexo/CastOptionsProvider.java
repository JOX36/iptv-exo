package com.jox3.iptvexo;

import android.content.Context;
import com.google.android.gms.cast.CastMediaControlIntent;
import com.google.android.gms.cast.framework.CastOptions;
import com.google.android.gms.cast.framework.OptionsProvider;
import com.google.android.gms.cast.framework.SessionProvider;
import java.util.List;

/**
 * Configuración mínima del SDK de Cast: usamos el "Default Media Receiver" de Google
 * (CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID) — una app que Google
 * mantiene y que YA corre en cualquier Chromecast, así que no hace falta publicar ni
 * registrar un receptor propio para reproducir video/HLS.
 *
 * Limitación real de este receptor por defecto: solo pide la URL directamente, sin
 * poder mandarle headers HTTP personalizados (User-Agent, tokens en headers, etc).
 * Si el proveedor IPTV exige esos headers para servir el stream, el Chromecast no
 * podrá reproducirlo aunque el teléfono sí pueda — es una limitación del receptor
 * genérico, no de esta app.
 */
public class CastOptionsProvider implements OptionsProvider {

    @Override
    public CastOptions getCastOptions(Context context) {
        return new CastOptions.Builder()
                .setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
                // No pedir reconexión agresiva en background — evita que el SDK
                // mantenga sockets abiertos innecesarios cuando la app está minimizada.
                .setResumeSavedSession(false)
                .build();
    }

    @Override
    public List<SessionProvider> getAdditionalSessionProviders(Context context) {
        return null;
    }
}
