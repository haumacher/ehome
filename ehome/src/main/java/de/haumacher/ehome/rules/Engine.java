/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.rules;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

import de.haumacher.ehome.rules.Astro.SunEvent;
import de.haumacher.ehome.rules.DatapointModel.CommandStatus;
import de.haumacher.ehome.rules.DatapointModel.Datapoint;
import de.haumacher.ehome.rules.DatapointModel.Origin;
import de.haumacher.ehome.rules.DatapointModel.Quality;
import de.haumacher.ehome.rules.DatapointModel.Update;
import de.haumacher.ehome.rules.Node.Fn;
import de.haumacher.ehome.rules.Node.Op;
import de.haumacher.ehome.rules.RuleLoader.LoadError;
import de.haumacher.ehome.rules.RuleLoader.LoadException;
import de.haumacher.ehome.rules.RuleLoader.Refusal;
import de.haumacher.ehome.rules.RuleLoader.ScheduleDirectory;
import de.haumacher.ehome.rules.RuleSet.Action;
import de.haumacher.ehome.rules.RuleSet.ActionKind;
import de.haumacher.ehome.rules.RuleSet.Level;
import de.haumacher.ehome.rules.RuleSet.Parameter;
import de.haumacher.ehome.rules.RuleSet.Reaction;
import de.haumacher.ehome.rules.RuleSet.Rule;
import de.haumacher.ehome.rules.RuleSet.StartReason;
import de.haumacher.ehome.rules.RuleSet.Trigger;
import de.haumacher.ehome.rules.Val.Color;
import de.haumacher.ehome.rules.Val.Date;
import de.haumacher.ehome.rules.Val.DateTime;
import de.haumacher.ehome.rules.Val.TimeOfDay;

/**
 * The reference rule interpreter (<code>spec/rules.md</code> 7): owns the loaded {@link RuleSet}
 * and its runtime (parameter values, timers, states, match memories, change tracking,
 * diagnostics), listens to the {@link DatapointModel} for triggers, runs cascades and commands
 * datapoints. A port of homecontrol <code>core/src/rules/engine.cpp</code>.
 *
 * <p>
 * The engine has no thread and no event loop of its own: external events (datapoint updates from
 * outside, schedule firings, parameter changes, starts) are queued, and the driver processes them
 * with {@link #drain()}; the expiries of rule timers are processed by
 * {@link #processDueTimers()} when the {@link #nextDeadline()} has come. Time comes from the
 * injected monotonic clock and {@link TimeSource}, so that a test or a simulation decides when
 * anything happens.
 * </p>
 */
public final class Engine implements DatapointModel.Listener {

	/** External events waiting for their cascade. */
	public static final int INBOUND_CAPACITY = 64;

	/**
	 * The wall clock and the sun as the rules see them (<code>spec/rules.md</code> D9, 7.7).
	 */
	public interface TimeSource {

		/**
		 * The current local time with its day of the week and no flags; <code>null</code> while the
		 * wall clock is not synchronized.
		 */
		DateTime localNow();

		/**
		 * Today's local time of the event; <code>null</code> without coordinates or when it does not
		 * occur today.
		 */
		TimeOfDay sunTime(SunEvent event);
	}

	/**
	 * The log codes of <code>spec/rules.md</code> 9.2.
	 */
	public enum LogCode {
		/** A log action. */
		LOG("log"),
		/** A reaction condition has no value. */
		CONDITION_NO_VALUE("conditionNoValue"),
		/** A write value has no value. */
		VALUE_NO_VALUE("valueNoValue"),
		/** An evaluation error. */
		EVAL_ERROR("evalError"),
		/** A write was refused. */
		WRITE_REFUSED("writeRefused"),
		/** A timer duration was refused. */
		TIMER_REFUSED("timerRefused"),
		/** A parameter value was refused. */
		PARAMETER_REFUSED("parameterRefused"),
		/** An override was reset by a new image. */
		PARAMETER_RESET("parameterReset"),
		/** A new image does not have the rule's state. */
		STATE_RESET("stateReset"),
		/** A cascade exceeded 32 derived firings. */
		LOOP_GUARD("loopGuard"),
		/** An event queue was full. */
		QUEUE_OVERFLOW("queueOverflow");

		private final String _name;

		LogCode(String name) {
			_name = name;
		}

		/** The code's name in the specification. */
		public String externalName() {
			return _name;
		}
	}

	/**
	 * Observes what the engine does. Must not call back into the engine.
	 */
	public interface Observer {

		/**
		 * A log entry with a code of <code>spec/rules.md</code> 9.2: the rule id (<code>-</code> when
		 * no rule is concerned), the code, the level and the text (for <code>log</code>, the
		 * formatted message).
		 */
		default void ruleLogged(String rule, LogCode code, Level level, String text) {
			// Nothing by default.
		}

		/** A successful command by a rule. */
		default void ruleCommanded(String rule, Datapoint datapoint, Val value) {
			// Nothing by default.
		}

		/** A parameter's current value changed. */
		default void parameterChanged(String rule, String parameter, Val value) {
			// Nothing by default.
		}
	}

	/**
	 * Outcome of a parameter change from the UI or API (<code>spec/rules.md</code> 8.2).
	 */
	public enum ParameterStatus {
		/** Taken. */
		OK,
		/** No such rule. */
		UNKNOWN_RULE,
		/** No such parameter. */
		UNKNOWN_PARAMETER,
		/** The parameter is not adjustable. */
		NOT_ADJUSTABLE,
		/** The value is not valid. */
		INVALID_VALUE;
	}

	private enum EventKind {
		DATAPOINT, SCHEDULE, TIMER, START, PARAMETER, START_ALL;
	}

	private record Event(EventKind kind, String name, int rule, int index, boolean changed, Origin origin,
			StartReason reason, boolean external) {

		static Event datapoint(String name, boolean changed, Origin origin) {
			return new Event(EventKind.DATAPOINT, name, -1, -1, changed, origin, null, false);
		}

		static Event schedule(String id) {
			return new Event(EventKind.SCHEDULE, id, -1, -1, false, null, null, false);
		}

		static Event timer(int rule, int timer) {
			return new Event(EventKind.TIMER, null, rule, timer, false, null, null, false);
		}

		static Event start(int rule, StartReason reason) {
			return new Event(EventKind.START, null, rule, -1, false, null, reason, false);
		}

		static Event parameter(int rule, int parameter) {
			return new Event(EventKind.PARAMETER, null, rule, parameter, false, null, null, false);
		}

		static Event startAll(StartReason reason) {
			return new Event(EventKind.START_ALL, null, -1, -1, false, null, reason, false);
		}
	}

	/** Per-root flags. */
	private static final int EVAL_ERROR_LOGGED = 0x01;

	private static final int NO_VALUE_LOGGED = 0x02;

	private static final int MATCH_MEMORY = 0x04;

	private static final int MATCHED = 0x08;

	private static final class RuleRuntime {
		int _firings;

		int _errors;

		long _lastTriggerMs;

		String _lastTrigger;

		long _lastActionMs;

		String _lastAction;

		long _lastErrorMs;

		LogCode _lastErrorCode;

		String _lastErrorText;

		int _cascadeFirings;

		int _peakCascadeFirings;

		int _loopGuards;

		int _state = -1;

		boolean _hasState;

		RuleRuntime copy() {
			RuleRuntime copy = new RuleRuntime();
			copy._firings = _firings;
			copy._errors = _errors;
			copy._lastTriggerMs = _lastTriggerMs;
			copy._lastTrigger = _lastTrigger;
			copy._lastActionMs = _lastActionMs;
			copy._lastAction = _lastAction;
			copy._lastErrorMs = _lastErrorMs;
			copy._lastErrorCode = _lastErrorCode;
			copy._lastErrorText = _lastErrorText;
			copy._peakCascadeFirings = _peakCascadeFirings;
			copy._loopGuards = _loopGuards;
			return copy;
		}
	}

	private static final class ParamRuntime {
		Val _value;

		boolean _overridden;
	}

	private static final class TimerRuntime {
		long _deadline;

		long _sequence;

		boolean _running;
	}

	private static final class ChangeRecord {
		long _time;

		boolean _known;
	}

	private final DatapointModel _model;

	private final LongSupplier _clock;

	private final ParameterStore _store;

	private final TimeSource _time;

	private Observer _observer = new Observer() {
		// Nothing.
	};

