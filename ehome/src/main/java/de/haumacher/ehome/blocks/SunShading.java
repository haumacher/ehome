/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.blocks;

import java.util.ArrayList;
import java.util.List;

import de.haumacher.ehome.blocks.BlockContext.InputValue;
import de.haumacher.ehome.blocks.Manifest.Aggregation;
import de.haumacher.ehome.blocks.Manifest.Choice;
import de.haumacher.ehome.blocks.Manifest.Field;
import de.haumacher.ehome.blocks.Manifest.InputTrigger;
import de.haumacher.ehome.blocks.Manifest.Param;
import de.haumacher.ehome.blocks.Manifest.ParamType;
import de.haumacher.ehome.blocks.Manifest.Port;
import de.haumacher.ehome.blocks.Manifest.PortType;
import de.haumacher.ehome.rules.Astro.SunPosition;
import de.haumacher.ehome.rules.DatapointModel.Quality;
import de.haumacher.ehome.rules.ExactMath;
import de.haumacher.ehome.rules.Val.DateTime;
import de.haumacher.ehome.rules.Values;

/**
 * The sun shading <code>org.homecontrol.sun-shading</code> 1.0 (homecontrol
 * <code>spec/blocks.md</code> 18): one facade's blind lowered while the sun is on the facade and it is
 * bright, the slats at the cut-off angle, heat protection while nobody is at home, retracted for wind,
 * rain and frost, suspended after an operation; moves on changes only, no more often than a minimum
 * interval. A literal port of homecontrol <code>core/src/blocks/sun_shading.cpp</code>: every formula
 * in IEEE 754 doubles in the order the specification writes it, on the exact trigonometry of
 * {@link ExactMath} (<code>spec/blocks.md</code> 13).
 */
public final class SunShading extends BlockType {

	/** The type id. */
	public static final String TYPE = "org.homecontrol.sun-shading";

	/** The one instance. */
	public static final SunShading INSTANCE = new SunShading();

	// Ports, in manifest order.

	/** Port <code>brightness</code>. */
	public static final int BRIGHTNESS = 0;

	/** Port <code>wind</code>. */
	public static final int WIND = 1;

	/** Port <code>rain</code>. */
	public static final int RAIN = 2;

	/** Port <code>frost</code>. */
	public static final int FROST = 3;

	/** Port <code>outside</code>. */
	public static final int OUTSIDE = 4;

	/** Port <code>manual</code>. */
	public static final int MANUAL = 5;

	/** Port <code>presence</code>. */
	public static final int PRESENCE = 6;

	/** Port <code>summer</code>. */
	public static final int SUMMER = 7;

	/** Port <code>roomTemperature</code>. */
	public static final int ROOM_TEMPERATURE = 8;

	/** Port <code>enable</code>. */
	public static final int ENABLE = 9;

	/** Port <code>position</code>. */
	public static final int POSITION = 10;

	/** Port <code>slat</code>. */
	public static final int SLAT = 11;

	/** Port <code>upDown</code>. */
	public static final int UP_DOWN = 12;

	/** Port <code>state</code>. */
	public static final int STATE = 13;

	/** Port <code>sunAzimuth</code>. */
	public static final int SUN_AZIMUTH = 14;

	/** Port <code>sunElevation</code>. */
	public static final int SUN_ELEVATION = 15;

	// Parameters, in manifest order.

	private static final int FACADE_AZIMUTH = 0;

	private static final int OPENING_ANGLE = 1;

	private static final int MIN_ELEVATION = 2;

	private static final int OVERHANG_ANGLE = 3;

	private static final int HORIZON_POINTS = 4;

	/** The parameter <code>horizonAngle1</code>; <code>horizonAngle&lt;i+1&gt;</code> at HORIZON_ANGLE1 + 2i, its elevation after it. */
	private static final int HORIZON_ANGLE1 = 5;

	private static final int BRIGHTNESS_ON = 13;

	private static final int BRIGHTNESS_OFF = 14;

	private static final int BRIGHTNESS_ON_DELAY = 15;

	private static final int BRIGHTNESS_OFF_DELAY = 16;

	private static final int SHADE_POSITION = 17;

	private static final int SLAT_MODE = 18;

