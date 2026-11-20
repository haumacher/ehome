/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.runtime;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.function.Function;

import com.top_logic.basic.util.ResKey;
import com.top_logic.basic.util.ResKey1;
import com.top_logic.basic.util.ResourcesModule;
import com.top_logic.model.TLClass;
import com.top_logic.model.TLClassifier;
import com.top_logic.model.TLEnumeration;
import com.top_logic.model.TLObject;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.Resources;
import com.top_logic.util.error.TopLogicException;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.model.logical.HistoryArchive;
import de.haumacher.ehome.model.logical.ProjectDatapoint;
import de.haumacher.ehome.model.project.Project;
import de.haumacher.ehome.model.runtime.Action;
import de.haumacher.ehome.model.runtime.ApplicationDatapoint;
import de.haumacher.ehome.model.runtime.AstroSchedule;
import de.haumacher.ehome.model.runtime.CancelTimerAction;
import de.haumacher.ehome.model.runtime.DatapointTrigger;
import de.haumacher.ehome.model.runtime.DatesSchedule;
import de.haumacher.ehome.model.runtime.IntervalSchedule;
import de.haumacher.ehome.model.runtime.LogAction;
import de.haumacher.ehome.model.runtime.MatchTrigger;
import de.haumacher.ehome.model.runtime.ParameterTrigger;
import de.haumacher.ehome.model.runtime.Reaction;
import de.haumacher.ehome.model.runtime.ReadAction;
import de.haumacher.ehome.model.runtime.RestartTimerAction;
import de.haumacher.ehome.model.runtime.Rule;
import de.haumacher.ehome.model.runtime.RuleChoice;
import de.haumacher.ehome.model.runtime.RuleParameter;
import de.haumacher.ehome.model.runtime.RuleState;
import de.haumacher.ehome.model.runtime.RuleTimeline;
import de.haumacher.ehome.model.runtime.RuleTimer;
import de.haumacher.ehome.model.runtime.ScheduleEntry;
import de.haumacher.ehome.model.runtime.ScheduleTrigger;
import de.haumacher.ehome.model.runtime.SetParameterAction;
import de.haumacher.ehome.model.runtime.SilenceTrigger;
import de.haumacher.ehome.model.runtime.StartTimerAction;
import de.haumacher.ehome.model.runtime.StartTrigger;
import de.haumacher.ehome.model.runtime.TimelineAssignment;
import de.haumacher.ehome.model.runtime.TimelinePlan;
import de.haumacher.ehome.model.runtime.TimelinePoint;
import de.haumacher.ehome.model.runtime.TimerTrigger;
import de.haumacher.ehome.model.runtime.Trigger;
import de.haumacher.ehome.model.runtime.WeeklySchedule;
import de.haumacher.ehome.model.runtime.WriteAction;
import de.haumacher.ehome.model.runtime.YearlySchedule;
import de.haumacher.ehome.model.zigbee.ZigbeeDevice;
import de.haumacher.ehome.model.zigbee.ZigbeeFunction;
import de.haumacher.ehome.rules.RuleLoader;

import de.haumacher.ehome.rules.RuleText;
import de.haumacher.ehome.rules.Type;
import de.haumacher.ehome.zigbee.ZigbeeBindings;
import de.haumacher.ehome.zigbee.ZigbeeImport;
import de.haumacher.ehome.zigbee.ZigbeeReporting;

/**
 * Reads the members <code>rules</code> and <code>schedules</code> of a runtime image (their parsed
 * JSON) into a project's model (haumacher/ehome #57), the inverse of {@link RuleExport}: the
 * expressions are stored as their canonical text ({@link RuleText#print(Object)}), the literal values
 * as literals, the names in every application language.
 *
 * <p>
 * Used to bring rules written as JSON (the conformance corpus of HomeControl, the test home of the
 * KNX test system) into E-Home, with the application datapoints and the location they need
 * ({@link #importImage(TLObject, String)}); the caller provides the transaction. Members the model
 * does not keep (a <code>description</code> or <code>comment</code> below a rule) and what the import
 * decided besides (a datapoint kept, created unbound) are named in the {@link #getNotes() notes}.
 * </p>
 */
public final class RuleImport {

	private final List<String> _notes = new ArrayList<>();

	/** The references read, resolved when the rules and entries are in the project (haumacher/ehome #90). */
	private final List<Pending> _pending = new ArrayList<>();

	/** The rule being read, the scope of the ids of its timers and parameters. */
	private TLObject _rule;

	/** Whether the references are resolved by the caller, after rules and entries are imported both. */
	private boolean _deferred;

	/** A reference read by its id, not yet resolved. */
	private record Pending(Consumer<TLObject> setter, String attribute, String id, TLObject rule) {
		// Value only.
	}

	/**
	 * What the import did not keep.
	 */
	public List<String> getNotes() {
		return _notes;
	}

	/**
	 * Adds the given rules (the member <code>rules</code> of an image) behind the rules of the
	 * project, in their order.
	 */
	public List<TLObject> importRules(TLObject project, List<?> rules) {
		Project owner = (Project) project;
		List<Rule> all = typed(RuleExport.objects(owner.getRules()));
		long order = 0;
		for (Rule rule : all) {
			if (rule.getOrder() instanceof Number number) {
				order = Math.max(order, number.longValue());
			}
		}
		List<TLObject> result = new ArrayList<>();
		for (Object item : rules) {
			Rule rule = rule((Map<?, ?>) item);
			rule.setOrder(Integer.valueOf((int) ++order));
			all.add(rule);
			result.add(rule);
		}
		owner.setRules(all);
		resolveUnlessDeferred(project);
		return result;
	}

