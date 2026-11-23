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

}

tasks.jar {
    archiveFileName.set("freecam.jar")
}

tasks.register<Copy>("installPlugin") {
    group = "application"
    description = "Install the Free Cam plugin into RuneLite's sideloaded plugins directory"
    dependsOn(tasks.jar)
    from(tasks.jar)
    into(File(System.getProperty("user.home"), ".runelite/sideloaded-plugins"))
}
