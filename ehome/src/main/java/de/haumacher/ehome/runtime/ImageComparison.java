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
 * does not author and a deployment removes.
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
		return new Result(State.DIFFERENT, revision, String.join("\n", lines));
	}

	/**
	 * The question asked before the image of the project is deployed: whether to deploy, with what
	 * differs from the image the controller stores; whether to deploy again if nothing differs.
	 */
	public static String confirmation(TLObject project, Instant now) {
		TLObject controller = Deployer.controller(project);
		Long next = Long.valueOf(Deployer.nextRevision(project));
		Result result = compare(project, now);
		if (result.state() == State.IDENTICAL) {
			return result.text() + "\n" + text(I18NConstants.CONFIRM_DEPLOY_AGAIN__REVISION.fill(next));
		}
		return text(I18NConstants.CONFIRM_DEPLOY__REVISION_CONTROLLER.fill(next, controller.tValueByName("name")))
			+ "\n\n" + result.text();
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
