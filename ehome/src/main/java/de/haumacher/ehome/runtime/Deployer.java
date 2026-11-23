/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.runtime;

import java.io.IOException;
import java.text.DateFormat;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import com.top_logic.basic.util.ResKey;
import com.top_logic.knowledge.service.HistoryUtils;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Revision;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.knowledge.wrap.WrapperHistoryUtils;
import com.top_logic.layout.provider.MetaLabelProvider;
import com.top_logic.model.TLClass;
import com.top_logic.model.TLObject;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.Resources;
import com.top_logic.util.error.TopLogicException;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.connection.ControllerConnection;
import de.haumacher.ehome.connection.ControllerConnections;
import de.haumacher.ehome.connection.InstallationKeys;
import de.haumacher.ehome.connection.ProjectControllers;
import de.haumacher.ehome.connection.Signatures.Keys;
import de.haumacher.ehome.model.project.Project;
import de.haumacher.ehome.model.runtime.Controller;
import de.haumacher.ehome.model.runtime.Deployment;
import de.haumacher.ehome.runtime.ControllerClient.Answer;
import de.haumacher.ehome.runtime.ImageReport.Refusal;
import de.haumacher.ehome.runtime.binary.CompileException;
import de.haumacher.ehome.runtime.binary.ImageCompiler;
import de.haumacher.ehome.runtime.binary.Layout;
import de.haumacher.ehome.settings.OperatorSettings;

/**
 * Deploys the runtime image of a project to its controller (haumacher/ehome #39) and records the
 * outcome as a deployment of the project (<code>ehome.runtime:Deployment</code>).
 *
 * <p>
 * The image carries the revision after the last one a controller loaded
 * (<code>ehome.project:Project#imageRevision</code>); only an accepted image advances it, so a refused
 * deployment is repeated with the same revision. Before the image is sent it is checked as the
 * controller checks it ({@link ImageValidator}); a refused image is not sent and the refusals are
 * recorded as those of the controller would be. Before the image is sent, the status of the
 * controller is read, and what it ran is recorded with the deployment. Each refusal is recorded
 * with the object of the project its path leads to ({@link RuntimeImage#elementAt(String)}). The
 * message of an accepted image warns for each datapoint the controller shows on the inhabitant's pages
 * by its id, having no name ({@link UnnamedDatapoints}, haumacher/ehome #114), and for each role of a
 * function that no object in effect serves ({@link FunctionRoleCheck}, haumacher/ehome #137); it does
 * not refuse it.
 * </p>
 *
 * <p>
 * The controller gets the binary image compiled from the source (haumacher/ehome #176, homecontrol
 * <code>spec/runtime-image.md</code> section 9), signed with the key the controller holds
 * ({@link InstallationKeys#signer(TLObject, TLObject)}, homecontrol issue #35), the signature its last
 * 64 bytes; the deployment records the signature and the key's fingerprint. A project without a key deploys unsigned. A device refusing the signature
 * ({@value #CODE_UNSIGNED}, {@value #CODE_BAD_SIGNATURE}) names the key it expects in its reason, which
 * the recorded message keeps beside the key E-Home signed with.
 * </p>
 *
 * <p>
 * The image a controller runs is unloaded with {@link #unload(TLObject, Instant)}
 * (<code>DELETE /api/image</code>, haumacher/ehome #47): the controller removes it with its persisted
 * values and history and returns to setup. An unload is recorded as a deployment as well, marked as
 * one (<code>unload</code>), with the revision the controller ran.
 * </p>
 *
 * <p>
 * An image that does not load leaves the controller without one (<code>spec/runtime-image.md</code>
 * I9): E-Home then sends the last valid deployment again ({@link LastValidImages}) and records it as a
 * deployment of its own, beside the failed one; so it does when the controller reports at its
 * connection that its image was refused ({@link #resendAfterRefusal(TLObject, String, String, Instant)}).
 * </p>
 *
 * <p>
 * Every deployment can be compiled again from its own point in time (haumacher/ehome #192): the
 * knowledge base is versioned, and the {@link #state(TLObject) state} of a deployment is the commit
 * its image was compiled from. "Deploy again" ({@link #redeploy(TLObject, TLObject, Instant)}) compiles
 * the image from the project as of that commit, its master data included (the historic objects of the
 * revision, {@link WrapperHistoryUtils#getWrapper(Revision, TLObject)}), with the time of the earlier
 * image, stamps it with the next revision and signs it with the key the controller holds now. It is
 * recorded as a deployment of its own, its message starting with the deployment it goes back to.
 * </p>
 *
 * <p>
 * Each record keeps the commit its image was compiled from (<code>Deployment#sourceRevision</code>):
 * the record and the compile are not one commit apart (the controller is asked and the image sent in
 * between, while others may commit). A deployment that goes back to an earlier one keeps that one's
 * commit and refers to it (<code>Deployment#redeployedFrom</code>), its image carrying the time of the
 * first. A deployment recorded before haumacher/ehome #192 has the revision before its record as its
 * state.
 * </p>
 */
public final class Deployer {

