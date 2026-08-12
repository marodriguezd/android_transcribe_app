# v0.1.33 — Floating dictation overlay drag-to-dismiss & gesture refinements (2026-08-12)

`versionCode 35` — drag-to-dismiss drop target, hold-and-drag gesture control, and edge-snapping for the floating dictation bubble.

- **Drag-to-dismiss target ("X"):** dragging the floating bubble displays a floating circular "X" drop target at the bottom of the screen with hover scaling, red highlight tint, and haptic feedback. Dropping the bubble over the "X" target closes the floating overlay completely.
- **Hold & move freely:** long-pressing the floating bubble allows moving it anywhere around the screen without closing automatically.
- **Edge snapping & inactivity side-docking:** smooth edge snapping when released, and automatic semi-transparency when idle.
- **Dynamic theme matching:** overlay views automatically update layout colors to match system dark/light theme shifts.

The current version history remains in [`CHANGELOG.md`](CHANGELOG.md).
