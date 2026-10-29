plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "app.floatingfocus"
    compileSdk = 36
    defaultConfig {
        applicationId = "app.floatingfocus"
        minSdk = 29
        targetSdk = 36
        versionCode = 4
        versionName = "0.3.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    androidResources { noCompress += "m4a" }
    sourceSets.getByName("main").assets.srcDir(rootProject.file("licenses"))
    // Unlicensed user-supplied recordings stay local and are never included in Release or Git.
    sourceSets.getByName("debug").assets.srcDir(rootProject.file(".tools/c4-local-assets"))
    // Optional local-build override; Android Studio retains its standard debug signing.
    providers.gradleProperty("workspaceDebugKeystore").orNull?.let { keyPath ->
        signingConfigs.getByName("debug") { storeFile = rootProject.file(keyPath) }
    }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.05.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel:2.9.0")
    implementation("androidx.savedstate:savedstate:1.3.0")
    implementation("androidx.core:core-ktx:1.16.0")
    testImplementation("junit:junit:4.13.2")
}
