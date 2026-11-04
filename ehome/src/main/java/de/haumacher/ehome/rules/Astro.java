/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.rules;

/**
 * The sun events of a day at a place, after the NOAA solar calculator (Meeus' low-precision solar
 * coordinates, refined at the event time): a port of homecontrol <code>core/src/astro.cpp</code>,
 * so that the controller and E-Home agree to the second on the events (both are within about a
 * minute of the US Naval Observatory between the polar circles).
 */
public final class Astro {

	/**
	 * The sun events. Dawn and dusk are the begin and end of civil twilight (the sun's centre 6
	 * degrees below the horizon); sunrise and sunset the moments the upper limb touches the horizon.
	 */
	public enum SunEvent {
		/** Civil twilight begins. */
		DAWN("dawn"),
		/** The sun rises: its upper limb appears on the horizon. */
		SUNRISE("sunrise"),
		/** The sun sets: its upper limb disappears below the horizon. */
		SUNSET("sunset"),
		/** Civil twilight ends. */
		DUSK("dusk");

		private final String _name;

		SunEvent(String name) {
			_name = name;
		}

		/** The name in the image. */
		public String externalName() {
			return _name;
		}

		/** The event with the name, <code>null</code> if none. */
		public static SunEvent fromName(String name) {
			for (SunEvent event : values()) {
				if (event._name.equals(name)) {
					return event;
				}
			}
			return null;
		}
	}

	/**
	 * A place: latitude and longitude in degrees (north and east positive), elevation in metres
	 * above the surroundings' horizon.
	 */
	public record GeoLocation(double latitude, double longitude, double elevation) {

		/** Whether the coordinates are in range. */
		public boolean valid() {
			return Double.isFinite(latitude) && latitude >= -90 && latitude <= 90 && Double.isFinite(longitude)
				&& longitude >= -180 && longitude <= 180 && Double.isFinite(elevation) && elevation >= -500
				&& elevation <= 9000;
		}
	}

	/**
	 * The sun's position at an instant: azimuth in degrees from north through east (0..360),
	 * elevation in degrees above the horizon (-90..90, geometric: no refraction).
	 */
	public record SunPosition(double azimuth, double elevation) {
		// A pair.
	}

	private Astro() {
		// Static helpers.
	}

	/**
	 * The sun's position at the UTC instant (seconds since the epoch) seen from the place (its
	 * elevation is ignored): the NOAA solar calculator evaluated at the instant, on the exact
	 * trigonometry of {@link ExactMath}, written exactly as homecontrol <code>spec/blocks.md</code>
	 * 13.2 (and its <code>core/src/sun_position.cpp</code>), so that the simulation and the controller
	 * compute the same bits. Within 0.02 degrees of the true position between 1900 and 2100.
	 */
	public static SunPosition sunPosition(long utc, GeoLocation where) {
		final double deg = 180.0 / 3.141592653589793;
		long u = utc;
		double d = (double) (u - 946728000L) / 86400.0;
		double t = d / 36525.0;
		double l0 = ExactMath.wrap360(280.46646 + t * (36000.76983 + t * 0.0003032));
		double m = 357.52911 + t * (35999.05029 - 0.0001537 * t);
		double e = 0.016708634 - t * (0.000042037 + 0.0000001267 * t);
		double sinM = ExactMath.sinDeg(m);
		double sin2M = ExactMath.sinDeg(2.0 * m);
		double c = sinM * (1.914602 - t * (0.004817 + 0.000014 * t)) + sin2M * (0.019993 - 0.000101 * t)
			+ ExactMath.sinDeg(3.0 * m) * 0.000289;
		double om = 125.04 - 1934.136 * t;
		double lam = l0 + c - 0.00569 - 0.00478 * ExactMath.sinDeg(om);
		double eps = 23.0 + (26.0 + (21.448 - t * (46.815 + t * (0.00059 - t * 0.001813))) / 60.0) / 60.0
			+ 0.00256 * ExactMath.cosDeg(om);
		double dec = ExactMath.asinDeg(ExactMath.sinDeg(eps) * ExactMath.sinDeg(lam));
		double y = ExactMath.sinDeg(eps / 2.0) / ExactMath.cosDeg(eps / 2.0);
		y = y * y;
		double sin2L0 = ExactMath.sinDeg(2.0 * l0);
		double cos2L0 = ExactMath.cosDeg(2.0 * l0);
		double eqt = 4.0 * (y * sin2L0 - 2.0 * e * sinM + 4.0 * e * y * sinM * cos2L0
			- 0.5 * y * y * ExactMath.sinDeg(4.0 * l0) - 1.25 * e * e * sin2M) * deg;
		long s = u - 86400L * Math.floorDiv(u, 86400L);
		double h = ((double) s / 60.0 + eqt + 4.0 * where.longitude()) / 4.0 - 180.0;
		double sinLat = ExactMath.sinDeg(where.latitude());
		double cosLat = ExactMath.cosDeg(where.latitude());
		double sinDec = ExactMath.sinDeg(dec);
		double cosDec = ExactMath.cosDeg(dec);
		double cosH = ExactMath.cosDeg(h);
		double up = sinLat * sinDec + cosLat * cosDec * cosH;
		double north = cosLat * sinDec - sinLat * cosDec * cosH;
		double east = -(cosDec * ExactMath.sinDeg(h));
		return new SunPosition(ExactMath.wrap360(ExactMath.atan2Deg(east, north)),
			ExactMath.atan2Deg(up, Math.sqrt(north * north + east * east)));
	}

