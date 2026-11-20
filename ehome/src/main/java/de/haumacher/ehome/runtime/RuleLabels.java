/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.runtime;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import com.top_logic.basic.util.ResKey;
import com.top_logic.basic.util.ResKey1;
import com.top_logic.model.TLClassifier;
import com.top_logic.model.TLObject;
import com.top_logic.util.Resources;

import de.haumacher.ehome.model.runtime.CancelTimerAction;
import de.haumacher.ehome.model.runtime.DatapointTrigger;
import de.haumacher.ehome.model.runtime.LogAction;
import de.haumacher.ehome.model.runtime.MatchTrigger;
import de.haumacher.ehome.model.runtime.ParameterTrigger;
import de.haumacher.ehome.model.runtime.Reaction;
import de.haumacher.ehome.model.runtime.ReadAction;
import de.haumacher.ehome.model.runtime.RestartTimerAction;
import de.haumacher.ehome.model.runtime.Rule;
import de.haumacher.ehome.model.runtime.RuleParameter;
import de.haumacher.ehome.model.runtime.RuleState;
import de.haumacher.ehome.model.runtime.ScheduleTrigger;
import de.haumacher.ehome.model.runtime.SetParameterAction;
import de.haumacher.ehome.model.runtime.SilenceTrigger;
import de.haumacher.ehome.model.runtime.StartTimerAction;
import de.haumacher.ehome.model.runtime.StartTrigger;
import de.haumacher.ehome.model.runtime.TimerTrigger;
import de.haumacher.ehome.model.runtime.WriteAction;
import de.haumacher.ehome.monitor.DatapointCodec.Dpt;
import de.haumacher.ehome.programming.GroupAddressTypes;
import de.haumacher.ehome.rules.RuleText;
import de.haumacher.ehome.rules.RuleText.SyntaxError;

/**
 * The short display texts of the reactions, triggers and actions of a rule (haumacher/ehome #214),
 * which the tables of the rules show: in the language of the reader, every datapoint, schedule
 * entry, parameter and timer by its name alone ({@link DatapointNames#withoutIds()}), each text a
 * whole phrase of the {@link I18NConstants}.
 *
 * <p>
 * A reaction reads in the order the rule evaluates it: the states it reacts in, its triggers, its
 * condition and the state it goes to (<code>In state normal: when Bewegung Hof received · if
 * Bewegung Hof → ankommen</code>); the parts it does not have are left out. A value written is shown
 * by the type of its target: a truth value as On/Off, of an up/down datapoint (DPT 1.008) as
 * Up/Down, a number with the unit of the target, a duration as <code>10 min</code>; another
 * expression with the names of its references, its operators as the rule language writes them.
 * </p>
 *
 * <p>
 * The technical text, with the ids, stays {@link RuleFunctions#summary(TLObject)}: the tooltips of
 * the tables, the refusals of the image, the log and the assistant read it.
 * </p>
 */
public final class RuleLabels {

	/** The text standing for a part that is not set. */
	private static final String NONE = "–";

	private final DatapointNames _names;

	private final TLObject _rule;

	private final Resources _resources = Resources.getInstance();

	private RuleLabels(TLObject obj) {
		_names = DatapointNames.of(RuntimeFunctions.project(obj)).withoutIds();
		_rule = RuleReferences.rule(obj);
	}

	/**
	 * The short display text of a reaction, a trigger or an action; of another part of a rule (a
	 * schedule entry, a point of a timeline) its {@link RuleFunctions#summary(TLObject) summary}.
	 */
	public static String label(TLObject obj) {
		RuleLabels labels = new RuleLabels(obj);
		if (obj instanceof Reaction reaction) {
			return labels.reaction(reaction);
		}
		String result = labels.part(obj);
		return result != null ? result : RuleFunctions.summary(obj);
	}

	// ---- reactions ---------------------------------------------------------------------------

	/** The kinds of the triggers of a reaction by the phrase that names them in it. */
	private enum Kind {
		/** A trigger whose text starts with a name: it is used as it is. */
		NAMED,
		/** A run of schedule triggers, named together: <code>schedule A or B</code>. */
		SCHEDULE,
		/** A timer trigger. */
		TIMER,
		/** A start trigger on all reasons. */
		START,
		/** A start trigger on some reasons. */
		START_REASONS;
	}

	/** A trigger of a reaction, or a run of schedule triggers, with the parameter of its phrase. */
	private record Segment(Kind kind, String text) {
		// The parts.
	}

