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
import de.haumacher.ehome.rules.DatapointModel.Quality;

/**
 * The heating curve <code>org.homecontrol.heating-curve</code> 1.0 (homecontrol
 * <code>spec/blocks.md</code> 17): the flow setpoint over the filtered outside temperature (slope and
 * level, a design point or 2..6 points), shifted by the room setpoint and an offset, limited; the
 * summer/winter switch from the filtered temperature; frost protection; a missing outside
 * temperature holds the filter. A literal port of homecontrol
 * <code>core/src/blocks/heating_curve.cpp</code>: every formula in IEEE 754 doubles in the order the
 * specification writes it, <code>min</code>/<code>max</code> as C++'s
 * <code>std::min</code>/<code>std::max</code> (<code>spec/blocks.md</code> 13).
 */
public final class HeatingCurve extends BlockType {

	/** The type id. */
	public static final String TYPE = "org.homecontrol.heating-curve";

	/** The one instance. */
	public static final HeatingCurve INSTANCE = new HeatingCurve();

	// Ports, in manifest order.

	/** Port <code>outside</code>. */
	public static final int OUTSIDE = 0;

	/** Port <code>roomSetpoint</code>. */
	public static final int ROOM_SETPOINT = 1;

	/** Port <code>offset</code>. */
	public static final int OFFSET = 2;

	/** Port <code>enable</code>. */
	public static final int ENABLE = 3;

	/** Port <code>summerSwitch</code>. */
	public static final int SUMMER_SWITCH = 4;

	/** Port <code>flowSetpoint</code>. */
	public static final int FLOW_SETPOINT = 5;

	/** Port <code>summer</code>. */
	public static final int SUMMER = 6;

	/** Port <code>outsideFiltered</code>. */
	public static final int OUTSIDE_FILTERED = 7;

	/** Port <code>state</code>. */
	public static final int STATE = 8;

	// Parameters, in manifest order.

	private static final int CURVE = 0;

	private static final int SLOPE = 1;

	private static final int LEVEL = 2;

	private static final int ROOM_TEMPERATURE = 3;

	private static final int DESIGN_OUTSIDE = 4;

	private static final int DESIGN_FLOW = 5;

	private static final int POINT_COUNT = 6;

	/** The parameter <code>outside1</code>; <code>outside&lt;i+1&gt;</code> at OUTSIDE1 + 2i, its flow after it. */
	private static final int OUTSIDE1 = 7;

	private static final int MIN_FLOW = 19;

	private static final int MAX_FLOW = 20;

	private static final int MIN_CHANGE = 21;

	private static final int OUTSIDE_FILTER = 22;

	private static final int SUMMER_ON = 23;

	private static final int SUMMER_OFF = 24;

	private static final int SUMMER_DAYS = 25;

	private static final int FROST_OUTSIDE = 26;

	private static final int OFF_FLOW = 27;

	private static final int SENSOR_TIMEOUT = 28;

	// State fields, in manifest order.

	/** State field <code>filtered</code>. */
	public static final int S_FILTERED = 0;

	private static final int S_HAS_FILTERED = 1;

	/** State field <code>aboveFor</code>. */
	public static final int S_ABOVE_FOR = 2;

	private static final int S_DERIVED_SUMMER = 3;

	private static final int S_LAST_STEP = 4;

	private static final int S_FROST_ON = 5;

	private static final int S_HAS_SENT = 6;

	private static final int S_SENT_FLOW = 7;

	private static final int S_SENT_SUMMER = 8;

	// The values of the output state.

	private static final int HEATING = 0;

	private static final int STATE_SUMMER = 1;

	private static final int OFF = 2;

	private static final int FROST = 3;

	private static final int FAULT = 4;

	// The forms of the curve.

	private static final int DESIGN = 1;

	private static final int POINTS = 2;

	private static final double HOUR = 3600000;

