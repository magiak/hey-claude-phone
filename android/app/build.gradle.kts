import java.net.URI
import java.util.zip.ZipInputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Small English model: "claude" is an English word, the Czech small model does not know it.
val voskModel = "vosk-model-small-en-us-0.15"
val voskAssets = rootProject.layout.projectDirectory.dir("vosk-assets")

android {
    namespace = "cz.magiak.asistent"
    compileSdk = 36

    defaultConfig {
        applicationId = "cz.magiak.asistent"
        // Claude app itself reportedly needs Android 8, so nothing lower is worth supporting.
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.2"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    sourceSets["main"].assets.srcDir(voskAssets)

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("com.alphacephei:vosk-android:0.3.75")
}

// The model (~40 MB) stays out of git; it is fetched once from the Vosk site.
// The uuid file tells StorageService on the phone when to re-copy the model.
val downloadVoskModel by tasks.registering {
    val target = voskAssets.dir("model").asFile
    outputs.dir(target)
    onlyIf { !target.resolve("uuid").exists() }
    doLast {
        target.deleteRecursively()
        val url = "https://alphacephei.com/vosk/models/$voskModel.zip"
        logger.lifecycle("Stahuju $url")
        ZipInputStream(URI(url).toURL().openStream().buffered()).use { zip ->
            generateSequence { zip.nextEntry }.forEach { entry ->
                val relative = entry.name.substringAfter('/')
                if (relative.isEmpty()) return@forEach
                val out = target.resolve(relative)
                if (entry.isDirectory) out.mkdirs() else {
                    out.parentFile.mkdirs()
                    out.outputStream().use { zip.copyTo(it) }
                }
            }
        }
        target.resolve("uuid").writeText(voskModel)
    }
}
tasks.named("preBuild") { dependsOn(downloadVoskModel) }
