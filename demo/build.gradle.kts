plugins {
    application
}

application {
    mainClass.set("io.github.syucel1.tm.demo.DemoApp")
}

val demoSeconds = providers.gradleProperty("demo.seconds").orElse("10")
val demoJfr = layout.buildDirectory.file("jfr/demo.jfr")

// Runs the demo app under JDK Flight Recorder and writes build/jfr/demo.jfr.
// tm-core's tests read this file (the Phase 2 exit gate).
val recordDemo by tasks.registering(JavaExec::class) {
    group = "demo"
    description = "Runs DemoApp with JFR (profile settings) and writes build/jfr/demo.jfr"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set(application.mainClass)
    args(demoSeconds.get())
    val out = demoJfr.get().asFile
    doFirst { out.parentFile.mkdirs() }
    jvmArgs(
        "-Xmx256m",
        "-XX:StartFlightRecording=filename=${out.absolutePath},settings=profile,dumponexit=true",
    )
    inputs.property("seconds", demoSeconds)
    outputs.file(out)
}
