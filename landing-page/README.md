# WorldgenD homepage

Plain Vite, no framework. The page works without JavaScript.

```
npm install
npm run dev      # local preview
npm run build    # static site in dist/
```

## Where things are

- `index.html`: all text, numbers and links. Edit it directly.
- `src/style.css`: colors and fonts are tokens at the top (`:root`).
- `src/main.ts`: the hero mosaic animation and the evolution-section highlight. Optional.
- `public/`: files copied as-is (favicon).

## Editing numbers

Every benchmark number must have a source link next to it. Bar widths are CSS
variables in the HTML: `--v` (race chart) or `--lo`/`--hi` (evolution chart),
divided by the chart's `--max`.
