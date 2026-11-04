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
import de.haumacher.ehome.rules.Val.DateTime;
import de.haumacher.ehome.rules.Values;

/**
 * The room controller <code>org.homecontrol.room-controller</code> 1.0 (homecontrol
 * <code>spec/blocks.md</code> 14): a PI(D) room temperature controller for a continuous valve, a
 * PWM-switched or a two-point heating. A literal port of homecontrol
 * <code>core/src/blocks/room_controller.cpp</code>: every formula in IEEE 754 doubles in the order
 * the specification writes it, without fused multiply-add, and <code>min</code>/<code>max</code>
 * as C++'s <code>std::min</code>/<code>std::max</code>, so it gives the device's bits
 * (<code>spec/blocks.md</code> 13).
 */
public final class RoomController extends BlockType {

	/** The type id. */
	public static final String TYPE = "org.homecontrol.room-controller";

	/** The one instance. */
	public static final RoomController INSTANCE = new RoomController();

	// Ports, in manifest order.

	/** Port <code>temperature</code>. */
	public static final int TEMPERATURE = 0;

	/** Port <code>setpoint</code>. */
	public static final int SETPOINT = 1;

	/** Port <code>mode</code>. */
	public static final int MODE = 2;

	/** Port <code>presence</code>. */
	public static final int PRESENCE = 3;

	/** Port <code>heatingPeriod</code>. */
	public static final int HEATING_PERIOD = 4;

	/** Port <code>window</code>. */
	public static final int WINDOW = 5;

	/** Port <code>enable</code>. */
	public static final int ENABLE = 6;

	/** Port <code>summer</code>. */
	public static final int SUMMER = 7;

	/** Port <code>forced</code>. */
	public static final int FORCED = 8;

	/** Port <code>valve</code>. */
	public static final int VALVE = 9;

	/** Port <code>switch</code>. */
	public static final int SWITCH = 10;

	/** Port <code>demand</code>. */
	public static final int DEMAND = 11;

	/** Port <code>activeSetpoint</code>. */
	public static final int ACTIVE_SETPOINT = 12;

	/** Port <code>state</code>. */
	public static final int STATE = 13;

	/** Port <code>p</code>. */
	public static final int P = 14;

	/** Port <code>i</code>. */
	public static final int I = 15;

	/** Port <code>d</code>. */
	public static final int D = 16;

	/** Port <code>error</code>. */
	public static final int ERROR = 17;

	/** Port <code>filtered</code>. */
	public static final int FILTERED = 18;

	// Parameters, in manifest order.

	private static final int SYSTEM = 0;

	private static final int CONTROL_TYPE = 1;

	private static final int XP = 2;

	private static final int TN = 3;

	private static final int TD = 4;

	private static final int FILTER = 5;

	private static final int CYCLE = 6;

	private static final int HYSTERESIS = 7;

	private static final int OUTPUT_MIN = 8;

	private static final int OUTPUT_MAX = 9;

	private static final int MIN_CHANGE = 10;

	private static final int RESEND_INTERVAL = 11;

	private static final int COMFORT = 12;

	private static final int STANDBY = 13;

	private static final int NIGHT = 14;

	private static final int FROST = 15;

	private static final int WINDOW_DELAY = 16;

	private static final int BOOST_THRESHOLD = 17;

	private static final int BOOST_DURATION = 18;

	private static final int SENSOR_TIMEOUT = 19;

	private static final int FALLBACK_POSITION = 20;

	private static final int FORCED_POSITION = 21;

	private static final int SUMMER_POSITION = 22;

	private static final int PROTECTION_DAY = 23;

	private static final int PROTECTION_TIME = 24;

	private static final int PROTECTION_DURATION = 25;

	// State fields, in manifest order.

	private static final int S_INTEGRAL = 0;

	private static final int S_FILTERED = 1;

	private static final int S_HAS_FILTERED = 2;

	private static final int S_LAST_STEP = 3;

	private static final int S_LAST_SETPOINT = 4;

