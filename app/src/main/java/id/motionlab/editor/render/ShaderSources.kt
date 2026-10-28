package id.motionlab.editor.render

/**
 * Shader GLSL ES 1.00. Satu "uber shader" per jenis tekstur (2D untuk gambar/teks/bentuk,
 * OES eksternal untuk video) sehingga setiap layer digambar dalam SATU draw call
 * lengkap dengan warp, blur, glow, color grading, LUT, vignette, dan transisi.
 * Keluaran premultiplied alpha (blend: ONE, ONE_MINUS_SRC_ALPHA).
 */
object ShaderSources {

    const val VERTEX = """
attribute vec2 aPos;
uniform vec2 uSize;
uniform vec2 uPos;
uniform float uAspect;
uniform vec2 uRot;
varying vec2 vLocal;
void main() {
    vLocal = aPos + 0.5;
    vec2 local = vec2(aPos.x * uSize.x * uAspect, aPos.y * uSize.y);
    vec2 r = vec2(local.x * uRot.x - local.y * uRot.y, local.x * uRot.y + local.y * uRot.x);
    vec2 world = r + vec2(uPos.x * uAspect, uPos.y);
    gl_Position = vec4(2.0 * world.x / uAspect, -2.0 * world.y, 0.0, 1.0);
}
"""

    fun fragment(external: Boolean): String {
        val header = if (external) "#extension GL_OES_EGL_image_external : require\n" else ""
        val sampler = if (external) "samplerExternalOES" else "sampler2D"
        val fetchBody = if (external) {
            "vec2 q = clamp(p, 0.002, 0.998);\n    return texture2D(uTex, (uSt * vec4(q.x, 1.0 - q.y, 0.0, 1.0)).xy);"
        } else {
            "return texture2D(uTex, clamp(p, 0.001, 0.999));"
        }
        return header + """
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
varying vec2 vLocal;
uniform $sampler uTex;
uniform mat4 uSt;
uniform sampler2D uLut;
uniform float uOpacity;
uniform float uBrightness;
uniform float uContrast;
uniform float uSaturation;
uniform float uTemperature;
uniform float uVignette;
uniform float uLutMix;
uniform float uWave;
uniform float uSwirl;
uniform float uDisplace;
uniform float uBlur;
uniform float uGlow;
uniform float uGlitch;
uniform float uWipe;
uniform float uTime;
uniform vec3 uWarpOrder;

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
}
float noise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash(i), hash(i + vec2(1.0, 0.0)), u.x),
               mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0, 1.0)), u.x), u.y);
}
vec4 fetch(vec2 p) {
    $fetchBody
}
vec3 lutLookup(vec3 c) {
    c = clamp(c, 0.0, 1.0);
    float b = c.b * 63.0;
    float b0 = floor(b);
    float b1 = min(b0 + 1.0, 63.0);
    vec2 t0 = vec2(mod(b0, 8.0), floor(b0 / 8.0));
    vec2 t1 = vec2(mod(b1, 8.0), floor(b1 / 8.0));
    vec2 xy = vec2(0.5) + c.rg * 63.0;
    vec2 uv0 = (t0 * 64.0 + xy) / 512.0;
    vec2 uv1 = (t1 * 64.0 + xy) / 512.0;
    return mix(texture2D(uLut, uv0).rgb, texture2D(uLut, uv1).rgb, b - b0);
}
void main() {
    if (vLocal.x > uWipe) discard;
    vec2 uv = vLocal;
    float tm = uTime;

    if (uGlitch > 0.001) {
        float slot = floor(tm * 18.0);
        float band = floor(uv.y * 20.0);
        float n = hash(vec2(band, slot));
        if (n > 0.72) { uv.x += (n - 0.86) * 0.35 * uGlitch; }
    }
    for (int oi = 0; oi < 3; oi++) {
        float kk = oi == 0 ? uWarpOrder.x : (oi == 1 ? uWarpOrder.y : uWarpOrder.z);
        if (kk < 0.5) {
            if (uWave > 0.001) {
                uv.x += sin(uv.y * 20.0 + tm * 5.0) * 0.035 * uWave;
                uv.y += sin(uv.x * 20.0 + tm * 4.0) * 0.035 * uWave;
            }
        } else if (kk < 1.5) {
            if (abs(uSwirl) > 0.001) {
                vec2 d = uv - vec2(0.5);
                float r = length(d);
                float ang = uSwirl * 3.0 * smoothstep(0.7, 0.0, r);
                float cs = cos(ang);
                float sn = sin(ang);
                uv = vec2(0.5) + vec2(d.x * cs - d.y * sn, d.x * sn + d.y * cs);
            }
        } else {
            if (uDisplace > 0.001) {
                vec2 q = uv * 9.0 + vec2(tm * 0.6, -tm * 0.4);
                vec2 n2 = vec2(noise(q), noise(q + vec2(31.7, 17.3))) - vec2(0.5);
                uv += n2 * 0.12 * uDisplace;
            }
        }
    }

    vec4 c;
    if (uBlur > 0.001) {
        float rad = uBlur * 0.05;
        vec4 acc = vec4(0.0);
        for (int i = 0; i < 16; i++) {
            float fi = float(i);
            float ang = fi * 2.3999632;
            float rr = sqrt((fi + 0.5) / 16.0) * rad;
            acc += fetch(uv + vec2(cos(ang), sin(ang)) * rr);
        }
        c = acc / 16.0;
    } else {
        c = fetch(uv);
    }

    vec3 glowRgb = vec3(0.0);
    if (uGlow > 0.001) {
        vec4 g = vec4(0.0);
        for (int i = 0; i < 12; i++) {
            float fi = float(i);
            float ang = fi * 2.3999632;
            float rr = sqrt((fi + 0.5) / 12.0) * 0.045;
            vec4 s = fetch(uv + vec2(cos(ang), sin(ang)) * rr);
            g += max(s - vec4(0.55 * s.a), vec4(0.0));
        }
        glowRgb = (g.rgb / 12.0) * uGlow * 3.0;
    }

    float a = c.a;
    vec3 rgb = a > 0.0001 ? c.rgb / a : vec3(0.0);
    rgb += vec3(uBrightness);
    rgb = (rgb - vec3(0.5)) * uContrast + vec3(0.5);
    float l = dot(rgb, vec3(0.2126, 0.7152, 0.0722));
    rgb = mix(vec3(l), rgb, uSaturation);
    rgb *= vec3(1.0 + 0.18 * uTemperature, 1.0 + 0.02 * uTemperature, 1.0 - 0.18 * uTemperature);
    rgb = clamp(rgb, 0.0, 1.0);
    if (uLutMix > 0.001) { rgb = mix(rgb, lutLookup(rgb), uLutMix); }
    float vig = 1.0 - uVignette * smoothstep(0.30, 0.80, distance(vLocal, vec2(0.5)));
    rgb *= vig;

    float outA = a * uOpacity;
    vec3 outRgb = rgb * outA + glowRgb * uOpacity;
    gl_FragColor = vec4(min(outRgb, vec3(1.0)), outA);
}
"""
    }
}
