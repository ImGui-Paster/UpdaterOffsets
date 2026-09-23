
import java.util.Properties
import java.io.FileInputStream

plugins {
    id("com.android.application") version "8.7.1" 
 
    
}

val keystorePropsFile = rootProject.file("release.properties")
val keystoreProps = Properties()

if (keystorePropsFile.exists()) {
    keystoreProps.load(FileInputStream(keystorePropsFile))
}

val hasValidSigningProps = keystorePropsFile.exists().also { exists ->
    if (exists) {
        FileInputStream(keystorePropsFile).use { keystoreProps.load(it) }
    }
}.let {
    listOf("storeFile", "storePassword", 
            "keyAlias", "keyPassword").all { key ->
        keystoreProps[key] != null
    }
}


android {
    namespace = "com.offset.updater"
    compileSdk = 35 
    
    // disable linter
    lint {
        checkReleaseBuilds = false
    }
        
    signingConfigs {
        if (hasValidSigningProps) {
            create("release") {
                storeFile = rootProject.file(keystoreProps["storeFile"] as String)
                storePassword = keystoreProps["storePassword"] as String
                keyAlias = keystoreProps["keyAlias"] as String
                keyPassword = keystoreProps["keyPassword"] as String
            }
        }
    }

    defaultConfig {
        applicationId = "com.offset.updater"
        minSdk = 21 
        targetSdk = 35  
        versionCode = 1
        versionName = "1.0"
        
        vectorDrawables { 
            useSupportLibrary = true
        }
    }
    
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17 
        targetCompatibility = JavaVersion.VERSION_17 
    }

    buildTypes {
        release {
            if (hasValidSigningProps) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        viewBinding = true
    }

    packaging {
        resources {
            resources.excludes.add("/META-INF/{AL2.0,LGPL2.1}")
        }
    }
}

tasks.withType<JavaCompile> {
    options.compilerArgs.add("-Xlint:deprecation")
}

 


dependencies {


    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.9.0")
    implementation("androidx.startup:startup-runtime:1.1.1")
    implementation("androidx.interpolator:interpolator:1.0.0")
}

// AIDE workaround: keep a copy of the Gradle-produced APK in a writable,
// project-local directory. This avoids relying on AIDE's legacy /main.apk
// temporary packaging path. AIDE Pro's "Redefine APK build path" can point
// at this directory when enabled.
val aideOutputDir = rootProject.layout.projectDirectory.dir("aide-output")

val copyDebugApkForAide = tasks.register<Copy>("copyDebugApkForAide") {
    dependsOn("assembleDebug")
    from(layout.buildDirectory.dir("outputs/apk/debug")) {
        include("app-debug.apk")
    }
    into(aideOutputDir)
    rename("app-debug.apk", "main.apk")
}

tasks.register<Copy>("copyReleaseApkForAide") {
    dependsOn("assembleRelease")
    from(layout.buildDirectory.dir("outputs/apk/release")) {
        include("app-release.apk")
    }
    into(aideOutputDir)
    rename("app-release.apk", "main-release.apk")
}