	private static final int S_HAS_LAST_SETPOINT = 5;

	private static final int S_LAST_KP = 6;

	private static final int S_HAS_LAST_KP = 7;

	private static final int S_WINDOW_ACTIVE = 8;

	private static final int S_BOOST_UNTIL = 9;

	private static final int S_PROTECTION_UNTIL = 10;

	private static final int S_PROTECTED_ON = 11;

	private static final int S_CYCLE_START = 12;

	private static final int S_HAS_CYCLE = 13;

	private static final int S_LAST_STATE = 14;

	private static final int S_SENT_VALVE = 15;

	private static final int S_HAS_SENT = 16;

	private static final int S_SENT_AT = 17;

	private static final int S_SWITCH_ON = 18;

	private static final int S_DEMAND_ON = 19;

	// Timers.

	private static final int WINDOW_TIMER = 0;

	private static final int PWM_TIMER = 1;

	// The values of the output state.

	private static final int OFF = 0;

	private static final int HEATING = 1;

	private static final int STATE_WINDOW = 2;

	private static final int STATE_FROST = 3;

	private static final int BOOST = 4;

	private static final int FAULT = 5;

	private static final int PROTECTION = 6;

	private static final int STATE_SUMMER = 7;

	private static final int STATE_FORCED = 8;

	// Systems and control types.

	private static final int UNDERFLOOR = 1;

	private static final int CONVECTOR = 2;

	private static final int CUSTOM = 3;

	private static final int PWM = 1;

	private static final int TWO_POINT = 2;

	private static final double MINUTE = 60000;

	private static final List<Aggregation> NUMERIC = List.of(Aggregation.MEAN, Aggregation.MIN, Aggregation.MAX);