	private RuleSet _set;

	private RuleRuntime[] _rules;

	private ParamRuntime[][] _params;

	private TimerRuntime[][] _timers;

	private byte[] _flags;

	private Map<String, ChangeRecord> _changes = new HashMap<>();

	private boolean _booted;

	private final ArrayDeque<Event> _inbound = new ArrayDeque<>();

	private boolean _inboundOverflowLogged;

	private final ArrayDeque<Event> _queue = new ArrayDeque<>();

	private boolean _inCascade;

	private boolean _aborted;

	private boolean _overflowLogged;

	private int _derivedFirings;

	private long _cascadeMs;

	private DateTime _wall;

	private int _currentRule;

	private long _timerSequence;

	/** The error of the evaluation in progress. */
	private String _evalError;

	/**
	 * Creates an engine on the model; it listens to the model until {@link #close()}.
	 *
	 * @param clock
	 *        The monotonic clock in milliseconds.
	 * @param store
	 *        The persisted parameter overrides (kept across a reboot by giving the same store to the
	 *        next engine).
	 */
	public Engine(DatapointModel model, LongSupplier clock, ParameterStore store, TimeSource time) {
		_model = model;
		_clock = clock;
		_store = store;
		_time = time;
		_model.addListener(this);
	}

	/** Stops listening to the model. */
	public void close() {
		_model.removeListener(this);
	}

	/** Sets the observer. */
	public void setObserver(Observer observer) {
		_observer = observer;
	}

	/** The loaded rules, <code>null</code> before the first load. */
	public RuleSet rules() {
		return _set;
	}

	// ---- Logging ----------------------------------------------------------------------------

	private void logRule(int rule, LogCode code, Level level, String detail, String text) {
		if (_set != null && rule >= 0 && rule < _rules.length) {
			logCode(_set._rules.get(rule)._id, _rules[rule], code, level, detail, text);
		} else {
			logCode("-", null, code, level, detail, text);
		}
	}

	private void logCode(String ruleId, RuleRuntime runtime, LogCode code, Level level, String detail, String text) {
		_observer.ruleLogged(ruleId, code, level, text);
		if (runtime != null && code != LogCode.LOG && level != Level.INFO) {
			runtime._errors++;
			runtime._lastErrorMs = _inCascade ? _cascadeMs : _clock.getAsLong();
			runtime._lastErrorCode = code;
			runtime._lastErrorText = detail;
		}
	}

	// ---- Loading and swapping ---------------------------------------------------------------

	/**
	 * Validates and compiles the rules (the <code>rules</code> member of an image) against the
	 * model's datapoints and the schedules, and swaps them in (<code>spec/rules.md</code> 7.6). Does
	 * not start the rules: call {@link #start(StartReason)}. Pending external events are processed
	 * with the old rules first.
	 *
	 * @return <code>null</code>, or the refusal; a refused load changes nothing.
	 */
	public Refusal load(Object rules, ScheduleDirectory schedules) {
		flush();
		RuleSet next;
		try {
			next = RuleLoader.load(rules, _model, schedules);
		} catch (LoadException ex) {
			return ex.getRefusal();
		}
		Refusal collision = checkParameterKeys(next);
		if (collision != null) {
			return collision;
		}
		install(next);
		return null;
	}

	private static Refusal checkParameterKeys(RuleSet next) {
		Map<Long, String> seen = new HashMap<>();
		for (Rule rule : next._rules) {
			for (Parameter parameter : rule._parameters) {
				Long hash = Long.valueOf(ParameterStore.hash(rule._id, parameter._id));
				String key = ParameterStore.key(rule._id, parameter._id);
				String before = seen.putIfAbsent(hash, key);
				if (before != null) {
					return new Refusal(LoadError.LIMIT_EXCEEDED, rule._id, "",
						"the storage keys of " + before + " and " + key + " collide; rename one");
				}
			}
		}
		return null;
	}

	private void install(RuleSet next) {
		RuleSet oldSet = _set;
		RuleRuntime[] oldRules = _rules;
		ParamRuntime[][] oldParams = _params;
		TimerRuntime[][] oldTimers = _timers;
		boolean boot = !_booted;
		int count = next._rules.size();
		RuleRuntime[] rules = new RuleRuntime[count];
		ParamRuntime[][] params = new ParamRuntime[count][];
		TimerRuntime[][] timers = new TimerRuntime[count][];
		for (int r = 0; r < count; r++) {
			Rule rule = next._rules.get(r);
			int o = oldSet != null ? oldSet.findRule(rule._id) : -1;
			RuleRuntime nr = o >= 0 ? oldRules[o].copy() : new RuleRuntime();
			rules[r] = nr;
			timers[r] = new TimerRuntime[rule._timers.size()];
			for (int t = 0; t < timers[r].length; t++) {
				timers[r][t] = new TimerRuntime();
			}
			if (o >= 0) {
				Rule oldRule = oldSet._rules.get(o);
				RuleRuntime orr = oldRules[o];
				if (orr._hasState) {
					String stateId = oldRule._states.get(orr._state)._id;
					int s = rule.findState(stateId);
					if (s >= 0) {
						nr._state = s;
						nr._hasState = true;
					} else {
						logCode(rule._id, nr, LogCode.STATE_RESET, Level.WARNING, "state not in the new image",
							"state '" + stateId + "' is not in the new image; the rule enters its initial state");
					}
				}
				for (int t = 0; t < rule._timers.size(); t++) {
					int ot = oldRule.findTimer(rule._timers.get(t)._id);
					if (ot >= 0 && oldTimers[o][ot]._running) {
						TimerRuntime nt = timers[r][t];
						nt._deadline = oldTimers[o][ot]._deadline;
						nt._sequence = oldTimers[o][ot]._sequence;
						nt._running = true;
					}
				}
			}
			params[r] = new ParamRuntime[rule._parameters.size()];
			for (int p = 0; p < params[r].length; p++) {
				Parameter decl = rule._parameters.get(p);
				ParamRuntime np = new ParamRuntime();
				params[r][p] = np;
				np._value = decl._default;
				Val override = null;
				if (o >= 0) {
					int op = oldSet._rules.get(o).findParameter(decl._id);
					if (op >= 0 && oldParams[o][op]._overridden) {
						override = oldParams[o][op]._value;
					}
				} else if (boot) {
					override = _store.read(rule._id, decl._id);
				}
				if (override == null) {
					continue;
				}
				if (decl.valid(override)) {
					np._value = override;
					np._overridden = true;
				} else {
					_store.erase(rule._id, decl._id);
					logCode(rule._id, nr, LogCode.PARAMETER_RESET, Level.WARNING, "override no longer fits",
						"override " + Values.format(override) + " of '" + decl._id + "' no longer fits ("
							+ (override.type() != decl._type ? "type changed" : "range or choices")
							+ "); reset to the default");
				}
			}
		}

		// Change tracking continues for datapoints still tracked; new ones start from the model.
		Map<String, ChangeRecord> changes = new HashMap<>();
		for (String name : next._tracked) {
			ChangeRecord old = _changes.get(name);
			if (old != null) {
				changes.put(name, old);
			} else {
				ChangeRecord record = new ChangeRecord();
				Datapoint datapoint = _model.get(name);
				if (datapoint != null && datapoint.quality() != Quality.UNKNOWN) {
					record._time = datapoint.updatedMs();
					record._known = true;
				}
				changes.put(name, record);
			}
		}

		_set = next;
		_rules = rules;
		_params = params;
		_timers = timers;
		_flags = new byte[next._roots.size()];
		_changes = changes;
		_booted = true;

		// Delete the stored overrides that no parameter holds any more.
		_store.prune((logical, value) -> {
			int slash = logical.indexOf('/');
			if (slash < 0) {
				return false;
			}
			int r = _set.findRule(logical.substring(0, slash));
			if (r < 0) {
				return false;
			}
			int p = _set._rules.get(r).findParameter(logical.substring(slash + 1));
			return p >= 0 && _params[r][p]._overridden;
		});
		for (int r = 0; r < count; r++) {
			Rule rule = _set._rules.get(r);
			for (int p = 0; p < rule._parameters.size(); p++) {
				_observer.parameterChanged(rule._id, rule._parameters.get(p)._id, _params[r][p]._value);
			}
		}
	}

	// ---- Queues ----------------------------------------------------------------------------

