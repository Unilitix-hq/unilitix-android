plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)    // required for Room code generation
    id("maven-publish")
    id("signing")
}

android {
    namespace = "io.unilitix.sdk"
    compileSdk = 36
    // Native crash capture (API 21-29) is built from src/main/cpp. The NDK the
    // published AAR is built with; override locally with
    // -Punilitix.ndkVersion=<version> (or in ~/.gradle/gradle.properties).
    ndkVersion = (findProperty("unilitix.ndkVersion") as String?) ?: "27.1.12297006"

    defaultConfig {
        minSdk = 21
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")

        buildConfigField("String", "SDK_VERSION", "\"1.10.0\"")

        ndk {
            // Every ABI the NDK still supports; apps keep only the ones they ship.
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.fragment)
    implementation(libs.coroutines.android)
    implementation(libs.coroutines.core)

    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.workmanager)

    implementation(libs.lifecycle.process)
    implementation(libs.lifecycle.runtime.ktx)

    implementation(libs.startup.runtime)
    implementation(libs.security.crypto)

    // Jetpack Compose masking in session replay. compileOnly: never added to
    // the app; the SDK only touches Compose after finding it at runtime
    // (capture/ComposeHost.kt). No Compose compiler: the SDK declares no
    // @Composable code, only plain Modifier extensions. 1.6.8 is old enough
    // for Kotlin 1.9 to read and newer than every API used (all Compose UI
    // 1.0+); apps run it against their own Compose version.
    compileOnly(libs.compose.ui)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

publishing {
    publications {
        create<MavenPublication>("release") {
            groupId = "com.unilitix"
            artifactId = "unilitix-android"
            version = "1.10.0"

            afterEvaluate {
                from(components["release"])
            }

            pom {
                name.set("Unilitix Android SDK")
                description.set("African-first mobile UX analytics for Android. Track sessions, screens, events and crashes.")
                url.set("https://unilitix.com")

                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/licenses/MIT")
                    }
                }

                developers {
                    developer {
                        id.set("unilitix")
                        name.set("Unilitix")
                        email.set("oluwatosin@unilitix.com")
                    }
                }

                scm {
                    connection.set("scm:git:github.com/Unilitix-hq/unilitix-android.git")
                    developerConnection.set("scm:git:ssh://github.com/Unilitix-hq/unilitix-android.git")
                    url.set("https://github.com/Unilitix-hq/unilitix-android")
                }
            }
        }
    }
}

signing {
    val signingKey = System.getenv("GPG_SECRET_KEY")
    val signingPassword = System.getenv("GPG_PASSPHRASE")
    useInMemoryPgpKeys(signingKey, signingPassword)
    sign(publishing.publications["release"])
    isRequired = true
}
