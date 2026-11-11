package com.solarbiscuit.atmosphera.stratos.cloud;

/**
 * The cloud programs (GLSL 330 core, after the lib's {@code GlslPrograms.header()}). Everything is camera-relative:
 * a sample at {@code p} (blocks from the camera) has world height {@code u_Camera.x + p.y}, and its height above the
 * curved cloud layer adds {@code |p.xz|² / 2R}, so distant clouds sink to the horizon. Noise coordinates come in as
 * per-tile fractions computed in double precision on the CPU, so they never lose precision far from the origin.
 */
final class CloudGlsl {
    private CloudGlsl() {
    }

    /** Shared by the march and the probe: uniforms, the deck intervals and the density field. */
    static final String COMMON = """
            uniform sampler2D u_Depth;
            uniform sampler3D u_Shape;
            uniform sampler3D u_Detail;
            uniform sampler2D u_Weather;
            uniform mat4 u_InvViewProj;
            uniform ivec4 u_Info;      // x divisor, y frame, z steps, w light steps
            uniform vec4 u_Flags;      // x detail, y temporal jitter, z debug view, w DH depth on
            uniform vec4 u_Camera;     // x camera world y, y far distance, z 1/(2R), w haze distance
            uniform vec4 u_Forward;    // xyz camera look direction
            uniform vec4 u_Deck;       // x low base, y low thickness, z coverage, w extinction
            uniform vec4 u_Type;       // x stratiform, y towers, z softness, w detail erosion
            uniform vec4 u_Layers;     // x mid height, y high height, z mid cover, w high cover
            uniform vec4 u_ShapeOff;   // xy shape tile fraction, zw detail tile fraction
            uniform vec4 u_WeatherOff; // xy low weather fraction, zw high weather fraction
            uniform vec4 u_Scale;      // x 1/shape size, y 1/detail size, z 1/weather size, w 1/high size
            uniform vec4 u_Block;      // x cell (0 = smooth), y layer depth, zw world position mod cell
            uniform vec4 u_LightDir;   // xyz towards the light (sun or moon)
            uniform vec4 u_LightCol;   // rgb light, w forward scattering
            uniform vec4 u_AmbTop;     // rgb sky light from above, w ambient strength
            uniform vec4 u_AmbBottom;  // rgb light from below, w powder
            uniform vec4 u_Haze;       // rgb haze colour, w darkening in rain
            uniform vec4 u_Tint;       // rgb tint, w brightness

            const float STRATOS_BIG = 1e9;
            const float STRATOS_NEAR_BAND = 2200.0;

            float stratos_remap(float v, float lo, float hi, float nlo, float nhi) {
                return nlo + (v - lo) / max(hi - lo, 1e-5) * (nhi - nlo);
            }

            // Ray parameters t (blocks) where the curved height y(t) = camY + b t + a t^2 is at or below h.
            // x > y means none.
            vec2 stratos_below(float h, float a, float b) {
                float c = u_Camera.x - h;
                if (a < 1e-12) {
                    if (abs(b) < 1e-7) {
                        return c <= 0.0 ? vec2(-STRATOS_BIG, STRATOS_BIG) : vec2(1.0, 0.0);
                    }
                    float r = -c / b;
                    return b > 0.0 ? vec2(-STRATOS_BIG, r) : vec2(r, STRATOS_BIG);
                }
                float disc = b * b - 4.0 * a * c;
                if (disc < 0.0) {
                    return vec2(1.0, 0.0);
                }
                float s = sqrt(disc);
                float q = -0.5 * (b + (b >= 0.0 ? s : -s));
                float r0 = q / a;
                float r1 = abs(q) > 1e-12 ? c / q : r0;
                return vec2(min(r0, r1), max(r0, r1));
            }

            // The first stretch of the ray inside the slab [lo, hi], within [0, tMax]; x >= y when there is none.
            vec2 stratos_slab(float lo, float hi, vec3 dir, float tMax) {
                float a = dot(dir.xz, dir.xz) * u_Camera.z;
                float b = dir.y;
                vec2 top = stratos_below(hi, a, b);
                if (top.x > top.y) {
                    return vec2(1.0, 0.0);
                }
                vec2 under = stratos_below(lo, a, b);
                vec2 seg;
                if (under.x > under.y) {
                    seg = top;
                } else {
                    seg = vec2(top.x, min(top.y, under.x));
                    vec2 segB = vec2(max(top.x, under.y), top.y);
                    vec2 clampedA = vec2(max(seg.x, 0.0), min(seg.y, tMax));
                    if (clampedA.x >= clampedA.y) {
                        seg = segB;
                    }
                }
                return vec2(max(seg.x, 0.0), min(seg.y, tMax));
            }

            // First t > 0 where the curved height crosses h, or -1.
            float stratos_plane(float h, vec3 dir) {
                float a = dot(dir.xz, dir.xz) * u_Camera.z;
                vec2 r = stratos_below(h, a, dir.y);
                if (r.x > r.y) {
                    return -1.0;
                }
                if (r.x > 0.0 && r.x < STRATOS_BIG * 0.5) {
                    return r.x;
                }
                return r.y > 0.0 && r.y < STRATOS_BIG * 0.5 ? r.y : -1.0;
            }

            // Coverage (x) and cloud type (y) of the low deck at camera-relative xz.
            vec2 stratos_weather(vec2 xz) {
                vec4 w = textureLod(u_Weather, xz * u_Scale.z + u_WeatherOff.xy, 0.0);
                float c = u_Deck.z;
                float soft = u_Type.z;
                float presence = smoothstep(1.0 - c - soft, 1.0 - c + soft, w.r);
                presence = max(presence, smoothstep(0.86, 1.0, c));
                return vec2(presence, w.g);
            }

            // Vertical profile at height fraction h for a cloud of this type: flat base, rounded top; sheets stay
            // low, storm towers reach the top of the deck.
            float stratos_profile(float h, float type) {
                float top = mix(mix(0.32, 0.9, type), 0.24, u_Type.x);
                top = mix(top, 1.0, u_Type.y * smoothstep(0.3, 0.75, type));
                return smoothstep(0.0, 0.05, h) * (1.0 - smoothstep(top * 0.5, top, h));
            }

            float stratos_heightFrac(vec3 p) {
                return (u_Camera.x + p.y + dot(p.xz, p.xz) * u_Camera.z - u_Deck.x) / u_Deck.y;
            }

            // Cloud density 0..1 at camera-relative p. Cheap where the weather map says clear.
            float stratos_density(vec3 p, float lod, bool detail) {
                if (u_Block.x > 0.0) {
                    // Blocky: whole cells of the world grid, in a thin flat layer.
                    float y = u_Camera.x + p.y + dot(p.xz, p.xz) * u_Camera.z;
                    if (y < u_Deck.x || y > u_Deck.x + u_Block.y) {
                        return 0.0;
                    }
                    vec2 cell = (floor((p.xz + u_Block.zw) / u_Block.x) + 0.5) * u_Block.x - u_Block.zw;
                    return step(0.5, stratos_weather(cell).x);
                }
                float h = stratos_heightFrac(p);
                if (h <= 0.0 || h >= 1.0) {
                    return 0.0;
                }
                vec2 wt = stratos_weather(p.xz);
                if (wt.x < 0.003) {
                    return 0.0;
                }
                float coverage = wt.x * stratos_profile(h, wt.y);
                if (coverage < 0.003) {
                    return 0.0;
                }
                float yWorld = u_Camera.x + p.y;
                vec4 s = textureLod(u_Shape, vec3(p.x * u_Scale.x + u_ShapeOff.x, yWorld * u_Scale.x,
                        p.z * u_Scale.x + u_ShapeOff.y), lod);
                float fbm = s.g * 0.625 + s.b * 0.25 + s.a * 0.125;
                float shape = stratos_remap(s.r, fbm - 1.0, 1.0, 0.0, 1.0);
                float d = clamp(stratos_remap(shape, 1.0 - coverage, 1.0, 0.0, 1.0), 0.0, 1.0);
                // Sheets: less billow, more even grey.
                d = mix(d, coverage * 0.7 * smoothstep(0.2, 0.6, shape), u_Type.x * 0.6);
                if (detail && d > 0.0 && u_Type.w > 0.0) {
                    float n = textureLod(u_Detail, vec3(p.x * u_Scale.y + u_ShapeOff.z, yWorld * u_Scale.y,
                            p.z * u_Scale.y + u_ShapeOff.w), 0.0).r;
                    // Wispy, torn bases; billowing tops.
                    float erode = mix(1.0 - n, n, clamp(h * 3.0, 0.0, 1.0));
                    d = clamp(stratos_remap(d, erode * u_Type.w, 1.0, 0.0, 1.0), 0.0, 1.0);
                }
                return d;
            }

            float stratos_hg(float mu, float g) {
                float g2 = g * g;
                return (1.0 - g2) / (12.5663706 * pow(max(1.0 + g2 - 2.0 * g * mu, 1e-4), 1.5));
            }

            // Optical depth of the low deck from p towards the light, a few growing steps.
            float stratos_lightDepth(vec3 p) {
                float od = 0.0;
                float t = 0.0;
                float step = 10.0;
                for (int i = 0; i < 6; i++) {
                    if (i >= u_Info.w) {
                        break;
                    }
                    t += step;
                    od += stratos_density(p + u_LightDir.xyz * t, 1.0, false) * step;
                    step *= 2.2;
                }
                return od * u_Deck.w;
            }

            // View direction of a screen uv (camera-relative, normalised).
            vec3 stratos_viewDir(vec2 uv) {
                vec4 far = u_InvViewProj * vec4(uv * 2.0 - 1.0, 1.0, 1.0);
                return normalize(far.xyz / far.w);
            }
            """;

