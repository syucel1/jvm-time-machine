# JVM Time Machine

> Rewind a running JVM to any moment in the last 30 minutes and see exactly what it was doing.

**Status:** Design phase (v0.1). No code yet; see [DESIGN.html](DESIGN.html) and [ROADMAP.md](ROADMAP.md).

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

| Module | Role |
| --- | --- |
| `tm-core` | Event model, JFR parsing, aggregation, query engine (shared) |
| `tm-agent` | Java agent: continuous JFR, snapshots, triggers, ring buffer, shipper |
| `tm-collector` | Service: ingest, Parquet/DuckDB (ClickHouse for fleets), analyzer, query API |
| `tm-cli` | Opens `.jfr` files and incident bundles offline; serves the UI locally |
| `tm-ui` | Browser UI (TypeScript, React, Canvas/WebGL): timeline, flame graphs, diffs, request view |

**UI choice:** a browser UI, not Swing. Smooth scrubbing over millions of events needs
GPU-backed Canvas/WebGL, and a browser UI lets you share incidents by link. `tm-cli` opens it
in a local browser tab for offline use. See the decision table in [DESIGN.html](DESIGN.html#ui).

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

20 commits in 4 phases, each ending with a gate. Full list: [ROADMAP.md](ROADMAP.md) / [ROADMAP.html](ROADMAP.html).

| Phase | Goal | Commits | Gate |
| --- | --- | --- | --- |
| 0. Prototype | `tm-cli` + browser UI on a `.jfr` file | 1–8 | Explains `demo.jfr` |
| 1. Agent MVP | Always-on agent, triggers, incident bundles | 9–12 | Overhead ≤ 2% |
| 2. Collector + fleet | Central collector, multi-JVM, alerts | 13–16 | 3 teams in production |
| 3. Requests + AI | Trace context, request timeline, AI summary | 17–20 | v1.0 release |

## Documents

| File | Contents |
| --- | --- |
| [DESIGN.html](DESIGN.html) | Full design: goals, components, data model, UI decision, flows, overhead, prior art, risks |
| [ROADMAP.md](ROADMAP.md) | Commit-by-commit plan (Markdown) |
| [ROADMAP.html](ROADMAP.html) | Same plan, visual version |

## Requirements (planned)

- JDK 17+ for the agent (JDK 21 recommended); JDK 21 for the collector and CLI
- Any modern browser for the UI

## License

Apache License 2.0 (planned).
