/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.runtime;

import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.top_logic.basic.util.ResKey;
import com.top_logic.model.TLObject;
import com.top_logic.util.Resources;

import de.haumacher.ehome.runtime.ControllerClient.Answer;
import de.haumacher.ehome.rules.Schedule;
import de.haumacher.ehome.runtime.Json.JsonException;

/**
 * What differs between the image a controller stores (<code>GET /api/image</code>, byte for byte as
 * it was deployed) and the image E-Home is about to deploy to it (haumacher/ehome #47).
 *
 * <p>
 * The image to deploy carries the next revision and the time of now, so its bytes always differ from
 * the stored ones. Whether the project changed since is therefore decided on the image of the project
 * as it would have been written with the stored image's revision and time: when its bytes are the
 * stored bytes, there is nothing to deploy. Otherwise the comparison names the stored image's project
 * and revision, the numbers of datapoints, locations and functions of both, the parts of the image
 * (its top level members) that differ, and the rules and schedules of the stored image, which E-Home
 * does not author and a deployment removes, and the inhabitant's schedule values the new image would
 * drop (homecontrol #58). The confirmation of a deployment warns when the controller reads an older
 * image schema than the image's.
 * </p>
 */
public final class ImageComparison {

	/**
	 * How the stored image relates to the image to deploy.
	 */
	public enum State {
		/** The controller could not be asked or did not answer with an image. */
		UNKNOWN,

		/** The controller stores no image. */
		NONE,

		/** The controller stores the image of the project as it is now. */
		IDENTICAL,

		/** The controller stores another image: of another state of the project, or of another project. */
		DIFFERENT;
	}

	/**
	 * The outcome of a comparison.
	 *
	 * @param state
	 *        How the images relate.
	 * @param revision
	 *        The revision of the stored image, <code>null</code> if unknown.
	 * @param text
	 *        What differs, for the user, one sentence per line.
	 */
	public record Result(State state, Long revision, String text) {
		// A record.
	}

	/** The members of the image that are no part of its content. */
	private static final String HEADER = "image";

	private ImageComparison() {
		// Static helpers.
	}

	/**
	 * Compares the image the controller of the project stores with the image of the project as it
	 * would be deployed now.
	 *
	 * @param project
	 *        The project, with a controller ({@link Deployer#controller(TLObject)}).
	 */
	public static Result compare(TLObject project, Instant now) {
		ControllerClient client = Deployer.client(Deployer.controller(project));
		RuntimeImage image = RuntimeImage.build(project, Deployer.nextRevision(project), now);
		Answer answer;
		try {
			answer = client.getImage();
		} catch (IOException ex) {
			return new Result(State.UNKNOWN, null,
				text(I18NConstants.COMPARE_UNREACHABLE__ADDRESS_ERROR.fill(client.address(), Deployer.reason(ex))));
		}
		if (answer.status() == 404 && "image.none".equals(answer.string("code"))) {
			return new Result(State.NONE, null, text(I18NConstants.COMPARE_NO_IMAGE) + "\n" + counts(image.getJson(), false));
		}
		if (!answer.ok()) {
			String error = answer.string("error");
			return new Result(State.UNKNOWN, null, text(I18NConstants.COMPARE_FAILED__STATUS_ERROR
				.fill(Integer.valueOf(answer.status()), error != null ? error : answer.text().strip())));
		}
		Map<?, ?> stored;
		try {
			stored = Json.parse(answer.text(), ImageValidator.MAX_NESTING) instanceof Map<?, ?> map ? map : null;
		} catch (JsonException ex) {
			stored = null;
		}
		Map<?, ?> header = stored != null && stored.get(HEADER) instanceof Map<?, ?> map ? map : null;
		if (header == null) {
			return new Result(State.UNKNOWN, null, text(I18NConstants.COMPARE_UNREADABLE));
		}
		String projectId = Objects.toString(header.get("projectId"), "");
		Long revision = header.get("revision") instanceof Number number ? Long.valueOf(number.longValue()) : null;
		Instant generatedAt = instant(header.get("generatedAt"));
		boolean sameProject = projectId.equals(image.getJson().get(HEADER) instanceof Map<?, ?> own
			? own.get("projectId") : null);

		if (sameProject && revision != null && generatedAt != null
			&& RuntimeImage.build(project, revision.longValue(), generatedAt).getText().equals(answer.text())) {
			return new Result(State.IDENTICAL, revision,
				text(I18NConstants.COMPARE_IDENTICAL__REVISION.fill(revision)));
		}

		List<String> lines = new ArrayList<>();
		if (sameProject) {
			lines.add(text(I18NConstants.COMPARE_REVISION__STORED_NEXT.fill(revision == null ? "?" : revision,
				Long.valueOf(Deployer.nextRevision(project)))));
		} else {
			lines.add(text(I18NConstants.COMPARE_OTHER_PROJECT__PROJECT_NAME_REVISION.fill(projectId,
				Objects.toString(header.get("name"), ""), revision == null ? "?" : revision)));
		}
		lines.add(counts(stored, true));
		lines.add(counts(image.getJson(), false));
		Set<String> changed = changedParts(stored, image.getJson());
		if (!changed.isEmpty()) {
			lines.add(text(I18NConstants.COMPARE_CHANGED_PARTS__PARTS.fill(String.join(", ", changed))));
		}
		int rules = missing(stored.get("rules"), image.getJson().get("rules"));
		int schedules = missing(stored.get("schedules"), image.getJson().get("schedules"));
		if (rules + schedules > 0) {
			lines.add(text(I18NConstants.COMPARE_RULES_REMOVED__RULES_SCHEDULES.fill(Integer.valueOf(rules),
				Integer.valueOf(schedules))));
		}
		lines.addAll(droppedOverrides(stored.get("schedules"), image.getJson().get("schedules")));
		lines.addAll(droppedTimelines(stored.get("rules"), image.getJson().get("rules")));
		return new Result(State.DIFFERENT, revision, String.join("\n", lines));
	}

