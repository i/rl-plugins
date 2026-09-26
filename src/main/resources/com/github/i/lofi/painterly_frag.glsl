#version 330

// Art-style post-processing for the whole frame: 3D scene, HUD and overlays.
// The frame's alpha channel holds UI coverage (1 = HUD, 0 = scene).
// Style ids must match rs117.hd.config.PainterlyStyle. Off still runs this pass when sprite shadows need it.
#define STYLE_OFF 0
#define STYLE_SQUIGGLE 1
#define STYLE_MS_PAINT 2
#define STYLE_OIL 3
#define STYLE_ACRYLIC 4

// Debug view ids must match rs117.hd.config.PainterlyDebugView.
#define DEBUG_SCENE 1
#define DEBUG_OUTLINES 2
#define DEBUG_DISTANCE 3

uniform mat4 invProjectionMatrix; // scene clip space back to local scene space
uniform vec3 cameraPos;           // camera position in local scene space

#include oklab.glsl

// Hue, saturation and value, each 0..1
vec3 srgbToHsv(vec3 c) {
    vec4 K = vec4(0.0, -1.0 / 3.0, 2.0 / 3.0, -1.0);
    vec4 p = mix(vec4(c.bg, K.wz), vec4(c.gb, K.xy), step(c.b, c.g));
    vec4 q = mix(vec4(p.xyw, c.r), vec4(c.r, p.yzx), step(p.x, c.r));
    float d = q.x - min(q.w, q.y);
    float e = 1.0e-10;
    return vec3(abs(q.z + (q.w - q.y) / (6.0 * d + e)), d / (q.x + e), q.x);
}

vec3 hsvToSrgb(vec3 c) {
    vec3 p = abs(fract(c.xxx + vec3(1.0, 2.0 / 3.0, 1.0 / 3.0)) * 6.0 - 3.0);
    return c.z * mix(vec3(1.0), clamp(p - 1.0, 0.0, 1.0), c.y);
}

uniform sampler2D sceneColor;  // the full frame, UI coverage in alpha
uniform sampler2D sceneDepth;  // scene depth, covering only sceneViewport
uniform sampler2D objectIds;   // scene object ids / 255, covering only sceneViewport: players and NPCs 1-255, else 0
uniform vec2 resolution;       // frame size in pixels
uniform vec4 sceneViewport;    // x, y, width, height of the 3D scene within the frame
uniform bool hasDepth;
uniform float hudStrength;     // 0..1 how strongly the style applies to UI pixels
uniform int style;
uniform int debugView;
uniform float boilTime;        // changes a few times per second, seeds the wobble
uniform float wobble;          // maximum wobble offset in pixels
uniform float lineWidth;       // outline thickness in pixels
uniform int paintRadius;       // brush radius, or pixel size for MS Paint
uniform float canvasStrength;  // 0..1 paper/canvas grain strength
uniform int hueSteps;          // number of hues the acrylic style paints with, 0 = unlimited
uniform sampler2D adaptivePalette; // MS Paint colors picked from the frame, in OKLab, one per texel of row 0
uniform int adaptiveColors;       // how many adaptivePalette colors to use, 0 for the classic palette

// Round shadows under sprites, as (x, y, z, radius) in local scene space. Must match SpriteManager.MAX_SHADOWS.
#define MAX_SPRITE_SHADOWS 32
uniform int spriteShadowCount;
uniform vec4 spriteShadows[MAX_SPRITE_SHADOWS];

in vec2 fUv;

out vec4 FragColor;

// Outlines fade out with distance so far-off scenery doesn't turn into line noise.
// Distances are in game units, where one tile is 128 units.
const float LINE_FADE_START = 2500.0;
const float LINE_FADE_END = 7000.0;

// Relative depth jump (in log space) that counts as a silhouette
const float DEPTH_EDGE_LOW = 0.04;
const float DEPTH_EDGE_HIGH = 0.10;

// Color difference that counts as an inner line. Kept high so textures don't turn into scribbles.
const float COLOR_EDGE_LOW = 0.20;
const float COLOR_EDGE_HIGH = 0.40;

