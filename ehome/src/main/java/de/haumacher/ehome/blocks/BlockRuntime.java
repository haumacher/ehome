/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.blocks;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;

import de.haumacher.ehome.blocks.BlockContext.InputValue;
import de.haumacher.ehome.blocks.BlockSet.BlockSpec;
import de.haumacher.ehome.blocks.BlockSet.PortBinding;
import de.haumacher.ehome.blocks.BlockType.StartReason;
import de.haumacher.ehome.blocks.Manifest.Aggregation;
import de.haumacher.ehome.blocks.Manifest.Field;
import de.haumacher.ehome.blocks.Manifest.InputTrigger;
import de.haumacher.ehome.blocks.Manifest.Param;
import de.haumacher.ehome.blocks.Manifest.ParamType;
import de.haumacher.ehome.blocks.Manifest.Port;
import de.haumacher.ehome.blocks.Manifest.PortType;
import de.haumacher.ehome.rules.Astro.SunPosition;
import de.haumacher.ehome.rules.DatapointModel;
import de.haumacher.ehome.rules.DatapointModel.CommandStatus;
import de.haumacher.ehome.rules.DatapointModel.Datapoint;
import de.haumacher.ehome.rules.DatapointModel.Quality;
import de.haumacher.ehome.rules.Engine;
import de.haumacher.ehome.rules.Engine.Culprit;
import de.haumacher.ehome.rules.ParameterStore;
import de.haumacher.ehome.rules.RuleSet;
import de.haumacher.ehome.rules.RuleSet.Level;
import de.haumacher.ehome.rules.Type;
import de.haumacher.ehome.rules.Val;
import de.haumacher.ehome.rules.Val.DateTime;
import de.haumacher.ehome.rules.Val.TimeOfDay;
import de.haumacher.ehome.rules.ValueType;
import de.haumacher.ehome.rules.Values;

/**
 * The block runtime (homecontrol <code>spec/blocks.md</code>): holds the compiled instances and
 * their runtime (state records, parameter values, timers, the period grid, the diagnostics), owns
 * the mirror datapoints <code>block/&lt;id&gt;/&lt;x&gt;</code>, persists the state records in a
 * {@link RecordStore} and the parameter overrides in the {@link ParameterStore} of the rule
 * parameters (under <code>@&lt;blockId&gt;</code>), and is called by the rule {@link Engine} inside
 * its cascades ({@link Engine.BlockHost}): after the rules for every event, for its timers and
 * periods ordered with the rule timers, and after the rules' start cascades. A port of homecontrol
 * <code>core/src/blocks/block_runtime.cpp</code>.
 */
public final class BlockRuntime implements Engine.BlockHost {

	/** The name of the technology that owns the mirrors. */
	public static final String TECHNOLOGY_NAME = "blocks";

	/** Kinds of {@link #blockEvent(int, int, int, boolean)}. */
	private static final int PARAMETER_CHANGED = 0;

	private static final int ENABLED_CHANGED = 1;

	private static final int RESTART = 2;

	/** An instance's status (<code>spec/blocks.md</code> 9). */
	public enum Status {
		/** Started and running. */
		RUNNING("running"),
		/** Switched off. */
		DISABLED("disabled"),
		/** Stopped by a fault. */
		FAULT("fault");

		private final String _name;

		Status(String name) {
			_name = name;
		}

		/** The name of the specification. */
		public String externalName() {
			return _name;
		}
	}

	/** The log codes of <code>spec/blocks.md</code> 9. */
	public enum LogCode {
		/** A log of the block. */
		LOG("log"),
		/** The instance faulted. */
		FAULT("fault"),
		/** The bound datapoint refused a command. */
		WRITE_REFUSED("writeRefused"),
		/** A timer outside 1 ms .. 30 d. */
		TIMER_REFUSED("timerRefused"),
		/** A parameter change refused. */
		PARAMETER_REFUSED("parameterRefused"),
		/** An override dropped by a new image. */
		PARAMETER_RESET("parameterReset"),
		/** A state record not kept. */
		STATE_RESET("stateReset"),
		/** A call refused by the loop guard. */
		LOOP_GUARD("loopGuard");

		private final String _name;

		LogCode(String name) {
			_name = name;
		}

		/** The code of the specification. */
		public String externalName() {
			return _name;
		}
	}

	/** The kinds of call (diagnostics). */
	public enum CallKind {
		/** None yet. */
		NONE("none"),
		/** <code>start</code>. */
		START("start"),
		/** <code>input</code>. */
		INPUT("input"),
		/** <code>timer</code>. */
		TIMER("timer"),
		/** <code>period</code>. */
		PERIOD("period"),
		/** <code>parameter</code>. */
		PARAMETER("parameter");

		private final String _name;

		CallKind(String name) {
			_name = name;
		}

		/** The name of the specification. */
		public String externalName() {
			return _name;
		}
	}

	/** Outcome of a change from the UI or API (homecontrol <code>spec/api.md</code> "Blocks"). */
	public enum ChangeStatus {
		/** Taken. */
		OK("ok"),
		/** No such block. */
		UNKNOWN_BLOCK("unknown"),
		/** No such parameter. */
		UNKNOWN_PARAMETER("unknown_parameter"),
		/** Not adjustable. */
		NOT_ADJUSTABLE("not_adjustable"),
		/** Not a valid value. */
		INVALID_VALUE("invalid_value"),
		/** A restart of a block that runs. */
		NOT_FAULTED("not_faulted");

		private final String _name;

		ChangeStatus(String name) {
			_name = name;
		}

		/** The code suffix of the API. */
		public String externalName() {
			return _name;
		}
	}

	/**
	 * Observes the runtime: the corpus runner, the simulation's log. Must not call back into the
	 * runtime.
	 */
	public interface Observer {

		/** A log entry with a code of <code>spec/blocks.md</code> 9. */
		default void blockLogged(String block, LogCode code, Level level, String text) {
			// Nothing by default.
		}

		/** A successful command of a block's output. */
		default void blockCommanded(String block, Datapoint datapoint, Val value) {
			// Nothing by default.
		}

		/** Right after the <code>loopGuard</code> entry of the block: the culprit and its count. */
		default void loopGuard(String block, String culprit, int firings) {
			// Nothing by default.
		}
	}

	/**
	 * A persisted state record (<code>spec/blocks.md</code> 8.1): the type and major version it was
	 * written by, and the persisted fields by id.
	 *
	 * @param type
	 *        The type id.
	 * @param major
	 *        The major version.
	 * @param fields
	 *        The persisted fields.
	 */
	public record StateRecord(String type, int major, Map<String, Double> fields) {
		// A value.
	}

	/** Where the state records are kept across a reboot (the device's blob store). */
	public interface RecordStore {

		/** The record of the block, <code>null</code> if none. */
		StateRecord read(String block);

		/** Stores the record of the block. */
		void write(String block, StateRecord record);

		/** Deletes the record of the block. */
		void remove(String block);

		/** The blocks with a record. */
		Collection<String> blocks();
	}