	/**
	 * What the inhabitant may have set on the controller that the image to deploy would drop
	 * (homecontrol #58, <code>spec/schedules.md</code> 4.2): for each entry of the stored image that the
	 * new image keeps, the adjustable members the new image no longer declares adjustable, and those
	 * whose bounds or step no longer allow every value the stored image allowed. E-Home does not know
	 * which values the inhabitant set, so it names what <i>would</i> be dropped. Entries the new image
	 * no longer has are counted with the removed ones.
	 */
	public static List<String> droppedOverrides(Object stored, Object next) {
		List<String> result = new ArrayList<>();
		if (!(stored instanceof Collection<?> entries) || !(next instanceof Collection<?> nextEntries)) {
			return result;
		}
		Map<Object, Map<?, ?>> byId = new HashMap<>();
		for (Object entry : nextEntries) {
			if (entry instanceof Map<?, ?> map) {
				byId.putIfAbsent(map.get("id"), map);
			}
		}
		for (Object item : entries) {
			if (!(item instanceof Map<?, ?> before) || !(before.get("adjustable") instanceof Collection<?> adjustable)) {
				continue;
			}
			Map<?, ?> after = byId.get(before.get("id"));
			if (after == null) {
				continue;
			}
			boolean sameKind = Objects.equals(before.get("kind"), after.get("kind"));
			Collection<?> nowAdjustable = sameKind && after.get("adjustable") instanceof Collection<?> list ? list : List.of();
			for (Object name : adjustable) {
				Schedule.Member member = Schedule.Member.fromName(String.valueOf(name));
				if (member == null) {
					continue;
				}
				if (!nowAdjustable.contains(name)) {
					result.add(text(I18NConstants.COMPARE_OVERRIDE_NOT_ADJUSTABLE__ENTRY_MEMBER.fill(before.get("id"), name)));
				} else if (narrowed(member, bound(before, member), bound(after, member))) {
					result.add(text(I18NConstants.COMPARE_OVERRIDE_NARROWED__ENTRY_MEMBER.fill(before.get("id"), name)));
				}
			}
		}
		return result;
	}

