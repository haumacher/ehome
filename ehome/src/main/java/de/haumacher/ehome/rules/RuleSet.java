/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.rules;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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

	/** The longest formatted log message in bytes; also the longest text part of a log message. */
	public static final int MAX_MESSAGE = 200;

	/** Choices per parameter. */
	public static final int MAX_CHOICES = 16;

	/** Bytes of a label text. */
	public static final int MAX_LABEL = 63;

	/** Entries of a label's language map. */
	public static final int MAX_LABEL_LANGUAGES = 16;

	/**
	 * The prefix of the datapoints that mirror the rule parameters (<code>spec/rules.md</code> 8.6):
	 * <code>rule/&lt;ruleId&gt;/&lt;parameterId&gt;</code>. A declared datapoint of an image may not
	 * start with it.
	 */
	public static final String PARAMETER_DATAPOINT_PREFIX = "rule/";

	/**
	 * The pseudo parameter id under which the current state of a rule is persisted
	 * (<code>spec/rules.md</code> 8.5): <code>&lt;ruleId&gt;/.state</code>.
	 */
	public static final String STATE_KEY = ".state";

	/**
	 * The pseudo parameter id under which the inhabitant's timeline of a rule is persisted
	 * (<code>spec/rules.md</code> 8.7): <code>&lt;ruleId&gt;/.timeline</code>.
	 */
	public static final String TIMELINE_KEY = ".timeline";

	/**
	 * The last part of the name of the datapoint of a rule's state (<code>spec/rules.md</code> 8.8):
	 * <code>rule/&lt;ruleId&gt;/state</code>; a parameter of a rule with states may not have this id.
	 */
	public static final String STATE_DATAPOINT = "state";

	/** Plans per timeline (<code>spec/rules.md</code> 10.2). */
	public static final int MAX_PLANS = 8;

	/** Points per plan. */
	public static final int MAX_PLAN_POINTS = 16;

	/** Points per timeline. */
	public static final int MAX_TIMELINE_POINTS = 64;

	/** Languages of a plan's label. */
	public static final int MAX_PLAN_LABEL_LANGUAGES = 4;

	/** Assignments per timeline: a default and seven weekdays. */
	public static final int MAX_ASSIGNMENTS = 8;

	/** Points in all timelines of an image, and in all effective timelines. */
	public static final int MAX_IMAGE_TIMELINE_POINTS = 256;

	/** Plans in all timelines of an image, and in all effective timelines. */
	public static final int MAX_IMAGE_TIMELINE_PLANS = 64;

	/** The greatest offset of a sun point in seconds (12 h, either way). */
	public static final int MAX_POINT_OFFSET = 12 * 3600;

	/**
	 * A rule (<code>spec/rules.md</code> 2.2).
	 */
	public static final class Rule {
		final String _id;

		final String _name;

		/** The name's language map, <code>null</code> when the image gave a plain string. */
		final Map<String, String> _nameMap;

		/** Parameter 0 is the enable switch <code>enabled</code>. */
		final List<Parameter> _parameters = new ArrayList<>();

		final List<Timer> _timers = new ArrayList<>();

		final List<State> _states = new ArrayList<>();

		int _initialState = -1;

		final List<Reaction> _reactions = new ArrayList<>();

		/** The image's timeline (<code>spec/rules.md</code> 2.8), <code>null</code> for none. */
		Timeline _timeline;

		Rule(String id, String name, Map<String, String> nameMap) {
			_id = id;
			_name = name;
			_nameMap = nameMap;
		}

		/** The stable id. */
		public String id() {
			return _id;
		}

		/** The name, resolved in the language of the load (<code>spec/rules.md</code> 2.2). */
		public String name() {
			return _name;
		}

		/**
		 * The name's language map as the image gives it, <code>null</code> when the image gave a plain
		 * string.
		 */
		public Map<String, String> nameMap() {
			return _nameMap;
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

		/** The image's timeline, <code>null</code> for none. */
		public Timeline timeline() {
			return _timeline;
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

		final Map<String, String> _labelMap;

		final String _unit;

		final Type _type;

		final boolean _adjustable;

		final Val _default;

		final Val _min;

		final Val _max;

		final List<Val> _choices;

		final List<String> _choiceLabels;

		Parameter(String id, String label, Map<String, String> labelMap, String unit, Type type, boolean adjustable,
				Val defaultValue, Val min, Val max, List<Val> choices, List<String> choiceLabels) {
			_id = id;
			_label = label;
			_labelMap = labelMap;
			_choiceLabels = choiceLabels;
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

		/** The label, resolved in the language of the load (<code>spec/rules.md</code> 2.2). */
		public String label() {
			return _label;
		}

		/** The label's language map, <code>null</code> when the image gave a plain string. */
		public Map<String, String> labelMap() {
			return _labelMap;
		}

		/** The resolved labels of the {@link #choices()}. */
		public List<String> choiceLabels() {
			return _choiceLabels;
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

		/** The label resolved in the language of the load, the id without a label. */
		String _label;

		/** The label's language map, <code>null</code> without a map. */
		Map<String, String> _labelMap;

		final List<Action> _entry = new ArrayList<>();

		final List<Action> _exit = new ArrayList<>();

		State(String id) {
			_id = id;
			_label = id;
		}

		/** The id. */
		public String id() {
			return _id;
		}

		/** The label (schema 1.7) resolved in the language of the load, the id where it has none. */
		public String label() {
			return _label;
		}

		/** The label's language map, <code>null</code> when the image gave a plain string or none. */
		public Map<String, String> labelMap() {
			return _labelMap;
		}
	}

	/**
	 * The timeline of a rule with states (<code>spec/rules.md</code> 2.8, schema 1.7): day plans of
	 * switching points and the assignments of the plans to days. Immutable once loaded.
	 */
	public static final class Timeline {
		final boolean _adjustable;

		final List<Plan> _plans = new ArrayList<>();

		final List<Assignment> _assignments = new ArrayList<>();

		Timeline(boolean adjustable) {
			_adjustable = adjustable;
		}

		/** Whether the inhabitant may replace the timeline (8.7). */
		public boolean adjustable() {
			return _adjustable;
		}

		/** The day plans. */
		public List<Plan> plans() {
			return Collections.unmodifiableList(_plans);
		}

		/** The assignments of the plans to days. */
		public List<Assignment> assignments() {
			return Collections.unmodifiableList(_assignments);
		}

		/** The number of points of all plans. */
		public int pointCount() {
			int result = 0;
			for (Plan plan : _plans) {
				result += plan._points.size();
			}
			return result;
		}

		/**
		 * The index of the plan of the given weekday (1 Monday .. 7 Sunday): that of the matching
		 * assignment of the highest rank (a <code>days</code> assignment, else the default).
		 */
		public int planOf(int weekday) {
			int fallback = -1;
			for (Assignment assignment : _assignments) {
				if (assignment._days == 0) {
					fallback = assignment._plan;
				} else if ((assignment._days & (1 << (weekday - 1))) != 0) {
					return assignment._plan;
				}
			}
			return fallback;
		}

		/**
		 * The canonical form of the timeline (<code>spec/rules.md</code> 8.7): its <code>plans</code>
		 * and <code>assignments</code> as a JSON value, members in the order of the tables of 2.8,
		 * an offset of 0 left out, without <code>adjustable</code>.
		 */
		public Map<String, Object> toJson() {
			List<Object> plans = new ArrayList<>();
			for (Plan plan : _plans) {
				Map<String, Object> json = new LinkedHashMap<>();
				json.put("id", plan._id);
				json.put("label", plan._label);
				List<Object> points = new ArrayList<>();
				for (Point point : plan._points) {
					points.add(point.toJson());
				}
				json.put("points", points);
				plans.add(json);
			}
			List<Object> assignments = new ArrayList<>();
			for (Assignment assignment : _assignments) {
				Map<String, Object> json = new LinkedHashMap<>();
				if (assignment._days != 0) {
					json.put("days", new ArrayList<>(assignment._dayList));
				}
				json.put("plan", _plans.get(assignment._plan)._id);
				assignments.add(json);
			}
			Map<String, Object> result = new LinkedHashMap<>();
			result.put("plans", plans);
			result.put("assignments", assignments);
			return result;
		}

		/**
		 * Whether the other timeline equals this one as <code>spec/rules.md</code> 8.7 compares an
		 * inhabitant's timeline with the image's: member by member in order (plans, their points, the
		 * assignments), an absent offset equal to 0, the days compared as sets, the labels as given;
		 * <code>adjustable</code> is not compared.
		 */
		public boolean sameAs(Timeline other) {
			if (other == null || other._plans.size() != _plans.size() || other._assignments.size() != _assignments.size()) {
				return false;
			}
			for (int i = 0; i < _plans.size(); i++) {
				Plan a = _plans.get(i);
				Plan b = other._plans.get(i);
				if (!a._id.equals(b._id) || !Objects.equals(a._label, b._label) || a._points.size() != b._points.size()) {
					return false;
				}
				for (int k = 0; k < a._points.size(); k++) {
					if (!a._points.get(k).sameAs(b._points.get(k))) {
						return false;
					}
				}
			}
			for (int i = 0; i < _assignments.size(); i++) {
				Assignment a = _assignments.get(i);
				Assignment b = other._assignments.get(i);
				if (a._days != b._days || !_plans.get(a._plan)._id.equals(other._plans.get(b._plan)._id)) {
					return false;
				}
			}
			return true;
		}

		/** The ids of the states the points of the timeline name, in the order of their first use. */
		public Set<String> stateIds() {
			Set<String> result = new LinkedHashSet<>();
			for (Plan plan : _plans) {
				for (Point point : plan._points) {
					result.add(point._stateId);
				}
			}
			return result;
		}
	}

	/**
	 * A day plan of a timeline: its switching points in plan order.
	 */
	public static final class Plan {
		final String _id;

		/** The label as the image gives it: a string or a map of language tags to strings. */
		final Object _label;

		final String _labelText;

		final List<Point> _points = new ArrayList<>();

		Plan(String id, Object label, String labelText) {
			_id = id;
			_label = label;
			_labelText = labelText;
		}

		/** The id. */
		public String id() {
			return _id;
		}

		/** The label resolved in the language of the load. */
		public String label() {
			return _labelText;
		}

		/** The switching points in plan order. */
		public List<Point> points() {
			return Collections.unmodifiableList(_points);
		}
	}

	/**
	 * A switching point (<code>spec/rules.md</code> 2.8): a clock time or a sun event with an offset
	 * and clamps, and the state the rule goes to.
	 */
	public static final class Point {
		/** The clock time in seconds of the day, -1 for a sun point. */
		final int _time;

		/** The sun event of a sun point, <code>null</code> for a clock point. */
		final Astro.SunEvent _sun;

		/** The offset of a sun point in seconds. */
		final int _offset;

		/** The earliest time of day of a sun point in seconds, -1 for none. */
		final int _notBefore;

		/** The latest time of day of a sun point in seconds, -1 for none. */
		final int _notAfter;

		/** The index of the state in the rule. */
		final int _state;

		final String _stateId;

		Point(int time, Astro.SunEvent sun, int offset, int notBefore, int notAfter, int state, String stateId) {
			_time = time;
			_sun = sun;
			_offset = offset;
			_notBefore = notBefore;
			_notAfter = notAfter;
			_state = state;
			_stateId = stateId;
		}

		/** The clock time in seconds of the day, -1 for a sun point. */
		public int time() {
			return _time;
		}

		/** The sun event, <code>null</code> for a clock point. */
		public Astro.SunEvent sun() {
			return _sun;
		}

		/** The offset of a sun point in seconds. */
		public int offset() {
			return _offset;
		}

		/** The earliest time of day of a sun point in seconds, -1 for none. */
		public int notBefore() {
			return _notBefore;
		}

		/** The latest time of day of a sun point in seconds, -1 for none. */
		public int notAfter() {
			return _notAfter;
		}

		/** The index of the state in the rule's states. */
		public int state() {
			return _state;
		}

		/** The id of the state. */
		public String stateId() {
			return _stateId;
		}

		boolean sameAs(Point other) {
			return _time == other._time && _sun == other._sun && _offset == other._offset
				&& _notBefore == other._notBefore && _notAfter == other._notAfter && _stateId.equals(other._stateId);
		}

		Map<String, Object> toJson() {
			Map<String, Object> result = new LinkedHashMap<>();
			if (_sun == null) {
				result.put("time", time(_time));
			} else {
				result.put("sun", _sun.externalName());
				if (_offset != 0) {
					result.put("offset", Long.valueOf(_offset * 1000L));
				}
				if (_notBefore >= 0) {
					result.put("notBefore", time(_notBefore));
				}
				if (_notAfter >= 0) {
					result.put("notAfter", time(_notAfter));
				}
			}
			result.put("state", _stateId);
			return result;
		}

		private static Map<String, Object> time(int seconds) {
			Map<String, Object> result = new LinkedHashMap<>();
			result.put("hour", Long.valueOf(seconds / 3600));
			result.put("minute", Long.valueOf(seconds / 60 % 60));
			result.put("second", Long.valueOf(seconds % 60));
			return result;
		}
	}

	/**
	 * An assignment of a plan to days: the default (every date) or weekdays.
	 */
	public static final class Assignment {
		/** The weekdays as bits (bit 0 Monday), 0 for the default. */
		final int _days;

		/** The weekdays as given (1 Monday .. 7 Sunday). */
		final List<Long> _dayList;

		/** The index of the plan. */
		final int _plan;

		Assignment(int days, List<Long> dayList, int plan) {
			_days = days;
			_dayList = dayList;
			_plan = plan;
		}

		/** The weekdays as bits (bit 0 Monday), 0 for the default. */
		public int days() {
			return _days;
		}

		/** The index of the plan. */
		public int plan() {
			return _plan;
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
		PARAMETER,
		/** A datapoint had no update for a period. */
		SILENCE;
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

		/** timer, parameter: the index in the rule; silence: the index in {@link RuleSet#_silences}. */
		int _ref = -1;

		/** silence: the period (a duration of literals and the rule's parameters). */
		Node _after;

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
		LOG("log"),
		/** Ask the technology for a datapoint's value. */
		READ("read");

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

		/** The index among all actions of the rule set. */
		int _index;

		/** write, read: the datapoint. */
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

	/** The JSON path of each root in the <code>rules</code> member, by {@link Node#_root}. */
	final List<String> _rootPaths = new ArrayList<>();

	/** The silence triggers in rule order. */
	final List<Trigger> _silences = new ArrayList<>();

	/** The index of the rule of each silence trigger. */
	final List<Integer> _silenceRules = new ArrayList<>();

	/** The number of actions of all rules. */
	int _actionCount;

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