	private boolean pushInbound(Event event) {
		if (_inbound.size() == INBOUND_CAPACITY) {
			if (!_inboundOverflowLogged) {
				_inboundOverflowLogged = true;
				logRule(-1, LogCode.QUEUE_OVERFLOW, Level.ERROR, "external event queue full",
					INBOUND_CAPACITY + " external events wait already; event dropped");
			}
			return false;
		}
		_inbound.add(event);
		return true;
	}

	private void pushCascade(Event event, int rule) {
		if (_queue.size() == RuleSet.MAX_QUEUE) {
			if (!_overflowLogged) {
				_overflowLogged = true;
				logRule(rule, LogCode.QUEUE_OVERFLOW, Level.ERROR, "event queue full",
					RuleSet.MAX_QUEUE + " events wait in the cascade; event dropped");
			}
			return;
		}
		_queue.add(event);
	}

	/** Whether external events wait for {@link #drain()}. */
	public boolean hasPending() {
		return !_inbound.isEmpty();
	}

	/** Processes the queued external events now. */
	public void flush() {
		if (!_inCascade) {
			drain();
		}
	}

	/**
	 * Processes the queued external events, one cascade each, in arrival order; the expiries of the
	 * rule timers due before each are processed first.
	 */
	public void drain() {
		if (_inCascade) {
			return;
		}
		while (!_inbound.isEmpty()) {
			processDueTimers();
			Event event = _inbound.poll();
			if (_set == null) {
				continue;
			}
			if (event.kind() == EventKind.START_ALL) {
				// Re-baseline the match memories (7.4), then one cascade per rule in rule order (7.5).
				beginCascade();
				for (int i = 0; i < _set._matches.size(); i++) {
					Trigger trigger = _set._matches.get(i);
					Val v = evaluate(trigger._condition, _set._matchRules.get(i).intValue());
					int root = trigger._condition._root;
					_flags[root] = (byte) (v.has() && v.bool() ? _flags[root] | MATCH_MEMORY : _flags[root] & ~MATCH_MEMORY);
				}
				endCascade();
				for (int r = 0; r < _set._rules.size(); r++) {
					beginCascade();
					runStart(r, event.reason());
					processQueue();
					endCascade();
				}
			} else {
				runCascade(event);
			}
		}
		_inboundOverflowLogged = false;
		processDueTimers();
	}

	/**
	 * The earliest deadline of a running rule timer (monotonic ms), {@link Long#MAX_VALUE} if none.
	 */
	public long nextDeadline() {
		long earliest = Long.MAX_VALUE;
		if (_timers != null) {
			for (TimerRuntime[] list : _timers) {
				for (TimerRuntime t : list) {
					if (t._running && t._deadline < earliest) {
						earliest = t._deadline;
					}
				}
			}
		}
		return earliest;
	}

	/**
	 * Processes the expiries of all rule timers due at the clock's current time, each as its own
	 * cascade, in the order of their deadlines (equal deadlines in the order they were started).
	 */
	public void processDueTimers() {
		if (_set == null || _inCascade) {
			return;
		}
		while (true) {
			long now = _clock.getAsLong();
			int bestRule = -1;
			int bestTimer = -1;
			for (int r = 0; r < _timers.length; r++) {
				for (int t = 0; t < _timers[r].length; t++) {
					TimerRuntime timer = _timers[r][t];
					if (!timer._running || timer._deadline > now) {
						continue;
					}
					if (bestRule < 0) {
						bestRule = r;
						bestTimer = t;
						continue;
					}
					TimerRuntime best = _timers[bestRule][bestTimer];
					if (timer._deadline < best._deadline
						|| (timer._deadline == best._deadline && timer._sequence < best._sequence)) {
						bestRule = r;
						bestTimer = t;
					}
				}
			}
			if (bestRule < 0) {
				return;
			}
			// Stopped before its event is processed (7.8).
			_timers[bestRule][bestTimer]._running = false;
			runCascade(Event.timer(bestRule, bestTimer));
		}
	}

	// ---- External inputs --------------------------------------------------------------------

	/**
	 * Starts every rule with the reason (boot or image, <code>spec/rules.md</code> 7.5), each in its
	 * own cascade, after re-baselining the match memories. Queued.
	 */
	public void start(StartReason reason) {
		if (_set == null || reason == StartReason.ENABLE) {
			throw new IllegalStateException("Cannot start the rules with " + reason + ".");
		}
		pushInbound(Event.startAll(reason));
	}

	/** The scheduler fired the entry. Queued. */
	public void scheduleFired(String id) {
		if (_set == null || !_set._schedules.contains(id)) {
			return;
		}
		pushInbound(Event.schedule(id));
	}

	@Override
	public void datapointUpdated(Update update) {
		if (_set == null) {
			return;
		}
		String name = update.datapoint().name();
		boolean changed = update.changed();
		if (changed) {
			ChangeRecord record = _changes.get(name);
			if (record != null) {
				record._time = _clock.getAsLong();
				record._known = true;
			}
		}
		if (!_set._interest.contains(name)) {
			return;
		}
		Event event = Event.datapoint(name, changed, update.origin());
		if (_inCascade) {
			pushCascade(event, _currentRule);
		} else {
			pushInbound(event);
		}
	}

	private void refuseParameter(String rule, String parameter, String reason) {
		int r = _set == null ? -1 : _set.findRule(rule);
		logCode(rule, r >= 0 ? _rules[r] : null, LogCode.PARAMETER_REFUSED, Level.WARNING, reason,
			"parameter '" + parameter + "' refused: " + reason);
	}

	private String findAdjustable(String rule, String parameter, int[] found) {
		int r = _set == null ? -1 : _set.findRule(rule);
		if (r < 0) {
			return "no such rule";
		}
		int p = _set._rules.get(r).findParameter(parameter);
		if (p < 0) {
			return "no such parameter";
		}
		if (!_set._rules.get(r)._parameters.get(p)._adjustable) {
			return "not adjustable";
		}
		found[0] = r;
		found[1] = p;
		return null;
	}

	private static ParameterStatus statusOf(String reason) {
		switch (reason) {
			case "no such rule":
				return ParameterStatus.UNKNOWN_RULE;
			case "no such parameter":
				return ParameterStatus.UNKNOWN_PARAMETER;
			case "not adjustable":
				return ParameterStatus.NOT_ADJUSTABLE;
			default:
				return ParameterStatus.INVALID_VALUE;
		}
	}

	/**
	 * Sets a parameter from the UI or API to a JSON literal value (<code>spec/rules.md</code> 8.2).
	 */
	public ParameterStatus setParameterJson(String rule, String parameter, Object json) {
		int[] found = new int[2];
		String reason = findAdjustable(rule, parameter, found);
		if (reason == null) {
			Type type = _set._rules.get(found[0])._parameters.get(found[1])._type;
			Values.Literal literal = Values.fromJson(type, json);
			if (!literal.isOk()) {
				reason = "not a value of the parameter's type";
			} else {
				return setParameter(rule, parameter, literal.value());
			}
		}
		refuseParameter(rule, parameter, reason);
		return statusOf(reason);
	}

	/**
	 * Sets a parameter from the UI or API (<code>spec/rules.md</code> 8.2): validated, made the
	 * override, persisted; a parameter-changed cascade is queued if the value differs.
	 */
	public ParameterStatus setParameter(String rule, String parameter, Val value) {
		int[] found = new int[2];
		String reason = findAdjustable(rule, parameter, found);
		Val converted = null;
		if (reason == null) {
			Parameter decl = _set._rules.get(found[0])._parameters.get(found[1]);
			converted = Values.convertForParameter(value, decl._type);
			if (converted == null) {
				reason = "not a value of the parameter's type";
			} else if (!decl.valid(converted)) {
				reason = decl._choices.isEmpty() ? "outside min..max" : "not one of the choices";
			}
		}
		if (reason != null) {
			refuseParameter(rule, parameter, reason);
			return statusOf(reason);
		}
		applyParameter(found[0], found[1], converted, false);
		return ParameterStatus.OK;
	}