	private static final int SHADE_SLAT = 19;

	private static final int SLAT_RATIO = 20;

	private static final int SLAT_OPEN = 21;

	private static final int SLAT_STEP = 22;

	private static final int HEAT_LIMIT = 23;

	private static final int WIND_LIMIT = 24;

	private static final int WIND_TIMEOUT = 25;

	private static final int FROST_LIMIT = 26;

	private static final int ALARM_RELEASE = 27;

	private static final int MANUAL_LOCK = 28;

	private static final int MANUAL_DURATION = 29;

	private static final int MIN_MOVE_INTERVAL = 30;

	// State fields, in manifest order.

	private static final int S_LAST_STATE = 0;

	private static final int S_WIND_ON = 1;

	private static final int S_WIND_CALM_AT = 2;

	private static final int S_RAIN_ON = 3;

	private static final int S_RAIN_DRY_AT = 4;

	private static final int S_FROST_ON = 5;

	private static final int S_FROST_CLEAR_AT = 6;

	/** State field <code>bright</code>. */
	public static final int S_BRIGHT = 7;

	private static final int S_BRIGHT_AT = 8;

	private static final int S_HEAT = 9;

	/** State field <code>manualAt</code>. */
	public static final int S_MANUAL_AT = 10;

	private static final int S_MANUAL_SUNNY = 11;

	private static final int S_MANUAL_DAY = 12;

	private static final int S_MANUAL_SEEN = 13;

	private static final int S_HOLDING = 14;

	private static final int S_DUE = 15;

	private static final int S_MOVED_AT = 16;

	private static final int S_SENT_POSITION = 17;

	private static final int S_SENT_SLAT = 18;

	private static final int S_SENT_UP_DOWN = 19;

	// The values of the output state.

	/** State idle. */
	public static final int IDLE = 0;

	/** State shading. */
	public static final int SHADING = 1;

	private static final int STATE_WIND = 2;

	private static final int STATE_RAIN = 3;

	private static final int STATE_FROST = 4;

	/** State manual. */
	public static final int STATE_MANUAL = 5;

	/** State heat protection. */
	public static final int HEAT_PROTECTION = 6;

	private static final int OFF = 7;

	// The choices.

	private static final int TRACKING = 1;

	private static final int SUN_CHANGE = 1;

	private static final int MIDNIGHT = 2;

	private static final double MINUTE = 60000;

	private static final double HOUR = 60 * MINUTE;

	private static final Manifest MANIFEST = new Manifest(TYPE, 1, 0, "Sun shading",
		"Shading of a facade by the sun's position and the brightness, with slat tracking, heat protection, wind, "
			+ "rain and frost alarms and a lock after manual operation",
		List.of(
			Port.in("brightness", PortType.REAL, "Brightness", "lx", false, 4, List.of(Aggregation.MAX, Aggregation.MEAN),
				InputTrigger.CHANGE),
			Port.in("wind", PortType.REAL, "Wind speed", "m/s", false, 4, List.of(Aggregation.MAX), InputTrigger.UPDATE),
			Port.in("rain", PortType.BOOLEAN, "Rain", "", false, 4, List.of(Aggregation.ANY), InputTrigger.UPDATE),
			Port.in("frost", PortType.BOOLEAN, "Frost alarm", "", false, 1, List.of(), InputTrigger.CHANGE),
			Port.in("outside", PortType.REAL, "Outside temperature", "°C", false, 1, List.of(), InputTrigger.CHANGE),
			Port.in("manual", PortType.BOOLEAN, "Manual operation", "", false, 8, List.of(Aggregation.ANY),
				InputTrigger.UPDATE),
			Port.in("presence", PortType.BOOLEAN, "Presence", "", false, 1, List.of(), InputTrigger.CHANGE),
			Port.in("summer", PortType.BOOLEAN, "Summer", "", false, 1, List.of(), InputTrigger.CHANGE),
			Port.in("roomTemperature", PortType.REAL, "Room temperature", "°C", false, 4,
				List.of(Aggregation.MAX, Aggregation.MEAN), InputTrigger.CHANGE),
			Port.in("enable", PortType.BOOLEAN, "Automatic on", "", false, 1, List.of(), InputTrigger.CHANGE),
			Port.out("position", PortType.REAL, "Position", "%", 0, Double.valueOf(0), Double.valueOf(100)),
			Port.out("slat", PortType.REAL, "Slat position", "%", 0, Double.valueOf(0), Double.valueOf(100)),
			Port.out("upDown", PortType.BOOLEAN, "Up/down", "", 0, null, null),
			Port.diagnostic("state", PortType.ENUMERATION, "State", "", Double.valueOf(0), Double.valueOf(7)),
			Port.diagnostic("sunAzimuth", PortType.REAL, "Sun azimuth", "°", null, null),
			Port.diagnostic("sunElevation", PortType.REAL, "Sun elevation", "°", null, null)),
		params(),
		List.of(new Field("lastState", false, -1), new Field("windOn", false, 0), new Field("windCalmAt", false, -1),
			new Field("rainOn", false, 0), new Field("rainDryAt", false, -1), new Field("frostOn", false, 0),
			new Field("frostClearAt", false, -1), new Field("bright", false, 0), new Field("brightAt", false, -1),
			new Field("heat", false, 0), new Field("manualAt", false, -1), new Field("manualSunny", false, 0),
			new Field("manualDay", false, -1), new Field("manualSeen", false, -1), new Field("holding", false, 0),
			new Field("due", false, 0), new Field("movedAt", false, -1), new Field("sentPosition", false, -1),
			new Field("sentSlat", false, -1), new Field("sentUpDown", false, -1)),
		List.of("wake"), 60000, 0);

