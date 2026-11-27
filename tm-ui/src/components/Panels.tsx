// Detail panels for the selected window: the numbers behind the timeline and flame graph,
// as tables (also the accessible, non-color view of the charts).
import { useState } from "react";
import type { WindowResult } from "../api";
import { bytes, count, nanos, packageOf, percent, prettyClass, seconds, shortMethod } from "../format";

interface Props {
  data: WindowResult;
  onMethod: (method: string) => void;
  onThread: (thread: string) => void;
  thread: string | null;
}

function Name({ value }: { value: string }) {
  const pkg = packageOf(value);
  return (
    <div className="name" title={value}>
      <span className="mono">{shortMethod(value)}</span>
      {pkg && <span className="pkg">{pkg}</span>}
    </div>
  );
}

function Bar({ pct }: { pct: number }) {
  return <div className="bar" style={{ width: `${Math.max(0, Math.min(100, pct))}%` }} />;
}

function Panel({ title, hint, children, tools }: { title: string; hint?: string; children: React.ReactNode; tools?: React.ReactNode }) {
  return (
    <section className="card">
      <header>
        <h2>{title}</h2>
        {hint && <span className="hint">{hint}</span>}
        {tools && <span style={{ marginLeft: "auto" }}>{tools}</span>}
      </header>
      <div className="body">{children}</div>
    </section>
  );
}

export function Panels({ data, onMethod, onThread, thread }: Props) {
  const [allocBy, setAllocBy] = useState<"site" | "class">("site");
  const allocs = allocBy === "site" ? data.allocationsBySite : data.allocationsByClass;
  const maxContention = Math.max(1, ...data.contention.map((c) => c.totalNanos));
  const maxThreadSamples = Math.max(1, ...data.threads.map((t) => t.cpuSamples));

  return (
    <div className="side">
      <Panel title="Hot methods" hint={`${count(data.cpuSamples)} CPU samples`}>
        {data.hotMethods.length === 0 ? <p className="empty">No CPU samples.</p> : (
          <table className="data">
            <thead><tr><th>Method (top frame)</th><th className="num">Samples</th><th className="num">Share</th></tr></thead>
            <tbody>
              {data.hotMethods.map((m) => (
                <tr key={m.method} className="clickable" onClick={() => onMethod(m.method)} title="Highlight in the flame graph">
                  <td><Name value={m.method} /><Bar pct={m.percent} /></td>
                  <td className="num">{count(m.samples)}</td>
                  <td className="num">{percent(m.percent)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </Panel>

      <Panel
        title="Allocations"
        hint={allocBy === "site" ? "by first application frame" : "by class"}
        tools={
          <span className="seg">
            <button className="btn small" aria-pressed={allocBy === "site"} onClick={() => setAllocBy("site")}>Site</button>
            <button className="btn small" aria-pressed={allocBy === "class"} onClick={() => setAllocBy("class")}>Class</button>
          </span>
        }
      >
        {allocs.length === 0 ? <p className="empty">No allocation samples.</p> : (
          <table className="data">
            <thead><tr><th>{allocBy === "site" ? "Site" : "Class"}</th><th className="num">Bytes</th><th className="num">Share</th></tr></thead>
            <tbody>
              {allocs.map((a) => (
                <tr key={a.name} className={allocBy === "site" ? "clickable" : undefined}
                    onClick={allocBy === "site" ? () => onMethod(a.name) : undefined}>
                  <td>{allocBy === "site" ? <Name value={a.name} /> : <span className="mono name">{prettyClass(a.name)}</span>}<Bar pct={a.percent} /></td>
                  <td className="num">{bytes(a.bytes)}</td>
                  <td className="num">{percent(a.percent)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </Panel>

      <Panel title="Lock contention" hint="threads blocked entering synchronized">
        {data.contention.length === 0 ? <p className="empty">No monitor contention.</p> : (
          <table className="data">
            <thead><tr><th>Site · lock class</th><th className="num">Waits</th><th className="num">Blocked</th><th className="num">Max</th></tr></thead>
            <tbody>
              {data.contention.map((c) => (
                <tr key={c.method + c.monitorClass} className="clickable" onClick={() => onMethod(c.method)}>
                  <td><Name value={c.method} /><span className="pkg mono" style={{ color: "var(--muted)", fontSize: 11 }}>{prettyClass(c.monitorClass)}</span>
                    <Bar pct={(100 * c.totalNanos) / maxContention} /></td>
                  <td className="num">{count(c.count)}</td>
                  <td className="num">{nanos(c.totalNanos)}</td>
                  <td className="num">{nanos(c.maxNanos)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </Panel>

      <Panel title="Garbage collection" hint={`${data.gc.count} collections · ${nanos(data.gc.pauseNanos)} paused`}>
        {data.gc.longest.length === 0 ? <p className="empty">No collections.</p> : (
          <table className="data">
            <thead><tr><th>At</th><th>Collector · cause</th><th className="num">Pause</th></tr></thead>
            <tbody>
              {data.gc.longest.map((g, i) => (
                <tr key={i}>
                  <td className="num">{seconds(g.offsetSeconds)}</td>
                  <td>{g.name}<span className="pkg" style={{ display: "block", color: "var(--muted)", fontSize: 11 }}>{g.cause}</span></td>
                  <td className="num">{nanos(g.pauseNanos)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </Panel>

      <Panel title="Threads" hint="click to filter the flame graph">
        {data.threads.length === 0 ? <p className="empty">No thread activity.</p> : (
          <table className="data">
            <thead><tr><th>Thread</th><th className="num">CPU</th><th className="num">Allocated</th><th className="num">Blocked</th></tr></thead>
            <tbody>
              {data.threads.map((t) => (
                <tr key={t.name} className="clickable" onClick={() => onThread(t.name)}
                    style={t.name === thread ? { background: "var(--accent-soft)" } : undefined}>
                  <td><span className="mono name">{t.name}</span><Bar pct={(100 * t.cpuSamples) / maxThreadSamples} /></td>
                  <td className="num">{count(t.cpuSamples)}</td>
                  <td className="num">{bytes(t.allocatedBytes)}</td>
                  <td className="num">{nanos(t.lockWaitNanos)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </Panel>
    </div>
  );
}
