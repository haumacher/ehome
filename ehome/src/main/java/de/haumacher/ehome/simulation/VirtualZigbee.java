/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.simulation;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import de.haumacher.ehome.rules.DatapointModel;
import de.haumacher.ehome.rules.DatapointModel.Datapoint;
import de.haumacher.ehome.rules.DatapointModel.Quality;
import de.haumacher.ehome.rules.Type;
import de.haumacher.ehome.rules.Val;
import de.haumacher.ehome.rules.Values;
import de.haumacher.ehome.simulation.Simulation.Kind;
import de.haumacher.ehome.zigbee.ZigbeeFunctionTable;

/**
 * The virtual Zigbee devices of a {@link Simulation}: every datapoint of the runtime image with a
 * <code>zigbee</code> member stands for one function of one endpoint of a device, and the device
 * behaves towards the {@link DatapointModel} as the controller's Zigbee adapter sees a real one
 * (homecontrol <code>core/src/zigbee/adapter.cpp</code>, <code>doc/zigbee.md</code> "Datapoints").
 *
 * <ul>
 * <li><b>Reports</b> ({@link #report(String, Val, boolean)}): the user makes the device report a value
 * (a sensor's reading, a button press as an <code>action</code>). The value is coded as the device
 * codes its attribute and decoded as the controller decodes it ({@link #normalize(String, Val)}: a
 * level in steps of 1/254, a temperature in hundredths of a degree, ...); a value outside what the
 * function can carry is refused with the range. Every report is an update of the datapoint (origin
 * report), also of the same value as before: a rule's <code>update</code> trigger fires for each
 * press of a button, a <code>change</code> trigger only when the value changes
 * (<code>spec/rules.md</code> 2.3), as on the controller.</li>
 * <li><b>Commands</b> ({@link #command(Datapoint, Val)}): a function with a command cluster (the
 * binding's <code>command</code>: <code>on_off</code>, <code>level</code>,
 * <code>color_temperature</code>) takes the value in the controller's range and reports it back
 * after the cascade, as the real device's attribute report follows the command; any other is
 * refused (<code>read-only</code>), with the reason in the timeline.</li>
 * <li><b>Reads</b> ({@link #read(Datapoint)}, <code>spec/rules.md</code> 7.12): the device reports
 * its current value after the cascade; one that has none yet leaves the datapoint unknown and says so
 * in the timeline. An <code>action</code> or a <code>multistate</code> input has no attribute to read
 * and refuses, as the controller does.</li>
 * <li><b>Start</b> ({@link #startReports()}): the controller reads the server functions but the
 * multistate input at start; the virtual device reports the values it held before the start (set by
 * {@link #report(String, Val, boolean)} before it), the others stay unknown.</li>
 * </ul>
 *
 * <p>
 * The reports are queued and delivered by {@link #deliver()} outside the rules' cascades, each an
 * external event of its own. Not thread-safe: called under the lock of the simulation.
 * </p>
 */
public final class VirtualZigbee implements DatapointModel.Technology {

	/** The names of the values of an <code>action</code>. */
	private static final List<String> ACTIONS = List.of("off", "on", "toggle", "dim up", "dim down", "dim stop");

	/** The largest attribute value of an illuminance (0xFFFF is invalid). */
	private static final int MAX_ILLUMINANCE = 0xFFFE;

	/**
	 * Where the devices write to the timeline.
	 */
	public interface Log {

		/**
		 * An entry of the timeline.
		 */
		void log(Kind kind, String source, String text);
	}

	/**
	 * The binding of a datapoint to a function of a device (the <code>zigbee</code> member of a
	 * datapoint of the image).
	 *
	 * @param datapoint
	 *        The id of the datapoint.
	 * @param ieee
	 *        The device's IEEE address, 16 lower-case hex digits.
	 * @param endpoint
	 *        The endpoint.
	 * @param cluster
	 *        The cluster.
	 * @param function
	 *        The key of the function (<code>occupancy</code>, <code>action</code>).
	 * @param command
	 *        The cluster its commands go to, <code>null</code> for a read-only function.
	 */
	public record Point(String datapoint, String ieee, int endpoint, int cluster, String function, Integer command) {

		/** Whether a command can be sent to the function. */
		public boolean commandable() {
			return command != null;
		}