	private static List<Param> params() {
		List<Param> result = new ArrayList<>();
		result.add(real("facadeAzimuth", "Facade direction", "°", 180, 0, 360, 1, false));
		result.add(real("openingAngle", "Opening angle", "°", 180, 10, 180, 1, false));
		result.add(real("minElevation", "Lowest sun elevation", "°", 5, 0, 90, 1, false));
		result.add(real("overhangAngle", "Overhang angle", "°", 90, 10, 90, 1, false));
		result.add(new Param("horizonPoints", ParamType.INTEGER, "Horizon points", "", 0, Double.valueOf(0),
			Double.valueOf(4), 1, List.of(), false));
		double[] angles = { -60, -20, 20, 60 };
		for (int i = 0; i < 4; i++) {
			int n = i + 1;
			result.add(real("horizonAngle" + n, "Horizon direction " + n, "°", angles[i], -90, 90, 1, false));
			result.add(real("horizonElevation" + n, "Horizon elevation " + n, "°", 0, 0, 90, 1, false));
		}
		result.add(real("brightnessOn", "Bright from", "lx", 30000, 0, 200000, 1000, false));
		result.add(real("brightnessOff", "Dark below", "lx", 20000, 0, 200000, 1000, false));
		result.add(duration("brightnessOnDelay", "Bright delay", 2 * MINUTE, 0, HOUR));
		result.add(duration("brightnessOffDelay", "Dark delay", 15 * MINUTE, 0, 2 * HOUR));
		result.add(real("shadePosition", "Shading position", "%", 100, 0, 100, 5, true));
		result.add(new Param("slatMode", ParamType.ENUMERATION, "Slats while shading", "", 0, null, null, 0,
			List.of(new Choice(0, "fixed", "Fixed"), new Choice(1, "tracking", "Following the sun")), false));
		result.add(real("shadeSlat", "Slat position when shading", "%", 75, 0, 100, 5, false));
		result.add(real("slatRatio", "Slat spacing to width", "", 0.9, 0.2, 1, 0.01, false));
		result.add(real("slatOpen", "Slat position when horizontal", "%", 0, 0, 90, 5, false));
		result.add(real("slatStep", "Slat step", "%", 10, 1, 50, 1, false));
		result.add(real("heatLimit", "Heat protection above", "°C", 26, 18, 40, 0.5, true));
		result.add(real("windLimit", "Wind alarm from", "m/s", 10, 1, 40, 0.5, false));
		result.add(duration("windTimeout", "Wind sensor timeout", 0, 0, 24 * HOUR));
		result.add(real("frostLimit", "Frost alarm below", "°C", 2, -20, 10, 0.5, false));
		result.add(duration("alarmRelease", "Alarm release delay", 10 * MINUTE, 0, 2 * HOUR));
		result.add(new Param("manualLock", ParamType.ENUMERATION, "After an operation", "", 0, null, null, 0,
			List.of(new Choice(0, "duration", "For a duration"), new Choice(1, "sunChange", "Until the sun changes"),
				new Choice(2, "midnight", "Until midnight")),
			false));
		result.add(duration("manualDuration", "Suspension after an operation", 2 * HOUR, MINUTE, 24 * HOUR));
		result.add(duration("minMoveInterval", "Shortest time between moves", 5 * MINUTE, 0, HOUR));
		return List.copyOf(result);
	}

