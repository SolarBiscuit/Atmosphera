package com.solarbiscuit.atmosphera.atmosphera.sky;

import static com.solarbiscuit.atmosphera.lib.util.AtmosMath.lerp;

/**
 * The Java twin of {@link SkyGlsl}'s atmosphere: the same constants and integrals, run once at start-up into a small
 * table of the sky's light at the zenith and at the horizon by the sun's height. Read each frame for the lib's
 * {@code SkySource} (Torrente's reflections, Lumis) and for the clouds' ambient light, so they match the drawn sky
 * without reading anything back from the GPU. Pure Java; no game classes.
 */
public final class SkyModel {
    static final double RG = 6360.0;
    static final double RT = 6460.0;
    private static final double[] RAYLEIGH = {5.802e-3, 13.558e-3, 33.1e-3};
    private static final double RAYLEIGH_H = 8.0;
    private static final double MIE_SCATTER = 3.996e-3;
    private static final double MIE_EXTINCT = 4.4e-3;
    private static final double MIE_H = 1.2;
    private static final double[] OZONE = {0.650e-3, 1.881e-3, 0.085e-3};
    private static final double GROUND_ALBEDO = 0.3;

    /** Camera height above the ground used for the table (km). */
    static final double TABLE_HEIGHT = 0.2;
    /** Entries over the sun's cosine-zenith, -1 .. 1. */
    static final int SUN_STEPS = 129;
    private static final int MS_SUN = 32;
    private static final int MS_HEIGHT = 8;

    /**
     * The built tables, published together (0.14.1). They were three plain fields written one after another by the
     * building thread, so the render thread could see the zenith table (ready) with the horizon or sunlight table
     * still missing, or arrays whose contents it had not been guaranteed to see.
     * {@code sunlight} (0.12.0): transmittance to the sun by height ({@link #SUN_HEIGHTS}) and sun cosine-zenith.
     */
    private record Tables(float[] zenith, float[] horizon, float[] sunlight) {
    }

    private static volatile Tables tables;
    /** Heights (km above the ground) of the sunlight table; the clouds use 2 (low deck) and 8 (high clouds). */
    static final double[] SUN_HEIGHTS = {0.2, 1.0, 2.0, 4.0, 8.0, 12.0};
    private static double[] ms;

    private SkyModel() {
    }

    /** Builds the tables (about 20-60 ms); safe to call again. Any thread; the tables swap in whole. */
    public static synchronized void build() {
        double[] msTable = new double[MS_SUN * MS_HEIGHT * 3];
        double[] rgb = new double[3];
        for (int h = 0; h < MS_HEIGHT; h++) {
            double r = RG + 0.01 + (h / (double) (MS_HEIGHT - 1)) * (RT - RG - 0.02);
            for (int s = 0; s < MS_SUN; s++) {
                double muS = s / (double) (MS_SUN - 1) * 2.0 - 1.0;
                multiScatter(r, muS, rgb);
                int at = (h * MS_SUN + s) * 3;
                msTable[at] = rgb[0];
                msTable[at + 1] = rgb[1];
                msTable[at + 2] = rgb[2];
            }
        }
        ms = msTable;
        float[] z = new float[SUN_STEPS * 3];
        float[] hz = new float[SUN_STEPS * 3];
        double r = RG + TABLE_HEIGHT;
        double[] out = new double[3];
        double[] acc = new double[3];
        for (int s = 0; s < SUN_STEPS; s++) {
            double muS = s / (double) (SUN_STEPS - 1) * 2.0 - 1.0;
            inScatter(r, muS, 0.0, 1.0, out);
            z[s * 3] = (float) out[0];
            z[s * 3 + 1] = (float) out[1];
            z[s * 3 + 2] = (float) out[2];
            // Horizon: 8 azimuths a little above the horizon, averaged.
            acc[0] = acc[1] = acc[2] = 0.0;
            for (int a = 0; a < 8; a++) {
                inScatter(r, muS, a * Math.PI / 4.0, 0.05, out);
                acc[0] += out[0] / 8.0;
                acc[1] += out[1] / 8.0;
                acc[2] += out[2] / 8.0;
            }
            hz[s * 3] = (float) acc[0];
            hz[s * 3 + 1] = (float) acc[1];
            hz[s * 3 + 2] = (float) acc[2];
        }
        // 0.12.0: sunlight at several heights. The sun is evaluated a disc radius higher (its upper limb), so a height
        // keeps a little light until the whole disc has set there, which also softens the Earth's shadow.
        float[] sl = new float[SUN_HEIGHTS.length * SUN_STEPS * 3];
        for (int hi = 0; hi < SUN_HEIGHTS.length; hi++) {
            double rh = RG + SUN_HEIGHTS[hi];
            for (int s = 0; s < SUN_STEPS; s++) {
                double muS = s / (double) (SUN_STEPS - 1) * 2.0 - 1.0;
                transmittance(rh, Math.min(1.0, muS + 0.0047), out);
                int at = (hi * SUN_STEPS + s) * 3;
                sl[at] = (float) out[0];
                sl[at + 1] = (float) out[1];
                sl[at + 2] = (float) out[2];
            }
        }
        tables = new Tables(z, hz, sl);
    }