	/** A {@link RecordStore} in memory. */
	public static final class MemoryRecords implements RecordStore {

		private final Map<String, StateRecord> _records = new LinkedHashMap<>();

		@Override
		public StateRecord read(String block) {
			return _records.get(block);
		}

		@Override
		public void write(String block, StateRecord record) {
			_records.put(block, record);
		}

		@Override
		public void remove(String block) {
			_records.remove(block);
		}

		@Override
		public Collection<String> blocks() {
			return new ArrayList<>(_records.keySet());
		}
	}

	private static final class Instance {
		Status _status = Status.DISABLED;

		/** Called since its start: inputs call it. */
		boolean _started;

		boolean _enabled = true;

		boolean _enabledOverridden;

		/** The record comes from before (the next start's restored). */
		boolean _restored;

		/** Faulted before the swap that created this instance: disabled, it writes nothing (spec 6.1). */
		boolean _wasFault;

		/** A persisted field differs from the stored record. */
		boolean _dirty;

		/** Written since the boot: the next write waits for the interval. */
		boolean _writtenOnce;

		double[] _state;

		double[] _stored;

		double[] _params;

		boolean[] _overridden;

		/** Per parameter: its mirror, <code>null</code> if none. */
		String[] _mirrorParams;

		/** Per port: its mirror, <code>null</code> if none. */
		String[] _mirrorPorts;

		String _mirrorEnabled;

		/** Per port: the input as last seen (change detection). */
		double[] _inputValue;

		Quality[] _inputQuality;

		long[] _timers;

		boolean[] _running;

		long _periodNext;

		boolean _periodArmed;

		long _lastWriteMs;

		int _cascadeCalls;

		// Diagnostics.

		int _calls;

		int _errors;

		CallKind _lastCall = CallKind.NONE;

		long _lastCallMs;

		String _lastCallArg = "";

		LogCode _lastErrorCode;

		String _lastError;

		long _lastErrorMs;

		String _fault;

		long _faultMs;
	}

	private final DatapointModel _model;

	private final LongSupplier _clock;

	private final ParameterStore _store;

	private final RecordStore _records;

	private final Engine _engine;

	private Observer _observer = new Observer() {
		// Nothing.
	};

	private final DatapointModel.Technology _technology = this::mirrorCommand;

	private BlockSet _set = BlockSet.empty();

	private Instance[] _instances = new Instance[0];

	/** The datapoints bound to an input of a block. */
	private Set<String> _interest = new HashSet<>();

	private boolean _booted;

	/**
	 * Creates a runtime on the engine (it becomes the engine's {@link Engine.BlockHost}).
	 *
	 * @param store
	 *        The store of the rule parameters, which also keeps the blocks' overrides.
	 * @param records
	 *        The state records kept across a reboot.
	 */
	public BlockRuntime(DatapointModel model, LongSupplier clock, ParameterStore store, RecordStore records,
			Engine engine) {
		_model = model;
		_clock = clock;
		_store = store;
		_records = records;
		_engine = engine;
		_engine.setBlockHost(this);
	}

	/** Sets the observer. */
	public void setObserver(Observer observer) {
		_observer = observer;
	}

	/** The technology that owns the mirrors (named {@link #TECHNOLOGY_NAME}). */
	public DatapointModel.Technology technology() {
		return _technology;
	}

	/** The compiled instances. */
	public BlockSet set() {
		return _set;
	}

	// ---- Logging --------------------------------------------------------------------------------

	private void log(int block, LogCode code, Level level, String text) {
		boolean known = block >= 0 && block < _instances.length;
		logText(known ? _set.at(block).id() : "-", known ? _instances[block] : null, code, level, text);
	}

	private void logText(String id, Instance in, LogCode code, Level level, String text) {
		_observer.blockLogged(id, code, level, text);
		if (in != null && code != LogCode.LOG && level != Level.INFO) {
			in._errors++;
			in._lastErrorCode = code;
			in._lastErrorMs = _engine.inCascade() ? _engine.cascadeMs() : _clock.getAsLong();
			in._lastError = text;
		}
	}

	private static String format(double value) {
		return value == Math.rint(value) && Math.abs(value) < 1e15 ? Long.toString((long) value) : Double.toString(value);
	}

	// ---- Records ---------------------------------------------------------------------------------

	/** The persisted fields of a state record. */
	private static Map<String, Double> persisted(Manifest m, double[] state) {
		Map<String, Double> fields = new LinkedHashMap<>();
		for (int f = 0; f < m.state().size(); f++) {
			if (m.state().get(f).persist()) {
				fields.put(m.state().get(f).id(), Double.valueOf(state[f]));
			}
		}
		return fields;
	}

	/**
	 * Reads the block's persisted record into its state.
	 *
	 * @return Whether there was a record of this type and major version.
	 */
	private boolean loadRecord(int block) {
		BlockSpec spec = _set.at(block);
		Manifest m = spec.manifest();
		Instance in = _instances[block];
		if (!m.persists()) {
			return false;
		}
		StateRecord record = _records.read(spec.id());
		if (record == null) {
			return false;
		}
		if (!record.type().equals(m.type()) || record.major() != m.major()) {
			log(block, LogCode.STATE_RESET, Level.WARNING,
				"the persisted state record is of another block type or major version; the state starts fresh");
			for (int f = 0; f < m.state().size(); f++) {
				in._state[f] = m.state().get(f).initial();
			}
			_records.remove(spec.id());
			return false;
		}
		for (int f = 0; f < m.state().size(); f++) {
			Field field = m.state().get(f);
			Double value = record.fields().get(field.id());
			if (field.persist() && value != null && Double.isFinite(value.doubleValue())) {
				in._state[f] = value.doubleValue();
			}
		}
		System.arraycopy(in._state, 0, in._stored, 0, in._state.length);
		return true;
	}

	private void recordChanged(int block) {
		Instance in = _instances[block];
		Manifest m = _set.at(block).manifest();
		if (in._status == Status.FAULT || !m.persists()) {
			return;
		}
		for (int f = 0; f < m.state().size() && !in._dirty; f++) {
			if (m.state().get(f).persist()
				&& Double.doubleToRawLongBits(in._state[f]) != Double.doubleToRawLongBits(in._stored[f])) {
				in._dirty = true;
			}
		}
	}

	private void writeRecord(int block) {
		Instance in = _instances[block];
		BlockSpec spec = _set.at(block);
		in._dirty = false;
		in._lastWriteMs = _clock.getAsLong();
		in._writtenOnce = true;
		_records.write(spec.id(), new StateRecord(spec.manifest().type(), spec.manifest().major(),
			persisted(spec.manifest(), in._state)));
		System.arraycopy(in._state, 0, in._stored, 0, in._state.length);
	}

	/**
	 * The shortest interval between two writes of an instance's record (<code>spec/blocks.md</code>
	 * B13): <code>max(persistEvery, n × 120 s)</code>, <code>n</code> the instances with persistent
	 * state.
	 */
	private long persistInterval(int block) {
		int persisting = 0;
		for (BlockSpec spec : _set.specs()) {
			persisting += spec.manifest().persists() ? 1 : 0;
		}
		long paced = persisting * 3600000L / Manifest.RECORD_WRITES_PER_HOUR;
		return Math.max(_set.at(block).manifest().persistEveryMs(), paced);
	}