	/**
	 * Removes the override: the value returns to the default (an event if that changes it).
	 */
	public ParameterStatus resetParameter(String rule, String parameter) {
		int[] found = new int[2];
		String reason = findAdjustable(rule, parameter, found);
		if (reason != null) {
			refuseParameter(rule, parameter, reason);
			return statusOf(reason);
		}
		int r = found[0];
		int p = found[1];
		Rule decl = _set._rules.get(r);
		ParamRuntime pr = _params[r][p];
		Val old = pr._value;
		pr._value = decl._parameters.get(p)._default;
		pr._overridden = false;
		_store.erase(decl._id, decl._parameters.get(p)._id);
		if (!Val.identical(old, pr._value)) {
			_observer.parameterChanged(decl._id, decl._parameters.get(p)._id, pr._value);
			if (p == 0 && !pr._value.bool()) {
				stopTimers(r);
			}
			pushInbound(Event.parameter(r, p));
		}
		return ParameterStatus.OK;
	}

	private void applyParameter(int rule, int index, Val value, boolean derived) {
		Rule r = _set._rules.get(rule);
		Parameter decl = r._parameters.get(index);
		ParamRuntime pr = _params[rule][index];
		Val old = pr._value;
		pr._value = value;
		pr._overridden = true;
		_store.write(r._id, decl._id, value);
		if (Val.identical(old, value)) {
			return;
		}
		_observer.parameterChanged(r._id, decl._id, value);
		if (index == 0 && !value.bool()) {
			// Disabling cancels the running timers (7.9).
			stopTimers(rule);
		}
		Event event = Event.parameter(rule, index);
		if (derived) {
			pushCascade(event, rule);
		} else {
			pushInbound(event);
		}
	}

	private void stopTimers(int rule) {
		for (TimerRuntime timer : _timers[rule]) {
			timer._running = false;
		}
	}

	// ---- Cascades ---------------------------------------------------------------------------

	private void beginCascade() {
		_cascadeMs = _clock.getAsLong();
		_wall = _time.localNow();
		_derivedFirings = 0;
		_aborted = false;
		_overflowLogged = false;
		_queue.clear();
		_inCascade = true;
		for (RuleRuntime rule : _rules) {
			rule._cascadeFirings = 0;
		}
	}

	private void endCascade() {
		_inCascade = false;
		_queue.clear();
	}

	private void runCascade(Event event) {
		beginCascade();
		if (event.kind() == EventKind.PARAMETER && event.index() == 0) {
			// The enable switch: enabling starts the rule with the reason `enable` in this cascade.
			if (event.rule() < _set._rules.size() && enabled(event.rule())) {
				runStart(event.rule(), StartReason.ENABLE);
			}
		} else {
			processEvent(event, false);
		}
		processQueue();
		endCascade();
	}

	private void runStart(int rule, StartReason reason) {
		Rule r = _set._rules.get(rule);
		RuleRuntime rr = _rules[rule];
		if (!r._states.isEmpty() && !rr._hasState) {
			rr._state = r._initialState;
			rr._hasState = true;
			if (enabled(rule)) {
				_currentRule = rule;
				runActions(rule, r._states.get(r._initialState)._entry);
			}
		}
		if (enabled(rule)) {
			processEvent(Event.start(rule, reason), false);
		}
	}

	private void processQueue() {
		while (!_queue.isEmpty() && !_aborted) {
			Event event = _queue.poll();
			processEvent(event, !event.external());
		}
	}

	private boolean triggerMatches(Trigger t, int rule, Event e) {
		switch (t._kind) {
			case DATAPOINT:
				if (e.kind() != EventKind.DATAPOINT || !e.name().equals(t._name)) {
					return false;
				}
				if (!t._onUpdate && !e.changed()) {
					return false;
				}
				if (t._originReport && e.origin() == Origin.COMMAND) {
					return false;
				}
				if (t._originCommand && e.origin() != Origin.COMMAND) {
					return false;
				}
				return true;
			case MATCH:
				return (e.kind() == EventKind.DATAPOINT || e.kind() == EventKind.PARAMETER)
					&& (_flags[t._condition._root] & MATCHED) != 0;
			case SCHEDULE:
				return e.kind() == EventKind.SCHEDULE && e.name().equals(t._name);
			case TIMER:
				return e.kind() == EventKind.TIMER && e.rule() == rule && e.index() == t._ref;
			case START:
				return e.kind() == EventKind.START && e.rule() == rule && t._reasons.contains(e.reason());
			case PARAMETER:
				return e.kind() == EventKind.PARAMETER && e.rule() == rule && e.index() == t._ref;
			default:
				return false;
		}
	}

	private static boolean matchReferences(Trigger t, int rule, Event e) {
		if (e.kind() == EventKind.DATAPOINT) {
			return t._matchDatapoints.contains(e.name());
		}
		return e.kind() == EventKind.PARAMETER && e.rule() == rule && t._matchParameters.contains(Integer.valueOf(e.index()));
	}

	private void processEvent(Event e, boolean derived) {
		List<Integer> matched = new ArrayList<>();

		// 1. Match memories, of every rule, enabled or not.
		if (e.kind() == EventKind.DATAPOINT || e.kind() == EventKind.PARAMETER) {
			for (int i = 0; i < _set._matches.size(); i++) {
				Trigger t = _set._matches.get(i);
				int rule = _set._matchRules.get(i).intValue();
				if (!matchReferences(t, rule, e)) {
					continue;
				}
				Val v = evaluate(t._condition, rule);
				boolean now = v.has() && v.bool();
				int root = t._condition._root;
				boolean was = (_flags[root] & MATCH_MEMORY) != 0;
				_flags[root] = (byte) (now ? _flags[root] | MATCH_MEMORY : _flags[root] & ~MATCH_MEMORY);
				if (now && !was) {
					_flags[root] |= MATCHED;
					matched.add(Integer.valueOf(root));
				}
			}
		}

		// 2. Rules, in rule order.
		for (int r = 0; r < _set._rules.size() && !_aborted; r++) {
			if (!enabled(r)) {
				continue;
			}
			Rule rule = _set._rules.get(r);
			RuleRuntime rr = _rules[r];
			Trigger first = null;
			for (int i = 0; i < rule._reactions.size() && first == null; i++) {
				Reaction reaction = rule._reactions.get(i);
				if (!inFrom(reaction, rr)) {
					continue;
				}
				for (Trigger t : reaction._triggers) {
					if (triggerMatches(t, r, e)) {
						first = t;
						break;
					}
				}
			}
			if (first == null) {
				continue;
			}
			if (derived) {
				if (_derivedFirings + 1 > RuleSet.MAX_DERIVED_FIRINGS) {
					int culprit = r;
					for (int i = 0; i < _rules.length; i++) {
						if (_rules[i]._cascadeFirings > _rules[culprit]._cascadeFirings) {
							culprit = i;
						}
					}
					rr._loopGuards++;
					logRule(r, LogCode.LOOP_GUARD, Level.ERROR, "cascade exceeded 32 derived firings",
						"cascade refused after " + RuleSet.MAX_DERIVED_FIRINGS + " derived firings (most: "
							+ _set._rules.get(culprit)._id + " with " + _rules[culprit]._cascadeFirings
							+ "); the queue is emptied");
					_aborted = true;
					_queue.clear();
					break;
				}
				_derivedFirings++;
				rr._cascadeFirings++;
				rr._peakCascadeFirings = Math.max(rr._peakCascadeFirings, rr._cascadeFirings);
			}
			rr._firings++;
			rr._lastTriggerMs = _cascadeMs;
			rr._lastTrigger = describe(first, rule, e);
			_currentRule = r;

			for (Reaction reaction : rule._reactions) {
				if (!inFrom(reaction, rr)) {
					continue;
				}
				boolean active = false;
				for (int k = 0; k < reaction._triggers.size() && !active; k++) {
					active = triggerMatches(reaction._triggers.get(k), r, e);
				}
				if (!active) {
					continue;
				}
				boolean satisfied = true;
				if (reaction._condition != null) {
					Val c = evaluate(reaction._condition, r);
					int root = reaction._condition._root;
					if (!c.has()) {
						if (_evalError == null && (_flags[root] & NO_VALUE_LOGGED) == 0) {
							_flags[root] |= NO_VALUE_LOGGED;
							logRule(r, LogCode.CONDITION_NO_VALUE, Level.WARNING, "condition has no value",
								"condition has no value: " + reaction._condition);
						}
						continue;
					}
					_flags[root] &= ~NO_VALUE_LOGGED;
					satisfied = c.bool();
				}
				if (satisfied) {
					if (reaction._to >= 0) {
						if (rr._hasState) {
							runActions(r, rule._states.get(rr._state)._exit);
						}
						runActions(r, reaction._actions);
						rr._state = reaction._to;
						rr._hasState = true;
						runActions(r, rule._states.get(reaction._to)._entry);
						break;
					}
					runActions(r, reaction._actions);
				} else {
					runActions(r, reaction._elseActions);
				}
			}
		}

		for (Integer root : matched) {
			_flags[root.intValue()] &= ~MATCHED;
		}
	}

