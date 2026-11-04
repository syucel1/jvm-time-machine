plugins {
    `java-library`
}

description = "Java agent: continuous JFR, snapshots, triggers, ring buffer (Phase 6+)"

tasks.jar {
    manifest {
        attributes(
            "Premain-Class" to "io.github.syucel1.tm.agent.TimeMachineAgent",
            "Agent-Class" to "io.github.syucel1.tm.agent.TimeMachineAgent",
        )
    }
}
