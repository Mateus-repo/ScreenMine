# Changelog

All notable changes to CoScreen are documented here.
This project follows [Semantic Versioning](https://semver.org/).

## [1.0.0] — 2026-08-20

First release. Minecraft 1.21.4, Fabric, client-side only.

### Added

**Screens in the world**

- Spawn a browser screen from the mini menu (`M`); it appears in front of you,
  facing you, and stays where you put it.
- Grab a screen with a multi-click gesture — three clicks within a second, the
  fourth picks it up — then move the mouse and click again to place it. No
  notification, no prompt: it just happens.
- Resize with `=` / `-`, from 1 to 32 blocks wide.
- Remove with `Delete` or from the screen's own panel.
- Screens are flat, opaque and unlit: the world does not shine through them, and
  the picture stays readable at night. No frame and no highlight outline when you
  aim at one.
- Rendering runs after clouds and weather, so nothing paints over the picture.

**The browser**

- Embedded Chromium through MCEF, rendered off-screen at 1920×1080 and uploaded
  as a texture each frame.
- Control mode (`C`): the crosshair becomes a mouse pointer — left and right
  click, mouse wheel scrolls. The keyboard only goes to the page while a text
  field there has focus, so movement keys keep working while you are just
  watching.
- Fullscreen browser (`B`) for careful interaction; the screen stays up in the
  world while it is open.
- A YouTube button, play/pause, ±10 seconds and an address bar on the screen's
  panel.
- Pause and seek from the world with `K`.

**Audio**

- Volume falls off with distance: full up to 6 blocks, silent past 40.
- Follows Minecraft's master volume.
- Per-screen volume with `]` / `[` and mute with `N`. Volume is local and is
  never sent over the network.

**Interface**

- Mini menu on `M` with everything in one place.
- English and Russian, switchable in the menu and independent of Minecraft's own
  language setting. The choice is remembered between sessions.
- Every key is rebindable under Options → Controls, category **CoScreen**.

**Configuration**

- `config/coscreen.properties` is created on first launch with a commented
  template covering the language and the companion server address.
- No server address is compiled into the mod. With none configured, the mod
  never opens a socket and never complains about it.

### Not in this release

- **Watching together.** Friends, invitations, shared sessions and synchronised
  playback are not finished. The menu entries are visible and a companion server
  exists behind them, but synchronised playback is not reliable yet. Planned for
  a future release.

### Known limitations

- Control mode suppresses vanilla left and right click as well as it can, but
  this is best-effort. The fullscreen browser is more comfortable for careful
  interaction.
- While the crosshair is on a screen, left click does not attack or break
  blocks — the grab gesture needs those clicks.
- Timecode reading relies on the first `<video>` element on the page.
- On first launch MCEF downloads Chromium (~200 MB); until it finishes, screens
  render as grey rectangles.

### Requires

- Minecraft 1.21.4, Fabric Loader 0.16.9+
- Java 21
- Fabric API 0.119.4+1.21.4
- MCEF 2.1.6-1.21.4