	/** The code of a deployment E-Home refused itself, the image being invalid. */
	public static final String CODE_INVALID = "ehome.invalid";

	/** The code of a deployment whose controller did not answer. */
	public static final String CODE_UNREACHABLE = "ehome.unreachable";

	/** The device's refusal of an image without signature while it holds an installation key. */
	public static final String CODE_UNSIGNED = "image.unsigned";

	/** The device's refusal of an image whose signature does not verify under its key. */
	public static final String CODE_BAD_SIGNATURE = "image.bad_signature";

	/** How long to wait for the controller. */
	public static final Duration TIMEOUT = Duration.ofSeconds(10);

	private Deployer() {
		// Static helpers.
	}

	/**
	 * Why the project cannot be deployed now, <code>null</code> if it can.
	 */
	public static ResKey veto(TLObject project) {
		if (((Project) project).getController() instanceof TLObject target && target.tValid()) {
			// A project deploys to its own controllers only (haumacher/ehome #151).
			ResKey foreign = ProjectControllers.foreignVeto(project, target);
			if (foreign != null) {
				return foreign;
			}
		}
		TLObject controller = controller(project);
		if (controller == null) {
			return I18NConstants.ERROR_NO_CONTROLLER;
		}
		return controllerVeto(controller);
	}

	/**
	 * Why the controller cannot be reached now, <code>null</code> if it can (haumacher/ehome #54): a
	 * controller connected to E-Home is reached over its connection, otherwise over HTTP on the LAN,
	 * which needs its host and token. An enrolled controller that is neither connected nor reachable
	 * on the LAN is offline: the reason names its last contact.
	 */
	public static ResKey controllerVeto(TLObject controller) {
		if (ControllerConnections.get(controller) != null) {
			return null;
		}
		Object name = ((Controller) controller).getName();
		boolean host = !blank(((Controller) controller).getHost());
		boolean token = !blank(((Controller) controller).getToken());
		if (ControllerConnections.controllerId(controller) != null && !(host && token)) {
			return offline(controller);
		}
		if (!host) {
			return I18NConstants.ERROR_NO_HOST__CONTROLLER.fill(name);
		}
		if (!token) {
			return I18NConstants.ERROR_NO_TOKEN__CONTROLLER.fill(name);
		}
		return null;
	}