    /**
     * The march: low deck volume plus the two thin layers, front to back. Two outputs (MRT). Needs
     * {@code DhBridge.GLSL} before it (for {@code atmos_dhDistance}).
     */
    static final String MARCH = COMMON + """
            in vec2 v_Uv;
            layout(location = 0) out vec4 fragColor;  // rgb in-scattered light (premultiplied), a transmittance
            layout(location = 1) out vec4 fragDepth;  // x cloud distance (for reprojection), y march limit


            vec3 stratos_ambient(float h) {
                return mix(u_AmbBottom.rgb, u_AmbTop.rgb, clamp(h, 0.0, 1.0)) * u_AmbTop.w;
            }

            vec4 stratos_low(vec3 dir, float tMax, float jitter, out float dist) {
                vec2 seg = stratos_slab(u_Deck.x, u_Deck.x + (u_Block.x > 0.0 ? u_Block.y : u_Deck.y), dir, tMax);
                dist = -1.0;
                if (seg.x >= seg.y) {
                    return vec4(0.0, 0.0, 0.0, 1.0);
                }
                bool far = seg.x > STRATOS_NEAR_BAND;
                int steps = far ? max(3, u_Info.z / 3) : u_Info.z;
                float dt = (seg.y - seg.x) / float(steps);
                float lod = far ? 2.0 : clamp(log2(seg.x / 500.0 + 1.0), 0.0, 2.0);
                bool detail = u_Flags.x > 0.5 && !far;
                float mu = dot(dir, u_LightDir.xyz);
                float phase = mix(0.0795775, 0.8 * stratos_hg(mu, 0.8) + 0.2 * stratos_hg(mu, -0.25), u_LightCol.w);
                float phaseMulti = stratos_hg(mu, 0.3);
                vec3 light = vec3(0.0);
                float trans = 1.0;
                float distSum = 0.0;
                float weightSum = 0.0;
                float t = seg.x + dt * jitter;
                for (int i = 0; i < 48; i++) {
                    if (i >= steps || trans < 0.02 || t > seg.y) {
                        break;
                    }
                    vec3 p = dir * t;
                    float d = stratos_density(p, lod, detail);
                    if (d > 0.0) {
                        float sigma = d * u_Deck.w;
                        float od = stratos_lightDepth(p);
                        float h = stratos_heightFrac(p);
                        float beer = exp(-od);
                        float multi = exp(-od * 0.25) * 0.5;
                        float powder = mix(1.0, 1.0 - exp(-od * 2.0 - sigma * 30.0), u_AmbBottom.w * (0.5 - 0.5 * mu));
                        vec3 s = u_LightCol.rgb * (beer * phase + multi * phaseMulti) * powder * 12.566
                                + stratos_ambient(h) * mix(0.55, 1.0, h);
                        float tr = exp(-sigma * dt);
                        float absorbed = trans * (1.0 - tr);
                        light += s * absorbed;
                        distSum += t * absorbed;
                        weightSum += absorbed;
                        trans *= tr;
                    }
                    t += dt;
                }
                dist = weightSum > 1e-4 ? distSum / weightSum : -1.0;
                return vec4(light, trans);
            }

            // A thin layer at height h: (rgb light, a transmittance); t is where the ray meets it.
            vec4 stratos_sheet(float h, vec3 dir, float tMax, bool cirrus, out float t) {
                t = stratos_plane(h, dir);
                if (t < 0.0 || t > tMax) {
                    return vec4(0.0, 0.0, 0.0, 1.0);
                }
                vec3 p = dir * t;
                float amount;
                if (cirrus) {
                    amount = textureLod(u_Weather, p.xz * u_Scale.w + u_WeatherOff.zw, 0.0).b * u_Layers.w;
                } else {
                    float cells = textureLod(u_Weather, p.xz * u_Scale.z * 6.0 + u_WeatherOff.xy * 6.0, 0.0).a;
                    amount = smoothstep(0.15, 0.55, cells) * u_Layers.z;
                }
                if (amount < 0.003) {
                    return vec4(0.0, 0.0, 0.0, 1.0);
                }
                // Path length through a thin layer grows at grazing angles (with curvature, roughly).
                float slope = max(abs(dir.y + 2.0 * t * dot(dir.xz, dir.xz) * u_Camera.z), 0.06);
                float tau = amount * (cirrus ? 0.5 : 1.4) / slope;
                float alpha = 1.0 - exp(-tau);
                float mu = dot(dir, u_LightDir.xyz);
                float phase = mix(0.0795775, stratos_hg(mu, cirrus ? 0.85 : 0.7), u_LightCol.w);
                vec3 s = u_LightCol.rgb * phase * 12.566 * (cirrus ? 1.0 : 0.8) * exp(-tau * 0.5)
                        + u_AmbTop.rgb * u_AmbTop.w * (cirrus ? 1.0 : 0.9);
                return vec4(s * alpha, 1.0 - alpha);
            }

            vec4 stratos_haze(vec4 layer, float t) {
                if (t < 0.0) {
                    return layer;
                }
                float f = 1.0 - exp(-t / max(u_Camera.w, 1.0));
                layer.rgb = mix(layer.rgb, u_Haze.rgb * (1.0 - layer.a), f);
                return layer;
            }

            void main() {
                vec2 uv = v_Uv;
                vec3 dir = stratos_viewDir(uv);
                float depth = texture(u_Depth, uv).r;
                float tMax = u_Camera.y;
                if (depth < 1.0) {
                    vec4 rel = u_InvViewProj * vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
                    tMax = min(tMax, length(rel.xyz / rel.w));
                }
                if (u_Flags.w > 0.5) {
                    // Distant Horizons LODs (compat.dh.DhBridge's GLSL, included before this source).
                    float dh = atmos_dhDistance(uv);
                    if (dh < 1e8) {
                        tMax = min(tMax, dh / max(dot(dir, u_Forward.xyz), 1e-3));
                    }
                }
                float jitter = 0.5;
                if (u_Flags.y > 0.5) {
                    jitter = fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715)))
                            + float(u_Info.y) * 0.61803398875);
                }

                float tLow;
                vec4 low = stratos_low(dir, tMax, jitter, tLow);
                float tMid = -1.0;
                float tHigh = -1.0;
                vec4 mid = u_Layers.z > 0.0 && u_Block.x == 0.0 ? stratos_sheet(u_Layers.x, dir, tMax, false, tMid) : vec4(0.0, 0.0, 0.0, 1.0);
                vec4 high = u_Layers.w > 0.0 ? stratos_sheet(u_Layers.y, dir, tMax, true, tHigh) : vec4(0.0, 0.0, 0.0, 1.0);
                low = stratos_haze(low, tLow);
                mid = stratos_haze(mid, tMid);
                high = stratos_haze(high, tHigh);

                // Front to back. From below the order is low, mid, high; from above the decks the reverse.
                vec4 a = low;
                vec4 b = mid;
                vec4 c = high;
                float ta = tLow < 0.0 ? STRATOS_BIG : tLow;
                float tb = tMid < 0.0 ? STRATOS_BIG : tMid;
                float tc = tHigh < 0.0 ? STRATOS_BIG : tHigh;
                if (tb < ta) { vec4 x = a; a = b; b = x; float y = ta; ta = tb; tb = y; }
                if (tc < tb) { vec4 x = b; b = c; c = x; float y = tb; tb = tc; tc = y; }
                if (tb < ta) { vec4 x = a; a = b; b = x; float y = ta; ta = tb; tb = y; }
                vec3 light = a.rgb + a.a * (b.rgb + b.a * c.rgb);
                float trans = a.a * b.a * c.a;

                vec3 grade = u_Tint.rgb * u_Tint.w * (1.0 - u_Haze.w);
                light *= grade;

                float dist = ta < STRATOS_BIG * 0.5 ? ta : tMax;
                if (tLow > 0.0 && low.a < 0.98) {
                    dist = tLow;
                }
                if (u_Flags.z > 0.5) {
                    int view = int(u_Flags.z + 0.5);
                    if (view == 1) {
                        light = vec3(1.0 - trans);
                    } else if (view == 2) {
                        light = vec3(tMax / u_Camera.y);
                    } else {
                        vec2 seg = stratos_slab(u_Deck.x, u_Deck.x + u_Deck.y, dir, tMax);
                        light = seg.x < seg.y ? vec3(stratos_weather(dir.xz * seg.x).x) : vec3(0.0, 0.0, 0.2);
                    }
                    trans = 0.0;
                }
                fragColor = vec4(light, trans);
                fragDepth = vec4(dist, tMax, 0.0, 1.0);
            }
            """;