	private String reaction(Reaction reaction) {
		List<Segment> segments = new ArrayList<>();
		List<String> schedules = null;
		for (TLObject trigger : RuleExport.objects(reaction.getTriggers())) {
			if (trigger instanceof ScheduleTrigger schedule) {
				if (schedules == null) {
					schedules = new ArrayList<>();
				}
				schedules.add(name(schedule.getSchedule(), "schedule"));
				continue;
			}
			if (schedules != null) {
				segments.add(new Segment(Kind.SCHEDULE, or(schedules)));
				schedules = null;
			}
			segments.add(segment(trigger));
		}
		if (schedules != null) {
			segments.add(new Segment(Kind.SCHEDULE, or(schedules)));
		}
		if (segments.isEmpty()) {
			segments.add(new Segment(Kind.NAMED, NONE));
		}

		// The states in the order the rule declares them.
		List<TLObject> fromStates = RuleExport.objects(reaction.getFrom());
		if (_rule instanceof Rule rule) {
			List<?> order = rule.getStates();
			fromStates.sort(Comparator.comparingInt(state -> order.indexOf(state)));
		}
		List<String> from = new ArrayList<>();
		for (TLObject state : fromStates) {
			from.add(String.valueOf(((RuleState) state).getIdentifier()));
		}
		String states = from.isEmpty() ? null : String.join(", ", from);

		String text = first(segments.get(0), states);
		for (Segment segment : segments.subList(1, segments.size())) {
			text = next(text, segment);
		}
		String condition = _names.expression(_rule, text(reaction.getCondition()));
		if (condition != null) {
			text = say(I18NConstants.REACTION_IF__TEXT_CONDITION.fill(text, condition));
		}
		if (reaction.getTo() instanceof RuleState to) {
			text = say(I18NConstants.REACTION_TO__TEXT_STATE.fill(text, String.valueOf(to.getIdentifier())));
		}
		return text;
	}

	private Segment segment(TLObject trigger) {
		if (trigger instanceof TimerTrigger timer) {
			return new Segment(Kind.TIMER, name(timer.getTimer(), "timer"));
		}
		if (trigger instanceof StartTrigger start) {
			ResKey reasons = reasons(start);
			return reasons == null ? new Segment(Kind.START, null) : new Segment(Kind.START_REASONS, say(reasons));
		}
		String text = part(trigger);
		return new Segment(Kind.NAMED, text != null ? text : NONE);
	}

	private String first(Segment segment, String states) {
		String text = segment.text();
		if (states == null) {
			switch (segment.kind()) {
				case SCHEDULE:
					return say(I18NConstants.REACTION_WHEN_SCHEDULE__NAMES.fill(text));
				case TIMER:
					return say(I18NConstants.REACTION_WHEN_TIMER__NAME.fill(text));
				case START:
					return say(I18NConstants.REACTION_WHEN_START);
				case START_REASONS:
					return say(I18NConstants.REACTION_WHEN_START__REASONS.fill(text));
				default:
					return say(I18NConstants.REACTION_WHEN__TRIGGER.fill(text));
			}
		}
		switch (segment.kind()) {
			case SCHEDULE:
				return say(I18NConstants.REACTION_IN_STATE_WHEN_SCHEDULE__STATES_NAMES.fill(states, text));
			case TIMER:
				return say(I18NConstants.REACTION_IN_STATE_WHEN_TIMER__STATES_NAME.fill(states, text));
			case START:
				return say(I18NConstants.REACTION_IN_STATE_WHEN_START__STATES.fill(states));
			case START_REASONS:
				return say(I18NConstants.REACTION_IN_STATE_WHEN_START__STATES_REASONS.fill(states, text));
			default:
				return say(I18NConstants.REACTION_IN_STATE_WHEN__STATES_TRIGGER.fill(states, text));
		}
	}

	private String next(String before, Segment segment) {
		String text = segment.text();
		switch (segment.kind()) {
			case SCHEDULE:
				return say(I18NConstants.REACTION_OR_SCHEDULE__TEXT_NAMES.fill(before, text));
			case TIMER:
				return say(I18NConstants.REACTION_OR_TIMER__TEXT_NAME.fill(before, text));
			case START:
				return say(I18NConstants.REACTION_OR_START__TEXT.fill(before));
			case START_REASONS:
				return say(I18NConstants.REACTION_OR_START__TEXT_REASONS.fill(before, text));
			default:
				return say(I18NConstants.REACTION_OR__TEXT_TRIGGER.fill(before, text));
		}
	}