		/**
		 * Whether the function has an attribute value the controller reads (at start and for a rule's
		 * read): a server function but the multistate input.
		 */
		public boolean readable() {
			ZigbeeFunctionTable.Info info = ZigbeeFunctionTable.byKey(function);
			return info != null && info.readOnStart();
		}

		/** Whether it is the commands of a button (the function <code>action</code>). */
		public boolean action() {
			return ZigbeeFunctionTable.ACTION.equals(function);
		}

		/** The device and endpoint, as the source of a timeline entry: <code>zigbee.&lt;ieee&gt;.&lt;endpoint&gt;</code>. */
		public String source() {
			return "zigbee." + ieee + "." + endpoint;
		}
	}

	/**
	 * A value as the function carries it, or why it cannot.
	 *
	 * @param value
	 *        The value as the controller decodes it from the device, <code>null</code> if refused.
	 * @param refusal
	 *        Why the function cannot carry the value, <code>null</code> if it can.
	 */
	public record Normalized(Val value, String refusal) {
		// A value.
	}

	private record Pending(Point point, Val value, boolean read) {
		// A report (or the answer to a read) waiting for the end of the cascade.
	}

	private final DatapointModel _model;

	private final Log _log;

	private final Map<String, Point> _points = new LinkedHashMap<>();

	/** The value of each function's attribute in the device; absent while the device has none. */
	private final Map<String, Val> _values = new HashMap<>();

	/** The simulated time of the last report of each datapoint. */
	private final Map<String, Long> _reported = new HashMap<>();

	private final Deque<Pending> _pending = new ArrayDeque<>();

	/**
	 * Creates virtual devices without functions.
	 */
	public VirtualZigbee(DatapointModel model, Log log) {
		_model = model;
		_log = log;
	}

	/**
	 * Reads the <code>zigbee</code> member of a datapoint of a runtime image (checked by the image
	 * validator).
	 *
	 * @return The binding, <code>null</code> if it names no function of the controller's table.
	 */
	public static Point point(String id, Map<?, ?> dp) {
		Map<?, ?> zigbee = (Map<?, ?>) dp.get("zigbee");
		String ieee = ZigbeeFunctionTable.normalizeIeee(String.valueOf(zigbee.get("ieee")));
		int endpoint = number(zigbee.get("endpoint"));
		int cluster = number(zigbee.get("cluster"));
		ZigbeeFunctionTable.Info info;
		if (Boolean.TRUE.equals(zigbee.get("client"))) {
			info = ZigbeeFunctionTable.byKey(ZigbeeFunctionTable.ACTION);
		} else {
			info = ZigbeeFunctionTable.byAttribute(cluster, number(zigbee.get("attribute")));
		}
		if (ieee == null || info == null) {
			return null;
		}
		Integer command = zigbee.get("command") instanceof Number n ? Integer.valueOf(n.intValue()) : null;
		return new Point(id, ieee, endpoint, cluster, info.key(), command);
	}

	private static int number(Object value) {
		return value instanceof Number n ? n.intValue() : -1;
	}

	/**
	 * Binds a datapoint of the model (added with this technology as its owner).
	 */
	public void bind(Point point) {
		_points.put(point.datapoint(), point);
	}

	/**
	 * The binding of the datapoint, <code>null</code> if it is no Zigbee datapoint.
	 */
	public Point point(String datapoint) {
		return _points.get(datapoint);
	}

	/**
	 * The bindings in the order of the image.
	 */
	public List<Point> points() {
		return List.copyOf(_points.values());
	}

	/**
	 * The value the device holds for the function of the datapoint, <code>null</code> if none (an
	 * <code>action</code> holds none: a press is no state).
	 */
	public Val deviceValue(String datapoint) {
		return _values.get(datapoint);
	}

	/**
	 * The values the devices hold, by datapoint (to carry them into the next simulation of the
	 * project, as real devices keep their state while the controller restarts).
	 */
	public Map<String, Val> deviceValues() {
		return Map.copyOf(_values);
	}

	/**
	 * The simulated time of the last report of the datapoint, <code>null</code> if none.
	 */
	public Long reported(String datapoint) {
		return _reported.get(datapoint);
	}

