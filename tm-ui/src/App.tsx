import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  api, type FlameGraphResult, type FlameKind, type Overview, type Range, type RecordingInfo,
  type ThreadLanesResult, type WindowResult,
} from "./api";
import { FlameGraph } from "./components/FlameGraph";
import { Findings } from "./components/Findings";
import { Panels } from "./components/Panels";
import { Timeline } from "./components/Timeline";
import { count, seconds, shortMethod } from "./format";
import { formatHash, parseHash } from "./hash";
import { findings, type Finding } from "./insights";
import { useTheme } from "./theme";

const KIND_LABEL: Record<FlameKind, string> = { cpu: "CPU", alloc: "Allocations", lock: "Locks" };
const KIND_HINT: Record<FlameKind, string> = {
  cpu: "width = CPU samples",
  alloc: "width = bytes allocated",
  lock: "width = time blocked on synchronized",
};

interface Loaded { info: RecordingInfo; overview: Overview; lanes: ThreadLanesResult }

export function App() {
  const { palette, theme, toggle } = useTheme();
  const initial = useMemo(() => parseHash(location.hash), []);
  const [loaded, setLoaded] = useState<Loaded | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [range, setRange] = useState<Range | null>(initial.range);
  const [kind, setKind] = useState<FlameKind>(initial.kind);
  const [thread, setThread] = useState<string | null>(initial.thread);
  const [focus, setFocus] = useState<string[]>([]);
  const [search, setSearch] = useState("");
  const [win, setWin] = useState<WindowResult | null>(null);
  const [flame, setFlame] = useState<FlameGraphResult | null>(null);
  const [loading, setLoading] = useState({ win: false, flame: false });
  const flameRef = useRef<HTMLElement>(null);

  // Load the recording once.
  useEffect(() => {
    const ac = new AbortController();
    Promise.all([api.recording(ac.signal), api.overview(ac.signal), api.threads(ac.signal)])
      .then(([info, overview, lanes]) => setLoaded({ info, overview, lanes }))
      .catch((e) => !ac.signal.aborted && setError(`Could not load the recording: ${e.message}`));
    return () => ac.abort();
  }, []);

  const duration = loaded ? Math.max(loaded.info.durationSeconds, 0.001) : 0;
  const effective: Range = useMemo(() => {
    if (!range || !loaded) return { from: 0, to: duration };
    const from = Math.max(0, Math.min(range.from, duration));
    return { from, to: Math.max(from, Math.min(range.to, duration)) };
  }, [range, loaded, duration]);
  const whole = effective.from <= 0 && effective.to >= duration;

  // Keep the URL hash in step, so the view can be shared as a link.
  useEffect(() => {
    if (!loaded) return;
    const h = formatHash({ range: whole ? null : effective, kind, thread });
    if (h !== location.hash && !(h === "" && location.hash === "")) {
      history.replaceState(null, "", h || location.pathname + location.search);
    }
  }, [effective, whole, kind, thread, loaded]);

  useEffect(() => {
    const onHash = () => {
      const s = parseHash(location.hash);
      setRange(s.range);
      setKind(s.kind);
      setThread(s.thread);
    };
    window.addEventListener("hashchange", onHash);
    return () => window.removeEventListener("hashchange", onHash);
  }, []);

  // Window details.
  useEffect(() => {
    if (!loaded) return;
    const ac = new AbortController();
    setLoading((l) => ({ ...l, win: true }));
    api.window(effective, ac.signal)
      .then((w) => { setWin(w); setLoading((l) => ({ ...l, win: false })); })
      .catch((e) => { if (!ac.signal.aborted) { setError(e.message); setLoading((l) => ({ ...l, win: false })); } });
    return () => ac.abort();
  }, [loaded, effective]);

  // Flame graph.
  useEffect(() => {
    if (!loaded) return;
    const ac = new AbortController();
    setLoading((l) => ({ ...l, flame: true }));
    api.flame(effective, kind, thread, ac.signal)
      .then((f) => { setFlame(f); setLoading((l) => ({ ...l, flame: false })); })
      .catch((e) => { if (!ac.signal.aborted) { setError(e.message); setLoading((l) => ({ ...l, flame: false })); } });
    return () => ac.abort();
  }, [loaded, effective, kind, thread]);

  const items = useMemo(
    () => (win && loaded ? findings(win, loaded.lanes, effective) : []),
    [win, loaded, effective],
  );

  const chooseKind = useCallback((k: FlameKind) => {
    setKind(k);
    setFocus([]);
  }, []);
  const chooseThread = useCallback((t: string | null) => {
    setThread(t);
    setFocus([]);
  }, []);

  const openFinding = (f: Finding) => {
    if (!f.flame) return;
    chooseKind(f.flame);
    chooseThread(f.thread);
    setSearch("");
    flameRef.current?.scrollIntoView({ behavior: "smooth", block: "start" });
  };

  const copyLink = () => {
    navigator.clipboard?.writeText(location.href).catch(() => undefined);
  };

  if (error && !loaded) {
    return <div className="app"><div className="error" role="alert">{error}</div></div>;
  }
  if (!loaded) {
    return <div className="app"><div className="empty">Loading recording…</div></div>;
  }
  const { info } = loaded;

  return (
    <div className="app">
      <div className="top">
        <div className="logo" aria-hidden="true">⟲</div>
        <h1>JVM Time Machine</h1>
        <span className="file">
          <b>{info.source}</b> · {seconds(info.durationSeconds)} · {count(info.eventCount)} events · {info.threads.length} threads
          · started {new Date(info.startEpochMillis).toLocaleString()}
        </span>
        <span className="spacer" />
        <a className="btn" href="/api">JSON API</a>
        <button className="btn" onClick={toggle} aria-label="Toggle theme">◐ {theme === "dark" ? "Light" : "Dark"}</button>
      </div>

      {error && <div className="error" role="alert">{error} <button className="btn small" onClick={() => setError(null)}>Dismiss</button></div>}

      <section className="card timeline">
        <header>
          <h2>Timeline</h2>
          <WindowBar range={effective} duration={duration} whole={whole} onRange={setRange} onCopy={copyLink} />
        </header>
        <div className="body">
          <div className="legend" style={{ marginBottom: 8 }}>
            <span><i style={{ background: palette.series1 }} />Measured (CPU: JVM)</span>
            <span><i className="line" style={{ background: palette.reference }} />CPU: whole machine</span>
            <span style={{ marginLeft: 12 }}>Threads:</span>
            <span><i style={{ background: palette.series1 }} />Running</span>
            <span><i style={{ background: palette.series2 }} />Blocked</span>
            <span><i style={{ background: palette.series3 }} />Waiting</span>
            <span style={{ marginLeft: "auto" }}>Drag to select · click a second · double-click to reset</span>
          </div>
          <Timeline
            overview={loaded.overview}
            lanes={loaded.lanes}
            duration={duration}
            range={effective}
            onRange={setRange}
            thread={thread}
            onThread={chooseThread}
            palette={palette}
          />
        </div>
      </section>

      {win && <div className={loading.win ? "loading" : undefined}><Findings items={items} kind={kind} onOpen={openFinding} /></div>}

      <div className="main">
        <section className="card" ref={flameRef}>
          <header>
            <h2>Flame graph</h2>
            <div className="tabs" role="tablist" aria-label="Flame graph kind">
              {(Object.keys(KIND_LABEL) as FlameKind[]).map((k) => (
                <button key={k} role="tab" aria-selected={kind === k} aria-pressed={kind === k} className="btn small" onClick={() => chooseKind(k)}>
                  {KIND_LABEL[k]}
                </button>
              ))}
            </div>
            <span className="hint">{KIND_HINT[kind]}</span>
            <div className="flame-tools">
              {thread && (
                <span className="chip">thread {thread}
                  <button aria-label="Show all threads" onClick={() => chooseThread(null)}>×</button>
                </span>
              )}
              <input type="search" placeholder="Highlight frames…" value={search} onChange={(e) => setSearch(e.target.value)} aria-label="Highlight frames by name" />
            </div>
          </header>
          {flame && focus.length > 0 && (
            <div className="crumbs">
              <button onClick={() => setFocus([])}>all</button>
              {focus.map((name, i) => (
                <span key={i}>› <button onClick={() => setFocus(focus.slice(0, i + 1))} title={name}>{shortMethod(name)}</button></span>
              ))}
            </div>
          )}
          <div className={`body${loading.flame ? " loading" : ""}`}>
            {flame ? <FlameGraph data={flame} focus={focus} onFocus={setFocus} search={search} palette={palette} />
              : <div className="empty">Loading flame graph…</div>}
          </div>
        </section>

        {win && (
          <div className={loading.win ? "loading" : undefined}>
            <Panels data={win} thread={thread} onThread={(t) => chooseThread(t === thread ? null : t)}
                    onMethod={(m) => setSearch(shortMethod(m))} />
          </div>
        )}
      </div>

      <footer className="foot">
        JVM Time Machine · window {seconds(effective.from)}–{seconds(effective.to)} · times are seconds since the recording start ·
        {" "}<a href="https://github.com/syucel1/jvm-time-machine">source</a>
      </footer>
    </div>
  );
}

