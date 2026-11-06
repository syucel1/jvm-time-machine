plugins {
    application
}

description = "Command-line tool: summarise, query and serve .jfr recordings"

dependencies {
    implementation(project(":tm-core"))
}

application {
    mainClass.set("io.github.syucel1.tm.cli.Main")
    applicationName = "tm-cli"
}

// Resolve relative file arguments from the repository root, not from tm-cli/.
tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
    standardInput = System.`in`
}

val demoJfr = rootProject.layout.projectDirectory.file("demo/build/jfr/demo.jfr")

tasks.test {
    // The API tests serve demo.jfr (Phase 3 exit gate).
    dependsOn(":demo:recordDemo")
    systemProperty("tm.demoJfr", demoJfr.asFile.absolutePath)
}