	private static Param real(String id, String label, String unit, double def, double min, double max, double step,
			boolean adjustable) {
		return new Param(id, ParamType.REAL, label, unit, def, Double.valueOf(min), Double.valueOf(max), step,
			List.of(), adjustable);
	}

	private static Param duration(String id, String label, double def, double min, double max) {
		return new Param(id, ParamType.DURATION, label, "min", def, Double.valueOf(min), Double.valueOf(max), MINUTE,
			List.of(), false);
	}

	private SunShading() {
		// The singleton.
	}

	@Override
	public Manifest manifest() {
		return MANIFEST;
	}

	private static int angleOf(int i) {
		return HORIZON_ANGLE1 + 2 * i;
	}

	private static int elevationOf(int i) {
		return HORIZON_ANGLE1 + 1 + 2 * i;
	}

	@Override
	public Blame check(double[] params) {
		if (!(params[BRIGHTNESS_OFF] < params[BRIGHTNESS_ON])) {
			return new Blame("brightnessOff must be below brightnessOn",
				I18NConstants.BLOCK_PARAM_BELOW__LOW_HIGH.fill("brightnessOff", "brightnessOn"),
				BRIGHTNESS_OFF);
		}
		int n = (int) params[HORIZON_POINTS];
		for (int i = 0; i + 1 < n; i++) {
			if (!(params[angleOf(i + 1)] > params[angleOf(i)])) {
				return new Blame("the horizon points' directions must rise", I18NConstants.BLOCK_HORIZON_RISE, angleOf(i + 1));
			}
		}
		return null;
	}

	/** The horizon's elevation at <code>g</code> degrees from the facade's normal (18.4). */
	private static double horizon(double[] p, double g) {
		int n = (int) p[HORIZON_POINTS];
		if (g <= p[angleOf(0)]) {
			return p[elevationOf(0)];
		}
		if (g >= p[angleOf(n - 1)]) {
			return p[elevationOf(n - 1)];
		}
		for (int i = 0; i + 1 < n; i++) {
			double x0 = p[angleOf(i)];
			double x1 = p[angleOf(i + 1)];
			if (g <= x1) {
				double y0 = p[elevationOf(i)];
				double y1 = p[elevationOf(i + 1)];
				return y0 + (y1 - y0) * ((g - x0) / (x1 - x0));
			}
		}
		return p[elevationOf(n - 1)];
	}

	/** The slat position of <code>tracking</code> at the profile angle (18.4). */
	private static double trackedSlat(double[] p, double profile) {
		double beta = ExactMath.asinDeg(p[SLAT_RATIO] * ExactMath.cosDeg(profile)) - profile;
		beta = ExactMath.min(ExactMath.max(beta, 0.0), 90.0);
		double open = p[SLAT_OPEN];
		double q = open + (100.0 - open) * (beta / 90.0);
		double step = p[SLAT_STEP];
		return ExactMath.min(open + step * -Math.floor(-((q - open) / step)), 100.0);
	}

	private static boolean isAlarm(double state) {
		return state == STATE_WIND || state == STATE_RAIN || state == STATE_FROST;
	}

	private static boolean isActive(double state) {
		return state == SHADING || state == HEAT_PROTECTION;
	}

	/** The alarm of 18.4: on at once, off when the condition has not held for <code>release</code>. */
	private static void alarm(double[] s, int on, int at, boolean cond, double t, double release) {
		if (cond) {
			s[on] = 1;
			s[at] = -1;
		} else if (s[on] == 1) {
			if (s[at] < 0) {
				s[at] = t;
			}
			if (t - s[at] >= release) {
				s[on] = 0;
				s[at] = -1;
			}
		}
	}