	/**
	 * Adds the given schedule entries (the member <code>schedules</code> of an image) behind the
	 * entries of the project.
	 */
	public List<TLObject> importSchedules(TLObject project, List<?> schedules) {
		Project owner = (Project) project;
		List<ScheduleEntry> all = typed(RuleExport.objects(owner.getSchedules()));
		List<TLObject> result = new ArrayList<>();
		for (Object item : schedules) {
			ScheduleEntry entry = schedule((Map<?, ?>) item);
			all.add(entry);
			result.add(entry);
		}
		owner.setSchedules(all);
		resolveUnlessDeferred(project);
		return result;
	}

	/**
	 * Adds the rules, schedule entries and application datapoints of a runtime image (the JSON text of
	 * an image, of an image fragment such as the test home <code>samples/KNX-Testsystem.home.json</code>,
	 * or of any object with these members, such as a case of the rule corpus) to the project
	 * (haumacher/ehome #60): the rules behind the project's rules, the entries behind its entries, the
	 * datapoints behind its application datapoints ({@link #importDatapoints(TLObject, List)}). The
	 * <code>settings</code> give the project its location and time zone where it has none
	 * ({@link #importSettings(TLObject, Object)}). A rule, schedule entry or datapoint whose id the
	 * project has already is kept as it is and named in the {@link #getNotes() notes}, so that an image
	 * imported again adds nothing twice. The caller provides the transaction.
	 *
	 * @return What was imported: the rules, the schedule entries and the datapoints.
	 * @throws TopLogicException
	 *         If the text is no JSON object, or has none of these members, or a rule, entry or datapoint
	 *         cannot be read.
	 */
	public Imported importImage(TLObject project, String text) {
		Object json;
		try {
			json = Json.parse(text, 100);
		} catch (Json.JsonException ex) {
			throw new TopLogicException(I18NConstants.ERROR_RULE_IMPORT_NO_JSON__REASON.fill(ex.getMessage()), ex);
		}
		if (!(json instanceof Map<?, ?> image)
			|| !(image.get("rules") instanceof List<?> || image.get("schedules") instanceof List<?>
				|| image.get("datapoints") instanceof List<?> || image.get("settings") instanceof Map<?, ?>)) {
			throw new TopLogicException(I18NConstants.ERROR_RULE_IMPORT_NOTHING);
		}
		try {
			List<TLObject> datapoints =
				image.get("datapoints") instanceof List<?> list ? importDatapoints(project, list) : List.of();
			importSettings(project, image.get("settings"));
			// A rule may name an entry imported after it, an entry the parameter of a rule.
			_deferred = true;
			List<TLObject> rules;
			List<TLObject> schedules;
			try {
				Project owner = (Project) project;
				rules = image.get("rules") instanceof List<?> list
					? importRules(project, unknown(ids(owner.getRules(), Rule::getIdentifier), list,
						I18NConstants.IMPORT_RULE_KEPT__ID))
					: List.of();
				schedules = image.get("schedules") instanceof List<?> list
					? importSchedules(project, unknown(ids(owner.getSchedules(), ScheduleEntry::getIdentifier), list,
						I18NConstants.IMPORT_SCHEDULE_KEPT__ID))
					: List.of();
			} finally {
				_deferred = false;
			}
			resolve(project);
			return new Imported(rules, schedules, datapoints);
		} catch (TopLogicException ex) {
			throw ex;
		} catch (RuntimeException ex) {
			throw new TopLogicException(I18NConstants.ERROR_RULE_IMPORT_UNREADABLE__REASON.fill(String.valueOf(ex.getMessage())),
				ex);
		}
	}

	/**
	 * The result of {@link RuleImport#importImage(TLObject, String)}: the rules, schedule entries and
	 * application datapoints created.
	 */
	public record Imported(List<TLObject> rules, List<TLObject> schedules, List<TLObject> datapoints) {
		// A record.
	}

	// ---- datapoints --------------------------------------------------------------------------

