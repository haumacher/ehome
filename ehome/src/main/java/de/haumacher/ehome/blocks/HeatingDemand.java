/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.blocks;

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

/**
 * The heating demand <code>org.homecontrol.heating-demand</code> 1.0 (homecontrol
 * <code>spec/blocks.md</code> 16): the rooms' valve positions and heat requests into the heat
 * source's request (hysteresis, minimum on and off times), the demand level (the highest, or the
 * mean of the highest), the circulation pump with its run-on, and the flow offset for the heating
 * curve. A literal port of homecontrol <code>core/src/blocks/heating_demand.cpp</code>: every formula
 * in IEEE 754 doubles in the order the specification writes it, <code>min</code>/<code>max</code>
 * as C++'s <code>std::min</code>/<code>std::max</code> (<code>spec/blocks.md</code> 13).
 */
public final class HeatingDemand extends BlockType {

	/** The type id. */
	public static final String TYPE = "org.homecontrol.heating-demand";

	/** The one instance. */
	public static final HeatingDemand INSTANCE = new HeatingDemand();

	// Ports, in manifest order.

	/** Port <code>valves</code>. */
	public static final int VALVES = 0;

	/** Port <code>demands</code>. */
	public static final int DEMANDS = 1;

	/** Port <code>enable</code>. */
	public static final int ENABLE = 2;

	/** Port <code>summer</code>. */
	public static final int SUMMER = 3;

	/** Port <code>request</code>. */
	public static final int REQUEST = 4;

	/** Port <code>level</code>. */
	public static final int LEVEL = 5;

	/** Port <code>pump</code>. */
	public static final int PUMP = 6;

	/** Port <code>flowSetpointOffset</code>. */
	public static final int FLOW_SETPOINT_OFFSET = 7;

	/** Port <code>state</code>. */
	public static final int STATE = 8;

	/** Port <code>staleInputs</code>. */
	public static final int STALE_INPUTS = 9;

	// Parameters, in manifest order.

	private static final int MODE = 0;

	private static final int TOP_COUNT = 1;

	private static final int ON_THRESHOLD = 2;

	private static final int OFF_THRESHOLD = 3;

	private static final int MIN_ON = 4;

	private static final int MIN_OFF = 5;

	private static final int PUMP_RUN_ON = 6;

	private static final int TARGET_LEVEL = 7;

	private static final int OFFSET_GAIN = 8;

	private static final int MAX_OFFSET = 9;

	private static final int STALE_INPUT = 10;

	// State fields, in manifest order.

	/** State field <code>offset</code>. */
	public static final int S_OFFSET = 0;

	private static final int S_LAST_STEP = 1;

	private static final int S_LAST_LEVEL = 2;

	private static final int S_HEAT = 3;

	private static final int S_SWITCHED_AT = 4;

	private static final int S_HAS_SWITCHED = 5;

	private static final int S_HAS_SENT = 6;

	private static final int S_SENT_REQUEST = 7;

	private static final int S_SENT_PUMP = 8;

	private static final int S_SENT_LEVEL = 9;

	private static final int S_SENT_OFFSET = 10;

	private static final int WAKE_TIMER = 0;

	// The values of the output state.

	private static final int OFF = 0;

	private static final int ON = 1;

	private static final int HELD_ON = 2;

	private static final int HELD_OFF = 3;

	private static final int DISABLED = 4;

	private static final int STATE_SUMMER = 5;

	/** The choice <code>max</code> of <code>mode</code>. */
	private static final int MAX = 0;

	/** The choice <code>full</code> of <code>staleInput</code>. */
	private static final int FULL = 1;

	private static final double MINUTE = 60000;

