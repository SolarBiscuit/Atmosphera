package com.solarbiscuit.atmosphera.atmosphera.sky;

/**
 * The sky's programs (GLSL 330 core, after the lib's {@code GlslPrograms.header()}). A physically based atmosphere in
 * the manner of Hillaire's 2020 paper, written for Atmosphera: Rayleigh and Mie scattering, ozone absorption and the
 * planet's shadow, with three look-up tables:
 * <ul>
 *   <li>transmittance (256x64), by height and view zenith, built once;</li>
 *   <li>multiple scattering (32x32), by height and sun zenith, built once;</li>
 *   <li>sky view (192x108), by azimuth and elevation around the camera, refreshed as the sun moves.</li>
 * </ul>
 * Distances are in kilometres; the camera sits a little above the ground, higher when it climbs. Sunsets, the Belt of
 * Venus and the Earth's shadow come out of the model itself.
 */
final class SkyGlsl {
    private SkyGlsl() {
    }

    static final String ATMOSPHERE = """
            const float SKY_RG = 6360.0;          // ground radius (km)
            const float SKY_RT = 6460.0;          // top of the atmosphere
            const vec3 SKY_RAYLEIGH = vec3(5.802e-3, 13.558e-3, 33.1e-3);
            const float SKY_RAYLEIGH_H = 8.0;
            const float SKY_MIE_SCATTER = 3.996e-3;
            const float SKY_MIE_EXTINCT = 4.4e-3;
            const float SKY_MIE_H = 1.2;
            const vec3 SKY_OZONE = vec3(0.650e-3, 1.881e-3, 0.085e-3);
            const vec3 SKY_GROUND_ALBEDO = vec3(0.3);
            const float SKY_PI = 3.14159265;

            // Distance to the sphere of radius rad from a point at radius r looking with cosine mu (outward), or -1.
            float sky_raySphere(float r, float mu, float rad) {
                float disc = r * r * (mu * mu - 1.0) + rad * rad;
                if (disc < 0.0) {
                    return -1.0;
                }
                float s = sqrt(disc);
                float t0 = -r * mu - s;
                float t1 = -r * mu + s;
                return t0 > 0.0 ? t0 : (t1 > 0.0 ? t1 : -1.0);
            }

            bool sky_hitsGround(float r, float mu) {
                return mu < 0.0 && r * r * (mu * mu - 1.0) + SKY_RG * SKY_RG >= 0.0;
            }

            // Scattering and extinction at height h (km above the ground).
            void sky_medium(float h, out vec3 rayleigh, out float mie, out vec3 extinction) {
                float dr = exp(-h / SKY_RAYLEIGH_H);
                float dm = exp(-h / SKY_MIE_H);
                float ozone = max(0.0, 1.0 - abs(h - 25.0) / 15.0);
                rayleigh = SKY_RAYLEIGH * dr;
                mie = SKY_MIE_SCATTER * dm;
                extinction = rayleigh + SKY_MIE_EXTINCT * dm + SKY_OZONE * ozone;
            }

            // Transmittance LUT coordinates (Bruneton's parameterisation).
            vec2 sky_transmittanceUv(float r, float mu) {
                float big = sqrt(SKY_RT * SKY_RT - SKY_RG * SKY_RG);
                float rho = sqrt(max(r * r - SKY_RG * SKY_RG, 0.0));
                float disc = r * r * (mu * mu - 1.0) + SKY_RT * SKY_RT;
                float d = max(0.0, -r * mu + sqrt(max(disc, 0.0)));
                float dMin = SKY_RT - r;
                float dMax = rho + big;
                return vec2((d - dMin) / max(dMax - dMin, 1e-4), rho / big);
            }

            void sky_transmittanceRMu(vec2 uv, out float r, out float mu) {
                float big = sqrt(SKY_RT * SKY_RT - SKY_RG * SKY_RG);
                float rho = big * uv.y;
                r = sqrt(rho * rho + SKY_RG * SKY_RG);
                float dMin = SKY_RT - r;
                float dMax = rho + big;
                float d = dMin + uv.x * (dMax - dMin);
                mu = d == 0.0 ? 1.0 : clamp((big * big - rho * rho - d * d) / (2.0 * r * d), -1.0, 1.0);
            }

            float sky_rayleighPhase(float c) {
                return 3.0 / (16.0 * SKY_PI) * (1.0 + c * c);
            }

            float sky_miePhase(float c) {
                const float g = 0.8;
                float g2 = g * g;
                return 3.0 / (8.0 * SKY_PI) * (1.0 - g2) * (1.0 + c * c) / ((2.0 + g2) * pow(max(1.0 + g2 - 2.0 * g * c, 1e-4), 1.5));
            }
            """;

    /** Transmittance LUT: optical depth to the top of the atmosphere, 40 steps. */
    static final String TRANSMITTANCE = ATMOSPHERE + """
            in vec2 v_Uv;
            out vec4 fragColor;

            void main() {
                float r;
                float mu;
                sky_transmittanceRMu(v_Uv, r, mu);
                float dist = sky_raySphere(r, mu, SKY_RT);
                vec3 depth = vec3(0.0);
                float dt = max(dist, 0.0) / 40.0;
                for (int i = 0; i < 40; i++) {
                    float t = (float(i) + 0.5) * dt;
                    float rr = sqrt(r * r + t * t + 2.0 * r * mu * t);
                    vec3 ray;
                    float mie;
                    vec3 ext;
                    sky_medium(rr - SKY_RG, ray, mie, ext);
                    depth += ext * dt;
                }
                fragColor = vec4(exp(-depth), 1.0);
            }
            """;

    static final String SAMPLE_TRANSMITTANCE = """
            uniform sampler2D u_Transmittance;

            // Transmittance from radius r towards cosine mu, 0 when the ray hits the ground (the planet's shadow).
            vec3 sky_transmittance(float r, float mu) {
                if (sky_hitsGround(r, mu)) {
                    return vec3(0.0);
                }
                return texture(u_Transmittance, sky_transmittanceUv(r, mu)).rgb;
            }
            """;

