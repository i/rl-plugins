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
| MS Paint | Chunky pixels and hard black outlines, in the classic 28-colour palette or, with **Colors** set, that many colours picked to fit the scene |
| Oil painting | Kuwahara brush smoothing on a woven canvas |
| Acrylic painting | Flat, saturated paint with thin dark line work on canvas; **Colors** limits the hues |

The scene and UI are drawn into an offscreen frame whose alpha records UI coverage, then painted
to the screen in one full-screen pass that also reads the scene's depth for outlines.

MS Paint's adaptive palette is picked on the GPU with k-means clustering in OKLab: each frame is
shrunk to 64x36 samples, and last frame's palette takes one clustering step towards them, so colours
follow the view smoothly instead of flickering. Code: `AdaptivePalette.java` and `palette_frag.glsl`.

Code: `PainterlyPass.java` (the pass) and `painterly_frag.glsl` (the styles). Its per-pixel cost
is a few dozen texture reads; Kuwahara reads land between pixel pairs, so each averages a 2x2
block.

## Lo-fi audio

Plays the game's audio slower (**Playback speed**, 92% by default) while the speed drifts a
little slower and faster around that (**Wobble**), bending pitch and tempo together like a worn
tape. **Tape saturation**, **Low cut** and **High cut** give it a warm, muffled tone.

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
1.12 client.

## Credits

The renderer is RuneLite's GPU plugin, BSD licensed, by Adam and the RuneLite contributors.