	/**
	 * The device reports a value of the datapoint's function: a reading, a state, a press of a button.
	 *
	 * @param started
	 *        Whether the simulation runs; before it, the device only takes the value and reports it
	 *        when the controller reads it at the start ({@link #startReports()}).
	 * @return <code>null</code>, or why the value is refused.
	 */
	public String report(String datapoint, Val value, boolean started) {
		Point point = _points.get(datapoint);
		if (point == null) {
			return "'" + datapoint + "' is not bound to a Zigbee device";
		}
		Normalized normalized = normalize(point.function(), value);
		if (normalized.refusal() != null) {
			return normalized.refusal();
		}
		if (point.action()) {
			if (!started) {
				return "a press of a button is no state the device keeps; press it while the simulation runs";
			}
		} else {
			_values.put(datapoint, normalized.value());
		}
		if (started) {
			_pending.add(new Pending(point, normalized.value(), false));
		}
		return null;
	}

	/**
	 * The reads of the start: every readable function reports the value its device holds; the
	 * others stay unknown.
	 */
	public void startReports() {
		for (Point point : _points.values()) {
			Val value = _values.get(point.datapoint());
			if (point.readable() && value != null) {
				_pending.add(new Pending(point, value, false));
			}
		}
	}

	@Override
	public boolean command(Datapoint datapoint, Val value) {
		Point point = _points.get(datapoint.name());
		if (point == null) {
			_log.log(Kind.PROBLEM, datapoint.name(), "command of '" + datapoint.name() + "' refused: not a Zigbee datapoint");
			return false;
		}
		if (!point.commandable()) {
			_log.log(Kind.PROBLEM, point.source(), "command of '" + datapoint.name() + "' refused: read-only (the "
				+ point.function() + " of the device takes no command)");
			return false;
		}
		Normalized normalized = normalize(point.function(), value);
		if (normalized.refusal() != null) {
			_log.log(Kind.PROBLEM, point.source(), "command of '" + datapoint.name() + "' refused: " + normalized.refusal());
			return false;
		}
		_log.log(Kind.ZIGBEE_COMMAND, point.source(), point.function() + " = " + Values.format(value) + " to cluster "
			+ point.command() + " (" + datapoint.name() + ")");
		_values.put(point.datapoint(), normalized.value());
		_pending.add(new Pending(point, normalized.value(), false));
		return true;
	}

	@Override
	public boolean read(Datapoint datapoint) {
		Point point = _points.get(datapoint.name());
		if (point == null || !point.readable()) {
			_log.log(Kind.PROBLEM, point == null ? datapoint.name() : point.source(), "read of '" + datapoint.name()
				+ "' refused: " + (point == null ? "not a Zigbee datapoint"
					: point.function() + " has no attribute value to read"));
			return false;
		}
		_pending.add(new Pending(point, null, true));
		return true;
	}

	/**
	 * Whether reports wait for {@link #deliver()}.
	 */
	public boolean hasPending() {
		return !_pending.isEmpty();
	}

	/**
	 * Delivers the waiting reports to the model; each is an external event of the rules.
	 */
	public void deliver() {
		while (!_pending.isEmpty()) {
			Pending pending = _pending.poll();
			Point point = pending.point();
			Val value = pending.read() ? _values.get(point.datapoint()) : pending.value();
			if (value == null) {
				boolean unknown = _model.get(point.datapoint()).quality() == Quality.UNKNOWN;
				_log.log(Kind.PROBLEM, point.source(), "read of '" + point.datapoint()
					+ "': the virtual device has no value yet (report one in the simulation); the datapoint stays "
					+ (unknown ? "unknown" : "as it is"));
				continue;
			}
			Datapoint datapoint = _model.get(point.datapoint());
			Val converted = value.type() == datapoint.type().expressionType() ? value
				: Values.convertForDatapoint(value, datapoint.type());
			if (converted == null) {
				_log.log(Kind.PROBLEM, point.source(), point.function() + " = " + Values.format(value) + " is no "
					+ datapoint.type().externalName() + " value; '" + point.datapoint() + "' unchanged");
				continue;
			}
			_model.report(point.datapoint(), converted);
			_reported.put(point.datapoint(), Long.valueOf(datapoint.updatedMs()));
			if (point.action()) {
				_log.log(Kind.ZIGBEE_ACTION, point.source(), "action " + actionText(value) + " (" + point.datapoint() + ")");
			} else {
				_log.log(Kind.ZIGBEE_REPORT, point.source(), point.function() + " = " + Values.format(value)
					+ (pending.read() ? " (read, " : " (") + point.datapoint() + ")");
			}
		}
	}

