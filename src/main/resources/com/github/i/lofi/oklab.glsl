// Color conversions shared by the art style shaders.
// OKLab (https://bottosson.github.io/posts/oklab/): distances in it match how different colors look.

// Most colors the adaptive MS Paint palette can have. Must match PainterlyPass.MAX_ADAPTIVE_COLORS.
#define MAX_ADAPTIVE_COLORS 64

vec3 srgbToLinear(vec3 srgb) {
    return mix(
        srgb / 12.92,
        pow((srgb + vec3(0.055)) / vec3(1.055), vec3(2.4)),
        step(vec3(0.04045), srgb));
}

vec3 linearToSrgb(vec3 linear) {
    return mix(
        linear * 12.92,
        1.055 * pow(max(linear, vec3(0.0)), vec3(1.0 / 2.4)) - 0.055,
        step(vec3(0.0031308), linear));
}

vec3 srgbToOklab(vec3 srgb) {
    vec3 linear = srgbToLinear(clamp(srgb, 0.0, 1.0));
    vec3 lms = mat3(
        0.4122214708, 0.2119034982, 0.0883024619,
        0.5363325363, 0.6806995451, 0.2817188376,
        0.0514459929, 0.1073969566, 0.6299787005
    ) * linear;
    lms = pow(max(lms, vec3(0.0)), vec3(1.0 / 3.0));
    return mat3(
        0.2104542553, 1.9779984951, 0.0259040371,
        0.7936177850, -2.4285922050, 0.7827717662,
        -0.0040720468, 0.4505937099, -0.8086757660
    ) * lms;
}

vec3 oklabToSrgb(vec3 lab) {
    vec3 lms = mat3(
        1.0, 1.0, 1.0,
        0.3963377774, -0.1055613458, -0.0894841775,
        0.2158037573, -0.0638541728, -1.2914855480
    ) * lab;
    lms = lms * lms * lms;
    vec3 linear = mat3(
        4.0767416621, -1.2684380046, -0.0041960863,
        -3.3077115913, 2.6097574011, -0.7034186147,
        0.2309699292, -0.3413193965, 1.7076147010
    ) * lms;
    return clamp(linearToSrgb(linear), 0.0, 1.0);
}