	@Override
	public long persistDeadline() {
		long earliest = Long.MAX_VALUE;
		for (int b = 0; b < _instances.length; b++) {
			Instance in = _instances[b];
			if (in._dirty && in._status != Status.FAULT) {
				long due = in._writtenOnce ? in._lastWriteMs + persistInterval(b) : 0;
				earliest = Math.min(earliest, due);
			}
		}
		return earliest;
	}

	@Override
	public void persistDue(long now) {
		for (int b = 0; b < _instances.length; b++) {
			Instance in = _instances[b];
			if (in._dirty && in._status != Status.FAULT
				&& (!in._writtenOnce || in._lastWriteMs + persistInterval(b) <= now)) {
				writeRecord(b);
			}
		}
	}

	/** Writes every due record now (an orderly reboot). */
	public void flush() {
		for (int b = 0; b < _instances.length; b++) {
			Instance in = _instances[b];
			if (in._dirty && in._status != Status.FAULT) {
				writeRecord(b);
			}
		}
	}

	// ---- Loading and swapping --------------------------------------------------------------------

	/**
	 * Swaps the set in (<code>spec/blocks.md</code> 8.1): records and overrides follow the block id
	 * (kept for the same type and major version), the first adopt reads the persisted records and
	 * overrides (the boot), the mirrors are bound and take their values quietly, records and
	 * overrides of blocks no longer in the image are deleted. Does not start the blocks: the
	 * engine's start does, after the rules.
	 */
	public void adopt(BlockSet next) {
		BlockSet oldSet = _set;
		Instance[] old = _instances;
		boolean boot = !_booted;
		Instance[] instances = new Instance[next.size()];
		_set = next;
		_instances = instances;
		for (int i = 0; i < next.size(); i++) {
			BlockSpec spec = next.at(i);
			Manifest m = spec.manifest();
			Instance in = new Instance();
			instances[i] = in;
			int fields = m.state().size();
			in._state = new double[fields];
			in._stored = new double[fields];
			for (int f = 0; f < fields; f++) {
				in._state[f] = m.state().get(f).initial();
				in._stored[f] = m.state().get(f).initial();
			}
			in._params = spec.defaults().clone();
			in._overridden = new boolean[m.params().size()];
			in._mirrorParams = new String[m.params().size()];
			in._mirrorPorts = new String[m.ports().size()];
			in._inputValue = new double[m.ports().size()];
			in._inputQuality = new Quality[m.ports().size()];
			in._timers = new long[m.timers().size()];
			in._running = new boolean[m.timers().size()];
			in._enabled = spec.enabled();
			String key = BlockSet.storeKey(spec.id());
			int o = oldSet.find(spec.id());
			if (o >= 0) {
				BlockSpec oldSpec = oldSet.at(o);
				Manifest om = oldSpec.manifest();
				Instance oi = old[o];
				// Diagnostics follow the id.
				in._calls = oi._calls;
				in._errors = oi._errors;
				in._lastErrorCode = oi._lastErrorCode;
				in._lastError = oi._lastError;
				in._lastErrorMs = oi._lastErrorMs;
				in._lastCall = oi._lastCall;
				in._lastCallMs = oi._lastCallMs;
				in._lastCallArg = oi._lastCallArg;
				boolean same = om.type().equals(m.type()) && om.major() == m.major();
				if (oi._status == Status.FAULT && same) {
					// A faulted instance starts from its persisted record, as a restart (spec/blocks.md B11).
					in._restored = loadRecord(i);
					in._writtenOnce = oi._writtenOnce;
					in._lastWriteMs = oi._lastWriteMs;
					in._wasFault = true;
				} else if (same) {
					// The same type and major version: the record in RAM, by field id (spec/blocks.md 8.1).
					for (int f = 0; f < fields; f++) {
						int of = om.field(m.state().get(f).id());
						if (of >= 0) {
							in._state[f] = oi._state[of];
							in._stored[f] = oi._stored[of];
						}
					}
					in._dirty = oi._dirty;
					in._lastWriteMs = oi._lastWriteMs;
					in._writtenOnce = oi._writtenOnce;
					in._restored = true;
				} else {
					log(i, LogCode.STATE_RESET, Level.WARNING, "the block type changed from " + om.type() + " "
						+ om.major() + "." + oldSpec.minor() + " to " + m.type() + " " + m.major() + "." + spec.minor()
						+ ": the state starts fresh");
					_records.remove(spec.id());
				}
				// Overrides from RAM, checked against the new manifest (spec/blocks.md 8.2).
				for (int op = 0; op < om.params().size(); op++) {
					if (!oi._overridden[op]) {
						continue;
					}
					Param oldDecl = om.params().get(op);
					int p = m.param(oldDecl.id());
					String why = null;
					if (p < 0) {
						why = "gone";
					} else if (!m.params().get(p).adjustable()) {
						why = "not adjustable";
					} else if (m.params().get(p).type() != oldDecl.type()) {
						why = "type changed";
					} else if (BlockSet.paramProblem(m.params().get(p), oi._params[op]) != null) {
						why = "out of bounds";
					}
					if (why == null) {
						in._params[p] = oi._params[op];
						in._overridden[p] = true;
					} else {
						_store.erase(key, oldDecl.id());
						log(i, LogCode.PARAMETER_RESET, Level.WARNING,
							"override " + format(oi._params[op]) + " of '" + oldDecl.id() + "' dropped: " + why);
					}
				}
				if (oi._enabledOverridden) {
					if (spec.enabledAdjustable()) {
						in._enabled = oi._enabled;
						in._enabledOverridden = true;
					} else {
						_store.erase(key, "enabled");
						log(i, LogCode.PARAMETER_RESET, Level.WARNING,
							"override " + oi._enabled + " of 'enabled' dropped: not adjustable");
					}
				}
			} else if (boot) {
				in._restored = loadRecord(i);
				for (int p = 0; p < m.params().size(); p++) {
					Param decl = m.params().get(p);
					Val stored = _store.read(key, decl.id());
					if (stored == null) {
						continue;
					}
					Double v = fromVal(decl.type(), stored);
					String why = null;
					if (!decl.adjustable()) {
						why = "not adjustable";
					} else if (v == null) {
						why = "type changed";
					} else if (BlockSet.paramProblem(decl, v.doubleValue()) != null) {
						why = "out of bounds";
					}
					if (why == null) {
						in._params[p] = v.doubleValue();
						in._overridden[p] = true;
					} else {
						_store.erase(key, decl.id());
						log(i, LogCode.PARAMETER_RESET, Level.WARNING,
							"override " + Values.format(stored) + " of '" + decl.id() + "' dropped: " + why);
					}
				}
				Val stored = _store.read(key, "enabled");
				if (stored != null) {
					if (spec.enabledAdjustable() && stored.type() == Type.BOOLEAN) {
						in._enabled = stored.bool();
						in._enabledOverridden = true;
					} else {
						_store.erase(key, "enabled");
						log(i, LogCode.PARAMETER_RESET, Level.WARNING, "override of 'enabled' dropped: "
							+ (spec.enabledAdjustable() ? "type changed" : "not adjustable"));
					}
				}
				// Overrides of parameters the manifest no longer has (the prune below deletes them).
				for (String logical : new ArrayList<>(_store.overrides().keySet())) {
					String prefix = key + "/";
					if (logical.startsWith(prefix)) {
						String parameter = logical.substring(prefix.length());
						if (!parameter.equals("enabled") && m.param(parameter) < 0) {
							log(i, LogCode.PARAMETER_RESET, Level.WARNING, "override of '" + parameter + "' dropped: gone");
						}
					}
				}
			}
			in._status = in._enabled ? Status.RUNNING : Status.DISABLED;
			in._started = false;
		}
		_booted = true;
		prune();
		bind();
	}