	private static final Manifest MANIFEST = new Manifest(TYPE, 1, 0, "Heating curve",
		"Weather-compensated flow setpoint with the summer/winter switch and frost protection",
		List.of(
			Port.in("outside", PortType.REAL, "Outside temperature", "°C", true, 4,
				List.of(Aggregation.MEAN, Aggregation.MIN), InputTrigger.NONE),
			Port.in("roomSetpoint", PortType.REAL, "Room setpoint", "°C", false, 1, List.of(), InputTrigger.CHANGE),
			Port.in("offset", PortType.REAL, "Flow offset", "K", false, 1, List.of(), InputTrigger.CHANGE),
			Port.in("enable", PortType.BOOLEAN, "Heating on", "", false, 1, List.of(), InputTrigger.CHANGE),
			Port.in("summerSwitch", PortType.BOOLEAN, "Summer switch", "", false, 1, List.of(), InputTrigger.CHANGE),
			Port.out("flowSetpoint", PortType.REAL, "Flow setpoint", "°C", 40, null, null),
			Port.out("summer", PortType.BOOLEAN, "Summer", "", 0, null, null),
			Port.diagnostic("outsideFiltered", PortType.REAL, "Filtered outside temperature", "°C", null, null),
			Port.diagnostic("state", PortType.ENUMERATION, "State", "", Double.valueOf(0), Double.valueOf(4))),
		params(),
		List.of(new Field("filtered", true, 0), new Field("hasFiltered", true, 0), new Field("aboveFor", true, 0),
			new Field("derivedSummer", true, 0), new Field("lastStep", false, 0), new Field("frostOn", false, 0),
			new Field("hasSent", false, 0), new Field("sentFlow", false, 0), new Field("sentSummer", false, 0)),
		List.of(), 60000, 1800000);

	private static List<Param> params() {
		List<Param> result = new ArrayList<>();
		result.add(new Param("curve", ParamType.ENUMERATION, "Curve", "", 0, null, null, 0,
			List.of(new Choice(0, "slope", "Slope and level"), new Choice(1, "design", "Design point"),
				new Choice(2, "points", "Points")),
			false));
		result.add(real("slope", "Slope", "", 1.2, 0.2, 4, 0.1, true));
		result.add(real("level", "Level", "K", 0, -15, 15, 0.5, true));
		result.add(real("roomTemperature", "Room temperature of the curve", "°C", 20, 10, 30, 0.5, false));
		result.add(real("designOutside", "Design outside temperature", "°C", -12, -30, 10, 1, false));
		result.add(real("designFlow", "Design flow temperature", "°C", 55, 20, 90, 1, false));
		result.add(new Param("pointCount", ParamType.INTEGER, "Points", "", 6, Double.valueOf(2), Double.valueOf(6), 1,
			List.of(), false));
		double[] outside = { -15, -10, -5, 0, 10, 20 };
		double[] flow = { 60, 55, 49, 43, 32, 20 };
		for (int i = 0; i < 6; i++) {
			int n = i + 1;
			result.add(real("outside" + n, "Outside temperature " + n, "°C", outside[i], -40, 40, 1, false));
			result.add(real("flow" + n, "Flow " + n, "°C", flow[i], 10, 90, 1, false));
		}
		result.add(real("minFlow", "Lowest flow", "°C", 25, 10, 60, 1, false));
		result.add(real("maxFlow", "Highest flow", "°C", 60, 20, 90, 1, false));
		result.add(real("minChange", "Smallest change sent", "K", 0.5, 0, 5, 0.1, false));
		result.add(new Param("outsideFilter", ParamType.DURATION, "Outside filter time constant", "h", 12 * HOUR,
			Double.valueOf(0), Double.valueOf(48 * HOUR), HOUR, List.of(), false));
		result.add(real("summerOn", "Summer above", "°C", 18, 5, 30, 0.5, false));
		result.add(real("summerOff", "Winter below", "°C", 15, 5, 30, 0.5, false));
		result.add(new Param("summerDays", ParamType.INTEGER, "Days to summer", "d", 3, Double.valueOf(0),
			Double.valueOf(14), 1, List.of(), false));
		result.add(real("frostOutside", "Frost protection below", "°C", 3, -20, 10, 0.5, false));
		result.add(real("offFlow", "Flow when off", "°C", 0, 0, 40, 1, false));
		result.add(new Param("sensorTimeout", ParamType.DURATION, "Sensor timeout", "min", 3 * HOUR,
			Double.valueOf(10 * 60000.0), Double.valueOf(24 * HOUR), 60000, List.of(), false));
		return List.copyOf(result);
	}

