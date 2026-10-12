package com.jox3.iptvexo;

import android.annotation.SuppressLint;

import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import okhttp3.OkHttpClient;

/**
 * Cliente HTTP único para toda la app.
 *
 * Antes cada canal, reintento y petición creaba su propio OkHttpClient: cada uno con su
 * pool de conexiones y su handshake TLS, lo que hacía más lento el cambio de canal y
 * gastaba memoria. Ahora todos comparten el mismo pool (conexiones keep-alive reutilizadas).
 *
 * Nota: se mantiene la aceptación de certificados no válidos porque muchos proveedores
 * IPTV usan certificados vencidos o autofirmados. Está centralizado aquí para poder
 * endurecerlo más adelante en un solo sitio.
 */
public final class NetClient {

    /** User-Agent para la API (player_api.php) y la interfaz. */
    public static final String API_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/114.0.0.0 Mobile Safari/537.36";

    /** User-Agent para los streams: muchos paneles IPTV solo dejan pasar reproductores. */
    public static final String PLAYER_USER_AGENT = "VLC/3.0.18 LibVLC/3.0.18";

    /** Hilos para peticiones de red: limitado para no crear un hilo por petición. */
    public static final ExecutorService IO = Executors.newFixedThreadPool(4);

    private static volatile OkHttpClient base;
    private static volatile OkHttpClient player;

    private NetClient() {}

    /** Cliente para la API y descargas pequeñas. */
    public static OkHttpClient api() {
        if (base == null) {
            synchronized (NetClient.class) {
                if (base == null) base = buildBase();
            }
        }
        return base;
    }

    /** Cliente para ExoPlayer: mismo pool de conexiones, User-Agent de reproductor. */
    public static OkHttpClient player() {
        if (player == null) {
            synchronized (NetClient.class) {
                if (player == null) {
                    player = api().newBuilder()
                        .readTimeout(20, TimeUnit.SECONDS)
                        .addInterceptor(chain -> chain.proceed(
                            chain.request().newBuilder()
                                .header("User-Agent", PLAYER_USER_AGENT)
                                .build()))
                        .build();
                }
            }
        }
        return player;
    }

    @SuppressLint({"TrustAllX509TrustManager", "CustomX509TrustManager"})
    private static OkHttpClient buildBase() {
        OkHttpClient.Builder b = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true);
        try {
            X509TrustManager tm = new X509TrustManager() {
                public void checkClientTrusted(X509Certificate[] c, String a) {}
                public void checkServerTrusted(X509Certificate[] c, String a) {}
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            };
            SSLContext sc = SSLContext.getInstance("TLS");
            sc.init(null, new TrustManager[]{tm}, new SecureRandom());
            b.sslSocketFactory(sc.getSocketFactory(), tm).hostnameVerifier((h, s) -> true);
        } catch (Exception ignored) {
            // Si falla la configuración permisiva, queda la validación estándar
        }
        return b.build();
    }
}
