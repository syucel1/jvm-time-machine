plugins {
    application
}

description = "Command-line tool: inspect .jfr files (Phase 2), serve the UI (Phase 3+)"

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
}