	private static Param real(String id, String label, String unit, double def, double min, double max, double step,
			boolean adjustable) {
		return new Param(id, ParamType.REAL, label, unit, def, Double.valueOf(min), Double.valueOf(max), step,
			List.of(), adjustable);
	}

	private HeatingCurve() {
		// The singleton.
	}

	@Override
	public Manifest manifest() {
		return MANIFEST;
	}

	private static int outsideOf(int i) {
		return OUTSIDE1 + 2 * i;
	}

	private static int flowOf(int i) {
		return OUTSIDE1 + 1 + 2 * i;
	}

	@Override
	public Blame check(double[] params) {
		if (!(params[MIN_FLOW] < params[MAX_FLOW])) {
			return new Blame("minFlow must be below maxFlow", MIN_FLOW);
		}
		if (!(params[SUMMER_OFF] < params[SUMMER_ON])) {
			return new Blame("summerOff must be below summerOn", SUMMER_OFF);
		}
		int form = (int) params[CURVE];
		if (form == DESIGN) {
			if (!(params[DESIGN_OUTSIDE] < params[ROOM_TEMPERATURE])) {
				return new Blame("designOutside must be below roomTemperature", DESIGN_OUTSIDE);
			}
			if (!(params[DESIGN_FLOW] > params[ROOM_TEMPERATURE])) {
				return new Blame("designFlow must be above roomTemperature", DESIGN_FLOW);
			}
		} else if (form == POINTS) {
			int n = (int) params[POINT_COUNT];
			for (int i = 0; i + 1 < n; i++) {
				if (!(params[outsideOf(i + 1)] > params[outsideOf(i)])) {
					return new Blame("the points' outside temperatures must rise", outsideOf(i + 1));
				}
			}
		}
		return null;
	}

	/** The flow of the points at <code>x</code> (<code>spec/blocks.md</code> 17.4 <code>interpolate</code>). */
	private static double interpolate(BlockContext cx, double x) {
		int n = (int) cx.param(POINT_COUNT);
		if (x <= cx.param(outsideOf(0))) {
			return cx.param(flowOf(0));
		}
		if (x >= cx.param(outsideOf(n - 1))) {
			return cx.param(flowOf(n - 1));
		}
		for (int i = 0; i + 1 < n; i++) {
			double x0 = cx.param(outsideOf(i));
			double x1 = cx.param(outsideOf(i + 1));
			if (x <= x1) {
				double y0 = cx.param(flowOf(i));
				double y1 = cx.param(flowOf(i + 1));
				return y0 + (y1 - y0) * ((x - x0) / (x1 - x0));
			}
		}
		return cx.param(flowOf(n - 1));
	}

	/** C++'s <code>std::max</code>: <code>(a &lt; b) ? b : a</code>. */
	private static double max(double a, double b) {
		return a < b ? b : a;
	}

	/** C++'s <code>std::min</code>: <code>(b &lt; a) ? b : a</code>. */
	private static double min(double a, double b) {
		return b < a ? b : a;
	}

	@Override
	public void start(BlockContext cx, StartReason reason, boolean restored) {
		// A fresh record holds the initial values already.
		double[] s = cx.state();
		s[S_LAST_STEP] = cx.now();
		s[S_HAS_SENT] = 0;
		step(cx);
	}

	@Override
	public void input(BlockContext cx, int port) {
		step(cx);
	}

	@Override
	public void timer(BlockContext cx, int timer) {
		step(cx);
	}

	@Override
	public void period(BlockContext cx) {
		step(cx);
	}

	@Override
	public void parameter(BlockContext cx, int index) {
		step(cx);
	}

