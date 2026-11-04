/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.blocks;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import de.haumacher.ehome.rules.ValueType;

/**
 * The manifest of a block type (homecontrol <code>spec/blocks.md</code> 3): its ports, parameters,
 * state fields, timers, period and persistence interval. A port of homecontrol
 * <code>core/include/hc/blocks/block_type.h</code>.
 *
 * @param type
 *        The reverse-DNS id of the type (<code>org.homecontrol.room-controller</code>).
 * @param major
 *        The major version.
 * @param minor
 *        The minor version.
 * @param label
 *        The English label.
 * @param description
 *        The English description.
 * @param ports
 *        The ports, inputs and outputs in manifest order.
 * @param params
 *        The parameters in manifest order.
 * @param state
 *        The fields of the state record.
 * @param timers
 *        The ids of the type's one-shot timers.
 * @param periodMs
 *        The interval of the <code>period()</code> calls, 0 for none.
 * @param persistEveryMs
 *        The shortest interval between two writes of the state record.
 */
public record Manifest(String type, int major, int minor, String label, String description, List<Port> ports,
		List<Param> params, List<Field> state, List<String> timers, long periodMs, long persistEveryMs) {

	/** The value types of a port (<code>spec/blocks.md</code> B6). */
	public enum PortType {
		/** 0 or 1. */
		BOOLEAN("boolean"),
		/** A whole number. */
		INTEGER("integer"),
		/** A real. */
		REAL("real"),
		/** An enumeration element. */
		ENUMERATION("enumeration");

		private final String _name;

		PortType(String name) {
			_name = name;
		}

		/** The name in the manifest. */
		public String externalName() {
			return _name;
		}

		/** The datapoint type of a mirror of a port of this type (<code>spec/blocks.md</code> 6). */
		public ValueType datapointType() {
			return switch (this) {
				case BOOLEAN -> ValueType.BOOLEAN;
				case INTEGER -> ValueType.SIGNED;
				case REAL -> ValueType.REAL;
				case ENUMERATION -> ValueType.ENUMERATION;
			};
		}

		/** Whether an input of this type reads a datapoint of the type (<code>spec/blocks.md</code> 5.3). */
		public boolean reads(ValueType datapoint) {
			return switch (this) {
				case REAL -> datapoint == ValueType.REAL || datapoint == ValueType.UNSIGNED || datapoint == ValueType.SIGNED;
				case INTEGER -> datapoint == ValueType.UNSIGNED || datapoint == ValueType.SIGNED;
				case ENUMERATION -> datapoint == ValueType.ENUMERATION || datapoint == ValueType.UNSIGNED;
				case BOOLEAN -> datapoint == ValueType.BOOLEAN;
			};
		}

		/** Whether an output of this type commands a datapoint of the type (<code>spec/blocks.md</code> 5.4). */
		public boolean commands(ValueType datapoint) {
			return switch (this) {
				case REAL -> datapoint == ValueType.REAL || datapoint == ValueType.UNSIGNED || datapoint == ValueType.SIGNED;
				case INTEGER -> datapoint == ValueType.UNSIGNED || datapoint == ValueType.SIGNED || datapoint == ValueType.REAL;
				case ENUMERATION -> datapoint == ValueType.ENUMERATION || datapoint == ValueType.UNSIGNED;
				case BOOLEAN -> datapoint == ValueType.BOOLEAN;
			};
		}
	}

	/** The value types of a parameter: the port types plus duration (ms) and time of day (seconds). */
	public enum ParamType {
		/** 0 or 1. */
		BOOLEAN("boolean"),
		/** A whole number. */
		INTEGER("integer"),
		/** A real. */
		REAL("real"),
		/** Milliseconds. */
		DURATION("duration"),
		/** Seconds since midnight. */
		TIME_OF_DAY("timeOfDay"),
		/** An enumeration element. */
		ENUMERATION("enumeration");

		private final String _name;

		ParamType(String name) {
			_name = name;
		}

		/** The name in the manifest. */
		public String externalName() {
			return _name;
		}

		/** The datapoint type of the mirror of a parameter of this type (<code>spec/blocks.md</code> 6). */
		public ValueType datapointType() {
			return switch (this) {
				case BOOLEAN -> ValueType.BOOLEAN;
				case INTEGER -> ValueType.SIGNED;
				case REAL -> ValueType.REAL;
				case DURATION -> ValueType.UNSIGNED;
				case TIME_OF_DAY -> ValueType.TIME_OF_DAY;
				case ENUMERATION -> ValueType.ENUMERATION;
			};
		}
	}

	/** The direction of a port. */
	public enum Direction {
		/** An input. */
		IN,
		/** An output. */
		OUT;
	}

	/** Which updates of an input's binding call <code>input(port)</code> (<code>spec/blocks.md</code> 5.1). */
	public enum InputTrigger {
		/** When the input's value or quality changes. */
		CHANGE("change"),
		/** At every update. */
		UPDATE("update"),
		/** Never. */
		NONE("none");

		private final String _name;

		InputTrigger(String name) {
			_name = name;
		}

		/** The name in the manifest. */
		public String externalName() {
			return _name;
		}
	}

	/** How an input bound to several datapoints combines them (<code>spec/blocks.md</code> 5.3). */
	public enum Aggregation {
		/** Not aggregated. */
		NONE("none"),
		/** The arithmetic mean. */
		MEAN("mean"),
		/** The minimum. */
		MIN("min"),
		/** The maximum. */
		MAX("max"),
		/** True if one is true. */
		ANY("any"),
		/** True if all are true. */
		ALL("all");

		private final String _name;

		Aggregation(String name) {
			_name = name;
		}

		/** The name in the image. */
		public String externalName() {
			return _name;
		}

		/** The aggregation of the name, {@link #NONE} for an unknown one. */
		public static Aggregation of(String name) {
			for (Aggregation a : values()) {
				if (a != NONE && a._name.equals(name)) {
					return a;
				}
			}
			return NONE;
		}
	}

	/**
	 * A port (<code>spec/blocks.md</code> 3).
	 *
	 * @param id
	 *        The identifier.
	 * @param direction
	 *        Input or output.
	 * @param type
	 *        The value type.
	 * @param label
	 *        The English label.
	 * @param unit
	 *        The unit, empty for none.
	 * @param required
	 *        Inputs: the image must bind it.
	 * @param multiple
	 *        Inputs: how many datapoints it may bind (1..4).
	 * @param aggregations
	 *        Inputs with <code>multiple</code> above 1: the aggregations allowed, the default first.
	 * @param trigger
	 *        Inputs: which updates call the block.
	 * @param diagnostic
	 *        Outputs: a value for diagnostics without failsafe.
	 * @param failsafe
	 *        Outputs that are not diagnostic: the value written when the instance stops.
	 * @param min
	 *        Outputs: the lowest value, <code>null</code> for none.
	 * @param max
	 *        Outputs: the highest value, <code>null</code> for none.
	 */
	public record Port(String id, Direction direction, PortType type, String label, String unit, boolean required,
			int multiple, List<Aggregation> aggregations, InputTrigger trigger, boolean diagnostic, double failsafe,
			Double min, Double max) {

		/** An input. */
		public static Port in(String id, PortType type, String label, String unit, boolean required, int multiple,
				List<Aggregation> aggregations, InputTrigger trigger) {
			return new Port(id, Direction.IN, type, label, unit, required, multiple, aggregations, trigger, false, 0, null,
				null);
		}

		/** An output with a failsafe value. */
		public static Port out(String id, PortType type, String label, String unit, double failsafe, Double min,
				Double max) {
			return new Port(id, Direction.OUT, type, label, unit, false, 1, List.of(), InputTrigger.CHANGE, false,
				failsafe, min, max);
		}

		/** A diagnostic output. */
		public static Port diagnostic(String id, PortType type, String label, String unit, Double min, Double max) {
			return new Port(id, Direction.OUT, type, label, unit, false, 1, List.of(), InputTrigger.CHANGE, true, 0,
				min, max);
		}

		/** Whether this is an input. */
		public boolean isInput() {
			return direction == Direction.IN;
		}

		/** The aggregation of a port bound to several datapoints when the image chooses none. */
		public Aggregation defaultAggregation() {
			return aggregations.isEmpty() ? Aggregation.NONE : aggregations.get(0);
		}
	}

	/**
	 * A choice of an integer or enumeration parameter.
	 *
	 * @param value
	 *        The number.
	 * @param id
	 *        The identifier the image may name it by.
	 * @param label
	 *        The English label.
	 */
	public record Choice(double value, String id, String label) {
		// A value.
	}

	/**
	 * A parameter (<code>spec/blocks.md</code> 3, <code>spec/rules.md</code> 8.1).
	 *
	 * @param id
	 *        The identifier.
	 * @param type
	 *        The value type.
	 * @param label
	 *        The English label.
	 * @param unit
	 *        The unit, empty for none.
	 * @param defaultValue
	 *        The default.
	 * @param min
	 *        The lowest value, <code>null</code> for none.
	 * @param max
	 *        The highest value, <code>null</code> for none.
	 * @param step
	 *        A hint for the UI, 0 for none.
	 * @param choices
	 *        The choices of an integer or enumeration parameter.
	 * @param adjustable
	 *        Whether the inhabitant may change it.
	 */
	public record Param(String id, ParamType type, String label, String unit, double defaultValue, Double min,
			Double max, double step, List<Choice> choices, boolean adjustable) {

		/** The choice with the id, <code>null</code> if none. */
		public Choice choice(String choiceId) {
			for (Choice choice : choices) {
				if (choice.id().equals(choiceId)) {
					return choice;
				}
			}
			return null;
		}

		/** The choice with the value, <code>null</code> if none. */
		public Choice choice(double value) {
			for (Choice choice : choices) {
				if (choice.value() == value) {
					return choice;
				}
			}
			return null;
		}
	}

	/**
	 * A field of the state record (<code>spec/blocks.md</code> 8.1, B12).
	 *
	 * @param id
	 *        The identifier.
	 * @param persist
	 *        Whether the field is persisted.
	 * @param initial
	 *        The initial value.
	 */
	public record Field(String id, boolean persist, double initial) {
		// A value.
	}

	/** The limits of <code>spec/blocks.md</code> 11. */
	public static final int MAX_BLOCKS = 32;

	/** Datapoints per input. */
	public static final int MAX_MULTIPLE = 4;

	/** Ports per type. */
	public static final int MAX_PORTS = 24;

	/** Parameters per type. */
	public static final int MAX_PARAMS = 32;

	/** State fields per type. */
	public static final int MAX_FIELDS = 32;

	/** Timers per type. */
	public static final int MAX_TIMERS = 4;

	/** The shortest timer. */
	public static final long TIMER_MIN_MS = 1;

	/** The longest timer. */
	public static final long TIMER_MAX_MS = 30L * 86400000L;

	/** The bytes of a persisted record. */
	public static final int MAX_RECORD_BYTES = 512;

	/** Record writes per hour, of all instances together (<code>spec/blocks.md</code> B13). */
	public static final int RECORD_WRITES_PER_HOUR = 30;

	/** The longest block id. */
	public static final int MAX_ID_LENGTH = 32;

	/** The ids the namespace of a block reserves. */
	public static final Set<String> RESERVED = Set.of("enabled");

	/** <code>major.minor</code>. */
	public String version() {
		return major + "." + minor;
	}

	/** The index of the port, -1 if none. */
	public int port(String id) {
		for (int i = 0; i < ports.size(); i++) {
			if (ports.get(i).id().equals(id)) {
				return i;
			}
		}
		return -1;
	}

	/** The index of the parameter, -1 if none. */
	public int param(String id) {
		for (int i = 0; i < params.size(); i++) {
			if (params.get(i).id().equals(id)) {
				return i;
			}
		}
		return -1;
	}

	/** The index of the state field, -1 if none. */
	public int field(String id) {
		for (int i = 0; i < state.size(); i++) {
			if (state.get(i).id().equals(id)) {
				return i;
			}
		}
		return -1;
	}

	/** The index of the timer, -1 if none. */
	public int timer(String id) {
		return timers.indexOf(id);
	}

	/** Whether any state field persists. */
	public boolean persists() {
		for (Field f : state) {
			if (f.persist()) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Checks the manifest against <code>spec/blocks.md</code> 3: <code>null</code> when valid, else
	 * the reason.
	 */
	public String check() {
		int dots = 0;
		for (char c : type.toCharArray()) {
			dots += c == '.' ? 1 : 0;
			if (!((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '.' || c == '-')) {
				return "the type id has a character outside a-z 0-9 . -";
			}
		}
		if (type.length() < 3 || type.length() > 63 || dots < 2) {
			return "the type id is not a reverse-DNS id of 3..63 characters";
		}
		if (ports.size() > MAX_PORTS || params.size() > MAX_PARAMS || state.size() > MAX_FIELDS
			|| timers.size() > MAX_TIMERS) {
			return "more ports, parameters, state fields or timers than the limits";
		}
		if (periodMs != 0 && (periodMs < 1000 || periodMs > 86400000)) {
			return "the period is not 1 s .. 1 d";
		}
		if (persists() && (persistEveryMs < 60000 || persistEveryMs > 86400000)) {
			return "persistEvery is not 1 min .. 1 d";
		}
		Set<String> namespace = new HashSet<>();
		for (int i = 0; i < ports.size() + params.size(); i++) {
			String id = i < ports.size() ? ports.get(i).id() : params.get(i - ports.size()).id();
			if (!identifier(id)) {
				return "a port or parameter id is not an identifier";
			}
			if (RESERVED.contains(id)) {
				return "the id 'enabled' is reserved";
			}
			if (!namespace.add(id)) {
				return "two ports or parameters share an id";
			}
		}
		for (Port p : ports) {
			if (p.isInput()) {
				if (p.multiple() < 1 || p.multiple() > MAX_MULTIPLE) {
					return "an input's multiple is not 1..4";
				}
				if (p.multiple() > 1) {
					Set<Aggregation> allowed = p.type() == PortType.BOOLEAN ? Set.of(Aggregation.ANY, Aggregation.ALL)
						: p.type() == PortType.ENUMERATION ? Set.of()
						: Set.of(Aggregation.MEAN, Aggregation.MIN, Aggregation.MAX);
					if (p.aggregations().isEmpty() || !allowed.containsAll(p.aggregations())) {
						return "an aggregated input's aggregations do not fit its type";
					}
				}
			} else if (!p.diagnostic() && !Double.isFinite(p.failsafe())) {
				return "an output's failsafe value is not finite";
			}
		}
		int record = 4 + 1 + 1 + type.length() + 2 + 1 + MAX_ID_LENGTH + 1 + 4;
		Set<String> fields = new HashSet<>();
		for (Field f : state) {
			if (!identifier(f.id())) {
				return "a state field id is not an identifier";
			}
			if (!fields.add(f.id())) {
				return "two state fields share an id";
			}
			if (f.persist()) {
				record += 1 + f.id().length() + 8;
			}
		}
		if (record > MAX_RECORD_BYTES) {
			return "the persisted record would exceed 512 bytes";
		}
		for (String timer : timers) {
			if (!identifier(timer)) {
				return "a timer id is not an identifier";
			}
		}
		for (Param p : params) {
			if ((p.min() != null && p.defaultValue() < p.min()) || (p.max() != null && p.defaultValue() > p.max())
				|| (p.min() != null && p.max() != null && p.min() > p.max())) {
				return "a parameter's default is outside its range";
			}
			if (!p.choices().isEmpty() && p.type() != ParamType.INTEGER && p.type() != ParamType.ENUMERATION) {
				return "choices of a parameter that is neither integer nor enumeration";
			}
		}
		return null;
	}

	/** An identifier of <code>spec/rules.md</code> 2.1 (1..24 characters). */
	static boolean identifier(String id) {
		if (id.isEmpty() || id.length() > 24) {
			return false;
		}
		for (int i = 0; i < id.length(); i++) {
			char c = id.charAt(i);
			boolean letter = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || c == '_';
			if (!letter && !(i > 0 && c >= '0' && c <= '9')) {
				return false;
			}
		}
		return true;
	}

}
