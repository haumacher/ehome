/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.blocks;

import java.util.ArrayList;
import java.util.List;

import de.haumacher.ehome.blocks.BlockContext.InputValue;
import de.haumacher.ehome.blocks.Manifest.Choice;
import de.haumacher.ehome.blocks.Manifest.Field;
import de.haumacher.ehome.blocks.Manifest.InputTrigger;
import de.haumacher.ehome.blocks.Manifest.Param;
import de.haumacher.ehome.blocks.Manifest.ParamType;
import de.haumacher.ehome.blocks.Manifest.Port;
import de.haumacher.ehome.blocks.Manifest.PortType;
import de.haumacher.ehome.rules.DatapointModel.Quality;
import de.haumacher.ehome.rules.Val.DateTime;
import de.haumacher.ehome.rules.Values;

/**
 * The PV surplus control <code>org.homecontrol.pv-surplus</code> 1.0 (homecontrol
 * <code>spec/blocks.md</code> 20): up to two switched or modulated loads in the order of their priority,
 * run from the photovoltaic system's surplus with a hysteresis, minimum times and a daily maximum,
 * forced by a deadline, behind a home battery up to a state of charge; a chain of instances for more
 * loads. A literal port of homecontrol <code>core/src/blocks/pv_surplus.cpp</code>, in IEEE 754
 * doubles in the order the specification writes it (<code>spec/blocks.md</code> 13).
 */
public final class PvSurplus extends BlockType {

	/** The type id. */
	public static final String TYPE = "org.homecontrol.pv-surplus";

	/** The one instance. */
	public static final PvSurplus INSTANCE = new PvSurplus();

	/** The loads of an instance. */
	public static final int LOADS = 2;

	// Ports, in manifest order: the inputs, then per load its four outputs, then loadPower, surplus.

	private static final int GRID_POWER = 0;

	private static final int PV_POWER = 1;

	private static final int HOUSE_POWER = 2;

	private static final int BATTERY_POWER = 3;

	private static final int BATTERY_SOC = 4;

	private static final int LOWER_POWER = 5;

	private static final int ENABLE = 6;

	/** Port <code>ready1</code>; <code>ready2</code> follows. */
	private static final int READY1 = 7;

	/** Port <code>switch1</code>; per load k (0-based) <code>switch</code>, <code>setpoint</code>, <code>state</code>, <code>energy</code> at 4k on. */
	public static final int SWITCH1 = READY1 + LOADS;

	/** Port <code>loadPower</code>. */
	public static final int LOAD_POWER = SWITCH1 + 4 * LOADS;

	/** Port <code>surplus</code>. */
	public static final int SURPLUS = LOAD_POWER + 1;

	// Parameters, in manifest order: the common ones, then per load its eight.

	private static final int GRID_SIGN = 0;

	private static final int BATTERY_SIGN = 1;

	private static final int SURPLUS_FILTER = 2;

	private static final int ON_MARGIN = 3;

	private static final int ALLOWED_IMPORT = 4;

	private static final int BATTERY_PRIORITY = 5;

	private static final int METER_TIMEOUT = 6;

	private static final int SETPOINT_UNIT = 7;

	/** <code>power1</code>; per load <code>power</code>, <code>minPower</code>, <code>step</code>, <code>minOn</code>, <code>minOff</code>, <code>maxPerDay</code>, <code>mustRunBy</code>, <code>mustRunFor</code>. */
	private static final int POWER1 = 8;

	private static final int PARAMS_PER_LOAD = 8;

	// State fields, in manifest order: the common ones, then per load its four.

	private static final int S_LAST_STEP = 0;

	/** State field <code>filtered</code>. */
	public static final int S_FILTERED = 1;

	private static final int S_HAS_FILTERED = 2;

	/** State field <code>day</code>. */
	public static final int S_DAY = 3;

	private static final int S_DAY_START = 4;

	private static final int S_HAS_SENT = 5;

	private static final int S_SENT_LOAD_POWER = 6;

	/** State field <code>level1</code>; per load <code>level</code>, <code>switchedAt</code>, <code>run</code>, <code>energy</code>. */
	public static final int S_LEVEL1 = 7;

	private static final int FIELDS_PER_LOAD = 4;

	// The values of state<k>.

	private static final int OFF = 0;

	private static final int WAITING = 1;