    /** Temporal resolve: this frame's (jittered) march blended with last frame's, reprojected by cloud distance. */
    static final String RESOLVE = """
            uniform sampler2D u_Low;
            uniform sampler2D u_LowDepth;
            uniform sampler2D u_History;
            uniform mat4 u_InvViewProj;
            uniform ivec4 u_Info;   // x history valid
            in vec2 v_Uv;
            out vec4 fragColor;
            """ + "%REPROJECT%" + """
            void main() {
                ivec2 px = ivec2(gl_FragCoord.xy);
                ivec2 size = textureSize(u_Low, 0);
                vec4 c = texelFetch(u_Low, px, 0);
                if (u_Info.x == 0) {
                    fragColor = c;
                    return;
                }
                vec4 lo = c;
                vec4 hi = c;
                for (int y = -1; y <= 1; y++) {
                    for (int x = -1; x <= 1; x++) {
                        vec4 n = texelFetch(u_Low, clamp(px + ivec2(x, y), ivec2(0), size - 1), 0);
                        lo = min(lo, n);
                        hi = max(hi, n);
                    }
                }
                vec4 spread = (hi - lo) * 0.25;
                lo -= spread;
                hi += spread;
                vec4 far = u_InvViewProj * vec4(v_Uv * 2.0 - 1.0, 1.0, 1.0);
                vec3 dir = normalize(far.xyz / far.w);
                float dist = texelFetch(u_LowDepth, px, 0).x;
                vec2 prev = atmos_reproject(dir * max(dist, 1.0), 0.0);
                if (any(lessThan(prev, vec2(0.0))) || any(greaterThan(prev, vec2(1.0)))) {
                    fragColor = c;
                    return;
                }
                vec4 h = clamp(texture(u_History, prev), lo, hi);
                fragColor = mix(c, h, 0.88);
            }
            """;

