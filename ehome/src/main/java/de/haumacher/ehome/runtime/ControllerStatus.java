/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.runtime;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;

import com.top_logic.basic.util.ResKey;
import com.top_logic.model.TLObject;
import com.top_logic.util.Resources;

import de.haumacher.ehome.runtime.ControllerClient.Answer;

/**
 * What a controller runs (<code>GET /api/status</code>, member <code>image</code>), and how it compares
 * with the last deployment of a project (drift).
 */
public final class ControllerStatus {

	/**
	 * The image member of a status answer.
	 *
	 * @param answered
	 *        Whether the controller told its status.
	 * @param loaded
	 *        Whether an image runs.
	 * @param project
	 *        Its project ID.
	 * @param revision
	 *        Its revision, <code>null</code> if unknown.
	 * @param text
	 *        What the controller runs, for the user.
	 * @param storable
	 *        The largest image in bytes the controller can store and load again
	 *        (<code>image.storable</code>, homecontrol #52), <code>null</code> when it reports none (a
	 *        firmware before it) or did not answer.
	 */
	public record Loaded(boolean answered, boolean loaded, String project, Long revision, String text, Long storable) {
		// A record.
	}

	private ControllerStatus() {
		// Static helpers.
	}

	/**
	 * Reads what the controller runs.
	 */
	public static Loaded read(ControllerClient client) {
		Answer answer;
		try {
			answer = client.status();
		} catch (IOException ex) {
			return new Loaded(false, false, null, null,
				text(I18NConstants.STATUS_UNREACHABLE__ADDRESS_ERROR.fill(client.address(), Deployer.reason(ex))), null);
		}
		if (!answer.ok()) {
			String error = answer.string("error");
			return new Loaded(false, false, null, null, text(I18NConstants.STATUS_FAILED__STATUS_ERROR
				.fill(Integer.valueOf(answer.status()), error != null ? error : answer.text().strip())), null);
		}
		Long storable = storable(answer.body());
		if (!(answer.body().get("image") instanceof Map<?, ?> image) || !Boolean.TRUE.equals(image.get("loaded"))) {
			Object refused = answer.body().get("image") instanceof Map<?, ?> image ? image.get("refused") : null;
			return new Loaded(true, false, null, null, text(refused != null
				? I18NConstants.STATUS_NO_IMAGE_REFUSED__CODE.fill(refused)
				: I18NConstants.STATUS_NO_IMAGE), storable);
		}
		String project = Objects.toString(image.get("project"), "");
		Long revision = image.get("revision") instanceof Number number ? Long.valueOf(number.longValue()) : null;
		String result = text(I18NConstants.STATUS_LOADED__PROJECT_REVISION_NAME_SOURCE.fill(project,
			revision == null ? "?" : revision, Objects.toString(image.get("name"), ""),
			Objects.toString(image.get("source"), "?")));
		if (Boolean.TRUE.equals(image.get("degraded"))) {
			result += " " + text(I18NConstants.STATUS_DEGRADED);
		}
		return new Loaded(true, true, project, revision, result, storable);
	}

	/**
	 * The largest image the controller can store and load again, from the body of its
	 * <code>GET /api/status</code> (<code>image.storable</code>, homecontrol #52).
	 *
	 * @return The bytes; <code>null</code> when the status has none.
	 */
	public static Long storable(Map<?, ?> status) {
		Object image = status == null ? null : status.get("image");
		Object storable = image instanceof Map<?, ?> map ? map.get("storable") : null;
		return storable instanceof Number number ? Long.valueOf(number.longValue()) : null;
	}

	/**
	 * What the controller runs, as a text for the user.
	 */
	public static String describe(ControllerClient client) {
		return read(client).text();
	}

	/**
	 * What the controller of the project runs, compared with the project's last deployment: one
	 * line saying whether they agree, one saying what the controller runs.
	 */
	public static String drift(TLObject project) {
		ResKey veto = Deployer.veto(project);
		if (Deployer.controller(project) == null) {
			return text(veto);
		}
		Loaded loaded = read(Deployer.client(Deployer.controller(project)));
		String projectId = Objects.toString(project.tValueByName("extId"), "");
		Long deployed = project.tValueByName("imageRevision") instanceof Number number ? Long.valueOf(number.longValue())
			: null;
		ResKey verdict;
		if (!loaded.answered() || (loaded.loaded() && loaded.revision() == null)) {
			verdict = null;
		} else if (!loaded.loaded()) {
			verdict = deployed == null ? I18NConstants.DRIFT_NEVER_DEPLOYED : I18NConstants.DRIFT_NO_IMAGE__DEPLOYED.fill(deployed);
		} else if (!projectId.equals(loaded.project())) {
			verdict = I18NConstants.DRIFT_OTHER_PROJECT__PROJECT.fill(loaded.project());
		} else if (deployed == null) {
			verdict = I18NConstants.DRIFT_NOT_DEPLOYED__LOADED.fill(loaded.revision());
		} else if (deployed.equals(loaded.revision())) {
			verdict = I18NConstants.DRIFT_IN_SYNC__REVISION.fill(deployed);
		} else {
			verdict = I18NConstants.DRIFT_OTHER_REVISION__LOADED_DEPLOYED.fill(loaded.revision(), deployed);
		}
		return verdict == null ? loaded.text() : text(verdict) + "\n" + loaded.text();
	}

	private static String text(ResKey key) {
		return Resources.getInstance().getString(key);
	}

}
