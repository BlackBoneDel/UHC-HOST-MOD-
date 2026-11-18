import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.api.tasks.bundling.Zip

plugins {
    java
    id("com.gradleup.shadow") version "9.6.1"
    id("info.solidsoft.pitest") version "1.19.0"
}

group = "com.blackbone.uhccore"
version = System.getenv("UHC_VERSION") ?: "1.0.0"

description = "Single-match competitive UHC core for Paper 26.3"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.codemc.io/repository/maven-releases/")
    maven("https://repo.viaversion.com/")
}

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(25)) }
    withSourcesJar()
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.3.build.200-stable")
    compileOnly("com.viaversion:viaversion-api:5.12.2")
    implementation("com.github.retrooper:packetevents-spigot:2.14.0")
    implementation("fr.mrmicky:fastboard:2.2.2")
    implementation("com.zaxxer:HikariCP:7.0.2")
    implementation("org.xerial:sqlite-jdbc:3.53.2.1")
    implementation("com.mysql:mysql-connector-j:9.7.0")

    testImplementation("org.mockbukkit.mockbukkit:mockbukkit-v26.3:4.121.0")
    testImplementation("io.papermc.paper:paper-api:26.3.build.200-stable")
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

val resourcePackZip by tasks.registering(Zip::class) {
    from("resourcepack") { exclude("legacy/**") }
    archiveFileName.set("uhccore-pack.zip")
    destinationDirectory.set(layout.buildDirectory.dir("generated-packs"))
    isReproducibleFileOrder = true
    isPreserveFileTimestamps = false
}

val legacyPackZip by tasks.registering(Zip::class) {
    from("resourcepack/legacy")
    archiveFileName.set("uhccore-legacy-pack.zip")
    destinationDirectory.set(layout.buildDirectory.dir("generated-packs"))
    isReproducibleFileOrder = true
    isPreserveFileTimestamps = false
}

tasks.processResources {
    from(resourcePackZip) { into("packs") }
    from(legacyPackZip) { into("packs") }
    from("licenses") { into("META-INF/licenses") }
    from("NOTICE") { into("META-INF") }
    from("LICENSE") { into("META-INF") }
    filesMatching("paper-plugin.yml") { expand("version" to project.version) }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(25)
}

tasks.test {
    useJUnitPlatform()
    systemProperty("file.encoding", "UTF-8")
}

tasks.named<ShadowJar>("shadowJar") {
    archiveClassifier.set("")
    mergeServiceFiles()
    relocate("com.github.retrooper.packetevents", "com.blackbone.uhccore.libs.packetevents")
    relocate("io.github.retrooper.packetevents", "com.blackbone.uhccore.libs.packetevents.platform")
    relocate("fr.mrmicky.fastboard", "com.blackbone.uhccore.libs.fastboard")
    relocate("com.zaxxer.hikari", "com.blackbone.uhccore.libs.hikari")
    // sqlite-jdbc's native JNI exports and FindClass use org/sqlite hard-coded. Relocating
    // its JNI implementation would break SQLite on every OS; a namespaced driver adapter
    // below is isolated by Paper's plugin classloader while the JNI payload stays intact.
    relocate("com.mysql.cj", "com.blackbone.uhccore.libs.mysql")
    // Paper provides these APIs and their class identities must stay shared with the server.
    exclude("net/kyori/**", "io/netty/**", "org/bukkit/**", "io/papermc/**", "org/slf4j/**")
}

tasks.build { dependsOn(tasks.shadowJar) }

pitest {
    pitestVersion.set("1.21.0")
    junit5PluginVersion.set("1.2.2")
    // Pure business rules only: listeners, GUIs, and network adapters are integration surfaces.
    targetClasses.set(setOf("com.blackbone.uhccore.domain.*"))
    targetTests.set(setOf("com.blackbone.uhccore.domain.*"))
    mutationThreshold.set(60)
    threads.set(4)
    outputFormats.set(setOf("XML", "HTML"))
    timestampedReports.set(false)
}