	private static final int RUNNING = 2;

	private static final int FORCED = 3;

	private static final int BLOCKED = 4;

	private static final double MINUTE = 60000;

	private static final double DAY_MS = 86400000;

	private static final Manifest MANIFEST = new Manifest(TYPE, 1, 0, "PV surplus control",
		"Up to two loads in the order of their priority from the PV surplus, behind the battery; forced by a deadline",
		ports(), params(), fields(), List.of(), 30000, 600000);

	private static List<Port> ports() {
		List<Port> ports = new ArrayList<>();
		ports.add(in("gridPower", PortType.REAL, "Grid power", "W"));
		ports.add(in("pvPower", PortType.REAL, "PV power", "W"));
		ports.add(in("housePower", PortType.REAL, "House consumption", "W"));
		ports.add(in("batteryPower", PortType.REAL, "Battery power", "W"));
		ports.add(in("batterySoc", PortType.REAL, "Battery state of charge", "%"));
		ports.add(in("lowerPower", PortType.REAL, "Power of the lower loads", "W"));
		ports.add(in("enable", PortType.BOOLEAN, "Surplus control on", ""));
		for (int k = 1; k <= LOADS; k++) {
			ports.add(in("ready" + k, PortType.BOOLEAN, "Load " + k + " ready", ""));
		}
		for (int k = 1; k <= LOADS; k++) {
			ports.add(Port.out("switch" + k, PortType.BOOLEAN, "Load " + k + " on", "", 0, null, null));
			ports.add(Port.out("setpoint" + k, PortType.REAL, "Load " + k + " setpoint", "W or %", 0, Double.valueOf(0),
				null));
			ports.add(Port.diagnostic("state" + k, PortType.ENUMERATION, "Load " + k + " state", "", Double.valueOf(0),
				Double.valueOf(4)));
			ports.add(Port.diagnostic("energy" + k, PortType.REAL, "Load " + k + " energy today", "Wh", null, null));
		}
		ports.add(Port.out("loadPower", PortType.REAL, "Power of the loads", "W", 0, null, null));
		ports.add(Port.diagnostic("surplus", PortType.REAL, "Surplus left", "W", null, null));
		return List.copyOf(ports);
	}

	/** An input, read at the period (trigger none, D4). */
	private static Port in(String id, PortType type, String label, String unit) {
		return Port.in(id, type, label, unit, false, 1, List.of(), InputTrigger.NONE);
	}

	private static List<Param> params() {
		List<Param> params = new ArrayList<>();
		params.add(new Param("gridSign", ParamType.ENUMERATION, "Grid meter's sign", "", 0, null, null, 0,
			List.of(new Choice(0, "importPositive", "Import positive"), new Choice(1, "exportPositive", "Export positive")),
			false));
		params.add(new Param("batterySign", ParamType.ENUMERATION, "Battery's sign", "", 0, null, null, 0,
			List.of(new Choice(0, "chargePositive", "Charging positive"),
				new Choice(1, "dischargePositive", "Discharging positive")),
			false));
		params.add(new Param("surplusFilter", ParamType.DURATION, "Surplus filter", "s", 120000, Double.valueOf(0),
			Double.valueOf(1800000), 10000, List.of(), false));
		params.add(watt("onMargin", "Switch-on margin", 100, 10000));
		params.add(watt("allowedImport", "Allowed import", 100, 10000));
		params.add(new Param("batteryPriority", ParamType.REAL, "Battery first below", "%", 100, Double.valueOf(0),
			Double.valueOf(100), 1, List.of(), true));
		params.add(new Param("meterTimeout", ParamType.DURATION, "Meter timeout", "s", 300000, Double.valueOf(10000),
			Double.valueOf(3600000), 10000, List.of(), false));
		params.add(new Param("setpointUnit", ParamType.ENUMERATION, "Setpoint unit", "", 0, null, null, 0,
			List.of(new Choice(0, "watt", "W"), new Choice(1, "percent", "% of the power")), false));
		for (int k = 1; k <= LOADS; k++) {
			params.add(loadWatt("power" + k, "Load " + k + " power"));
			params.add(loadWatt("minPower" + k, "Load " + k + " lowest power"));
			params.add(loadWatt("step" + k, "Load " + k + " step"));
			params.add(hours("minOn" + k, "Load " + k + " minimum on time", 5 * MINUTE, false));
			params.add(hours("minOff" + k, "Load " + k + " minimum off time", 5 * MINUTE, false));
			params.add(hours("maxPerDay" + k, "Load " + k + " longest runtime per day", 0, false));
			params.add(new Param("mustRunBy" + k, ParamType.TIME_OF_DAY, "Load " + k + " must have run by", "", 64800,
				Double.valueOf(0), Double.valueOf(86399), 60, List.of(), true));
			params.add(hours("mustRunFor" + k, "Load " + k + " must have run for", 0, true));
		}
		return List.copyOf(params);
	}