	/** A stored override back into a number; <code>null</code> when its type is not the parameter's. */
	private static Double fromVal(ParamType type, Val v) {
		if (!v.has()) {
			return null;
		}
		switch (type) {
			case BOOLEAN:
				return v.type() == Type.BOOLEAN ? Double.valueOf(v.bool() ? 1 : 0) : null;
			case INTEGER:
				return v.type() == Type.INTEGER ? Double.valueOf(v.integer()) : null;
			case REAL:
				return v.type() == Type.REAL ? Double.valueOf(v.real()) : null;
			case DURATION:
				return v.type() == Type.DURATION ? Double.valueOf(v.integer()) : null;
			case TIME_OF_DAY:
				return v.type() == Type.TIME_OF_DAY ? Double.valueOf(v.time().secondsOfDay()) : null;
			case ENUMERATION:
				return v.type() == Type.ENUMERATION ? Double.valueOf(v.integer()) : null;
			default:
				return null;
		}
	}

	/** A parameter value as the store keeps it. */
	private static Val toVal(ParamType type, double v) {
		switch (type) {
			case BOOLEAN:
				return Val.ofBool(v != 0);
			case INTEGER:
				return Val.ofInt((long) v);
			case REAL:
				return Val.ofReal(v);
			case DURATION:
				return Val.ofDuration((long) v);
			case TIME_OF_DAY:
				return Val.ofTime(timeOfDay(v));
			default:
				return Val.ofEnum((long) v);
		}
	}

	private static TimeOfDay timeOfDay(double v) {
		int s = (int) v;
		return new TimeOfDay(s / 3600, s % 3600 / 60, s % 60, 0);
	}

	private void prune() {
		_store.prune((logical, value) -> {
			if (!logical.startsWith("@")) {
				return true;
			}
			int slash = logical.indexOf('/');
			if (slash < 0) {
				return false;
			}
			int b = _set.find(logical.substring(1, slash));
			if (b < 0) {
				return false;
			}
			String parameter = logical.substring(slash + 1);
			if (parameter.equals("enabled")) {
				return _instances[b]._enabledOverridden;
			}
			int p = _set.at(b).manifest().param(parameter);
			return p >= 0 && _instances[b]._overridden[p];
		});
		for (String block : _records.blocks()) {
			int b = _set.find(block);
			if (b < 0 || !_set.at(b).manifest().persists()) {
				_records.remove(block);
			}
		}
	}

	/** Binds the mirrors (again, after the model was reloaded without an adopt). */
	public void bind() {
		_interest = new HashSet<>();
		_engine.quietly(() -> {
			for (int b = 0; b < _instances.length; b++) {
				BlockSpec spec = _set.at(b);
				Manifest m = spec.manifest();
				Instance in = _instances[b];
				in._mirrorEnabled = bindOne(spec.id(), "enabled", ValueType.BOOLEAN);
				publishMirror(in._mirrorEnabled, ValueType.BOOLEAN, in._enabled ? 1 : 0, Quality.GOOD);
				for (int p = 0; p < m.params().size(); p++) {
					in._mirrorParams[p] = null;
					Param decl = m.params().get(p);
					if (decl.adjustable()) {
						ValueType type = decl.type().datapointType();
						in._mirrorParams[p] = bindOne(spec.id(), decl.id(), type);
						publishMirror(in._mirrorParams[p], type, in._params[p], Quality.GOOD);
					}
				}
				for (int p = 0; p < m.ports().size(); p++) {
					Port decl = m.ports().get(p);
					PortBinding binding = spec.ports().get(p);
					in._mirrorPorts[p] = null;
					if (!decl.isInput()) {
						in._mirrorPorts[p] = bindOne(spec.id(), decl.id(), decl.type().datapointType());
						continue;
					}
					_interest.addAll(binding.datapoints());
					InputValue v = aggregate(b, p);
					in._inputValue[p] = v.value();
					in._inputQuality[p] = v.quality();
					if (binding.datapoints().size() > 1) {
						in._mirrorPorts[p] = bindOne(spec.id(), decl.id(), decl.type().datapointType());
						publishMirror(in._mirrorPorts[p], decl.type().datapointType(), v.value(), v.quality());
					}
				}
			}
		});
	}

	private String bindOne(String block, String member, ValueType type) {
		String name = BlockSet.mirrorName(block, member);
		Datapoint datapoint = _model.get(name);
		if (datapoint == null || datapoint.type() != type) {
			return null;
		}
		_model.bind(name, _technology);
		return name;
	}

	// ---- Inputs and outputs ----------------------------------------------------------------------

	/** A datapoint value as a number (<code>spec/blocks.md</code> B6). */
	private static double numberOf(Datapoint datapoint) {
		Val v = datapoint.value();
		switch (datapoint.type()) {
			case BOOLEAN:
				return v.bool() ? 1 : 0;
			case UNSIGNED:
			case SIGNED:
			case ENUMERATION:
				return v.integer();
			case REAL:
				return v.real();
			case TIME_OF_DAY:
				return v.time().secondsOfDay();
			default:
				return 0;
		}
	}

	private static double numberOf(Val v) {
		switch (v.type()) {
			case BOOLEAN:
				return v.bool() ? 1 : 0;
			case INTEGER:
			case DURATION:
			case ENUMERATION:
				return v.integer();
			case REAL:
				return v.real();
			case TIME_OF_DAY:
				return v.time().secondsOfDay();
			default:
				return 0;
		}
	}

	/** A number as a value of the datapoint type; <code>null</code> when it does not fit. */
	private static Val valueOf(ValueType type, double v) {
		switch (type) {
			case BOOLEAN:
				return Val.ofBool(v != 0);
			case REAL:
				return Val.ofReal(v);
			case ENUMERATION:
				if (v < 0 || v > 4294967295.0 || v != Math.floor(v)) {
					return null;
				}
				return Val.ofEnum((long) v);
			case UNSIGNED:
			case SIGNED: {
				// Half away from zero.
				double r = v >= 0 ? Math.floor(v + 0.5) : -Math.floor(-v + 0.5);
				if (type == ValueType.UNSIGNED ? (r < 0 || r > 4294967295.0) : (r < -2147483648.0 || r > 2147483647.0)) {
					return null;
				}
				return Val.ofInt((long) r);
			}
			case TIME_OF_DAY:
				if (v < 0 || v > 86399) {
					return null;
				}
				return Val.ofTime(timeOfDay(v));
			default:
				return null;
		}
	}