	private static final Manifest MANIFEST = new Manifest(TYPE, 1, 0, "Room controller",
		"PI(D) room temperature controller for a valve, a PWM-switched or a two-point heating",
		List.of(
			Port.in("temperature", PortType.REAL, "Temperature", "°C", true, 4, NUMERIC, InputTrigger.NONE),
			Port.in("setpoint", PortType.REAL, "External setpoint", "°C", false, 1, List.of(), InputTrigger.CHANGE),
			Port.in("mode", PortType.ENUMERATION, "HVAC mode", "", false, 1, List.of(), InputTrigger.CHANGE),
			Port.in("presence", PortType.BOOLEAN, "Presence", "", false, 1, List.of(), InputTrigger.CHANGE),
			Port.in("heatingPeriod", PortType.BOOLEAN, "Heating period", "", false, 1, List.of(), InputTrigger.CHANGE),
			Port.in("window", PortType.BOOLEAN, "Window open", "", false, 4, List.of(Aggregation.ANY),
				InputTrigger.CHANGE),
			Port.in("enable", PortType.BOOLEAN, "Heating on", "", false, 1, List.of(), InputTrigger.CHANGE),
			Port.in("summer", PortType.BOOLEAN, "Summer", "", false, 1, List.of(), InputTrigger.CHANGE),
			Port.in("forced", PortType.BOOLEAN, "Forced position", "", false, 1, List.of(), InputTrigger.CHANGE),
			Port.out("valve", PortType.REAL, "Valve", "%", 30, Double.valueOf(0), Double.valueOf(100)),
			Port.out("switch", PortType.BOOLEAN, "Switch", "", 0, null, null),
			Port.out("demand", PortType.BOOLEAN, "Heat demand", "", 0, null, null),
			Port.diagnostic("activeSetpoint", PortType.REAL, "Active setpoint", "°C", null, null),
			Port.diagnostic("state", PortType.ENUMERATION, "State", "", Double.valueOf(0), Double.valueOf(8)),
			Port.diagnostic("p", PortType.REAL, "P term", "%", null, null),
			Port.diagnostic("i", PortType.REAL, "I term", "%", null, null),
			Port.diagnostic("d", PortType.REAL, "D term", "%", null, null),
			Port.diagnostic("error", PortType.REAL, "Control error", "K", null, null),
			Port.diagnostic("filtered", PortType.REAL, "Filtered temperature", "°C", null, null)),
		List.of(
			new Param("system", ParamType.ENUMERATION, "Heating system", "", 0, null, null, 0,
				List.of(new Choice(0, "radiator", "Radiator"), new Choice(1, "underfloor", "Underfloor heating"),
					new Choice(2, "convector", "Convector"), new Choice(3, "custom", "Custom")),
				false),
			new Param("controlType", ParamType.ENUMERATION, "Control", "", 0, null, null, 0,
				List.of(new Choice(0, "continuous", "Continuous"), new Choice(1, "pwm", "PWM"),
					new Choice(2, "twoPoint", "Two-point")),
				false),
			real("xp", "Proportional band", "K", 3, 0.5, 20, 0.1, false),
			real("tn", "Reset time", "min", 90, 1, 600, 1, false),
			real("td", "Derivative time", "min", 0, 0, 60, 1, false),
			real("filter", "Filter time constant", "min", 2, 0, 60, 0.5, false),
			duration("cycle", "PWM cycle", "min", 15 * MINUTE, 5 * MINUTE, 60 * MINUTE, MINUTE),
			real("hysteresis", "Hysteresis", "K", 0.5, 0.1, 5, 0.1, false),
			real("outputMin", "Lowest valve position", "%", 0, 0, 100, 1, false),
			real("outputMax", "Highest valve position", "%", 100, 0, 100, 1, false),
			real("minChange", "Smallest change sent", "%", 2, 0, 50, 1, false),
			duration("resendInterval", "Resend interval", "min", 0, 0, 1440 * MINUTE, MINUTE),
			real("comfort", "Comfort", "°C", 21, 5, 30, 0.5, true),
			real("standby", "Standby", "°C", 19, 5, 30, 0.5, true),
			real("night", "Night", "°C", 17, 5, 30, 0.5, true),
			real("frost", "Frost protection", "°C", 7, 4, 15, 0.5, true),
			duration("windowDelay", "Window delay", "s", MINUTE, 0, 60 * MINUTE, 1000),
			real("boostThreshold", "Boost threshold", "K", 0, 0, 5, 0.1, false),
			duration("boostDuration", "Longest boost", "min", 30 * MINUTE, MINUTE, 180 * MINUTE, MINUTE),
			duration("sensorTimeout", "Sensor timeout", "min", 30 * MINUTE, 0, 1440 * MINUTE, MINUTE),
			real("fallbackPosition", "Valve without sensor", "%", 30, 0, 100, 1, false),
			real("forcedPosition", "Forced valve position", "%", 100, 0, 100, 1, false),
			real("summerPosition", "Valve in summer", "%", 0, 0, 100, 1, false),
			new Param("protectionDay", ParamType.INTEGER, "Valve protection day", "", 0, Double.valueOf(0),
				Double.valueOf(7), 1, List.of(), false),
			new Param("protectionTime", ParamType.TIME_OF_DAY, "Valve protection from", "", 36000, Double.valueOf(0),
				Double.valueOf(86399), 60, List.of(), false),
			duration("protectionDuration", "Valve protection duration", "min", 5 * MINUTE, MINUTE, 30 * MINUTE,
				MINUTE)),
		List.of(new Field("integral", true, 0), new Field("filtered", false, 0), new Field("hasFiltered", false, 0),
			new Field("lastStep", false, 0), new Field("lastSetpoint", false, 0),
			new Field("hasLastSetpoint", false, 0), new Field("lastKp", false, 0), new Field("hasLastKp", false, 0),
			new Field("windowActive", false, 0), new Field("boostUntil", false, 0),
			new Field("protectionUntil", false, 0), new Field("protectedOn", true, 0),
			new Field("cycleStart", false, 0), new Field("hasCycle", false, 0), new Field("lastState", false, 0),
			new Field("sentValve", false, 0), new Field("hasSent", false, 0), new Field("sentAt", false, 0),
			new Field("switchOn", false, 0), new Field("demandOn", false, 0)),
		List.of("window", "pwm"), 60000, 600000);