	private static final Manifest MANIFEST = new Manifest(TYPE, 1, 0, "Heating demand",
		"The rooms' valves and demands into the heat source's request, the demand level, the pump and a flow offset",
		List.of(
			Port.in("valves", PortType.REAL, "Valve positions", "%", false, 32, List.of(Aggregation.MAX),
				InputTrigger.UPDATE),
			Port.in("demands", PortType.BOOLEAN, "Heat demands", "", false, 32, List.of(Aggregation.ANY),
				InputTrigger.UPDATE),
			Port.in("enable", PortType.BOOLEAN, "Heating on", "", false, 1, List.of(), InputTrigger.CHANGE),
			Port.in("summer", PortType.BOOLEAN, "Summer", "", false, 1, List.of(), InputTrigger.CHANGE),
			Port.out("request", PortType.BOOLEAN, "Heat request", "", 1, null, null),
			Port.out("level", PortType.REAL, "Demand level", "%", 100, Double.valueOf(0), Double.valueOf(100)),
			Port.out("pump", PortType.BOOLEAN, "Circulation pump", "", 1, null, null),
			Port.out("flowSetpointOffset", PortType.REAL, "Flow setpoint offset", "K", 0, Double.valueOf(0),
				Double.valueOf(20)),
			Port.diagnostic("state", PortType.ENUMERATION, "State", "", Double.valueOf(0), Double.valueOf(5)),
			Port.diagnostic("staleInputs", PortType.INTEGER, "Inputs not good", "", null, null)),
		List.of(
			new Param("mode", ParamType.ENUMERATION, "Demand mode", "", 0, null, null, 0,
				List.of(new Choice(0, "max", "Highest valve"), new Choice(1, "topMean", "Mean of the highest")), false),
			new Param("topCount", ParamType.INTEGER, "Valves averaged", "", 3, Double.valueOf(1), Double.valueOf(32), 1,
				List.of(), false),
			real("onThreshold", "Switch-on level", "%", 20, 0, 100, 1),
			real("offThreshold", "Switch-off level", "%", 5, 0, 100, 1),
			duration("minOn", "Minimum on time", 5 * MINUTE),
			duration("minOff", "Minimum off time", 5 * MINUTE),
			duration("pumpRunOn", "Pump run-on", 5 * MINUTE),
			real("targetLevel", "Target level", "%", 70, 10, 100, 1),
			real("offsetGain", "Offset gain", "K/(% h)", 0.1, 0, 1, 0.01),
			real("maxOffset", "Highest offset", "K", 5, 0, 20, 0.5),
			new Param("staleInput", ParamType.ENUMERATION, "Input not good", "", 0, null, null, 0,
				List.of(new Choice(0, "ignore", "Ignore"), new Choice(1, "full", "Count as fully open")), false)),
		List.of(new Field("offset", true, 0), new Field("lastStep", false, 0), new Field("lastLevel", false, 0),
			new Field("heat", false, 0), new Field("switchedAt", false, 0), new Field("hasSwitched", false, 0),
			new Field("hasSent", false, 0), new Field("sentRequest", false, 0), new Field("sentPump", false, 0),
			new Field("sentLevel", false, 0), new Field("sentOffset", false, 0)),
		List.of("wake"), 60000, 600000);

	private static Param real(String id, String label, String unit, double def, double min, double max, double step) {
		return new Param(id, ParamType.REAL, label, unit, def, Double.valueOf(min), Double.valueOf(max), step,
			List.of(), false);
	}

	private static Param duration(String id, String label, double def) {
		return new Param(id, ParamType.DURATION, label, "min", def, Double.valueOf(0), Double.valueOf(60 * MINUTE),
			MINUTE, List.of(), false);
	}

	private HeatingDemand() {
		// The singleton.
	}

	@Override
	public Manifest manifest() {
		return MANIFEST;
	}