	/**
	 * What the inhabitant may have set on the controller that the image to deploy would drop
	 * (homecontrol <code>spec/rules.md</code> 8.7, haumacher/ehome #140): E-Home cannot know the
	 * inhabitant's timeline, but an inhabitant's timeline is dropped when it names a state the new
	 * rule no longer has. For each rule of the stored image with an adjustable timeline that the new
	 * image keeps with a timeline, the states its last deployed timeline names that the new rule no
	 * longer has: a timeline of the inhabitant naming one of them would be dropped.
	 */
	public static List<String> droppedTimelines(Object stored, Object next) {
		List<String> result = new ArrayList<>();
		if (!(stored instanceof Collection<?> rules) || !(next instanceof Collection<?> nextRules)) {
			return result;
		}
		Map<Object, Map<?, ?>> byId = new HashMap<>();
		for (Object rule : nextRules) {
			if (rule instanceof Map<?, ?> map) {
				byId.putIfAbsent(map.get("id"), map);
			}
		}
		for (Object item : rules) {
			if (!(item instanceof Map<?, ?> before) || !(before.get("timeline") instanceof Map<?, ?> timeline)
				|| Boolean.FALSE.equals(timeline.get("adjustable"))) {
				continue;
			}
			Map<?, ?> after = byId.get(before.get("id"));
			if (after == null || !(after.get("timeline") instanceof Map<?, ?>)) {
				// The rule or its timeline is gone: the controller drops the inhabitant's timeline silently.
				continue;
			}
			Set<Object> states = new HashSet<>();
			if (after.get("states") instanceof Collection<?> list) {
				for (Object state : list) {
					if (state instanceof Map<?, ?> map) {
						states.add(map.get("id"));
					}
				}
			}
			Set<Object> gone = new LinkedHashSet<>();
			if (timeline.get("plans") instanceof Collection<?> plans) {
				for (Object plan : plans) {
					if (plan instanceof Map<?, ?> p && p.get("points") instanceof Collection<?> points) {
						for (Object point : points) {
							if (point instanceof Map<?, ?> q && q.get("state") != null && !states.contains(q.get("state"))) {
								gone.add(q.get("state"));
							}
						}
					}
				}
			}
			for (Object state : gone) {
				result.add(text(I18NConstants.COMPARE_TIMELINE_STATE_GONE__RULE_STATE.fill(before.get("id"), state)));
			}
		}
		return result;
	}

	/** The bounds of the member in an entry of an image, the member's whole range where it has none. */
	private static Schedule.Bound bound(Map<?, ?> entry, Schedule.Member member) {
		long min = member.rangeMin();
		long max = member.rangeMax();
		long step = 0;
		if (entry.get("bounds") instanceof Map<?, ?> bounds && bounds.get(member.externalName()) instanceof Map<?, ?> bound) {
			Long low = bound.containsKey("min") ? member.scalar(bound.get("min")) : null;
			Long high = bound.containsKey("max") ? member.scalar(bound.get("max")) : null;
			min = low != null ? low.longValue() : min;
			max = high != null ? high.longValue() : max;
			if (bound.get("step") instanceof Number n) {
				step = member.isTime() ? n.longValue() : n.longValue() / 1000;
			}
		}
		return new Schedule.Bound(member, min, max, step);
	}

	/** Whether a value the old bounds allow may not fit the new ones. */
	private static boolean narrowed(Schedule.Member member, Schedule.Bound before, Schedule.Bound after) {
		if (member == Schedule.Member.DAYS) {
			return false;
		}
		long lowest = before.step() > 0 ? Math.ceilDiv(before.min(), before.step()) * before.step() : before.min();
		long highest = before.step() > 0 ? Math.floorDiv(before.max(), before.step()) * before.step() : before.max();
		if (after.min() > lowest || after.max() < highest) {
			return true;
		}
		if (after.step() <= 0 || lowest == highest && lowest % after.step() == 0) {
			return false;
		}
		return before.step() <= 0 || before.step() % after.step() != 0 || lowest % after.step() != 0;
	}