	private InputValue aggregate(int block, int port) {
		PortBinding binding = _set.at(block).ports().get(port);
		if (!binding.bound()) {
			return InputValue.UNBOUND;
		}
		Aggregation aggregation = binding.aggregation();
		for (Quality want : new Quality[] { Quality.GOOD, Quality.STALE }) {
			int n = 0;
			double acc = 0;
			long updated = 0;
			for (String name : binding.datapoints()) {
				Datapoint datapoint = _model.get(name);
				if (datapoint == null || datapoint.quality() != want) {
					continue;
				}
				double x = numberOf(datapoint);
				if (n == 0) {
					acc = x;
				} else {
					switch (aggregation) {
						case MEAN -> acc = acc + x;
						case MIN -> acc = x < acc ? x : acc;
						case MAX -> acc = x > acc ? x : acc;
						case ANY -> acc = (acc != 0 || x != 0) ? 1 : 0;
						case ALL -> acc = (acc != 0 && x != 0) ? 1 : 0;
						default -> {
							// A single datapoint.
						}
					}
				}
				updated = Math.max(updated, datapoint.updatedMs());
				n++;
			}
			if (n > 0) {
				if (aggregation == Aggregation.MEAN && n > 1) {
					acc = acc / n;
				} else if (n == 1 && (aggregation == Aggregation.ANY || aggregation == Aggregation.ALL)) {
					acc = acc != 0 ? 1 : 0;
				}
				return new InputValue(acc, want, updated, true);
			}
		}
		return new InputValue(0, Quality.UNKNOWN, 0, true);
	}

	private void publishMirror(String name, ValueType type, double value, Quality quality) {
		Datapoint datapoint = name != null ? _model.get(name) : null;
		if (datapoint == null || quality == Quality.UNKNOWN) {
			return;
		}
		Val v = valueOf(type, value);
		if (v == null) {
			// The value does not fit the datapoint: it keeps its previous value.
			return;
		}
		boolean same = datapoint.quality() != Quality.UNKNOWN && Val.identical(datapoint.value(), v);
		if (datapoint.quality() == quality && same) {
			return;
		}
		if (datapoint.quality() != Quality.GOOD || !same) {
			_model.publish(name, v);
		}
		if (quality == Quality.STALE) {
			_model.reportStale(name);
		}
	}

	private void writeOutput(int block, int port, double value) {
		BlockSpec spec = _set.at(block);
		Port decl = spec.manifest().ports().get(port);
		Instance in = _instances[block];
		PortBinding binding = spec.ports().get(port);
		if (binding.bound()) {
			String name = binding.datapoints().get(0);
			Datapoint datapoint = _model.get(name);
			Val v = datapoint != null ? valueOf(datapoint.type(), value) : null;
			if (datapoint == null) {
				log(block, LogCode.WRITE_REFUSED, Level.ERROR,
					"output '" + decl.id() + "': its datapoint is not in the model");
			} else if (v == null) {
				log(block, LogCode.WRITE_REFUSED, Level.ERROR, "output '" + decl.id() + "': " + format(value)
					+ " does not fit the " + datapoint.type().externalName() + " datapoint '" + name + "'");
			} else {
				CommandStatus status = _model.command(name, v);
				if (status != CommandStatus.OK) {
					log(block, LogCode.WRITE_REFUSED, Level.ERROR,
						"output '" + decl.id() + "': command to '" + name + "' refused: " + status.text());
				} else {
					_observer.blockCommanded(spec.id(), datapoint, datapoint.value());
				}
			}
		}
		publishMirror(in._mirrorPorts[port], decl.type().datapointType(), value, Quality.GOOD);
	}

	private void writeFailsafe(int block) {
		Manifest m = _set.at(block).manifest();
		for (int p = 0; p < m.ports().size(); p++) {
			Port decl = m.ports().get(p);
			if (!decl.isInput() && !decl.diagnostic()) {
				writeOutput(block, p, decl.failsafe());
			}
		}
	}

	// ---- Calls -----------------------------------------------------------------------------------

	private final class Context implements BlockContext {

		private final int _block;

		private final Instance _in;

		private final Manifest _m;

		boolean _faulted;

		String _reason = "";

		Context(int block) {
			_block = block;
			_in = _instances[block];
			_m = _set.at(block).manifest();
		}

		@Override
		public long now() {
			return _engine.inCascade() ? _engine.cascadeMs() : _clock.getAsLong();
		}

		@Override
		public DateTime wall() {
			return _engine.cascadeWall();
		}

		@Override
		public SunPosition sun() {
			return _engine.cascadeSun();
		}

		@Override
		public InputValue read(int port) {
			if (port < 0 || port >= _m.ports().size() || !_m.ports().get(port).isInput()) {
				return InputValue.UNBOUND;
			}
			return aggregate(_block, port);
		}

		@Override
		public int count(int port) {
			if (port < 0 || port >= _m.ports().size() || !_m.ports().get(port).isInput()) {
				return 0;
			}
			return _set.at(_block).ports().get(port).datapoints().size();
		}

		@Override
		public InputValue readAt(int port, int k) {
			if (k < 0 || k >= count(port)) {
				return InputValue.UNBOUND;
			}
			Datapoint datapoint = _model.get(_set.at(_block).ports().get(port).datapoints().get(k));
			if (datapoint == null || datapoint.quality() == Quality.UNKNOWN) {
				return new InputValue(0, Quality.UNKNOWN, 0, true);
			}
			return new InputValue(numberOf(datapoint), datapoint.quality(), datapoint.updatedMs(), true);
		}

		@Override
		public double param(int index) {
			return index >= 0 && index < _in._params.length ? _in._params[index] : 0;
		}

		@Override
		public void write(int port, double value) {
			if (_faulted) {
				return;
			}
			if (port < 0 || port >= _m.ports().size() || _m.ports().get(port).isInput()) {
				fault("a write to a port that is no output");
				return;
			}
			Port d = _m.ports().get(port);
			String why = null;
			if (!Double.isFinite(value)) {
				why = "is not finite";
			} else if (d.type() == PortType.BOOLEAN && value != 0 && value != 1) {
				why = "is not 0 or 1";
			} else if ((d.type() == PortType.INTEGER || d.type() == PortType.ENUMERATION) && value != Math.floor(value)) {
				why = "is not a whole number";
			} else if (d.type() == PortType.INTEGER && (value < -2147483648.0 || value > 2147483647.0)) {
				why = "is outside the signed 32-bit range";
			} else if (d.type() == PortType.ENUMERATION && (value < 0 || value > 4294967295.0)) {
				why = "is outside 0..4294967295";
			} else if ((d.min() != null && value < d.min().doubleValue()) || (d.max() != null && value > d.max().doubleValue())) {
				why = "is outside the port's range";
			}
			if (why != null) {
				fault("the value " + format(value) + " of '" + d.id() + "' " + why);
				return;
			}
			writeOutput(_block, port, value);
		}