	/** The earlier of a deadline so far (-1: none) and <code>at</code>. */
	private static double earliest(double wake, double at) {
		return wake < 0 || at < wake ? at : wake;
	}

	/** The largest update time of the datapoints of <code>manual</code> that are not unknown; -1 if none. */
	private static double latestManual(BlockContext cx) {
		double latest = -1;
		for (int k = 0; k < cx.count(MANUAL); k++) {
			InputValue v = cx.readAt(MANUAL, k);
			if (v.quality() != Quality.UNKNOWN) {
				latest = ExactMath.max(latest, v.updatedMs());
			}
		}
		return latest;
	}

	@Override
	public void start(BlockContext cx, StartReason reason, boolean restored) {
		// A fresh record holds the initial values already.
		double[] s = cx.state();
		s[S_LAST_STATE] = -1;
		s[S_MOVED_AT] = -1;
		s[S_MANUAL_SEEN] = latestManual(cx);
		cx.cancelTimer(0);
		step(cx, false);
	}

	@Override
	public void input(BlockContext cx, int port) {
		boolean operated = false;
		if (port == MANUAL) {
			double[] s = cx.state();
			double latest = latestManual(cx);
			operated = latest > s[S_MANUAL_SEEN];
			s[S_MANUAL_SEEN] = ExactMath.max(s[S_MANUAL_SEEN], latest);
		}
		step(cx, operated);
	}

	@Override
	public void timer(BlockContext cx, int timer) {
		step(cx, false);
	}

	@Override
	public void period(BlockContext cx) {
		step(cx, false);
	}

	@Override
	public void parameter(BlockContext cx, int index) {
		step(cx, false);
	}

