// Progressive enhancement only. The page is complete without this file.

const reducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches;

/*
 * Hero mosaic.
 * A 32x32 chunk view tiled at modulus 16, as in WorldgenD's mosaic fill:
 * phase = (x mod 16) + 16 * (z mod 16). Phases fill in order, and the four
 * chunks sharing a phase light up together. One pass, then the loop stops.
 */
const GRID = 32;
const MODULUS = 16;
const FILL_MS = 2200;
const EXAMPLE = { x: 5, z: 6 };

function cssVar(name: string): string {
  return getComputedStyle(document.documentElement).getPropertyValue(name).trim();
}

function hexToRgb(hex: string): [number, number, number] {
  const n = parseInt(hex.replace('#', ''), 16);
  return [(n >> 16) & 255, (n >> 8) & 255, n & 255];
}

// Cheap smooth field so the finished grid reads as terrain, not a checkerboard.
function height(x: number, z: number): number {
  const v = Math.sin(x * 0.31) + Math.cos(z * 0.27) + Math.sin((x + z) * 0.16) * 0.8;
  return (v + 2.8) / 5.6;
}

function setupMosaic(canvas: HTMLCanvasElement): void {
  const ctx = canvas.getContext('2d');
  if (!ctx) return;

  const [r, g, b] = hexToRgb(cssVar('--accent'));
  const ink = cssVar('--ink');
  const bg = cssVar('--bg');
  let cell = 0;
  let gap = 0;

  function resize(): void {
    const dpr = Math.min(window.devicePixelRatio || 1, 2);
    const size = canvas.getBoundingClientRect().width;
    canvas.width = Math.round(size * dpr);
    canvas.height = Math.round(size * dpr);
    cell = canvas.width / GRID;
    gap = Math.max(1, Math.round(dpr));
  }

  function drawCell(x: number, z: number, style: string): void {
    ctx!.fillStyle = style;
    ctx!.fillRect(x * cell, z * cell, cell - gap, cell - gap);
  }

  // Five height bands read like a contour map.
  function settled(x: number, z: number): string {
    const band = Math.min(4, Math.floor(height(x, z) * 5));
    return `rgba(${r}, ${g}, ${b}, ${(0.14 + band * 0.17).toFixed(2)})`;
  }

  function cellsOfPhase(p: number): Array<[number, number]> {
    const px = p % MODULUS;
    const pz = Math.floor(p / MODULUS);
    const out: Array<[number, number]> = [];
    for (let z = pz; z < GRID; z += MODULUS) {
      for (let x = px; x < GRID; x += MODULUS) out.push([x, z]);
    }
    return out;
  }

  function markExample(): void {
    for (const [x, z] of cellsOfPhase(EXAMPLE.x + MODULUS * EXAMPLE.z)) drawCell(x, z, ink);
  }

  function drawFinal(): void {
    ctx!.fillStyle = bg;
    ctx!.fillRect(0, 0, canvas.width, canvas.height);
    for (let z = 0; z < GRID; z++) {
      for (let x = 0; x < GRID; x++) drawCell(x, z, settled(x, z));
    }
    markExample();
  }

  resize();
  canvas.classList.add('is-drawn');

  let resizeTimer = 0;
  window.addEventListener('resize', () => {
    clearTimeout(resizeTimer);
    resizeTimer = window.setTimeout(() => {
      resize();
      drawFinal();
    }, 150);
  });

  if (reducedMotion) {
    drawFinal();
    return;
  }

  // Each frame paints only the phases reached since the last frame, so the
  // whole animation touches each of the 1,024 cells about twice.
  ctx.fillStyle = bg;
  ctx.fillRect(0, 0, canvas.width, canvas.height);
  const phases = MODULUS * MODULUS;
  let start = 0;
  let done = 0;
  let lit: number[] = [];

  function frame(now: number): void {
    if (!start) start = now;
    const target = Math.min(phases, Math.floor(((now - start) / FILL_MS) * phases));

    for (const p of lit) {
      for (const [x, z] of cellsOfPhase(p)) drawCell(x, z, settled(x, z));
    }
    lit = [];
    for (; done < target; done++) {
      for (const [x, z] of cellsOfPhase(done)) drawCell(x, z, ink);
      lit.push(done);
    }

    if (done < phases) {
      requestAnimationFrame(frame);
    } else {
      drawFinal();
    }
  }
  requestAnimationFrame(frame);
}

/*
 * Replay panel in the evolution section.
 * Fills the 80x80 benchmark grid (6,400 chunks) in the active version's
 * measured time, sped up 60x. Times come from the bar row's --lo/--hi, so
 * the HTML stays the only place numbers live. The mosaic fills in phase
 * order; Orion versions dispatch on demand, shown as a scattered order.
 */
const BENCH_GRID = 80;
const BENCH_CHUNKS = BENCH_GRID * BENCH_GRID;
const REPLAY_SPEED = 60;

function phaseOrder(): number[] {
  const idx = Array.from({ length: BENCH_CHUNKS }, (_, i) => i);
  const phase = (i: number) => (i % BENCH_GRID) % MODULUS + MODULUS * (Math.floor(i / BENCH_GRID) % MODULUS);
  return idx.sort((a, b) => phase(a) - phase(b) || a - b);
}

function scatterOrder(): number[] {
  const idx = Array.from({ length: BENCH_CHUNKS }, (_, i) => i);
  let seed = 69;
  for (let i = idx.length - 1; i > 0; i--) {
    seed = (seed * 1103515245 + 12345) >>> 0;
    const j = seed % (i + 1);
    [idx[i], idx[j]] = [idx[j], idx[i]];
  }
  return idx;
}

