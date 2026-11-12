/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.top_logic.model.TLFormObjectBase;
import com.top_logic.model.TLObject;

import de.haumacher.ehome.rules.Schedule;

/**
 * The references of rules and schedule entries to the objects they name (haumacher/ehome #90): the
 * datapoint of a trigger or action, the output of a schedule entry, the schedule entry of a trigger,
 * the timer and parameter of a trigger or action. The image names each by its id
 * ({@link #id(TLObject, Object, String)}); the forms offer the objects that fit ({@link #options(Object, String)});
 * an import resolves the ids of an image ({@link #resolve(TLObject, TLObject, String, String, Map)}).
 */
public final class RuleReferences {

	/** The attributes of the rules and schedule entries that are such references. */
	public static final Set<String> ATTRIBUTES = Set.of("datapoint", "output", "schedule", "timer", "parameter");

	/** Options of a trigger on a datapoint: every datapoint of the image. */
	public static final String TRIGGER = "trigger";

	/** Options of a write action: the writable datapoints. */
	public static final String WRITE = "write";

	/** Options of a read action: the datapoints bound to a technology that reads values. */
	public static final String READ = "read";

	/** Options of the output of a schedule entry: the boolean application datapoints not commanded. */
	public static final String OUTPUT = "output";

	/** Options of a schedule trigger: the entries of the project. */
	public static final String SCHEDULE = "schedule";

	/** Options of a timer: the timers of the rule. */
	public static final String TIMER = "timer";

	/** Options of a parameter: the parameters of the rule. */
	public static final String PARAMETER = "parameter";

	private RuleReferences() {
		// Static only.
	}

	// ---- ids ---------------------------------------------------------------------------------

	/**
	 * The id the image names the given target of a reference by: the id of its datapoint for a
	 * datapoint ({@link #datapointId(TLObject, TLObject)}), the identifier of a schedule entry, timer
	 * or parameter; <code>null</code> for none.
	 *
	 * @param project
	 *        The project of the rule or schedule entry.
	 * @param target
	 *        The value of the reference.
	 * @param attribute
	 *        The name of the reference.
	 */
	public static String id(TLObject project, Object target, String attribute) {
		if (!(target instanceof TLObject obj) || !obj.tValid()) {
			return null;
		}
		switch (attribute) {
			case "schedule":
			case "timer":
			case "parameter":
				return identifier(obj);
			default:
				return datapointId(project, obj);
		}
	}

	/**
	 * The id of the datapoint the given object stands for in the image of the project: the
	 * controller's datapoint of a time master address (<code>knx.time</code>), else its id
	 * ({@link Pages#imageId(TLObject)}: a group address in three levels, the id of an application
	 * datapoint, <code>rule/&lt;rule&gt;/&lt;parameter&gt;</code>, <code>rule/&lt;rule&gt;/enabled</code>,
	 * <code>schedule/&lt;id&gt;/time</code>, the id of a datapoint of a virtual device).
	 */
	public static String datapointId(TLObject project, TLObject datapoint) {
		if (project != null && Pages.isGroupAddress(datapoint)) {
			String timeMaster = RuntimeImage.timeMasterId(project, datapoint);
			if (timeMaster != null) {
				return timeMaster;
			}
		}
		return Pages.imageId(datapoint);
	}

	private static String identifier(TLObject obj) {
		Object id = obj.tValueByName("identifier");
		return id == null || id.toString().isBlank() ? null : id.toString().trim();
	}

