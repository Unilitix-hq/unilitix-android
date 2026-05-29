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

    defaultConfig {
        minSdk = 21
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")

        buildConfigField("String", "SDK_VERSION", "\"1.4.1\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            buildConfigField("String", "API_HOST", "\"unilitix-api-production.up.railway.app\"")
        }
        debug {
            buildConfigField("String", "API_HOST", "\"localhost\"")
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
            version = "1.4.1"

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
