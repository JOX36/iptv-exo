# JOX3 TV para PC (Windows)

Programa de escritorio hermano de la app Android JOX3 TV. Hecho con
Compose Multiplatform (Kotlin). Es un proyecto aparte: no usa ni cambia la carpeta `app/`.

- Compilar y abrir (con JDK 21 y Gradle): `gradle -p desktop run`
- Instalador: lo genera GitHub Actions (`.github/workflows/desktop.yml`) en Windows.
  Cada cambio que llega a `main` se publica como versión `desktop-v1.0.N`
  (marcada como "no última" para no confundir a la app Android, que busca la última versión).
- Datos del usuario (cuentas): `%APPDATA%\JOX3 TV\accounts.json`.