	/**
	 * The value of an <code>action</code> with its name, e.g. <code>1 (on)</code>.
	 */
	public static String actionText(Val value) {
		long code = value.integer();
		return code + (code >= 0 && code < ACTIONS.size() ? " (" + ACTIONS.get((int) code) + ")" : "");
	}

	/**
	 * The value as the function carries it: coded as the device codes its attribute and decoded as
	 * the controller decodes it (homecontrol <code>attribute_value()</code>, the command's coding in
	 * <code>ZigbeeAdapter::command()</code>), or why the function cannot carry it.
	 *
	 * @param function
	 *        The key of the function.
	 * @param value
	 *        The value, of the datapoint's type.
	 */
	public static Normalized normalize(String function, Val value) {
		if (value == null || !value.has()) {
			return refused("no value");
		}
		switch (function) {
			case "on_off":
			case "occupancy":
			case "ias_zone":
				return value.type() == Type.BOOLEAN ? new Normalized(value, null)
					: refused("the " + function + " takes true or false");
			default:
				break;
		}
		if (!isNumber(value)) {
			return refused("the " + function + " takes a number");
		}
		double d = value.type() == Type.REAL ? value.real() : value.integer();
		switch (function) {
			case "level": {
				if (!(d >= 0 && d <= 100)) {
					return refused("a level takes 0..100 %");
				}
				long n = lround(d * 254.0 / 100.0);
				return real(round1(n * 100.0 / 254.0));
			}
			case "color_temperature": {
				if (!(d >= 16 && d <= 1_000_000)) {
					return refused("a colour temperature takes 16..1000000 K");
				}
				long mireds = lround(1e6 / lround(d));
				return new Normalized(Val.ofInt(lround(1e6 / mireds)), null);
			}
			case "temperature": {
				long n = lround(d * 100);
				if (n < -0x7FFF || n > 0x7FFF) {
					return refused("a temperature takes -327.67..327.67 °C");
				}
				return real(n / 100.0);
			}
			case "humidity": {
				if (!(d >= 0 && d <= 100)) {
					return refused("a humidity takes 0..100 %");
				}
				return real(lround(d * 100) / 100.0);
			}
			case "illuminance": {
				if (!(d >= 0)) {
					return refused("an illuminance takes 0 lux and more");
				}
				long n = d < 1 ? 0 : lround(10000 * Math.log10(d) + 1);
				if (n > MAX_ILLUMINANCE) {
					return refused("an illuminance takes 0.." + Values.format(Val.ofReal(illuminance(MAX_ILLUMINANCE)))
						+ " lux");
				}
				return real(illuminance(n));
			}
			case "battery": {
				if (!(d >= 0 && d <= 100)) {
					return refused("a battery level takes 0..100 %");
				}
				return real(lround(d * 2) / 2.0);
			}
			case "multistate":
				if (!(d >= 0 && d <= 0xFFFF) || d != Math.rint(d)) {
					return refused("a multistate value takes 0..65535");
				}
				return new Normalized(Val.ofInt((long) d), null);
			case ZigbeeFunctionTable.ACTION:
				if (!(d >= 0 && d < ACTIONS.size()) || d != Math.rint(d)) {
					return refused("an action takes 0 off, 1 on, 2 toggle, 3 dim up, 4 dim down, 5 dim stop");
				}
				return new Normalized(Val.ofEnum((long) d), null);
			default:
				return refused("the function " + function + " is not simulated");
		}
	}

	private static boolean isNumber(Val value) {
		return value.type() == Type.INTEGER || value.type() == Type.REAL || value.type() == Type.ENUMERATION;
	}

	private static double illuminance(long n) {
		return n == 0 ? 0 : round1(Math.pow(10.0, (n - 1.0) / 10000.0));
	}

	private static Normalized real(double value) {
		return new Normalized(Val.ofReal(value), null);
	}

	private static Normalized refused(String reason) {
		return new Normalized(null, reason);
	}

	/** Rounds half away from zero, as C's <code>lround</code>. */
	private static long lround(double value) {
		return value < 0 ? -Math.round(-value) : Math.round(value);
	}

	private static double round1(double value) {
		return lround(value * 10.0) / 10.0;
	}

}
