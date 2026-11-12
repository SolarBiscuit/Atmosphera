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
            uniform sampler2D u_LayerTex;  // r cirrus fibres, g cirrus patches, b altocumulus cells, a ripple/patch field
            uniform mat4 u_InvViewProj;
            uniform ivec4 u_Info;      // x divisor, y frame, z steps, w light steps
            uniform vec4 u_Flags;      // x detail, y temporal jitter, z debug view, w DH depth on
            uniform vec4 u_Camera;     // x camera world y, y far distance, z 1/(2R), w haze distance
            uniform vec4 u_Forward;    // xyz camera look direction, w angular size of one low-resolution pixel
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
            uniform vec4 u_March;      // 0.2.0 toggles: x 1/z near steps, z third octave (y, w unused)
            uniform vec4 u_Storm;      // 0.5.0: x storm strength (towers), yz wind direction (xz), w rain shafts
            uniform vec4 u_Flash;      // 0.5.0: xyz lightning flash (camera-relative), w intensity
            uniform vec4 u_RegionOff;  // 0.5.0: xy region map centre minus camera, z 1 / map size, w 1 while valid
            uniform vec4 u_Local;      // 0.5.0: x camera dryness, y camera coldness, z storm features on
            uniform sampler2D u_Region; // r dryness, g coldness, b orographic lift (0.5 = none)

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

            // 0.5.0: the regional map at camera-relative xz: dryness, coldness, orographic lift (-1..1). Outside the
            // map (or before it is filled) the camera's own values and no lift.
            vec3 stratos_region(vec2 xz) {
                if (u_RegionOff.w < 0.5) {
                    return vec3(u_Local.x, u_Local.y, 0.0);
                }
                // 0.6.2: the lookup wanders by up to ~100 blocks with the drifting weather noise, so the map's cells
                // never draw a straight border in the deck.
                vec2 warp = textureLod(u_Weather, xz * u_Scale.z * 0.37 + u_WeatherOff.xy + vec2(0.41, 0.13), 0.0).rg;
                xz += (warp - 0.5) * 200.0;
                vec2 uv = (xz - u_RegionOff.xy) * u_RegionOff.z + 0.5;
                vec2 edge = abs(uv - 0.5) * 2.0;
                float fade = smoothstep(0.8, 1.0, max(edge.x, edge.y));
                vec3 r = textureLod(u_Region, clamp(uv, 0.0, 1.0), 0.0).rgb;
                return mix(vec3(r.xy, r.z * 2.0 - 1.0), vec3(u_Local.x, u_Local.y, 0.0), fade);
            }

            // 0.5.0 cost: the regional values are taken once per ray (the cells are 64 blocks and the field is smooth),
            // and the light march skips the storm extras. Set by the callers; the defaults are the camera's values.
            vec3 stratos_rayRegion = vec3(-1.0);
            bool stratos_cheap = false;
            // 0.6.2: how much of the last stratos_density sample was shelf cloud (0..1). The cheap light march skips
            // the storm extras, so the march shades the shelf itself: it stands in the storm's shadow.
            float stratos_shelfShare = 0.0;

            // Local coverage (x, 0..1), cloud type (y), local stratiform weight (z) and orographic lift (w) of the low
            // deck at camera-relative xz. The coverage varies smoothly around the deck's mean (0.1.1: no longer a mask
            // that saturates to 1, which made solid layers). 0.5.0: the biome of the place, not only the camera's, sets
            // the amount and kind (the deck's mean already holds the camera's), and terrain rising into the wind adds
            // cloud while its lee side thins it.
            vec4 stratos_weatherEx(vec2 xz) {
                vec4 w = textureLod(u_Weather, xz * u_Scale.z + u_WeatherOff.xy, 0.0);
                float c = u_Deck.z;
                float strat = u_Type.x;
                float lift = 0.0;
                if (u_Local.z > 0.5) {
                    vec3 reg = stratos_rayRegion.x >= 0.0 ? stratos_rayRegion : vec3(u_Local.x, u_Local.y, 0.0);
                    c *= (1.0 - 0.55 * reg.x) / max(1.0 - 0.55 * u_Local.x, 0.2);
                    strat = clamp(strat + (reg.y - u_Local.y) * 0.35, 0.0, 1.0);
                    lift = reg.z;
                    c = clamp(c + lift * 0.22 * smoothstep(0.05, 0.3, c + 0.1), 0.0, 1.0);
                }
                // The variation fades out towards 0%, so no amount means no clouds.
                float lc = clamp(c + (w.r - 0.5) * 1.3 * smoothstep(0.0, 0.25, c), 0.0, 1.0);
                lc = max(lc, smoothstep(0.9, 1.0, c));
                return vec4(lc, w.g, strat, lift);
            }

            vec2 stratos_weather(vec2 xz) {
                return stratos_weatherEx(xz).xy;
            }

            // A position anchored to the drifting low deck (blocks, within one weather tile), for patterns that must not
            // follow the camera (mammatus, rain streaks).
            vec2 stratos_lowWorld(vec2 xz) {
                return xz + u_WeatherOff.xy / u_Scale.z;
            }

            // 0.5.0 rain shafts and virga under raining cells: grey curtains below the deck's base, slanted downwind,
            // streaked; only past 64 blocks (vanilla's rain covers the near field).
            float stratos_shaft(vec3 p, float h) {
                if (u_Storm.w < 0.02) {
                    return 0.0;
                }
                float below = -h * u_Deck.y;
                float depth = max(u_Deck.x - 63.0, 1.0);
                if (below > depth) {
                    return 0.0;
                }
                vec2 src = p.xz - u_Storm.yz * below * 0.35;
                float lc = stratos_weatherEx(src).x;
                float cell = smoothstep(0.62, 0.9, lc) * u_Storm.w;
                if (cell < 0.01) {
                    return 0.0;
                }
                vec2 wpos = stratos_lowWorld(src);
                float streak = textureLod(u_Detail, vec3(wpos.x / 90.0, (u_Camera.x + p.y) / 900.0, wpos.y / 90.0), 0.0).r;
                // Light rain evaporates on the way down (virga); heavy rain reaches the ground.
                float reach = 1.0 - smoothstep(mix(0.25, 1.4, u_Storm.w), mix(0.5, 1.6, u_Storm.w), below / depth);
                float near = smoothstep(48.0, 96.0, length(p));
                return cell * reach * near * (0.08 + 0.14 * streak);
            }

            // Vertical profile at height fraction h for a cloud of this type: flat base, rounded top; sheets stay
            // low, storm towers reach the top of the deck.
            float stratos_profile(float h, float type) {
                float top = mix(mix(0.32, 0.9, type), 0.65, u_Type.x);
                top = mix(top, 1.0, u_Type.y * smoothstep(0.3, 0.75, type));
                // Sheets keep a flatter top than heaped cumulus.
                float shoulder = mix(0.5, 0.8, u_Type.x);
                return smoothstep(0.0, 0.05, h) * (1.0 - smoothstep(top * shoulder, top, h));
            }

            float stratos_heightFrac(vec3 p) {
                return (u_Camera.x + p.y + dot(p.xz, p.xz) * u_Camera.z - u_Deck.x) / u_Deck.y;
            }

            // Cloud density 0..1 at camera-relative p. Cheap where the weather map says clear.
            float stratos_density(vec3 p, float lod, bool detail) {
                stratos_shelfShare = 0.0;
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
                if (h >= 1.0) {
                    return 0.0;
                }
                if (h <= 0.0) {
                    return stratos_cheap ? 0.0 : stratos_shaft(p, h);
                }
                vec4 wx = stratos_weatherEx(p.xz);
                vec2 wt = wx.xy;
                float lc = wt.x;
                float strat = wx.z;
                // 0.5.0 storm features where a storm cell stands (towers in thunder).
                float cell = u_Storm.x * smoothstep(0.45, 0.75, wt.y);
                float extra = 0.0;
                float shelf = 0.0;
                if (u_Storm.x > 0.01 && !stratos_cheap) {
                    if (cell > 0.01 && h > 0.7) {
                        // Anvil: the tower's top spreads under the deck top, sheared downwind into an overhang.
                        float a = smoothstep(0.72, 0.92, h) * cell;
                        vec2 up = stratos_weather(p.xz - u_Storm.yz * a * 900.0);
                        lc = max(lc, clamp(up.x + 0.35 * a, 0.0, 1.0) * smoothstep(0.4, 0.7, up.y + 0.2));
                    }
                    if (h < 0.22 && cell < 0.5) {
                        // Shelf cloud: a low dark wedge ahead (downwind) of a storm cell's leading edge.
                        vec2 back = stratos_weather(p.xz - u_Storm.yz * 650.0);
                        float storm = u_Storm.x * smoothstep(0.45, 0.75, back.y) * smoothstep(0.5, 0.8, back.x);
                        float wedge = smoothstep(0.02, 0.05, h) * (1.0 - smoothstep(0.08, 0.2, h));
                        // 0.6.2: fades to nothing at the gate (it cut off at half strength: a hard-edged slab).
                        shelf = storm * (1.0 - smoothstep(0.2, 0.5, cell)) * wedge * 0.7;
                    }
                    if (cell > 0.01 && h > 0.55 && h < 0.76 && lc > 0.35) {
                        // Mammatus: pouches hanging from the anvil's underside.
                        vec2 wpos = stratos_lowWorld(p.xz);
                        float pouch = textureLod(u_LayerTex, wpos / 380.0 + vec2(0.19, 0.71), 0.0).b;
                        float hang = (0.74 - h) / 0.19;
                        extra = max(extra, smoothstep(0.0, 0.12, pouch - 0.3 - hang * 0.55) * cell * 0.8);
                    }
                }
                float extras = max(extra, shelf);
                if (lc < 0.003 && extras <= 0.0) {
                    return 0.0;
                }
                float prof = stratos_profile(h, wt.y);
                if (cell > 0.01) {
                    // The anvil keeps its full height to the deck top.
                    prof = max(prof, smoothstep(0.72, 0.85, h) * cell * (1.0 - smoothstep(0.97, 1.0, h)));
                }
                if (prof < 0.003 && extras <= 0.0) {
                    return 0.0;
                }
                float yWorld = u_Camera.x + p.y;
                vec4 s = textureLod(u_Shape, vec3(p.x * u_Scale.x + u_ShapeOff.x, yWorld * u_Scale.x,
                        p.z * u_Scale.x + u_ShapeOff.y), lod);
                float fbm = s.g * 0.625 + s.b * 0.25 + s.a * 0.125;
                float shape = clamp(stratos_remap(s.r, fbm - 1.0, 1.0, 0.0, 1.0), 0.0, 1.0);
                // Heaped clouds: only shape above (1 - local coverage) survives, so 50% coverage gives separate clouds
                // with clear sky between them; the style's softness widens the edge.
                float cum = clamp((shape * prof - (1.0 - lc)) / max(lc + u_Type.z, 1e-3), 0.0, 1.0) * lc;
                // Sheets (rain, cold biomes): a thick grey layer whose base undulates with the shape noise and whose
                // density varies in patches, instead of flat discs.
                float sheetBase = smoothstep(0.0, 0.14, h + (shape - 0.5) * 0.18);
                float sheet = sheetBase * prof * smoothstep(0.25, 0.7, shape * 0.6 + lc * 0.4) * lc;
                float base = max(mix(cum, sheet, strat), extra);
                // 0.6.2: the shelf takes the shape noise (it was a smooth slab) and says how much of the sample it is.
                float sh = shelf * smoothstep(0.2, 0.65, shape);
                float d = max(base, sh);
                stratos_shelfShare = sh > 0.0 ? clamp((sh - base) / sh * 3.0, 0.0, 1.0) : 0.0;
                if (detail && d > 0.0 && u_Type.w > 0.0) {
                    float n = textureLod(u_Detail, vec3(p.x * u_Scale.y + u_ShapeOff.z, yWorld * u_Scale.y,
                            p.z * u_Scale.y + u_ShapeOff.w), 0.0).r;
                    // Wispy, torn bases; billowing tops.
                    float erode = mix(1.0 - n, n, clamp(h * 3.0, 0.0, 1.0));
                    d = clamp(stratos_remap(d, erode * u_Type.w, 1.0, 0.0, 1.0), 0.0, 1.0);
                }
                return d;
            }

            // Cirrus: optical thickness at camera-relative xz on the high deck. Fibres are filtered first and
            // thresholded here, so their edges stay smooth at any distance (0.1.1); two samples at different scales and
            // angles keep them from looking like one repeated stripe.
            float stratos_cirrus(vec2 xz, float lod) {
                vec2 uv = xz * u_Scale.w + u_WeatherOff.zw;
                float a = textureLod(u_LayerTex, uv, lod).r;
                vec2 uv2 = mat2(0.94, 0.34, -0.34, 0.94) * uv * 1.9 + vec2(0.37, 0.61);
                float b = textureLod(u_LayerTex, uv2, lod + 0.93).r;
                float fibre = a * 0.62 + b * 0.38;
                float field = smoothstep(0.42, 0.72, textureLod(u_LayerTex, uv * 0.31 + vec2(0.13, 0.29), max(lod - 1.7, 0.0)).g);
                return smoothstep(0.52, 0.8, fibre) * field * u_Layers.w;
            }

            // Altocumulus (0.1.6): four forms, each over regions of a kilometre or two, blended at the borders, so one
            // sky holds different kinds instead of one repeated pattern. The region field: two very slow values that
            // pick the form, the direction and spacing of rows, and how packed the clumps are.
            vec2 stratos_altoRegion(vec2 uv) {
                return vec2(textureLod(u_LayerTex, uv * 0.071 + vec2(0.31, 0.77), 5.0).a,
                        textureLod(u_LayerTex, uv * 0.053 + vec2(0.83, 0.12), 5.0).g);
            }

            // Weights of the forms (sum 1): x floccus (ragged tufts of mixed size), y undulatus (rows), z lacunosus
            // (a thin sheet with round holes), w lenticular (smooth lens-shaped patches).
            vec4 stratos_altoWeights(vec2 r) {
                float hiA = smoothstep(0.48, 0.6, r.x);
                float hiB = smoothstep(0.5, 0.62, r.y);
                vec4 w = vec4(hiA * (1.0 - hiB), 1.0 - smoothstep(0.4, 0.52, r.x), hiA * hiB * 0.85,
                        smoothstep(0.58, 0.7, r.y) * (1.0 - smoothstep(0.4, 0.5, r.x)) * 0.8) + 0.01;
                return w / (w.x + w.y + w.z + w.w);
            }

            // The blended form at tile coordinates uv (0..1 cloud), and its albedo (neighbouring clumps differ in
            // brightness and thickness).
            float stratos_altoShape(vec2 uv, float lod, vec2 r, vec4 w, out float albedo) {
                // 0.2.7: a mid-frequency warp bends the cell lookup, so clumps become irregular lobes, not circles.
                vec2 warp = (textureLod(u_LayerTex, uv * 2.7 + vec2(0.17, 0.53), lod + 1.4).ar - 0.5) * 0.05;
                vec2 q = uv + warp;
                vec4 l = textureLod(u_LayerTex, q, lod);
                // Two more cell octaves at other scales and angles: clumps are built from sub-clumps (cauliflower
                // edges), and size, thickness and brightness vary from clump to clump.
                float c2 = textureLod(u_LayerTex, mat2(0.8, 0.6, -0.6, 0.8) * q * 1.85 + vec2(0.21, 0.67), lod + 0.89).b;
                float c3 = textureLod(u_LayerTex, mat2(-0.5, 0.87, -0.87, -0.5) * q * 3.4 + vec2(0.71, 0.13), lod + 1.77).b;
                // Fine fibres fray the edges.
                float fray = textureLod(u_LayerTex, q * 4.1 + vec2(0.37, 0.91), lod + 2.0).r - 0.5;
                float sub = mix(0.3, 0.6, r.y);
                float k = mix(l.b, max(l.b, c2 * 0.95), sub) + (c3 - 0.5) * 0.22 - fray * 0.26;
                float thick = mix(0.6, 1.4, c2);
                float d = 0.0;
                albedo = 0.0;
                if (w.x > 0.011) {
                    float big = smoothstep(0.3, 0.7, l.a);
                    float f = smoothstep(0.40, 0.76, k * mix(1.0, 0.82, big)) * thick;
                    d += w.x * f;
                    albedo += w.x * mix(0.8, 1.1, c2);
                }
                if (w.y > 0.011) {
                    // Rows turn and change their spacing from region to region; the warp bends them too.
                    float angle = r.x * 2.4 + r.y * 1.3;
                    float rows = mix(30.0, 60.0, r.y);
                    float phase = dot(q, vec2(cos(angle), sin(angle))) * rows + l.a * 3.0;
                    float ripple = 0.5 + 0.5 * sin(phase * 6.2831853);
                    float f = smoothstep(0.34, 0.74, k * (0.55 + 0.45 * ripple)) * mix(0.75, 1.2, c2);
                    d += w.y * f;
                    albedo += w.y * mix(0.85, 1.05, c2);
                }
                if (w.z > 0.011) {
                    // Holes where the cells' centres are, frayed; thin, so the sky shows through.
                    float holes = smoothstep(0.55, 0.85, l.b + fray * 0.3);
                    d += w.z * (1.0 - holes) * smoothstep(0.3, 0.6, l.a) * 0.6 * mix(0.8, 1.2, c3);
                    albedo += w.z * 0.95;
                }
                if (w.w > 0.011) {
                    // Long lenses: the slow channel stretched along one direction, warped and frayed.
                    vec2 luv = mat2(0.6, -0.8, 0.8, 0.6) * q;
                    float lens = textureLod(u_LayerTex, vec2(luv.x * 0.15, luv.y * 0.6) + vec2(0.4, 0.9), lod).a;
                    d += w.w * smoothstep(0.56, 0.8, lens - fray * 0.12) * 0.9;
                    albedo += w.w * 1.05;
                }
                return d;
            }

            float stratos_alto(vec2 xz, float lod, out vec2 uvOut, out vec2 rOut, out vec4 wOut, out float albedo) {
                vec2 uv = xz * u_Scale.z * 3.0 + u_WeatherOff.xy * 3.0;
                uvOut = uv;
                rOut = stratos_altoRegion(uv);
                wOut = stratos_altoWeights(rOut);
                float field = smoothstep(0.38, 0.68, textureLod(u_LayerTex, uv * 0.23 + vec2(0.5, 0.17), max(lod - 2.1, 0.0)).a);
                // Some regions sparse, some packed.
                float crowd = mix(0.7, 1.2, rOut.y);
                float shape = stratos_altoShape(uv, lod, rOut, wOut, albedo);
                albedo = mix(albedo, 1.0, u_Type.x);
                return mix(shape * field * crowd, field * 0.55, u_Type.x) * u_Layers.z;
            }

            // Mip level of the 512-texel layer texture for a sheet hit at distance t: the pixel's footprint on the
            // sheet (stretched at grazing angles) in texels. Explicit, because the sheets are evaluated in branches.
            float stratos_sheetLod(float t, float slope, float tileScale) {
                float footprint = t * u_Forward.w / sqrt(max(slope, 0.02));
                // 0.2.7: half a level sharper: the thin decks no longer pass through the temporal resolve.
                // 0.3.0: a quarter level sharper than the matched level (0.2.7 took half a level, which with the
                // thresholds aliased into stair-steps once magnified); the composite now upsamples them smoothly.
                return max(log2(max(footprint * tileScale * 512.0, 1.0)) - 0.25, 0.0);
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
                    stratos_cheap = true;
                    od += stratos_density(p + u_LightDir.xyz * t, 1.0, false) * step;
                    stratos_cheap = false;
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
            layout(location = 0) out vec4 fragColor;  // low deck: rgb in-scattered light (premultiplied), a transmittance
            layout(location = 1) out vec4 fragDepth;  // x low deck distance (reprojection), y march limit, w all decks' T
            layout(location = 2) out vec4 fragThin;   // 0.2.7: altocumulus + cirrus (no temporal history): rgb, a T


            vec3 stratos_ambient(float h) {
                return mix(u_AmbBottom.rgb, u_AmbTop.rgb, clamp(h, 0.0, 1.0)) * u_AmbTop.w;
            }

            vec4 stratos_low(vec3 dir, float tMax, float jitter, out float dist) {
                vec2 seg = stratos_slab(u_Deck.x, u_Deck.x + (u_Block.x > 0.0 ? u_Block.y : u_Deck.y), dir, tMax);
                dist = -1.0;
                // 0.5.0: the regional values for this ray, at the middle of its stretch through the deck.
                stratos_rayRegion = u_Local.z > 0.5 && seg.x < seg.y
                        ? stratos_region(dir.xz * (0.5 * (seg.x + seg.y))) : vec3(-1.0);
                // 0.5.0: rain shafts below the base, in 3 steps of their own (grey, lit by the sky only).
                vec3 shaftLight = vec3(0.0);
                float shaftT = 1.0;
                if (u_Storm.w > 0.02 && u_Block.x == 0.0) {
                    vec2 low = stratos_slab(63.0, u_Deck.x, dir, tMax);
                    if (low.x < low.y) {
                        float sdt = (low.y - low.x) / 3.0;
                        for (int i = 0; i < 3; i++) {
                            vec3 q = dir * (low.x + sdt * (float(i) + jitter));
                            float sd = stratos_shaft(q, stratos_heightFrac(q));
                            float tr = exp(-sd * u_Deck.w * sdt);
                            shaftLight += shaftT * (1.0 - tr) * stratos_ambient(0.0) * 0.8;
                            shaftT *= tr;
                        }
                    }
                }
                if (seg.x >= seg.y) {
                    return vec4(shaftLight, shaftT);
                }
                bool far = seg.x > STRATOS_NEAR_BAND;
                int steps = far ? max(3, u_Info.z / 3) : u_Info.z;
                // 0.2.5: from above the deck the rays cross it at a slant and most of them meet the deck's sunlit
                // top first; two thirds of the steps are enough there (flying over the clouds cost the most).
                if (u_Camera.x > u_Deck.x + u_Deck.y) {
                    steps = max(6, steps * 2 / 3);
                }
                float dt = (seg.y - seg.x) / float(steps);
                float lod = far ? 2.0 : clamp(log2(seg.x / 500.0 + 1.0), 0.0, 2.0);
                bool detail = u_Flags.x > 0.5 && !far;
                float mu = dot(dir, u_LightDir.xyz);
                float phase = mix(0.0795775, 0.8 * stratos_hg(mu, 0.8) + 0.2 * stratos_hg(mu, -0.25), u_LightCol.w);
                float phaseMulti = stratos_hg(mu, 0.3);
                // 0.2.0 (u_March.z): a third, softer scattering octave lifts deep cumulus cores (hoisted per ray).
                float phase3 = u_March.z > 0.5 ? stratos_hg(mu, 0.1) : 0.0;
                vec3 light = vec3(0.0);
                float trans = 1.0;
                float distSum = 0.0;
                float weightSum = 0.0;
                float t = seg.x + dt * jitter;
                // 0.2.0 (u_March.x): with the camera in or just under the deck, near steps spaced evenly in 1/t, so
                // the first steps are not hundreds of blocks apart in front of the camera.
                bool disparity = u_March.x > 0.5 && !far && seg.x < 400.0;
                float invN = 1.0 / max(seg.x, 16.0);
                float invF = 1.0 / seg.y;
                int taken = 0;
                for (int i = 0; i < 64; i++) {
                    if (taken >= steps || trans < 0.02 || t > seg.y) {
                        break;
                    }
                    if (disparity) {
                        float k = (float(taken) + jitter) / float(steps);
                        t = 1.0 / (invN - k * (invN - invF));
                        float tNext = 1.0 / max(invN - (k + 1.0 / float(steps)) * (invN - invF), 1e-6);
                        dt = max(min(tNext, seg.y) - t, 0.5);
                    }
                    vec3 p = dir * t;
                    taken++;
                    float d = stratos_density(p, lod, detail);
                    if (d > 0.0) {
                        // Read before the light march, which overwrites it.
                        float shade = 1.0 - 0.85 * stratos_shelfShare;
                        float sigma = d * u_Deck.w;
                        float od = stratos_lightDepth(p);
                        float h = stratos_heightFrac(p);
                        float beer = exp(-od);
                        float multi = exp(-od * 0.25) * 0.5;
                        float powder = mix(1.0, 1.0 - exp(-od * 2.0 - sigma * 30.0), u_AmbBottom.w * (0.5 - 0.5 * mu));
                        // Rain and storms darken the lower part of the deck (0.1.1: an albedo term, no longer a dimmer on
                        // the whole picture, which made rain clouds vanish into the sky).
                        float albedo = mix(1.0 - u_Haze.w, 1.0, clamp(h * 1.4, 0.0, 1.0));
                        float third = u_March.z > 0.5 ? exp(-od * 0.0625) * 0.25 * phase3 : 0.0;
                        vec3 s = (u_LightCol.rgb * (beer * phase + multi * phaseMulti + third) * powder * 12.566 * shade
                                + stratos_ambient(h) * mix(0.55, 1.0, h) * mix(1.0, shade, 0.5)) * albedo;
                        if (u_Flash.w > 0.0) {
                            // 0.5.0: lightning lights the cell from within (falls off over a few hundred blocks).
                            vec3 dv = p - u_Flash.xyz;
                            s += vec3(0.85, 0.88, 1.0) * (u_Flash.w * 9.0 / (1.0 + dot(dv, dv) / 122500.0));
                        }
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
                // Shafts lie in front of the deck from below it, behind it from above.
                if (u_Camera.x < u_Deck.x) {
                    return vec4(shaftLight + shaftT * light, shaftT * trans);
                }
                return vec4(light + trans * shaftLight, trans * shaftT);
            }

            // A thin layer at height h: (rgb light, a transmittance); t is where the ray meets it.
            vec4 stratos_sheet(float h, vec3 dir, float tMax, bool cirrus, out float t) {
                t = stratos_plane(h, dir);
                if (!cirrus && t > 0.0) {
                    // 0.2.7: the altocumulus layer rises and sinks by up to 120 blocks from region to region; one
                    // refinement of the plane at the height found at the first hit.
                    vec2 r0 = stratos_altoRegion((dir * t).xz * u_Scale.z * 3.0 + u_WeatherOff.xy * 3.0);
                    float lift = clamp((r0.x * 0.6 + r0.y * 0.4 - 0.5) * 480.0, -120.0, 120.0);
                    t = stratos_plane(h + lift, dir);
                }
                if (t < 0.0 || t > tMax) {
                    return vec4(0.0, 0.0, 0.0, 1.0);
                }
                vec3 p = dir * t;
                float amount;
                float shade = 1.0;
                float altoAlbedo = 1.0;
                // Path length through a thin layer grows at grazing angles (with curvature, roughly).
                float slope = max(abs(dir.y + 2.0 * t * dot(dir.xz, dir.xz) * u_Camera.z), 0.06);
                if (cirrus) {
                    amount = stratos_cirrus(p.xz, stratos_sheetLod(t, slope, u_Scale.w));
                } else {
                    vec2 uv;
                    vec2 region;
                    vec4 forms;
                    float lod = stratos_sheetLod(t, slope, u_Scale.z * 3.0);
                    amount = stratos_alto(p.xz, lod, uv, region, forms, altoAlbedo);
                    if (amount > 0.003 && u_Type.x < 0.95) {
                        // A clump is lit on its sun side and shaded on the far side: compare with a sample a little
                        // towards the light, so each clump reads as a puff instead of a flat disc.
                        vec2 toLight = u_LightDir.xz / max(length(u_LightDir.xz), 1e-3);
                        float unused;
                        float towards = stratos_altoShape(uv + toLight * 0.01, lod, region, forms, unused);
                        float here = stratos_altoShape(uv, lod, region, forms, unused);
                        shade = mix(clamp(1.0 - (towards - here) * 2.2, 0.35, 1.3), 1.0, u_Type.x);
                    }
                }
                if (amount < 0.003) {
                    return vec4(0.0, 0.0, 0.0, 1.0);
                }
                float tau = amount * (cirrus ? 0.45 : 1.4) / slope;
                float alpha = 1.0 - exp(-tau);
                float mu = dot(dir, u_LightDir.xyz);
                vec3 s;
                if (cirrus) {
                    // Thin ice: bright, a strong forward lobe near the sun, the sun's own colour at dusk and dawn;
                    // not darkened by the rain below.
                    float phase = mix(0.0795775, 0.7 * stratos_hg(mu, 0.85) + 0.3 * stratos_hg(mu, 0.2), u_LightCol.w);
                    s = u_LightCol.rgb * phase * 12.566 * 0.95 + u_AmbTop.rgb * u_AmbTop.w * 1.05;
                } else {
                    // Softer forward glow than cirrus, capped: small bright clumps near the sun read as glitter (0.1.3).
                    float phase = min(mix(0.0795775, 0.75 * stratos_hg(mu, 0.55) + 0.25 * stratos_hg(mu, -0.2), u_LightCol.w), 0.25);
                    float albedo = (1.0 - 0.6 * u_Haze.w) * altoAlbedo;
                    s = (u_LightCol.rgb * phase * 12.566 * 0.85 * exp(-tau * 0.4) * shade
                            + u_AmbTop.rgb * u_AmbTop.w * 0.9) * albedo;
                }
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

                // 0.2.7: the thin decks go to their own target, outside the temporal resolve (they are analytic, with no
                // noise to accumulate, and reprojecting them by the low deck's distance smeared them in motion). The
                // composite puts them behind the low deck, or in front of it when the camera is above the mid deck.
                vec4 b = mid;
                vec4 c = high;
                if (tHigh >= 0.0 && (tMid < 0.0 || tHigh < tMid)) {
                    b = high;
                    c = mid;
                }
                vec4 thin = vec4(b.rgb + b.a * c.rgb, b.a * c.a);
                vec3 grade = u_Tint.rgb * u_Tint.w;
                vec3 light = low.rgb * grade;
                float trans = low.a;
                thin.rgb *= grade;
                float dist = tLow > 0.0 && low.a < 0.98 ? tLow : tMax;
                if (u_Flags.z > 0.5) {
                    int view = int(u_Flags.z + 0.5);
                    if (view == 1) {
                        light = vec3(1.0 - trans * thin.a);
                    } else if (view == 2) {
                        light = vec3(tMax / u_Camera.y);
                    } else {
                        vec2 seg = stratos_slab(u_Deck.x, u_Deck.x + u_Deck.y, dir, tMax);
                        light = seg.x < seg.y ? vec3(stratos_weather(dir.xz * seg.x).x) : vec3(0.0, 0.0, 0.2);
                    }
                    trans = 0.0;
                    thin = vec4(0.0, 0.0, 0.0, 1.0);
                }
                fragThin = thin;
                fragColor = vec4(light, trans);
                fragDepth = vec4(dist, tMax, 0.0, trans * thin.a);
            }
            """;

    /** Temporal resolve: this frame's (jittered) march blended with last frame's, reprojected by cloud distance. */
    /**
     * 0.4.0 "Mirror": the clouds along the directions of the sky-view map (the lib's SkyView layout: u world azimuth,
     * v = sqrt(elevation / (pi / 2))), a few rows a frame. The march's own functions, few steps, no depth limit; one
     * output: premultiplied light and transmittance of all decks, in their order along the view.
     */
    static final String MAP = MARCH.substring(0, MARCH.lastIndexOf("void main() {")) + """
            uniform vec4 u_Probe0;     // x 1 when the camera is above the altocumulus

            void main() {
                float az = (v_Uv.x - 0.5) * 6.2831853;
                float el = v_Uv.y * v_Uv.y * 1.5707963;
                vec3 dir = vec3(cos(el) * cos(az), sin(el), cos(el) * sin(az));
                float tMax = u_Camera.y;
                float tLow;
                vec4 low = stratos_low(dir, tMax, 0.5, tLow);
                float tMid = -1.0;
                float tHigh = -1.0;
                vec4 mid = u_Layers.z > 0.0 && u_Block.x == 0.0 ? stratos_sheet(u_Layers.x, dir, tMax, false, tMid) : vec4(0.0, 0.0, 0.0, 1.0);
                vec4 high = u_Layers.w > 0.0 ? stratos_sheet(u_Layers.y, dir, tMax, true, tHigh) : vec4(0.0, 0.0, 0.0, 1.0);
                low = stratos_haze(low, tLow);
                mid = stratos_haze(mid, tMid);
                high = stratos_haze(high, tHigh);
                vec4 b = mid;
                vec4 c = high;
                if (tHigh >= 0.0 && (tMid < 0.0 || tHigh < tMid)) {
                    b = high;
                    c = mid;
                }
                vec4 thin = vec4(b.rgb + b.a * c.rgb, b.a * c.a);
                vec3 grade = u_Tint.rgb * u_Tint.w;
                low.rgb *= grade;
                thin.rgb *= grade;
                // u_Probe0.x: 1 when the camera is above the altocumulus (the thin decks in front of the low deck).
                fragColor = u_Probe0.x > 0.5 ? vec4(thin.rgb + thin.a * low.rgb, thin.a * low.a)
                        : vec4(low.rgb + low.a * thin.rgb, low.a * thin.a);
            }
            """;

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
                // 0.1.4: the clamp box only takes neighbours that march to about the same limit (same side of a
                // silhouette). With every neighbour in it, sky texels next to a tree or ridge accepted the tree texels'
                // cloud-free history while the camera moved, and a 1-2 px band of thinner cloud (brighter sky) followed
                // every outline against the sky. Edge texels also keep less history.
                float limit = log(max(texelFetch(u_LowDepth, px, 0).y, 0.05));
                bool edge = false;
                vec4 lo = c;
                vec4 hi = c;
                for (int y = -1; y <= 1; y++) {
                    for (int x = -1; x <= 1; x++) {
                        ivec2 q = clamp(px + ivec2(x, y), ivec2(0), size - 1);
                        if (abs(log(max(texelFetch(u_LowDepth, q, 0).y, 0.05)) - limit) > 0.15) {
                            edge = true;
                            continue;
                        }
                        vec4 n = texelFetch(u_Low, q, 0);
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
                fragColor = mix(c, h, edge ? 0.5 : 0.88);
            }
            """;

    /** Full-resolution composite: depth-aware upsample of the clouds over the scene (blend ONE, SRC_ALPHA). */
    static final String COMPOSITE = """
            uniform sampler2D u_Clouds;
            uniform sampler2D u_Thin;
            uniform sampler2D u_LowDepth;
            uniform sampler2D u_Depth;
            uniform mat4 u_InvViewProj;
            uniform vec4 u_Params;   // x far distance, y debug view, z 1 when the thin decks are in front of the low deck
            in vec2 v_Uv;
            out vec4 fragColor;

            // 0.3.0: cubic B-spline upsample from four bilinear taps, for the thin decks. Their thresholded shapes,
            // magnified bilinearly from the march resolution, showed stair-steps along every edge.
            vec4 stratos_bspline(sampler2D tex, vec2 uv) {
                vec2 size = vec2(textureSize(tex, 0));
                vec2 st = uv * size - 0.5;
                vec2 i = floor(st);
                vec2 f = st - i;
                vec2 f2 = f * f;
                vec2 f3 = f2 * f;
                vec2 w0 = (1.0 - 3.0 * f + 3.0 * f2 - f3) / 6.0;
                vec2 w1 = (4.0 - 6.0 * f2 + 3.0 * f3) / 6.0;
                vec2 w2 = (1.0 + 3.0 * f + 3.0 * f2 - 3.0 * f3) / 6.0;
                vec2 w3 = f3 / 6.0;
                vec2 g0 = w0 + w1;
                vec2 g1 = w2 + w3;
                vec2 h0 = (i - 0.5 + w1 / g0) / size;
                vec2 h1 = (i + 1.5 + w3 / g1) / size;
                return g0.y * (g0.x * textureLod(tex, h0, 0.0) + g1.x * textureLod(tex, vec2(h1.x, h0.y), 0.0))
                        + g1.y * (g0.x * textureLod(tex, vec2(h0.x, h1.y), 0.0) + g1.x * textureLod(tex, h1, 0.0));
            }

            vec3 stratos_shoulder(vec3 c) {
                vec3 over = max(c - 0.8, 0.0);
                return min(c, vec3(0.8)) + 0.2 * (1.0 - exp(-over / 0.2));
            }

            void main() {
                ivec2 size = textureSize(u_Clouds, 0);
                vec2 st = v_Uv * vec2(size) - 0.5;
                ivec2 i0 = ivec2(floor(st));
                vec2 f = fract(st);
                ivec2 p00 = clamp(i0, ivec2(0), size - 1);
                ivec2 p10 = clamp(i0 + ivec2(1, 0), ivec2(0), size - 1);
                ivec2 p01 = clamp(i0 + ivec2(0, 1), ivec2(0), size - 1);
                ivec2 p11 = clamp(i0 + ivec2(1, 1), ivec2(0), size - 1);
                bool debug = u_Params.y > 0.5;
                // 0.2.4 fast path 1 (one bilinear fetch since 0.2.5): no cloud in any of the four taps (most terrain,
                // clear sky): nothing to blend.
                vec4 bilinear = textureLod(u_Clouds, v_Uv, 0.0);
                vec4 thinBilinear = textureLod(u_Thin, v_Uv, 0.0);
                if (!debug && bilinear.a > 0.998 && max(bilinear.r, max(bilinear.g, bilinear.b)) < 0.002
                        && thinBilinear.a > 0.998 && max(thinBilinear.r, max(thinBilinear.g, thinBilinear.b)) < 0.002) {
                    discard;
                }
                vec4 limits = vec4(texelFetch(u_LowDepth, p00, 0).y, texelFetch(u_LowDepth, p10, 0).y,
                        texelFetch(u_LowDepth, p01, 0).y, texelFetch(u_LowDepth, p11, 0).y);
                float lo = min(min(limits.x, limits.y), min(limits.z, limits.w));
                float hi = max(max(limits.x, limits.y), max(limits.z, limits.w));
                vec4 c;
                vec4 thin;
                if (hi <= lo * 1.02 + 0.5) {
                    // 0.2.4 fast path 2: the four taps march to the same distance, so there is no silhouette here and
                    // the plain bilinear blend is exact enough: no full-resolution depth, no weights.
                    c = bilinear;
                    bool thinEmpty = thinBilinear.a > 0.998 && max(thinBilinear.r, max(thinBilinear.g, thinBilinear.b)) < 0.002;
                    thin = thinEmpty ? thinBilinear : stratos_bspline(u_Thin, v_Uv);
                } else {
                    vec4 c00 = texelFetch(u_Clouds, p00, 0);
                    vec4 c10 = texelFetch(u_Clouds, p10, 0);
                    vec4 c01 = texelFetch(u_Clouds, p01, 0);
                    vec4 c11 = texelFetch(u_Clouds, p11, 0);
                    // An edge (terrain against sky, LOD tree lines): weight the taps by how well their march limit
                    // matches this pixel's own distance.
                    float depth = texture(u_Depth, v_Uv).r;
                    float dist = u_Params.x;
                    if (depth < 1.0) {
                        vec4 rel = u_InvViewProj * vec4(v_Uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
                        dist = min(dist, length(rel.xyz / rel.w));
                    }
                    float ld = log(max(dist, 0.05));
                    vec4 errs = abs(log(max(limits, vec4(0.05))) - vec4(ld));
                    float nearestErr = min(min(errs.x, errs.y), min(errs.z, errs.w));
                    // 0.1.5: relative to the best-matching tap, and much sharper. exp(-6 err) alone left a far LOD tree
                    // tap (2000 blocks against the 4250 block sky limit) at about 4% weight, so sky pixels mostly over a
                    // tree texel took a quarter of their clouds from it: a thin clear rim along distant tree lines.
                    vec4 wb = vec4((1.0 - f.x) * (1.0 - f.y), f.x * (1.0 - f.y), (1.0 - f.x) * f.y, f.x * f.y);
                    vec4 w = wb * exp(-(errs - vec4(nearestErr)) * 30.0);
                    float wsum = w.x + w.y + w.z + w.w;
                    vec4 t00 = texelFetch(u_Thin, p00, 0);
                    vec4 t10 = texelFetch(u_Thin, p10, 0);
                    vec4 t01 = texelFetch(u_Thin, p01, 0);
                    vec4 t11 = texelFetch(u_Thin, p11, 0);
                    if (wsum > 1e-4) {
                        c = (c00 * w.x + c10 * w.y + c01 * w.z + c11 * w.w) / wsum;
                        thin = (t00 * w.x + t10 * w.y + t01 * w.z + t11 * w.w) / wsum;
                    } else {
                        bool n0 = errs.x == nearestErr;
                        bool n1 = errs.y == nearestErr;
                        bool n2 = errs.z == nearestErr;
                        c = n0 ? c00 : n1 ? c10 : n2 ? c01 : c11;
                        thin = n0 ? t00 : n1 ? t10 : n2 ? t01 : t11;
                    }
                }
                // Low deck and thin decks in their order along the view.
                c = u_Params.z > 0.5 ? vec4(thin.rgb + thin.a * c.rgb, thin.a * c.a)
                        : vec4(c.rgb + c.a * thin.rgb, c.a * thin.a);
                if (debug) {
                    fragColor = vec4(c.rgb, 0.0);
                    return;
                }
                fragColor = vec4(stratos_shoulder(c.rgb), c.a);
            }
            """;

    /**
     * Cloud shadow map (0.2.0): each texel is a point on a horizontal reference plane inside the low deck; the clouds'
     * transmittance along the sun through the whole deck there, times the altocumulus and cirrus layers. World-locked
     * (the CPU snaps the map centre to whole texels), so shadows do not swim. Read through lib CloudShadowShader.
     */
    static final String SHADOW = COMMON + """
            uniform vec4 u_Probe0;   // xyz sun direction
            uniform vec4 u_Probe1;   // xy map centre minus camera, z map size (blocks), w reference plane height
            in vec2 v_Uv;
            out vec4 fragColor;

            void main() {
                vec3 sun = u_Probe0.xyz;
                if (sun.y < 0.03) {
                    fragColor = vec4(1.0);
                    return;
                }
                vec2 xz = u_Probe1.xy + (v_Uv - 0.5) * u_Probe1.z;
                vec3 p0 = vec3(xz.x, u_Probe1.w - u_Camera.x, xz.y);
                float top = u_Deck.x + (u_Block.x > 0.0 ? u_Block.y : u_Deck.y);
                float ta = (u_Deck.x - u_Probe1.w) / sun.y;
                float tb = (top - u_Probe1.w) / sun.y;
                int n = max(u_Info.z, 1);
                float dt = (tb - ta) / float(n);
                float t = ta + dt * 0.5;
                float od = 0.0;
                for (int i = 0; i < 12; i++) {
                    if (i >= n) {
                        break;
                    }
                    od += stratos_density(p0 + sun * t, 1.0, false) * dt;
                    t += dt;
                }
                float trans = exp(-od * u_Deck.w);
                // u_Info.w: 0 on LOW (0.2.4), where the thin decks' faint shadows are left out.
                if (u_Info.w > 0 && u_Layers.z > 0.0 && u_Block.x == 0.0) {
                    vec3 pm = p0 + sun * ((u_Layers.x - u_Probe1.w) / sun.y);
                    vec2 uv;
                    vec2 region;
                    vec4 forms;
                    float albedo;
                    trans *= exp(-stratos_alto(pm.xz, 2.0, uv, region, forms, albedo) * 1.4);
                }
                if (u_Info.w > 0 && u_Layers.w > 0.0) {
                    vec3 ph = p0 + sun * ((u_Layers.y - u_Probe1.w) / sun.y);
                    trans *= exp(-stratos_cirrus(ph.xz, 2.0) * 0.45);
                }
                fragColor = vec4(trans);
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
                    vec2 uv;
                    vec2 region;
                    vec4 forms;
                    float albedo;
                    trans *= exp(-stratos_alto((dir * tm).xz, 2.0, uv, region, forms, albedo) * 1.4 / max(dir.y, 0.06));
                }
                float th = stratos_plane(u_Layers.y, dir);
                if (th > 0.0 && u_Layers.w > 0.0) {
                    trans *= exp(-stratos_cirrus((dir * th).xz, 2.0) * 0.45 / max(dir.y, 0.06));
                }
                return trans;
            }

            void main() {
                fragColor = vec4(stratos_trans(gl_FragCoord.x < 1.0 ? u_Probe0.xyz : u_Probe1.xyz));
            }
            """;
}