	@Override
	public Blame check(double[] params) {
		if (!(params[OFF_THRESHOLD] < params[ON_THRESHOLD])) {
			return new Blame("offThreshold must be below onThreshold",
				I18NConstants.BLOCK_PARAM_BELOW__LOW_HIGH.fill("offThreshold", "onThreshold"),
				OFF_THRESHOLD);
		}
		return null;
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
		cx.cancelTimer(WAKE_TIMER);
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

	/**
	 * Keeps the <code>limit</code> largest values added, in descending order
	 * (<code>spec/blocks.md</code> 16.4).
	 */
	private static final class Highest {

		final double[] _values;

		final int _limit;

		int _size;

		Highest(int limit) {
			_limit = limit;
			_values = new double[limit];
		}

		void add(double v) {
			if (_size == _limit && !(v > _values[_limit - 1])) {
				return;
			}
			if (_size < _limit) {
				_size++;
			}
			int i = _size - 1;
			while (i > 0 && _values[i - 1] < v) {
				_values[i] = _values[i - 1];
				i--;
			}
			_values[i] = v;
		}
	}

	private static void step(BlockContext cx) {
		double[] s = cx.state();
		final double t = cx.now();
		final double dt = (t - s[S_LAST_STEP]) / 1000.0;
		s[S_LAST_STEP] = t;

		// The offset over the interval just ended, with the level and request that held during it (D5).
		final double e = (s[S_HEAT] == 1 ? s[S_LAST_LEVEL] : 0) - cx.param(TARGET_LEVEL);
		final double maxOffset = cx.param(MAX_OFFSET);
		s[S_OFFSET] = s[S_OFFSET] + cx.param(OFFSET_GAIN) * e * (dt / 3600.0);
		s[S_OFFSET] = min(max(s[S_OFFSET], 0.0), maxOffset);

		// The level over the valves and the demands, one by one (D1, D2, D3).
		final boolean countFull = (int) cx.param(STALE_INPUT) == FULL;
		final boolean byMax = (int) cx.param(MODE) == MAX;
		Highest highest = new Highest(byMax ? 1 : (int) cx.param(TOP_COUNT));
		int stale = 0;
		for (int port : new int[] { VALVES, DEMANDS }) {
			int n = cx.count(port);
			for (int k = 0; k < n; k++) {
				InputValue v = cx.readAt(port, k);
				if (v.good()) {
					highest.add(port == VALVES ? min(max(v.value(), 0.0), 100.0) : (v.value() != 0 ? 100.0 : 0.0));
				} else {
					stale++;
					if (countFull) {
						highest.add(100);
					}
				}
			}
		}
		double level = 0;
		if (highest._size > 0) {
			if (byMax) {
				level = highest._values[0];
			} else {
				double sum = highest._values[0];
				for (int i = 1; i < highest._size; i++) {
					sum = sum + highest._values[i];
				}
				level = sum / highest._size;
			}
		}

		InputValue enable = cx.read(ENABLE);
		final boolean isOff = enable.good() && enable.value() == 0;
		final boolean isSummer = cx.read(SUMMER).isTrue();
		double want;
		if (isOff || isSummer) {
			want = 0;
		} else if (level >= cx.param(ON_THRESHOLD)) {
			want = 1;
		} else if (level <= cx.param(OFF_THRESHOLD)) {
			want = 0;
		} else {
			want = s[S_HEAT];
		}

		// The minimum times (D4).
		boolean held = false;
		boolean wake = false;
		double wakeAt = 0;
		if (want != s[S_HEAT]) {
			double hold = s[S_HEAT] == 1 ? cx.param(MIN_ON) : cx.param(MIN_OFF);
			if (s[S_HAS_SWITCHED] == 0 || t - s[S_SWITCHED_AT] >= hold) {
				s[S_HEAT] = want;
				s[S_SWITCHED_AT] = t;
				s[S_HAS_SWITCHED] = 1;
			} else {
				held = true;
				wake = true;
				wakeAt = s[S_SWITCHED_AT] + hold;
			}
		}

		final double runOn = cx.param(PUMP_RUN_ON);
		final boolean pump = s[S_HEAT] == 1 || (s[S_HAS_SWITCHED] == 1 && t - s[S_SWITCHED_AT] < runOn);
		if (s[S_HEAT] == 0 && pump) {
			double end = s[S_SWITCHED_AT] + runOn;
			wakeAt = wake ? min(wakeAt, end) : end;
			wake = true;
		}
		if (wake) {
			cx.setTimer(WAKE_TIMER, (long) (wakeAt - t));
		} else {
			cx.cancelTimer(WAKE_TIMER);
		}
		s[S_LAST_LEVEL] = level;

		final int state = held ? (s[S_HEAT] == 1 ? HELD_ON : HELD_OFF)
			: isOff ? DISABLED : isSummer ? STATE_SUMMER : (s[S_HEAT] == 1 ? ON : OFF);

		// Outputs (spec/blocks.md 16.4).
		final boolean first = s[S_HAS_SENT] == 0;
		if (first || s[S_HEAT] != s[S_SENT_REQUEST]) {
			cx.write(REQUEST, s[S_HEAT]);
		}
		s[S_SENT_REQUEST] = s[S_HEAT];
		if (first || level != s[S_SENT_LEVEL]) {
			cx.write(LEVEL, level);
		}
		s[S_SENT_LEVEL] = level;
		final double pumpOn = pump ? 1 : 0;
		if (first || pumpOn != s[S_SENT_PUMP]) {
			cx.write(PUMP, pumpOn);
		}
		s[S_SENT_PUMP] = pumpOn;
		final double offset = s[S_OFFSET];
		if (first || (offset != s[S_SENT_OFFSET]
			&& (Math.abs(offset - s[S_SENT_OFFSET]) >= 0.1 || offset == 0 || offset == maxOffset))) {
			cx.write(FLOW_SETPOINT_OFFSET, offset);
			s[S_SENT_OFFSET] = offset;
		}
		cx.write(STATE, state);
		cx.write(STALE_INPUTS, stale);
		s[S_HAS_SENT] = 1;
	}

}
