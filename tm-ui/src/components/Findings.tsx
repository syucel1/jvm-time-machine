import type { FlameKind } from "../api";
import type { Finding, Severity } from "../insights";

const SEV: Record<Severity, { icon: string; label: string }> = {
  high: { icon: "▲", label: "High" },
  medium: { icon: "◆", label: "Medium" },
  low: { icon: "●", label: "Low" },
  none: { icon: "○", label: "None" },
};

const VIEW: Record<FlameKind, string> = { cpu: "CPU flame graph", alloc: "allocation flame graph", lock: "lock flame graph" };

/** One card per planted-problem category, each opening the flame graph that shows the evidence. */
export function Findings({ items, kind, onOpen }: { items: Finding[]; kind: FlameKind; onOpen: (f: Finding) => void }) {
  return (
    <div className="findings" role="list" aria-label="What stands out in this window">
      {items.map((f) => {
        const clickable = f.flame !== null && f.severity !== "none";
        const sev = SEV[f.severity];
        return (
          <button
            key={f.id}
            role="listitem"
            type="button"
            className={`card finding${f.severity === "none" ? " none" : ""}${f.flame === kind && clickable ? " active" : ""}`}
            onClick={clickable ? () => onOpen(f) : undefined}
            disabled={!clickable}
            aria-label={`${f.title}: ${f.headline} ${f.headlineLabel}. ${f.detail}`}
          >
            <div className="ft">
              <h3>{f.title}</h3>
              <span className={`sev ${f.severity}`}><span aria-hidden="true">{sev.icon}</span>{sev.label}</span>
            </div>
            <div className="big">{f.headline}{f.headlineLabel && <small>{f.headlineLabel}</small>}</div>
            <p>{f.detail}</p>
            {clickable && f.flame && <span className="go">Show {VIEW[f.flame]}{f.thread ? ` for ${f.thread}` : ""} →</span>}
          </button>
        );
      })}
    </div>
  );
}