	private static Param real(String id, String label, String unit, double def, double min, double max, double step,
			boolean adjustable) {
		return new Param(id, ParamType.REAL, label, unit, def, Double.valueOf(min), Double.valueOf(max), step,
			List.of(), adjustable);
	}

	private static Param duration(String id, String label, String unit, double def, double min, double max,
			double step) {
		return new Param(id, ParamType.DURATION, label, unit, def, Double.valueOf(min), Double.valueOf(max), step,
			List.of(), false);
	}

	private RoomController() {
		// The singleton.
	}

	@Override
	public Manifest manifest() {
		return MANIFEST;
	}

	@Override
	public Blame check(double[] params) {
		if (!(params[OUTPUT_MIN] < params[OUTPUT_MAX])) {
			return new Blame("outputMin must be below outputMax", OUTPUT_MIN);
		}
		return null;
	}

	/** The gains of a preset (<code>spec/blocks.md</code> 14.2): xp, tn, td, filter. */
	private static double[] preset(int system) {
		switch (system) {
			case UNDERFLOOR:
				return new double[] { 3, 180, 10, 2 };
			case CONVECTOR:
				return new double[] { 2, 45, 5, 2 };
			default:
				return new double[] { 3, 90, 5, 2 };
		}
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
		s[S_HAS_LAST_SETPOINT] = 0;
		s[S_HAS_CYCLE] = 0;
		s[S_HAS_SENT] = 0;
		s[S_BOOST_UNTIL] = 0;
		if (reason != StartReason.IMAGE) {
			s[S_HAS_FILTERED] = 0;
		}
		s[S_WINDOW_ACTIVE] = cx.read(WINDOW).isTrue() ? 1 : 0;
		cx.cancelTimer(WINDOW_TIMER);
		step(cx);
	}

	@Override
	public void input(BlockContext cx, int port) {
		if (port == WINDOW) {
			double[] s = cx.state();
			if (cx.read(WINDOW).isTrue()) {
				if (s[S_WINDOW_ACTIVE] == 0) {
					double delay = cx.param(WINDOW_DELAY);
					if (delay == 0) {
						s[S_WINDOW_ACTIVE] = 1;
					} else if (!cx.timerRunning(WINDOW_TIMER)) {
						cx.setTimer(WINDOW_TIMER, (long) delay);
					}
				}
			} else {
				s[S_WINDOW_ACTIVE] = 0;
				cx.cancelTimer(WINDOW_TIMER);
			}
		}
		step(cx);
	}

