package dev.thedocruby.resounding.material;

import org.junit.jupiter.api.Test;

/**
 * Explores phase-state formulas against representative materials at ambient ({@link MaterialResolver#AMBIENT_KELVIN}).
 *
 * <p>Run with {@code ./gradlew test --tests PhaseStateExperimentTest} and read stdout.
 * Nothing here asserts a winner — it prints side-by-side numbers for design discussion.
 */
class PhaseStateExperimentTest {

    private static final double T = MaterialResolver.AMBIENT_KELVIN;

    /** Semantic anchors from {@link Material} — stored 0..1, printed also on 0..3 scale. */
    private enum Phase {
        ABSENT(0.0),
        PLASMA(0.25),
        GAS(0.5),
        LIQUID(0.75),
        SOLID(1.0);

        final double onUnitInterval;

        Phase(double onUnitInterval) {
            this.onUnitInterval = onUnitInterval;
        }

        double onThreePointScale() {
            return onUnitInterval * 3.0;
        }
    }

  /** Sample substances: name, melt, boil (as authored in resounding.materials.json), density, lwave, swave. */
    private record Sample(String name, double melt, double boil, double density, double lwave, double swave) {}

    private static final Sample NITROGEN = new Sample("N (gas)", -210.1, -195.79, 1251, 317.5, 333.6);
    private static final Sample IRON = new Sample("Fe (solid)", 1538, 2861, 7874, 5900, 5900);
    private static final Sample WATER = new Sample("H2O (liquid)", 0, 100, 1000, 1480, 1500);
    private static final Sample OBSIDIAN = new Sample("obsidian-like", 1200, 1400, 2650, 3800, 4200);

    @FunctionalInterface
    private interface PhaseModel {
        /** Phase coordinate on [0, 1] semantic scale (see {@link Material}). */
        double state(double tempK, double melt, double boil);
    }

    @FunctionalInterface
    private interface VelocityModel {
        double velocity(double state, double lwave, double swave);
    }

    // --- current production formula ---
    private static final PhaseModel CURRENT = (temp, melt, boil) ->
            Acoustics.lerpProgress(temp, melt, boil);

    // --- unit fix: treat authored melt/boil as Celsius when they look like Celsius ---
    private static double toKelvinIfCelsius(double value) {
        // Values below ~200 are almost certainly °C in the shipped pack (N2 boils at -196°C, etc.)
        return value < 200.0 ? value + 273.15 : value;
    }

    private static final PhaseModel MELT_BOIL_AS_CELSIUS = (temp, melt, boil) ->
            Acoustics.lerpProgress(temp, toKelvinIfCelsius(melt), toKelvinIfCelsius(boil));

    /**
     * Piecewise band model: each phase occupies a quarter of [0,1].
     * Below melt → solid..hypersolid; melt..boil → liquid; boil..2×boil → gas; above → plasma.
     * Within each band, progress is unclamped lerpProgress (extrapolation allowed outside band center).
     */
    private static final PhaseModel PIECEWISE_BANDS = (temp, melt, boil) -> {
        double m = toKelvinIfCelsius(melt);
        double b = toKelvinIfCelsius(boil);
        if (temp <= 0) return Phase.ABSENT.onUnitInterval;
        if (temp < m) {
            // cold solid → hypersolid as T→0: state runs 1.0 .. 3.0 on the 0..3 scale (1.0 .. 2.0 on 0..1)
            double cold = Acoustics.lerpProgress(temp, 0, m); // 0 at 0K, 1 at melt
            return Acoustics.lerp(cold, 2.0 / 3.0, 1.0); // maps to [2.0, 3.0] on 0..3 scale → [0.667, 1.0]
        }
        if (temp < b) {
            double liq = Acoustics.lerpProgress(temp, m, b);
            return Acoustics.lerp(liq, Phase.SOLID.onUnitInterval, Phase.LIQUID.onUnitInterval);
        }
        if (temp < b * 2) {
            double gas = Acoustics.lerpProgress(temp, b, b * 2);
            return Acoustics.lerp(gas, Phase.LIQUID.onUnitInterval, Phase.GAS.onUnitInterval);
        }
        double plasma = Acoustics.lerpProgress(temp, b * 2, b * 4);
        return Acoustics.lerp(plasma, Phase.GAS.onUnitInterval, Phase.PLASMA.onUnitInterval);
    };