// Classic 28-color MS Paint palette
const int PALETTE_SIZE = 28;
const vec3 MS_PAINT_PALETTE[PALETTE_SIZE] = vec3[](
    vec3(0.000, 0.000, 0.000), vec3(0.502, 0.502, 0.502), vec3(0.502, 0.000, 0.000), vec3(0.502, 0.502, 0.000),
    vec3(0.000, 0.502, 0.000), vec3(0.000, 0.502, 0.502), vec3(0.000, 0.000, 0.502), vec3(0.502, 0.000, 0.502),
    vec3(0.502, 0.502, 0.251), vec3(0.000, 0.251, 0.251), vec3(0.000, 0.502, 1.000), vec3(0.000, 0.251, 0.502),
    vec3(0.502, 0.000, 1.000), vec3(0.502, 0.251, 0.000),
    vec3(1.000, 1.000, 1.000), vec3(0.753, 0.753, 0.753), vec3(1.000, 0.000, 0.000), vec3(1.000, 1.000, 0.000),
    vec3(0.000, 1.000, 0.000), vec3(0.000, 1.000, 1.000), vec3(0.000, 0.000, 1.000), vec3(1.000, 0.000, 1.000),
    vec3(1.000, 1.000, 0.502), vec3(0.000, 1.000, 0.502), vec3(0.502, 1.000, 1.000), vec3(0.502, 0.502, 1.000),
    vec3(1.000, 0.000, 0.502), vec3(1.000, 0.502, 0.251)
);

const vec2 OUTLINE_DIRECTIONS[8] = vec2[](
    vec2(1, 0), vec2(-1, 0), vec2(0, 1), vec2(0, -1),
    vec2(0.7071, 0.7071), vec2(-0.7071, 0.7071), vec2(0.7071, -0.7071), vec2(-0.7071, -0.7071)
);

// ---------------------------------------------------------------- noise

float hash12(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

float valueNoise(vec2 p) {
    vec2 cell = floor(p);
    vec2 f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f);
    float a = hash12(cell);
    float b = hash12(cell + vec2(1, 0));
    float c = hash12(cell + vec2(0, 1));
    float d = hash12(cell + vec2(1, 1));
    return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}

// Smooth random offset in pixels. The pattern only changes when boilTime ticks over,
// which is what makes lines look redrawn by hand rather than swimming.
vec2 wobbleOffset(vec2 px, float frequency) {
    vec2 seed = vec2(boilTime * 17.13, boilTime * 5.71);
    vec2 n = vec2(
        valueNoise(px * frequency + seed),
        valueNoise(px * frequency + seed + vec2(41.7, 93.1))
    );
    return (n - 0.5) * 2.0 * wobble;
}

// ---------------------------------------------------------------- frame sampling

vec3 sampleColor(vec2 px) {
    return texture(sceneColor, px / resolution).rgb;
}

float uiCoverage(vec2 px) {
    return texture(sceneColor, px / resolution).a;
}

// Positions and distances are reconstructed with the scene's inverse view-projection matrix, so they are correct
// for every projection 117 HD uses (finite, infinite, orthographic).
// The sky, and anything outside the 3D viewport, counts as infinitely far away.
const float SKY_DISTANCE = 1e6;

// Local scene position of the surface under a pixel. Returns false for the sky and outside the 3D viewport.
bool scenePosition(vec2 px, out vec3 position) {
    position = vec3(0.0);
    if (!hasDepth)
        return false;

    vec2 uv = (px - sceneViewport.xy) / sceneViewport.zw;
    if (any(lessThan(uv, vec2(0.0))) || any(greaterThan(uv, vec2(1.0))))
        return false;

    float depth = texture(sceneDepth, uv).r;
    if (depth <= 0.0)
        return false;

    // Default glDepthRange: window depth 0..1 maps to NDC -1..1
    vec4 world = invProjectionMatrix * vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    if (abs(world.w) < 1e-6)
        return false;
    position = world.xyz / world.w;
    return true;
}

