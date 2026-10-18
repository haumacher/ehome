/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.rules;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The <code>rules</code> member of a runtime image, validated and compiled by the
 * {@link RuleLoader} (<code>spec/rules.md</code> 2 and 10). Read-only.
 */
public final class RuleSet {

	/** The limits of <code>spec/rules.md</code> 10.2. */
	public static final int MAX_RULES = 128;

	/** Reactions per rule. */
	public static final int MAX_REACTIONS = 16;

	/** Triggers per reaction. */
	public static final int MAX_TRIGGERS = 8;

	/** Actions per action list. */
	public static final int MAX_ACTIONS = 16;

	/** Parameters per rule, with <code>enabled</code>. */
	public static final int MAX_PARAMETERS = 8;

	/** Timers per rule. */
	public static final int MAX_TIMERS = 8;

	/** States per rule. */
	public static final int MAX_STATES = 16;

	/** Nodes per expression. */
	public static final int MAX_NODES_PER_EXPRESSION = 64;

	/** Expression depth (a leaf has depth 1). */
	public static final int MAX_DEPTH = 12;

	/** Arguments per call. */
	public static final int MAX_ARGUMENTS = 8;

	/** Parts of a log message. */
	public static final int MAX_LOG_PARTS = 8;

	/** Nodes in all expressions of an image. */
	public static final int MAX_NODES = 4096;

	/** Derived firings per cascade. */
	public static final int MAX_DERIVED_FIRINGS = 32;

	/** Events waiting in the cascade queue. */
	public static final int MAX_QUEUE = 128;

	/** The shortest timer duration in ms. */
	public static final long TIMER_MIN_MS = 100;

	/** The longest timer duration in ms. */
	public static final long TIMER_MAX_MS = 30L * 86400000L;

	/** The longest formatted log message in bytes. */
	public static final int MAX_MESSAGE = 200;

	/**
	 * A rule (<code>spec/rules.md</code> 2.2).
	 */
	public static final class Rule {
		final String _id;

		final String _name;

		/** Parameter 0 is the enable switch <code>enabled</code>. */
		final List<Parameter> _parameters = new ArrayList<>();

		final List<Timer> _timers = new ArrayList<>();

		final List<State> _states = new ArrayList<>();

		int _initialState = -1;

		final List<Reaction> _reactions = new ArrayList<>();

		Rule(String id, String name) {
			_id = id;
			_name = name;
		}

		/** The stable id. */
		public String id() {
			return _id;
		}

		/** The name. */
		public String name() {
			return _name;
		}

		/** The parameters, the enable switch first. */
		public List<Parameter> parameters() {
			return Collections.unmodifiableList(_parameters);
		}

		/** The timers. */
		public List<Timer> timers() {
			return Collections.unmodifiableList(_timers);
		}

		/** The states (empty for a rule without states). */
		public List<State> states() {
			return Collections.unmodifiableList(_states);
		}

		/** The index of the initial state, -1 without states. */
		public int initialState() {
			return _initialState;
		}

		/** The reactions. */
		public List<Reaction> reactions() {
			return Collections.unmodifiableList(_reactions);
		}

		/** The index of the parameter, -1 if none. */
		public int findParameter(String id) {
			for (int i = 0; i < _parameters.size(); i++) {
				if (_parameters.get(i)._id.equals(id)) {
					return i;
				}
			}
			return -1;
		}

		/** The index of the timer, -1 if none. */
		public int findTimer(String id) {
			for (int i = 0; i < _timers.size(); i++) {
				if (_timers.get(i)._id.equals(id)) {
					return i;
				}
			}
			return -1;
		}

		/** The index of the state, -1 if none. */
		public int findState(String id) {
			for (int i = 0; i < _states.size(); i++) {
				if (_states.get(i)._id.equals(id)) {
					return i;
				}
			}
			return -1;
		}
	}

	/**
	 * An adjustable parameter (<code>spec/rules.md</code> 8.1).
	 */
	public static final class Parameter {
		final String _id;

		final String _label;