function rowRange(row: HTMLElement): [number, number] | null {
  const lo = parseFloat(row.style.getPropertyValue('--lo'));
  const hi = parseFloat(row.style.getPropertyValue('--hi'));
  return Number.isFinite(lo) && Number.isFinite(hi) ? [lo, hi] : null;
}

function range(a: number, b: number, digits: number): string {
  const x = a.toFixed(digits);
  const y = b.toFixed(digits);
  return x === y ? x : `${x}–${y}`;
}

function createReplay(panel: HTMLElement, rows: HTMLElement[], steps: HTMLElement[]) {
  const canvas = panel.querySelector<HTMLCanvasElement>('canvas');
  const ctx = canvas?.getContext('2d');
  const nameEl = panel.querySelector<HTMLElement>('.replay-name');
  const timeEl = panel.querySelector<HTMLElement>('.replay-time');
  const relEl = panel.querySelector<HTMLElement>('.replay-rel');
  const mosaicRow = rows.find((r) => r.dataset.step === 'mosaic');
  const mosaic = mosaicRow && rowRange(mosaicRow);
  if (!canvas || !ctx || !nameEl || !timeEl || !relEl || !mosaic) return null;

  panel.hidden = false;
  const fill = cssVar('--accent');
  const orders = { mosaic: phaseOrder(), orion: scatterOrder() };
  let token = 0;

  function cellRect(i: number): [number, number, number, number] {
    const x = i % BENCH_GRID;
    const z = Math.floor(i / BENCH_GRID);
    const x0 = Math.floor((x * canvas!.width) / BENCH_GRID);
    const z0 = Math.floor((z * canvas!.height) / BENCH_GRID);
    const x1 = Math.floor(((x + 1) * canvas!.width) / BENCH_GRID);
    const z1 = Math.floor(((z + 1) * canvas!.height) / BENCH_GRID);
    return [x0, z0, x1 - x0, z1 - z0];
  }

  return function play(step: string): void {
    const row = rows.find((r) => r.dataset.step === step);
    const title = steps.find((s) => s.dataset.step === step)?.querySelector('h3')?.textContent;
    if (!row) return;
    const run = ++token;
    const dpr = Math.min(window.devicePixelRatio || 1, 2);
    const size = Math.round(canvas.getBoundingClientRect().width * dpr);
    if (size && canvas.width !== size) {
      canvas.width = size;
      canvas.height = size;
    }
    ctx.clearRect(0, 0, canvas.width, canvas.height);
    nameEl.textContent = title ?? '';

    const r = rowRange(row);
    if (!r) {
      timeEl.textContent = 'Not timed';
      relEl.textContent = 'No full-scale run to replay.';
      return;
    }
    const [lo, hi] = r;
    timeEl.textContent = `${range((lo * BENCH_CHUNKS) / 1000, (hi * BENCH_CHUNKS) / 1000, 1)} s`;
    relEl.textContent = `${range(mosaic[0] / hi, mosaic[0] / lo, 2)}× the mosaic's speed`;

    const order = step === 'mosaic' ? orders.mosaic : orders.orion;
    ctx.fillStyle = fill;
    if (reducedMotion || panel.offsetParent === null) {
      for (const i of order) ctx.fillRect(...cellRect(i));
      return;
    }

    const duration = (((lo + hi) / 2) * BENCH_CHUNKS) / REPLAY_SPEED;
    let start = 0;
    let drawn = 0;
    const frame = (now: number) => {
      if (run !== token) return;
      if (!start) start = now;
      const target = Math.min(BENCH_CHUNKS, Math.floor(((now - start) / duration) * BENCH_CHUNKS));
      for (; drawn < target; drawn++) ctx.fillRect(...cellRect(order[drawn]));
      if (drawn < BENCH_CHUNKS) requestAnimationFrame(frame);
    };
    requestAnimationFrame(frame);
  };
}

/*
 * Evolution section.
 * An IntersectionObserver with a thin band at the middle of the viewport
 * (rootMargin -45% top and bottom) reports which step is being read. The
 * matching bar is highlighted. Nothing here touches scroll position.
 */
function setupEvolution(root: HTMLElement): void {
  if (!('IntersectionObserver' in window)) return;

  const steps = Array.from(root.querySelectorAll<HTMLElement>('.evo-step'));
  const rows = Array.from(root.querySelectorAll<HTMLElement>('.bar-row'));
  const panel = root.querySelector<HTMLElement>('.replay');
  const play = panel ? createReplay(panel, rows, steps) : null;
  let current = '';

  function activate(step: string): void {
    if (step === current) return;
    current = step;
    play?.(step);
    root.classList.add('has-active');
    for (const el of [...steps, ...rows]) {
      el.classList.toggle('is-active', el.dataset.step === step);
    }
  }

  const observer = new IntersectionObserver(
    (entries) => {
      for (const entry of entries) {
        const step = (entry.target as HTMLElement).dataset.step;
        if (entry.isIntersecting && step) activate(step);
      }
    },
    { rootMargin: '-45% 0px -45% 0px' },
  );
  steps.forEach((s) => observer.observe(s));
}

const mosaic = document.getElementById('mosaic');
if (mosaic instanceof HTMLCanvasElement) setupMosaic(mosaic);

const evo = document.querySelector<HTMLElement>('.evo');
if (evo) setupEvolution(evo);