	private static void step(BlockContext cx, boolean operated) {
		double[] s = cx.state();
		double[] p = new double[MANIFEST.params().size()];
		for (int i = 0; i < p.length; i++) {
			p[i] = cx.param(i);
		}
		final long now = cx.now();
		final double t = now;
		final double release = p[ALARM_RELEASE];

		// The alarms (D3, D4).
		final double windTimeout = p[WIND_TIMEOUT];
		final int winds = cx.count(WIND);
		boolean windCond = false;
		for (int k = 0; k < winds; k++) {
			InputValue w = cx.readAt(WIND, k);
			boolean known = w.quality() != Quality.UNKNOWN;
			if ((known && w.value() >= p[WIND_LIMIT])
				|| (windTimeout > 0 && (!known || (double) (now - w.updatedMs()) >= windTimeout))) {
				windCond = true;
			}
		}
		alarm(s, S_WIND_ON, S_WIND_CALM_AT, windCond, t, release);
		boolean rainCond = false;
		for (int k = 0; k < cx.count(RAIN); k++) {
			InputValue r = cx.readAt(RAIN, k);
			if (r.quality() != Quality.UNKNOWN && r.value() != 0) {
				rainCond = true;
			}
		}
		alarm(s, S_RAIN_ON, S_RAIN_DRY_AT, rainCond, t, release);
		InputValue f = cx.read(FROST);
		InputValue o = cx.read(OUTSIDE);
		alarm(s, S_FROST_ON, S_FROST_CLEAR_AT, (f.quality() != Quality.UNKNOWN && f.value() != 0)
			|| (o.quality() != Quality.UNKNOWN && o.value() < p[FROST_LIMIT]), t, release);

		// The brightness (D9).
		InputValue b = cx.read(BRIGHTNESS);
		if (!b.bound()) {
			s[S_BRIGHT] = 1;
			s[S_BRIGHT_AT] = -1;
		} else {
			boolean change;
			double delay;
			if (s[S_BRIGHT] == 0) {
				change = b.good() && b.value() >= p[BRIGHTNESS_ON];
				delay = p[BRIGHTNESS_ON_DELAY];
			} else {
				change = !b.good() || b.value() < p[BRIGHTNESS_OFF];
				delay = p[BRIGHTNESS_OFF_DELAY];
			}
			if (!change) {
				s[S_BRIGHT_AT] = -1;
			} else {
				if (s[S_BRIGHT_AT] < 0) {
					s[S_BRIGHT_AT] = t;
				}
				if (t - s[S_BRIGHT_AT] >= delay) {
					s[S_BRIGHT] = 1 - s[S_BRIGHT];
					s[S_BRIGHT_AT] = -1;
				}
			}
		}

		// The sun on the facade (D1, D8).
		SunPosition sun = cx.sun();
		boolean sunOn = false;
		double profile = 0;
		if (sun != null) {
			double az = sun.azimuth();
			double el = sun.elevation();
			double g = az - p[FACADE_AZIMUTH];
			g = g - 360.0 * Math.floor((g + 180.0) / 360.0);
			profile = ExactMath.atan2Deg(ExactMath.sinDeg(el), ExactMath.cosDeg(el) * ExactMath.cosDeg(g));
			sunOn = Math.abs(g) < p[OPENING_ANGLE] / 2.0 && el > p[MIN_ELEVATION]
				&& (p[HORIZON_POINTS] == 0 || el > horizon(p, g)) && (p[OVERHANG_ANGLE] == 90 || profile < p[OVERHANG_ANGLE]);
		}
		final double sunny = sunOn && s[S_BRIGHT] == 1 ? 1 : 0;

		// Heat protection (D11).
		InputValue presence = cx.read(PRESENCE);
		InputValue summer = cx.read(SUMMER);
		InputValue room = cx.read(ROOM_TEMPERATURE);
		if (presence.good() && presence.value() == 0 && (!summer.bound() || (summer.good() && summer.value() != 0))
			&& room.good()) {
			if (room.value() > p[HEAT_LIMIT]) {
				s[S_HEAT] = 1;
			} else if (room.value() < p[HEAT_LIMIT] - 1.0) {
				s[S_HEAT] = 0;
			}
		} else {
			s[S_HEAT] = 0;
		}

		// The lock after an operation (D6, D7).
		final boolean alarmed = s[S_WIND_ON] == 1 || s[S_RAIN_ON] == 1 || s[S_FROST_ON] == 1;
		DateTime w = cx.wall();
		final double today = w != null ? Values.daysOf(w.year(), w.month(), w.day()) : -1;
		final int lock = (int) p[MANUAL_LOCK];
		if (operated && !alarmed) {
			s[S_MANUAL_AT] = t;
			s[S_MANUAL_SUNNY] = sunny;
			s[S_MANUAL_DAY] = today;
			s[S_HOLDING] = 0;
		}
		if (s[S_MANUAL_AT] >= 0) {
			boolean end;
			if (lock == MIDNIGHT && s[S_MANUAL_DAY] >= 0 && w != null) {
				end = today != s[S_MANUAL_DAY];
			} else {
				end = t - s[S_MANUAL_AT] >= p[MANUAL_DURATION] || (lock == SUN_CHANGE && sunny != s[S_MANUAL_SUNNY]);
			}
			if (end) {
				s[S_MANUAL_AT] = -1;
				s[S_MANUAL_DAY] = -1;
			}
		}

		InputValue enable = cx.read(ENABLE);
		final int state;
		if (s[S_WIND_ON] == 1) {
			state = STATE_WIND;
		} else if (s[S_RAIN_ON] == 1) {
			state = STATE_RAIN;
		} else if (s[S_FROST_ON] == 1) {
			state = STATE_FROST;
		} else if (s[S_MANUAL_AT] >= 0) {
			state = STATE_MANUAL;
		} else if (enable.good() && enable.value() == 0) {
			state = OFF;
		} else if (s[S_HEAT] == 1) {
			state = HEAT_PROTECTION;
		} else if (sunny == 1) {
			state = SHADING;
		} else {
			state = IDLE;
		}

		// The target and the moves (D2, D3, D10, D11, D12).
		boolean hasTarget = false;
		double tp = 0;
		double tq = 0;
		double tu = 0;
		if (isAlarm(state)) {
			if (!isAlarm(s[S_LAST_STATE]) || operated) {
				cx.write(POSITION, 0);
				cx.write(SLAT, 0);
				cx.write(UP_DOWN, 0);
				s[S_SENT_POSITION] = 0;
				s[S_SENT_SLAT] = 0;
				s[S_SENT_UP_DOWN] = 0;
				s[S_MOVED_AT] = t;
				s[S_HOLDING] = 0;
				s[S_DUE] = 0;
			}
		} else if (isActive(state)) {
			if (!isActive(s[S_LAST_STATE])) {
				s[S_DUE] = 1;
			}
			hasTarget = true;
			if (state == HEAT_PROTECTION) {
				tp = 100;
				tq = 100;
				tu = 1;
			} else {
				tp = p[SHADE_POSITION];
				tq = (int) p[SLAT_MODE] == TRACKING ? trackedSlat(p, profile) : p[SHADE_SLAT];
				tu = tp > 0 ? 1 : 0;
			}
		} else if (state == IDLE && s[S_HOLDING] == 1) {
			hasTarget = true;
			s[S_DUE] = 1;
		} else {
			s[S_DUE] = 0;
		}
		double heldUntil = -1;
		if (hasTarget && (s[S_DUE] == 1 || tp != s[S_SENT_POSITION] || tq != s[S_SENT_SLAT] || tu != s[S_SENT_UP_DOWN])) {
			if (s[S_MOVED_AT] < 0 || t - s[S_MOVED_AT] >= p[MIN_MOVE_INTERVAL]) {
				boolean all = s[S_DUE] == 1;
				if (all || tp != s[S_SENT_POSITION]) {
					cx.write(POSITION, tp);
					s[S_SENT_POSITION] = tp;
				}
				if (all || tq != s[S_SENT_SLAT]) {
					cx.write(SLAT, tq);
					s[S_SENT_SLAT] = tq;
				}
				if (all || tu != s[S_SENT_UP_DOWN]) {
					cx.write(UP_DOWN, tu);
					s[S_SENT_UP_DOWN] = tu;
				}
				s[S_MOVED_AT] = t;
				s[S_DUE] = 0;
				s[S_HOLDING] = isActive(state) ? 1 : 0;
			} else {
				heldUntil = s[S_MOVED_AT] + p[MIN_MOVE_INTERVAL];
			}
		}

		// The timer.
		double wake = -1;
		if (s[S_WIND_ON] == 1 && s[S_WIND_CALM_AT] >= 0) {
			wake = earliest(wake, s[S_WIND_CALM_AT] + release);
		}
		if (s[S_RAIN_ON] == 1 && s[S_RAIN_DRY_AT] >= 0) {
			wake = earliest(wake, s[S_RAIN_DRY_AT] + release);
		}
		if (s[S_FROST_ON] == 1 && s[S_FROST_CLEAR_AT] >= 0) {
			wake = earliest(wake, s[S_FROST_CLEAR_AT] + release);
		}
		for (int k = 0; windTimeout > 0 && k < winds; k++) {
			InputValue v = cx.readAt(WIND, k);
			if (v.quality() != Quality.UNKNOWN && (double) (now - v.updatedMs()) < windTimeout) {
				wake = earliest(wake, (double) v.updatedMs() + windTimeout);
			}
		}
		if (s[S_BRIGHT_AT] >= 0) {
			wake = earliest(wake, s[S_BRIGHT_AT] + (s[S_BRIGHT] == 0 ? p[BRIGHTNESS_ON_DELAY] : p[BRIGHTNESS_OFF_DELAY]));
		}
		if (s[S_MANUAL_AT] >= 0 && !(lock == MIDNIGHT && s[S_MANUAL_DAY] >= 0 && w != null)) {
			wake = earliest(wake, s[S_MANUAL_AT] + p[MANUAL_DURATION]);
		}
		if (heldUntil >= 0) {
			wake = earliest(wake, heldUntil);
		}
		if (wake >= 0) {
			cx.setTimer(0, (long) (wake - t));
		} else {
			cx.cancelTimer(0);
		}

		cx.write(STATE, state);
		if (sun != null) {
			cx.write(SUN_AZIMUTH, sun.azimuth());
			cx.write(SUN_ELEVATION, sun.elevation());
		}
		s[S_LAST_STATE] = state;
	}

}