    /**
     * Logistic soft bands: smooth transitions between phase anchors without hard clamps.
     * Each logistic peaks near a transition temperature; weighted sum of anchor values.
     */
    private static final PhaseModel LOGISTIC_BLEND = (temp, melt, boil) -> {
        double m = toKelvinIfCelsius(melt);
        double b = toKelvinIfCelsius(boil);
        if (temp <= 0) return Phase.ABSENT.onUnitInterval;

        double wSolid = logistic(temp, m, -m * 0.15);      // dominant below melt
        double wLiquid = logistic(temp, (m + b) * 0.5, (b - m) * 0.2); // peak mid-range
        double wGas = logistic(temp, b * 1.1, b * 0.15);   // dominant above boil
        double wPlasma = logistic(temp, b * 3, b * 0.5);   // tail at very high T

        double num = wSolid * Phase.SOLID.onUnitInterval
                + wLiquid * Phase.LIQUID.onUnitInterval
                + wGas * Phase.GAS.onUnitInterval
                + wPlasma * Phase.PLASMA.onUnitInterval;
        double den = wSolid + wLiquid + wGas + wPlasma;
        return num / den;
    };

    private static double logistic(double x, double center, double width) {
        return 1.0 / (1.0 + Math.exp(-(x - center) / width));
    }

    /**
     * Inverted melt-boil progress for solids: remap so ambient solids sit at state=1 (swave endpoint).
     * Fluids still use melt..boil as 0.75..0.5 transition.
     */
    private static final PhaseModel INVERTED_SOLID_GAS = (temp, melt, boil) -> {
        double m = toKelvinIfCelsius(melt);
        double b = toKelvinIfCelsius(boil);
        if (temp < m) {
            // solid: deeper cold → higher state (toward hypersolid on 0..3 = 1.0+ on 0..1)
            double depth = Acoustics.lerpProgress(m, temp, m + 273.15); // 0 at melt, 1 when 273K below melt
            return Acoustics.lerp(depth, Phase.SOLID.onUnitInterval, 1.0 + 0.33); // up to ~1.33 (4.0 on 0..3)
        }
        if (temp < b) {
            double liq = Acoustics.lerpProgress(temp, m, b);
            return Acoustics.lerp(liq, Phase.SOLID.onUnitInterval, Phase.LIQUID.onUnitInterval);
        }
        double gas = Acoustics.lerpProgress(temp, b, b * 2);
        return Acoustics.lerp(gas, Phase.LIQUID.onUnitInterval, Phase.GAS.onUnitInterval);
    };

    // --- velocity mappings ---

    /** Production: state 0 → lwave, state 1 → swave (linear, unclamped). */
    private static final VelocityModel VEL_LINEAR = Acoustics::lerp;

    /**
     * Piecewise: snap lerp endpoints to phase anchors so state=0.5 always uses gas-speed interpolation
     * between lwave and swave, state=0.75 liquid, state=1.0 solid.
     */
    private static final VelocityModel VEL_PHASE_ANCHORED = (state, lwave, swave) -> {
        if (state >= Phase.SOLID.onUnitInterval) {
            double t = Acoustics.lerpProgress(state, Phase.SOLID.onUnitInterval, 1.0 + 0.33);
            return Acoustics.lerp(t, swave, swave * 1.15); // hypersolid: slightly stiffer
        }
        if (state >= Phase.LIQUID.onUnitInterval) {
            double t = Acoustics.lerpProgress(state, Phase.LIQUID.onUnitInterval, Phase.SOLID.onUnitInterval);
            return Acoustics.lerp(t, lwave, swave);
        }
        if (state >= Phase.GAS.onUnitInterval) {
            double t = Acoustics.lerpProgress(state, Phase.GAS.onUnitInterval, Phase.LIQUID.onUnitInterval);
            return Acoustics.lerp(t, lwave * 0.35, lwave); // gas: mostly low velocity
        }
        double t = Acoustics.lerpProgress(state, Phase.PLASMA.onUnitInterval, Phase.GAS.onUnitInterval);
        return Acoustics.lerp(t, lwave * 0.1, lwave * 0.35);
    };

