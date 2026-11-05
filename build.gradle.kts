plugins {
    java
    `maven-publish`
}

group = "fr.vorace"
version = providers.gradleProperty("version").get()
val paperApi = providers.gradleProperty("paperApi").get()

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://jitpack.io") // vorace-core
}

// vorace-core : le .jar indiqué par -PvoraceCore=<chemin> (CI, compilation locale), sinon la version publiée sur JitPack
val voraceCore = providers.gradleProperty("voraceCore")
val core: Any = if (voraceCore.isPresent) files(voraceCore.get())
    else "com.github.VoracePlugin:vorace-core:${providers.gradleProperty("voraceCoreVersion").get()}"

dependencies {
    compileOnly("io.papermc.paper:paper-api:$paperApi")
    compileOnly(core)
    testImplementation("io.papermc.paper:paper-api:$paperApi")
    testImplementation(core)
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(providers.gradleProperty("jdk").orElse("25").get().toInt())
    withSourcesJar()
    disableAutoTargetJvm()
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 21
}

tasks.processResources {
    val v = project.version.toString()
    inputs.property("version", v)
    filesMatching("plugin.yml") { expand("version" to v) }
}

// Les tests sont des programmes simples lancés par la tâche "tests" (pas JUnit)
tasks.test { enabled = false }

tasks.jar { archiveFileName = "VoraceMob-${project.version}.jar" }

tasks.named<Jar>("sourcesJar") { destinationDirectory = layout.buildDirectory.dir("sources") }

tasks.register<JavaExec>("tests") {
    group = "verification"
    description = "Tests sans serveur du cerveau, des troupeaux et des volants"
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "fr.vorace.mobs.TestMobs"
}

// Dépendance pour les autres plugins (JitPack) : compileOnly("com.github.VoracePlugin:vorace-mob:<version>")
publishing {
    publications {
        create<MavenPublication>("voraceMob") {
            groupId = "com.github.VoracePlugin"
            artifactId = "vorace-mob"
            from(components["java"])
        }
    }
}