	/**
	 * Adds the application datapoints of the given datapoints of an image (the member
	 * <code>datapoints</code>) behind the application datapoints of the project, as
	 * {@link ApplicationDatapoints#json(TLObject)} writes them: the id, the label, the value type, the
	 * unit, the flags writable and persist, the history. What the image derives (<code>transmit</code>,
	 * <code>readOnStart</code>) is not stored.
	 *
	 * <ul>
	 * <li>A datapoint whose id the project has already is kept as it is (a note says so); the file
	 * listing an id twice is refused.</li>
	 * <li>A datapoint of a KNX group address (member <code>knx</code>) is not created: the project's
	 * group addresses are these datapoints (a note gives their number).</li>
	 * <li>A datapoint with a <code>zigbee</code> member is bound to the function of the project's Zigbee
	 * device of that IEEE address with that endpoint, cluster and attribute (or the client cluster
	 * without an attribute, <code>action</code>), with the binding's <code>reporting</code> as the
	 * function's override where it has none. Without the device or the function, or with the function
	 * bound to another datapoint, the datapoint is created unbound (a note says why).</li>
	 * <li>An id the project's checks refuse ({@link ApplicationDatapoints#idProblem(Object, Object)}), a
	 * value type E-Home does not know or a function whose value type does not fit refuses the
	 * import.</li>
	 * </ul>
	 *
	 * @return The datapoints created.
	 */
	public List<TLObject> importDatapoints(TLObject project, List<?> datapoints) {
		List<TLObject> all = ApplicationDatapoints.of(project);
		Map<String, TLObject> known = new HashMap<>();
		for (TLObject datapoint : all) {
			String id = ApplicationDatapoints.id(datapoint);
			if (id != null) {
				known.putIfAbsent(id, datapoint);
			}
		}
		Set<String> listed = new HashSet<>();
		List<TLObject> created = new ArrayList<>();
		List<Map<?, ?>> sources = new ArrayList<>();
		int knx = 0;
		int index = 0;
		for (Object item : datapoints) {
			Map<?, ?> json = (Map<?, ?>) item;
			if (json.containsKey("knx")) {
				knx++;
				index++;
				continue;
			}
			if (!(json.get("id") instanceof String id) || id.isBlank()) {
				throw new TopLogicException(I18NConstants.ERROR_IMPORT_DATAPOINT_NO_ID__INDEX.fill(Integer.valueOf(index)));
			}
			index++;
			if (!listed.add(id)) {
				throw new TopLogicException(I18NConstants.ERROR_IMPORT_DATAPOINT_TWICE__ID.fill(id));
			}
			if (known.containsKey(id)) {
				note(I18NConstants.IMPORT_DATAPOINT_KEPT__ID.fill(DatapointNames.name(known.get(id))));
				continue;
			}
			created.add(datapoint(id, json));
			sources.add(json);
		}
		all.addAll(created);
		((Project) project).setApplicationDatapoints(typed(all));
		for (int n = 0; n < created.size(); n++) {
			TLObject datapoint = created.get(n);
			String id = ApplicationDatapoints.id(datapoint);
			ResKey problem = ApplicationDatapoints.idProblem(id, datapoint);
			if (problem != null) {
				throw new TopLogicException(I18NConstants.ERROR_IMPORT_DATAPOINT_ID__ID_REASON.fill(id, problem));
			}
			if (sources.get(n).get("zigbee") instanceof Map<?, ?> zigbee) {
				bind(project, datapoint, id, zigbee);
			}
		}
		if (knx > 0) {
			note(I18NConstants.IMPORT_KNX_DATAPOINTS_SKIPPED__COUNT.fill(Integer.valueOf(knx)));
		}
		return created;
	}

	private ApplicationDatapoint datapoint(String id, Map<?, ?> json) {
		ApplicationDatapoint result = create(ApplicationDatapoints.TYPE);
		result.setIdentifier(id);
		if (json.get("label") != null) {
			result.setLabel(label(json.get("label"), "datapoint " + id));
		}
		Object typeName = json.get("type") == null ? ValueTypes.BOOLEAN : json.get("type");
		TLClassifier type = classifier("DatapointValueType", typeName);
		if (type == null) {
			throw new TopLogicException(I18NConstants.ERROR_IMPORT_DATAPOINT_TYPE__ID_TYPE.fill(id, typeName));
		}
		result.setType(type);
		if (json.get("unit") instanceof String unit && !unit.isBlank()) {
			result.setUnit(unit);
		}
		if (Boolean.TRUE.equals(json.get("writable"))) {
			result.setWritable(true);
		}
		if (Boolean.TRUE.equals(json.get("persist"))) {
			result.setPersist(true);
		}
		if (json.get("history") instanceof Map<?, ?> history) {
			// The image states the history: it becomes the datapoint's own, set by hand (haumacher/ehome #73).
			result.setHistoryManual(true);
			if (history.get("retention") instanceof Number retention) {
				result.setHistoryRetention(Integer.valueOf(retention.intValue()));
			}
			if (history.get("spacing") instanceof Number spacing) {
				result.setHistorySpacing(Integer.valueOf(spacing.intValue()));
			}
			if (history.get("archives") instanceof List<?> archives) {
				List<HistoryArchive> parts = new ArrayList<>();
				for (Object entry : archives) {
					if (entry instanceof Map<?, ?> archive && archive.get("resolution") instanceof Number resolution
						&& archive.get("retention") instanceof Number archiveRetention) {
						HistoryArchive part = (HistoryArchive) ModelService.getInstance().getFactory()
							.createObject((TLClass) TLModelUtil.findType("ehome.logical:HistoryArchive"));
						part.setResolution(resolution.intValue());
						part.setRetention(archiveRetention.intValue());
						parts.add(part);
					}
				}
				result.setHistoryArchives(parts);
			}
		}
		notes(json, "datapoint " + id);
		return result;
	}

	private void bind(TLObject project, TLObject datapoint, String id, Map<?, ?> zigbee) {
		String ieee = String.valueOf(zigbee.get("ieee"));
		ZigbeeDevice device = null;
		for (TLObject candidate : ZigbeeImport.devices(project)) {
			if (ieee.equalsIgnoreCase(String.valueOf(((ZigbeeDevice) candidate).getIeee()))) {
				device = (ZigbeeDevice) candidate;
				break;
			}
		}
		if (device == null) {
			note(I18NConstants.IMPORT_ZIGBEE_NO_DEVICE__ID_IEEE.fill(id, ieee));
			return;
		}
		Object attribute = zigbee.get("attribute");
		ZigbeeFunction function = null;
		for (TLObject object : RuleExport.objects(device.getFunctions())) {
			ZigbeeFunction candidate = (ZigbeeFunction) object;
			if (same(Integer.valueOf(candidate.getEndpoint()), zigbee.get("endpoint"))
				&& same(candidate.getCluster(), zigbee.get("cluster"))
				&& (attribute == null ? candidate.getAttribute() == null
					: same(candidate.getAttribute(), attribute))) {
				function = candidate;
				break;
			}
		}
		if (function == null) {
			note(I18NConstants.IMPORT_ZIGBEE_NO_FUNCTION__ID_IEEE_ENDPOINT_CLUSTER_ATTRIBUTE.fill(id, ieee,
				zigbee.get("endpoint"), zigbee.get("cluster"), attribute == null ? "-" : attribute));
			return;
		}
		List<TLObject> bound = ZigbeeBindings.boundDatapoints(function);
		if (!bound.isEmpty()) {
			note(I18NConstants.IMPORT_ZIGBEE_BOUND__ID_OTHER.fill(DatapointNames.name(datapoint),
				DatapointNames.name(bound.get(0))));
			return;
		}
		ZigbeeBindings.bind(function, datapoint);
		if (zigbee.get("reporting") instanceof Map<?, ?> reporting) {
			Map<String, Object> current = ZigbeeReporting.json(function);
			if (current == null) {
				if (reporting.get("min") instanceof Number min) {
					function.setReportingMin(Integer.valueOf(min.intValue()));
				}
				if (reporting.get("max") instanceof Number max) {
					function.setReportingMax(Integer.valueOf(max.intValue()));
				}
				if (reporting.get("change") instanceof Number change) {
					function.setReportingChange(Double.valueOf(change.doubleValue()));
				}
			} else if (!sameJson(current, reporting)) {
				note(I18NConstants.IMPORT_REPORTING_KEPT__ID_PROJECT_FILE.fill(DatapointNames.name(datapoint), Json.write(current).trim(),
					Json.write(reporting).trim()));
			}
		}
	}

