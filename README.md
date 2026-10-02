# Lo-Fi

A RuneLite plugin that gives the game a hand-made, lo-fi feel: painterly art styles and slowed-down,
wobbly audio. The renderer is a copy of RuneLite's own **GPU** plugin with an art style pass added,
so it replaces GPU while it's on. Turn GPU and any other GPU renderer (such as 117 HD) off first.

## Art style

Applies to the whole frame: the game world, HUD, chat and RuneLite overlays. **HUD strength**
tones the style down on the interface if text gets hard to read.

| Style | Look |
|---|---|
| Squiggle Vision | Wobbly, redrawn outlines over flat shaded fills (Home Movies) |
| MS Paint | Chunky pixels that split along object edges, and hard black outlines at full resolution, in the classic 28-colour palette or, with **Colors** set, that many colours picked to fit the scene |
| Oil painting | Kuwahara brush smoothing on a woven canvas |
| Acrylic painting | Flat, saturated paint with thin dark line work on canvas; **Colors** limits the hues |
| Landscape painting | After Thomas Moran: warm light and cool shadows, distance fading into warm haze, a painted sky, detail up close and soft washes far away, faint lines only on characters and big depth jumps |

The scene and UI are drawn into an offscreen frame whose alpha records UI coverage, then painted
to the screen in one full-screen pass that also reads the scene's depth for outlines. Players and
NPCs are also outlined by their shape: each gets an id from 1 to 32767 in its vertices' spare short,
the scene shader writes it to a second, 16-bit colour buffer, and the pass draws a line wherever it changes,
so characters stay separate from ground of the same colour. **Model outlines** turns all of these
lines off. If the depth copy fails, which is logged once, lines are drawn without distance fading.

MS Paint's adaptive palette is picked on the GPU with k-means clustering in OKLab: each frame is
shrunk to 64x36 samples, and last frame's palette takes one clustering step towards them, so colours
follow the view smoothly instead of flickering. Code: `AdaptivePalette.java` and `palette_frag.glsl`.

Code: `PainterlyPass.java` (the pass) and `painterly_frag.glsl` (the styles). Its per-pixel cost
is a few dozen texture reads; Kuwahara reads land between pixel pairs, so each averages a 2x2
block.

## Performance

**Render scale** (25-100%, default 100%) draws the 3D scene and the art style at that share of the
screen's resolution, then stretches the result to fit; MS Paint stretches with hard pixels, the
other styles smoothly. The interface is drawn afterwards at full resolution, so it stays sharp, but
that means **HUD strength** has no effect below 100%. At 50% there are a quarter of the pixels to
draw, style and copy, which is the biggest single speed-up on integrated GPUs and high resolution
screens. Sizes in the art style settings (brush size, outline thickness) are in scaled pixels, so
they look larger on screen at lower scales.

## Chat bubbles

**Chat bubbles** (on by default, in the Art style section) draws overhead text, like public chat
and NPC shouts, as comic book speech bubbles with a tail pointing at the speaker. The game's own
overhead text is replaced with a blank, like RuneLite's chat filter does, so the chat effects (wave, scroll, colours)
are not shown. Bubbles are part of the UI, so **HUD strength** decides how much the art style
paints them. Code: `ChatBubbleOverlay.java`.

## Blocky health bars

**Blocky health bars** (on by default, in the Art style section) replaces the game's health bars
with taller ones: a row of green blocks for the health left, then solid red for what's missing.
Bars are wider for things with more total hitpoints, growing with its square root from 24 to 160
pixels, with one block per hitpoint when they fit. NPC hitpoints come from RuneLite's NPC data and
yours from your Hitpoints level; other players don't share theirs, so they get a 50 hp bar. The
game's bars are hidden by blanking their sprites as the client loads them.

The same setting draws hitsplats as matching square tiles, coloured by type (red damage, blue
block, green poison, pink heal and so on), with a gold rim on max hits and darker tiles for other
players' hits. The API can't hide the game's hitsplats, so each tile is drawn opaque over the slot
the game uses, big enough to cover its splat. Code: `HealthBarOverlay.java`, `HitsplatOverlay.java`.

## Sprites

The **Sprites** section draws players and NPCs as flat cut-outs, like RuneScape Classic or Paper
Mario. Each frame, every actor's facing is snapped to one of 8 views relative to the camera (or
the left and right sides in **2 directions** mode, with a paper flip between them), and its model is
squashed into a card facing the camera as it's uploaded. **Pin viewing angle** draws every sprite as
if seen from the same height, however far the camera tilts. NPCs larger than **Max NPC size** and
anything in **Keep 3D** (names or IDs) stay 3D. **Round shadows** draws a soft shadow under each
sprite in the art style pass, which then runs even with the art style off. A hotkey toggles sprites.

Flattening turns some faces away from the camera, so each sprite face is also drawn reversed and
pushed slightly behind the card: it fills the holes culling would leave, without covering the
front. Depth bias is skipped on sprites, since the card is far thinner than the bias.

Limits: the game tests clicks against the real 3D model at its real facing, and that can't be
turned off, so the renderer also click tests the sprite's own flattened shape: a sprite is
clickable wherever it's drawn, plus wherever the hidden 3D model would be. Highlight outlines from
other plugins still follow the 3D model. **Highlight outlines** (on by default) outlines sprites
instead: the character under the mouse, the one you're interacting with, and NPCs highlighted by NPC
Indicators, in its colour. The art style pass draws them around each character's pixels in the
object id buffer, and NPC Indicators' list is read by reflection from RuneLite's `NpcOverlayService`.
Turn off Interact Highlight's and NPC Indicators' own outlines while sprites are on. Code:
`SpriteHighlights.java`. Actors on boats (other world views) stay 3D.

Code: `SpriteManager.java` (facings and the flattening view) and `ModelUploader.java` (flattening
during upload).

## Lo-fi audio

Plays the game's audio slower (**Playback speed**, 92% by default) while the speed drifts a
little slower and faster around that (**Wobble**), bending pitch and tempo together like a worn
tape. **Tape saturation**, **Low cut** and **High cut** give it a warm, muffled tone, and **Grit**
(40% by default) makes it sound like cheap gear: tape hiss and the odd crackle, harder drive, and a
sample-and-hold and bit crusher like an old sampler (down to about 9 kHz and 6 bits at 100%).

The game renders audio only when its output line has free space. The plugin swaps that line for
one that buffers what the game writes and drains it at the wobbling speed through a resampler, so
the game itself renders slower. The audio classes are found by type at runtime, and if anything
fails the game's audio is left untouched.

Code: `audio/LofiAudio.java` (finding and swapping lines), `audio/LofiLine.java` (buffering and
resampling), `audio/Wobble.java` (the speed curve) and `audio/TapeTone.java` (filters and
saturation).

## Development

Run the dev client with a JDK 11-21 (Gradle 8.10 does not run on newer JDKs):

```
JAVA_HOME=/path/to/jdk-21 ./gradlew run
```

The renderer tracks RuneLite master's GPU plugin (copied at a5c2494), so the build uses the newest
development client (`1.13.+`). When RuneLite starts a new version line, bump `runeLiteVersion`:
the old snapshot stops getting game updates, and interactions such as mining or talking to NPCs
silently stop working in the dev client.

## Credits

The renderer is RuneLite's GPU plugin, BSD licensed, by Adam and the RuneLite contributors.
