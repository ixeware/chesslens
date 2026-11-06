# ChessLens design system

**Scene.** A player at a real board or in front of a monitor, phone propped or in hand, glancing at
it between moves, usually indoors under room light. The app sits next to the game and must never
out-shout the board, so the chrome is dark and quiet, and the only saturated colour is the signal.

**World.** Taken from forgegui.com (pinned by the brief) with its blue swapped for green: near-black
slabs lit from above, bevelled controls with a rim highlight, one key per screen that glows like a
lit button. The AR layer over the camera is the exception and keeps the reference video's neon.

## Colour (Restrained + one signal)

| Token | Value | Use |
|---|---|---|
| `Ground` | `#0B0B0B` | App background |
| `Slab` → `SlabLit` | `#101010` → `#1B1B1B` | Panels: radial light from the top centre |
| `Well` / `WellLine` | `#0A0A0A` / `#1D1D1D` | Recessed areas: previews, boards, segmented tracks |
| `ControlTop` → `ControlBottom` | `#2A2A2A` → `#161414` | Bevelled controls, light pooled at the bottom |
| `ControlBorder` | `#B5B5B5` @ 22% | Control hairline |
| `Rim` | white @ 18% | Top-edge highlight on every slab and control |
| `Green` | `#22E07A` | Actions, selection, progress, focus |
| `GreenDeep` | `#003D1C` | Edge of the glowing primary action |
| `Neon` | `#5BF08D` | AR layer only (brackets, ribbons, best-move pill) |
| `Text` / `TextDim` / `TextMuted` | `#EDEDED` / 70% / 50% | Primary / secondary / tertiary text |

Engine lines: green, blue `#4F95FF`, violet `#B07CFF`. Last opponent move: yellow `#F7D64A`.
Errors and threats: red `#FF5A5F`. Settings tiles carry a category hue each; the hues never mark actions.

## Type

- **Cal Sans** (display + labels): headlines, the best move, button labels, numbers in cards.
- **Inter Tight** (UI + body), variable weight 400–700.
- Material roles only (`displaySmall` … `labelSmall`), scale ratio ≈1.2, in `sp`.

## Components

- **Slab** (`forgeSlab`): 24–28dp radius, radial top light, rim, soft offset shadow. Never nested
  inside another slab; wells go inside slabs.
- **BevelButton / BevelIconButton**: 14dp radius, 48dp minimum touch target. Selected = black key with
  a green inner glow and green hairline.
- **GlowButton**: the single primary action of a screen (Start, Allow camera, Lock board).
- **TabBand**: icon tabs on a darker band at the top of a composer slab (3D board / 2D screen).
- **Segmented**: two or three options in a well (Hints / Analysis, White / Black / Both).
- **TopPill + EngineRing**: top bar centre status and the app mark inside an engine-depth ring.
- **ForgeChip**: option chips; selected = green hairline + glow.
- **IconTile**: 40dp squircle with bottom-lit colour, used only as a list-row leading icon.
- **Meter**: dark track with green fill (engine depth, accuracy).
- **HudPill**: solid dark pill for text over camera footage (no blur).

Icons come from Material Symbols (rounded). Chess annotation marks (`!!`, `!`, `?!`, `?`, `??`) are
set in Cal Sans because they are notation, not icons.

## Motion

150–250 ms state transitions with ease-out; pop-in with slight overshoot for AR labels. Loops are
reserved for the AR layer (ribbon shimmer, check pulse, waveform while speaking).

## AR layer (over camera)

Neon corner brackets, glowing move ribbons drawn in board perspective, floating move pills
(`Nf6 +0.26`), yellow pill for the opponent's last move, scan rings, red `CHECK` pill, quality
badges with sparks, and in Analysis: multi-line arrows, threat arrow, hanging-piece rings, a control
heat-map and the floating evaluation bar. Labels never overlap; when they would, they stack upward
with a leader line. On flat (2D) boards labels sit lower because there are no piece heights to clear.
