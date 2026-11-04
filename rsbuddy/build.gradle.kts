plugins {
    java
}

dependencies {
    // These classes are supplied by the RuneLite client at runtime. Do not bundle them into the plugin jar.
    compileOnly(project(":client"))
    compileOnly("net.runelite:runelite-api:${project.version}")
    compileOnly(libs.guice.core)
    compileOnly(libs.javax.inject)
    compileOnly(libs.slf4j.api)
    compileOnly(libs.findbugs)
    // JNA ships with the RuneLite client and is used for X11/XTest hardware input.
    compileOnly(libs.jna.core)
    compileOnly(libs.jna.platform)

    testImplementation(libs.junit)
    testImplementation(project(":client"))
}

tasks.jar {
    archiveFileName.set("rsbuddy.jar")
}

tasks.register<Copy>("installPlugin") {
    group = "application"
    description = "Install the RSBuddy plugin into RuneLite's sideloaded plugins directory"
    dependsOn(tasks.jar)
    from(tasks.jar)
    into(File(System.getProperty("user.home"), ".runelite/sideloaded-plugins"))
}

// Standalone routine jar: ./gradlew :rsbuddy:routineJar -Proutine=OakChopperScript
// (simple name in net.runelite.rsbuddy.routines, or a fully qualified class).
// Packs the class, its nested classes and META-INF/services so the RSBuddy
// panel loads it from ~/.runelite/rsbuddy/routines. Add -PinstallRoutine to
// copy it straight into that folder.
val routineJar = tasks.register<Jar>("routineJar") {
    group = "rsbuddy"
    description = "Package one routine as a loadable jar (-Proutine=ClassName)"
    val routineName = (findProperty("routine") as String?) ?: "ExampleRoutine"
    val fqcn = if (routineName.contains('.')) routineName else "net.runelite.rsbuddy.routines.$routineName"
    val path = fqcn.replace('.', '/')
    val simple = fqcn.substringAfterLast('.')
    val servicesDir = layout.buildDirectory.dir("routine-services/$simple")
    dependsOn(tasks.classes)
    archiveFileName.set("$simple.jar")
    destinationDirectory.set(layout.buildDirectory.dir("routine-jars"))
    from(sourceSets.main.get().output.classesDirs) {
        include("$path.class", "$path\$*.class")
    }
    from(servicesDir)
    manifest { attributes("RSBuddy-Routine" to fqcn) }
    doFirst {
        val services = servicesDir.get().file("META-INF/services/net.runelite.rsbuddy.routine.Routine").asFile
        services.parentFile.mkdirs()
        services.writeText(fqcn + "\n")
    }
    doLast {
        if (project.hasProperty("installRoutine")) {
            val target = File(System.getProperty("user.home"), ".runelite/rsbuddy/routines")
            target.mkdirs()
            archiveFile.get().asFile.copyTo(File(target, "$simple.jar"), overwrite = true)
            println("Installed $simple.jar into $target")
        }
    }
}