	// ---- settings ----------------------------------------------------------------------------

	/**
	 * A value of the project for a message: a number as it is, so that the message formats it in
	 * the user's locale like the values of the file, "-" for none.
	 */
	private static Object shown(Object value) {
		return value == null ? "-" : value;
	}

	/**
	 * Gives the project the location (<code>location</code>: <code>lat</code>, <code>lon</code>,
	 * <code>elev</code>) and the time zone (<code>tz</code>) of the given settings of an image, each
	 * where the project has none; a project's own location or time zone that differs is kept. Both are
	 * named in the {@link #getNotes() notes}.
	 */
	public void importSettings(TLObject projectObject, Object settings) {
		if (!(settings instanceof Map<?, ?> map)) {
			return;
		}
		Project project = (Project) projectObject;
		if (map.get("location") instanceof Map<?, ?> location && location.get("lat") instanceof Number lat
			&& location.get("lon") instanceof Number lon) {
			Object projectLat = project.getLatitude();
			Object projectLon = project.getLongitude();
			if (projectLat == null && projectLon == null) {
				project.setLatitude(Double.valueOf(lat.doubleValue()));
				project.setLongitude(Double.valueOf(lon.doubleValue()));
				if (location.get("elev") instanceof Number elev && project.getElevation() == null) {
					project.setElevation(Integer.valueOf(elev.intValue()));
				}
				note(I18NConstants.IMPORT_LOCATION_SET__LAT_LON.fill(lat, lon));
			} else if (!same(projectLat, lat) || !same(projectLon, lon)) {
				note(I18NConstants.IMPORT_LOCATION_KEPT__LAT_LON_FILE_LAT_FILE_LON.fill(shown(projectLat), shown(projectLon),
					lat, lon));
			}
		}
		if (map.get("tz") instanceof String tz && !tz.isBlank()) {
			Object own = project.getTimeZone();
			if (own == null || own.toString().isBlank()) {
				project.setTimeZone(tz);

				note(I18NConstants.IMPORT_TIME_ZONE_SET__TZ.fill(tz));
			} else if (!own.toString().trim().equals(tz.trim())) {
				note(I18NConstants.IMPORT_TIME_ZONE_KEPT__TZ_FILE_TZ.fill(own, tz));
			}
		}
	}

	/**
	 * The elements of the list whose id the project has none of in the given reference yet; the others
	 * are named in the notes, so that importing an image again adds nothing twice.
	 */
	private List<Object> unknown(Set<String> known, List<?> list, ResKey1 kept) {
		List<Object> result = new ArrayList<>();
		for (Object item : list) {
			if (item instanceof Map<?, ?> map && map.get("id") instanceof String id && known.contains(id)) {
				note(kept.fill(id));
			} else {
				result.add(item);
			}
		}
		return result;
	}

	/** The ids of the given objects of a project (its rules, its schedule entries). */
	private static <T extends TLObject> Set<String> ids(Collection<? extends T> objects, Function<T, String> id) {
		Set<String> result = new HashSet<>();
		for (TLObject obj : RuleExport.objects(objects)) {
			@SuppressWarnings("unchecked")
			String value = id.apply((T) obj);
			if (value != null) {
				result.add(value);
			}
		}
		return result;
	}

	private void note(ResKey key) {
		_notes.add(Resources.getInstance().getString(key));
	}

	private static boolean same(Object a, Object b) {
		return a instanceof Number x && b instanceof Number y ? x.doubleValue() == y.doubleValue()
			: a == null ? b == null : a.equals(b);
	}

	private static boolean sameJson(Map<?, ?> a, Map<?, ?> b) {
		if (!a.keySet().equals(b.keySet())) {
			return false;
		}
		for (Object key : a.keySet()) {
			if (!same(a.get(key), b.get(key))) {
				return false;
			}
		}
		return true;
	}

	// ---- rules -------------------------------------------------------------------------------