	/**
	 * The objects of the project that are datapoints of its image, by their ids: the group addresses
	 * (also those the image cannot carry), the application datapoints, the rules (their enable
	 * switch) and their parameters, the schedule entries with a time, the datapoints of the virtual
	 * devices; the first object of an id wins.
	 */
	public static Map<String, TLObject> datapoints(TLObject project) {
		Map<String, TLObject> result = new LinkedHashMap<>();
		List<TLObject> candidates = new ArrayList<>(RuntimeFunctions.groupAddresses(project));
		candidates.addAll(ApplicationDatapoints.of(project));
		for (TLObject rule : RuleExport.orderedRules(project)) {
			candidates.add(rule);
			candidates.addAll(RuleExport.objects(rule.tValueByName("parameters")));
			TLObject state = Pages.stateDatapoint(rule);
			if (state != null) {
				// rule/<rule>/state (haumacher/ehome #140).
				candidates.add(state);
			}
		}
		for (TLObject entry : RuleExport.objects(project.tValueByName("schedules"))) {
			if (Pages.isTimeSchedule(entry)) {
				candidates.add(entry);
			}
		}
		candidates.addAll(BlockInstances.datapoints(project));
		for (TLObject candidate : candidates) {
			String id = datapointId(project, candidate);
			if (id != null) {
				result.putIfAbsent(id, candidate);
			}
		}
		return result;
	}

	/**
	 * The object an id of an image names for the given reference: a datapoint of the project (an
	 * application datapoint for the output of a schedule entry), a schedule entry of the project, a
	 * timer or parameter of the rule; <code>null</code> if there is none.
	 *
	 * @param project
	 *        The project.
	 * @param rule
	 *        The rule the reference belongs to, <code>null</code> for a schedule entry.
	 * @param attribute
	 *        The name of the reference.
	 * @param id
	 *        The id of the image.
	 * @param datapoints
	 *        The datapoints of the project by their ids ({@link #datapoints(TLObject)}), computed once
	 *        for many references.
	 */
	public static TLObject resolve(TLObject project, TLObject rule, String attribute, String id,
			Map<String, TLObject> datapoints) {
		if (id == null || id.isBlank()) {
			return null;
		}
		String key = id.trim();
		switch (attribute) {
			case "schedule":
				return byIdentifier(RuleExport.objects(project.tValueByName("schedules")), key);
			case "timer":
				return rule == null ? null : byIdentifier(RuleExport.objects(rule.tValueByName("timers")), key);
			case "parameter":
				return rule == null ? null : byIdentifier(RuleExport.objects(rule.tValueByName("parameters")), key);
			case "output": {
				TLObject datapoint = datapoints.get(key);
				return datapoint != null && ApplicationDatapoints.isApplicationDatapoint(datapoint) ? datapoint : null;
			}
			default: {
				TLObject datapoint = datapoints.get(key);
				if (datapoint == null) {
					// A group address also by its address where its datapoint has an identifier (haumacher/ehome #130).
					for (TLObject address : RuntimeFunctions.groupAddresses(project)) {
						if (key.equals(GroupAddressIds.address(address))) {
							return address;
						}
					}
				}
				return datapoint;
			}
		}
	}

	private static TLObject byIdentifier(List<TLObject> objects, String id) {
		for (TLObject obj : objects) {
			if (id.equals(identifier(obj))) {
				return obj;
			}
		}
		return null;
	}

	// ---- options -----------------------------------------------------------------------------

	/**
	 * The objects a reference of the given kind may name, for the given object (a trigger, action or
	 * schedule entry, or a form's overlay of it), in the order of the project.
	 *
	 * <ul>
	 * <li>{@link #TRIGGER}: every datapoint of the image, of the rules' parameters and of the
	 * adjustable times of the schedule entries;</li>
	 * <li>{@link #WRITE}: those a rule may write: the writable ones, and every application datapoint
	 * (homecontrol <code>spec/rules.md</code> 7.3, schema 1.6, haumacher/ehome #138), but not the output
	 * of a schedule entry;</li>
	 * <li>{@link #READ}: those bound to a technology that reads values;</li>
	 * <li>{@link #OUTPUT}: the boolean application datapoints that are not writable (the controller
	 * refuses commands to an output, {@link ApplicationDatapoints#outputProblem(Object, Object)});</li>
	 * <li>{@link #SCHEDULE}: the entries of the project;</li>
	 * <li>{@link #TIMER}, {@link #PARAMETER}: the timers or parameters of the rule.</li>
	 * </ul>
	 *
	 * <p>
	 * The datapoints are those of the image of the committed state, computed once per state of the
	 * data ({@link ImageCache}).
	 * </p>
	 */
	public static List<TLObject> options(Object obj, String kind) {
		TLObject stored = stored(obj);
		TLObject project = stored == null ? null : RuntimeFunctions.project(stored);
		if (project == null) {
			return List.of();
		}
		switch (kind) {
			case TRIGGER:
			case WRITE:
			case READ:
				return ImageCache.get(project).derived(new OptionsKey(kind), entry -> datapointOptions(project, entry, kind));
			case OUTPUT: {
				List<TLObject> result = new ArrayList<>();
				for (TLObject datapoint : ApplicationDatapoints.of(project)) {
					if (ValueTypes.BOOLEAN.equals(ApplicationDatapoints.valueType(datapoint))
						&& !ApplicationDatapoints.writable(datapoint)) {
						result.add(datapoint);
					}
				}
				return result;
			}
			case SCHEDULE:
				return RuleExport.objects(project.tValueByName("schedules"));
			case TIMER:
			case PARAMETER: {
				TLObject rule = rule(stored);
				return rule == null ? List.of() : RuleExport.objects(rule.tValueByName(kind.equals(TIMER) ? "timers" : "parameters"));
			}
			default:
				return List.of();
		}
	}

