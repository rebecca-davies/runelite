# RS3 Interface — Multi-Panel Docking Plugin

**Date:** 2026-06-15
**Status:** Approved, implementing

## Goal
A RuneLite plugin that lets the player keep multiple OSRS side-panel tabs (inventory, prayer, magic, etc.) open at once, freely repositioned, and **combined into docks**. Panels keep their native OSRS look (no custom chrome) and stay fully clickable/interactive.

## Mechanism (core trick)
Clicks on a game widget are hit-tested wherever the widget actually renders. So the plugin can relocate a real tab's content container and keep it clickable. The game's clientscripts re-hide/reset positions on tab switches; the plugin **re-asserts state every client tick** (`setHidden(false)` + `setForcedPosition(x,y)` + `revalidate()`), winning on the next tick. Cost: occasional 1-frame flicker.

## The dock model
A **dock** = a screen position + an ordered set of member panels + one active member.
- The plugin shows each dock's **active member** and hides the other members of that dock.
- Because visibility is overridden every tick, **every dock shows its active member simultaneously** → multiple tabs open at once.
- A lone floating panel = a dock with one member.

### Per-dock stone strip (the switcher)
Each dock renders a row of **native OSRS tab stones** along its **bottom edge** — one stone per member panel. These are the **real game stone widgets, relocated** via `setForcedPosition` into the strip, so they keep native sprites and stay clickable.
- Clicking a stone fires the game's tab-change var; the plugin maps it to that panel and makes it the dock's active member (content shown directly above the strip).
- Docking a panel into a dock adds its stone to that dock's strip.

### Default layout
On first run everything stays **vanilla/native**. The plugin only takes over a panel once the user pulls it into a dock in edit mode. Unmanaged tabs behave exactly like today (avoids cramming 14 stones into one strip on load).

## Edit mode
A configurable hotkey toggles edit mode.
- **Locked (default):** pure OSRS look, panels interactive, zero chrome.
- **Editing:** overlay draws temporary outlines + drop-target highlights; the mouse listener **consumes events** so rearranging never clicks items.
  - Drag a panel onto another dock → its stone joins that dock's strip (becomes active member).
  - Drag a panel to empty space → splits into its own dock with a one-stone strip.
  - Drag a dock body → moves the whole dock + strip together.

## Per-tick layout algorithm (locked, running, resizable mode)
For each dock:
1. Lay out the strip: position each member's stone in a row at the dock's bottom (`setForcedPosition`, unhide, revalidate).
2. Show the active member's content container directly above the strip (`setHidden(false)` + `setForcedPosition` + `revalidate`).
3. Hide the other members' content containers (`setHidden(true)`).

On tab-change var change (any stone clicked) → set the clicked panel active in its dock.

## Components
- **`Rs3InterfacePlugin`** — lifecycle; per-tick enforcement loop; subscribes to `ClientTick`, `GameStateChanged`, `ResizeableChanged`, and the tab-change var change.
- **`ManagedPanel`** (enum) — the 14 side tabs: display name, content-container component ID, stone component ID, tab index. **First implementation task: pin the exact `InterfaceID.ToplevelOsrsStretch` component IDs for all tabs + stones.**
- **`Dock`** — position + ordered members + active member; derives its strip from member stones.
- **`LayoutManager`** — list of docks; load/save as JSON via `ConfigManager`; clamp to viewport; ops: move / split-out / merge / set-active; default = vanilla (nothing managed).
- **`Rs3InterfaceOverlay`** — edit-mode outlines + drop highlights only; nothing when locked.
- **`Rs3InterfaceInput`** (`MouseListener`, via `MouseManager`) — edit-mode drag/drop, consumes events.
- **`Rs3InterfaceConfig`** — enable, edit-mode hotkey, per-panel enable toggles, reset-layout, optional snap-to-grid.

## Scope & honest limits (v1)
- **Resizable mode only.** Fixed mode shows a warning and stays vanilla.
- **Move + dock, not resize** for game panels (inventory grid won't reflow).
- All 14 tabs targeted, each with an individual enable toggle so a quirky one (logout / settings / world-switcher) can be excluded.
- Out of scope: bank, world map, other full-screen interfaces — side-tab panels only.
- Tab-stone "active" highlight reflects the game's single active tab (cosmetic).
- Occasional 1-frame flicker on switches/login.

## Testing
- Unit tests: `LayoutManager` JSON serialization round-trip; coordinate clamping; dock merge/split/set-active operations.
- In-client behavior validated manually by the user.

## Validate-first order
1. Pin component IDs; confirm a single panel can be force-positioned + stays clickable.
2. Prove two panels visible at once (two docks).
3. Add per-dock stone strip + switching.
4. Add edit-mode drag/drop docking + persistence.