    /**
     * The colour of sunlight at a height (0.12.0): the sun's transmittance through the atmosphere to {@code heightKm}
     * for the sun's cosine-zenith, relative to the noon sun at that height, with part of the brightness lost near the
     * horizon given back (the eye adapts; the colour keeps its red). 0 once the Earth's shadow covers that height.
     * Returns false before the tables are built. Any thread.
     */
    public static boolean sunlight(double heightKm, float muS, float[] out) {
        Tables built = tables;
        if (built == null) {
            return false;
        }
        float[] table = built.sunlight();
        double hk = Math.max(SUN_HEIGHTS[0], Math.min(SUN_HEIGHTS[SUN_HEIGHTS.length - 1], heightKm));
        int h0 = 0;
        while (h0 < SUN_HEIGHTS.length - 2 && SUN_HEIGHTS[h0 + 1] < hk) {
            h0++;
        }
        float fh = (float) ((hk - SUN_HEIGHTS[h0]) / (SUN_HEIGHTS[h0 + 1] - SUN_HEIGHTS[h0]));
        float x = (Math.max(-1f, Math.min(1f, muS)) * 0.5f + 0.5f) * (SUN_STEPS - 1);
        int s0 = Math.min((int) x, SUN_STEPS - 2);
        float fs = x - s0;
        float lum = 0f;
        float noon = 0f;
        for (int c = 0; c < 3; c++) {
            float a = lerp(table[(h0 * SUN_STEPS + s0) * 3 + c], table[(h0 * SUN_STEPS + s0 + 1) * 3 + c], fs);
            float b = lerp(table[((h0 + 1) * SUN_STEPS + s0) * 3 + c], table[((h0 + 1) * SUN_STEPS + s0 + 1) * 3 + c], fs);
            float n0 = table[(h0 * SUN_STEPS + SUN_STEPS - 1) * 3 + c];
            float n1 = table[((h0 + 1) * SUN_STEPS + SUN_STEPS - 1) * 3 + c];
            float noonC = Math.max(lerp(n0, n1, fh), 1e-4f);
            out[c] = lerp(a, b, fh) / noonC;
        }
        lum = 0.2126f * out[0] + 0.7152f * out[1] + 0.0722f * out[2];
        if (lum > 1e-5f) {
            float lift = (float) Math.pow(Math.min(1f, lum), -0.55);
            out[0] *= lift;
            out[1] *= lift;
            out[2] *= lift;
        }
        noon = Math.max(out[0], Math.max(out[1], out[2]));
        if (noon > 1.4f) {
            float k = 1.4f / noon;
            out[0] *= k;
            out[1] *= k;
            out[2] *= k;
        }
        return true;
    }

    public static boolean ready() {
        return tables != null;
    }

    /** Linear in-scattered light at the zenith (out[0..2]) for the sun's cosine-zenith. */
    public static void zenith(float muS, float[] out) {
        Tables built = tables;
        lookup(built == null ? null : built.zenith(), muS, out);
    }

    /** Linear in-scattered light just above the horizon, averaged around it. */
    public static void horizon(float muS, float[] out) {
        Tables built = tables;
        lookup(built == null ? null : built.horizon(), muS, out);
    }