// The object id under a pixel, 0-255: players and NPCs have their own, everything else is 0.
float objectId(vec2 px) {
    if (!hasDepth)
        return 0.0;
    vec2 uv = (px - sceneViewport.xy) / sceneViewport.zw;
    if (any(lessThan(uv, vec2(0.0))) || any(greaterThan(uv, vec2(1.0))))
        return 0.0;
    return floor(texture(objectIds, uv).r * 255.0 + 0.5);
}

// Distance from the camera in game units, where one tile is 128 units.
float viewDistance(vec2 px) {
    vec3 position;
    if (!scenePosition(px, position))
        return SKY_DISTANCE;
    return min(distance(position, cameraPos), SKY_DISTANCE);
}

// Only surfaces within this height of a sprite's feet receive its shadow, so walls and the sprite itself don't
const float SPRITE_SHADOW_HEIGHT = 24.0;
const float SPRITE_SHADOW_DARKNESS = 0.45;

// How much a pixel is darkened by the soft round shadows under sprites, 0..1.
float spriteShadow(vec2 px) {
    vec3 position;
    if (spriteShadowCount <= 0 || !scenePosition(px, position))
        return 0.0;

    float shadow = 0.0;
    for (int i = 0; i < spriteShadowCount && i < MAX_SPRITE_SHADOWS; i++) {
        vec4 sprite = spriteShadows[i];
        vec2 offset = (position.xz - sprite.xz) / sprite.w;
        float height = abs(position.y - sprite.y);
        float falloff = 1.0 - smoothstep(0.55, 1.0, dot(offset, offset));
        shadow = max(shadow, falloff * (1.0 - smoothstep(0.5, 1.0, height / SPRITE_SHADOW_HEIGHT)));
    }
    return shadow * SPRITE_SHADOW_DARKNESS;
}

float luma(vec3 color) {
    return dot(color, vec3(0.299, 0.587, 0.114));
}

// ---------------------------------------------------------------- building blocks

// 0..1 outline coverage. Silhouettes come from depth jumps, inner lines from color jumps.
// Each test is one-sided (only the nearer or darker pixel draws), so lines are lineWidth thick, not double.
float outline(vec2 px, float width) {
    float centerDistance = viewDistance(px);
    // Color and UI coverage come from one read: rgb is the frame, alpha the HUD coverage
    vec4 center = texture(sceneColor, px / resolution);
    vec3 centerColor = center.rgb;
    float centerLuma = luma(centerColor);
    // The HUD has no depth of its own, so it only gets color lines, at full strength regardless of distance
    float centerUi = center.a;
    float centerId = objectId(px);
    float edge = 0.0;

    for (int i = 0; i < 8; i++) {
        vec2 neighbor = px + OUTLINE_DIRECTIONS[i] * width;
        float neighborDistance = viewDistance(neighbor);
        vec4 neighborSample = texture(sceneColor, neighbor / resolution);
        vec3 neighborColor = neighborSample.rgb;
        float ui = max(centerUi, neighborSample.a);

        float depthJump = log(neighborDistance) - log(centerDistance);
        float depthEdge = smoothstep(DEPTH_EDGE_LOW, DEPTH_EDGE_HIGH, depthJump) * (1.0 - ui);

        float colorJump = luma(neighborColor) > centerLuma ? length(neighborColor - centerColor) : 0.0;
        float colorEdge = smoothstep(COLOR_EDGE_LOW, COLOR_EDGE_HIGH, colorJump) * 0.8;

        // Players and NPCs are outlined by their shape even where their colors and depth match what's behind
        // them. Only the higher id draws, so lines between two characters stay one line thick.
        float objectEdge = centerId > objectId(neighbor) ? 1.0 - ui : 0.0;

        float nearest = min(centerDistance, neighborDistance);
        float fade = mix(1.0 - smoothstep(LINE_FADE_START, LINE_FADE_END, nearest), 1.0, ui);
        edge = max(edge, max(max(depthEdge, colorEdge), objectEdge) * fade);
    }
    return edge;
}