		final String _unit;

		final Type _type;

		final boolean _adjustable;

		final Val _default;

		final Val _min;

		final Val _max;

		final List<Val> _choices;

		Parameter(String id, String label, String unit, Type type, boolean adjustable, Val defaultValue, Val min, Val max,
				List<Val> choices) {
			_id = id;
			_label = label;
			_unit = unit;
			_type = type;
			_adjustable = adjustable;
			_default = defaultValue;
			_min = min;
			_max = max;
			_choices = choices;
		}

		/** The id. */
		public String id() {
			return _id;
		}

		/** The label. */
		public String label() {
			return _label;
		}

		/** The display unit, empty without one. */
		public String unit() {
			return _unit;
		}

		/** The type. */
		public Type type() {
			return _type;
		}

		/** Whether the UI and the API may change it. */
		public boolean adjustable() {
			return _adjustable;
		}

		/** The default value. */
		public Val defaultValue() {
			return _default;
		}

		/** The lower bound, <code>null</code> without one. */
		public Val min() {
			return _min;
		}

		/** The upper bound, <code>null</code> without one. */
		public Val max() {
			return _max;
		}

		/** The values of the choices, empty without choices. */
		public List<Val> choices() {
			return _choices;
		}

		/**
		 * Whether the value is valid for this declaration: of the type, within the bounds, one of the
		 * choices.
		 */
		public boolean valid(Val value) {
			if (!value.has() || value.type() != _type) {
				return false;
			}
			if (_min != null && Evaluator.order(value, _min) < 0) {
				return false;
			}
			if (_max != null && Evaluator.order(value, _max) > 0) {
				return false;
			}
			if (!_choices.isEmpty()) {
				for (Val choice : _choices) {
					if (Val.equal(value, choice)) {
						return true;
					}
				}
				return false;
			}
			return true;
		}
	}

	/**
	 * A timer declaration (<code>spec/rules.md</code> 2.5).
	 */
	public static final class Timer {
		final String _id;

		Node _duration;

		Timer(String id) {
			_id = id;
		}

		/** The id. */
		public String id() {
			return _id;
		}
	}

	/**
	 * A state with its entry and exit actions (<code>spec/rules.md</code> 2.6).
	 */
	public static final class State {
		final String _id;

		final List<Action> _entry = new ArrayList<>();

		final List<Action> _exit = new ArrayList<>();

		State(String id) {
			_id = id;
		}

		/** The id. */
		public String id() {
			return _id;
		}
	}

	/**
	 * A reaction (<code>spec/rules.md</code> 2.4).
	 */
	public static final class Reaction {
		final List<Trigger> _triggers = new ArrayList<>();

		Node _condition;

		final List<Action> _actions = new ArrayList<>();

		final List<Action> _elseActions = new ArrayList<>();

		/** Bit per state index; all bits without <code>from</code>. */
		int _fromMask = -1;

		/** The target state index, -1 for none. */
		int _to = -1;
	}

	/**
	 * The trigger kinds (<code>spec/rules.md</code> 2.3).
	 */
	public enum TriggerKind {
		/** An update of a datapoint. */
		DATAPOINT,
		/** A condition becomes true. */
		MATCH,
		/** A schedule entry fired. */
		SCHEDULE,
		/** A timer of the rule expired. */
		TIMER,
		/** The rule starts. */
		START,
		/** A parameter of the rule changed. */
		PARAMETER;
	}

	/**
	 * Why a rule starts (<code>spec/rules.md</code> 7.5).
	 */
	public enum StartReason {
		/** After a boot. */
		BOOT("boot"),
		/** After an image swap. */
		IMAGE("image"),
		/** When the rule is enabled. */
		ENABLE("enable");

		private final String _name;

		StartReason(String name) {
			_name = name;
		}

		/** The name in the image. */
		public String externalName() {
			return _name;
		}
	}

	/**
	 * A trigger.
	 */
	public static final class Trigger {
		final TriggerKind _kind;