	private static void step(BlockContext cx) {
		double[] s = cx.state();
		final long now = cx.now();
		final double t = now;
		final double dt = (t - s[S_LAST_STEP]) / 1000.0;
		s[S_LAST_STEP] = t;
		final double tf = cx.param(OUTSIDE_FILTER) / 1000.0;

		InputValue x = cx.read(OUTSIDE);
		final boolean fresh = x.quality() != Quality.UNKNOWN && (double) (now - x.updatedMs()) <= cx.param(SENSOR_TIMEOUT);
		if (fresh && x.good()) {
			double value = x.value();
			if (s[S_HAS_FILTERED] == 0 || tf == 0) {
				s[S_FILTERED] = value;
				s[S_HAS_FILTERED] = 1;
			} else {
				s[S_FILTERED] = s[S_FILTERED] + (value - s[S_FILTERED]) * (dt / (tf + dt));
			}
			double summerOn = cx.param(SUMMER_ON);
			double days = cx.param(SUMMER_DAYS) * 86400.0;
			if (s[S_FILTERED] > summerOn) {
				s[S_ABOVE_FOR] = min(s[S_ABOVE_FOR] + dt, days);
			} else {
				s[S_ABOVE_FOR] = 0;
			}
			if (s[S_DERIVED_SUMMER] == 0 && s[S_FILTERED] > summerOn && s[S_ABOVE_FOR] >= days) {
				s[S_DERIVED_SUMMER] = 1;
			} else if (s[S_DERIVED_SUMMER] == 1 && s[S_FILTERED] < cx.param(SUMMER_OFF)) {
				s[S_DERIVED_SUMMER] = 0;
			}
		}
		final double filtered = s[S_FILTERED];
		final double fx = fresh ? x.value() : filtered; // frost on the momentary value (D5)
		final double frostOutside = cx.param(FROST_OUTSIDE);
		if (fx < frostOutside) {
			s[S_FROST_ON] = 1;
		} else if (fx >= frostOutside + 1.0) {
			s[S_FROST_ON] = 0;
		}

		InputValue sw = cx.read(SUMMER_SWITCH);
		final boolean summer = sw.good() ? sw.value() != 0 : s[S_DERIVED_SUMMER] == 1;

		final double r = cx.param(ROOM_TEMPERATURE);
		InputValue rs = cx.read(ROOM_SETPOINT);
		final double tr = rs.good() ? rs.value() : r;
		final double level = cx.param(LEVEL);
		double flow;
		switch ((int) cx.param(CURVE)) {
			case DESIGN: {
				double slope = (cx.param(DESIGN_FLOW) - r) / (r - cx.param(DESIGN_OUTSIDE));
				flow = tr + level + slope * (tr - filtered);
				break;
			}
			case POINTS: {
				double d = tr - r;
				flow = interpolate(cx, filtered - d) + d + level;
				break;
			}
			default:
				flow = tr + level + cx.param(SLOPE) * (tr - filtered);
				break;
		}
		InputValue offset = cx.read(OFFSET);
		if (offset.good()) {
			flow = flow + offset.value();
		}
		final double minFlow = cx.param(MIN_FLOW);
		final double maxFlow = cx.param(MAX_FLOW);
		flow = min(max(flow, minFlow), maxFlow);

		InputValue enable = cx.read(ENABLE);
		final boolean isOff = enable.good() && enable.value() == 0;
		final double offFlow = cx.param(OFF_FLOW);
		double out = flow;
		int state = HEATING;
		if (isOff || summer) {
			if (s[S_FROST_ON] == 1) {
				out = minFlow;
				state = FROST;
			} else {
				out = offFlow;
				state = isOff ? OFF : STATE_SUMMER;
			}
		}
		if (!fresh) {
			state = FAULT;
		}

		// Outputs (spec/blocks.md 17.4).
		final boolean first = s[S_HAS_SENT] == 0;
		if (first || (out != s[S_SENT_FLOW] && (Math.abs(out - s[S_SENT_FLOW]) >= cx.param(MIN_CHANGE)
			|| out == offFlow || out == minFlow || out == maxFlow))) {
			cx.write(FLOW_SETPOINT, out);
			s[S_SENT_FLOW] = out;
		}
		final double summerValue = summer ? 1 : 0;
		if (first || summerValue != s[S_SENT_SUMMER]) {
			cx.write(SUMMER, summerValue);
		}
		s[S_SENT_SUMMER] = summerValue;
		cx.write(OUTSIDE_FILTERED, filtered);
		cx.write(STATE, state);
		s[S_HAS_SENT] = 1;
	}

}