	private static boolean inFrom(Reaction reaction, RuleRuntime rr) {
		int state = rr._hasState ? rr._state : 0;
		return (reaction._fromMask & (1 << state)) != 0;
	}

	private String describe(Trigger trigger, Rule rule, Event e) {
		switch (trigger._kind) {
			case DATAPOINT:
				return "datapoint " + trigger._name;
			case MATCH:
				return "match";
			case SCHEDULE:
				return "schedule " + trigger._name;
			case TIMER:
				return "timer " + rule._timers.get(trigger._ref)._id;
			case START:
				return "start " + e.reason().externalName();
			default:
				return "parameter " + rule._parameters.get(trigger._ref)._id;
		}
	}

	// ---- Actions ----------------------------------------------------------------------------

	private void runActions(int rule, List<Action> list) {
		for (Action action : list) {
			_currentRule = rule;
			switch (action._kind) {
				case WRITE -> actionWrite(rule, action);
				case START_TIMER, RESTART_TIMER, CANCEL_TIMER -> actionTimer(rule, action);
				case SET_PARAMETER -> actionSetParameter(rule, action);
				case LOG -> actionLog(rule, action);
				default -> throw new IllegalStateException(action._kind.toString());
			}
		}
	}

	private void recordAction(int rule, String text) {
		RuleRuntime rr = _rules[rule];
		rr._lastActionMs = _cascadeMs;
		rr._lastAction = text;
	}

	private void actionWrite(int rule, Action action) {
		Val v = evaluate(action._expr, rule);
		int root = action._expr._root;
		String name = action._datapoint;
		if (!v.has()) {
			if (_evalError == null && (_flags[root] & NO_VALUE_LOGGED) == 0) {
				_flags[root] |= NO_VALUE_LOGGED;
				logRule(rule, LogCode.VALUE_NO_VALUE, Level.WARNING, "write value has no value",
					"value for '" + name + "' has no value; nothing written");
			}
			return;
		}
		_flags[root] &= ~NO_VALUE_LOGGED;
		Datapoint datapoint = _model.get(name);
		if (datapoint == null) {
			logRule(rule, LogCode.WRITE_REFUSED, Level.ERROR, "datapoint not in the model",
				"datapoint '" + name + "' is not in the model");
			return;
		}
		Val converted = Values.convertForDatapoint(v, datapoint.type());
		if (converted == null) {
			logRule(rule, LogCode.WRITE_REFUSED, Level.ERROR, "value does not convert exactly", Values.format(v)
				+ " does not convert exactly to the " + datapoint.type().externalName() + " datapoint '" + name + "'");
			return;
		}
		if (action._onlyOnChange && datapoint.quality() == Quality.GOOD && Val.identical(datapoint.value(), converted)) {
			return;
		}
		CommandStatus status = _model.command(name, converted);
		if (status != CommandStatus.OK) {
			logRule(rule, LogCode.WRITE_REFUSED, Level.ERROR, "command refused",
				"command to '" + name + "' refused: " + status.text());
			return;
		}
		_observer.ruleCommanded(_set._rules.get(rule)._id, datapoint, converted);
		recordAction(rule, "write " + name + " = " + Values.format(v));
	}

	private void actionTimer(int rule, Action action) {
		Rule r = _set._rules.get(rule);
		TimerRuntime t = _timers[rule][action._ref];
		String id = r._timers.get(action._ref)._id;
		if (action._kind == ActionKind.CANCEL_TIMER) {
			if (t._running) {
				t._running = false;
				recordAction(rule, "cancelTimer " + id);
			}
			return;
		}
		if (action._kind == ActionKind.START_TIMER && t._running) {
			return;
		}
		Node root = action._expr != null ? action._expr : r._timers.get(action._ref)._duration;
		Val d = evaluate(root, rule);
		if (!d.has()) {
			logRule(rule, LogCode.TIMER_REFUSED, Level.ERROR, "timer duration has no value",
				"timer '" + id + "' not started: the duration has no value");
			return;
		}
		if (d.integer() < RuleSet.TIMER_MIN_MS || d.integer() > RuleSet.TIMER_MAX_MS) {
			logRule(rule, LogCode.TIMER_REFUSED, Level.ERROR, "timer duration outside 100ms..30d",
				"timer '" + id + "' not started: " + Values.durationText(d.integer()) + " is outside 100ms..30d");
			return;
		}
		t._running = true;
		t._deadline = _cascadeMs + d.integer();
		t._sequence = ++_timerSequence;
		recordAction(rule, action._kind.externalName() + " " + id + " " + Values.format(d));
	}

	private void actionSetParameter(int rule, Action action) {
		Rule r = _set._rules.get(rule);
		Parameter decl = r._parameters.get(action._ref);
		Val v = evaluate(action._expr, rule);
		if (!v.has()) {
			refuseParameter(r._id, decl._id, "the value has no value");
			return;
		}
		Val converted = Values.convertForParameter(v, decl._type);
		if (converted == null || !decl.valid(converted)) {
			String reason = Values.format(v) + " is not valid";
			logCode(r._id, _rules[rule], LogCode.PARAMETER_REFUSED, Level.WARNING, "value not valid",
				"parameter '" + decl._id + "' refused: " + reason);
			return;
		}
		applyParameter(rule, action._ref, converted, true);
		recordAction(rule, "setParameter " + decl._id + " = " + Values.format(converted));
	}

	private void actionLog(int rule, Action action) {
		StringBuilder text = new StringBuilder();
		for (Object part : action._parts) {
			if (part instanceof String verbatim) {
				text.append(verbatim);
			} else {
				text.append(Values.format(evaluate((Node) part, rule)));
			}
		}
		byte[] message = text.toString().getBytes(StandardCharsets.UTF_8);
		String result;
		if (message.length > RuleSet.MAX_MESSAGE) {
			int end = RuleSet.MAX_MESSAGE - 3;
			while (end > 0 && (message[end] & 0xC0) == 0x80) {
				// Do not cut a UTF-8 sequence.
				end--;
			}
			result = new String(Arrays.copyOf(message, end), StandardCharsets.UTF_8) + "...";
		} else {
			result = text.toString();
		}
		logCode(_set._rules.get(rule)._id, _rules[rule], LogCode.LOG, action._level, "", result);
		recordAction(rule, "log");
	}

	// ---- Evaluation -------------------------------------------------------------------------

	/**
	 * Evaluates an expression root of the rule; an evaluation error is logged once per root.
	 * Afterwards {@link #_evalError} tells whether the evaluation failed.
	 */
	private Val evaluate(Node root, int rule) {
		_evalError = null;
		Val v = eval(root, rule);
		int index = root._root;
		if (_evalError != null) {
			if ((_flags[index] & EVAL_ERROR_LOGGED) == 0) {
				_flags[index] |= EVAL_ERROR_LOGGED;
				logRule(rule, LogCode.EVAL_ERROR, Level.ERROR, _evalError, _evalError + " in " + root);
			}
		} else {
			_flags[index] &= ~EVAL_ERROR_LOGGED;
		}
		return v;
	}

	private Val fail(Node node, String text) {
		if (_evalError == null) {
			_evalError = text;
		}
		return Val.none(node._type);
	}

	private Val eval(Node n, int rule) {
		Val v;
		switch (n._kind) {
			case LITERAL:
				v = n._literal;
				break;
			case DATAPOINT:
				v = datapointValue(n._name);
				break;
			case PARAMETER:
				v = _params[rule][n._ref]._value;
				break;
			case UNARY: {
				Val x = eval(n._args.get(0), rule);
				if (!x.has()) {
					v = x;
				} else if (n._op == Op.NOT) {
					v = Val.ofBool(!x.bool());
				} else if (x.type() == Type.REAL) {
					v = Val.ofReal(-x.real());
				} else if (x.integer() == Long.MIN_VALUE) {
					v = fail(n, "integer overflow");
				} else {
					v = x.type() == Type.DURATION ? Val.ofDuration(-x.integer()) : Val.ofInt(-x.integer());
				}
				break;
			}
			case BINARY:
				v = evalBinary(n, rule);
				break;
			case CALL:
				v = evalCall(n, rule);
				break;
			default:
				v = Val.none(Type.BOOLEAN);
				break;
		}
		if (!v.has() && n._type != null) {
			v = Val.none(n._type);
		}
		return v;
	}

