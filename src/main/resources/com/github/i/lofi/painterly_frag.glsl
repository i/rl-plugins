#version 330

// Art-style post-processing for the whole frame: 3D scene, HUD and overlays.
// The frame's alpha channel holds UI coverage (1 = HUD, 0 = scene).
// Style ids must match rs117.hd.config.PainterlyStyle. Off still runs this pass when sprite shadows need it.
#define STYLE_OFF 0
#define STYLE_SQUIGGLE 1
#define STYLE_MS_PAINT 2
#define STYLE_OIL 3
#define STYLE_ACRYLIC 4
#define STYLE_LANDSCAPE 5
#define STYLE_PAPER_CUTOUT 6

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
uniform sampler2D objectIds;   // scene object ids / 65535, covering only sceneViewport, see ObjectIds.java
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

#define OBJECT_MAX_CHARACTER 255.0
#define OBJECT_GROUND 256.0

// The object id under a pixel, see ObjectIds.java: 0 for sky, 1-255 players and NPCs, 256 the ground,
// and above that one id per scenery object.
float objectId(vec2 px) {
    if (!hasDepth)
        return 0.0;
    vec2 uv = (px - sceneViewport.xy) / sceneViewport.zw;
    if (any(lessThan(uv, vec2(0.0))) || any(greaterThan(uv, vec2(1.0))))
        return 0.0;
    return floor(texture(objectIds, uv).r * 65535.0 + 0.5);
}

// The character id under a pixel, 1-255, or 0 for anything that isn't a player or NPC
float characterId(vec2 px) {
    float id = objectId(px);
    return id <= OBJECT_MAX_CHARACTER ? id : 0.0;
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
// colorWeight and depthWeight scale color and depth lines; character shapes are always outlined in full.
float outlineWeighted(vec2 px, float width, float colorWeight, float depthWeight) {
    float centerDistance = viewDistance(px);
    // Color and UI coverage come from one read: rgb is the frame, alpha the HUD coverage
    vec4 center = texture(sceneColor, px / resolution);
    vec3 centerColor = center.rgb;
    float centerLuma = luma(centerColor);
    // The HUD has no depth of its own, so it only gets color lines, at full strength regardless of distance
    float centerUi = center.a;
    float centerId = characterId(px);
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
        float objectEdge = centerId > characterId(neighbor) ? 1.0 - ui : 0.0;

        float nearest = min(centerDistance, neighborDistance);
        float fade = mix(1.0 - smoothstep(LINE_FADE_START, LINE_FADE_END, nearest), 1.0, ui);
        edge = max(edge, max(max(depthEdge * depthWeight, colorEdge * colorWeight), objectEdge) * fade);
    }
    return edge;
}