function WindowBar({ range, duration, whole, onRange, onCopy }: {
  range: Range; duration: number; whole: boolean; onRange: (r: Range | null) => void; onCopy: () => void;
}) {
  const [from, setFrom] = useState(range.from.toFixed(1));
  const [to, setTo] = useState(range.to.toFixed(1));
  useEffect(() => {
    setFrom(range.from.toFixed(1));
    setTo(range.to.toFixed(1));
  }, [range]);
  const apply = () => {
    const f = Number(from);
    const t = Number(to);
    if (Number.isFinite(f) && Number.isFinite(t) && t > f) onRange({ from: Math.max(0, f), to: Math.min(duration, t) });
  };
  const len = range.to - range.from;
  return (
    <form className="window-bar" style={{ marginLeft: "auto" }} onSubmit={(e) => { e.preventDefault(); apply(); }}>
      <span className="range">{whole ? "Whole recording" : `${seconds(range.from)} – ${seconds(range.to)}`} <span style={{ color: "var(--muted)", fontWeight: 400 }}>({len.toFixed(1)} s)</span></span>
      <label htmlFor="from">From</label>
      <input id="from" inputMode="decimal" value={from} onChange={(e) => setFrom(e.target.value)} onBlur={apply} />
      <label htmlFor="to">to</label>
      <input id="to" inputMode="decimal" value={to} onChange={(e) => setTo(e.target.value)} onBlur={apply} />
      <button type="button" className="btn small" disabled={whole} onClick={() => onRange(null)}>Whole recording</button>
      <button type="button" className="btn small" onClick={onCopy} title="Copy a link to this window and flame graph">Copy link</button>
    </form>
  );
}