    /** Full-resolution composite: depth-aware upsample of the clouds over the scene (blend ONE, SRC_ALPHA). */
    static final String COMPOSITE = """
            uniform sampler2D u_Clouds;
            uniform sampler2D u_LowDepth;
            uniform sampler2D u_Depth;
            uniform mat4 u_InvViewProj;
            uniform vec4 u_Params;   // x far distance, y debug view
            in vec2 v_Uv;
            out vec4 fragColor;

            vec3 stratos_shoulder(vec3 c) {
                vec3 over = max(c - 0.8, 0.0);
                return min(c, vec3(0.8)) + 0.2 * (1.0 - exp(-over / 0.2));
            }

            void main() {
                float depth = texture(u_Depth, v_Uv).r;
                float dist = u_Params.x;
                if (depth < 1.0) {
                    vec4 rel = u_InvViewProj * vec4(v_Uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
                    dist = min(dist, length(rel.xyz / rel.w));
                }
                ivec2 size = textureSize(u_Clouds, 0);
                vec2 st = v_Uv * vec2(size) - 0.5;
                ivec2 i0 = ivec2(floor(st));
                vec2 f = fract(st);
                vec4 sum = vec4(0.0);
                float wsum = 0.0;
                vec4 nearest = vec4(0.0, 0.0, 0.0, 1.0);
                float nearestErr = 1e9;
                for (int k = 0; k < 4; k++) {
                    ivec2 o = ivec2(k & 1, k >> 1);
                    ivec2 p = clamp(i0 + o, ivec2(0), size - 1);
                    float limit = texelFetch(u_LowDepth, p, 0).y;
                    float err = abs(log(max(limit, 0.05)) - log(max(dist, 0.05)));
                    float wb = (o.x == 1 ? f.x : 1.0 - f.x) * (o.y == 1 ? f.y : 1.0 - f.y);
                    float w = wb * exp(-err * 6.0);
                    vec4 c = texelFetch(u_Clouds, p, 0);
                    sum += c * w;
                    wsum += w;
                    if (err < nearestErr) {
                        nearestErr = err;
                        nearest = c;
                    }
                }
                vec4 c = wsum > 1e-4 ? sum / wsum : nearest;
                if (u_Params.y > 0.5) {
                    fragColor = vec4(c.rgb, 0.0);
                    return;
                }
                fragColor = vec4(stratos_shoulder(c.rgb), c.a);
            }
            """;