	private Rule rule(Map<?, ?> json) {
		Rule rule = create("Rule");
		_rule = rule;
		// Its state datapoint at once: the references of the image may name it (rule/<id>/state).
		StateDatapoints.ensure(rule);
		rule.setIdentifier((String) json.get("id"));
		rule.setNameKey(label(json.get("name"), "rule " + json.get("id")));
		rule.setDescription((String) json.get("description"));
		rule.setEnabled(!Boolean.FALSE.equals(json.get("enabled")));
		rule.setEnabledAdjustable(!Boolean.FALSE.equals(json.get("enabledAdjustable")));
		notes(json, "rule " + json.get("id"), "description");

		List<RuleParameter> parameters = new ArrayList<>();
		for (Object item : list(json.get("parameters"))) {
			parameters.add(parameter((Map<?, ?>) item));
		}
		rule.setParameters(parameters);

		List<RuleTimer> timers = new ArrayList<>();
		for (Object item : list(json.get("timers"))) {
			Map<?, ?> timer = (Map<?, ?>) item;
			RuleTimer result = create("RuleTimer");
			result.setIdentifier((String) timer.get("id"));
			result.setDuration(text(timer.get("duration")));
			notes(timer, "timer " + timer.get("id"));
			timers.add(result);
		}
		rule.setTimers(timers);

		List<RuleState> states = new ArrayList<>();
		for (Object item : list(json.get("states"))) {
			Map<?, ?> state = (Map<?, ?>) item;
			RuleState result = create("RuleState");
			result.setIdentifier((String) state.get("id"));
			if (state.get("label") != null) {
				result.setLabel(label(state.get("label"), "state " + state.get("id")));
			}
			result.setEntry(actions(state.get("entry")));
			result.setExit(actions(state.get("exit")));
			notes(state, "state " + state.get("id"));
			states.add(result);
		}
		rule.setStates(states);
		rule.setInitialState(state(states, json.get("initialState")));
		if (json.get("timeline") instanceof Map<?, ?> timeline) {
			rule.setTimeline(timeline(timeline, states));
		}

		List<Reaction> reactions = new ArrayList<>();
		for (Object item : list(json.get("reactions"))) {
			reactions.add(reaction((Map<?, ?>) item, states));
		}
		rule.setReactions(reactions);
		return rule;
	}


	/**
	 * The timeline of a rule with states (homecontrol <code>spec/rules.md</code> 2.8, haumacher/ehome
	 * #140): its plans with their points and its assignments, the states and plans by their ids.
	 */
	private RuleTimeline timeline(Map<?, ?> json, List<RuleState> states) {
		RuleTimeline result = create("RuleTimeline");
		result.setAdjustable(!Boolean.FALSE.equals(json.get("adjustable")));
		List<TimelinePlan> plans = new ArrayList<>();
		for (Object item : list(json.get("plans"))) {
			Map<?, ?> plan = (Map<?, ?>) item;
			TimelinePlan created = create("TimelinePlan");
			created.setIdentifier((String) plan.get("id"));
			created.setLabel(label(plan.get("label"), "plan " + plan.get("id")));
			List<TimelinePoint> points = new ArrayList<>();
			for (Object element : list(plan.get("points"))) {
				Map<?, ?> point = (Map<?, ?>) element;
				TimelinePoint p = create("TimelinePoint");
				if (point.get("time") != null) {
					p.setTime(RuleText.printValue(point.get("time"), Type.TIME_OF_DAY));
				}
				if (point.get("sun") != null) {
					p.setSun(classifier("SunEvent", point.get("sun")));
				}
				if (point.get("offset") != null) {
					p.setOffset(RuleText.printValue(point.get("offset"), Type.DURATION));
				}
				if (point.get("notBefore") != null) {
					p.setNotBefore(RuleText.printValue(point.get("notBefore"), Type.TIME_OF_DAY));
				}
				if (point.get("notAfter") != null) {
					p.setNotAfter(RuleText.printValue(point.get("notAfter"), Type.TIME_OF_DAY));
				}
				RuleState state = state(states, point.get("state"));
				if (state == null) {
					_notes.add("unknown state " + point.get("state") + " in a point of plan " + plan.get("id"));
				}
				p.setState(state);
				points.add(p);
			}
			created.setPoints(points);
			plans.add(created);
		}
		result.setPlans(plans);
		List<TimelineAssignment> assignments = new ArrayList<>();
		for (Object item : list(json.get("assignments"))) {
			Map<?, ?> assignment = (Map<?, ?>) item;
			TimelineAssignment created = create("TimelineAssignment");
			TimelinePlan plan = null;
			for (TimelinePlan candidate : plans) {
				if (java.util.Objects.equals(candidate.getIdentifier(), assignment.get("plan"))) {
					plan = candidate;
				}
			}
			if (plan == null) {
				_notes.add("unknown plan " + assignment.get("plan") + " in an assignment");
			}
			created.setPlan(plan);
			List<TLClassifier> days = new ArrayList<>();
			for (Object day : list(assignment.get("days"))) {
				if (!(day instanceof Number n) || n.doubleValue() != n.intValue() || n.intValue() < 1 || n.intValue() > 7) {
					// No weekday the model holds: refused, not dropped.
					throw new TopLogicException(I18NConstants.ERROR_RULE_IMPORT_UNREADABLE__REASON
						.fill("weekday " + day + " of an assignment is not 1..7"));
				}
				days.add(classifier("Weekday", RuleExport.weekday(n.intValue())));
			}
			created.setDays(new LinkedHashSet<>(days));
			assignments.add(created);
		}
		result.setAssignments(assignments);
		return result;
	}

