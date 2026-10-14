# ScreenMine

A real Chromium browser as a flat screen in the Minecraft world: spawn it, move
it, resize it, browse and watch YouTube from inside the game. Client-side only,
works solo with no server.

Port of [CoScreen](#credits) from Minecraft 1.21.4 to **Minecraft 26.3** (Fabric).

> **Work in progress.** The port is still under construction on the
> `port/26.3` branch. `main` currently carries only the render spike and builds,
> but is not a usable mod. See [`progresso.md`](progresso.md) for where things
> stand.

## Requirements

- Minecraft **26.3** ("Wilderness Bound")
- Fabric Loader 0.19.5+
- Java **25**
- Fabric API 0.163.0+26.3
- [mcef-modern](https://github.com/DimasKama/mcef-modern) 0.3.3+mc26.3 — the
  embedded Chromium runtime. It downloads Chromium (~200 MB) on first launch, so
  screens render as grey rectangles until that finishes.

## Credits

ScreenMine is a port of **CoScreen**, authored by **Ma3stro567**, which was
distributed under the GNU Affero General Public License v3.0. Substantial parts of
its source were carried over and modified. See [`NOTICE`](NOTICE) for the full
attribution, the files involved, and the changes made.

## Licence

**GNU Affero General Public License v3.0 only** (`AGPL-3.0-only`).

Full text in [`LICENSE`](LICENSE).

CoScreen itself says nothing about whether the grant is `-only` or `-or-later`
(no licence field, no source headers, no grant statement), so ScreenMine takes the
conservative reading, which is also the FSF's recommended default. If you hold the
copyright to CoScreen and intended `-or-later`, say so and this can be relaxed.