		@Override
		public void setTimer(int timer, long ms) {
			if (_faulted) {
				return;
			}
			if (timer < 0 || timer >= _m.timers().size()) {
				fault("a timer the type does not declare");
				return;
			}
			if (ms < Manifest.TIMER_MIN_MS || ms > Manifest.TIMER_MAX_MS) {
				BlockRuntime.this.log(_block, LogCode.TIMER_REFUSED, Level.ERROR,
					"timer '" + _m.timers().get(timer) + "' of " + ms + " ms is not in 1 ms..30 d");
				fault("a timer outside 1 ms..30 d");
				return;
			}
			_in._timers[timer] = now() + ms;
			_in._running[timer] = true;
		}

		@Override
		public void cancelTimer(int timer) {
			if (timer >= 0 && timer < _m.timers().size()) {
				_in._running[timer] = false;
			}
		}

		@Override
		public boolean timerRunning(int timer) {
			return timer >= 0 && timer < _m.timers().size() && _in._running[timer];
		}

		@Override
		public double[] state() {
			return _in._state;
		}

		@Override
		public void log(String text) {
			BlockRuntime.this.log(_block, LogCode.LOG, Level.INFO, text);
		}

		@Override
		public void fault(String reason) {
			if (!_faulted) {
				_faulted = true;
				_reason = reason;
			}
		}
	}

	private void call(int block, CallKind kind, int arg, boolean derived, StartReason reason, boolean restored) {
		Instance in = _instances[block];
		BlockSpec spec = _set.at(block);
		Manifest m = spec.manifest();
		if (derived) {
			Culprit culprit = _engine.derivedCall();
			if (culprit != null) {
				log(block, LogCode.LOOP_GUARD, Level.ERROR,
					"call refused after " + RuleSet.MAX_DERIVED_FIRINGS + " derived firings (most: " + culprit.id() + " with "
						+ culprit.firings() + "); the cascade is aborted");
				_observer.loopGuard(spec.id(), culprit.id(), culprit.firings());
				return;
			}
			in._cascadeCalls++;
		}
		Context cx = new Context(block);
		BlockType type = spec.type();
		String what = "";
		switch (kind) {
			case START:
				what = reason.externalName();
				in._started = true;
				type.start(cx, reason, restored);
				break;
			case INPUT:
				what = m.ports().get(arg).id();
				type.input(cx, arg);
				break;
			case TIMER:
				what = m.timers().get(arg);
				type.timer(cx, arg);
				break;
			case PERIOD:
				type.period(cx);
				break;
			case PARAMETER:
				what = m.params().get(arg).id();
				type.parameter(cx, arg);
				break;
			default:
				break;
		}
		in._calls++;
		in._lastCall = kind;
		in._lastCallMs = cx.now();
		in._lastCallArg = what;
		for (int f = 0; f < m.state().size() && !cx._faulted; f++) {
			if (!Double.isFinite(in._state[f])) {
				cx.fault("the state field '" + m.state().get(f).id() + "' is not finite");
			}
		}
		if (cx._faulted) {
			fault(block, cx._reason);
			return;
		}
		recordChanged(block);
	}

	private void stop(int block, Status status) {
		Instance in = _instances[block];
		in._status = status;
		in._started = false;
		Arrays.fill(in._running, false);
		in._periodArmed = false;
		writeFailsafe(block);
	}

	private void fault(int block, String reason) {
		Instance in = _instances[block];
		in._fault = reason;
		in._faultMs = _engine.inCascade() ? _engine.cascadeMs() : _clock.getAsLong();
		in._dirty = false;
		log(block, LogCode.FAULT, Level.ERROR, reason + "; stopped, the outputs at their failsafe values");
		stop(block, Status.FAULT);
	}

	// ---- Engine.BlockHost ------------------------------------------------------------------------

	@Override
	public boolean wants(String datapoint) {
		return _interest.contains(datapoint);
	}

	@Override
	public void datapointEvent(String datapoint, boolean derived) {
		if (!wants(datapoint)) {
			return;
		}
		for (int b = 0; b < _instances.length; b++) {
			BlockSpec spec = _set.at(b);
			Manifest m = spec.manifest();
			Instance in = _instances[b];
			for (int p = 0; p < m.ports().size(); p++) {
				Port decl = m.ports().get(p);
				PortBinding binding = spec.ports().get(p);
				if (!decl.isInput() || !binding.datapoints().contains(datapoint)) {
					continue;
				}
				InputValue v = aggregate(b, p);
				boolean changed = v.value() != in._inputValue[p] || v.quality() != in._inputQuality[p];
				in._inputValue[p] = v.value();
				in._inputQuality[p] = v.quality();
				if (binding.datapoints().size() > 1 && changed) {
					publishMirror(in._mirrorPorts[p], decl.type().datapointType(), v.value(), v.quality());
				}
				InputTrigger trigger = decl.trigger();
				if (in._status == Status.RUNNING && in._started
					&& (trigger == InputTrigger.UPDATE || (trigger == InputTrigger.CHANGE && changed))) {
					call(b, CallKind.INPUT, p, derived, null, false);
				}
				if (_engine.aborted()) {
					return;
				}
			}
		}
	}

	@Override
	public void blockEvent(int kind, int block, int arg, boolean derived) {
		if (block < 0 || block >= _instances.length) {
			return;
		}
		Instance in = _instances[block];
		Manifest m = _set.at(block).manifest();
		switch (kind) {
			case PARAMETER_CHANGED:
				// The value is the parameter's already; its mirror, then the call (spec/blocks.md 5.1).
				if (arg < m.params().size()) {
					publishMirror(in._mirrorParams[arg], m.params().get(arg).type().datapointType(), in._params[arg],
						Quality.GOOD);
					if (in._status == Status.RUNNING && in._started) {
						call(block, CallKind.PARAMETER, arg, derived, null, false);
					}
				}
				break;
			case ENABLED_CHANGED:
				publishMirror(in._mirrorEnabled, ValueType.BOOLEAN, in._enabled ? 1 : 0, Quality.GOOD);
				if (in._status == Status.FAULT) {
					// A fault ends with a restart or a new image only (spec/blocks.md 7).
					break;
				}
				if (in._enabled && in._status == Status.DISABLED) {
					in._status = Status.RUNNING;
					Arrays.fill(in._running, false);
					in._periodNext = _engine.cascadeMs() + m.periodMs();
					in._periodArmed = m.periodMs() > 0;
					call(block, CallKind.START, 0, derived, StartReason.ENABLE, true);
				} else if (!in._enabled && in._status != Status.DISABLED) {
					stop(block, Status.DISABLED);
				}
				break;
			case RESTART:
				if (in._status == Status.FAULT) {
					// As at boot: the persisted record, the other fields fresh (spec/blocks.md B11).
					for (int f = 0; f < m.state().size(); f++) {
						in._state[f] = m.state().get(f).initial();
						in._stored[f] = m.state().get(f).initial();
					}
					boolean restored = loadRecord(block);
					in._fault = null;
					if (!in._enabled) {
						// Its outputs are at their failsafe values already.
						in._status = Status.DISABLED;
						break;
					}
					in._status = Status.RUNNING;
					Arrays.fill(in._running, false);
					in._periodNext = _engine.cascadeMs() + m.periodMs();
					in._periodArmed = m.periodMs() > 0;
					call(block, CallKind.START, 0, derived, StartReason.RESTART, restored);
				}
				break;
			default:
				break;
		}
	}