// Generalized Kuwahara: average the quadrant around px with the least color variance.
// Flattens surfaces into paint-like patches while keeping edges sharp.
//
// Samples land between pixel pairs two pixels apart, so linear filtering averages a 2x2 block in each read: a
// quarter of the reads for the same area. Variance within each block is lost, which only makes the quadrant choice
// slightly less picky.
vec3 kuwahara(vec2 px, int radius) {
    if (radius <= 0)
        return sampleColor(px);

    // Reads per axis covering pixels 0..radius
    int steps = (radius + 2) / 2;
    float count = float(steps * steps);
    vec3 bestMean = vec3(0);
    float bestVariance = 1e9;

    for (int quadrant = 0; quadrant < 4; quadrant++) {
        vec2 direction = vec2((quadrant & 1) == 0 ? -1.0 : 1.0, (quadrant & 2) == 0 ? -1.0 : 1.0);
        vec3 sum = vec3(0);
        vec3 sumSquared = vec3(0);
        for (int y = 0; y < steps; y++) {
            for (int x = 0; x < steps; x++) {
                vec3 color = sampleColor(px + direction * (vec2(x, y) * 2.0 + 0.5));
                sum += color;
                sumSquared += color * color;
            }
        }
        vec3 mean = sum / count;
        vec3 variance = sumSquared / count - mean * mean;
        float totalVariance = variance.r + variance.g + variance.b;
        if (totalVariance < bestVariance) {
            bestVariance = totalVariance;
            bestMean = mean;
        }
    }
    return bestMean;
}

// Snap brightness to a few flat shades while keeping the hue, like cel paint.
vec3 flattenShades(vec3 color, float levels) {
    float value = max(max(color.r, color.g), color.b);
    if (value <= 1e-4)
        return color;
    float snapped = (floor(value * levels) + 0.5) / levels;
    return color * (snapped / value);
}

vec3 adjustSaturation(vec3 color, float amount) {
    return clamp(mix(vec3(luma(color)), color, amount), 0.0, 1.0);
}

// Soft light blend (pegtop). A grain value of 0.5 leaves the base unchanged.
vec3 softLight(vec3 base, float grain) {
    return (1.0 - 2.0 * grain) * base * base + 2.0 * grain * base;
}

// Static paper grain in screen space: fine speckle plus faint fibers.
float paperGrain(vec2 screenPx) {
    float speckle = hash12(floor(screenPx));
    float fibers = valueNoise(screenPx * vec2(0.08, 0.35));
    return mix(0.5, speckle * 0.5 + fibers * 0.5, 0.6);
}

// Woven canvas in screen space: crossing threads plus irregularity.
float canvasWeave(vec2 screenPx) {
    float threadsX = sin(screenPx.x * 1.4 + valueNoise(screenPx * 0.1) * 2.0);
    float threadsY = sin(screenPx.y * 1.4 + valueNoise(screenPx.yx * 0.1) * 2.0);
    float weave = 0.5 + 0.18 * threadsX * threadsY;
    return weave + (hash12(floor(screenPx)) - 0.5) * 0.12;
}

// How much hue and saturation count against lightness when matching. The palette has few muted colors, so
// without the extra weight OSRS's muted greens and browns would mostly match greys.
const vec3 PALETTE_MATCH_WEIGHTS = vec3(1.0, 2.2, 2.2);