	private String or(List<String> names) {
		String result = names.get(0);
		for (String name : names.subList(1, names.size())) {
			result = say(I18NConstants.REACTION_OR__TEXT_TRIGGER.fill(result, name));
		}
		return result;
	}

	// ---- triggers and actions ----------------------------------------------------------------

	/**
	 * The text of a trigger or an action, <code>null</code> for another object.
	 */
	private String part(TLObject obj) {
		if (obj instanceof DatapointTrigger trigger) {
			return say(datapointTrigger(trigger).fill(name(trigger.getDatapoint(), "datapoint")));
		}
		if (obj instanceof MatchTrigger trigger) {
			return say(I18NConstants.TRIGGER_MATCH__CONDITION.fill(orNone(expression(trigger.getCondition()))));
		}
		if (obj instanceof SilenceTrigger trigger) {
			return say(I18NConstants.TRIGGER_SILENCE__NAME_DURATION.fill(
				name(trigger.getDatapoint(), "datapoint"), orNone(value(null, trigger.getAfter()))));
		}
		if (obj instanceof ScheduleTrigger trigger) {
			return say(I18NConstants.TRIGGER_SCHEDULE__NAME.fill(name(trigger.getSchedule(), "schedule")));
		}
		if (obj instanceof TimerTrigger trigger) {
			return say(I18NConstants.TRIGGER_TIMER__NAME.fill(name(trigger.getTimer(), "timer")));
		}
		if (obj instanceof ParameterTrigger trigger) {
			return say(I18NConstants.TRIGGER_PARAMETER__NAME.fill(name(trigger.getParameter(), "parameter")));
		}
		if (obj instanceof StartTrigger trigger) {
			ResKey reasons = reasons(trigger);
			return reasons == null ? say(I18NConstants.TRIGGER_START)
				: say(I18NConstants.TRIGGER_START__REASONS.fill(say(reasons)));
		}
		if (obj instanceof WriteAction action) {
			String target = name(action.getDatapoint(), "datapoint");
			String value = orNone(value(action.getDatapoint(), action.getValue()));
			return say(action.getOnlyOnChange() ? I18NConstants.ACTION_WRITE_ON_CHANGE__TARGET_VALUE.fill(target, value)
				: I18NConstants.ACTION_WRITE__TARGET_VALUE.fill(target, value));
		}
		if (obj instanceof ReadAction action) {
			return say(I18NConstants.ACTION_READ__NAME.fill(name(action.getDatapoint(), "datapoint")));
		}
		if (obj instanceof StartTimerAction action) {
			String timer = name(action.getTimer(), "timer");
			String duration = value(null, action.getDuration());
			return say(duration == null ? I18NConstants.ACTION_START_TIMER__NAME.fill(timer)
				: I18NConstants.ACTION_START_TIMER__NAME_DURATION.fill(timer, duration));
		}
		if (obj instanceof RestartTimerAction action) {
			String timer = name(action.getTimer(), "timer");
			String duration = value(null, action.getDuration());
			return say(duration == null ? I18NConstants.ACTION_RESTART_TIMER__NAME.fill(timer)
				: I18NConstants.ACTION_RESTART_TIMER__NAME_DURATION.fill(timer, duration));
		}
		if (obj instanceof CancelTimerAction action) {
			return say(I18NConstants.ACTION_CANCEL_TIMER__NAME.fill(name(action.getTimer(), "timer")));
		}
		if (obj instanceof SetParameterAction action) {
			return say(I18NConstants.ACTION_SET_PARAMETER__NAME_VALUE.fill(
				name(action.getParameter(), "parameter"), orNone(value(action.getParameter(), action.getValue()))));
		}
		if (obj instanceof LogAction action) {
			String message = orNone(_names.message(_rule, text(action.getMessage())));
			String level = action.getLevel() instanceof TLClassifier classifier ? classifier.getName() : "info";
			switch (level) {
				case "warning":
					return say(I18NConstants.ACTION_LOG_WARNING__MESSAGE.fill(message));
				case "error":
					return say(I18NConstants.ACTION_LOG_ERROR__MESSAGE.fill(message));
				default:
					return say(I18NConstants.ACTION_LOG_INFO__MESSAGE.fill(message));
			}
		}
		return null;
	}