	/** A real parameter in W. */
	private static Param watt(String id, String label, double def, double max) {
		return new Param(id, ParamType.REAL, label, "W", def, Double.valueOf(0), Double.valueOf(max), 10, List.of(),
			false);
	}

	/** A load's power: whole W (D6). */
	private static Param loadWatt(String id, String label) {
		return new Param(id, ParamType.INTEGER, label, "W", 0, Double.valueOf(0), Double.valueOf(100000), 10, List.of(),
			false);
	}

	/** A duration of 0 .. 24 h. */
	private static Param hours(String id, String label, double def, boolean adjustable) {
		return new Param(id, ParamType.DURATION, label, "min", def, Double.valueOf(0), Double.valueOf(DAY_MS), MINUTE,
			List.of(), adjustable);
	}

	private static List<Field> fields() {
		List<Field> fields = new ArrayList<>(List.of(new Field("lastStep", false, 0), new Field("filtered", false, 0),
			new Field("hasFiltered", false, 0), new Field("day", true, -1), new Field("dayStart", false, 0),
			new Field("hasSent", false, 0), new Field("sentLoadPower", false, 0)));
		for (int k = 1; k <= LOADS; k++) {
			fields.add(new Field("level" + k, false, 0));
			fields.add(new Field("switchedAt" + k, false, -1));
			fields.add(new Field("run" + k, true, 0));
			fields.add(new Field("energy" + k, true, 0));
		}
		return List.copyOf(fields);
	}

	private PvSurplus() {
		// The singleton.
	}

	@Override
	public Manifest manifest() {
		return MANIFEST;
	}

