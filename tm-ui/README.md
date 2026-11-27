# tm-ui

Browser UI for JVM Time Machine: a timeline you can scrub, thread lanes, flame graphs and
plain-language findings for one recording. Later phases add time diffs and request views.

Built with Vite, React and TypeScript; the lanes and flame graph are drawn on Canvas. See
[DESIGN.html](https://syucel1.github.io/jvm-time-machine/DESIGN.html#ui) for why the UI runs in the
browser rather than Swing.

## How it ships

`./gradlew build` runs `npm ci` and `npm run build` here (tasks `:tm-cli:npmCi` and
`:tm-cli:buildUi`) and copies `dist/` into `tm-cli`'s classpath under `/ui`. `tm-cli serve`
serves it at `/` next to the JSON API under `/api`. `./gradlew check` also runs the unit tests
(`:tm-cli:testUi`). Pass `-Pui.skip=true` to build `tm-cli` without Node.js.

## Develop

Needs Node.js 20.19 or later.

```bash
# terminal 1: serve a recording's API on :7070
./gradlew :tm-cli:run --args="serve demo/build/jfr/demo.jfr"

# terminal 2: UI with hot reload on http://localhost:5173/, proxying /api to :7070
cd tm-ui
npm install
npm run dev          # TM_API=http://127.0.0.1:7171 npm run dev for another port
```

| Script | Does |
| --- | --- |
| `npm run dev` | Vite dev server with hot reload |
| `npm run build` | Type-check, then build into `dist/` |
| `npm test` | Unit tests (vitest): formatting, flame layout, URL state, findings |
| `npm run typecheck` | Type-check only |

## Layout

| File | Role |
| --- | --- |
| `src/api.ts` | Types and fetchers for the tm-cli JSON API |
| `src/App.tsx` | Page: loads the recording, holds the selected window, kind and thread |
| `src/components/Timeline.tsx` | Overview and thread lanes with the scrubber (Canvas) |
| `src/components/FlameGraph.tsx` | Zoomable, searchable flame graph (Canvas) |
| `src/components/Findings.tsx`, `src/insights.ts` | "What stands out" cards and the rules behind them |
| `src/components/Panels.tsx` | Tables: hot methods, allocations, contention, GC, threads |
| `src/flame.ts` | Pure flame graph layout and zoom |
| `src/hash.ts` | View state in the URL hash, for shareable links |
| `src/theme.ts`, `src/styles.css` | Light/dark tokens shared by CSS and the canvases |

Colors: series 1–3 (running, blocked, waiting) come from a palette checked for color-vision
deficiency in both themes. Every color in a chart also has a legend, a tooltip and a table, so
nothing is told by color alone.