    private static void lookup(float[] table, float muS, float[] out) {
        if (table == null) {
            out[0] = out[1] = out[2] = 0f;
            return;
        }
        float x = (Math.max(-1f, Math.min(1f, muS)) * 0.5f + 0.5f) * (SUN_STEPS - 1);
        int i = Math.min((int) x, SUN_STEPS - 2);
        float f = x - i;
        for (int c = 0; c < 3; c++) {
            out[c] = table[i * 3 + c] * (1f - f) + table[(i + 1) * 3 + c] * f;
        }
    }

    // --- the model (mirrors SkyGlsl.ATMOSPHERE) ---

    private static double raySphere(double r, double mu, double rad) {
        double disc = r * r * (mu * mu - 1.0) + rad * rad;
        if (disc < 0.0) {
            return -1.0;
        }
        double s = Math.sqrt(disc);
        double t0 = -r * mu - s;
        double t1 = -r * mu + s;
        return t0 > 0.0 ? t0 : (t1 > 0.0 ? t1 : -1.0);
    }

    private static boolean hitsGround(double r, double mu) {
        return mu < 0.0 && r * r * (mu * mu - 1.0) + RG * RG >= 0.0;
    }

    /** Scattering (rayleigh rgb, mie) and extinction rgb at height h: out[0..2] rayleigh, [3] mie, [4..6] extinction. */
    private static void medium(double h, double[] m) {
        double dr = Math.exp(-h / RAYLEIGH_H);
        double dm = Math.exp(-h / MIE_H);
        double ozone = Math.max(0.0, 1.0 - Math.abs(h - 25.0) / 15.0);
        for (int c = 0; c < 3; c++) {
            m[c] = RAYLEIGH[c] * dr;
            m[4 + c] = m[c] + MIE_EXTINCT * dm + OZONE[c] * ozone;
        }
        m[3] = MIE_SCATTER * dm;
    }

    /** Transmittance from radius r along mu to the top of the atmosphere (0 into the ground), by direct integration. */
    private static void transmittance(double r, double mu, double[] out) {
        if (hitsGround(r, mu)) {
            out[0] = out[1] = out[2] = 0.0;
            return;
        }
        double dist = Math.max(raySphere(r, mu, RT), 0.0);
        double dt = dist / 24.0;
        double[] m = new double[7];
        double d0 = 0.0;
        double d1 = 0.0;
        double d2 = 0.0;
        for (int i = 0; i < 24; i++) {
            double t = (i + 0.5) * dt;
            double rr = Math.sqrt(r * r + t * t + 2.0 * r * mu * t);
            medium(rr - RG, m);
            d0 += m[4] * dt;
            d1 += m[5] * dt;
            d2 += m[6] * dt;
        }
        out[0] = Math.exp(-d0);
        out[1] = Math.exp(-d1);
        out[2] = Math.exp(-d2);
    }

    private static void multiScatter(double r, double muS, double[] out) {
        double sx = Math.sqrt(Math.max(0.0, 1.0 - muS * muS));
        double[] lum = new double[3];
        double[] fms = new double[3];
        double[] m = new double[7];
        double[] sunT = new double[3];
        for (int k = 0; k < 16; k++) {
            double z = 1.0 - (k + 0.5) / 8.0;
            double a = k * 2.39996323;
            double s = Math.sqrt(Math.max(0.0, 1.0 - z * z));
            double dx = s * Math.cos(a);
            double dy = z;
            double mu = dy;
            boolean ground = hitsGround(r, mu);
            double tMax = ground ? raySphere(r, mu, RG) : raySphere(r, mu, RT);
            double dt = Math.max(tMax, 0.0) / 16.0;
            double[] trans = {1.0, 1.0, 1.0};
            for (int i = 0; i < 16; i++) {
                double t = (i + 0.5) * dt;
                double px = dx * t;
                double py = r + dy * t;
                double pz = s * Math.sin(a) * t;
                double rr = Math.sqrt(px * px + py * py + pz * pz);
                medium(rr - RG, m);
                transmittance(rr, (px * sx + py * muS) / rr, sunT);
                for (int c = 0; c < 3; c++) {
                    double scat = m[c] + m[3];
                    double ext = Math.max(m[4 + c], 1e-6);
                    double stepT = Math.exp(-m[4 + c] * dt);
                    double s1 = scat * sunT[c] / (4.0 * Math.PI);
                    lum[c] += trans[c] * (s1 - s1 * stepT) / ext / 16.0;
                    fms[c] += trans[c] * (scat - scat * stepT) / ext / 16.0;
                    trans[c] *= stepT;
                }
            }
            if (ground) {
                double gx = dx * tMax;
                double gy = r + dy * tMax;
                double gz = s * Math.sin(a) * tMax;
                double gl = Math.sqrt(gx * gx + gy * gy + gz * gz);
                double muG = Math.max((gx * sx + gy * muS) / gl, 0.0);
                transmittance(RG, muG, sunT);
                for (int c = 0; c < 3; c++) {
                    lum[c] += trans[c] * sunT[c] * muG * GROUND_ALBEDO / Math.PI / 16.0;
                }
            }
        }
        for (int c = 0; c < 3; c++) {
            out[c] = lum[c] / Math.max(1.0 - fms[c], 1e-3);
        }
    }