	private RuleParameter parameter(Map<?, ?> json) {
		RuleParameter result = create("RuleParameter");
		result.setIdentifier((String) json.get("id"));
		result.setTitle(label(json.get("label"), "parameter " + json.get("id")));
		String typeName = String.valueOf(json.get("type"));
		Type type = Type.fromName(typeName);
		result.setType(classifier("RuleParameterType", typeName));
		if (type != null) {
			if (json.containsKey("default")) {
				result.setDefault(RuleText.printValue(json.get("default"), type));
			}
			if (json.containsKey("min")) {
				result.setMin(RuleText.printValue(json.get("min"), type));
			}
			if (json.containsKey("max")) {
				result.setMax(RuleText.printValue(json.get("max"), type));
			}
			if (json.get("step") instanceof Number step) {
				Type stepType = type == Type.DURATION ? Type.DURATION
					: step.doubleValue() == Math.rint(step.doubleValue()) && !(step instanceof Double) ? Type.INTEGER
						: Type.REAL;
				result.setStep(RuleText.printValue(step, stepType));
			}
			List<RuleChoice> choices = new ArrayList<>();
			for (Object item : list(json.get("choices"))) {
				Map<?, ?> choice = (Map<?, ?>) item;
				RuleChoice created = create("RuleChoice");
				created.setValue(RuleText.printValue(choice.get("value"), type));
				created.setTitle(label(choice.get("label"), "choice of parameter " + json.get("id")));
				choices.add(created);
			}
			result.setChoices(choices);
		}
		result.setUnit((String) json.get("unit"));
		result.setAdjustable(!Boolean.FALSE.equals(json.get("adjustable")));
		notes(json, "parameter " + json.get("id"));
		return result;
	}

	private Reaction reaction(Map<?, ?> json, List<RuleState> states) {
		Reaction result = create("Reaction");
		List<Trigger> triggers = new ArrayList<>();
		for (Object item : list(json.get("triggers"))) {
			triggers.add(trigger((Map<?, ?>) item));
		}
		result.setTriggers(triggers);
		result.setCondition(text(json.get("condition")));
		result.setActions(actions(json.get("actions")));
		result.setElseActions(actions(json.get("elseActions")));
		Set<RuleState> from = new LinkedHashSet<>();
		for (Object id : list(json.get("from"))) {
			RuleState state = state(states, id);
			if (state != null) {
				from.add(state);
			} else {
				_notes.add("unknown state " + id + " in from");
			}
		}
		result.setFrom(from);
		result.setTo(state(states, json.get("to")));
		notes(json, "reaction");
		return result;
	}

	private Trigger trigger(Map<?, ?> json) {
		String kind = String.valueOf(json.get("kind"));
		Trigger result;
		switch (kind) {
			case "datapoint": {
				DatapointTrigger trigger = create("DatapointTrigger");
				reference(trigger, "datapoint", json.get("datapoint"),
					target -> trigger.setDatapoint((ProjectDatapoint) target));
				trigger.setOn(classifier("UpdateKind", json.get("on") == null ? "change" : json.get("on")));
				trigger.setOrigin(classifier("UpdateOrigin", json.get("origin") == null ? "any" : json.get("origin")));
				result = trigger;
				break;
			}
			case "match": {
				MatchTrigger trigger = create("MatchTrigger");
				trigger.setCondition(text(json.get("condition")));
				result = trigger;
				break;
			}
			case "schedule": {
				ScheduleTrigger trigger = create("ScheduleTrigger");
				reference(trigger, "schedule", json.get("schedule"),
					target -> trigger.setSchedule((ScheduleEntry) target));
				result = trigger;
				break;
			}
			case "timer": {
				TimerTrigger trigger = create("TimerTrigger");
				reference(trigger, "timer", json.get("timer"), target -> trigger.setTimer((RuleTimer) target));
				result = trigger;
				break;
			}
			case "parameter": {
				ParameterTrigger trigger = create("ParameterTrigger");
				reference(trigger, "parameter", json.get("parameter"),
					target -> trigger.setParameter((RuleParameter) target));
				result = trigger;
				break;
			}
			case "silence": {
				SilenceTrigger trigger = create("SilenceTrigger");
				reference(trigger, "datapoint", json.get("datapoint"),
					target -> trigger.setDatapoint((ProjectDatapoint) target));
				trigger.setAfter(text(json.get("after")));
				result = trigger;
				break;
			}
			case "start": {
				StartTrigger trigger = create("StartTrigger");
				Object reasons = json.get("reasons");
				Collection<?> given = reasons instanceof Collection<?> c ? c : List.of("boot", "image", "enable");
				trigger.setOnBoot(given.contains("boot"));
				trigger.setOnImage(given.contains("image"));
				trigger.setOnEnable(given.contains("enable"));
				result = trigger;
				break;
			}
			default:
				throw new IllegalArgumentException("Unknown trigger kind '" + kind + "'.");
		}
		notes(json, "trigger " + kind);
		return result;
	}

	private List<Action> actions(Object json) {
		List<Action> result = new ArrayList<>();
		for (Object item : list(json)) {
			result.add(action((Map<?, ?>) item));
		}
		return result;
	}