	private Val datapointValue(String name) {
		Datapoint datapoint = _model.get(name);
		if (datapoint == null || datapoint.quality() != Quality.GOOD) {
			return Val.none(Type.BOOLEAN);
		}
		return datapoint.value();
	}

	private long cascadeAge(long time) {
		return _cascadeMs >= time ? _cascadeMs - time : 0;
	}

	private static long secondsOfDay(TimeOfDay t) {
		return t.secondsOfDay();
	}

	private Val evalBinary(Node n, int rule) {
		Op op = n._op;
		if (op == Op.AND || op == Op.OR) {
			// true dominates `or`, false dominates `and`.
			boolean dominant = op == Op.OR;
			Val a = eval(n._args.get(0), rule);
			if (a.has() && a.bool() == dominant) {
				return Val.ofBool(dominant);
			}
			Val b = eval(n._args.get(1), rule);
			if (b.has() && b.bool() == dominant) {
				return Val.ofBool(dominant);
			}
			if (!a.has() || !b.has()) {
				return Val.none(Type.BOOLEAN);
			}
			return Val.ofBool(!dominant);
		}
		Val a = eval(n._args.get(0), rule);
		Val b = eval(n._args.get(1), rule);
		if (!a.has() || !b.has()) {
			return Val.none(n._type);
		}
		switch (op) {
			case EQ:
				return Val.ofBool(Evaluator.equal(a, b));
			case NE:
				return Val.ofBool(!Evaluator.equal(a, b));
			case LT:
				return Val.ofBool(Evaluator.order(a, b) < 0);
			case LE:
				return Val.ofBool(Evaluator.order(a, b) <= 0);
			case GT:
				return Val.ofBool(Evaluator.order(a, b) > 0);
			case GE:
				return Val.ofBool(Evaluator.order(a, b) >= 0);
			default:
				break;
		}
		boolean numbers = a.type().isNumeric() && b.type().isNumeric();
		boolean integers = a.type() == Type.INTEGER && b.type() == Type.INTEGER;
		switch (op) {
			case ADD:
			case SUB: {
				boolean add = op == Op.ADD;
				if (integers || (a.type() == Type.DURATION && b.type() == Type.DURATION)) {
					long r;
					try {
						r = add ? Math.addExact(a.integer(), b.integer()) : Math.subtractExact(a.integer(), b.integer());
					} catch (ArithmeticException ex) {
						return fail(n, integers ? "integer overflow" : "duration overflow");
					}
					return integers ? Val.ofInt(r) : Val.ofDuration(r);
				}
				if (numbers) {
					return realResult(n, add ? a.number() + b.number() : a.number() - b.number());
				}
				if (a.type() == Type.TIME_OF_DAY && b.type() == Type.DURATION) {
					long seconds = b.integer() / 1000;
					long s = Math.floorMod(secondsOfDay(a.time()) + (add ? seconds : -seconds), 86400L);
					TimeOfDay t = a.time();
					return Val.ofTime(new TimeOfDay((int) (s / 3600), (int) (s % 3600 / 60), (int) (s % 60), t.dayOfWeek()));
				}
				if (a.type() == Type.DATE_TIME && b.type() == Type.DURATION) {
					long seconds = b.integer() / 1000;
					long s = a.dateTime().seconds() + (add ? seconds : -seconds);
					long days = Math.floorDiv(s, 86400L);
					long rest = s - days * 86400;
					Date date = Values.civilOf(days);
					if (date.year() < 1900 || date.year() > 2155) {
						return fail(n, "date-time out of range");
					}
					return Val.ofDateTime(new DateTime(date.year(), date.month(), date.day(), (int) (rest / 3600),
						(int) (rest % 3600 / 60), (int) (rest % 60), Values.weekdayOf(date.year(), date.month(), date.day()), 0));
				}
				if (a.type() == Type.TIME_OF_DAY) {
					return Val.ofDuration((secondsOfDay(a.time()) - secondsOfDay(b.time())) * 1000);
				}
				if (a.type() == Type.DATE_TIME) {
					return Val.ofDuration((a.dateTime().seconds() - b.dateTime().seconds()) * 1000);
				}
				if (a.type() == Type.DATE) {
					return Val.ofDuration((a.date().days() - b.date().days()) * 86400000L);
				}
				if (a.type() == Type.STRING) {
					if (a.bytes().length + b.bytes().length > Val.MAX_STRING) {
						return fail(n, "a string longer than 28 bytes");
					}
					byte[] bytes = Arrays.copyOf(a.bytes(), a.bytes().length + b.bytes().length);
					System.arraycopy(b.bytes(), 0, bytes, a.bytes().length, b.bytes().length);
					return Val.ofString(bytes);
				}
				break;
			}
			case MUL: {
				if (integers) {
					try {
						return Val.ofInt(Math.multiplyExact(a.integer(), b.integer()));
					} catch (ArithmeticException ex) {
						return fail(n, "integer overflow");
					}
				}
				if (numbers) {
					return realResult(n, a.number() * b.number());
				}
				Val d = a.type() == Type.DURATION ? a : b;
				Val f = a.type() == Type.DURATION ? b : a;
				if (f.type() == Type.INTEGER) {
					try {
						return Val.ofDuration(Math.multiplyExact(d.integer(), f.integer()));
					} catch (ArithmeticException ex) {
						return fail(n, "duration overflow");
					}
				}
				return durationResult(n, (double) d.integer() * f.real());
			}
			case DIV:
				if (numbers) {
					if (b.number() == 0) {
						return fail(n, "division by zero");
					}
					return realResult(n, a.number() / b.number());
				}
				if (a.type() == Type.DURATION && b.type() == Type.DURATION) {
					if (b.integer() == 0) {
						return fail(n, "division by zero");
					}
					return realResult(n, (double) a.integer() / (double) b.integer());
				}
				if (b.number() == 0) {
					return fail(n, "division by zero");
				}
				return durationResult(n, a.integer() / b.number());
			case MOD: {
				if (b.integer() == 0) {
					return fail(n, "remainder by zero");
				}
				long r = b.integer() == -1 ? 0 : a.integer() % b.integer();
				return integers ? Val.ofInt(r) : Val.ofDuration(r);
			}
			default:
				break;
		}
		return fail(n, "unsupported operands");
	}

	private Val realResult(Node n, double r) {
		return Double.isFinite(r) ? Val.ofReal(r) : fail(n, "a result that is not a finite real");
	}

	private Val durationResult(Node n, double ms) {
		double rounded = Values.round(ms);
		if (!Double.isFinite(rounded) || Math.abs(rounded) >= 9.2e18) {
			return fail(n, "duration overflow");
		}
		return Val.ofDuration((long) rounded);
	}

	private Val integerOf(Node n, double r) {
		if (!(Math.abs(r) < 9.2233720368547758e18)) {
			return fail(n, "a result beyond the integer range");
		}
		return Val.ofInt((long) r);
	}

