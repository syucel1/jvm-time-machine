package io.github.syucel1.tm.agent;

import java.lang.instrument.Instrumentation;

/**
 * Entry point for {@code -javaagent:tm-agent.jar} and runtime attach.
 *
 * <p>Placeholder until Phase 6 (Always-on agent). It only logs that it was loaded, so the jar
 * can already be attached to an application to check the plumbing.
 */
public final class TimeMachineAgent {

    private TimeMachineAgent() {
    }

    public static void premain(String args, Instrumentation inst) {
        start(args, "premain");
    }

    public static void agentmain(String args, Instrumentation inst) {
        start(args, "agentmain");
    }

    private static void start(String args, String mode) {
        System.err.printf("[tm-agent] loaded via %s (args=%s); recording starts in Phase 6%n", mode, args);
    }
}
