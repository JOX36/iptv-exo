// Proyecto de escritorio (Windows) de JOX3 TV.
// Es un build de Gradle independiente: no toca la app Android (carpeta app/).
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
    }
}

rootProject.name = "jox3-tv-desktop"