	/** The key of the options of a kind among the values derived from an {@link ImageCache.Entry}. */
	private record OptionsKey(String kind) {
		// Value only.
	}

	private static List<TLObject> datapointOptions(TLObject project, ImageCache.Entry entry, String kind) {
		ImageValidator.Directory directory = entry.directory();
		Set<String> times = Set.copyOf(Schedule.timeDatapointIds(new RuleExport().schedules(project)));
		List<TLObject> result = new ArrayList<>();
		for (Map.Entry<String, TLObject> datapoint : datapoints(project).entrySet()) {
			String id = datapoint.getKey();
			TLObject target = datapoint.getValue();
			boolean fits;
			if (Pages.isTimeSchedule(target)) {
				// The adjustable time of an entry (homecontrol #58): read, triggered on and written by rules.
				fits = times.contains(identifier(target)) && !kind.equals(READ);
			} else if (directory.type(id) == null) {
				fits = false;
			} else {
				fits = switch (kind) {
					case WRITE -> directory.ruleWritable(id) && !directory.ownsOutput(id);
					case READ -> directory.technologyReads(id);
					default -> true;
				};
			}
			if (fits) {
				result.add(target);
			}
		}
		return List.copyOf(result);
	}

	/**
	 * The label of an option of a reference: the label of the object and the id the image names it
	 * by, in the format of every display text naming a datapoint ({@link DatapointNames#format(String, String)}).
	 *
	 * @param kind
	 *        The kind of options ({@link #options(Object, String)}): it decides the id, a parameter of
	 *        a rule is the datapoint <code>rule/&lt;rule&gt;/&lt;parameter&gt;</code> for a trigger and
	 *        its identifier for a parameter trigger.
	 */
	public static String optionLabel(Object option, String kind) {
		if (!(option instanceof TLObject target) || !target.tValid()) {
			return "";
		}
		TLObject project = RuntimeFunctions.project(target);
		String id = switch (kind) {
			case SCHEDULE, TIMER, PARAMETER -> identifier(target);
			default -> datapointId(project, target);
		};
		String label = DatapointNames.label(target);
		return id == null && label == null ? "" : DatapointNames.format(label, id);
	}

	// ---- context -----------------------------------------------------------------------------

	/** The stored object of a form's overlay, the object itself otherwise. */
	static TLObject stored(Object obj) {
		if (obj instanceof TLFormObjectBase form) {
			return form.getEditedObject();
		}
		return obj instanceof TLObject object && object.tValid() ? object : null;
	}

	/** The rule the given part of a rule belongs to, <code>null</code> for none. */
	static TLObject rule(TLObject part) {
		for (TLObject node = part; node != null; node = node.tContainer()) {
			if ("Rule".equals(node.tType().getName()) && "ehome.runtime".equals(node.tType().getModule().getName())) {
				return node;
			}
		}
		return null;
	}

}