	@Override
	public long nextDeadline() {
		long earliest = Long.MAX_VALUE;
		for (Instance in : _instances) {
			if (in._status != Status.RUNNING) {
				continue;
			}
			for (int t = 0; t < in._timers.length; t++) {
				if (in._running[t] && in._timers[t] < earliest) {
					earliest = in._timers[t];
				}
			}
			if (in._periodArmed && in._periodNext < earliest) {
				earliest = in._periodNext;
			}
		}
		return earliest;
	}

	@Override
	public void runDue(long now) {
		// The earliest deadline; on a tie the first block, within it the timers in manifest order,
		// then the period (spec/blocks.md 5.1).
		int bestBlock = -1;
		int bestSlot = 0;
		long best = Long.MAX_VALUE;
		for (int b = 0; b < _instances.length; b++) {
			Instance in = _instances[b];
			if (in._status != Status.RUNNING) {
				continue;
			}
			int timers = in._timers.length;
			for (int t = 0; t <= timers; t++) {
				boolean armed = t < timers ? in._running[t] : in._periodArmed;
				long deadline = t < timers ? in._timers[t] : in._periodNext;
				if (armed && deadline <= now && deadline < best) {
					best = deadline;
					bestBlock = b;
					bestSlot = t;
				}
			}
		}
		if (bestBlock < 0) {
			return;
		}
		Instance in = _instances[bestBlock];
		Manifest m = _set.at(bestBlock).manifest();
		if (bestSlot < m.timers().size()) {
			in._running[bestSlot] = false;
			call(bestBlock, CallKind.TIMER, bestSlot, false, null, false);
		} else {
			long next = in._periodNext + m.periodMs();
			while (next <= now) {
				// A period that passed while the loop was busy is called once.
				next += m.periodMs();
			}
			in._periodNext = next;
			call(bestBlock, CallKind.PERIOD, 0, false, null, false);
		}
	}

	@Override
	public int startCount() {
		return _instances.length;
	}

	@Override
	public void startBlock(int index, RuleSet.StartReason reason) {
		if (index < 0 || index >= _instances.length) {
			return;
		}
		Instance in = _instances[index];
		Manifest m = _set.at(index).manifest();
		boolean wasFault = in._wasFault;
		in._wasFault = false;
		if (!in._enabled) {
			in._status = Status.DISABLED;
			in._started = false;
			if (!wasFault) {
				// A disabled block's outputs are defined (spec/blocks.md B10); a faulted one's are at their
				// failsafe values already: nothing is written (6.1).
				writeFailsafe(index);
			}
			return;
		}
		in._status = Status.RUNNING;
		Arrays.fill(in._running, false);
		in._periodNext = _engine.cascadeMs() + m.periodMs();
		in._periodArmed = m.periodMs() > 0;
		boolean restored = in._restored;
		// A later start (enable) has the record in RAM.
		in._restored = true;
		call(index, CallKind.START, 0, false, reason == RuleSet.StartReason.BOOT ? StartReason.BOOT : StartReason.IMAGE,
			restored);
	}

	@Override
	public void cascadeBegun() {
		for (Instance in : _instances) {
			in._cascadeCalls = 0;
		}
	}

	@Override
	public Culprit busiest() {
		Culprit result = null;
		for (int b = 0; b < _instances.length; b++) {
			int n = _instances[b]._cascadeCalls;
			if (n > 0 && (result == null || n > result.firings())) {
				result = new Culprit(_set.at(b).id(), n);
			}
		}
		return result;
	}

	// ---- Changes from the UI, the API and the mirrors' commands ----------------------------------

	private void refuse(String block, String parameter, String reason) {
		int b = _set.find(block);
		logText(block, b >= 0 ? _instances[b] : null, LogCode.PARAMETER_REFUSED, Level.WARNING,
			"parameter '" + parameter + "' refused: " + reason);
	}

	/**
	 * Logs the refusal of a change found by a check (a change of several members refused whole).
	 */
	public void refused(String block, String parameter, String reason) {
		refuse(block, parameter, reason);
	}

	/**
	 * The outcome of a check of a change.
	 *
	 * @param status
	 *        {@link ChangeStatus#OK} or why it is refused.
	 * @param reason
	 *        The reason in words.
	 * @param value
	 *        A checked parameter's value.
	 */
	public record Check(ChangeStatus status, String reason, double value) {

		/** Whether the change is allowed. */
		public boolean ok() {
			return status == ChangeStatus.OK;
		}
	}

	private Check findParam(String block, String parameter, int[] found) {
		int b = _set.find(block);
		if (b < 0) {
			return new Check(ChangeStatus.UNKNOWN_BLOCK, "no such block", 0);
		}
		Manifest m = _set.at(b).manifest();
		int p = m.param(parameter);
		if (p < 0) {
			return new Check(ChangeStatus.UNKNOWN_PARAMETER, "no such parameter", 0);
		}
		if (!m.params().get(p).adjustable()) {
			return new Check(ChangeStatus.NOT_ADJUSTABLE, "not adjustable", 0);
		}
		found[0] = b;
		found[1] = p;
		return new Check(ChangeStatus.OK, "", 0);
	}

	/**
	 * Checks a parameter change without changing or logging anything: <code>json</code>
	 * <code>null</code> checks a reset.
	 */
	public Check checkParameter(String block, String parameter, Object json, boolean reset) {
		int[] found = new int[2];
		Check check = findParam(block, parameter, found);
		if (!check.ok() || reset) {
			return check;
		}
		Param decl = _set.at(found[0]).manifest().params().get(found[1]);
		BlockSet.ParamProblem[] reason = { null };
		Double v = BlockSet.paramFromJson(decl, json, reason);
		String problem = v == null ? reason[0].text() : BlockSet.paramProblem(decl, v.doubleValue());
		if (problem != null) {
			return new Check(ChangeStatus.INVALID_VALUE, problem, 0);
		}
		return new Check(ChangeStatus.OK, "", v.doubleValue());
	}

	/** Checks a change of the enable switch. */
	public Check checkEnabled(String block) {
		int b = _set.find(block);
		if (b < 0) {
			return new Check(ChangeStatus.UNKNOWN_BLOCK, "no such block", 0);
		}
		if (!_set.at(b).enabledAdjustable()) {
			return new Check(ChangeStatus.NOT_ADJUSTABLE, "not adjustable", 0);
		}
		return new Check(ChangeStatus.OK, "", 0);
	}

