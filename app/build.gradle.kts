import java.text.SimpleDateFormat
import java.util.Date

plugins {
    alias(libs.plugins.ksp)
    id("com.android.application")
    id("kotlin-android")
    id("com.google.gms.google-services")
    id("com.google.firebase.crashlytics")
    id("android-app-dependencies")
    id("test-app-dependencies")
    id("jacoco-app-dependencies")
}

repositories {
    mavenCentral()
    google()
}

fun generateGitBuild(): String = try {
    val processBuilder = ProcessBuilder("git", "describe", "--always")
    val output = File.createTempFile("git-build", "")
    processBuilder.redirectOutput(output)
    val process = processBuilder.start()
    process.waitFor()
    output.readText().trim()
} catch (_: Exception) {
    "NoGitSystemAvailable"
}

fun generateGitRemote(): String = try {
    val processBuilder = ProcessBuilder("git", "remote", "get-url", "origin")
    val output = File.createTempFile("git-remote", "")
    processBuilder.redirectOutput(output)
    val process = processBuilder.start()
    process.waitFor()
    output.readText().trim()
} catch (_: Exception) {
    "NoGitSystemAvailable"
}

fun generateDate(): String =
    SimpleDateFormat("yyyy.MM.dd").format(Date())

fun isMaster(): Boolean = !Versions.appVersion.contains("-")

fun gitAvailable(): Boolean = try {
    val processBuilder = ProcessBuilder("git", "--version")
    val output = File.createTempFile("git-version", "")
    processBuilder.redirectOutput(output)
    val process = processBuilder.start()
    process.waitFor()
    output.readText().isNotEmpty()
} catch (_: Exception) {
    false
}

fun allCommitted(): Boolean = try {
    val processBuilder = ProcessBuilder("git", "status", "-s")
    val output = File.createTempFile("git-committed", "")
    processBuilder.redirectOutput(output)
    val process = processBuilder.start()
    process.waitFor()
    output.readText()
        .replace(Regex("""(?m)^\s*(M|A|D|\?\?)\s*.*?\.idea\/codeStyles\/.*?\s*$"""), "")
        .replace(Regex("""(?m)^\s*(\?\?)\s*.*?\s*$"""), "")
        .trim()
        .isEmpty()
} catch (_: Exception) {
    false
}

android {
    namespace = "app.aaps"
    ndkVersion = Versions.ndkVersion

    defaultConfig {
        minSdk = Versions.minSdk
        targetSdk = Versions.targetSdk

        buildConfigField("String", "VERSION", "\"$version\"")
        buildConfigField("String", "BUILDVERSION", "\"${generateGitBuild()}-${generateDate()}\"")
        buildConfigField("String", "REMOTE", "\"${generateGitRemote()}\"")
        buildConfigField("String", "HEAD", "\"${generateGitBuild()}\"")
        buildConfigField("String", "COMMITTED", "\"${allCommitted()}\"")

        testInstrumentationRunner = "app.aaps.runners.InjectedTestRunner"
    }

    // מוסיפים ממד חדש כדי להפריד עם/בלי Eopatch
    flavorDimensions.add("standard")
    flavorDimensions.add("pump")

    productFlavors {
        create("full") {
            isDefault = true
            applicationId = "info.nightscout.androidaps"
            dimension = "standard"
            resValue("string", "app_name", "AAPS")
            versionName = Versions.appVersion
            manifestPlaceholders["appIcon"] = "@mipmap/ic_launcher"
            manifestPlaceholders["appIconRound"] = "@mipmap/ic_launcher_round"
        }
        create("pumpcontrol") {
            applicationId = "info.nightscout.aapspumpcontrol"
            dimension = "standard"
            resValue("string", "app_name", "Pumpcontrol")
            versionName = Versions.appVersion + "-pumpcontrol"
            manifestPlaceholders["appIcon"] = "@mipmap/ic_pumpcontrol"
            manifestPlaceholders["appIconRound"] = "@null"
        }
        create("aapsclient") {
            applicationId = "info.nightscout.aapsclient"
            dimension = "standard"
            resValue("string", "app_name", "AAPSClient")
            versionName = Versions.appVersion + "-aapsclient"
            manifestPlaceholders["appIcon"] = "@mipmap/ic_yellowowl"
            manifestPlaceholders["appIconRound"] = "@mipmap/ic_yellowowl"
        }
        create("aapsclient2") {
            applicationId = "info.nightscout.aapsclient2"
            dimension = "standard"
            resValue("string", "app_name", "AAPSClient2")
            versionName = Versions.appVersion + "-aapsclient"
            manifestPlaceholders["appIcon"] = "@mipmap/ic_blueowl"
            manifestPlaceholders["appIconRound"] = "@mipmap/ic_blueowl"
        }

        // Flavors חדשים לממד pump
        create("withEopatch") {
            dimension = "pump"
            buildConfigField("boolean", "WITH_EOPATCH", "true")
        }
        create("noEopatch") {
            dimension = "pump"
            buildConfigField("boolean", "WITH_EOPATCH", "false")
        }
    }

    useLibrary("org.apache.http.legacy")

    buildFeatures {
        dataBinding = true
        buildConfig = true
    }
}