    private static void msLookup(double r, double muS, double[] out) {
        double u = (muS * 0.5 + 0.5) * (MS_SUN - 1);
        double v = Math.max(0.0, Math.min(1.0, (r - RG) / (RT - RG))) * (MS_HEIGHT - 1);
        int i = Math.max(0, Math.min((int) u, MS_SUN - 2));
        int j = Math.max(0, Math.min((int) v, MS_HEIGHT - 2));
        double fu = Math.max(0.0, Math.min(1.0, u - i));
        double fv = Math.max(0.0, Math.min(1.0, v - j));
        for (int c = 0; c < 3; c++) {
            double a = ms[(j * MS_SUN + i) * 3 + c] * (1 - fu) + ms[(j * MS_SUN + i + 1) * 3 + c] * fu;
            double b = ms[((j + 1) * MS_SUN + i) * 3 + c] * (1 - fu) + ms[((j + 1) * MS_SUN + i + 1) * 3 + c] * fu;
            out[c] = a * (1 - fv) + b * fv;
        }
    }

    static double rayleighPhase(double c) {
        return 3.0 / (16.0 * Math.PI) * (1.0 + c * c);
    }

    static double miePhase(double c) {
        double g = 0.8;
        double g2 = g * g;
        return 3.0 / (8.0 * Math.PI) * (1.0 - g2) * (1.0 + c * c)
                / ((2.0 + g2) * Math.pow(Math.max(1.0 + g2 - 2.0 * g * c, 1e-4), 1.5));
    }

    /** In-scattered light from radius r along (azimuth from the sun, sine of elevation); mirrors the sky-view LUT. */
    private static void inScatter(double r, double muS, double azimuth, double sinElev, double[] out) {
        double cosElev = Math.sqrt(Math.max(0.0, 1.0 - sinElev * sinElev));
        double dx = cosElev * Math.cos(azimuth);
        double dy = sinElev;
        double dz = cosElev * Math.sin(azimuth);
        double sx = Math.sqrt(Math.max(0.0, 1.0 - muS * muS));
        double mu = dy;
        boolean ground = hitsGround(r, mu);
        double tMax = ground ? raySphere(r, mu, RG) : raySphere(r, mu, RT);
        double c = dx * sx + dy * muS;
        double pr = rayleighPhase(c);
        double pm = miePhase(c);
        double[] m = new double[7];
        double[] sunT = new double[3];
        double[] msv = new double[3];
        double[] trans = {1.0, 1.0, 1.0};
        out[0] = out[1] = out[2] = 0.0;
        for (int i = 0; i < 24; i++) {
            double t0 = i / 24.0;
            double t1 = (i + 1) / 24.0;
            double ta = t0 * t0 * tMax;
            double tb = t1 * t1 * tMax;
            double t = 0.5 * (ta + tb);
            double step = tb - ta;
            double px = dx * t;
            double py = r + dy * t;
            double pz = dz * t;
            double rr = Math.sqrt(px * px + py * py + pz * pz);
            medium(rr - RG, m);
            double muSun = (px * sx + py * muS) / rr;
            transmittance(rr, muSun, sunT);
            msLookup(rr, muSun, msv);
            for (int k = 0; k < 3; k++) {
                double s = (m[k] * pr + m[3] * pm) * sunT[k] + (m[k] + m[3]) * msv[k];
                double ext = Math.max(m[4 + k], 1e-6);
                double stepT = Math.exp(-m[4 + k] * step);
                out[k] += trans[k] * (s - s * stepT) / ext;
                trans[k] *= stepT;
            }
        }
    }
}
