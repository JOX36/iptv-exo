import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.1.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.21"
    id("org.jetbrains.compose") version "1.8.2"
}

group = "com.jox3.tvdesktop"

// Versión del programa: en CI llega como -PappVersion=1.0.N (N = número de compilación).
// El instalador MSI exige el formato MAYOR.MENOR.COMPILACIÓN.
val appVersion: String = (findProperty("appVersion") as String?)?.takeIf { it.isNotBlank() } ?: "1.0.0"
version = appVersion

kotlin {
    jvmToolchain(21)
}

// Token de TMDB: llega del secreto TMDB_TOKEN de GitHub Actions al compilar (igual que en
// la app Android). Nunca se escribe en el código ni en el repositorio: se genera un archivo
// temporal dentro de build/.
val tmdbToken: String = (System.getenv("TMDB_TOKEN") ?: "").trim().filter { it.isLetterOrDigit() || it in "._-" }
val generateSecrets by tasks.registering {
    val out = layout.buildDirectory.dir("generated/secrets/kotlin")
    val token = tmdbToken
    inputs.property("tmdbTokenHash", token.hashCode())
    outputs.dir(out)
    doLast {
        val f = out.get().asFile.resolve("com/jox3/tvdesktop/BuildSecrets.kt")
        f.parentFile.mkdirs()
        f.writeText(
            "package com.jox3.tvdesktop\n\n" +
                "/** Generado al compilar (no editar). */\n" +
                "internal object BuildSecrets {\n    const val TMDB_TOKEN = \"" + token + "\"\n}\n"
        )
    }
}
kotlin.sourceSets.named("main") { kotlin.srcDir(generateSecrets) }

tasks.test {
    useJUnitPlatform()
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.json:json:20240303")
    implementation("net.java.dev.jna:jna-platform:5.15.0") // cifrado de contraseñas con Windows (DPAPI)
    implementation("uk.co.caprica:vlcj:4.7.3")             // reproductor: libVLC desde Kotlin (rama 4.7, API estable)
    implementation("com.google.code.gson:gson:2.11.0")    // lectura por partes de listas JSON enormes

    testImplementation(kotlin("test"))
}

compose.desktop {
    application {
        mainClass = "com.jox3.tvdesktop.MainKt"
        // El programa conoce su propia versión (sirve para las actualizaciones de la etapa 5).
        jvmArgs += listOf("-Djox3.version=$appVersion", "-Dfile.encoding=UTF-8")

        nativeDistributions {
            targetFormats(TargetFormat.Msi)
            packageName = "JOX3 TV"
            packageVersion = appVersion
            description = "JOX3 TV para Windows"
            vendor = "JOX36"
            copyright = "JOX36"

            // Partes de Java que se incluyen dentro del instalador (no hace falta tener Java instalado).
            // jdk.crypto.ec: conexiones HTTPS de muchos servidores. jdk.localedata: fechas en español.
            // java.xml: guía de programación de internet (XMLTV).
            modules("java.naming", "java.sql", "java.management", "java.xml", "jdk.unsupported", "jdk.crypto.ec", "jdk.charsets", "jdk.localedata")

            // VLC va DENTRO del instalador (carpeta vlc/windows/vlc, la prepara CI): el usuario
            // no instala nada aparte y siempre tiene la versión probada. En el programa queda
            // en la carpeta de recursos (propiedad compose.application.resources.dir).
            val vlcDir = project.layout.projectDirectory.dir("vlc")
            if (vlcDir.asFile.exists()) appResourcesRootDir.set(vlcDir)

            windows {
                iconFile.set(project.file("icons/icon.ico"))
                menuGroup = "JOX3 TV"
                shortcut = true          // acceso directo en el escritorio
                dirChooser = true        // permite elegir carpeta al instalar
                perUserInstall = true    // se instala sin pedir permisos de administrador
                // NUNCA cambiar: así cada versión nueva reemplaza a la anterior en vez de duplicarse.
                upgradeUuid = "2c7475dc-bcd9-436c-8ac4-d464e7fbb539"
            }
        }
    }
}
