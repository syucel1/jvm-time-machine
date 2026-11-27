plugins {
    application
}

description = "Command-line tool: summarise, query and serve .jfr recordings, with the timeline UI"

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

// ---------------------------------------------------------------- tm-ui (Phase 4)
//
// tm-ui is a Vite + React project, built with npm and bundled into tm-cli's classpath under
// /ui, where TmServer serves it. Needs Node.js 20.19+ on the PATH. Build without it (API only)
// with -Pui.skip=true; tm-cli then serves the API index page at /.

val uiDir = rootProject.layout.projectDirectory.dir("tm-ui")
val skipUi = providers.gradleProperty("ui.skip").map { it != "false" }.getOrElse(false)
val npm = if (System.getProperty("os.name").lowercase().contains("windows")) "npm.cmd" else "npm"

val uiSources = files(
    uiDir.dir("src"),
    uiDir.dir("public"),
    uiDir.file("index.html"),
    uiDir.file("package.json"),
    uiDir.file("package-lock.json"),
    uiDir.file("tsconfig.json"),
    uiDir.file("vite.config.ts"),
)

val npmCi by tasks.registering(Exec::class) {
    group = "ui"
    description = "Installs tm-ui dependencies from package-lock.json"
    onlyIf { !skipUi }
    workingDir = uiDir.asFile
    commandLine(npm, "ci", "--no-audit", "--no-fund")
    inputs.files(uiDir.file("package.json"), uiDir.file("package-lock.json"))
    // npm writes this file on every install; cheaper to track than all of node_modules.
    outputs.file(uiDir.file("node_modules/.package-lock.json"))
}

val buildUi by tasks.registering(Exec::class) {
    group = "ui"
    description = "Type-checks and builds tm-ui into tm-ui/dist"
    onlyIf { !skipUi }
    dependsOn(npmCi)
    workingDir = uiDir.asFile
    commandLine(npm, "run", "build")
    inputs.files(uiSources)
    outputs.dir(uiDir.dir("dist"))
}

val testUi by tasks.registering(Exec::class) {
    group = "ui"
    description = "Runs the tm-ui unit tests (vitest)"
    onlyIf { !skipUi }
    dependsOn(npmCi)
    workingDir = uiDir.asFile
    commandLine(npm, "test")
    inputs.files(uiSources)
    val stamp = layout.buildDirectory.file("ui-test.ok")
    outputs.file(stamp)
    doLast { stamp.get().asFile.writeText("ok\n") }
}

tasks.processResources {
    if (!skipUi) {
        from(buildUi) {
            into("ui")
        }
    }
}

tasks.check {
    dependsOn(testUi)
}
