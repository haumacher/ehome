/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.rules;

/**
 * Trigonometry in degrees that gives the same bits on every platform (homecontrol
 * <code>spec/blocks.md</code> 13.1): built from <code>+</code>, <code>-</code>, <code>*</code>,
 * <code>/</code>, {@link Math#sqrt(double)} and {@link Math#floor(double)} alone, evaluated in the
 * order the specification writes, so that the simulation computes exactly what the controller
 * computes (homecontrol <code>core/src/exact_math.cpp</code>). {@link Math#sin(double)} and
 * {@link Math#atan2(double, double)} differ in the last bits from the controller's C library. Error
 * against the true functions: below 1e-13 (sine, cosine) and 1e-12 degrees (the arc functions).
 */
public final class ExactMath {

	private static final double PI = 3.141592653589793;

	private static final double RAD = PI / 180.0;

	private static final double DEG = 180.0 / PI;

	private static final double S3 = -1.0 / 6.0;

	private static final double S5 = 1.0 / 120.0;

	private static final double S7 = -1.0 / 5040.0;

	private static final double S9 = 1.0 / 362880.0;

	private static final double S11 = -1.0 / 39916800.0;

	private static final double S13 = 1.0 / 6227020800.0;

	private static final double C2 = -1.0 / 2.0;

	private static final double C4 = 1.0 / 24.0;

	private static final double C6 = -1.0 / 720.0;

	private static final double C8 = 1.0 / 40320.0;

	private static final double C10 = -1.0 / 3628800.0;

	private static final double C12 = 1.0 / 479001600.0;

	private static final double C14 = -1.0 / 87178291200.0;

	private static final double A3 = -1.0 / 3.0;

	private static final double A5 = 1.0 / 5.0;

	private static final double A7 = -1.0 / 7.0;

	private static final double A9 = 1.0 / 9.0;

	private static final double A11 = -1.0 / 11.0;

	private static final double A13 = 1.0 / 13.0;

	private static final double A15 = -1.0 / 15.0;

	private static final double A17 = 1.0 / 17.0;

	private ExactMath() {
		// Static helpers.
	}

	/** <code>x - 360 floor(x / 360)</code>: 0..360. */
	public static double wrap360(double x) {
		return x - 360.0 * Math.floor(x / 360.0);
	}

	/** The sine of the angle in degrees. */
	public static double sinDeg(double degrees) {
		return sinCos(degrees, true);
	}

	/** The cosine of the angle in degrees. */
	public static double cosDeg(double degrees) {
		return sinCos(degrees, false);
	}

	private static double sinCos(double degrees, boolean sine) {
		double r = wrap360(degrees);
		double k = Math.floor(r / 90.0 + 0.5);
		double z = (r - 90.0 * k) * RAD;
		double z2 = z * z;
		double s = z + z * z2 * (S3 + z2 * (S5 + z2 * (S7 + z2 * (S9 + z2 * (S11 + z2 * S13)))));
		double c = 1.0 + z2 * (C2 + z2 * (C4 + z2 * (C6 + z2 * (C8 + z2 * (C10 + z2 * (C12 + z2 * C14))))));
		switch ((int) k) {
			case 1:
				return sine ? c : -s;
			case 2:
				return sine ? -s : -c;
			case 3:
				return sine ? -c : s;
			default:
				return sine ? s : c;
		}
	}

	/** The angle of <code>(x, y)</code> in degrees, -180..180; 0 for (0, 0). */
	public static double atan2Deg(double y, double x) {
		if (x == 0 && y == 0) {
			return 0;
		}
		double ax = Math.abs(x);
		double ay = Math.abs(y);
		boolean swap = ay > ax;
		double t = swap ? ax / ay : ay / ax;
		t = t / (1.0 + Math.sqrt(1.0 + t * t));
		t = t / (1.0 + Math.sqrt(1.0 + t * t));
		double t2 = t * t;
		double a = t + t * t2 * (A3 + t2 * (A5 + t2 * (A7 + t2 * (A9 + t2 * (A11 + t2 * (A13 + t2 * (A15 + t2 * A17)))))));
		double d = 4.0 * a * DEG;
		if (swap) {
			d = 90.0 - d;
		}
		if (x < 0) {
			d = 180.0 - d;
		}
		if (y < 0) {
			d = -d;
		}
		return d;
	}

	/** The arc sine in degrees of <code>v</code> limited to -1..1. */
	public static double asinDeg(double v) {
		double w = min(max(v, -1.0), 1.0);
		return atan2Deg(w, Math.sqrt((1.0 - w) * (1.0 + w)));
	}

	/** C++'s <code>std::max</code>: <code>(a &lt; b) ? b : a</code>. */
	public static double max(double a, double b) {
		return a < b ? b : a;
	}

	/** C++'s <code>std::min</code>: <code>(b &lt; a) ? b : a</code>. */
	public static double min(double a, double b) {
		return b < a ? b : a;
	}

}