		/** datapoint: the datapoint; schedule: the schedule id. */
		String _name;

		/** datapoint: every update, not only changes. */
		boolean _onUpdate;

		/** datapoint: reports (and stale) only. */
		boolean _originReport;

		/** datapoint: commands only. */
		boolean _originCommand;

		/** start: the reasons. */
		Set<StartReason> _reasons;

		/** match: the condition. */
		Node _condition;

		/** match: the datapoints the condition reads. */
		final Set<String> _matchDatapoints = new LinkedHashSet<>();

		/** match: the parameter indices the condition reads. */
		final Set<Integer> _matchParameters = new LinkedHashSet<>();

		/** timer, parameter: the index in the rule. */
		int _ref = -1;

		Trigger(TriggerKind kind) {
			_kind = kind;
		}

		/** The kind. */
		public TriggerKind kind() {
			return _kind;
		}
	}

	/**
	 * The action kinds (<code>spec/rules.md</code> 2.7).
	 */
	public enum ActionKind {
		/** Command a datapoint. */
		WRITE("write"),
		/** Start a stopped timer. */
		START_TIMER("startTimer"),
		/** Start or restart a timer. */
		RESTART_TIMER("restartTimer"),
		/** Stop a timer. */
		CANCEL_TIMER("cancelTimer"),
		/** Set a parameter. */
		SET_PARAMETER("setParameter"),
		/** Write a log line. */
		LOG("log");

		private final String _name;

		ActionKind(String name) {
			_name = name;
		}

		/** The name in the image. */
		public String externalName() {
			return _name;
		}
	}

	/**
	 * The levels of a log action and of the engine's log entries.
	 */
	public enum Level {
		/** Information. */
		INFO("info"),
		/** A warning. */
		WARNING("warning"),
		/** An error. */
		ERROR("error");

		private final String _name;

		Level(String name) {
			_name = name;
		}

		/** The name in the image and the corpus. */
		public String externalName() {
			return _name;
		}
	}

	/**
	 * An action.
	 */
	public static final class Action {
		final ActionKind _kind;

		/** write: the datapoint. */
		String _datapoint;

		/** write: the datapoint's type. */
		ValueType _datapointType;

		/** write: only when the value differs. */
		boolean _onlyOnChange;

		/** write, setParameter: the value; start/restartTimer: the duration or <code>null</code>. */
		Node _expr;

		/** timer and parameter actions: the index in the rule. */
		int _ref = -1;

		/** log: the level. */
		Level _level;

		/** log: the parts, {@link String}s and {@link Node}s. */
		final List<Object> _parts = new ArrayList<>();

		Action(ActionKind kind) {
			_kind = kind;
		}

		/** The kind. */
		public ActionKind kind() {
			return _kind;
		}
	}

	final List<Rule> _rules = new ArrayList<>();

	/** The datapoints read by sinceChange() and changedWithin() (change tracking, 7.7). */
	final Set<String> _tracked = new LinkedHashSet<>();

	/** The schedules named by triggers. */
	final Set<String> _schedules = new LinkedHashSet<>();

	/** The datapoints named by datapoint triggers and match conditions. */
	final Set<String> _interest = new LinkedHashSet<>();

	/** The match triggers in rule order, with the index of their rule. */
	final List<Trigger> _matches = new ArrayList<>();

	final List<Integer> _matchRules = new ArrayList<>();

	/** The expression roots, by {@link Node#_root}. */
	final List<Node> _roots = new ArrayList<>();

	/** The rules in image order. */
	public List<Rule> rules() {
		return Collections.unmodifiableList(_rules);
	}

	/** The index of the rule, -1 if none. */
	public int findRule(String id) {
		for (int i = 0; i < _rules.size(); i++) {
			if (_rules.get(i)._id.equals(id)) {
				return i;
			}
		}
		return -1;
	}

	/** The number of parameters of all rules, with their enable switches. */
	public int parameterCount() {
		int count = 0;
		for (Rule rule : _rules) {
			count += rule._parameters.size();
		}
		return count;
	}

}
