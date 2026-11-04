rootProject.name = "jvm-time-machine"

include(
    "tm-core",
    "tm-agent",
    "tm-collector",
    "tm-cli",
    "demo",
)

// tm-ui is a Node/Vite project added in Phase 4; it is not a Gradle module.
