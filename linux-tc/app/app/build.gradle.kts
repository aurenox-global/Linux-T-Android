plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.debi"
    compileSdk = 35
    ndkVersion = "26.3.11579264"

    defaultConfig {
        applicationId = "io.debi.tc"
        minSdk = 28
        // targetSdk 28 a propósito: Android 10+ bloquea exec de binarios en el
        // directorio privado de la app para targetSdk >= 29. proot necesita
        // ejecutar binarios desde /data/data/io.debi/files/... (mismo truco que Termux).
        targetSdk = 28
        versionCode = 23
        versionName = "0.4.9"

        ndk { abiFilters += "arm64-v8a" }

        externalNativeBuild {
            cmake {
                cFlags += listOf("-O2", "-std=c11")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    packaging {
        jniLibs { useLegacyPackaging = true }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("org.apache.commons:commons-compress:1.21")
    implementation("org.tukaani:xz:1.9")
}

// Al terminar el build, renombra el APK a Linux-TC-<versionName>-<buildType>.apk
tasks.matching { it.name == "assembleDebug" || it.name == "assembleRelease" }.configureEach {
    doLast {
        val bt = if (name.contains("Release")) "release" else "debug"
        val dir = layout.buildDirectory.dir("outputs/apk/$bt").get().asFile
        val src = dir.listFiles()?.firstOrNull { it.name == "app-$bt.apk" }
        if (src != null) {
            val dst = File(dir, "Linux-TC-${android.defaultConfig.versionName}-$bt.apk")
            if (dst.exists()) dst.delete()
            src.renameTo(dst)
        }
    }
}


// Sincroniza DOCUMENTACION.md (raíz del proyecto) dentro de los assets para poder
// leerla en la app (Ajustes → Documentación).
val docFile = rootProject.file("../DOCUMENTACION.md")
tasks.register<Copy>("syncDocs") {
    onlyIf { docFile.exists() }
    from(docFile)
    into(layout.projectDirectory.dir("src/main/assets/docs"))
}
tasks.matching { it.name == "preBuild" }.configureEach { dependsOn("syncDocs") }