	private Val evalCall(Node n, int rule) {
		List<Node> args = n._args;
		switch (n._fn) {
			case IF: {
				Val c = eval(args.get(0), rule);
				if (!c.has()) {
					return Val.none(n._type);
				}
				return Evaluator.promote(eval(args.get(c.bool() ? 1 : 2), rule), n._type);
			}
			case KNOWN:
				return Val.ofBool(eval(args.get(0), rule).has());
			case COALESCE: {
				Val x = eval(args.get(0), rule);
				return Evaluator.promote(x.has() ? x : eval(args.get(1), rule), n._type);
			}
			case BETWEEN: {
				Val x = eval(args.get(0), rule);
				Val low = eval(args.get(1), rule);
				Val high = eval(args.get(2), rule);
				if (!x.has() || !low.has() || !high.has()) {
					return Val.none(n._type);
				}
				if (Evaluator.order(low, high) > 0) {
					return Val.ofBool(false);
				}
				return Val.ofBool(Evaluator.order(low, x) <= 0 && Evaluator.order(x, high) <= 0);
			}
			case HYSTERESIS: {
				Val x = eval(args.get(0), rule);
				Val low = eval(args.get(1), rule);
				Val high = eval(args.get(2), rule);
				if (!x.has() || !low.has() || !high.has()) {
					return Val.none(n._type);
				}
				if (Evaluator.order(low, high) >= 0) {
					return fail(n, "hysteresis with low >= high");
				}
				if (Evaluator.order(x, high) >= 0) {
					return Val.ofBool(true);
				}
				if (Evaluator.order(x, low) <= 0) {
					return Val.ofBool(false);
				}
				return eval(args.get(3), rule);
			}
			case ABS: {
				Val x = eval(args.get(0), rule);
				if (!x.has()) {
					return x;
				}
				if (x.type() == Type.REAL) {
					return Val.ofReal(Math.abs(x.real()));
				}
				if (x.integer() == Long.MIN_VALUE) {
					return fail(n, "integer overflow");
				}
				long magnitude = Math.abs(x.integer());
				return x.type() == Type.DURATION ? Val.ofDuration(magnitude) : Val.ofInt(magnitude);
			}
			case MIN:
			case MAX: {
				Val best = null;
				boolean missing = false;
				for (Node arg : args) {
					Val x = eval(arg, rule);
					if (!x.has()) {
						missing = true;
						continue;
					}
					if (best == null || (n._fn == Fn.MIN ? Evaluator.order(x, best) < 0 : Evaluator.order(x, best) > 0)) {
						best = x;
					}
				}
				return missing ? Val.none(n._type) : Evaluator.promote(best, n._type);
			}
			case CLAMP: {
				Val x = eval(args.get(0), rule);
				Val low = eval(args.get(1), rule);
				Val high = eval(args.get(2), rule);
				if (!x.has() || !low.has() || !high.has()) {
					return Val.none(n._type);
				}
				if (Evaluator.order(low, high) > 0) {
					return fail(n, "clamp with low > high");
				}
				Val result = Evaluator.order(x, high) > 0 ? high : x;
				if (Evaluator.order(result, low) < 0) {
					result = low;
				}
				return Evaluator.promote(result, n._type);
			}
			case ROUND:
			case FLOOR:
			case CEIL:
			case TRUNC: {
				Val x = eval(args.get(0), rule);
				if (!x.has() || x.type() == Type.INTEGER) {
					return x;
				}
				double r = x.real();
				double result = switch (n._fn) {
					case ROUND -> Values.round(r);
					case FLOOR -> Math.floor(r);
					case CEIL -> Math.ceil(r);
					default -> r < 0 ? Math.ceil(r) : Math.floor(r);
				};
				return integerOf(n, result);
			}
			case ROUND_TO: {
				Val x = eval(args.get(0), rule);
				Val step = eval(args.get(1), rule);
				if (!x.has() || !step.has()) {
					return Val.none(n._type);
				}
				double s = step.number();
				if (!(s > 0)) {
					return fail(n, "roundTo with a step not above 0");
				}
				return realResult(n, Values.round(x.number() / s) * s);
			}
			case TO_INT: {
				Val x = eval(args.get(0), rule);
				if (!x.has()) {
					return Val.none(n._type);
				}
				return Val.ofInt(x.type() == Type.BOOLEAN ? (x.bool() ? 1 : 0) : x.integer());
			}
			case NOW:
				return _wall != null ? Val.ofDateTime(_wall) : Val.none(n._type);
			case TODAY:
				return _wall != null ? Val.ofDate(new Date(_wall.year(), _wall.month(), _wall.day())) : Val.none(n._type);
			case TIME:
				return _wall != null
					? Val.ofTime(new TimeOfDay(_wall.hour(), _wall.minute(), _wall.second(), _wall.dayOfWeek()))
					: Val.none(n._type);
			case WEEKDAY: {
				if (args.isEmpty()) {
					return _wall != null ? Val.ofInt(Values.weekdayOf(_wall.year(), _wall.month(), _wall.day()))
						: Val.none(n._type);
				}
				Val x = eval(args.get(0), rule);
				if (!x.has()) {
					return Val.none(n._type);
				}
				switch (x.type()) {
					case DATE:
						return Val.ofInt(Values.weekdayOf(x.date().year(), x.date().month(), x.date().day()));
					case DATE_TIME:
						return Val.ofInt(Values.weekdayOf(x.dateTime().year(), x.dateTime().month(), x.dateTime().day()));
					default:
						return x.time().dayOfWeek() != 0 ? Val.ofInt(x.time().dayOfWeek()) : Val.none(n._type);
				}
			}
			case HOUR:
			case MINUTE:
			case SECOND: {
				Val x = eval(args.get(0), rule);
				if (!x.has()) {
					return Val.none(n._type);
				}
				boolean t = x.type() == Type.TIME_OF_DAY;
				int h = t ? x.time().hour() : x.dateTime().hour();
				int m = t ? x.time().minute() : x.dateTime().minute();
				int s = t ? x.time().second() : x.dateTime().second();
				return Val.ofInt(n._fn == Fn.HOUR ? h : n._fn == Fn.MINUTE ? m : s);
			}
			case YEAR:
			case MONTH:
			case DAY: {
				Val x = eval(args.get(0), rule);
				if (!x.has()) {
					return Val.none(n._type);
				}
				boolean d = x.type() == Type.DATE;
				int y = d ? x.date().year() : x.dateTime().year();
				int mo = d ? x.date().month() : x.dateTime().month();
				int da = d ? x.date().day() : x.dateTime().day();
				return Val.ofInt(n._fn == Fn.YEAR ? y : n._fn == Fn.MONTH ? mo : da);
			}
			case DATE_OF:
			case TIME_OF: {
				Val x = eval(args.get(0), rule);
				if (!x.has()) {
					return Val.none(n._type);
				}
				DateTime dt = x.dateTime();
				if (n._fn == Fn.DATE_OF) {
					return Val.ofDate(new Date(dt.year(), dt.month(), dt.day()));
				}
				return Val.ofTime(new TimeOfDay(dt.hour(), dt.minute(), dt.second(),
					Values.weekdayOf(dt.year(), dt.month(), dt.day())));
			}
			case IN_WINDOW: {
				Val t = eval(args.get(0), rule);
				Val from = eval(args.get(1), rule);
				Val until = eval(args.get(2), rule);
				if (!t.has() || !from.has() || !until.has()) {
					return Val.none(n._type);
				}
				int s = t.type() == Type.TIME_OF_DAY ? t.time().secondsOfDay()
					: t.dateTime().hour() * 3600 + t.dateTime().minute() * 60 + t.dateTime().second();
				int f = from.time().secondsOfDay();
				int u = until.time().secondsOfDay();
				if (f < u) {
					return Val.ofBool(f <= s && s < u);
				}
				if (f > u) {
					return Val.ofBool(s >= f || s < u);
				}
				return Val.ofBool(false);
			}
			case SUNRISE:
			case SUNSET:
			case DAWN:
			case DUSK: {
				if (_wall == null) {
					return Val.none(n._type);
				}
				SunEvent event = switch (n._fn) {
					case SUNRISE -> SunEvent.SUNRISE;
					case SUNSET -> SunEvent.SUNSET;
					case DAWN -> SunEvent.DAWN;
					default -> SunEvent.DUSK;
				};
				TimeOfDay t = _time.sunTime(event);
				return t != null ? Val.ofTime(t) : Val.none(n._type);
			}
			case SINCE_UPDATE:
			case LAST_KNOWN:
			case QUALITY: {
				Datapoint datapoint = _model.get(args.get(0)._name);
				Quality quality = datapoint != null ? datapoint.quality() : Quality.UNKNOWN;
				if (n._fn == Fn.QUALITY) {
					return Val.ofString(quality.externalName());
				}
				if (quality == Quality.UNKNOWN) {
					return Val.none(n._type);
				}
				return n._fn == Fn.SINCE_UPDATE ? Val.ofDuration(cascadeAge(datapoint.updatedMs())) : datapoint.value();
			}
			case SINCE_CHANGE:
			case CHANGED_WITHIN: {
				ChangeRecord record = _changes.get(args.get(0)._name);
				boolean known = record != null && record._known;
				long age = known ? cascadeAge(record._time) : 0;
				if (n._fn == Fn.SINCE_CHANGE) {
					return known ? Val.ofDuration(age) : Val.none(n._type);
				}
				Val limit = eval(args.get(1), rule);
				if (!known) {
					return Val.ofBool(false);
				}
				if (!limit.has()) {
					return Val.none(n._type);
				}
				return Val.ofBool(age <= limit.integer());
			}
			case RUNNING:
			case REMAINING: {
				TimerRuntime t = _timers[rule][args.get(0)._ref];
				if (n._fn == Fn.RUNNING) {
					return Val.ofBool(t._running);
				}
				if (!t._running) {
					return Val.none(n._type);
				}
				return Val.ofDuration(t._deadline > _cascadeMs ? t._deadline - _cascadeMs : 0);
			}
			case STATE: {
				int target = n._ref;
				RuleRuntime rr = _rules[target];
				if (!rr._hasState) {
					return Val.none(n._type);
				}
				return Val.ofString(_set._rules.get(target)._states.get(rr._state)._id);
			}
			case LENGTH: {
				Val s = eval(args.get(0), rule);
				return s.has() ? Val.ofInt(s.bytes().length) : Val.none(n._type);
			}
			case CONTAINS:
			case STARTS_WITH:
			case ENDS_WITH: {
				Val s = eval(args.get(0), rule);
				Val part = eval(args.get(1), rule);
				if (!s.has() || !part.has()) {
					return Val.none(n._type);
				}
				byte[] text = s.bytes();
				byte[] p = part.bytes();
				if (n._fn == Fn.CONTAINS) {
					return Val.ofBool(indexOf(text, p) >= 0);
				}
				if (p.length > text.length) {
					return Val.ofBool(false);
				}
				int offset = n._fn == Fn.STARTS_WITH ? 0 : text.length - p.length;
				return Val.ofBool(Arrays.equals(text, offset, offset + p.length, p, 0, p.length));
			}
			case TO_STRING: {
				Val x = eval(args.get(0), rule);
				if (!x.has()) {
					return Val.none(n._type);
				}
				byte[] text = Values.format(x).getBytes(StandardCharsets.UTF_8);
				if (text.length > Val.MAX_STRING) {
					return fail(n, "a string longer than 28 bytes");
				}
				return Val.ofString(text);
			}
			case RGB:
			case RGBW: {
				long[] levels = new long[4];
				boolean missing = false;
				for (int k = 0; k < args.size(); k++) {
					Val x = eval(args.get(k), rule);
					if (!x.has()) {
						missing = true;
					} else {
						levels[k] = x.integer();
					}
				}
				if (missing) {
					return Val.none(n._type);
				}
				for (int k = 0; k < args.size(); k++) {
					if (levels[k] < 0 || levels[k] > 255) {
						return fail(n, "a color level outside 0..255");
					}
				}
				return Val.ofColor(new Color((int) levels[0], (int) levels[1], (int) levels[2], (int) levels[3],
					n._fn == Fn.RGBW ? Color.ALL : Color.RGB));
			}
			case RED:
			case GREEN:
			case BLUE:
			case WHITE: {
				Val c = eval(args.get(0), rule);
				if (!c.has()) {
					return Val.none(n._type);
				}
				Color color = c.color();
				int bit = switch (n._fn) {
					case RED -> Color.RED;
					case GREEN -> Color.GREEN;
					case BLUE -> Color.BLUE;
					default -> Color.WHITE;
				};
				if ((color.valid() & bit) == 0) {
					return Val.none(n._type);
				}
				int level = switch (n._fn) {
					case RED -> color.red();
					case GREEN -> color.green();
					case BLUE -> color.blue();
					default -> color.white();
				};
				return Val.ofInt(level);
			}
			case CONTROLLED: {
				Val bit = eval(args.get(0), rule);
				Val number = eval(args.get(1), rule);
				if (!bit.has() || !number.has()) {
					return Val.none(n._type);
				}
				if (number.integer() < 0 || number.integer() > 255) {
					return fail(n, "a controlled number outside 0..255");
				}
				return Val.ofControlled(bit.bool(), (int) number.integer());
			}
			case CONTROL_BIT:
			case CONTROL_VALUE: {
				Val x = eval(args.get(0), rule);
				if (!x.has()) {
					return Val.none(n._type);
				}
				return n._fn == Fn.CONTROL_BIT ? Val.ofBool(x.controlled().control())
					: Val.ofInt(x.controlled().value());
			}
			default:
				return fail(n, "unknown function");
		}
	}