	@Override
	public void timer(BlockContext cx, int timer) {
		double[] s = cx.state();
		if (timer == PWM_TIMER) {
			s[S_SWITCH_ON] = 0;
			cx.write(SWITCH, 0);
			return;
		}
		if (cx.read(WINDOW).isTrue()) {
			s[S_WINDOW_ACTIVE] = 1;
		}
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

		int system = (int) cx.param(SYSTEM);
		double[] g = system == CUSTOM
			? new double[] { cx.param(XP), cx.param(TN), cx.param(TD), cx.param(FILTER) }
			: preset(system);
		final double kp = 100.0 / g[0];
		final double ti = g[1] * 60.0;
		final double td = g[2] * 60.0;
		final double tf = g[3] * 60.0;

		final InputValue temperature = cx.read(TEMPERATURE);
		final double timeout = cx.param(SENSOR_TIMEOUT);
		final boolean ok = temperature.good() && (timeout == 0 || (double) (now - temperature.updatedMs()) <= timeout);
		double x = 0;
		if (ok) {
			x = temperature.value();
			if (s[S_HAS_FILTERED] == 0 || tf == 0) {
				s[S_FILTERED] = x;
				s[S_HAS_FILTERED] = 1;
			} else {
				s[S_FILTERED] = s[S_FILTERED] + (x - s[S_FILTERED]) * (dt / (tf + dt));
			}
		}
		final double f = s[S_FILTERED];

		// The setpoint and the regulating state (spec/blocks.md 14.5).
		final InputValue mode = cx.read(MODE);
		final InputValue enable = cx.read(ENABLE);
		final InputValue external = cx.read(SETPOINT);
		double sp;
		int regState = HEATING;
		if (s[S_WINDOW_ACTIVE] == 1) {
			sp = cx.param(FROST);
			regState = STATE_WINDOW;
		} else if (enable.good() && enable.value() == 0) {
			sp = cx.param(FROST);
			regState = OFF;
		} else if (mode.good() && mode.value() == 4) {
			sp = cx.param(FROST);
			regState = STATE_FROST;
		} else if (external.good()) {
			sp = external.value();
		} else if (mode.good() && (mode.value() == 1 || mode.value() == 2 || mode.value() == 3)) {
			sp = cx.param(mode.value() == 1 ? COMFORT : (mode.value() == 2 ? STANDBY : NIGHT));
		} else {
			final InputValue period = cx.read(HEATING_PERIOD);
			final InputValue presence = cx.read(PRESENCE);
			if (period.good() && period.value() == 0) {
				sp = cx.param(NIGHT);
			} else if (presence.good() && presence.value() == 0) {
				sp = cx.param(STANDBY);
			} else {
				sp = cx.param(COMFORT);
			}
		}

		// Valve protection (the wall clock).
		final double protectionDay = cx.param(PROTECTION_DAY);
		DateTime w = protectionDay > 0 ? cx.wall() : null;
		if (w != null && w.dayOfWeek() == protectionDay
			&& (double) (w.hour() * 3600 + w.minute() * 60 + w.second()) >= cx.param(PROTECTION_TIME)) {
			final double day = Values.daysOf(w.year(), w.month(), w.day());
			if (day != s[S_PROTECTED_ON]) {
				s[S_PROTECTION_UNTIL] = t + cx.param(PROTECTION_DURATION);
				s[S_PROTECTED_ON] = day;
			}
		}

		final int control = (int) cx.param(CONTROL_TYPE);
		final double outMin = cx.param(OUTPUT_MIN);
		final double outMax = cx.param(OUTPUT_MAX);
		double pTerm = 0;
		double dTerm = 0;
		double u;
		int state;
		boolean regulating = false;
		boolean twoPointOn = false;
		if (cx.read(FORCED).isTrue()) {
			state = STATE_FORCED;
			u = cx.param(FORCED_POSITION);
			s[S_BOOST_UNTIL] = 0;
		} else if (t < s[S_PROTECTION_UNTIL]) {
			state = PROTECTION;
			u = 100;
			s[S_BOOST_UNTIL] = 0;
		} else if (cx.read(SUMMER).isTrue()) {
			state = STATE_SUMMER;
			u = cx.param(SUMMER_POSITION);
			s[S_BOOST_UNTIL] = 0;
		} else if (!ok) {
			state = FAULT;
			u = cx.param(FALLBACK_POSITION);
			s[S_BOOST_UNTIL] = 0;
		} else {
			state = regState;
			regulating = true;
			final double e = sp - f;
			if (control == TWO_POINT) {
				final double h = cx.param(HYSTERESIS) / 2.0;
				if (f <= sp - h) {
					twoPointOn = true;
				} else if (f >= sp + h) {
					twoPointOn = false;
				} else {
					twoPointOn = s[S_SWITCH_ON] != 0;
				}
				u = twoPointOn ? outMax : outMin;
				s[S_BOOST_UNTIL] = 0;
			} else {
				final double threshold = cx.param(BOOST_THRESHOLD);
				if (threshold > 0 && s[S_HAS_LAST_SETPOINT] == 1 && sp > s[S_LAST_SETPOINT] && e >= threshold) {
					s[S_BOOST_UNTIL] = t + cx.param(BOOST_DURATION);
				}
				if (s[S_BOOST_UNTIL] > t && e >= threshold / 2.0) {
					state = BOOST;
					u = outMax;
				} else {
					s[S_BOOST_UNTIL] = 0;
					if (s[S_HAS_LAST_KP] == 1 && kp != s[S_LAST_KP]) {
						// Bumpless.
						s[S_INTEGRAL] = s[S_INTEGRAL] + (s[S_LAST_KP] - kp) * e;
					}
					s[S_LAST_KP] = kp;
					s[S_HAS_LAST_KP] = 1;
					pTerm = kp * e;
					if (td > 0 && tf > 0) {
						// On the filtered value: no kick on a setpoint step.
						dTerm = -(kp * td * (x - f) / tf);
					}
					final double v = pTerm + s[S_INTEGRAL] + dTerm;
					if (!((v >= outMax && e > 0) || (v <= outMin && e < 0))) {
						// Conditional integration.
						s[S_INTEGRAL] = s[S_INTEGRAL] + kp * (dt / ti) * e;
					}
					s[S_INTEGRAL] = min(max(s[S_INTEGRAL], outMin), outMax);
					u = min(max(pTerm + s[S_INTEGRAL] + dTerm, outMin), outMax);
				}
			}
		}
		s[S_LAST_SETPOINT] = sp;
		s[S_HAS_LAST_SETPOINT] = 1;
		final double valve = Math.floor(u + 0.5);

		// Outputs (spec/blocks.md 14.4).
		final boolean first = s[S_HAS_SENT] == 0;
		boolean send = first;
		if (!send && valve != s[S_SENT_VALVE]
			&& (Math.abs(valve - s[S_SENT_VALVE]) >= cx.param(MIN_CHANGE) || valve == outMin || valve == outMax
				|| valve == 0 || valve == 100)) {
			send = true;
		}
		final double resend = cx.param(RESEND_INTERVAL);
		if (!send && resend > 0 && t - s[S_SENT_AT] >= resend) {
			send = true;
		}
		if (send) {
			cx.write(VALVE, valve);
			s[S_SENT_VALVE] = valve;
			s[S_SENT_AT] = t;
			s[S_HAS_SENT] = 1;
		}

		boolean on;
		if (control == PWM) {
			final double cycle = cx.param(CYCLE);
			if (s[S_HAS_CYCLE] == 0 || t - s[S_CYCLE_START] >= cycle || state != s[S_LAST_STATE]) {
				s[S_CYCLE_START] = t;
				s[S_HAS_CYCLE] = 1;
				final double onTime = (cycle * valve) / 100.0;
				on = onTime > 0;
				if (on && onTime < cycle) {
					cx.setTimer(PWM_TIMER, (long) Math.floor(onTime));
				} else {
					cx.cancelTimer(PWM_TIMER);
				}
			} else {
				on = s[S_SWITCH_ON] != 0;
			}
		} else if (control == TWO_POINT && regulating) {
			on = twoPointOn;
		} else {
			on = valve > 0;
		}
		if (first || on != (s[S_SWITCH_ON] != 0)) {
			cx.write(SWITCH, on ? 1 : 0);
		}
		s[S_SWITCH_ON] = on ? 1 : 0;

		final boolean demand = valve > 0 && state != STATE_FORCED && state != PROTECTION && state != STATE_SUMMER;
		if (first || demand != (s[S_DEMAND_ON] != 0)) {
			cx.write(DEMAND, demand ? 1 : 0);
		}
		s[S_DEMAND_ON] = demand ? 1 : 0;

		cx.write(ACTIVE_SETPOINT, sp);
		cx.write(STATE, state);
		cx.write(P, pTerm);
		cx.write(I, s[S_INTEGRAL]);
		cx.write(D, dTerm);
		cx.write(ERROR, s[S_HAS_FILTERED] != 0 ? sp - f : 0);
		cx.write(FILTERED, f);
		s[S_LAST_STATE] = state;
	}

}