    /**
     * Multiple-scattering LUT: the light scattered more than once, as an isotropic source per height and sun zenith
     * (a geometric series over the second order, after Hillaire). 16 directions, 16 steps; built once.
     */
    static final String MULTISCATTER = ATMOSPHERE + SAMPLE_TRANSMITTANCE + """
            in vec2 v_Uv;
            out vec4 fragColor;

            void main() {
                float muS = v_Uv.x * 2.0 - 1.0;
                float r = SKY_RG + 0.01 + v_Uv.y * (SKY_RT - SKY_RG - 0.02);
                vec3 sun = vec3(sqrt(max(0.0, 1.0 - muS * muS)), muS, 0.0);
                vec3 lum = vec3(0.0);
                vec3 fms = vec3(0.0);
                for (int k = 0; k < 16; k++) {
                    // Directions on a spiral over the sphere.
                    float z = 1.0 - (float(k) + 0.5) / 8.0;
                    float a = float(k) * 2.39996323;
                    float s = sqrt(max(0.0, 1.0 - z * z));
                    vec3 dir = vec3(s * cos(a), z, s * sin(a));
                    float mu = dir.y;
                    float tMax = sky_hitsGround(r, mu) ? sky_raySphere(r, mu, SKY_RG) : sky_raySphere(r, mu, SKY_RT);
                    float dt = max(tMax, 0.0) / 16.0;
                    vec3 trans = vec3(1.0);
                    for (int i = 0; i < 16; i++) {
                        float t = (float(i) + 0.5) * dt;
                        vec3 p = vec3(0.0, r, 0.0) + dir * t;
                        float rr = length(p);
                        vec3 ray;
                        float mie;
                        vec3 ext;
                        sky_medium(rr - SKY_RG, ray, mie, ext);
                        vec3 scat = ray + vec3(mie);
                        vec3 stepT = exp(-ext * dt);
                        vec3 sunT = sky_transmittance(rr, dot(p, sun) / rr);
                        vec3 s1 = scat * sunT / (4.0 * SKY_PI);
                        vec3 integ = (s1 - s1 * stepT) / max(ext, vec3(1e-6));
                        vec3 integMs = (scat - scat * stepT) / max(ext, vec3(1e-6));
                        lum += trans * integ / 16.0;
                        fms += trans * integMs / 16.0;
                        trans *= stepT;
                    }
                    if (sky_hitsGround(r, mu)) {
                        vec3 ground = vec3(0.0, r, 0.0) + dir * tMax;
                        float muG = max(dot(normalize(ground), sun), 0.0);
                        lum += trans * sky_transmittance(SKY_RG, muG) * muG * SKY_GROUND_ALBEDO / SKY_PI / 16.0;
                    }
                }
                vec3 psi = lum / max(vec3(1.0) - fms, vec3(1e-3));
                fragColor = vec4(psi, 1.0);
            }
            """;

    /** Sky-view LUT: in-scattered light around the camera, by azimuth from the sun and elevation; 24 steps. */
    static final String SKYVIEW = ATMOSPHERE + SAMPLE_TRANSMITTANCE + """
            uniform sampler2D u_MultiScatter;
            uniform vec4 u_SkyCam;     // x camera radius (km), y sun cosine-zenith
            in vec2 v_Uv;
            out vec4 fragColor;

            vec3 sky_ms(float r, float muS) {
                vec2 uv = vec2(muS * 0.5 + 0.5, (r - SKY_RG) / (SKY_RT - SKY_RG));
                return texture(u_MultiScatter, uv).rgb;
            }

            void main() {
                float r = u_SkyCam.x;
                float muS = u_SkyCam.y;
                vec3 sun = vec3(sqrt(max(0.0, 1.0 - muS * muS)), muS, 0.0);
                // Elevation: denser near the horizon (square-root mapping), relative to the true horizon at this height.
                float horizon = -asin(clamp(sqrt(max(r * r - SKY_RG * SKY_RG, 0.0)) / r, 0.0, 1.0)); // the true horizon, just below level
                float v = v_Uv.y;
                float lat = v < 0.5 ? -(1.0 - 2.0 * v) * (1.0 - 2.0 * v) : (2.0 * v - 1.0) * (2.0 * v - 1.0);
                float elevation = lat * (SKY_PI * 0.5) + horizon;
                float azimuth = v_Uv.x * 2.0 * SKY_PI;
                vec3 dir = vec3(cos(elevation) * cos(azimuth), sin(elevation), cos(elevation) * sin(azimuth));
                float mu = dir.y;
                bool ground = sky_hitsGround(r, mu);
                float tMax = ground ? sky_raySphere(r, mu, SKY_RG) : sky_raySphere(r, mu, SKY_RT);
                float dt = max(tMax, 0.0) / 24.0;
                float c = dot(dir, sun);
                float pr = sky_rayleighPhase(c);
                float pm = sky_miePhase(c);
                vec3 lum = vec3(0.0);
                vec3 trans = vec3(1.0);
                for (int i = 0; i < 24; i++) {
                    // Steps grow with distance (squared spacing): fine near the camera, coarse far away.
                    float t0 = float(i) / 24.0;
                    float t1 = float(i + 1) / 24.0;
                    float ta = t0 * t0 * tMax;
                    float tb = t1 * t1 * tMax;
                    float t = 0.5 * (ta + tb);
                    float step = tb - ta;
                    vec3 p = vec3(0.0, r, 0.0) + dir * t;
                    float rr = length(p);
                    vec3 ray;
                    float mie;
                    vec3 ext;
                    sky_medium(rr - SKY_RG, ray, mie, ext);
                    float muSun = dot(p, sun) / rr;
                    vec3 sunT = sky_transmittance(rr, muSun);
                    vec3 s = (ray * pr + mie * pm) * sunT + (ray + vec3(mie)) * sky_ms(rr, muSun);
                    vec3 stepT = exp(-ext * step);
                    lum += trans * (s - s * stepT) / max(ext, vec3(1e-6));
                    trans *= stepT;
                }
                fragColor = vec4(lum, 1.0);
            }
            """;