	/**
	 * The schema below which the deploy confirmation warns ({@link #schemaWarning(String)}). The
	 * additions of a newer schema are refused by the deployer itself only where the image uses them
	 * (the parameter ids of schema 1.5, {@link Deployer#parameterIdsRefusal(String, Object)}), so an
	 * image without them goes to a controller reading 1.4 as before, without a warning.
	 */
	static final String WARNED_SCHEMA = "1.4";

	/**
	 * The line of the deploy confirmation that warns of a controller reading an older image schema than
	 * the image's (homecontrol <code>spec/runtime-image.md</code> I4): its firmware refuses the
	 * adjustable schedule members of schema 1.2. <code>null</code> when the controller reads the
	 * image's schema, or could not be asked.
	 *
	 * @param reported
	 *        The newest schema the controller reports it reads, <code>null</code> when it reports none.
	 */
	public static String schemaWarning(String reported) {
		if (reported == null) {
			return text(I18NConstants.CONFIRM_SCHEMA_UNKNOWN__IMAGE.fill(RuntimeImage.SCHEMA_VERSION));
		}
		int[] have = version(reported);
		int[] need = version(WARNED_SCHEMA);
		if (have != null && (have[0] > need[0] || have[0] == need[0] && have[1] >= need[1])) {
			return null;
		}
		return text(I18NConstants.CONFIRM_SCHEMA_OLDER__READS_IMAGE.fill(reported, RuntimeImage.SCHEMA_VERSION));
	}

	private static int[] version(String text) {
		String[] parts = text.trim().split("\\.");
		if (parts.length != 2) {
			return null;
		}
		try {
			return new int[] { Integer.parseInt(parts[0]), Integer.parseInt(parts[1]) };
		} catch (NumberFormatException ex) {
			return null;
		}
	}

	/**
	 * The newest image schema the controller of the project reports it reads: from its E-Home
	 * connection (its <code>auth</code>), else from <code>GET /api/status</code>
	 * (<code>device.schema</code>).
	 *
	 * @return The reported version; <code>""</code> when the controller could not be asked;
	 *         <code>null</code> when it reports none.
	 */
	static String reportedSchema(TLObject controller) {
		ControllerClient client = Deployer.client(controller);
		if (client.connection() != null) {
			return client.connection().schema();
		}
		try {
			Answer answer = client.status();
			if (!answer.ok()) {
				return "";
			}
			Object device = answer.body() == null ? null : answer.body().get("device");
			Object schema = device instanceof Map<?, ?> map ? map.get("schema") : null;
			return schema instanceof String text && !text.isBlank() ? text : null;
		} catch (IOException ex) {
			return "";
		}
	}

	/**
	 * The question asked before the image of the project is deployed: whether to deploy, with what
	 * differs from the image the controller stores; whether to deploy again if nothing differs. Below
	 * it the warnings: the datapoints the inhabitant's pages show by their id, having no name
	 * ({@link UnnamedDatapoints}, haumacher/ehome #114), a controller reading an older schema, an image
	 * larger than the controller can store.
	 */
	public static String confirmation(TLObject project, Instant now) {
		TLObject controller = Deployer.controller(project);
		Long next = Long.valueOf(Deployer.nextRevision(project));
		Result result = compare(project, now);
		String text;
		if (result.state() == State.IDENTICAL) {
			text = result.text() + "\n" + text(I18NConstants.CONFIRM_DEPLOY_AGAIN__REVISION.fill(next));
		} else {
			text = text(I18NConstants.CONFIRM_DEPLOY__REVISION_CONTROLLER.fill(next, controller.tValueByName("name")))
				+ "\n\n" + result.text();
		}
		// The datapoints the inhabitant's pages will show by their id, having no name (haumacher/ehome #114).
		RuntimeImage image = RuntimeImage.build(project, next.longValue(), now);
		List<String> unnamed = new ArrayList<>();
		for (ResKey warning : UnnamedDatapoints.warnings(image.getJson())) {
			unnamed.add(text(warning));
		}
		if (!unnamed.isEmpty()) {
			text += "\n\n" + String.join("\n", unnamed);
		}
		if (result.state() != State.UNKNOWN) {
			String reported = reportedSchema(controller);
			String warning = "".equals(reported) ? null : schemaWarning(reported);
			if (warning != null) {
				text += "\n\n" + warning;
			}
			// An image larger than the controller can store and load again (homecontrol #52) runs, but may
			// not survive a restart; a controller that reports no limit gets no warning.
			Long storable = ControllerStatus.read(Deployer.client(controller)).storable();
			if (storable != null) {
				ResKey tooLarge = Deployer.storableWarning(image.getBytes().length, storable);
				if (tooLarge != null) {
					text += "\n\n" + text(tooLarge);
				}
			}
		}
		return text;
	}