float outline(vec2 px, float width) {
    return outlineWeighted(px, width, 1.0, 1.0);
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

// 19th century landscape painting, after Thomas Moran: warm light and cool shadows, distance fading into warm
// haze, a painted sky, detail up close and soft washes far away, and forms shaped by light rather than lines.
const vec3 LANDSCAPE_HAZE = vec3(0.93, 0.86, 0.70);
const vec3 LANDSCAPE_WARM = vec3(1.10, 0.99, 0.80);
const vec3 LANDSCAPE_COOL = vec3(0.80, 0.92, 1.02);
const float LANDSCAPE_HAZE_START = 1200.0;
const float LANDSCAPE_HAZE_END = 7000.0;

// The direction a pixel looks in, in local scene space where -y is up. Returns false outside the 3D view.
bool viewRay(vec2 px, out vec3 direction) {
    direction = vec3(0.0, 0.0, 1.0);
    if (!hasDepth)
        return false;
    vec2 uv = (px - sceneViewport.xy) / sceneViewport.zw;
    if (any(lessThan(uv, vec2(0.0))) || any(greaterThan(uv, vec2(1.0))))
        return false;
    // Any point along the pixel's ray will do. NDC depth 1 is a finite point in front of the camera for every
    // projection core uses.
    vec4 world = invProjectionMatrix * vec4(uv * 2.0 - 1.0, 1.0, 1.0);
    if (abs(world.w) < 1e-6)
        return false;
    vec3 toPoint = world.xyz / world.w - cameraPos;
    if (dot(toPoint, toPoint) < 1e-6)
        return false;
    direction = normalize(toPoint);
    return true;
}

// Soft layered clouds over a gradient, cool overhead and warm at the horizon. The clouds are painted on a plane
// high above the world, so they turn and tilt with the camera and shrink into the distance near the horizon.
vec3 paintedSky(vec2 px, vec3 gameSky) {
    vec3 direction;
    float elevation;
    vec2 p;
    if (viewRay(px, direction)) {
        elevation = -direction.y;
        // Where the ray meets the cloud plane; rays near or below the horizon are held just above it
        p = direction.xz / max(elevation, 0.06) * 0.9;
    } else {
        elevation = clamp(px.y / resolution.y, 0.0, 1.0) * 0.6;
        p = px / resolution.y * vec2(2.2, 5.0);
    }

    vec3 sky = mix(LANDSCAPE_HAZE, vec3(0.55, 0.66, 0.72), smoothstep(0.0, 0.6, elevation));
    // A little of the game's own sky color, so dark or tinted areas keep their mood
    sky = mix(sky, gameSky, 0.2);

    float clouds = valueNoise(p * 1.3) * 0.55 + valueNoise(p * 3.1 + 7.0) * 0.3 + valueNoise(p * 7.4 + 3.0) * 0.15;
    // Clouds thin out into the haze at the horizon
    float body = smoothstep(0.45, 0.75, clouds) * smoothstep(0.0, 0.12, elevation);
    // Clouds are lit from above and shaded below
    float shade = smoothstep(0.55, 0.9, valueNoise(p * 1.3 + vec2(0.0, 0.35)));
    vec3 cloud = mix(vec3(0.98, 0.95, 0.88), vec3(0.55, 0.58, 0.60), shade * 0.8);
    return mix(sky, cloud, body * 0.85);
}

vec3 landscapePainting(vec2 px, vec2 screenPx) {
    vec3 original = sampleColor(px);
    float ui = uiCoverage(px);
    vec3 position;
    bool surface = scenePosition(px, position);
    if (!surface && ui < 0.5)
        return softLight(paintedSky(px, original), mix(0.5, paperGrain(screenPx), canvasStrength));

    float distance = surface ? viewDistance(px) : 0.0;
    float far = smoothstep(LANDSCAPE_HAZE_START, LANDSCAPE_HAZE_END, distance);

    // Detail up close, soft washes in the distance
    vec2 bleed = wobbleOffset(px, 1.0 / 80.0) * mix(0.5, 2.0, far);
    vec3 wash = kuwahara(px + bleed, max(paintRadius, 2));
    vec3 color = mix(original, wash, mix(0.55, 1.0, far));
    color = limitPaints(color);
    if (hueSteps <= 0)
        color = mix(color, wash, 0.5);

    // Warm light, cool shadows, richer color
    float light = luma(color);
    color *= mix(LANDSCAPE_COOL, LANDSCAPE_WARM, smoothstep(0.15, 0.7, light));
    color = adjustSaturation(color, 1.3);
    // A gentle S-curve: deeper shadows, brighter light
    color = mix(color, color * color * (3.0 - 2.0 * color), 0.35);

    // Atmospheric perspective: distance loses contrast and dissolves into warm haze
    vec3 haze = LANDSCAPE_HAZE * mix(0.92, 1.05, light);
    color = mix(color, haze, far * 0.75);

    // Forms come from light, not lines: only characters and big depth jumps get a faint warm brown line
    float line = outlineWeighted(px + wobbleOffset(px, 1.0 / 40.0) * 0.3, lineWidth, 0.0, 0.4);
    color = mix(color, color * vec3(0.35, 0.27, 0.2), line * 0.6 * (1.0 - far));

    // Uneven wash density, pinned to surfaces so it moves with the scene
    vec2 paperPos = surface ? vec2(position.x + position.y * 0.7, position.z - position.y * 0.7) / 200.0 : px / 90.0;
    float mottle = valueNoise(paperPos) * 0.6 + valueNoise(paperPos * 3.3 + 17.0) * 0.4;
    color *= 1.0 + (mottle - 0.5) * 0.1;

    // The foreground frames the view, a little darker towards the edges of the frame
    vec2 centered = px / resolution - 0.5;
    color *= 1.0 - 0.22 * smoothstep(0.25, 0.75, dot(centered, centered) * 2.0);

    return softLight(clamp(color, 0.0, 1.0), mix(0.5, paperGrain(screenPx), canvasStrength));
}

// Paper cutout, like Archer's ransom-note look: every object is a piece of colored paper glued on top of what's
// behind it. Pieces come from the object id buffer, so they follow real shapes: each character, tree or wall is
// one piece and the ground is one sheet. Each piece has its own paper, is nudged slightly out of place, shows a
// hairline white cut edge, and casts a small shadow onto the piece below.
const float CUTOUT_SHADOW_PIXELS = 3.0;
const float CUTOUT_JITTER_PIXELS = 1.0;
const vec3 CUTOUT_EDGE_COLOR = vec3(0.97, 0.95, 0.9);

// Stacking layers: sky at the back, then the ground, scenery, and characters on top
float cutoutLayer(float id) {
    if (id <= 0.0)
        return 0.0;
    if (id == OBJECT_GROUND)
        return 1.0;
    return id > OBJECT_GROUND ? 2.0 : 3.0;
}

// Whether piece a (id, distance) visibly lies on top of piece b: a higher layer, or clearly nearer within a layer.
// Touching pieces at about the same depth, like neighboring wall segments, count as one sheet and aren't cut.
bool onTop(vec2 a, vec2 b) {
    float la = cutoutLayer(a.x);
    float lb = cutoutLayer(b.x);
    if (la != lb)
        return la > lb;
    return b.y - a.y > max(a.y * 0.08, 64.0);
}

vec2 cutoutPiece(vec2 px) {
    return vec2(objectId(px), viewDistance(px));
}

vec3 paperCutout(vec2 px, vec2 screenPx) {
    // The HUD keeps its shapes, on plain paper
    if (uiCoverage(px) > 0.5)
        return softLight(sampleColor(px), mix(0.5, paperGrain(screenPx), canvasStrength));

    // Nudge each piece a little out of place, so cuts don't line up like a clean render
    float firstId = objectId(px);
    float seed = hash12(vec2(firstId * 0.37, 11.0));
    vec2 jitter = (vec2(seed, hash12(vec2(seed, 3.7))) - 0.5) * 2.0 * CUTOUT_JITTER_PIXELS;
    vec2 at = px + jitter;
    vec2 piece = cutoutPiece(at);
    float ownSeed = hash12(vec2(piece.x * 0.37, 11.0));

    // Flat paper color with a few shading bands, so faces and clothes stay readable inside a piece
    vec3 color = kuwahara(at, max(paintRadius, 2));
    color = flattenShades(adjustSaturation(color, 1.15), 4.0);

    // Each piece is cut from its own sheet: a slight tint, and fibers running at the piece's own angle
    float angle = ownSeed * 6.2831853;
    vec2 along = vec2(cos(angle), sin(angle));
    float fibers = valueNoise(vec2(dot(screenPx, along) * 0.06, dot(screenPx, vec2(-along.y, along.x)) * 0.6));
    float flecks = hash12(floor(screenPx / 2.0) + ownSeed * 71.0);
    color *= 1.0 + (ownSeed - 0.5) * 0.1;
    color *= 1.0 + ((fibers - 0.5) * 0.12 + (flecks - 0.5) * 0.06) * mix(0.4, 1.0, canvasStrength);

    // A hairline white cut edge where this piece lies on top of a different one
    float edge = 0.0;
    for (int i = 0; i < 4; i++) {
        vec2 offset = vec2(i == 0 ? 1.0 : i == 1 ? -1.0 : 0.0, i == 2 ? 1.0 : i == 3 ? -1.0 : 0.0);
        vec2 neighbor = cutoutPiece(at + offset);
        if (neighbor.x != piece.x && onTop(piece, neighbor))
            edge = 1.0;
    }
    // Faint, and fading with distance, so the cuts separate pieces without dominating the frame
    float edgeStrength = 0.45 * (1.0 - smoothstep(LINE_FADE_START, LINE_FADE_END, piece.y));
    color = mix(color, CUTOUT_EDGE_COLOR, edge * edgeStrength);

    // A small soft shadow cast down and to the right by any piece lying on this one
    float shadow = 0.0;
    for (int i = 1; i <= 2; i++) {
        float reach = CUTOUT_SHADOW_PIXELS * float(i) / 2.0;
        vec2 caster = cutoutPiece(at + vec2(-reach, reach));
        if (caster.x != piece.x && onTop(caster, piece))
            shadow = max(shadow, 1.0 - float(i - 1) * 0.45);
    }
    color *= 1.0 - 0.3 * shadow * (1.0 - edge * edgeStrength);

    return clamp(color, 0.0, 1.0);
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
    } else if (style == STYLE_LANDSCAPE) {
        color = landscapePainting(px, screenPx);
    } else if (style == STYLE_PAPER_CUTOUT) {
        color = paperCutout(px, screenPx);
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