	/**
	 * Why the enrolled controller cannot be reached over its connection: not connected, with its last
	 * contact.
	 */
	public static ResKey offline(TLObject controller) {
		Object name = ((Controller) controller).getName();
		Object contact = ((Controller) controller).getLastContact();
		if (contact instanceof Date date) {
			return I18NConstants.ERROR_OFFLINE__CONTROLLER_LAST_CONTACT.fill(name,
				DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Resources.getInstance().getLocale())
					.format(date));
		}
		return I18NConstants.ERROR_NEVER_CONNECTED__CONTROLLER.fill(name);
	}

	private static boolean blank(Object value) {
		return value == null || value.toString().isBlank();
	}

	/**
	 * The controller the project is deployed to, <code>null</code> if none is chosen or the chosen one
	 * is not one of the project's own (haumacher/ehome #151, {@link #veto(TLObject)} names it).
	 */
	public static TLObject controller(TLObject project) {
		return ((Project) project).getController() instanceof TLObject controller
			&& ProjectControllers.isOwn(project, controller) ? controller : null;
	}

	/**
	 * The client talking to the given controller.
	 */
	public static ControllerClient client(TLObject controller) {
		ControllerConnection connection = ControllerConnections.get(controller);
		if (connection != null) {
			return ControllerClient.over(connection, text(I18NConstants.ADDRESS_CONNECTION), TIMEOUT);
		}
		ResKey localNetwork = OperatorSettings.localNetworkVeto();
		if (localNetwork != null) {
			// Only the connection the controller opens itself is left (haumacher/ehome #150).
			ResKey reason = ControllerConnections.controllerId(controller) != null ? offline(controller) : localNetwork;
			return ControllerClient.offline(text(I18NConstants.ADDRESS_OFFLINE), text(reason), TIMEOUT);
		}
		Controller target = (Controller) controller;
		Object host = target.getHost();
		Object port = target.getPortOrNull();
		Object token = target.getToken();
		if (blank(host) || (ControllerConnections.controllerId(controller) != null && blank(token))) {
			ResKey reason = ControllerConnections.controllerId(controller) != null ? offline(controller)
				: I18NConstants.ERROR_NO_HOST__CONTROLLER.fill(target.getName());
			return ControllerClient.offline(text(I18NConstants.ADDRESS_OFFLINE), text(reason), TIMEOUT);
		}
		return new ControllerClient(host.toString().trim(),
			port instanceof Number number ? number.intValue() : 80, token == null ? null : token.toString(), TIMEOUT);
	}

	/**
	 * The revision the next deployment of the project carries.
	 */
	public static long nextRevision(TLObject project) {
		return ((Project) project).getImageRevision() instanceof Number last ? last.longValue() + 1 : 1;
	}

	/**
	 * Deploys the image of the project to its controller and records the deployment, in a
	 * transaction of its own.
	 *
	 * <p>
	 * The image is validated ({@link ImageValidator}) and compiled into the binary image
	 * ({@link de.haumacher.ehome.runtime.binary.ImageCompiler}, homecontrol
	 * <code>spec/runtime-image.md</code> section 9, haumacher/ehome #176), signed with the key the
	 * controller holds. It is refused before sending when the controller reads no binary image (a
	 * firmware that reports no <code>format</code>, or another major format: "Firmware aktualisieren")
	 * or when it is larger than the controller's image slot. Before it is sent, the image the
	 * controller runs is fetched (<code>GET /api/image</code>): when the new image does not load (the
	 * controller then runs none, <code>spec/runtime-image.md</code> I9), that last valid deployment is
	 * sent again at once and recorded as a deployment of its own.
	 * </p>
	 *
	 * @return The recorded deployment.
	 * @throws TopLogicException
	 *         If the project cannot be deployed ({@link #veto(TLObject)}).
	 */
	public static TLObject deploy(TLObject project, Instant now) {
		ResKey veto = veto(project);
		if (veto != null) {
			throw new TopLogicException(veto);
		}
		// The state the image is compiled from: the revision the reader sees (its session revision).
		State state = new State(HistoryUtils.getSessionRevision().getCommitNumber(), now);
		return deploy(project, project, state, null, now);
	}

	/**
	 * Deploys the image of an earlier deployment of the project again (haumacher/ehome #192): the
	 * image compiled from the project as of the {@link #state(TLObject) state} of that deployment,
	 * with the time of its image, the next revision and the key the controller holds now. Checked,
	 * compared, sent and recorded as {@link #deploy(TLObject, Instant)} does; the message of the
	 * record starts with the deployment it goes back to.
	 *
	 * @param project
	 *        The project, as it is now.
	 * @param earlier
	 *        The deployment of the project to deploy again.
	 * @return The recorded deployment.
	 * @throws TopLogicException
	 *         If the deployment cannot be deployed again ({@link #redeployVeto(TLObject, TLObject)}).
	 */
	public static TLObject redeploy(TLObject project, TLObject earlier, Instant now) {
		ResKey veto = redeployVeto(project, earlier);
		if (veto != null) {
			throw new TopLogicException(veto);
		}
		State state = state(earlier);
		return deploy(project, source(project, state), state, earlier, now);
	}

	/**
	 * Why the given deployment of the project cannot be deployed again now, <code>null</code> if it
	 * can: the project cannot be deployed ({@link #veto(TLObject)}), the entry is no deployment of
	 * the project, it is an unload, no controller accepted its image, it sent an earlier image again (it has no state of its own), or
	 * the history no longer reaches back to its state.
	 */
	public static ResKey redeployVeto(TLObject project, TLObject earlier) {
		if (earlier == null || !earlier.tValid() || !((Project) project).getDeployments().contains(earlier)) {
			return I18NConstants.ERROR_REDEPLOY_NO_DEPLOYMENT;
		}
		ResKey veto = veto(project);
		if (veto != null) {
			return veto;
		}
		Deployment entry = (Deployment) earlier;
		if (entry.getUnload()) {
			return I18NConstants.ERROR_REDEPLOY_UNLOAD;
		}
		if (!entry.getAccepted()) {
			// No controller ever ran this image (E-Home did not send it, or the controller refused it):
			// there is no working state to go back to.
			return I18NConstants.ERROR_REDEPLOY_NOT_ACCEPTED__REVISION.fill(entry.getRevision());
		}
		State state = state(earlier);
		if (state == null) {
			return I18NConstants.ERROR_REDEPLOY_RESENT__REVISION.fill(entry.getRevision());
		}
		if (state.commit() < HistoryUtils.getHistoryManager().getFirstRevision()) {
			return I18NConstants.ERROR_REDEPLOY_HISTORY_GONE__COMMIT.fill(Long.valueOf(state.commit()));
		}
		if (source(project, state) == null) {
			return I18NConstants.ERROR_REDEPLOY_NO_PROJECT__COMMIT.fill(Long.valueOf(state.commit()));
		}
		return null;
	}

	/**
	 * The state of the project a deployment compiled its image from (haumacher/ehome #192).
	 *
	 * @param commit
	 *        The commit number of the revision whose project the image was compiled from.
	 * @param generatedAt
	 *        The time the image carries (<code>image.generatedAt</code>).
	 */
	public record State(long commit, Instant generatedAt) {
		// A value.
	}

	/**
	 * The state of the project the given deployment compiled its image from, <code>null</code> for an
	 * entry without an image of its own: an unload, an earlier image sent again.
	 *
	 * <p>
	 * The commit is the recorded <code>sourceRevision</code>; the time of the image the one of the
	 * deployment, for a deployment that went back to an earlier one the time of that one's image. A
	 * deployment recorded before haumacher/ehome #192 (without <code>sourceRevision</code>) has the
	 * revision before its record as its state; one that sent an earlier image again has no state (it
	 * read no status of the controller before, <code>before</code> is empty, and was not refused by
	 * E-Home itself).
	 * </p>
	 */
	public static State state(TLObject deployment) {
		return state(deployment, 0);
	}

	/** The longest chain of deployments going back to earlier ones that is followed. */
	private static final int MAX_CHAIN = 1000;

	private static State state(TLObject object, int depth) {
		if (object == null || !object.tValid() || depth > MAX_CHAIN || ((Deployment) object).getUnload()) {
			return null;
		}
		Deployment deployment = (Deployment) object;
		Instant time = deployment.getTime() instanceof Date date ? date.toInstant() : null;
		if (time == null) {
			return null;
		}
		if (deployment.getSourceRevision() instanceof Number commit) {
			if (deployment.getRedeployedFrom() instanceof TLObject earlier) {
				State origin = state(earlier, depth + 1);
				return new State(commit.longValue(), origin != null ? origin.generatedAt() : time);
			}
			return new State(commit.longValue(), time);
		}
		// Recorded before haumacher/ehome #192: compiled right before its record, unless it sent an earlier
		// image again, which reads no status of the controller (an image E-Home refused itself is recorded
		// before the status is read).
		boolean compiled = deployment.getBefore() != null || CODE_INVALID.equals(deployment.getCode());
		long created = deployment.tHandle().getCreateCommitNumber();
		if (!compiled || created <= 0 || created > HistoryUtils.getSessionRevision().getCommitNumber()) {
			return null;
		}
		return new State(created - 1, time);
	}

	/** The attribute of a deployment holding the commit its image was compiled from (haumacher/ehome #192). */
	public static final String SOURCE_REVISION = "sourceRevision";

	/** The attribute of a deployment referring to the earlier deployment it deployed again. */
	public static final String REDEPLOYED_FROM = "redeployedFrom";

	/**
	 * The project as of the given state: the historic object of its revision, <code>null</code> if
	 * the project did not exist then.
	 */
	public static TLObject source(TLObject project, State state) {
		return WrapperHistoryUtils.getWrapper(HistoryUtils.getRevision(state.commit()), project);
	}

	/**
	 * Deploys the image compiled from the given source.
	 *
	 * @param project
	 *        The project as it is now: its controller, revision, key and deployments.
	 * @param source
	 *        The project the image is compiled from: the project itself, or its historic object of an
	 *        earlier state.
	 * @param state
	 *        The state of the source, recorded with the deployment.
	 * @param earlier
	 *        The deployment deployed again, <code>null</code> for a deployment of the project as it is.
	 */
	private static TLObject deploy(TLObject project, TLObject source, State state, TLObject earlier, Instant now) {
		TLObject controller = controller(project);
		ControllerClient client = client(controller);
		long revision = nextRevision(project);
		RuntimeImage image = RuntimeImage.build(source, revision, state.generatedAt());
		ImageReport report = image.validate();
		Outcome outcome = new Outcome();
		outcome._state = state;
		outcome._earlier = earlier;
		if (!report.isValid()) {
			outcome._code = CODE_INVALID;
			outcome._message = text(I18NConstants.DEPLOY_REFUSED_LOCALLY__COUNT.fill(report.getRefusals().size()));
			outcome._refusals.addAll(report.getRefusals());
			outcome._total = report.getRefusals().size();
			return record(project, controller, client, image, Long.valueOf(revision), now, outcome);
		}
		ControllerStatus.Loaded before = ControllerStatus.read(client);
		outcome._before = before.text();
		String reported = reportedFormat(client, before);
		ResKey formatRefusal = formatRefusal(reported, ((Controller) controller).getName(), image.getJson());
		if (before.answered() && formatRefusal != null) {
			// A firmware reading JSON images (or another major format) cannot load the binary image: nothing is sent.
			outcome._code = CODE_FORMAT;
			outcome._message = text(formatRefusal);
			return record(project, controller, client, image, Long.valueOf(revision), now, outcome);
		}
		Keys signer = InstallationKeys.signer(project, controller);
		byte[] binary;
		try {
			binary = image.compile(signer == null ? null : signer.seed(), before.answered() ? reportedMinor(reported) : Layout.MINOR);
		} catch (CompileException ex) {
			outcome._code = CODE_INVALID;
			outcome._message = text(I18NConstants.DEPLOY_COMPILE_FAILED__PATH_REASON.fill(ex.getPath(), ex.getReason()));
			return record(project, controller, client, image, Long.valueOf(revision), now, outcome);
		}
		outcome._bytes = Integer.valueOf(binary.length);
		if (signer != null) {
			outcome._signature = HexFormat.of().formatHex(binary, binary.length - Layout.SIGNATURE_SIZE, binary.length);
			outcome._fingerprint = signer.fingerprint();
		}
		Long slot = reportedSlot(client, before);
		ResKey tooLarge = slotRefusal(binary.length, slot);
		if (tooLarge != null) {
			outcome._code = CODE_TOO_LARGE;
			outcome._message = text(tooLarge);
			return record(project, controller, client, image, Long.valueOf(revision), now, outcome);
		}
		// What the controller runs now is the last valid deployment, sent again if the new image fails.
		LastValidImages.Entry running = before.loaded() ? LastValidImages.fetch(controller, client) : null;
		send(client, binary, outcome);
		if (outcome._accepted) {
			outcome._image = binary;
			// A role of a function no object in effect serves (haumacher/ehome #137): taken, but it never works.
			Map<String, Object> json = image.getJson();
			String message = text(I18NConstants.DEPLOY_ACCEPTED__REVISION_DATAPOINTS_LOCATIONS_BYTES.fill(
				Long.valueOf(revision), size(json.get("datapoints")), size(json.get("locations")),
				Integer.valueOf(binary.length)));
			if (image.getUnusedFunctions() > 0) {
				message += " " + text(I18NConstants.EXPORT_UNUSED__COUNT.fill(Integer.valueOf(image.getUnusedFunctions())));
			}
			// A datapoint the inhabitant's pages show by its id, having no name (haumacher/ehome #114).
			for (ResKey warning : UnnamedDatapoints.warnings(json)) {
				message += " " + text(warning);
			}
			for (ResKey warning : FunctionRoleCheck.warnings(project)) {
				message += " " + text(warning);
			}
			outcome._message = message;
			return record(project, controller, client, image, Long.valueOf(revision), now, outcome);
		}
		if (!gone(client, outcome)) {
			return record(project, controller, client, image, Long.valueOf(revision), now, outcome);
		}
		// The controller runs no image now (spec/runtime-image.md I9): the last valid deployment goes again.
		LastValidImages.Entry last = running != null ? running : LastValidImages.get(controller);
		if (last == null) {
			outcome._message += " " + text(I18NConstants.DEPLOY_NO_LAST_VALID);
			return record(project, controller, client, image, Long.valueOf(revision), now, outcome);
		}
		Outcome resend = resend(client, last, outcome);
		outcome._message += " " + text(resend._accepted ? I18NConstants.DEPLOY_RESENT__REVISION.fill(last.revision())
			: I18NConstants.DEPLOY_RESEND_FAILED__REVISION.fill(last.revision()));
		TLObject deployment = record(project, controller, client, image, Long.valueOf(revision), now, outcome);
		record(project, controller, client, null, last.revision(), Instant.now(), resend);
		return deployment;
	}

	/**
	 * Sends the last valid deployment again after the image before it did not load.
	 *
	 * @param cause
	 *        Why it is sent again, for its message; <code>null</code> for a refusal the controller
	 *        reported by itself.
	 */
	private static Outcome resend(ControllerClient client, LastValidImages.Entry last, Outcome cause) {
		Outcome outcome = new Outcome();
		outcome._resend = true;
		outcome._bytes = Integer.valueOf(last.bytes().length);
		outcome._foreign = !last.own();
		send(client, last.bytes(), outcome);
		String revision = String.valueOf(last.revision());
		if (outcome._accepted) {
			outcome._image = last.bytes();
			outcome._message = text(I18NConstants.RESEND_ACCEPTED__REVISION.fill(revision));
		} else {
			outcome._message = text(I18NConstants.RESEND_REFUSED__REVISION.fill(revision)) + " " + outcome._message;
		}
		return outcome;
	}

	/**
	 * The controller reported that the image it tried to load was refused and it runs none
	 * (homecontrol <code>spec/api.md</code>, "The socket": <code>image.refused</code> of its
	 * <code>auth</code>; <code>spec/runtime-image.md</code> I9, e.g. its slot damaged by a power cut):
	 * the last valid deployment E-Home knows of goes again and is recorded with the project of the
	 * controller. Without one, the refusal is recorded so that the user deploys again.
	 *
	 * @param controller
	 *        The controller (<code>ehome.runtime:Controller</code>) in its project.
	 * @param code
	 *        The code of the refusal.
	 * @param reason
	 *        Its first refusal, <code>path: reason</code>.
	 * @return The recorded deployment, <code>null</code> for a controller without a project.
	 */
	public static TLObject resendAfterRefusal(TLObject controller, String code, String reason, Instant now) {
		TLObject project = controller.tContainer();
		if (project == null || !project.tValid()) {
			return null;
		}
		ControllerClient client = client(controller);
		String reported = text(I18NConstants.DEPLOY_REPORTED_REFUSED__CODE_REASON.fill(code, reason == null ? "" : reason));
		LastValidImages.Entry last = LastValidImages.get(controller);
		if (last == null) {
			Outcome outcome = new Outcome();
			outcome._resend = true;
			outcome._code = code;
			outcome._message = reported + " " + text(I18NConstants.DEPLOY_NO_LAST_VALID);
			return record(project, controller, client, null, null, now, outcome);
		}
		Outcome outcome = resend(client, last, null);
		outcome._message = reported + " " + outcome._message;
		return record(project, controller, client, null, last.revision(), now, outcome);
	}

	/**
	 * Whether the controller runs no image after the refusal of the image just sent (homecontrol
	 * <code>spec/api.md</code>, "Runtime image": <code>loaded</code> of the refusal; without an answer,
	 * its status).
	 */
	private static boolean gone(ControllerClient client, Outcome outcome) {
		if (outcome._loaded != null) {
			return !outcome._loaded.booleanValue();
		}
		if (outcome._status != null) {
			return false;
		}
		ControllerStatus.Loaded after = ControllerStatus.read(client);
		return after.answered() && !after.loaded() && after.refused() != null;
	}

	/** The code of a deployment E-Home refused because the controller reads no binary image of format 1. */
	public static final String CODE_FORMAT = "ehome.format";

	/** The code of a deployment E-Home refused because the image is larger than the controller's slot. */
	public static final String CODE_TOO_LARGE = "ehome.too_large";

	/**
	 * The format of the binary runtime image the controller reads: from its E-Home connection (its
	 * <code>auth</code>), else from its status.
	 */
	static String reportedFormat(ControllerClient client, ControllerStatus.Loaded status) {
		if (client.connection() != null && client.connection().format() != null) {
			return client.connection().format();
		}
		return status.format();
	}

	/**
	 * The size of the controller's image slot: from its status, else from its E-Home connection;
	 * <code>null</code> when it reports none.
	 */
	static Long reportedSlot(ControllerClient client, ControllerStatus.Loaded status) {
		if (status.slot() != null) {
			return status.slot();
		}
		return client.connection() == null ? null : client.connection().slot();
	}

	/**
	 * Why the binary image of the given source cannot go to a controller reporting the given format
	 * (homecontrol <code>spec/runtime-image.md</code> sections 8 and 11): a controller reporting none
	 * runs a firmware that reads JSON images (up to 1.2), one of another major format a firmware E-Home
	 * cannot write for, one of an older minor format than the image is written in for it (format 1.1
	 * for the anonymous pages, haumacher/ehome #184, 1.2 for the initial values; the meter flags of 1.3
	 * are left out for a controller before 1.3, {@link ImageCompiler#forFormat(Map, int)}) a firmware
	 * that does not know its sections, one before 1.3 a firmware whose pages do not draw the chart
	 * ranges 6h and 10y (haumacher/ehome #221); <code>null</code> when it reads the image. There is no
	 * JSON fallback: every controller is flashed with a firmware reading the binary image.
	 */
	public static ResKey formatRefusal(String reported, Object controller, Map<?, ?> source) {
		if (reported == null || reported.isBlank()) {
			return I18NConstants.DEPLOY_REFUSED_NO_FORMAT__CONTROLLER.fill(controller);
		}
		int minor = reportedMinor(reported);
		int imageMinor = ImageCompiler.formatMinor(ImageCompiler.forFormat(source, minor));
		if (minor < 0 || minor < imageMinor) {
			return I18NConstants.DEPLOY_REFUSED_FORMAT__CONTROLLER_FORMAT_IMAGE.fill(controller, reported.trim(),
				Layout.MAJOR + "." + imageMinor);
		}
		if (minor < 3 && ImageCompiler.usesRanges13(source)) {
			return I18NConstants.DEPLOY_REFUSED_CHART_RANGES__CONTROLLER_FORMAT.fill(controller, reported.trim());
		}
		return null;
	}

	/**
	 * The minor version of the format the controller reports (<code>1.2</code>: 2); -1 for a format of
	 * another major version or none.
	 */
	public static int reportedMinor(String reported) {
		if (reported == null) {
			return -1;
		}
		String[] parts = reported.trim().split("\\.");
		try {
			if (parts.length == 2 && Integer.parseInt(parts[0]) == Layout.MAJOR) {
				return Integer.parseInt(parts[1]);
			}
		} catch (NumberFormatException ex) {
			// None.
		}
		return -1;
	}

	/**
	 * Why an image of the given size cannot go to a controller with the given image slot (homecontrol
	 * <code>spec/runtime-image.md</code> section 7): "Das Abbild hat N Bytes, der Controller fasst M."
	 * <code>null</code> when it fits or the controller reports no slot.
	 */
	public static ResKey slotRefusal(int bytes, Long slot) {
		if (slot == null || bytes <= slot.longValue()) {
			return null;
		}
		return I18NConstants.DEPLOY_TOO_LARGE__BYTES_SLOT.fill(Integer.valueOf(bytes), slot);
	}

	/**
	 * Unloads the image the controller of the project runs (<code>DELETE /api/image</code>) and
	 * records it as a deployment marked as an unload, in a transaction of its own.
	 *
	 * @return The recorded unload.
	 * @throws TopLogicException
	 *         If the project cannot be deployed ({@link #veto(TLObject)}), and so not unloaded.
	 */
	public static TLObject unload(TLObject project, Instant now) {
		ResKey veto = veto(project);
		if (veto != null) {
			throw new TopLogicException(veto);
		}
		TLObject controller = controller(project);
		ControllerClient client = client(controller);
		Outcome outcome = new Outcome();
		outcome._unload = true;
		ControllerStatus.Loaded loaded = ControllerStatus.read(client);
		outcome._before = loaded.text();
		Answer answer;
		try {
			answer = client.deleteImage();
		} catch (IOException ex) {
			outcome._code = CODE_UNREACHABLE;
			outcome._message = text(I18NConstants.DEPLOY_UNREACHABLE__ADDRESS_ERROR.fill(client.address(), reason(ex)));
			return record(project, controller, client, null, loaded.revision(), now, outcome);
		}
		outcome._status = Integer.valueOf(answer.status());
		if (answer.status() == 200) {
			outcome._accepted = true;
			outcome._message = text(I18NConstants.UNLOAD_DONE__CONTROLLER.fill(((Controller) controller).getName()));
		} else {
			String code = answer.string("code");
			String error = answer.string("error");
			outcome._code = code != null ? code : "http." + answer.status();
			outcome._message = text(I18NConstants.UNLOAD_REFUSED__STATUS_ERROR.fill(Integer.valueOf(answer.status()),
				error != null ? error : answer.text().strip()));
		}
		return record(project, controller, client, null, loaded.revision(), now, outcome);
	}

	/**
	 * The code of the controller's refusal while it still stores the previous image (homecontrol #68).
	 */
	public static final String CODE_BUSY = "image.busy";

	/** How often an image refused as {@link #CODE_BUSY} is sent again. */
	static final int BUSY_RETRIES = 5;

	/** The wait before an image refused as {@link #CODE_BUSY} is sent again (the controller's Retry-After). */
	static final long BUSY_WAIT_MS = 1000;

	private static void send(ControllerClient client, byte[] image, Outcome outcome) {
		Answer answer;
		try {
			answer = client.putImage(image);
			// The controller is receiving another image (503 image.busy, Retry-After 1): the image is sent
			// again after a second, a few times.
			for (int retry = 0; retry < BUSY_RETRIES && answer.status() == 503 && CODE_BUSY.equals(answer.string("code"));
					retry++) {
				Thread.sleep(BUSY_WAIT_MS);
				answer = client.putImage(image);
			}
		} catch (IOException ex) {
			outcome._code = CODE_UNREACHABLE;
			outcome._message = text(I18NConstants.DEPLOY_UNREACHABLE__ADDRESS_ERROR.fill(client.address(), reason(ex)));
			return;
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			outcome._code = CODE_UNREACHABLE;
			outcome._message = text(I18NConstants.DEPLOY_UNREACHABLE__ADDRESS_ERROR.fill(client.address(),
				reason(new IOException("interrupted while the controller was busy", ex))));
			return;
		}
		outcome._status = Integer.valueOf(answer.status());
		if (answer.status() == 200) {
			outcome._accepted = true;
			outcome._loaded = Boolean.TRUE;
			outcome._message = "";
			return;
		}
		if (answer.body().get("loaded") instanceof Boolean loaded) {
			outcome._loaded = loaded;
		}
		String code = answer.string("code");
		String error = answer.string("error");
		outcome._code = code != null ? code : "http." + answer.status();
		outcome._message = text(I18NConstants.DEPLOY_REFUSED__STATUS_ERROR.fill(Integer.valueOf(answer.status()),
			error != null ? error : answer.text().strip()));
		if (CODE_UNSIGNED.equals(code) || CODE_BAD_SIGNATURE.equals(code)) {
			outcome._message += " " + text(outcome._fingerprint == null ? I18NConstants.DEPLOY_REFUSED_UNSIGNED
				: I18NConstants.DEPLOY_REFUSED_SIGNED__FINGERPRINT.fill(outcome._fingerprint));
		}
		if (answer.body().get("refusals") instanceof List<?> refusals) {
			for (Object entry : refusals) {
				if (entry instanceof Map<?, ?> refusal) {
					outcome._refusals.add(new Refusal(String.valueOf(refusal.get("path") == null ? "" : refusal.get("path")),
						String.valueOf(refusal.get("reason"))));
				}
			}
		}
		outcome._total = answer.body().get("total") instanceof Number total ? total.intValue() : outcome._refusals.size();
	}

	private static TLObject record(TLObject project, TLObject controller, ControllerClient client, RuntimeImage image,
			Long revision, Instant now, Outcome outcome) {
		try (Transaction tx = PersistencyLayer.getKnowledgeBase()
			.beginTransaction(commitMessage(project, controller, revision, outcome))) {
			Deployment deployment = (Deployment) create("ehome.runtime:Deployment");
			Deployment earlier = (Deployment) outcome._earlier;
			deployment.setUnload(outcome._unload);
			deployment.setRevision(revision);
			deployment.setTime(Date.from(now));
			deployment.setController((Controller) controller);
			deployment.setTarget(((Controller) controller).getName() + " (" + client.address() + ")");
			deployment.setAccepted(outcome._accepted);
			deployment.setStatus(outcome._status);
			deployment.setCode(outcome._code);
			// A deployment that goes back to an earlier one names it first (haumacher/ehome #192).
			deployment.setMessage(earlier == null ? outcome._message
				: text(I18NConstants.REDEPLOY_FROM__REVISION_TIME.fill(earlier.getRevision(), time(earlier.getTime())))
					+ " " + outcome._message);
			deployment.setBefore(outcome._before);
			deployment.setBytes(outcome._bytes);
			deployment.setTotal(Integer.valueOf(outcome._total));
			deployment.setSignature(outcome._signature);
			deployment.setKeyFingerprint(outcome._fingerprint);
			deployment.setSourceRevision(outcome._state == null ? null : Long.valueOf(outcome._state.commit()));
			deployment.setRedeployedFrom(earlier);
			List<de.haumacher.ehome.model.runtime.Refusal> refusals = new ArrayList<>();
			for (Refusal refusal : outcome._refusals) {
				de.haumacher.ehome.model.runtime.Refusal stored =
					(de.haumacher.ehome.model.runtime.Refusal) create("ehome.runtime:Refusal");
				stored.setPath(refusal.path());
				stored.setReason(refusal.reason());
				stored.setMessage(refusal.translations());
				// An image of an earlier state leads to the historic objects: the refusal names them as they are now.
				stored.setElement(image == null ? null : current(image.elementAt(refusal.path())));
				refusals.add(stored);
			}
			deployment.setRefusals(refusals);
			if (outcome._accepted && !outcome._unload && outcome._image != null) {
				// The last valid deployment of the controller from now on (haumacher/ehome #176).
				LastValidImages.store(controller, deployment, outcome._image);
			}
			List<Deployment> deployments = new ArrayList<>();
			deployments.add(deployment);
			deployments.addAll(((Project) project).getDeployments());
			((Project) project).setDeployments(deployments);
			if (outcome._accepted && !outcome._unload && !outcome._foreign && revision != null) {
				((Project) project).setImageRevision(revision);
			}
			tx.commit();
			return deployment;
		}
	}

	/**
	 * The commit message of the record of a deployment or an unload: what happened to which project on
	 * which controller (haumacher/ehome #143).
	 */
	private static ResKey commitMessage(TLObject project, TLObject controller, Long revision, Outcome outcome) {
		String projectLabel = MetaLabelProvider.INSTANCE.getLabel(project);
		String controllerName = String.valueOf(((Controller) controller).getName());

		if (outcome._unload) {
			return outcome._accepted ? I18NConstants.COMMIT_UNLOADED__PROJECT_CONTROLLER.fill(projectLabel, controllerName)
				: I18NConstants.COMMIT_UNLOAD_FAILED__PROJECT_CONTROLLER.fill(projectLabel, controllerName);
		}
		return outcome._accepted
			? I18NConstants.COMMIT_DEPLOYED__PROJECT_CONTROLLER_REVISION.fill(projectLabel, controllerName, revision)
			: I18NConstants.COMMIT_DEPLOY_FAILED__PROJECT_CONTROLLER.fill(projectLabel, controllerName);
	}

	/**
	 * The given object of the project as it is now: itself, or for a historic object its current
	 * version; <code>null</code> for an object deleted since.
	 */
	private static TLObject current(TLObject object) {
		if (object == null || WrapperHistoryUtils.isCurrent(object)) {
			return object;
		}
		TLObject current = WrapperHistoryUtils.getCurrent(object);
		return current != null && current.tValid() ? current : null;
	}

	/**
	 * The time of a deployment as the user reads it.
	 */
	public static String time(Object time) {
		return time instanceof Date date ? DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM,
			Resources.getInstance().getLocale()).format(date) : "?";
	}

	private static TLObject create(String type) {
		return ModelService.getInstance().getFactory().createObject((TLClass) TLModelUtil.findType(type));
	}

	private static Integer size(Object list) {
		return Integer.valueOf(list instanceof Collection<?> collection ? collection.size() : 0);
	}

	public static String reason(IOException ex) {
		String message = ex.getMessage();
		return message == null || message.isBlank() ? ex.getClass().getSimpleName() : message;
	}

	private static String text(ResKey key) {
		return Resources.getInstance().getString(key);
	}

	/**
	 * What came of a deployment, before it is recorded.
	 */
	private static final class Outcome {
		boolean _unload;

		/** Whether this is the last valid deployment sent again after a failed one. */
		boolean _resend;

		/** The state the image was compiled from, <code>null</code> for none (an unload, an image sent again). */
		State _state;

		/** The deployment deployed again, <code>null</code> for a deployment of the project as it is. */
		TLObject _earlier;

		/** Whether the image sent again was not deployed from this project (another tool, another project). */
		boolean _foreign;

		boolean _accepted;

		/**
		 * Whether the controller runs an image after the answer (<code>loaded</code> of a refusal);
		 * <code>null</code> when it did not say.
		 */
		Boolean _loaded;

		Integer _status;

		String _code;

		String _message;

		String _before;

		/** The binary image the controller accepted, kept with the deployment; <code>null</code> for none. */
		byte[] _image;

		/** The size of the binary image, <code>null</code> when none was compiled. */
		Integer _bytes;

		int _total;

		String _signature;

		String _fingerprint;

		final List<Refusal> _refusals = new ArrayList<>();
	}

}