	/** Checks a restart. */
	public Check checkRestart(String block) {
		int b = _set.find(block);
		if (b < 0) {
			return new Check(ChangeStatus.UNKNOWN_BLOCK, "no such block", 0);
		}
		if (_instances[b]._status != Status.FAULT) {
			return new Check(ChangeStatus.NOT_FAULTED, "the block has not faulted", 0);
		}
		return new Check(ChangeStatus.OK, "", 0);
	}

	/** Sets a parameter from the UI or API to a JSON value of its image form. */
	public Check setParameterJson(String block, String parameter, Object json) {
		Check check = checkParameter(block, parameter, json, false);
		if (!check.ok()) {
			refuse(block, parameter, check.reason());
			return check;
		}
		return setParameter(block, parameter, check.value());
	}

	/** Sets a parameter from the UI or API. */
	public Check setParameter(String block, String parameter, double value) {
		int[] found = new int[2];
		Check check = findParam(block, parameter, found);
		if (check.ok()) {
			String problem = BlockSet.paramProblem(_set.at(found[0]).manifest().params().get(found[1]), value);
			if (problem != null) {
				check = new Check(ChangeStatus.INVALID_VALUE, problem, 0);
			}
		}
		if (!check.ok()) {
			refuse(block, parameter, check.reason());
			return check;
		}
		applyParam(found[0], found[1], value);
		return check;
	}

	/** Returns a parameter to the image's value. */
	public Check resetParameter(String block, String parameter) {
		int[] found = new int[2];
		Check check = findParam(block, parameter, found);
		if (!check.ok()) {
			refuse(block, parameter, check.reason());
			return check;
		}
		applyParam(found[0], found[1], _set.at(found[0]).defaults()[found[1]]);
		return check;
	}

	/** Switches the block on or off. */
	public Check setEnabled(String block, boolean enabled) {
		Check check = checkEnabled(block);
		if (!check.ok()) {
			refuse(block, "enabled", check.reason());
			return check;
		}
		applyEnabled(_set.find(block), enabled);
		return check;
	}

	/** Restarts a faulted block. */
	public Check restart(String block) {
		Check check = checkRestart(block);
		if (check.ok()) {
			_engine.queueBlockEvent(RESTART, _set.find(block), 0);
		}
		return check;
	}

	private void applyParam(int block, int index, double value) {
		BlockSpec spec = _set.at(block);
		Param decl = spec.manifest().params().get(index);
		Instance in = _instances[block];
		double old = in._params[index];
		boolean was = in._overridden[index];
		String key = BlockSet.storeKey(spec.id());
		in._params[index] = value;
		if (value == spec.defaults()[index]) {
			// The default is no override (spec/rules.md D5).
			in._overridden[index] = false;
			if (was) {
				_store.erase(key, decl.id());
			}
		} else {
			in._overridden[index] = true;
			if (!was || old != value) {
				_store.write(key, decl.id(), toVal(decl.type(), value));
			}
		}
		if (old != value) {
			_engine.queueBlockEvent(PARAMETER_CHANGED, block, index);
		}
	}

	private void applyEnabled(int block, boolean enabled) {
		BlockSpec spec = _set.at(block);
		Instance in = _instances[block];
		boolean old = in._enabled;
		String key = BlockSet.storeKey(spec.id());
		in._enabled = enabled;
		if (enabled == spec.enabled()) {
			if (in._enabledOverridden) {
				_store.erase(key, "enabled");
			}
			in._enabledOverridden = false;
		} else {
			if (!in._enabledOverridden || old != enabled) {
				_store.write(key, "enabled", Val.ofBool(enabled));
			}
			in._enabledOverridden = true;
		}
		if (old != enabled) {
			_engine.queueBlockEvent(ENABLED_CHANGED, block, enabled ? 1 : 0);
		}
	}

	private boolean mirrorCommand(Datapoint datapoint, Val value) {
		for (int b = 0; b < _instances.length; b++) {
			BlockSpec spec = _set.at(b);
			Manifest m = spec.manifest();
			Instance in = _instances[b];
			if (datapoint.name().equals(in._mirrorEnabled)) {
				if (!spec.enabledAdjustable() || value.type() != Type.BOOLEAN) {
					refuse(spec.id(), "enabled", spec.enabledAdjustable() ? "not a boolean" : "not adjustable");
					return false;
				}
				applyEnabled(b, value.bool());
				return true;
			}
			for (int p = 0; p < m.params().size(); p++) {
				if (!datapoint.name().equals(in._mirrorParams[p])) {
					continue;
				}
				Param decl = m.params().get(p);
				String reason;
				if (value.type() != decl.type().datapointType().expressionType()) {
					reason = "not a value of the parameter's type";
				} else {
					reason = BlockSet.paramProblem(decl, numberOf(value));
				}
				if (reason != null) {
					refuse(spec.id(), decl.id(), reason);
					return false;
				}
				applyParam(b, p, numberOf(value));
				return true;
			}
		}
		return false;
	}

	// ---- Accessors -------------------------------------------------------------------------------

	/** The status of the block. */
	public Status status(int block) {
		return _instances[block]._status;
	}

	/** The fault's reason, <code>null</code> when it has not faulted. */
	public String faultReason(int block) {
		return _instances[block]._status == Status.FAULT ? _instances[block]._fault : null;
	}

	/** The number of calls since the boot. */
	public int calls(int block) {
		return _instances[block]._calls;
	}

	/** The kind of the last call. */
	public CallKind lastCall(int block) {
		return _instances[block]._lastCall;
	}

	/** The number of logged problems. */
	public int errors(int block) {
		return _instances[block]._errors;
	}

	/** The last logged problem, <code>null</code> for none. */
	public String lastError(int block) {
		return _instances[block]._lastError;
	}

	/** The state record. */
	public double[] state(int block) {
		return _instances[block]._state;
	}

	/** A parameter's current value. */
	public double param(int block, int index) {
		return _instances[block]._params[index];
	}

	/** Whether the parameter has an override. */
	public boolean overridden(int block, int index) {
		return _instances[block]._overridden[index];
	}

	/** Whether the block is switched on. */
	public boolean enabled(int block) {
		return _instances[block]._enabled;
	}

	/** An input as a call would read it now. */
	public InputValue input(int block, int port) {
		return aggregate(block, port);
	}

	/** The remaining time of a running timer, <code>null</code> when it does not run. */
	public Long timerRemaining(int block, int timer) {
		Instance in = _instances[block];
		return in._running[timer] ? Long.valueOf(in._timers[timer] - _clock.getAsLong()) : null;
	}

	/** The ids of the blocks in block order. */
	public List<String> ids() {
		List<String> ids = new ArrayList<>();
		for (BlockSpec spec : _set.specs()) {
			ids.add(spec.id());
		}
		return ids;
	}

}
