package com.jox3.iptvexo;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.view.animation.AnimationSet;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.ScaleAnimation;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;

@SuppressLint("CustomSplashScreen")
public class SplashActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Pantalla completa sin status bar
        getWindow().setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        );
        setContentView(R.layout.activity_splash);

        TextView logo    = findViewById(R.id.splash_logo);
        TextView tagline = findViewById(R.id.splash_tagline);

        // Animación del logo — fade + escala
        AnimationSet logoAnim = new AnimationSet(true);
        AlphaAnimation fadeIn = new AlphaAnimation(0f, 1f);
        fadeIn.setDuration(900);
        ScaleAnimation scaleIn = new ScaleAnimation(
            0.8f, 1f, 0.8f, 1f,
            Animation.RELATIVE_TO_SELF, 0.5f,
            Animation.RELATIVE_TO_SELF, 0.5f
        );
        scaleIn.setDuration(900);
        scaleIn.setInterpolator(new DecelerateInterpolator());
        logoAnim.addAnimation(fadeIn);
        logoAnim.addAnimation(scaleIn);
        logo.startAnimation(logoAnim);

        // Tagline aparece después
        tagline.setVisibility(View.INVISIBLE);
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            AlphaAnimation tagAnim = new AlphaAnimation(0f, 1f);
            tagAnim.setDuration(600);
            tagline.setVisibility(View.VISIBLE);
            tagline.startAnimation(tagAnim);
        }, 700);

        // Ir a MainActivity después de 2.2 segundos
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            AlphaAnimation fadeOut = new AlphaAnimation(1f, 0f);
            fadeOut.setDuration(400);
            fadeOut.setAnimationListener(new Animation.AnimationListener() {
                public void onAnimationStart(Animation a) {}
                public void onAnimationRepeat(Animation a) {}
                public void onAnimationEnd(Animation a) {
                    startActivity(new Intent(SplashActivity.this, MainActivity.class));
                    finish();
                    overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
                }
            });
            findViewById(R.id.splash_root).startAnimation(fadeOut);
        }, 2200);
    }
}