allprojects {
    repositories {}
}

dependencies {
    implementation(project(":shared:impl"))
    implementation(project(":core:data"))
    implementation(project(":core:objects"))
    implementation(project(":core:graph"))
    implementation(project(":core:graphview"))
    implementation(project(":core:interfaces"))
    implementation(project(":core:keys"))
    implementation(project(":core:libraries"))
    implementation(project(":core:nssdk"))
    implementation(project(":core:utils"))
    implementation(project(":core:ui"))
    implementation(project(":core:validators"))
    implementation(project(":ui"))
    implementation(project(":plugins:aps"))
    implementation(project(":plugins:automation"))
    implementation(project(":plugins:configuration"))
    implementation(project(":plugins:constraints"))
    implementation(project(":plugins:insulin"))
    implementation(project(":plugins:main"))
    implementation(project(":plugins:sensitivity"))
    implementation(project(":plugins:smoothing"))
    implementation(project(":plugins:source"))
    implementation(project(":plugins:sync"))
    implementation(project(":implementation"))
    implementation(project(":database:impl"))
    implementation(project(":database:persistence"))
    implementation(project(":pump:combov2"))
    implementation(project(":pump:dana"))
    implementation(project(":pump:danars"))
    implementation(project(":pump:danar"))
    implementation(project(":pump:diaconn"))

    // Eopatch רק אם בוחרים flavor עם Eopatch
    add("withEopatchImplementation", project(":pump:eopatch"))

    implementation(project(":pump:medtrum"))
    implementation(project(":pump:equil"))
    implementation(project(":pump:insight"))
    implementation(project(":pump:medtronic"))
    implementation(project(":pump:pump-common"))
    implementation(project(":pump:omnipod-common"))
    implementation(project(":pump:omnipod-eros"))
    implementation(project(":pump:omnipod-dash"))
    implementation(project(":pump:rileylink"))
    implementation(project(":pump:virtual"))
    implementation(project(":workflow"))

    testImplementation(project(":shared:tests"))
    androidTestImplementation(project(":shared:tests"))
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.org.skyscreamer.jsonassert)

    kspAndroidTest(libs.com.google.dagger.android.processor)
    ksp(libs.com.google.dagger.android.processor)
    ksp(libs.com.google.dagger.compiler)

    api(libs.com.uber.rxdogtag2.rxdogtag)
    api(libs.com.google.firebase.config)
}

println("-------------------")
println("isMaster: ${isMaster()}")
println("gitAvailable: ${gitAvailable()}")
println("allCommitted: ${allCommitted()}")
println("-------------------")

if (!gitAvailable()) {
    throw GradleException("GIT system is not available. On Windows try to run Android Studio as Administrator. Check if GIT is installed and Studio has permissions to use it")
}
if (isMaster() && !allCommitted()) {
    throw GradleException("There are uncommitted changes. Clone sources again as described in wiki and do not allow gradle update")
}