	private Action action(Map<?, ?> json) {
		String kind = String.valueOf(json.get("kind"));
		Action result;
		switch (kind) {
			case "write": {
				WriteAction action = create("WriteAction");
				reference(action, "datapoint", json.get("datapoint"),
					target -> action.setDatapoint((ProjectDatapoint) target));
				action.setValue(text(json.get("value")));
				action.setOnlyOnChange(Boolean.TRUE.equals(json.get("onlyOnChange")));
				result = action;
				break;
			}
			case "startTimer": {
				StartTimerAction action = create("StartTimerAction");
				reference(action, "timer", json.get("timer"), target -> action.setTimer((RuleTimer) target));
				action.setDuration(text(json.get("duration")));
				result = action;
				break;
			}
			case "restartTimer": {
				RestartTimerAction action = create("RestartTimerAction");
				reference(action, "timer", json.get("timer"), target -> action.setTimer((RuleTimer) target));
				action.setDuration(text(json.get("duration")));
				result = action;
				break;
			}
			case "cancelTimer": {
				CancelTimerAction action = create("CancelTimerAction");
				reference(action, "timer", json.get("timer"), target -> action.setTimer((RuleTimer) target));
				result = action;
				break;
			}
			case "read": {
				ReadAction action = create("ReadAction");
				reference(action, "datapoint", json.get("datapoint"),
					target -> action.setDatapoint((ProjectDatapoint) target));
				result = action;
				break;
			}
			case "setParameter": {
				SetParameterAction action = create("SetParameterAction");
				reference(action, "parameter", json.get("parameter"),
					target -> action.setParameter((RuleParameter) target));
				action.setValue(text(json.get("value")));
				result = action;
				break;
			}
			case "log": {
				LogAction action = create("LogAction");
				action.setLevel(classifier("LogLevel", json.get("level")));
				List<Object> message = new ArrayList<>();
				for (Object part : list(json.get("message"))) {
					message.add(RuleText.normalize(part));
				}
				action.setMessage(RuleText.printMessage(message));
				result = action;
				break;
			}

			default:
				throw new IllegalArgumentException("Unknown action kind '" + kind + "'.");
		}
		notes(json, "action " + kind);
		return result;
	}

	// ---- schedules ---------------------------------------------------------------------------

	private ScheduleEntry schedule(Map<?, ?> json) {
		String kind = String.valueOf(json.get("kind"));
		String type = switch (kind) {
			case "weekly" -> "WeeklySchedule";
			case "yearly" -> "YearlySchedule";
			case "dates" -> "DatesSchedule";
			case "astro" -> "AstroSchedule";
			case "interval" -> "IntervalSchedule";
			case "once" -> "OnceSchedule";
			default -> throw new IllegalArgumentException("Unknown schedule kind '" + kind + "'.");
		};
		ScheduleEntry result = create(type);
		result.setIdentifier((String) json.get("id"));
		result.setLabel(label(json.get("label"), "schedule entry " + json.get("id")));
		result.setEnabled(!Boolean.FALSE.equals(json.get("enabled")));
		result.setEnabledAdjustable(!Boolean.FALSE.equals(json.get("enabledAdjustable")));
		result.setMissed(classifier("MissedFiring", json.get("missed") == null ? "skip" : json.get("missed")));
		_rule = null;
		reference(result, "output", json.get("output"), target -> result.setOutput((ApplicationDatapoint) target));
		if (json.containsKey("days")) {
			// The schedules with weekdays (weekly, astro, interval); the generic reading of the
			// time members follows.
			List<TLClassifier> days = new ArrayList<>();
			for (Object day : list(json.get("days"))) {
				days.add(classifier("Weekday", RuleExport.weekday(((Number) day).intValue())));
			}
			Set<TLClassifier> daySet = new LinkedHashSet<>(days);
			if (result instanceof WeeklySchedule weekly) {
				weekly.setDays(daySet);
			} else if (result instanceof AstroSchedule astro) {
				astro.setDays(daySet);
			} else if (result instanceof IntervalSchedule interval) {
				interval.setDays(daySet);
			} else {
				throw new IllegalArgumentException("No weekdays in a schedule of kind '" + kind + "'.");
			}
		}
		String[][] values = { { "time", "timeOfDay" }, { "notBefore", "timeOfDay" }, { "notAfter", "timeOfDay" },
			{ "from", "timeOfDay" }, { "until", "timeOfDay" }, { "every", "duration" }, { "offset", "duration" },
			{ "at", "dateTime" } };
		for (String[] value : values) {
			if (json.containsKey(value[0])) {
				result.tUpdateByName(value[0], RuleText.printValue(json.get(value[0]), Type.fromName(value[1])));
			}
		}
		if (json.get("month") instanceof Number month) {
			((YearlySchedule) result).setMonth(Integer.valueOf(month.intValue()));
		}
		if (json.get("day") instanceof Number day) {
			((YearlySchedule) result).setDay(Integer.valueOf(day.intValue()));
		}
		if (json.containsKey("sun")) {
			((AstroSchedule) result).setSun(classifier("SunEvent", json.get("sun")));
		}
		if (json.containsKey("dates")) {
			List<String> dates = new ArrayList<>();
			for (Object date : list(json.get("dates"))) {
				dates.add(RuleText.printValue(date, Type.DATE));
			}
			((DatesSchedule) result).setDates(String.join(", ", dates));
		}
		// The adjustable members and their bounds (homecontrol #58).
		for (Object member : list(json.get("adjustable"))) {
			if (result.tType().getPart(member + "Adjustable") != null) {
				result.tUpdateByName(member + "Adjustable", Boolean.TRUE);
			}
		}
		if (json.get("bounds") instanceof Map<?, ?> bounds) {
			for (Map.Entry<?, ?> entry : bounds.entrySet()) {
				String member = String.valueOf(entry.getKey());
				if (!(entry.getValue() instanceof Map<?, ?> bound) || result.tType().getPart(member + "Min") == null) {
					continue;
				}
				Type valueType = member.equals("at") ? Type.DATE_TIME
					: member.equals("offset") || member.equals("every") ? Type.DURATION : Type.TIME_OF_DAY;
				for (String part : new String[] { "min", "max" }) {
					if (bound.containsKey(part)) {
						result.tUpdateByName(member + (part.equals("min") ? "Min" : "Max"),
							RuleText.printValue(bound.get(part), valueType));
					}
				}
				if (bound.get("step") instanceof Number step && result.tType().getPart(member + "Step") != null) {
					// A step of a time is in seconds, of a duration in milliseconds.
					long ms = valueType == Type.TIME_OF_DAY ? step.longValue() * 1000 : step.longValue();
					result.tUpdateByName(member + "Step", RuleText.printValue(Long.valueOf(ms), Type.DURATION));
				}
			}
		}
		return result;
	}