// MS_PAINT_PALETTE converted with srgbToOklab ahead of time, so matching doesn't convert 28 colors per pixel
const vec3 MS_PAINT_PALETTE_OKLAB[PALETTE_SIZE] = vec3[](
    vec3(0.000000, 0.000000, 0.000000),
    vec3(0.599905, 0.000000, 0.000000),
    vec3(0.376713, 0.134896, 0.075496),
    vec3(0.580697, -0.042815, 0.119123),
    vec3(0.519781, -0.140310, 0.107682),
    vec3(0.543153, -0.089652, -0.023635),
    vec3(0.271165, -0.019471, -0.186887),
    vec3(0.420937, 0.164714, -0.101477),
    vec3(0.585343, -0.027808, 0.081712),
    vec3(0.336368, -0.055521, -0.014637),
    vec3(0.615186, -0.050654, -0.204631),
    vec3(0.376325, -0.032384, -0.118193),
    vec3(0.530468, 0.118946, -0.267900),
    vec3(0.444361, 0.062030, 0.090139),
    vec3(1.000000, 0.000000, 0.000000),
    vec3(0.807843, 0.000000, 0.000000),
    vec3(0.627955, 0.224863, 0.125846),
    vec3(0.967983, -0.071369, 0.198570),
    vec3(0.866440, -0.233888, 0.179498),
    vec3(0.905399, -0.149444, -0.039398),
    vec3(0.452014, -0.032457, -0.311528),
    vec3(0.701674, 0.274566, -0.169156),
    vec3(0.975025, -0.048308, 0.141286),
    vec3(0.875076, -0.205408, 0.112987),
    vec3(0.927774, -0.109285, -0.030053),
    vec3(0.661376, 0.032318, -0.180855),
    vec3(0.645352, 0.260104, 0.011209),
    vec3(0.734917, 0.121858, 0.122264)
);

vec3 nearestPaletteColor(vec3 color) {
    vec3 target = srgbToOklab(color);
    int best = 0;
    float bestDistance = 1e9;
    for (int i = 0; i < PALETTE_SIZE; i++) {
        vec3 diff = (MS_PAINT_PALETTE_OKLAB[i] - target) * PALETTE_MATCH_WEIGHTS;
        float paletteDistance = dot(diff, diff);
        if (paletteDistance < bestDistance) {
            bestDistance = paletteDistance;
            best = i;
        }
    }
    return MS_PAINT_PALETTE[best];
}

// ---------------------------------------------------------------- styles

vec3 squiggleVision(vec2 px, vec2 screenPx) {
    vec2 wiggle = wobbleOffset(px, 1.0 / 35.0);

    // Fills wobble less than lines, like color painted loosely inside redrawn outlines
    vec3 fill = kuwahara(px + wiggle * 0.5, paintRadius);
    fill = adjustSaturation(flattenShades(fill, 5.0), 1.15);

    float line = outline(px + wiggle, lineWidth);
    vec3 ink = fill * 0.12;
    vec3 color = mix(fill, ink, line);

    return softLight(color, mix(0.5, paperGrain(screenPx), canvasStrength));
}

// Nearest of the colors PainterlyPass picked from the frame, see palette_frag.glsl
vec3 nearestAdaptiveColor(vec3 color) {
    vec3 target = srgbToOklab(color);
    vec3 best = target;
    float bestDistance = 1e9;
    for (int i = 0; i < adaptiveColors && i < MAX_ADAPTIVE_COLORS; i++) {
        vec3 candidate = texelFetch(adaptivePalette, ivec2(i, 0), 0).rgb;
        vec3 diff = candidate - target;
        float paletteDistance = dot(diff, diff);
        if (paletteDistance < bestDistance) {
            bestDistance = paletteDistance;
            best = candidate;
        }
    }
    return oklabToSrgb(best);
}

vec3 msPaint(vec2 px) {
    // Chunky pixels: snap to a grid of pixelSize scene pixels
    float pixelSize = float(max(paintRadius, 1));
    vec2 cell = (floor(px / pixelSize) + 0.5) * pixelSize;

    vec3 color = sampleColor(cell);
    vec3 fill = adaptiveColors > 0 ? nearestAdaptiveColor(color) : nearestPaletteColor(color);

    // Hard, aliased black outlines with only a little wobble
    float line = step(0.5, outline(cell + wobbleOffset(cell, 1.0 / 60.0) * 0.5, lineWidth));
    return mix(fill, vec3(0), line);
}

vec3 oilPainting(vec2 px, vec2 screenPx) {
    // Brush jitter: small, high-frequency displacement
    vec2 jitter = wobbleOffset(px, 1.0 / 10.0) * 0.5;
    vec3 paint = kuwahara(px + jitter, max(paintRadius, 1));

    // Soft dark edges where forms overlap
    paint *= 1.0 - 0.35 * outline(px, lineWidth);

    // Warm highlights, slightly richer color
    paint = mix(paint, paint * vec3(1.06, 1.0, 0.9), luma(paint));
    paint = adjustSaturation(paint, 1.1);

    return softLight(paint, mix(0.5, canvasWeave(screenPx), canvasStrength));
}

