#version 330

// Picks the adaptive MS Paint palette from the frame with k-means clustering in OKLab.
// Drawn into a paletteSize x 1 target: each pixel is one palette color, in OKLab. Each pass moves every color
// towards the average of the frame samples nearest to it (one Lloyd iteration). The palette carries over between
// frames, so colors drift smoothly instead of being picked from scratch and flickering.

#include oklab.glsl

uniform sampler2D samples;  // the frame, downsampled, in sRGB
uniform sampler2D previous; // the palette from the last pass, in OKLab
uniform int paletteSize;
uniform bool seeding;       // pick starting colors from the samples instead of refining the previous palette
uniform float blend;        // 0..1 how far each color moves towards its cluster's average per pass

out vec4 FragColor;

void main() {
    int self = int(gl_FragCoord.x);
    ivec2 size = textureSize(samples, 0);
    int total = size.x * size.y;

    if (seeding) {
        // Spread starting colors across the frame with golden ratio steps, so they rarely land on the same spot
        int index = int(fract(float(self) * 0.61803398875 + 0.5) * float(total));
        ivec2 position = ivec2(index % size.x, index / size.x);
        FragColor = vec4(srgbToOklab(texelFetch(samples, position, 0).rgb), 1.0);
        return;
    }

    vec3 palette[MAX_ADAPTIVE_COLORS];
    int count = min(paletteSize, MAX_ADAPTIVE_COLORS);
    for (int i = 0; i < count; i++)
        palette[i] = texelFetch(previous, ivec2(i, 0), 0).rgb;

    vec3 sum = vec3(0.0);
    float members = 0.0;
    for (int y = 0; y < size.y; y++) {
        for (int x = 0; x < size.x; x++) {
            vec3 color = srgbToOklab(texelFetch(samples, ivec2(x, y), 0).rgb);
            int nearest = 0;
            float nearestDistance = 1e9;
            for (int i = 0; i < count; i++) {
                vec3 diff = palette[i] - color;
                float d = dot(diff, diff);
                if (d < nearestDistance) {
                    nearestDistance = d;
                    nearest = i;
                }
            }
            if (nearest == self) {
                sum += color;
                members += 1.0;
            }
        }
    }

    // A color nothing is near keeps its place, ready for when the view changes
    vec3 mine = palette[self];
    vec3 next = members > 0.0 ? mix(mine, sum / members, blend) : mine;
    FragColor = vec4(next, 1.0);
}
