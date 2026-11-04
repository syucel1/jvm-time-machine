# JVM Time Machine: Roadmap

From design to v1.0 in **10 phases**. Each phase delivers something usable on its own and ends with an
**exit gate** that must pass before the next phase starts. Phases are in order, not to scale.

Legend: ✅ done · ⬜ planned

| # | Phase | Delivers | Exit gate |
| --- | --- | --- | --- |
| 1 | ✅ Design | Goals, architecture, UI decision, roadmap | Design reviewed and agreed |
| 2 | ✅ Foundations | Build, CI, core event model, demo app | `demo.jfr` parsed into the event model in CI |
| 3 | ⬜ Offline viewer | `tm-cli` + query API on a `.jfr` file | API answers hot methods, GC, locks per window |
| 4 | ⬜ Timeline UI | Browser UI with scrubber and flame graphs | UI explains all problems planted in `demo.jfr` |
| 5 | ⬜ Time diff | Compare two windows | Diff pinpoints a regression in a test recording |
| 6 | ⬜ Always-on agent | Agent with rolling buffer and snapshots | Runs 24 h in a test service with no errors |
| 7 | ⬜ Incident capture | Triggers, incident bundles, overhead control | Overhead ≤ 2% on the benchmark suite |
| 8 | ⬜ Collector & fleet | Central service, multi-JVM, alerts | 3 teams run it in production for 2 weeks |
| 9 | ⬜ Request context | Trace IDs, request timeline, deep profiling | A slow request explained from its trace ID |
| 10 | ⬜ Intelligence & v1.0 | AI summaries, hardening, packaging | v1.0.0 released |

---

## 1. Design ✅

- Problem statement, goals and non-goals
- Component architecture (agent, collector, clients)
- Data model and storage approach
- UI technology decision: browser (React + Canvas/WebGL), not Swing
- Overhead budget, safety rules, prior art, risks

**Exit gate:** design reviewed and agreed.

## 2. Foundations ✅

- Gradle multi-module project: `tm-core`, `tm-agent`, `tm-collector`, `tm-cli`, `tm-ui`
- Java 21 toolchain, Apache 2.0 licence, GitHub Actions CI
- Demo application with planted problems (CPU hotspot, heavy allocation, lock contention)
- Core event model: read `.jfr` files, map to events and deduplicated stack traces

**Exit gate:** `demo.jfr` is parsed into the event model in CI.

## 3. Offline viewer

- Per-second aggregation: CPU, heap, GC pauses, thread states, allocation rate
- Window queries: hot methods, allocations, contention, GC for any time range
- `tm-cli serve <file.jfr>` with an embedded HTTP server and JSON query API

**Exit gate:** the API answers hot methods, GC and lock questions for any window.

## 4. Timeline UI

- Browser UI (Vite, React, TypeScript) bundled into `tm-cli`
- Overview lanes for CPU, heap, GC and threads
- Timeline scrubber with window selection and detail panels
- Interactive flame graph (Canvas)

**Exit gate:** the UI explains all problems planted in `demo.jfr`.

## 5. Time diff

- Compare window A (normal) with window B (bad)
- Differential flame graph and event-delta table
- Shareable links to a window or a diff

**Exit gate:** the diff pinpoints a planted regression in a test recording.

## 6. Always-on agent

- Java agent (`-javaagent` or runtime attach), YAML config
- Continuous JFR streaming with a custom `timemachine.jfc` profile
- Rolling 30-minute disk buffer; JMX controls (start, stop, dump)
- Periodic snapshots: thread dumps, class histograms, heap stats

**Exit gate:** the agent runs for 24 hours in a test service with no errors.

## 7. Incident capture

- Trigger rules: GC pause, CPU, exception rate, latency
- On a trigger: freeze the buffer, boost detail, write an incident bundle (`.tmz`)
- Overhead benchmark suite in CI; circuit breaker and kill switch

**Exit gate:** measured overhead ≤ 2% on the benchmark suite.

## 8. Collector & fleet

- `tm-collector` service: ingest, columnar storage (Parquet/DuckDB), shared query API
- Agent shipper with batching, backoff and a bounded queue
- Fleet overview and incident list in the UI
- Analyzer incident reports; Slack and webhook alerts

**Exit gate:** at least 3 teams run it in production for 2 weeks.

## 9. Request context

- OpenTelemetry trace context on events; HTTP and JDBC events with trace IDs
- Request timeline view: one request across threads, locks, SQL and GC
- Optional async-profiler module for wall-clock and native stacks after a trigger

**Exit gate:** a slow request is fully explained from its trace ID.

## 10. Intelligence & v1.0

- Opt-in AI incident summary
- Redaction rules and privacy review
- Docs, Docker images, Helm chart, ClickHouse option for large fleets

**Exit gate:** v1.0.0 released.

---

## Beyond v1.0

- IntelliJ plugin: jump from a hot frame to source
- Desktop wrapper (Tauri) if users ask for it
- Heap dump analysis in the UI (dominators, leak suspects)
- Virtual-thread pinning views (JDK 21+)
- Grafana data source plugin

## Open questions

- Depend on the OpenTelemetry Java agent, or ship our own context capture?
- DuckDB first and ClickHouse later, or ClickHouse from the start?
- Minimum agent JDK: 17 or 21?
- Which LLM API is acceptable for incident data?
- Final project name (trademark check)