	@Override
	public Blame check(double[] params) {
		for (int k = 0; k < LOADS; k++) {
			int base = POWER1 + PARAMS_PER_LOAD * k;
			if (params[base + 2] > 0 && params[base] > 0 && !(params[base + 1] > 0 && params[base + 1] <= params[base])) {
				return new Blame("a modulated load's minPower must be above 0 and at most its power",
					I18NConstants.BLOCK_MODULATED_MIN_POWER, base + 1);
			}
			if (params[base + 5] > 0 && !(params[base + 7] <= params[base + 5])) {
				return new Blame("mustRunFor must be at most maxPerDay",
					I18NConstants.BLOCK_PARAM_AT_MOST__LOW_HIGH.fill("mustRunFor", "maxPerDay"), base + 7);
			}
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
		double now = cx.now();
		if (!(restored && (reason == StartReason.IMAGE || reason == StartReason.ENABLE))) {
			// A record from flash: its monotonic times are gone (D11).
			s[S_LAST_STEP] = now;
			s[S_DAY_START] = now;
		}
		s[S_HAS_SENT] = 0;
		s[S_HAS_FILTERED] = 0;
		if (reason == StartReason.ENABLE) {
			// The failsafe switched the loads off (D7).
			for (int k = 0; k < LOADS; k++) {
				s[S_LEVEL1 + FIELDS_PER_LOAD * k] = 0;
				s[S_LEVEL1 + FIELDS_PER_LOAD * k + 1] = -1;
			}
		}
		step(cx);
	}

	@Override
	public void input(BlockContext cx, int port) {
		// Every input has the trigger none (D4).
	}

	@Override
	public void timer(BlockContext cx, int timer) {
		// No timers.
	}

	@Override
	public void period(BlockContext cx) {
		step(cx);
	}

	@Override
	public void parameter(BlockContext cx, int index) {
		// A parameter takes effect at the next period (D4).
	}

	private static double param(BlockContext cx, int k, int j) {
		return cx.param(POWER1 + PARAMS_PER_LOAD * k + j);
	}

	private static int field(int k, int j) {
		return S_LEVEL1 + FIELDS_PER_LOAD * k + j;
	}

	/** The lowest level of load k. */
	private static double lo(BlockContext cx, int k) {
		return param(cx, k, 2) == 0 ? param(cx, k, 0) : param(cx, k, 1);
	}

	/** The highest level of load k. */
	private static double top(BlockContext cx, int k) {
		double power = param(cx, k, 0);
		double minPower = param(cx, k, 1);
		double step = param(cx, k, 2);
		return step == 0 ? power : minPower + step * Math.floor((power - minPower) / step);
	}

	/** The highest level at or below y, 0 if none (20.5 <code>fit</code>). */
	private static double fit(BlockContext cx, int k, double y) {
		double power = param(cx, k, 0);
		double minPower = param(cx, k, 1);
		double step = param(cx, k, 2);
		if (step == 0) {
			return y >= power ? power : 0;
		}
		if (y < minPower) {
			return 0;
		}
		return min(minPower + step * Math.floor((y - minPower) / step), top(cx, k));
	}

	/** The minimum times (20.5 <code>held</code>). */
	private static double held(BlockContext cx, double[] s, int k, double want, double t) {
		double c = s[field(k, 0)];
		double at = s[field(k, 1)];
		if (c == 0 && want > 0 && at >= 0 && t - at < param(cx, k, 4)) {
			return 0;
		}
		if (c > 0 && want == 0 && at >= 0 && t - at < param(cx, k, 3)) {
			return min(c, lo(cx, k));
		}
		return want;
	}

	private static void restartDay(double[] s) {
		for (int k = 0; k < LOADS; k++) {
			s[field(k, 2)] = 0;
			s[field(k, 3)] = 0;
		}
	}

	private static void step(BlockContext cx) {
		double[] s = cx.state();
		final double t = cx.now();

		// D11: the interval just ended.
		final double dt = (t - s[S_LAST_STEP]) / 1000.0;
		for (int k = 0; k < LOADS; k++) {
			double level = s[field(k, 0)];
			if (level > 0) {
				s[field(k, 2)] = s[field(k, 2)] + (t - s[S_LAST_STEP]);
				s[field(k, 3)] = s[field(k, 3)] + level * (dt / 3600.0);
			}
		}
		s[S_LAST_STEP] = t;
		DateTime w = cx.wall();
		if (w != null) {
			double day = Values.daysOf(w.year(), w.month(), w.day());
			if (s[S_DAY] < 0) {
				s[S_DAY] = day;
			} else if (day != s[S_DAY]) {
				restartDay(s);
				s[S_DAY] = day;
				s[S_DAY_START] = t;
			}
		} else if (t - s[S_DAY_START] >= DAY_MS) {
			restartDay(s);
			s[S_DAY] = -1;
			s[S_DAY_START] = t;
		}

		// D3, D8: the meters.
		InputValue g = cx.read(GRID_POWER);
		InputValue p = cx.read(PV_POWER);
		InputValue h = cx.read(HOUSE_POWER);
		InputValue b = cx.read(BATTERY_POWER);
		List<InputValue> meters = new ArrayList<>(3);
		if (g.bound()) {
			meters.add(g);
		} else if (p.bound() && h.bound()) {
			meters.add(p);
			meters.add(h);
		}
		if (!meters.isEmpty() && b.bound()) {
			meters.add(b);
		}
		double timeout = cx.param(METER_TIMEOUT);
		boolean measured = !meters.isEmpty();
		boolean lost = meters.isEmpty();
		for (InputValue m : meters) {
			boolean fresh = t - m.updatedMs() <= timeout;
			if (!(m.good() && fresh)) {
				measured = false;
			}
			if (m.quality() == Quality.UNKNOWN || !fresh) {
				lost = true;
			}
		}
		InputValue l = cx.read(LOWER_POWER);
		double low = l.quality() != Quality.UNKNOWN ? l.value() : 0;
		if (lost) {
			s[S_HAS_FILTERED] = 0;
		}
		if (measured) {
			double export = g.bound() ? (cx.param(GRID_SIGN) == 0 ? -g.value() : g.value()) : p.value() - h.value();
			double battery = 0;
			if (b.bound()) {
				// D9
				double bc = cx.param(BATTERY_SIGN) == 0 ? b.value() : -b.value();
				if (!g.bound()) {
					export = export - bc;
				}
				InputValue soc = cx.read(BATTERY_SOC);
				boolean first = soc.good() ? soc.value() < cx.param(BATTERY_PRIORITY) : cx.param(BATTERY_PRIORITY) > 0;
				battery = first ? min(bc, 0.0) : bc;
			}
			// D2: the sample.
			double x = export + battery + s[field(0, 0)] + s[field(1, 0)] + low;
			double tf = cx.param(SURPLUS_FILTER) / 1000.0;
			if (s[S_HAS_FILTERED] == 0 || tf == 0) {
				s[S_FILTERED] = x;
				s[S_HAS_FILTERED] = 1;
			} else {
				s[S_FILTERED] = s[S_FILTERED] + (x - s[S_FILTERED]) * (dt / (tf + dt));
			}
		}

		// The allocation.
		InputValue e = cx.read(ENABLE);
		boolean enabled = !(e.good() && e.value() == 0);
		// A lost surplus has cleared it.
		boolean known = s[S_HAS_FILTERED] == 1;
		boolean[] allowed = new boolean[LOADS];
		boolean[] blocked = new boolean[LOADS];
		boolean[] forced = new boolean[LOADS];
		double[] levels = new double[LOADS];
		for (int k = 0; k < LOADS; k++) {
			InputValue r = cx.read(READY1 + k);
			allowed[k] = param(cx, k, 0) > 0 && enabled && (!r.bound() || (r.good() && r.value() != 0));
			double maxPerDay = param(cx, k, 5);
			blocked[k] = maxPerDay > 0 && s[field(k, 2)] >= maxPerDay;
			double mustRunFor = param(cx, k, 7);
			double need = mustRunFor - s[field(k, 2)];
			forced[k] = allowed[k] && !blocked[k] && mustRunFor > 0 && need > 0 && w != null
				&& w.hour() * 3600 + w.minute() * 60 + w.second() + need / 1000.0 >= param(cx, k, 6);
		}
		double budget = s[S_FILTERED];
		for (int k = 0; k < LOADS; k++) {
			// Forced loads first (D10).
			if (forced[k]) {
				levels[k] = held(cx, s, k, top(cx, k), t);
				budget = budget - levels[k];
			}
		}
		for (int k = 0; k < LOADS; k++) {
			// Then by priority (D2).
			if (forced[k]) {
				continue;
			}
			double want = 0;
			if (allowed[k] && !blocked[k] && known) {
				double up = measured ? fit(cx, k, budget - cx.param(ON_MARGIN)) : 0;
				double c = s[field(k, 0)];
				double down = c > 0 ? fit(cx, k, min(c, budget + cx.param(ALLOWED_IMPORT))) : 0;
				want = max(up, down);
			}
			levels[k] = held(cx, s, k, want, t);
			budget = budget - levels[k];
		}

		// D14
		boolean first = s[S_HAS_SENT] == 0;
		boolean percent = cx.param(SETPOINT_UNIT) != 0;
		for (int k = 0; k < LOADS; k++) {
			double level = levels[k];
			boolean changed = level != s[field(k, 0)];
			if ((s[field(k, 0)] > 0) != (level > 0)) {
				s[field(k, 1)] = t;
			}
			s[field(k, 0)] = level;
			int state = level > 0 ? (forced[k] ? FORCED : RUNNING) : !allowed[k] ? OFF : blocked[k] ? BLOCKED : WAITING;
			int port = SWITCH1 + 4 * k;
			if (first || changed) {
				cx.write(port, level > 0 ? 1 : 0);
				double power = param(cx, k, 0);
				cx.write(port + 1, !percent ? level : power > 0 ? (level / power) * 100.0 : 0);
			}
			cx.write(port + 2, state);
			cx.write(port + 3, s[field(k, 3)]);
		}
		double loadPower = s[field(0, 0)] + s[field(1, 0)] + low;
		if (first || loadPower != s[S_SENT_LOAD_POWER]) {
			cx.write(LOAD_POWER, loadPower);
			s[S_SENT_LOAD_POWER] = loadPower;
		}
		if (s[S_HAS_FILTERED] == 1) {
			// What the loads leave: the next instance's meter (20.3).
			cx.write(SURPLUS, s[S_FILTERED] - loadPower);
		}
		s[S_HAS_SENT] = 1;
	}

}