// Brightness levels per paint, and saturation levels when hues are limited
const float PAINT_SHADE_STEPS = 6.0;
const float PAINT_SATURATION_STEPS = 3.0;

// Snap a color to a limited set of paints: hueSteps evenly spaced hues, a few saturations and a
// few shades. With hueSteps at 0, only the shade is snapped and hue and saturation stay continuous.
vec3 limitPaints(vec3 color) {
    if (hueSteps <= 0)
        return flattenShades(color, PAINT_SHADE_STEPS);

    vec3 hsv = srgbToHsv(clamp(color, 0.0, 1.0));
    // Rounding (not flooring) keeps pure red at hue 0; hsvToSrgb wraps hue 1.0 back to red
    hsv.x = round(hsv.x * float(hueSteps)) / float(hueSteps);
    hsv.y = round(hsv.y * PAINT_SATURATION_STEPS) / PAINT_SATURATION_STEPS;
    hsv.z = (floor(hsv.z * PAINT_SHADE_STEPS) + 0.5) / PAINT_SHADE_STEPS;
    return hsvToSrgb(hsv);
}

// Flat, saturated paint with thin dark line work on canvas, like an acrylic painting of the game.
vec3 acrylicPainting(vec2 px, vec2 screenPx) {
    // Opaque flat fills: smooth away texture detail, then snap to a limited set of paints
    vec3 paint = kuwahara(px, max(paintRadius, 2));
    paint = adjustSaturation(paint, 1.35);
    paint = limitPaints(paint);
    // Slightly brighter, like opaque paint over a white canvas
    paint = pow(paint, vec3(0.9));

    // Hand-painted unevenness: soft variation in paint thickness, and faint brush streaks
    float mottle = valueNoise(px / 18.0) - 0.5;
    float streaks = valueNoise(vec2(dot(px, vec2(0.8, 0.6)) / 2.5, dot(px, vec2(-0.6, 0.8)) / 30.0)) - 0.5;
    paint *= 1.0 + mottle * 0.08 + streaks * 0.05;

    // Outlines painted in a darker shade of the local color rather than black
    float line = outline(px + wobbleOffset(px, 1.0 / 45.0) * 0.5, lineWidth);
    paint = mix(paint, paint * 0.25, line * 0.85);

    return softLight(clamp(paint, 0.0, 1.0), mix(0.5, canvasWeave(screenPx), canvasStrength));
}

void main() {
    vec2 px = fUv * resolution;
    vec2 screenPx = gl_FragCoord.xy;
    vec3 original = sampleColor(px);

    if (debugView == DEBUG_SCENE) {
        FragColor = vec4(original, 1.0);
        return;
    }
    if (debugView == DEBUG_OUTLINES) {
        FragColor = vec4(vec3(1.0 - outline(px, lineWidth)), 1.0);
        return;
    }
    if (debugView == DEBUG_DISTANCE) {
        // Black up close, white where outlines have fully faded out
        FragColor = vec4(vec3(clamp(viewDistance(px) / LINE_FADE_END, 0.0, 1.0)), 1.0);
        return;
    }

    vec3 color;
    if (style == STYLE_OFF) {
        color = original;
    } else if (style == STYLE_MS_PAINT) {
        color = msPaint(px);
    } else if (style == STYLE_OIL) {
        color = oilPainting(px, screenPx);
    } else if (style == STYLE_ACRYLIC) {
        color = acrylicPainting(px, screenPx);
    } else {
        color = squiggleVision(px, screenPx);
    }

    // Scene pixels are always fully styled, HUD pixels by hudStrength
    float ui = uiCoverage(px);
    float strength = mix(1.0, hudStrength, ui);
    color = mix(original, color, strength);

    // Sprite shadows sit on top of the painted ground, and never on the HUD
    color *= 1.0 - spriteShadow(px) * (1.0 - ui);
    FragColor = vec4(color, 1.0);
}
