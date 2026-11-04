plugins {
    `java-library`
}

description = "Core event model, JFR reading and analysis shared by all JVM Time Machine modules"

val demoJfr = rootProject.layout.projectDirectory.file("demo/build/jfr/demo.jfr")

tasks.test {
    // Phase 2 exit gate: parse the recording produced by the demo app.
    dependsOn(":demo:recordDemo")
    inputs.file(demoJfr).withPropertyName("demoJfr").optional()
    systemProperty("tm.demoJfr", demoJfr.asFile.absolutePath)
}
