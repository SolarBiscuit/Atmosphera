package com.solarbiscuit.atmosphera.stratos.sky;

/**
 * The sky's programs (GLSL 330 core, after the lib's {@code GlslPrograms.header()}). A physically based atmosphere in
 * the manner of Hillaire's 2020 paper, written for Stratos: Rayleigh and Mie scattering, ozone absorption and the
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
                float horizon = -acos(clamp(sqrt(max(r * r - SKY_RG * SKY_RG, 0.0)) / r, -1.0, 1.0));
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
            uniform sampler2D u_Noise;         // Stratos's layer texture (crater and Milky Way detail)
            uniform sampler2D u_PackSun;
            uniform sampler2D u_PackMoon;
            uniform mat4 u_InvViewProj;
            uniform vec4 u_SkyCam;    // x camera radius (km), y sun cosine-zenith, z sun azimuth (radians), w exposure
            uniform vec4 u_Sun;       // xyz towards the sun (world), w angular radius (radians)
            uniform vec4 u_Moon;      // xyz towards the moon, w angular radius
            uniform vec4 u_MoonPhase; // x phase angle (0 full .. pi new), y brightness, z pack textures (1), w phase index
            uniform vec4 u_Stars;     // x brightness, y Milky Way, z celestial rotation (radians), w airglow
            uniform vec4 u_Look;      // x saturation, y horizon blend (radians), z rain, w thunder
            uniform vec4 u_Fog;       // rgb fog colour (the game's, biome tinted), w pixel angle
            uniform vec4 u_Time;      // x seconds
            in vec2 v_Uv;
            out vec4 fragColor;

            float sky_hash(vec3 p) {
                p = fract(p * vec3(443.897, 441.423, 437.195));
                p += dot(p, p.yzx + 19.19);
                return fract((p.x + p.y) * p.z);
            }

            vec3 sky_lut(vec3 dir) {
                float r = u_SkyCam.x;
                float horizon = -acos(clamp(sqrt(max(r * r - SKY_RG * SKY_RG, 0.0)) / r, -1.0, 1.0));
                float elevation = asin(clamp(dir.y, -1.0, 1.0)) - horizon;
                float lat = clamp(elevation / (SKY_PI * 0.5), -1.0, 1.0);
                float v = lat < 0.0 ? 0.5 - 0.5 * sqrt(-lat) : 0.5 + 0.5 * sqrt(lat);
                float azimuth = atan(dir.z, dir.x) - u_SkyCam.z;
                float u = fract(azimuth / (2.0 * SKY_PI));
                return texture(u_SkyView, vec2(u, v)).rgb;
            }

            vec3 sky_stars(vec3 dir, float night) {
                if (night < 0.01 || u_Stars.x <= 0.0) {
                    return vec3(0.0);
                }
                // Celestial frame: the sky turns with the sun about the world's z axis, as vanilla's sun and stars do
                // (the sun's direction is fixed in this frame).
                float a = u_Stars.z;
                vec3 c = vec3(dir.x * cos(a) + dir.y * sin(a), -dir.x * sin(a) + dir.y * cos(a), dir.z);
                vec3 cell = floor(c * 160.0);
                float h = sky_hash(cell);
                vec3 star = vec3(0.0);
                if (h > 0.93) {
                    vec3 centre = (cell + 0.5 + (vec3(sky_hash(cell + 1.7), sky_hash(cell + 3.1), sky_hash(cell + 5.3)) - 0.5) * 0.6) / 160.0;
                    // Squared angle from the chord (no acos): |a - b|^2 = 2 (1 - cos) ~ angle^2 for small angles.
                    vec3 toStar = normalize(centre) - c;
                    float ang2 = dot(toStar, toStar);
                    // Few bright stars, many faint ones.
                    float mag = pow(sky_hash(cell + 7.7), 8.0);
                    float size = max(u_Fog.w * 0.9, 0.0003);
                    float twinkle = 1.0 + 0.35 * sin(u_Time.x * (3.0 + 7.0 * h) + h * 40.0) * (1.0 - clamp(dir.y * 3.0, 0.0, 1.0));
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
                    vec2 suv = vec2(atan(c.z, c.x) / (2.0 * SKY_PI), c.y) * vec2(3.0, 1.5);
                    float mottle = textureLod(u_Noise, suv, 0.0).a * 0.6 + textureLod(u_Noise, suv * 3.1, 0.0).b * 0.4;
                    milky = vec3(0.55, 0.6, 0.75) * band * smoothstep(0.35, 0.8, mottle) * 0.05;
                }
                float visible = night * smoothstep(-0.02, 0.15, dir.y) * (1.0 - 0.85 * u_Look.z);
                return (star * u_Stars.x * 0.6 + milky) * visible;
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
                    vec4 tex = texture(u_PackMoon, cellUv + (q * vec2(0.5, -0.5) + 0.5) * vec2(0.25, 0.5));
                    return tex.rgb * tex.a * 1.2;
                }
                float rr = dot(d, d);
                float glow = exp(-ang * ang / (rad * rad * 1.2)) * 0.06;
                if (rr > 1.0) {
                    return vec3(0.75, 0.8, 0.9) * glow * u_MoonPhase.y;
                }
                vec3 normal = vec3(d, sqrt(1.0 - rr));
                float phase = u_MoonPhase.x;
                vec3 light = vec3(sin(phase), 0.0, cos(phase));
                float craters = texture(u_Noise, d * 0.35 + vec2(0.31, 0.47)).b;
                float maria = smoothstep(0.42, 0.62, texture(u_Noise, d * 0.12 + vec2(0.7, 0.2)).a);
                vec3 albedo = vec3(0.92, 0.9, 0.86) * (0.78 + 0.22 * craters) * (1.0 - 0.32 * maria);
                float lit = max(dot(normal, light), 0.0);
                // The lit part is as bright at a crescent as at full moon; earthshine on the dark side; a soft
                // terminator. The glow around it follows the moon's brightness.
                vec3 col = albedo * (smoothstep(0.0, 0.08, lit) * (0.35 + 0.65 * lit) + 0.025);
                return col * 1.1 + vec3(0.75, 0.8, 0.9) * glow * u_MoonPhase.y;
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
                        vec4 tex = texture(u_PackSun, d * 0.5 + 0.5);
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

            void main() {
                vec4 far = u_InvViewProj * vec4(v_Uv * 2.0 - 1.0, 1.0, 1.0);
                vec3 dir = normalize(far.xyz / far.w);
                if (dir.y <= 0.0) {
                    // At and below the horizon the sky is all fog colour, darkening into the void (as below).
                    fragColor = vec4(u_Fog.rgb * mix(1.0, 0.7, smoothstep(0.0, -0.3, dir.y)), 1.0);
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
                col = max(col, sky_sun(dir, sunT) * (1.0 - 0.8 * rain));
                // Night: the model's sky goes black; a dim moonlit blue, airglow and the stars take over.
                float night = 1.0 - smoothstep(-0.18, 0.02, u_Sun.y);
                vec3 nightSky = vec3(0.010, 0.014, 0.030) * (0.35 + 0.65 * u_MoonPhase.y);
                float above = max(dir.y, 0.0);
                vec3 airglow = mix(vec3(0.010, 0.016, 0.006), vec3(0.012, 0.008, 0.003), above) * exp(-above * 6.0) * u_Stars.w;
                col += (nightSky * (0.6 + 0.4 * (1.0 - above)) + airglow) * night;
                col += sky_stars(dir, night);
                // By day the moon is pale (the sky's light lies over it).
                col += sky_moon(dir, night) * (1.0 - 0.7 * rain) * mix(0.3, 1.0, night);
                col = sky_saturate(col, u_Look.x);
                // The horizon fades into the game's fog colour, so terrain fog meets the sky without a seam; below
                // the horizon the void darkens as in vanilla.
                float elev = dir.y;
                float blend = 1.0 - smoothstep(0.0, max(u_Look.y, 1e-3), elev);
                col = mix(col, u_Fog.rgb, blend);
                col *= mix(1.0, 0.7, smoothstep(0.0, -0.3, elev));
                fragColor = vec4(col, 1.0);
            }
            """;
}
