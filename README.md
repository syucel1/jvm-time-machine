# JVM Time Machine

> Rewind a running JVM to any moment in the last 30 minutes and see exactly what it was doing.

[![Design](https://img.shields.io/badge/📐_Design-view_page-3b6cf6?style=for-the-badge)](https://syucel1.github.io/jvm-time-machine/DESIGN.html)
[![Roadmap](https://img.shields.io/badge/🗺️_Roadmap-view_page-8b5cf6?style=for-the-badge)](https://syucel1.github.io/jvm-time-machine/ROADMAP.html)
[![Status](https://img.shields.io/badge/status-phase_2_foundations-1f9d63?style=for-the-badge)](ROADMAP.md)
[![CI](https://github.com/syucel1/jvm-time-machine/actions/workflows/ci.yml/badge.svg)](https://github.com/syucel1/jvm-time-machine/actions/workflows/ci.yml)

**Status:** Phase 2 (Foundations) done: build, CI, demo app and the core JFR event model. Read the [design](https://syucel1.github.io/jvm-time-machine/DESIGN.html)
and [roadmap](https://syucel1.github.io/jvm-time-machine/ROADMAP.html) as web pages, or the [roadmap in Markdown](ROADMAP.md).

---

## What is it?

JVM Time Machine is a free, open-source, always-on "dashcam" for Java applications. It builds on
**JDK Flight Recorder (JFR)** and adds what JFR alone doesn't give you:

| Feature | What it means |
| --- | --- |
| **Dashcam buffer** | A rolling 30-minute recording that is frozen automatically when an incident trigger fires |
| **Timeline scrubber** | Drag across time and see threads, hot methods, heap, GC, locks and in-flight requests at any second |
| **Time diff** | "What changed between 10:00 and 10:05?" as a differential flame graph and event deltas |
| **Request reconstruction** | Paste a trace ID and see that request's CPU, lock waits, SQL calls, GC pauses and exceptions |
| **Incident reports** | A readable summary of every incident, optionally written by an LLM |

It is meant to run in **production** with a steady-state overhead of **2% CPU or less**.

## Why?

- Production incidents are usually over before anyone can attach a profiler.
- JFR records the right data but has no request view, no time diff and no automatic incident capture.
- Commercial profilers (JProfiler, YourKit) are excellent for development, but they aren't designed to run always-on in production.

## Architecture at a glance

```
┌──────────────── Target JVM ────────────────┐
│ tm-agent                                   │
│  JFR recorder · Profiler bridge ·          │
│  Snapshotter · Context capture             │
│        │                                   │
│        ▼                                   │
│  Trigger engine ─► Ring buffer ─► Shipper ─┼──► tm-collector
└────────────────────────────────────────────┘     Ingest ► Event store ► Query API
                                                            │      ▲
                                                            ▼      │
                                                         Analyzer ─┘
                                                            │
             tm-ui (browser) · tm-cli · JMC/JProfiler export · Alerts
```

| Module | Role | Status |
| --- | --- | --- |
| `tm-core` | Event model, JFR parsing, aggregation, query engine (shared) | Event model + JFR reader |
| `tm-cli` | Opens `.jfr` files and incident bundles offline; serves the UI locally | `summary` command |
| `tm-agent` | Java agent: continuous JFR, snapshots, triggers, ring buffer, shipper | Placeholder (Phase 6) |
| `tm-collector` | Service: ingest, Parquet/DuckDB (ClickHouse for fleets), analyzer, query API | Placeholder (Phase 8) |
| `tm-ui` | Browser UI (TypeScript, React, Canvas/WebGL): timeline, flame graphs, diffs, request view | Placeholder (Phase 4) |
| `demo` | Demo app with planted problems (CPU hotspot, allocations, lock contention) | Done |

**UI choice:** a browser UI, not Swing. Smooth scrubbing over millions of events needs
GPU-backed Canvas/WebGL, and a browser UI lets you share incidents by link. `tm-cli` opens it
in a local browser tab for offline use. See the decision table in [DESIGN.html](https://syucel1.github.io/jvm-time-machine/DESIGN.html#ui).

## Build and run

Requires **JDK 21+**. The Gradle wrapper downloads Gradle itself.

```bash
# Build everything, record demo.jfr and run all tests
./gradlew build                      # Windows: gradlew.bat build

# Record the demo app under JFR (writes demo/build/jfr/demo.jfr)
./gradlew :demo:recordDemo -Pdemo.seconds=15

# Summarise any recording
./gradlew :tm-cli:run --args="summary demo/build/jfr/demo.jfr"
```

Example output:

```
Recording   demo/build/jfr/demo.jfr
Duration    8.2 s
Events      31,820 (83 types)
Threads     15
Stacks      92 unique for 3,445 stack references (37.4x dedup)

Hot methods (jdk.ExecutionSample, top frame)
   68.01%      489  io.github.syucel1.tm.demo.DemoApp.slowPrimeCount
    7.23%       52  sun.security.provider.NativePRNG$RandomIO.implNextBytes
    6.26%       45  java.lang.Integer.getChars
```

Use the core library directly:

```java
JvmRecording rec = JfrReader.create().read(Path.of("app.jfr"));
rec.countByType();                       // events per type
HotMethods.top(rec, 10);                 // hottest methods by CPU samples
rec.eventsOfType("jdk.JavaMonitorEnter") // lock waits, with thread and stack
   .map(rec::stackOf)
   .forEach(System.out::println);
```

### Project layout

```
.
├── demo/            demo app with planted problems + recordDemo task
├── tm-core/         event model (TmEvent, StackTable, JvmRecording), JfrReader, analysis
├── tm-cli/          command-line tool (tm-cli summary)
├── tm-agent/        Java agent (placeholder until Phase 6)
├── tm-collector/    collector service (placeholder until Phase 8)
├── tm-ui/           browser UI (placeholder until Phase 4)
└── .github/workflows/ci.yml
```

## Try the idea today with plain JFR

JDK 17+ includes everything needed to see the kind of data we build on:

```bash
# Record
java -XX:StartFlightRecording=duration=60s,filename=app.jfr,settings=profile -jar app.jar

# Or attach to a running JVM
jcmd <pid> JFR.start duration=60s filename=app.jfr

# Analyse (JDK 21+)
jfr view hot-methods app.jfr
jfr view allocation-by-site app.jfr
jfr view contention-by-site app.jfr
jfr view gc-pauses app.jfr
jcmd <pid> JFR.view hot-methods      # live, no file
```

## Roadmap

10 phases from design to v1.0, each ending with an exit gate. Details: [ROADMAP.md](ROADMAP.md) / [ROADMAP.html](https://syucel1.github.io/jvm-time-machine/ROADMAP.html).

| # | Phase | Delivers | Status |
| --- | --- | --- | --- |
| 1 | Design | Goals, architecture, UI decision, roadmap | ✅ Done |
| 2 | Foundations | Build, CI, core event model, demo app | ✅ Done |
| 3 | Offline viewer | `tm-cli` + query API on a `.jfr` file | Planned |
| 4 | Timeline UI | Browser UI with scrubber and flame graphs | Planned |
| 5 | Time diff | Compare two time windows | Planned |
| 6 | Always-on agent | Agent with rolling buffer and snapshots | Planned |
| 7 | Incident capture | Triggers, incident bundles, overhead control | Planned |
| 8 | Collector & fleet | Central service, multi-JVM, alerts | Planned |
| 9 | Request context | Trace IDs, request timeline, deep profiling | Planned |
| 10 | Intelligence & v1.0 | AI summaries, hardening, packaging | Planned |

## Documents

| File | Contents |
| --- | --- |
| [DESIGN.html](https://syucel1.github.io/jvm-time-machine/DESIGN.html) ([source](DESIGN.html)) | Full design: goals, components, data model, UI decision, flows, overhead, prior art, risks |
| [ROADMAP.md](ROADMAP.md) | Phase-by-phase plan (Markdown) |
| [ROADMAP.html](https://syucel1.github.io/jvm-time-machine/ROADMAP.html) ([source](ROADMAP.html)) | Same plan, visual version |

## Requirements

- JDK 21+ to build and run (the minimum agent JDK, 17 or 21, is still an open question)
- Any modern browser for the UI (from Phase 4)

## License

Apache License 2.0. See [LICENSE](LICENSE).
