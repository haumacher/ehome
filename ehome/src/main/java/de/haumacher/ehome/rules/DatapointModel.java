/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.rules;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * The datapoints the rules read and command: a value, a quality and an update time per datapoint,
 * and the two ways a value changes (after homecontrol <code>hc::DatapointModel</code>):
 *
 * <ul>
 * <li>{@link #report(String, Val)}: the owning technology says what the value is.</li>
 * <li>{@link #command(String, Val)}: the application (a rule, the UI) wants a value; the owning
 * {@link Technology} accepts or refuses it, and on acceptance the model takes the value at
 * once.</li>
 * </ul>
 *
 * <p>
 * Every update is told to the {@link Listener}s right away, with the value and quality before.
 * Values are kept as the expression type the datapoint reads as (unsigned and signed as integer).
 * </p>
 */
public final class DatapointModel implements RuleLoader.DatapointDirectory {

	/**
	 * How far a datapoint's value can be trusted.
	 */
	public enum Quality {
		/** No value received or commanded yet. */
		UNKNOWN("unknown"),
		/** The last update came from the technology or the application. */
		GOOD("good"),
		/** The technology lost contact; the value is the last known. */
		STALE("stale");

		private final String _name;

		Quality(String name) {
			_name = name;
		}

		/** The name used by <code>quality(@d)</code>. */
		public String externalName() {
			return _name;
		}
	}

	/**
	 * Where an update came from.
	 */
	public enum Origin {
		/** Reported by the technology. */
		REPORT,
		/** The technology reported the datapoint stale. */
		STALE,
		/** Commanded by the application. */
		COMMAND,
		/**
		 * The owner published a value of its own (the rule engine the value of a parameter,
		 * <code>spec/rules.md</code> 8.6); for the triggers an update that is no command.
		 */
		PUBLISH;
	}

	/**
	 * The owner of a datapoint: receives the commands and the requests to read.
	 */
	public interface Technology {

		/**
		 * Transmits a command; <code>false</code> refuses it.
		 */
		boolean command(Datapoint datapoint, Val value);

		/**
		 * Asks the source of the datapoint for its current value (a rule's <code>read</code> action,
		 * <code>spec/rules.md</code> 7.12): <code>true</code> when the request was sent or queued; the
		 * answer arrives later as a {@link DatapointModel#report(String, Val) report}. By default a
		 * technology cannot read.
		 */
		default boolean read(Datapoint datapoint) {
			return false;
		}
	}

	/**
	 * Observes every update.
	 */
	public interface Listener {

		/**
		 * Called right after an update. Must not change the model.
		 */
		void datapointUpdated(Update update);
	}

	/**
	 * An update: the datapoint (already with its new value and quality), what it had before and
	 * where the update came from.
	 */
	public record Update(Datapoint datapoint, Val oldValue, Quality oldQuality, Origin origin) {

		/** Whether the value or the quality changed. */
		public boolean changed() {
			return oldQuality != datapoint._quality || !Val.identical(oldValue, datapoint._value);
		}
	}

	/**
	 * Outcome of a command.
	 */
	public enum CommandStatus {
		/** Taken. */
		OK("ok"),
		/** No such datapoint. */
		UNKNOWN_DATAPOINT("unknown datapoint"),
		/** The datapoint is not writable. */
		NOT_WRITABLE("not writable"),
		/** The value does not convert to the datapoint's type. */
		WRONG_TYPE("wrong type"),
		/** The technology refused it. */
		REFUSED("refused");

		private final String _text;

		CommandStatus(String text) {
			_text = text;
		}

		/** A text for the log. */
		public String text() {
			return _text;
		}
	}

	/**
	 * A datapoint of the model.
	 */
	public static final class Datapoint {
		private final String _name;

		private final ValueType _type;

		private final boolean _writable;

		private final boolean _application;

		Technology _technology;

		Val _value;

		Quality _quality = Quality.UNKNOWN;

		long _updatedMs;

		Datapoint(String name, ValueType type, boolean writable, boolean application, Technology technology) {
			_name = name;
			_type = type;
			_writable = writable;
			_application = application;
			_technology = technology;
			_value = Val.none(type.expressionType());
		}

		/** The name. */
		public String name() {
			return _name;
		}

		/** The value type. */
		public ValueType type() {
			return _type;
		}

		/** Whether the user (the UI, the API) and rules may command it. */
		public boolean writable() {
			return _writable;
		}

		/**
		 * Whether it is a value of the application (homecontrol <code>spec/runtime-image.md</code> 3.3:
		 * <code>virtual</code> or without a technology member), which rules write also when it is not
		 * {@link #writable()} (schema 1.6, haumacher/ehome #138).
		 */
		public boolean application() {
			return _application;
		}

		/** The owner, <code>null</code> for none. */
		public Technology technology() {
			return _technology;
		}

		/** The current value (also while stale); no value while unknown. */
		public Val value() {
			return _value;
		}

		/** The quality. */
		public Quality quality() {
			return _quality;
		}

		/** The monotonic time of the last update with a value (not of a stale report). */
		public long updatedMs() {
			return _updatedMs;
		}
	}

	private final LongSupplier _clock;

	private final Map<String, Datapoint> _datapoints = new LinkedHashMap<>();

	private final List<Listener> _listeners = new ArrayList<>();

	private boolean _notifying;

	/**
	 * Creates an empty model.
	 *
	 * @param clock
	 *        The monotonic clock in milliseconds, for the update times.
	 */
	public DatapointModel(LongSupplier clock) {
		_clock = clock;
	}

	/**
	 * Adds a datapoint (unknown until reported or commanded).
	 *
	 * @param technology
	 *        The owner, <code>null</code> for an application value that every command sets.
	 */
	public Datapoint add(String name, ValueType type, boolean writable, Technology technology) {
		return add(name, type, writable, false, technology);
	}

	/**
	 * Adds a datapoint (unknown until reported or commanded).
	 *
	 * @param application
	 *        Whether it is a value of the application ({@link Datapoint#application()}), which rules
	 *        write also when it is not writable.
	 * @param technology
	 *        The owner, <code>null</code> for an application value that every command sets.
	 */
	public Datapoint add(String name, ValueType type, boolean writable, boolean application, Technology technology) {
		if (_datapoints.containsKey(name)) {
			throw new IllegalArgumentException("Datapoint '" + name + "' exists already.");
		}
		Datapoint datapoint = new Datapoint(name, type, writable, application, technology);
		_datapoints.put(name, datapoint);
		return datapoint;
	}

	/** The datapoint with the name, <code>null</code> if none. */
	public Datapoint get(String name) {
		return _datapoints.get(name);
	}

	/** All datapoints in the order they were added. */
	public Collection<Datapoint> datapoints() {
		return Collections.unmodifiableCollection(_datapoints.values());
	}

	@Override
	public ValueType type(String name) {
		Datapoint datapoint = _datapoints.get(name);
		return datapoint == null ? null : datapoint._type;
	}

	@Override
	public boolean writable(String name) {
		Datapoint datapoint = _datapoints.get(name);
		return datapoint != null && datapoint._writable;
	}

	@Override
	public boolean ruleWritable(String name) {
		Datapoint datapoint = _datapoints.get(name);
		return datapoint != null && (datapoint._writable || datapoint._application);
	}

	/** Registers a listener. */
	public void addListener(Listener listener) {
		checkNotNotifying();
		_listeners.add(listener);
	}

	/** Removes a listener. */
	public void removeListener(Listener listener) {
		checkNotNotifying();
		_listeners.remove(listener);
	}

	/**
	 * The technology reports a value (origin report): it becomes the value with quality good.
	 *
	 * @throws IllegalArgumentException
	 *         For an unknown datapoint or a value that does not convert to its type.
	 */
	public void report(String name, Val value) {
		checkNotNotifying();
		Datapoint datapoint = require(name);
		Val converted = convert(datapoint, value);
		if (converted == null) {
			throw new IllegalArgumentException("A " + value.type() + " value for the " + datapoint._type
				+ " datapoint '" + name + "'.");
		}
		store(datapoint, converted, Origin.REPORT);
	}

	/**
	 * The technology reports the datapoint stale: an update only when its quality was good.
	 */
	public void reportStale(String name) {
		checkNotNotifying();
		Datapoint datapoint = require(name);
		if (datapoint._quality != Quality.GOOD) {
			return;
		}
		Val oldValue = datapoint._value;
		Quality oldQuality = datapoint._quality;
		datapoint._quality = Quality.STALE;
		notify(datapoint, oldValue, oldQuality, Origin.STALE);
	}

	/**
	 * The application commands a value (origin command): the user (the UI, the API), which needs the
	 * datapoint {@link Datapoint#writable() writable}.
	 */
	public CommandStatus command(String name, Val value) {
		return command(name, value, false);
	}

	/**
	 * A rule's write (<code>spec/rules.md</code> 7.3): as {@link #command(String, Val)}, but a value of
	 * the application ({@link Datapoint#application()}) that no technology owns takes it also when it
	 * is not writable (schema 1.6, haumacher/ehome #138).
	 */
	public CommandStatus ruleCommand(String name, Val value) {
		return command(name, value, true);
	}

	private CommandStatus command(String name, Val value, boolean byRule) {
		checkNotNotifying();
		Datapoint datapoint = _datapoints.get(name);
		if (datapoint == null) {
			return CommandStatus.UNKNOWN_DATAPOINT;
		}
		if (!datapoint._writable && !(byRule && datapoint._application && datapoint._technology == null)) {
			return CommandStatus.NOT_WRITABLE;
		}
		Val converted = convert(datapoint, value);
		if (converted == null) {
			return CommandStatus.WRONG_TYPE;
		}
		if (datapoint._technology != null && !datapoint._technology.command(datapoint, converted)) {
			return CommandStatus.REFUSED;
		}
		store(datapoint, converted, Origin.COMMAND);
		return CommandStatus.OK;
	}

	/**
	 * Asks the owning technology for the datapoint's current value (<code>spec/rules.md</code> 7.12);
	 * stores nothing and tells no listener.
	 *
	 * @return {@link CommandStatus#OK} when the technology took the request,
	 *         {@link CommandStatus#UNKNOWN_DATAPOINT}, or {@link CommandStatus#REFUSED} when no
	 *         technology owns the datapoint or the technology refused.
	 */
	public CommandStatus read(String name) {
		checkNotNotifying();
		Datapoint datapoint = _datapoints.get(name);
		if (datapoint == null) {
			return CommandStatus.UNKNOWN_DATAPOINT;
		}
		if (datapoint._technology == null || !datapoint._technology.read(datapoint)) {
			return CommandStatus.REFUSED;
		}
		return CommandStatus.OK;
	}

	/**
	 * The owning technology publishes a value of its own (origin publish): it becomes the value with
	 * quality good.
	 *
	 * @throws IllegalArgumentException
	 *         For an unknown datapoint or a value that does not convert to its type.
	 */
	public void publish(String name, Val value) {
		checkNotNotifying();
		Datapoint datapoint = require(name);
		Val converted = convert(datapoint, value);
		if (converted == null) {
			throw new IllegalArgumentException("A " + value.type() + " value for the " + datapoint._type
				+ " datapoint '" + name + "'.");
		}
		store(datapoint, converted, Origin.PUBLISH);
	}

	/**
	 * Makes the technology the owner of the datapoint (the rule engine binds the datapoints of the
	 * rule parameters, <code>spec/rules.md</code> 8.6).
	 */
	public void bind(String name, Technology technology) {
		require(name)._technology = technology;
	}

	private static Val convert(Datapoint datapoint, Val value) {
		if (value.type() == datapoint._type.expressionType() && value.has()) {
			return value;
		}
		return Values.convertForDatapoint(value, datapoint._type);
	}

	private Datapoint require(String name) {
		Datapoint datapoint = _datapoints.get(name);
		if (datapoint == null) {
			throw new IllegalArgumentException("No datapoint '" + name + "'.");
		}
		return datapoint;
	}

	private void store(Datapoint datapoint, Val value, Origin origin) {
		Val oldValue = datapoint._value;
		Quality oldQuality = datapoint._quality;
		datapoint._value = value;
		datapoint._quality = Quality.GOOD;
		datapoint._updatedMs = _clock.getAsLong();
		notify(datapoint, oldValue, oldQuality, origin);
	}

	private void notify(Datapoint datapoint, Val oldValue, Quality oldQuality, Origin origin) {
		_notifying = true;
		try {
			Update update = new Update(datapoint, oldValue, oldQuality, origin);
			for (Listener listener : List.copyOf(_listeners)) {
				listener.datapointUpdated(update);
			}
		} finally {
			_notifying = false;
		}
	}

	private void checkNotNotifying() {
		if (_notifying) {
			throw new IllegalStateException("The datapoint model is changed from within a listener.");
		}
	}

}