	// ---- references --------------------------------------------------------------------------

	/** Reads a reference of a part of the current rule by the id of the image. */
	private void reference(TLObject obj, String attribute, Object id, Consumer<TLObject> setter) {
		if (id != null && !id.toString().isBlank()) {
			_pending.add(new Pending(setter, attribute, id.toString(), _rule));
		}
	}

	private void resolveUnlessDeferred(TLObject project) {
		if (!_deferred) {
			resolve(project);
		}
	}

	/**
	 * Sets the references read to the objects of the project their ids name (haumacher/ehome #90): a
	 * datapoint, a schedule entry of the project, a timer or parameter of the rule.
	 *
	 * @throws TopLogicException
	 *         If an id names nothing of the project: the image does not belong to it. The message
	 *         names every such id.
	 */
	private void resolve(TLObject project) {
		Map<String, TLObject> datapoints = RuleReferences.datapoints(project);
		Set<String> unresolved = new LinkedHashSet<>();
		for (Pending pending : _pending) {
			TLObject target = RuleReferences.resolve(project, pending.rule(), pending.attribute(), pending.id(), datapoints);
			if (target == null) {
				unresolved.add(pending.id());
			} else {
				pending.setter().accept(target);
			}
		}
		_pending.clear();
		if (!unresolved.isEmpty()) {
			throw new TopLogicException(I18NConstants.ERROR_RULE_IMPORT_UNRESOLVED__IDS.fill(String.join(", ", unresolved)));
		}
	}

	// ---- helpers -----------------------------------------------------------------------------

	private void notes(Map<?, ?> json, String what, String... kept) {
		for (String member : new String[] { "description", "comment" }) {
			if (json.containsKey(member) && !List.of(kept).contains(member)) {
				_notes.add(what + ": the " + member + " is not kept");
			}
		}
	}

	/** The canonical text of an expression: its normalized tree printed (spec/rules.md 4.8). */
	private static String text(Object expression) {
		return expression == null ? null : RuleText.print(RuleText.normalize(expression));
	}

	/**
	 * A label of the image (spec/rules.md 2.2) as a multilingual text in the languages it has
	 * (haumacher/ehome #205): a plain string, which has no language, in the
	 * {@link FunctionProposal#userLanguage() user's language} only; a language map in each
	 * application language it gives a text for (by its exact tag). A map in none of the application languages gives
	 * the text the controller shows by default (en, the first entry) in the user's language. The
	 * texts of other languages are named in the {@link #getNotes() notes}; <code>null</code> stays
	 * empty.
	 */
	private ResKey label(Object json, String what) {
		if (json == null) {
			return null;
		}
		if (!(json instanceof Map<?, ?> map)) {
			return FunctionProposal.userText(String.valueOf(json));
		}
		Map<String, String> languages = new LinkedHashMap<>();
		for (Map.Entry<?, ?> entry : map.entrySet()) {
			languages.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
		}
		Map<String, String> texts = new TreeMap<>();
		List<String> application = new ArrayList<>();
		List<String> used = new ArrayList<>();
		for (Locale locale : ResourcesModule.getInstance().getSupportedLocales()) {
			String language = locale.getLanguage();
			application.add(language);
			String tag = tagOf(languages, language);
			if (tag != null) {
				texts.put(language, languages.get(tag));
				used.add(tag);
			}
		}
		if (texts.isEmpty() && !languages.isEmpty()) {
			String language = FunctionProposal.userLanguage();
			String text = RuleLoader.resolveLabel(languages, language);
			texts.put(language, text);
			_notes.add(what + ": the label has no text in the languages of E-Home (" + String.join(", ", application)
				+ "); its text \"" + text + "\" is kept in " + language);
			return FunctionProposal.text(texts);
		}
		List<String> dropped = new ArrayList<>(languages.keySet());
		dropped.removeAll(used);
		if (!dropped.isEmpty()) {
			_notes.add(what + ": the label's texts in " + String.join(", ", dropped) + " are not kept (the languages of E-Home: "
				+ String.join(", ", application) + ")");
		}
		return FunctionProposal.text(texts);
	}

	private static List<?> list(Object json) {
		return json instanceof List<?> list ? list : List.of();
	}

	private static RuleState state(List<RuleState> states, Object id) {
		for (RuleState state : states) {
			if (state.getIdentifier().equals(id)) {
				return state;
			}
		}
		return null;
	}

	/**
	 * The tag of a label's language map giving the text of the application language: the language
	 * itself (a regional tag such as <code>de-CH</code> is another language); <code>null</code> for
	 * none.
	 */
	private static String tagOf(Map<String, String> languages, String language) {
		for (String tag : languages.keySet()) {
			if (tag.equalsIgnoreCase(language)) {
				return tag;
			}
		}
		return null;
	}

	static TLClassifier classifier(String enumeration, Object name) {
		return ((TLEnumeration) TLModelUtil.findType("ehome.runtime:" + enumeration)).getClassifier(String.valueOf(name));
	}

	/**
	 * A new object of the given type of the module <code>ehome.runtime</code>, as the interface of that
	 * type.
	 */
	@SuppressWarnings("unchecked")
	static <T extends TLObject> T create(String type) {
		return (T) ModelService.getInstance().getFactory()
			.createObject((TLClass) TLModelUtil.findType("ehome.runtime:" + type));
	}

	/** The objects as the model type the caller knows they have. */
	@SuppressWarnings("unchecked")
	static <T extends TLObject> List<T> typed(List<? extends TLObject> objects) {
		return (List<T>) new ArrayList<>(objects);
	}


}
