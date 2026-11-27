// Canvas colors come from the same CSS custom properties as the rest of the page, so light,
// dark and the theme toggle stay in one place (styles.css). Canvases redraw when the theme changes.
import { useEffect, useState } from "react";

export interface Palette {
  surface: string;
  surface2: string;
  border: string;
  grid: string;
  text: string;
  muted: string;
  accent: string;
  accentSoft: string;
  dim: string;
  series1: string;
  series2: string;
  series3: string;
  series4: string;
  reference: string;
  flameApp: string;
  flameAppText: string;
  flamePlatform: string;
  flamePlatformText: string;
  flameMatch: string;
  flameMatchText: string;
}

const VARS: Record<keyof Palette, string> = {
  surface: "--surface",
  surface2: "--surface-2",
  border: "--border",
  grid: "--grid",
  text: "--text",
  muted: "--muted",
  accent: "--accent",
  accentSoft: "--accent-soft",
  dim: "--dim",
  series1: "--series-1",
  series2: "--series-2",
  series3: "--series-3",
  series4: "--series-4",
  reference: "--reference",
  flameApp: "--flame-app",
  flameAppText: "--flame-app-text",
  flamePlatform: "--flame-platform",
  flamePlatformText: "--flame-platform-text",
  flameMatch: "--flame-match",
  flameMatchText: "--flame-match-text",
};

export function readPalette(): Palette {
  const css = getComputedStyle(document.documentElement);
  const out = {} as Palette;
  for (const [k, v] of Object.entries(VARS) as [keyof Palette, string][]) {
    out[k] = css.getPropertyValue(v).trim() || "#888";
  }
  return out;
}

export type Theme = "light" | "dark";

function systemTheme(): Theme {
  return matchMedia("(prefers-color-scheme: dark)").matches ? "dark" : "light";
}

function storedTheme(): Theme | null {
  try {
    const t = localStorage.getItem("tm-theme");
    return t === "dark" || t === "light" ? t : null;
  } catch {
    return null;
  }
}

/** Current palette plus a toggle; the stored choice is shared with the design and roadmap pages. */
export function useTheme(): { palette: Palette; theme: Theme; toggle: () => void } {
  const [theme, setTheme] = useState<Theme>(() => storedTheme() ?? systemTheme());
  const [palette, setPalette] = useState<Palette>(() => {
    const stored = storedTheme();
    if (stored) document.documentElement.setAttribute("data-theme", stored);
    return readPalette();
  });

  useEffect(() => {
    const mq = matchMedia("(prefers-color-scheme: dark)");
    const onChange = () => {
      if (!storedTheme()) {
        setTheme(systemTheme());
        setPalette(readPalette());
      }
    };
    mq.addEventListener("change", onChange);
    return () => mq.removeEventListener("change", onChange);
  }, []);

  const toggle = () => {
    const next: Theme = theme === "dark" ? "light" : "dark";
    document.documentElement.setAttribute("data-theme", next);
    try {
      localStorage.setItem("tm-theme", next);
    } catch {
      // storage unavailable: the toggle still works for this page view
    }
    setTheme(next);
    setPalette(readPalette());
  };
  return { palette, theme, toggle };
}

/** Sizes a canvas to its CSS box at device-pixel resolution and returns a scaled 2D context. */
export function prepareCanvas(canvas: HTMLCanvasElement, cssWidth: number, cssHeight: number): CanvasRenderingContext2D {
  const dpr = window.devicePixelRatio || 1;
  const w = Math.max(1, Math.round(cssWidth * dpr));
  const h = Math.max(1, Math.round(cssHeight * dpr));
  if (canvas.width !== w || canvas.height !== h) {
    canvas.width = w;
    canvas.height = h;
  }
  canvas.style.width = `${cssWidth}px`;
  canvas.style.height = `${cssHeight}px`;
  const ctx = canvas.getContext("2d")!;
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
  ctx.clearRect(0, 0, cssWidth, cssHeight);
  return ctx;
}

/** Width of an element, kept up to date with a ResizeObserver. */
export function useWidth(ref: React.RefObject<HTMLElement | null>): number {
  const [width, setWidth] = useState(0);
  useEffect(() => {
    const el = ref.current;
    if (!el) return;
    const ro = new ResizeObserver(([entry]) => setWidth(Math.floor(entry.contentRect.width)));
    ro.observe(el);
    setWidth(Math.floor(el.getBoundingClientRect().width));
    return () => ro.disconnect();
  }, [ref]);
  return width;
}