	private static double rad(double degrees) {
		return degrees * Math.PI / 180.0;
	}

	private static double deg(double radians) {
		return radians * 180.0 / Math.PI;
	}

	/** Declination (degrees) and equation of time (minutes) at the Julian day <code>jd</code>. */
	private static double[] solarPosition(double jd) {
		double t = (jd - 2451545.0) / 36525.0;
		double l0 = (280.46646 + t * (36000.76983 + t * 0.0003032)) % 360.0;
		if (l0 < 0) {
			l0 += 360.0;
		}
		double m = 357.52911 + t * (35999.05029 - 0.0001537 * t);
		double e = 0.016708634 - t * (0.000042037 + 0.0000001267 * t);
		double mr = rad(m);
		double center = Math.sin(mr) * (1.914602 - t * (0.004817 + 0.000014 * t))
			+ Math.sin(2 * mr) * (0.019993 - 0.000101 * t) + Math.sin(3 * mr) * 0.000289;
		double trueLongitude = l0 + center;
		double omega = 125.04 - 1934.136 * t;
		double apparentLongitude = trueLongitude - 0.00569 - 0.00478 * Math.sin(rad(omega));
		double seconds = 21.448 - t * (46.815 + t * (0.00059 - t * 0.001813));
		double meanObliquity = 23.0 + (26.0 + seconds / 60.0) / 60.0;
		double obliquity = meanObliquity + 0.00256 * Math.cos(rad(omega));
		double declination = deg(Math.asin(Math.sin(rad(obliquity)) * Math.sin(rad(apparentLongitude))));
		double y = Math.tan(rad(obliquity) / 2);
		y *= y;
		double l0r = rad(l0);
		double eq = y * Math.sin(2 * l0r) - 2 * e * Math.sin(mr) + 4 * e * y * Math.sin(mr) * Math.cos(2 * l0r)
			- 0.5 * y * y * Math.sin(4 * l0r) - 1.25 * e * e * Math.sin(2 * mr);
		return new double[] { declination, 4 * deg(eq) };
	}

	/** The hour angle (degrees) at which the sun's centre reaches the zenith distance; NaN if never. */
	private static double hourAngle(double latitude, double declination, double zenith) {
		double lat = rad(latitude);
		double dec = rad(declination);
		double denominator = Math.cos(lat) * Math.cos(dec);
		if (Math.abs(denominator) < 1e-12) {
			return Double.NaN;
		}
		double c = Math.cos(rad(zenith)) / denominator - Math.tan(lat) * Math.tan(dec);
		if (c > 1.0 || c < -1.0) {
			return Double.NaN;
		}
		return deg(Math.acos(c));
	}

	/** Minutes after 0h UT of the date starting at <code>jd0</code>; NaN if the event does not occur. */
	private static double eventMinutes(double jd0, GeoLocation where, double zenith, boolean morning) {
		double estimate = 720.0 - 4.0 * where.longitude();
		for (int iteration = 0; iteration < 3; iteration++) {
			double[] position = solarPosition(jd0 + estimate / 1440.0);
			double ha = hourAngle(where.latitude(), position[0], zenith);
			if (Double.isNaN(ha)) {
				return Double.NaN;
			}
			double delta = morning ? ha : -ha;
			estimate = 720.0 - 4.0 * (where.longitude() + delta) - position[1];
		}
		return estimate;
	}

	/**
	 * The UTC instant (seconds since the epoch) of the event of the date at the place,
	 * <code>null</code> if it does not occur (polar day or night, a summer night too bright for civil
	 * dusk) or the place is invalid. The events of a date are those around its solar noon; they may
	 * fall on the neighbouring UTC date.
	 */
	public static Long sunTime(int year, int month, int day, GeoLocation where, SunEvent event) {
		if (!where.valid()) {
			return null;
		}
		long days = Values.daysOf(year, month, day);
		double jd0 = days + 2440587.5;
		double dip = where.elevation() > 0 ? 2.076 * Math.sqrt(where.elevation()) / 60.0 : 0.0;
		double zenith = event == SunEvent.DAWN || event == SunEvent.DUSK ? 96.0 : 90.833 + dip;
		boolean morning = event == SunEvent.DAWN || event == SunEvent.SUNRISE;
		double minutes = eventMinutes(jd0, where, zenith, morning);
		if (Double.isNaN(minutes)) {
			return null;
		}
		return Long.valueOf(days * 86400 + llround(minutes * 60.0));
	}

	/** <code>llround()</code> of C: half away from zero. */
	private static long llround(double x) {
		return (long) Values.round(x);
	}

}
