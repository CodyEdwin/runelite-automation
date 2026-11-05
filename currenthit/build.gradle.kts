plugins {
    java
}

dependencies {
    compileOnly(project(":client"))
    compileOnly("net.runelite:runelite-api:${project.version}")
    compileOnly(libs.guice.core)
    compileOnly(libs.javax.inject)
    compileOnly(libs.slf4j.api)
    compileOnly(libs.findbugs)

    testImplementation(libs.junit)
    testImplementation(project(":client"))
}

tasks.jar {
    archiveFileName.set("currenthit.jar")
}

tasks.register<Copy>("installPlugin") {
    group = "application"
    description = "Install the Current Hit plugin into RuneLite's sideloaded plugins directory"
    dependsOn(tasks.jar)
    from(tasks.jar)
    into(File(System.getProperty("user.home"), ".runelite/sideloaded-plugins"))
}