    /** The sky on screen: the sky-view LUT, sun, moon, stars, airglow, and the blend into the fog at the horizon. */
    static final String SKY = ATMOSPHERE + SAMPLE_TRANSMITTANCE + """
            uniform sampler2D u_SkyView;
            uniform sampler2D u_Noise;         // Atmosphera's layer texture (crater and Milky Way detail)
            uniform sampler2D u_PackSun;
            uniform sampler2D u_PackMoon;
            // 0.10.0: up to three nebula images (NebulaImages slots) and, per nebula k, u_Neb[4k..4k+3]:
            //   [0] xyz centre in the nebula frame, w 1/sin(radius); [1] x cone cosine, y turn, z strength, w radius;
            //   [2] xy uv per unit of q, z fade-in, w the image's long side (0 = not ready); [3] rgb glow colour.
            uniform sampler2D u_Nebula0;
            uniform sampler2D u_Nebula1;
            uniform sampler2D u_Nebula2;
            uniform vec4 u_Neb[12];
            uniform vec4 u_NebFrame;           // x, y: cosine and sine of the nebula frame's turn; z: how many show
            uniform vec4 u_PackDisc;           // 0.10.0: x the resource pack moon's disc radius in moon radii
            uniform vec4 u_Twinkle;            // 0.10.0: x smoothed seconds, y amount, z speed
            uniform mat4 u_InvViewProj;
            uniform vec4 u_SkyCam;    // x camera radius (km), y sun cosine-zenith, z sun azimuth (radians), w exposure
            uniform vec4 u_Sun;       // xyz towards the sun (world), w angular radius (radians)
            uniform vec4 u_Moon;      // xyz towards the moon, w angular radius
            uniform vec4 u_MoonPhase; // x phase angle (0 full .. pi new), y brightness, z pack textures (1), w phase index
            uniform vec4 u_Stars;     // x brightness, y Milky Way, z/w cosine/sine of the celestial rotation
            uniform vec4 u_Look;      // x saturation, y horizon blend (radians), z rain, w thunder
            uniform vec4 u_Fog;       // rgb fog colour (the game's, biome tinted), w pixel angle
            uniform vec4 u_Look2;     // x airglow, y star amount, z 0..1 camera above the cloud deck
            uniform vec4 u_Map;       // 0.4.0: x 1 = draw the sky-view map (lib SkyView layout), y 1 = a cloud map is bound, z 1 = sky events
            uniform sampler2D u_CloudMap;
            uniform vec4 u_Time;      // x seconds, y/z cosines of the sun's/moon's early-out angles, w horizon (radians)
            // 0.7.0-0.9.0: the sky's occasional sights (SkyEvents). u_Map.z is 1 while any of them shows.
            uniform vec4 u_Fx[8];
            // [0] x halo, y sun dogs, z circumzenithal arc, w green flash (strengths 0..1)
            // [1] x aurora, y noctilucent clouds, z zodiacal light, w meteor count
            // [2] nebula: x strength, y image index, z 1 / sin(radius), w cosine of its outer angle
            // [3] nebula: xyz centre among the stars, w its turn on the sky (radians)
            // [4] lunar eclipse, in moon radii: xy the shadow's centre, z its radius, w strength
            // [5] the halo's light: xyz towards the sun (or the moon by night), w its brightness
            // [6] x seconds, y the night's seed, z noctilucent upper limit (sine of the elevation), w pillar count
            // [7] xy the sun's horizontal direction, z the circumzenithal arc's elevation (radians)
            uniform vec4 u_Dog[2];       // sun dogs: xyz direction, w strength
            uniform vec4 u_Pillar[8];    // light pillars: xy horizontal direction, z 1 / width^2, w height falloff
            uniform vec4 u_PillarCol[8]; // rgb colour times intensity
            uniform vec4 u_Meteor[8];    // two rows each: head xyz + brightness, tail xyz + width
            in vec2 v_Uv;
            out vec4 fragColor;

            float sky_hash(vec3 p) {
                p = fract(p * vec3(443.897, 441.423, 437.195));
                p += dot(p, p.yzx + 19.19);
                return fract((p.x + p.y) * p.z);
            }

            // 0.3.3: polynomial arcsine and arctangent (the built-ins were most of the pass's cost at 1440p).
            // Arcsine after Abramowitz and Stegun 4.4.45, error under 7e-5 rad.
            float sky_asin(float x) {
                float a = abs(x);
                float r = 1.5707963 - sqrt(max(1.0 - a, 0.0)) * (1.5707288 + a * (-0.2121144 + a * (0.0742610 - 0.0187293 * a)));
                return x < 0.0 ? -r : r;
            }

            // Arctangent of y/x over the full circle, error about 1e-5 rad.
            float sky_atan2(float y, float x) {
                float ax = abs(x);
                float ay = abs(y);
                float a = min(ax, ay) / max(max(ax, ay), 1e-8);
                float s = a * a;
                float r = ((-0.0464964749 * s + 0.15931422) * s - 0.327622764) * s * a + a;
                r = ay > ax ? 1.57079637 - r : r;
                r = x < 0.0 ? 3.14159274 - r : r;
                return y < 0.0 ? -r : r;
            }

            vec3 sky_lut(vec3 dir) {
                // u_Time.w: the true horizon's elevation at the camera's height (just below level), from Java.
                float elevation = sky_asin(clamp(dir.y, -1.0, 1.0)) - u_Time.w;
                float lat = clamp(elevation / (SKY_PI * 0.5), -1.0, 1.0);
                float v = lat < 0.0 ? 0.5 - 0.5 * sqrt(-lat) : 0.5 + 0.5 * sqrt(lat);
                float azimuth = sky_atan2(dir.z, dir.x) - u_SkyCam.z;
                float u = fract(azimuth / (2.0 * SKY_PI));
                return texture(u_SkyView, vec2(u, v)).rgb;
            }

            vec3 sky_stars(vec3 dir, vec3 c, float night, float crowd) {
                if (night < 0.01 || u_Stars.x <= 0.0 || u_Look2.y <= 0.0) {
                    return vec3(0.0);
                }
                vec3 cell = floor(c * 160.0);
                float h = sky_hash(cell);
                vec3 star = vec3(0.0);
                // 0.6.0: the star amount sets the share of cells holding a star (7% at 100%, at most 25%).
                if (h > 1.0 - min(0.07 * u_Look2.y * (1.0 + crowd), 0.25)) {
                    vec3 centre = (cell + 0.5 + (vec3(sky_hash(cell + 1.7), sky_hash(cell + 3.1), sky_hash(cell + 5.3)) - 0.5) * 0.6) / 160.0;
                    // Squared angle from the chord (no acos): |a - b|^2 = 2 (1 - cos) ~ angle^2 for small angles.
                    vec3 toStar = normalize(centre) - c;
                    float ang2 = dot(toStar, toStar);
                    // Few bright stars, many faint ones.
                    float mag = pow(sky_hash(cell + 7.7), 8.0);
                    // 0.10.0: at least 1.2 pixels wide (a one-pixel star popped on and off as the sky turned under the
                    // pixel grid); its peak lowered to match.
                    float size = max(u_Fog.w * 1.2, 0.0003);
                    // 0.10.0: a slow, smooth twinkle: each star its own rate (a second hash: only h > 0.93 holds a
                    // star, so h alone gave every star the same 1.5 Hz), two waves that never line up, most near the
                    // horizon and least for the brightest stars, on the smoothed clock (no jumps).
                    float hs = sky_hash(cell + 11.3);
                    float tw = u_Twinkle.x * u_Twinkle.z;
                    float rate = 1.0 + 3.0 * hs;
                    float wave = 0.6 * sin(tw * rate + h * 40.0) + 0.4 * sin(tw * rate * 2.71 + hs * 23.0);
                    float amount = u_Twinkle.y * mix(1.0, 0.35, smoothstep(0.0, 0.25, mag))
                            * mix(1.0, 0.35, clamp(dir.y * 2.0, 0.0, 1.0));
                    float twinkle = (1.0 + amount * wave) * 0.75;
                    float temp = sky_hash(cell + 9.1);
                    vec3 col = mix(vec3(1.0, 0.8, 0.62), vec3(0.78, 0.86, 1.0), temp);
                    star = col * exp(-ang2 / (size * size)) * (0.08 + 2.2 * mag) * twinkle;
                }
                // A faint Milky Way band along a great circle, mottled by the layer texture.
                vec3 n = normalize(vec3(0.3, 0.85, 0.42));
                float across = dot(c, n);
                float band = exp(-across * across / 0.018) * u_Stars.y;
                vec3 milky = vec3(0.0);
                if (band > 0.01) {
                    vec2 suv = vec2(sky_atan2(c.z, c.x) / (2.0 * SKY_PI), c.y) * vec2(3.0, 1.5);
                    float mottle = textureLod(u_Noise, suv, 0.0).a * 0.6 + textureLod(u_Noise, suv * 3.1, 0.0).b * 0.4;
                    milky = vec3(0.55, 0.6, 0.75) * band * smoothstep(0.35, 0.8, mottle) * 0.05;
                }
                float visible = night * smoothstep(-0.02, 0.15, dir.y) * (1.0 - 0.85 * u_Look.z);
                return (star * u_Stars.x * 0.6 + milky) * visible;
            }

            // 0.9.0 "Omens": the Earth's shadow on the moon (d: disc coordinates in moon radii). Outside the shadow's edge the
            // penumbra dims the disc; inside, the moon is lit only by the Earth's sunsets: copper red, darker at the centre.
            // cover: how much of this pixel is moon (0.9.0: the umbra colour had a floor, so the transparent margin of a
            // resource pack's moon quad turned red, a red square round the moon).
            vec3 sky_eclipse(vec3 moonCol, vec2 d, float cover) {
                if (u_Fx[4].w <= 0.0) {
                    return moonCol;
                }
                float ru = u_Fx[4].z;
                float dist = length(d - u_Fx[4].xy);
                float pen = mix(0.45, 1.0, smoothstep(ru, ru * 1.75, dist));
                float lit = smoothstep(ru * 0.94, ru * 1.05, dist);
                float lum = dot(moonCol, vec3(0.3, 0.6, 0.1));
                vec3 umbra = vec3(0.85, 0.2, 0.07) * max(lum, 0.45 * cover) * (0.65 + 0.6 * smoothstep(0.0, ru, dist));
                return mix(moonCol, mix(umbra, moonCol * pen, lit), u_Fx[4].w);
            }

            vec3 sky_moon(vec3 dir, float night) {
                float cosAng = dot(dir, u_Moon.xyz);
                if (cosAng < u_Time.z) {
                    return vec3(0.0);
                }
                float ang = acos(min(cosAng, 1.0));
                float rad = u_Moon.w;
                // Disc coordinates: a basis around the moon's direction (z is the axis of the sun's and moon's path).
                vec3 m = u_Moon.xyz;
                vec3 east = normalize(cross(abs(m.z) < 0.9 ? vec3(0.0, 0.0, 1.0) : vec3(1.0, 0.0, 0.0), m));
                vec3 north = cross(m, east);
                vec2 d = vec2(dot(dir, east), dot(dir, north)) / sin(rad);
                if (u_MoonPhase.z > 0.5) {
                    // The resource pack's moon: its phase's cell of the 4x2 atlas, at twice the disc's radius (vanilla's
                    // moon texture has a margin).
                    vec2 q = d * 0.5;
                    if (any(greaterThan(abs(q), vec2(1.0)))) {
                        return vec3(0.0);
                    }
                    float idx = u_MoonPhase.w;
                    vec2 cellUv = vec2(mod(idx, 4.0), floor(idx / 4.0)) * vec2(0.25, 0.5);
                    vec4 tex = textureLod(u_PackMoon, cellUv + (q * vec2(0.5, -0.5) + 0.5) * vec2(0.25, 0.5), 0.0);
                    vec3 packCol = tex.rgb * tex.a * 1.2;
                    if (u_Fx[4].w <= 0.0) {
                        return packCol;
                    }
                    // 0.10.0: the eclipse colours the disc only (its radius measured from the pack's texture), and the
                    // pack's halo round it fades to a faint warm glow (it drew a red ring, and the Earth's shadow edge
                    // showed in it as a large circle). Any moon size: d is in moon radii.
                    float inside = 1.0 - smoothstep(u_PackDisc.x * 0.97, u_PackDisc.x * 1.03, length(d));
                    vec3 halo = packCol * mix(vec3(1.0), vec3(0.1, 0.035, 0.02), u_Fx[4].w);
                    return mix(halo, sky_eclipse(packCol, d, inside), inside);
                }
                float rr = dot(d, d);
                float glow = exp(-ang * ang / (rad * rad * 1.2)) * 0.06;
                // In totality the glow round the moon turns red with it.
                float total = u_Fx[4].w * (1.0 - smoothstep(u_Fx[4].z - 1.2, u_Fx[4].z - 0.7, length(u_Fx[4].xy)));
                vec3 glowCol = mix(vec3(0.75, 0.8, 0.9), vec3(0.8, 0.25, 0.12), total);
                if (rr > 1.0) {
                    return glowCol * glow * u_MoonPhase.y * (1.0 - 0.67 * total);
                }
                vec3 normal = vec3(d, sqrt(1.0 - rr));
                float phase = u_MoonPhase.x;
                vec3 light = vec3(sin(phase), 0.0, cos(phase));
                // Explicit mip levels: texels of the layer texture per screen pixel across the disc.
                float perPixel = 512.0 * u_Fog.w / sin(rad);
                float craters = textureLod(u_Noise, d * 0.35 + vec2(0.31, 0.47), log2(max(perPixel * 0.35, 1.0))).b;
                float maria = smoothstep(0.42, 0.62,
                        textureLod(u_Noise, d * 0.12 + vec2(0.7, 0.2), log2(max(perPixel * 0.12, 1.0))).a);
                vec3 albedo = vec3(0.92, 0.9, 0.86) * (0.78 + 0.22 * craters) * (1.0 - 0.32 * maria);
                float lit = max(dot(normal, light), 0.0);
                // The lit part is as bright at a crescent as at full moon; earthshine on the dark side; a soft
                // terminator. The glow around it follows the moon's brightness.
                vec3 col = albedo * (smoothstep(0.0, 0.08, lit) * (0.35 + 0.65 * lit) + 0.025);
                // 0.10.0: in totality the red glow round the disc is a third as bright (only a slight radiance).
                return sky_eclipse(col * 1.1, d, 1.0) + glowCol * glow * u_MoonPhase.y * (1.0 - 0.67 * total);
            }

            // The sun, in display values: disc and corona tinted by the sun's transmittance (normalised), so a
            // setting sun stays orange instead of tonemapping to white. The glow beside it is the model's Mie lobe.
            vec3 sky_sun(vec3 dir, vec3 sunT) {
                vec3 disc = vec3(0.0);
                float cosAng = dot(dir, u_Sun.xyz);
                if (cosAng < u_Time.y) {
                    return disc;
                }
                float ang = acos(min(cosAng, 1.0));
                float rad = u_Sun.w;
                float peak = max(sunT.r, max(sunT.g, sunT.b));
                vec3 tint = sunT / max(peak, 1e-5);
                float fade = smoothstep(0.0, 0.02, peak);
                if (u_MoonPhase.z > 0.5) {
                    // The resource pack's sun.png, at twice the disc's radius (vanilla's sun texture has a margin).
                    vec3 sunE = normalize(cross(abs(u_Sun.z) < 0.9 ? vec3(0.0, 0.0, 1.0) : vec3(1.0, 0.0, 0.0), u_Sun.xyz));
                    vec3 sunN = cross(u_Sun.xyz, sunE);
                    vec2 d = vec2(dot(dir, sunE), dot(dir, sunN)) / sin(rad * 2.0);
                    if (all(lessThan(abs(d), vec2(1.0)))) {
                        vec4 tex = textureLod(u_PackSun, d * 0.5 + 0.5, 0.0);
                        disc = tex.rgb * tex.a * tint * fade;
                    }
                    return disc;
                }
                // Limb-darkened disc with an edge one pixel wide, and a small corona.
                float x = clamp(ang / rad, 0.0, 1.0);
                float mu = sqrt(max(0.0, 1.0 - x * x));
                float edge = 1.0 - smoothstep(rad - u_Fog.w, rad + u_Fog.w, ang);
                disc = tint * (1.0 - 0.45 * (1.0 - mu)) * edge * fade;
                float corona = exp(-max(ang - rad, 0.0) / (rad * 0.7)) * 0.35 * (1.0 - edge);
                return max(disc, tint * corona * fade);
            }

            vec3 sky_saturate(vec3 c, float s) {
                float l = dot(c, vec3(0.2126, 0.7152, 0.0722));
                return mix(vec3(l), c, s);
            }

            // ---- 0.7.0 "Optics": ice crystals in cirrus bend the light of the sun (or a bright moon). ----
            vec3 sky_optics(vec3 dir) {
                vec3 add = vec3(0.0);
                vec3 src = u_Fx[5].xyz;
                float cosA = dot(dir, src);
                if (u_Fx[0].x > 0.0 && cosA > 0.82 && cosA < 0.945) {
                    // The 22 degree halo: a sharp inner edge, red inside (21.7 degrees) to blue (22.4), fading slowly outwards.
                    float a = acos(cosA);
                    vec3 inner = smoothstep(vec3(0.3735, 0.3787, 0.3857), vec3(0.3822, 0.3875, 0.3962), vec3(a));
                    float outer = exp(-max(a - 0.384, 0.0) / 0.05);
                    add += inner * outer * vec3(1.0, 0.97, 0.94) * (0.35 * u_Fx[0].x * u_Fx[5].w);
                }
                if (u_Fx[0].y > 0.0) {
                    // Sun dogs: a bright spot at the sun's height on each side, red towards the sun, with a white tail away from it.
                    for (int i = 0; i < 2; i++) {
                        vec3 d = u_Dog[i].xyz;
                        if (dot(dir, d) > 0.985) {
                            vec3 away = normalize(d - src * dot(d, src));
                            vec3 rel = dir - d;
                            float along = dot(rel, away);
                            float across2 = max(dot(rel, rel) - along * along, 0.0);
                            float tail = along > 0.0 ? 0.09 : 0.022;
                            float g = exp(-along * along / (tail * tail) - across2 / 0.0004);
                            vec3 tint = mix(vec3(1.0, 0.45, 0.2), vec3(0.95, 0.97, 1.0), smoothstep(-0.02, 0.04, along));
                            add += tint * g * (0.75 * u_Fx[0].y * u_Dog[i].w);
                        }
                    }
                }
                if (u_Fx[0].z > 0.0 && dir.y > 0.6) {
                    // The circumzenithal arc: a quarter circle of pure colours round the zenith, on the sun's side, red towards
                    // the sun. u_Fx[7].z is its elevation for green; red lies lower, violet higher.
                    vec2 h = dir.xz / max(length(dir.xz), 1e-4);
                    float side = dot(h, u_Fx[7].xy);
                    if (side > 0.3) {
                        vec3 off = vec3(sky_asin(dir.y)) - (u_Fx[7].z + vec3(-0.012, 0.0, 0.016));
                        add += exp(-off * off / 0.000121) * (0.35 * u_Fx[0].z * smoothstep(0.3, 0.75, side));
                    }
                }
                return add;
            }

            // ---- 0.8.0 "Night" and the light pillars: each term is skipped by its uniform while it is not showing. ----
            vec3 sky_nightfx(vec3 dir) {
                vec3 add = vec3(0.0);
                vec2 hz = dir.xz / max(length(dir.xz), 1e-4);
                if (u_Fx[1].z > 0.0) {
                    // Zodiacal light: a faint cone along the sun's path, leaning up from where the sun set or will rise.
                    float toSun = dot(dir, u_Sun.xyz);
                    add += vec3(1.0, 0.93, 0.8) * (0.1 * u_Fx[1].z) * exp(-(1.0 - toSun) * 3.2) * exp(-dir.z * dir.z / 0.03)
                            * smoothstep(0.0, 0.12, dir.y);
                }
                if (u_Fx[1].x > 0.0 && dir.y > 0.06) {
                    // Aurora: curtains in the northern sky. Thin bright ridges of a slow noise field on a plane far overhead,
                    // sampled six times up the view ray (a hanging sheet seen from below): green low down, red-violet at the top.
                    float mask = smoothstep(-0.25, 0.6, -hz.y) * smoothstep(0.06, 0.2, dir.y);
                    if (mask > 0.01) {
                        float t = u_Fx[6].x;
                        vec2 p = dir.xz / (dir.y + 0.12);
                        vec2 warp = (textureLod(u_Noise, p * 0.04 + vec2(u_Fx[6].y, t * 0.0015), 0.0).ga - 0.5) * 0.25;
                        // 0.9.0: the rays come from the warp's own fetch, and 4 layers (6 cost 0.05 ms at 1440p).
                        float rays = 0.65 + 1.4 * abs(warp.x);
                        vec3 sum = vec3(0.0);
                        for (int k = 0; k < 4; k++) {
                            float fk = float(k) * 0.3333;
                            vec2 q = p * (1.0 + 0.22 * fk) * 0.09 + warp + vec2(t * 0.003, u_Fx[6].y * 3.0);
                            float ridge = 1.0 - abs(2.0 * textureLod(u_Noise, q, 0.0).a - 1.0);
                            ridge *= ridge;
                            ridge *= ridge;
                            ridge *= ridge;
                            sum += mix(vec3(0.15, 1.0, 0.45), vec3(0.75, 0.2, 0.65), fk * fk) * ridge * (1.0 - 0.6 * fk);
                        }
                        add += sum * (0.15 * u_Fx[1].x) * mask * rays;
                    }
                }
                if (u_Fx[1].y > 0.0 && dir.y > 0.02) {
                    // Noctilucent clouds: ice at the edge of space, still in sunlight long after sunset. Rippled blue-white
                    // bands low over the sun's side of the horizon; their upper limit sinks as the sun does.
                    float band = smoothstep(0.03, 0.09, dir.y) * (1.0 - smoothstep(u_Fx[6].z * 0.6, u_Fx[6].z, dir.y));
                    float m = smoothstep(0.1, 0.8, dot(hz, u_Fx[7].xy)) * band;
                    if (m > 0.01) {
                        vec2 p = dir.xz / (dir.y + 0.05) * 0.035 + vec2(u_Fx[6].y * 5.0, u_Fx[6].x * 0.0004);
                        float fibres = textureLod(u_Noise, p, 0.0).r;
                        float field = textureLod(u_Noise, p * 0.31 + 0.4, 0.0).a;
                        float ripple = 0.5 + 0.5 * sin(dot(p, vec2(210.0, 130.0)) + 6.0 * field);
                        float n = smoothstep(0.35, 0.8, fibres * 0.75 + field * 0.5) * (0.55 + 0.45 * ripple);
                        add += vec3(0.55, 0.8, 1.0) * n * m * (0.32 * u_Fx[1].y);
                    }
                }
                // Light pillars: columns of light standing over distant lights (and over a sun at the horizon), from flat ice
                // crystals drifting near the ground. A bright core low down and a faint glow round it.
                int pillars = int(u_Fx[6].w);
                for (int i = 0; i < pillars; i++) {
                    vec4 pl = u_Pillar[i];
                    if (dot(hz, pl.xy) > 0.98) {
                        float sn = hz.x * pl.y - hz.y * pl.x;
                        float g = exp(-sn * sn * pl.z) + 0.25 * exp(-sn * sn * pl.z * 0.08);
                        add += u_PillarCol[i].rgb * g * exp(-dir.y * pl.w) * smoothstep(0.0, 0.03, dir.y);
                    }
                }
                // Meteors: a short streak from the tail to the head, brightest at the head.
                int meteors = int(u_Fx[1].w);
                for (int i = 0; i < meteors; i++) {
                    vec3 tail = u_Meteor[2 * i + 1].xyz;
                    vec3 seg = u_Meteor[2 * i].xyz - tail;
                    float along = clamp(dot(dir - tail, seg) / max(dot(seg, seg), 1e-8), 0.0, 1.0);
                    vec3 off = dir - (tail + seg * along);
                    float w = max(u_Meteor[2 * i + 1].w, u_Fog.w * 1.2);
                    add += vec3(0.9, 0.95, 1.0) * (exp(-dot(off, off) / (w * w)) * along * along * u_Meteor[2 * i].w);
                }
                return add;
            }

            // 0.9.0: a nebula: a real telescope image on a patch of the sky, its long side the nebula's radius, turned by
            // the night's angle, its outline following the image (bright gas outlasts faint background) and lobed so it
            // is not an oval. 0.10.0: up to three (k), in the nebula frame c (nearly still against the land). The mip
            // level comes from the pixel's size on the sky (no derivatives: this runs inside branches).
            vec3 sky_nebula(vec3 c, int k, sampler2D tex, float pixel) {
                vec4 a = u_Neb[k * 4];
                vec4 b = u_Neb[k * 4 + 1];
                vec4 s = u_Neb[k * 4 + 2];
                if (s.w <= 0.0 || dot(c, a.xyz) < b.x) {
                    return vec3(0.0);
                }
                vec3 n = a.xyz;
                vec3 e1 = normalize(cross(abs(n.y) < 0.9 ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 0.0, 0.0), n));
                vec3 e2 = cross(n, e1);
                vec2 q = vec2(dot(c, e1), dot(c, e2)) * a.w;
                q = mat2(cos(b.y), sin(b.y), -sin(b.y), cos(b.y)) * q;
                vec2 uv = 0.5 + q * s.xy;
                vec2 edge = min(uv, 1.0 - uv);
                if (min(edge.x, edge.y) <= 0.0) {
                    return vec3(0.0);
                }
                float texels = pixel * a.w * 0.5 * s.w;
                vec3 img = textureLod(tex, uv, log2(max(texels, 1.0))).rgb;
                float lum = dot(img, vec3(0.3, 0.6, 0.1));
                vec2 e = (uv - 0.5) * 2.0;
                float around = atan(e.y, e.x);
                float warp = 0.13 * sin(3.0 * around + b.y * 2.3) + 0.08 * sin(5.0 * around + b.y * 4.1)
                        + 0.05 * sin(8.0 * around + b.y * 6.7);
                float falloff = 1.0 - smoothstep(0.45, 1.0, length(e) + warp);
                float fade = smoothstep(0.0, 1.0, falloff * (0.6 + 1.8 * lum)) * smoothstep(0.0, 0.05, min(edge.x, edge.y));
                return img * (fade * b.z * s.z * 0.8);
            }

            // 0.9.0: the nebula's glow, a broad haze of its mean colour (more saturated) reaching past the image.
            vec3 sky_nebulaGlow(vec3 c, int k) {
                vec4 a = u_Neb[k * 4];
                vec4 b = u_Neb[k * 4 + 1];
                vec3 g = u_Neb[k * 4 + 3].rgb;
                float d = acos(clamp(dot(c, a.xyz), -1.0, 1.0));
                float x = d / (1.5 * max(b.w, 1e-3));
                float l = dot(g, vec3(0.3, 0.6, 0.1));
                return max(mix(vec3(l), g, 1.8), 0.0) * (0.16 * exp(-x * x) * b.z);
            }

            void main() {
                bool mapMode = u_Map.x > 0.5;
                vec3 dir;
                if (mapMode) {
                    // The sky-view map: u world azimuth, v = sqrt(elevation / (pi / 2)).
                    float mapAz = (v_Uv.x - 0.5) * 6.2831853;
                    float mapEl = v_Uv.y * v_Uv.y * 1.5707963;
                    dir = vec3(cos(mapEl) * cos(mapAz), max(sin(mapEl), 0.002), cos(mapEl) * sin(mapAz));
                } else {
                    vec4 far = u_InvViewProj * vec4(v_Uv * 2.0 - 1.0, 1.0, 1.0);
                    dir = normalize(far.xyz / far.w);
                }
                // 0.6.0: above the cloud deck the ground's fog (dark in storms) does not belong at the horizon; the sky's own
                // horizon colour takes its place (a uniform branch: only while flying over the clouds).
                vec3 fogC = u_Fog.rgb;
                if (u_Look2.z > 0.0) {
                    vec3 hz = sky_lut(normalize(vec3(dir.x, 0.01, dir.z)));
                    hz = pow(vec3(1.0) - exp(-hz * u_SkyCam.w), vec3(1.0 / 2.2));
                    fogC = mix(fogC, sky_saturate(hz, u_Look.x) * 0.9, u_Look2.z);
                }
                // 0.10.0: the horizon colour of this azimuth: half the fog colour, half the drawn sky just above the
                // horizon (the same as Atmosphera's terrain haze and far-cloud haze use), so sky, distant terrain, far clouds
                // and the void below meet in one colour all round, orange towards a setting sun (the single fog colour
                // left a straight line where terrain met the sky at dawn and dusk).
                float hzAz = sky_atan2(dir.z, dir.x) - u_SkyCam.z;
                float hzLat = clamp((0.035 - u_Time.w) / 1.5707963, 0.0, 1.0);
                vec3 hzLum = textureLod(u_SkyView, vec2(fract(hzAz / 6.2831853), 0.5 + 0.5 * sqrt(hzLat)), 0.0).rgb;
                vec3 hzC = mix(fogC, pow(vec3(1.0) - exp(-hzLum * u_SkyCam.w), vec3(1.0 / 2.2)), 0.5 * (1.0 - u_Look2.z));
                if (dir.y <= 0.0) {
                    // Below the horizon the sky carries on in the horizon colour (the user's Atmospherics "sky wall"),
                    // darkening only slowly towards the void, so there is no edge to see under a terrain silhouette.
                    float voidDark = mix(1.0, 0.75, smoothstep(0.0, -0.43, dir.y) * (1.0 - u_Look2.z));
                    fragColor = vec4(hzC * voidDark, 1.0);
                    return;
                }
                vec3 sunT = sky_transmittance(u_SkyCam.x, u_Sun.y);
                vec3 lum = sky_lut(dir);
                // Rain and storms: a greyer, darker sky (the clouds do most of it).
                float rain = u_Look.z;
                lum = sky_saturate(lum, 1.0 - 0.6 * rain) * (1.0 - 0.35 * rain - 0.2 * u_Look.w);
                // Linear light to the screen: exposure (raised in twilight, as eyes adapt), a soft shoulder, display
                // gamma. Everything after this is in the game's display values (like the fog colour).
                vec3 col = pow(vec3(1.0) - exp(-lum * u_SkyCam.w), vec3(1.0 / 2.2));
                if (!mapMode) {
                    col = max(col, sky_sun(dir, sunT) * (1.0 - 0.8 * rain));
                }
                // 0.7.0: the occasional sights, all behind one uniform branch (nothing runs on an ordinary sky).
                bool fx = !mapMode && u_Map.z > 0.5;
                if (fx) {
                    col += sky_optics(dir) * (1.0 - 0.7 * rain);
                }
                // Night: the model's sky goes black; a dim moonlit blue, airglow and the stars take over.
                float night = 1.0 - smoothstep(-0.18, 0.02, u_Sun.y);
                if (night > 0.001) {
                    // A uniform branch: by day none of this runs.
                    vec3 nightSky = vec3(0.010, 0.014, 0.030) * (0.35 + 0.65 * u_MoonPhase.y);
                    float above = max(dir.y, 0.0);
                    vec3 airglow = mix(vec3(0.010, 0.016, 0.006), vec3(0.012, 0.008, 0.003), above) * exp(-above * 6.0) * u_Look2.x;
                    col += (nightSky * (0.6 + 0.4 * (1.0 - above)) + airglow) * night;
                    // Celestial frame: the sky turns with the sun about the world's z axis, as vanilla's sun and stars do
                    // (u_Stars.z, w: cosine and sine of the rotation, from Java).
                    vec3 cel = vec3(dir.x * u_Stars.z + dir.y * u_Stars.w, -dir.x * u_Stars.w + dir.y * u_Stars.z, dir.z);
                    float crowd = 0.0;
                    vec3 nebLight = vec3(0.0);
                    if (u_NebFrame.z > 0.5) {
                        // 0.10.0: the nebula frame turns at a tenth of the sky's rate. Also in the water reflection
                        // map (blurred by its coarse pixel), so water reflects them.
                        vec3 nc = vec3(dir.x * u_NebFrame.x + dir.y * u_NebFrame.y, -dir.x * u_NebFrame.y + dir.y * u_NebFrame.x, dir.z);
                        float pix = mapMode ? 0.05 : u_Fog.w;
                        nebLight = sky_nebula(nc, 0, u_Nebula0, pix) + sky_nebulaGlow(nc, 0);
                        if (u_NebFrame.z > 1.5) {
                            nebLight += sky_nebula(nc, 1, u_Nebula1, pix) + sky_nebulaGlow(nc, 1);
                        }
                        if (u_NebFrame.z > 2.5) {
                            nebLight += sky_nebula(nc, 2, u_Nebula2, pix) + sky_nebulaGlow(nc, 2);
                        }
                        nebLight *= night * smoothstep(-0.02, 0.15, dir.y) * (1.0 - 0.85 * u_Look.z);
                    }
                    if (!mapMode) {
                        // The image has its own stars: the sky's stars thin out where the nebula is bright.
                        float nebLum = dot(nebLight, vec3(0.3, 0.6, 0.1));
                        col += sky_stars(dir, cel, night, crowd) * (1.0 - 0.8 * smoothstep(0.02, 0.25, nebLum));
                    }
                    // Screen blend: the nebula lightens the sky without going past white.
                    col += nebLight * (1.0 - clamp(col, 0.0, 1.0));
                }
                if (fx) {
                    col += sky_nightfx(dir) * (1.0 - 0.85 * rain);
                }
                // By day the moon is pale (the sky's light lies over it).
                if (!mapMode) {
                    col += sky_moon(dir, night) * (1.0 - 0.7 * rain) * mix(0.3, 1.0, night);
                }
                col = sky_saturate(col, u_Look.x);
                if (mapMode && u_Map.y > 0.5) {
                    // Clouds in the map (Atmosphera's cloud map, the same layout): sky * T + light, with the clouds'
                    // composite shoulder.
                    vec4 cm = textureLod(u_CloudMap, v_Uv, 0.0);
                    vec3 over = max(cm.rgb - 0.8, 0.0);
                    col = col * cm.a + min(cm.rgb, vec3(0.8)) + 0.2 * (1.0 - exp(-over / 0.2));
                }
                // The horizon fades into the horizon colour (0.10.0: per azimuth, see hzC), so distant terrain and
                // clouds meet the sky without a seam. A smooth curve over the horizon blend angle.
                float elev = dir.y;
                float blend = 1.0 - smoothstep(0.0, max(u_Look.y, 1e-3), elev);
                col = mix(col, hzC, blend * blend * (3.0 - 2.0 * blend));
                if (fx && u_Fx[0].w > 0.0 && dot(dir, u_Sun.xyz) > u_Time.y) {
                    // The green flash: the last sliver of the sun, its top edge, as it meets the horizon.
                    vec3 rel = (dir - u_Sun.xyz) / u_Sun.w;
                    float cap = smoothstep(0.55, 0.95, rel.y) * (1.0 - smoothstep(1.0, 1.35, rel.y))
                            * (1.0 - smoothstep(0.5, 0.95, length(rel.xz)));
                    col += vec3(0.25, 1.0, 0.35) * cap * (2.0 * u_Fx[0].w) * smoothstep(0.0, 0.004, dir.y);
                }
                fragColor = vec4(col, 1.0);
            }
            """;
}