	private static int indexOf(byte[] text, byte[] part) {
		if (part.length == 0) {
			return 0;
		}
		for (int i = 0; i + part.length <= text.length; i++) {
			if (Arrays.equals(text, i, i + part.length, part, 0, part.length)) {
				return i;
			}
		}
		return -1;
	}

	// ---- Accessors --------------------------------------------------------------------------

	/** Whether the rule's enable switch is on. */
	public boolean enabled(int rule) {
		return _params != null && rule < _params.length && _params[rule][0]._value.bool();
	}

	/** The current value of parameter <code>index</code> (0 is <code>enabled</code>) of the rule. */
	public Val parameterValue(int rule, int index) {
		return _params[rule][index]._value;
	}

	/** Whether the parameter has an override. */
	public boolean parameterOverridden(int rule, int index) {
		return _params[rule][index]._overridden;
	}

	/** The remaining ms of the timer at the clock's current time, <code>null</code> when stopped. */
	public Long timerRemaining(int rule, int index) {
		TimerRuntime t = _timers[rule][index];
		if (!t._running) {
			return null;
		}
		long now = _clock.getAsLong();
		return Long.valueOf(t._deadline > now ? t._deadline - now : 0);
	}

	/** The current state id, <code>null</code> without states. */
	public String currentState(int rule) {
		RuleRuntime rr = _rules[rule];
		return rr._hasState ? _set._rules.get(rule)._states.get(rr._state)._id : null;
	}

	/** The firings of the rule since the rules started at boot. */
	public int firings(int rule) {
		return _rules[rule]._firings;
	}

	/**
	 * The diagnostics of a rule (<code>spec/rules.md</code> 9.1).
	 *
	 * @param firings
	 *        The firings since the rules started at boot.
	 * @param lastTrigger
	 *        What fired the rule last (<code>datapoint hall.presence</code>, <code>timer off</code>,
	 *        ...), <code>null</code> if it never fired.
	 * @param lastTriggerMs
	 *        When, on the monotonic clock.
	 * @param lastAction
	 *        The last action executed (<code>write hall.light = true</code>, ...), <code>null</code>
	 *        if none.
	 * @param lastActionMs
	 *        When, on the monotonic clock.
	 * @param errors
	 *        The number of logged errors and warnings.
	 * @param lastErrorCode
	 *        The code of the last one, <code>null</code> if none.
	 * @param lastErrorText
	 *        Its detail.
	 * @param lastErrorMs
	 *        When, on the monotonic clock.
	 * @param peakCascadeFirings
	 *        The most derived firings of the rule in one cascade.
	 * @param loopGuards
	 *        How often the loop guard aborted a cascade of the rule.
	 */
	public record RuleStatus(int firings, String lastTrigger, long lastTriggerMs, String lastAction, long lastActionMs,
			int errors, LogCode lastErrorCode, String lastErrorText, long lastErrorMs, int peakCascadeFirings,
			int loopGuards) {
		// A value.
	}

	/**
	 * The diagnostics of the rule with the given index.
	 */
	public RuleStatus status(int rule) {
		RuleRuntime rr = _rules[rule];
		return new RuleStatus(rr._firings, rr._lastTrigger, rr._lastTriggerMs, rr._lastAction, rr._lastActionMs,
			rr._errors, rr._lastErrorCode, rr._lastErrorText, rr._lastErrorMs, rr._peakCascadeFirings, rr._loopGuards);
	}

	/**
	 * The diagnostics of the rule (<code>spec/rules.md</code> 9.1) as text lines: firings, last
	 * trigger, last action, errors and last error.
	 */
	public String diagnostics(int rule) {
		RuleRuntime rr = _rules[rule];
		return "firings " + rr._firings + ", last trigger " + rr._lastTrigger + " at " + rr._lastTriggerMs
			+ ", last action " + rr._lastAction + " at " + rr._lastActionMs + ", errors " + rr._errors
			+ (rr._lastErrorCode != null ? ", last error " + rr._lastErrorCode.externalName() + " (" + rr._lastErrorText
				+ ") at " + rr._lastErrorMs : "")
			+ ", most derived firings in a cascade " + rr._peakCascadeFirings + ", loop guards " + rr._loopGuards;
	}

}