  @Test
    void printPhaseStateComparison() {
        PhaseModel[] phaseModels = {
                CURRENT,
                MELT_BOIL_AS_CELSIUS,
                PIECEWISE_BANDS,
                LOGISTIC_BLEND,
                INVERTED_SOLID_GAS
        };
        String[] phaseNames = {
                "current (lerpProgress, K-as-authored)",
                "celsius melt/boil → K",
                "piecewise bands + hypersolid cold tail",
                "logistic soft blend",
                "inverted solid + celsius"
        };
        VelocityModel[] velModels = {VEL_LINEAR, VEL_PHASE_ANCHORED};
        String[] velNames = {"linear lerp(lwave,swave)", "phase-anchored piecewise"};

        Sample[] samples = {NITROGEN, IRON, WATER, OBSIDIAN};
        double granularity = 0.6;
        double solventImpedance = 400.0; // rough air-like

        System.out.println();
        System.out.println("=== Phase state experiment @ T=" + T + " K ===");
        System.out.println("Semantic scale (Material.java): 0=absent, 0.25=plasma, 0.5=gas, 0.75=liquid, 1=solid");
        System.out.println("Same anchors on 0..3 scale: multiply by 3 → 0, 0.75, 1.5, 2.25, 3");
        System.out.println();

        for (Sample s : samples) {
            System.out.println("--- " + s.name + "  melt=" + s.melt + " boil=" + s.boil
                    + "  ρ=" + s.density + "  lwave=" + s.lwave + " swave=" + s.swave + " ---");
            System.out.printf("%-42s %-28s %6s %6s %10s %12s %10s %s%n",
                    "phase model", "velocity model", "st[01]", "st[03]", "v (m/s)", "Z", "perm", "ok?");
            for (int pi = 0; pi < phaseModels.length; pi++) {
                double state = phaseModels[pi].state(T, s.melt, s.boil);
                for (int vi = 0; vi < velModels.length; vi++) {
                    double v = velModels[vi].velocity(state, s.lwave, s.swave);
                    double z = v * s.density;
                    double refl = (z + solventImpedance == 0) ? 0 : Acoustics.reflection(z, solventImpedance);
                    double perm = Math.pow(1.0 - refl, granularity * (1.0 + state));
                    boolean finite = Double.isFinite(state) && Double.isFinite(v)
                            && Double.isFinite(z) && Double.isFinite(perm);
                    System.out.printf("%-42s %-28s %6.3f %6.3f %10.1f %12.1f %10.4f %s%n",
                            phaseNames[pi], velNames[vi], state, state * 3, v, z, perm,
                            finite ? "yes" : "NO");
                }
            }
            System.out.println();
        }

        System.out.println("--- Phase trajectory for N2 (gas) as T rises: piecewise bands + phase-anchored velocity ---");
        System.out.printf("%8s %6s %6s %10s %12s%n", "T (K)", "st[01]", "st[03]", "v (m/s)", "Z");
        double mK = toKelvinIfCelsius(NITROGEN.melt);
        double bK = toKelvinIfCelsius(NITROGEN.boil);
        double[] temps = {50, mK, (mK + bK) * 0.5, bK, T, bK * 2, bK * 4, 2000};
        for (double temp : temps) {
            double st = PIECEWISE_BANDS.state(temp, NITROGEN.melt, NITROGEN.boil);
            double v = VEL_PHASE_ANCHORED.velocity(st, NITROGEN.lwave, NITROGEN.swave);
            System.out.printf("%8.1f %6.3f %6.3f %10.1f %12.1f%n", temp, st, st * 3, v, v * NITROGEN.density);
        }
        System.out.println();

        System.out.println("--- Phase trajectory for Fe (solid) as T rises: piecewise bands + phase-anchored velocity ---");
        System.out.printf("%8s %6s %6s %10s %12s%n", "T (K)", "st[01]", "st[03]", "v (m/s)", "Z");
        mK = toKelvinIfCelsius(IRON.melt);
        bK = toKelvinIfCelsius(IRON.boil);
        temps = new double[]{50, T, mK * 0.9, mK, (mK + bK) * 0.5, bK, bK * 2};
        for (double temp : temps) {
            double st = PIECEWISE_BANDS.state(temp, IRON.melt, IRON.boil);
            double v = VEL_PHASE_ANCHORED.velocity(st, IRON.lwave, IRON.swave);
            System.out.printf("%8.1f %6.3f %6.3f %10.1f %12.1f%n", temp, st, st * 3, v, v * IRON.density);
        }
        System.out.println();
    }
}