	private static ResKey1 datapointTrigger(DatapointTrigger trigger) {
		boolean update = trigger.getOn() instanceof TLClassifier on && on.getName().equals("update");
		String origin = trigger.getOrigin() instanceof TLClassifier classifier ? classifier.getName() : "any";
		switch (origin) {
			case "report":
				return update ? I18NConstants.TRIGGER_RECEIVED_REPORT__NAME : I18NConstants.TRIGGER_CHANGES_REPORT__NAME;
			case "command":
				return update ? I18NConstants.TRIGGER_RECEIVED_COMMAND__NAME : I18NConstants.TRIGGER_CHANGES_COMMAND__NAME;
			default:
				return update ? I18NConstants.TRIGGER_RECEIVED__NAME : I18NConstants.TRIGGER_CHANGES__NAME;
		}
	}

	/**
	 * The reasons of a start trigger in parentheses, <code>null</code> for all three.
	 */
	private static ResKey reasons(StartTrigger trigger) {
		boolean boot = trigger.getOnBoot();
		boolean image = trigger.getOnImage();
		boolean enable = trigger.getOnEnable();
		if (boot && image && enable) {
			return null;
		}
		if (boot && image) {
			return I18NConstants.START_REASONS_BOOT_IMAGE;
		}
		if (boot && enable) {
			return I18NConstants.START_REASONS_BOOT_ENABLE;
		}
		if (image && enable) {
			return I18NConstants.START_REASONS_IMAGE_ENABLE;
		}
		if (boot) {
			return I18NConstants.START_REASONS_BOOT;
		}
		if (image) {
			return I18NConstants.START_REASONS_IMAGE;
		}
		if (enable) {
			return I18NConstants.START_REASONS_ENABLE;
		}
		return I18NConstants.START_REASONS_NONE;
	}

	// ---- names and values --------------------------------------------------------------------

	private String name(Object target, String attribute) {
		return orNone(_names.reference(target, attribute));
	}

	private String expression(String text) {
		return _names.expression(_rule, text(text));
	}

	/**
	 * A value written to the given target (<code>null</code> for a duration of a timer or a
	 * silence), as the target's type shows it; <code>null</code> for none.
	 */
	private String value(TLObject target, String text) {
		String expression = text(text);
		if (expression == null) {
			return null;
		}
		Object node;
		try {
			node = RuleText.normalize(RuleText.parse(expression));
		} catch (SyntaxError ex) {
			return expression;
		}
		if (!(node instanceof Map<?, ?> map) || !"literal".equals(map.get("kind"))) {
			return expression(expression);
		}
		String literal = RuleText.print(node);
		switch (String.valueOf(map.get("type"))) {
			case "boolean": {
				boolean on = Boolean.TRUE.equals(map.get("value"));
				if (isUpDown(target)) {
					return say(on ? I18NConstants.VALUE_DOWN : I18NConstants.VALUE_UP);
				}
				return say(on ? I18NConstants.VALUE_ON : I18NConstants.VALUE_OFF);
			}
			case "integer":
			case "real": {
				String unit = unit(target);
				return unit == null ? literal : literal + " " + unit;
			}
			case "duration":
				// 1h30min as 1 h 30 min.
				return literal.replaceAll("(\\d)(?=[a-z])", "$1 ").replaceAll("([a-z])(?=\\d)", "$1 ");
			default:
				return expression(expression);
		}
	}

	/**
	 * Whether the target is a group address of the type up/down (DPT 1.008: 0 up, 1 down).
	 */
	private static boolean isUpDown(TLObject target) {
		if (target == null || !target.tValid() || !Pages.isGroupAddress(target)) {
			return false;
		}
		Dpt dpt = GroupAddressTypes.effective(target);
		return dpt != null && !dpt.isMainType() && dpt.main() == 1 && dpt.sub() == 8;
	}

	/**
	 * The unit of the values of the target, <code>null</code> for none.
	 */
	private static String unit(TLObject target) {
		if (target == null || !target.tValid()) {
			return null;
		}
		if (Pages.isGroupAddress(target) || ApplicationDatapoints.isApplicationDatapoint(target)) {
			return DatapointSettings.unit(target);
		}
		if (target instanceof RuleParameter parameter) {
			return text(parameter.getUnit());
		}
		return null;
	}

	private String say(ResKey key) {
		return _resources.getString(key);
	}

	private static String orNone(String text) {
		return text == null ? NONE : text;
	}

	private static String text(Object value) {
		return value == null || value.toString().isBlank() ? null : value.toString().trim();
	}

}