    /** Sun and moon cover: transmittance along the light directions from the camera, into a 2x1 target. */
    static final String PROBE = COMMON + """
            uniform vec4 u_Probe0;   // xyz sun direction
            uniform vec4 u_Probe1;   // xyz moon direction
            out vec4 fragColor;

            float stratos_trans(vec3 dir) {
                if (dir.y < -0.05) {
                    return 1.0;
                }
                float trans = 1.0;
                vec2 seg = stratos_slab(u_Deck.x, u_Deck.x + (u_Block.x > 0.0 ? u_Block.y : u_Deck.y), dir, u_Camera.y);
                if (seg.x < seg.y) {
                    float dt = (seg.y - seg.x) / 24.0;
                    float t = seg.x + dt * 0.5;
                    float od = 0.0;
                    for (int i = 0; i < 24; i++) {
                        od += stratos_density(dir * t, 1.0, false) * dt;
                        t += dt;
                    }
                    trans = exp(-od * u_Deck.w);
                }
                float tm = stratos_plane(u_Layers.x, dir);
                if (tm > 0.0 && u_Layers.z > 0.0 && u_Block.x == 0.0) {
                    vec3 p = dir * tm;
                    float cells = textureLod(u_Weather, p.xz * u_Scale.z * 6.0 + u_WeatherOff.xy * 6.0, 0.0).a;
                    trans *= exp(-smoothstep(0.15, 0.55, cells) * u_Layers.z * 1.4 / max(dir.y, 0.06));
                }
                float th = stratos_plane(u_Layers.y, dir);
                if (th > 0.0 && u_Layers.w > 0.0) {
                    vec3 p = dir * th;
                    float amount = textureLod(u_Weather, p.xz * u_Scale.w + u_WeatherOff.zw, 0.0).b * u_Layers.w;
                    trans *= exp(-amount * 0.5 / max(dir.y, 0.06));
                }
                return trans;
            }

            void main() {
                fragColor = vec4(stratos_trans(gl_FragCoord.x < 1.0 ? u_Probe0.xyz : u_Probe1.xyz));
            }
            """;
}
