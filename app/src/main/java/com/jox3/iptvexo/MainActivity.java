package com.jox3.iptvexo;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.splashscreen.SplashScreen;

/**
 * Puerta de entrada (el icono de la app): abre la interfaz que corresponde al equipo.
 * TV / TV Box → interfaz de TV (Compose for TV). Celular o tablet → interfaz del celular.
 *
 * Antes aquí vivía la interfaz web anterior (WebView + player.html); ya no se usaba y se quitó.
 */
public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Pantalla de inicio oficial de Android 12+ (compatible con versiones anteriores)
        SplashScreen.installSplashScreen(this);
        super.onCreate(savedInstanceState);
        Class<?> target = isTvDevice()
            ? com.jox3.iptvexo.tv.TvActivity.class
            : com.jox3.iptvexo.phone.PhoneActivity.class;
        startActivity(new Intent(this, target));
        finish();
    }

    private boolean isTvDevice() {
        boolean leanback = getPackageManager().hasSystemFeature(PackageManager.FEATURE_LEANBACK);
        boolean tvMode = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_TYPE_MASK)
            == Configuration.UI_MODE_TYPE_TELEVISION;
        return leanback || tvMode;
    }
}
