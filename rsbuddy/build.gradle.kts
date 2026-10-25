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

    testImplementation(libs.junit)
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