	/**
	 * The top level members of the image (its parts: <code>settings</code>, <code>datapoints</code>,
	 * ...) that differ, the header compared without its revision and time.
	 */
	public static Set<String> changedParts(Map<?, ?> stored, Map<?, ?> image) {
		Set<String> result = new LinkedHashSet<>();
		Set<Object> members = new LinkedHashSet<>(image.keySet());
		members.addAll(stored.keySet());
		for (Object member : members) {
			Object before = stored.get(member);
			Object after = image.get(member);
			if (HEADER.equals(member) && before instanceof Map<?, ?> beforeHeader && after instanceof Map<?, ?> afterHeader) {
				before = withoutStamp(beforeHeader);
				after = withoutStamp(afterHeader);
			}
			if (!Objects.equals(normalize(before), normalize(after))) {
				result.add(String.valueOf(member));
			}
		}
		return result;
	}

	private static Map<?, ?> withoutStamp(Map<?, ?> header) {
		Map<Object, Object> result = new LinkedHashMap<>(header);
		result.remove("revision");
		result.remove("generatedAt");
		return result;
	}

	/**
	 * The JSON value with its numbers as {@link Double}s, so that a number read back compares equal
	 * to the number written (a long against a double of the same value).
	 */
	private static Object normalize(Object value) {
		if (value instanceof Number number) {
			return Double.valueOf(number.doubleValue());
		}
		if (value instanceof Map<?, ?> map) {
			Map<Object, Object> result = new HashMap<>();
			map.forEach((k, v) -> result.put(k, normalize(v)));
			return result;
		}
		if (value instanceof Collection<?> list) {
			List<Object> result = new ArrayList<>();
			for (Object element : list) {
				result.add(normalize(element));
			}
			return result;
		}
		return value;
	}

	private static String counts(Map<?, ?> image, boolean stored) {
		Integer datapoints = Integer.valueOf(size(image.get("datapoints")));
		Integer locations = Integer.valueOf(size(image.get("locations")));
		Integer functions = Integer.valueOf(size(image.get("functions")));
		return text((stored ? I18NConstants.COMPARE_STORED_COUNTS__DATAPOINTS_LOCATIONS_FUNCTIONS
			: I18NConstants.COMPARE_NEW_COUNTS__DATAPOINTS_LOCATIONS_FUNCTIONS).fill(datapoints, locations, functions));
	}

	/**
	 * The number of entries of the stored list (rules or schedule entries) whose <code>id</code> the
	 * new list does not have: what the deployment removes from the controller.
	 */
	private static int missing(Object stored, Object next) {
		if (!(stored instanceof Collection<?> entries)) {
			return 0;
		}
		Set<Object> kept = new HashSet<>();
		if (next instanceof Collection<?> list) {
			for (Object entry : list) {
				if (entry instanceof Map<?, ?> map) {
					kept.add(map.get("id"));
				}
			}
		}
		int result = 0;
		for (Object entry : entries) {
			if (!(entry instanceof Map<?, ?> map) || !kept.contains(map.get("id"))) {
				result++;
			}
		}
		return result;
	}

	private static int size(Object list) {
		return list instanceof Collection<?> collection ? collection.size() : 0;
	}

	private static Instant instant(Object text) {
		if (text == null) {
			return null;
		}
		try {
			return Instant.parse(text.toString());
		} catch (DateTimeParseException ex) {
			return null;
		}
	}

	private static String text(ResKey key) {
		return Resources.getInstance().getString(key);
	}

}
