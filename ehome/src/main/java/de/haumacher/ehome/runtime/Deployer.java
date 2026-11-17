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
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
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
		if (project.tValueByName("controller") instanceof TLObject target && target.tValid()) {
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
		Object name = controller.tValueByName("name");
		boolean host = !blank(controller.tValueByName("host"));
		boolean token = !blank(controller.tValueByName("token"));
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
		Object name = controller.tValueByName("name");
		Object contact = controller.tValueByName("lastContact");
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
		return project.tValueByName("controller") instanceof TLObject controller
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
		Object host = controller.tValueByName("host");
		Object port = controller.tValueByName("port");
		Object token = controller.tValueByName("token");
		if (blank(host) || (ControllerConnections.controllerId(controller) != null && blank(token))) {
			ResKey reason = ControllerConnections.controllerId(controller) != null ? offline(controller)
				: I18NConstants.ERROR_NO_HOST__CONTROLLER.fill(controller.tValueByName("name"));
			return ControllerClient.offline(text(I18NConstants.ADDRESS_OFFLINE), text(reason), TIMEOUT);
		}
		return new ControllerClient(host.toString().trim(),
			port instanceof Number number ? number.intValue() : 80, token == null ? null : token.toString(), TIMEOUT);
	}

	/**
	 * The revision the next deployment of the project carries.
	 */
	public static long nextRevision(TLObject project) {
		return project.tValueByName("imageRevision") instanceof Number last ? last.longValue() + 1 : 1;
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
		TLObject controller = controller(project);
		ControllerClient client = client(controller);
		long revision = nextRevision(project);
		RuntimeImage image = RuntimeImage.build(project, revision, now);
		ImageReport report = image.validate();
		Outcome outcome = new Outcome();
		if (!report.isValid()) {
			outcome._code = CODE_INVALID;
			outcome._message = text(I18NConstants.DEPLOY_REFUSED_LOCALLY__COUNT.fill(report.getRefusals().size()));
			outcome._refusals.addAll(report.getRefusals());
			outcome._total = report.getRefusals().size();
			return record(project, controller, client, image, Long.valueOf(revision), now, outcome);
		}
		ControllerStatus.Loaded before = ControllerStatus.read(client);
		outcome._before = before.text();
		ResKey formatRefusal = formatRefusal(reportedFormat(client, before), controller.tValueByName("name"),
			ImageCompiler.formatMinor(image.getJson()));
		if (before.answered() && formatRefusal != null) {
			// A firmware reading JSON images (or another major format) cannot load the binary image: nothing is sent.
			outcome._code = CODE_FORMAT;
			outcome._message = text(formatRefusal);
			return record(project, controller, client, image, Long.valueOf(revision), now, outcome);
		}
		Keys signer = InstallationKeys.signer(project, controller);
		byte[] binary;
		try {
			binary = image.compile(signer == null ? null : signer.seed());
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
	 * Why the binary image cannot go to a controller reporting the given format (homecontrol
	 * <code>spec/runtime-image.md</code> section 11): a controller reporting none runs a firmware
	 * that reads JSON images (up to 1.2), one of another major format a firmware E-Home cannot write
	 * for, one of an older minor format than the image is written in (format 1.1 for the anonymous
	 * pages, haumacher/ehome #184) a firmware that does not know its sections; <code>null</code> when
	 * it reads the image's format. There is no JSON fallback: every controller is flashed with a
	 * firmware reading the binary image.
	 *
	 * @param imageMinor
	 *        The minor version of the format the image is written in
	 *        ({@link de.haumacher.ehome.runtime.binary.ImageCompiler#formatMinor(Map)}).
	 */
	public static ResKey formatRefusal(String reported, Object controller, int imageMinor) {
		if (reported == null || reported.isBlank()) {
			return I18NConstants.DEPLOY_REFUSED_NO_FORMAT__CONTROLLER.fill(controller);
		}
		String[] parts = reported.trim().split("\\.");
		try {
			if (parts.length == 2 && Integer.parseInt(parts[0]) == Layout.MAJOR && Integer.parseInt(parts[1]) >= imageMinor) {
				return null;
			}
		} catch (NumberFormatException ex) {
			// Refused below.
		}
		return I18NConstants.DEPLOY_REFUSED_FORMAT__CONTROLLER_FORMAT_IMAGE.fill(controller, reported.trim(),
			Layout.MAJOR + "." + imageMinor);
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
			outcome._message = text(I18NConstants.UNLOAD_DONE__CONTROLLER.fill(controller.tValueByName("name")));
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
			TLObject deployment = create("ehome.runtime:Deployment");
			deployment.tUpdateByName("unload", Boolean.valueOf(outcome._unload));
			deployment.tUpdateByName("revision", revision);
			deployment.tUpdateByName("time", Date.from(now));
			deployment.tUpdateByName("controller", controller);
			deployment.tUpdateByName("target", controller.tValueByName("name") + " (" + client.address() + ")");
			deployment.tUpdateByName("accepted", Boolean.valueOf(outcome._accepted));
			deployment.tUpdateByName("status", outcome._status);
			deployment.tUpdateByName("code", outcome._code);
			deployment.tUpdateByName("message", outcome._message);
			deployment.tUpdateByName("before", outcome._before);
			deployment.tUpdateByName("bytes", outcome._bytes);
			deployment.tUpdateByName("total", Integer.valueOf(outcome._total));
			deployment.tUpdateByName("signature", outcome._signature);
			deployment.tUpdateByName("keyFingerprint", outcome._fingerprint);
			List<TLObject> refusals = new ArrayList<>();
			for (Refusal refusal : outcome._refusals) {
				TLObject stored = create("ehome.runtime:Refusal");
				stored.tUpdateByName("path", refusal.path());
				stored.tUpdateByName("reason", refusal.reason());
				stored.tUpdateByName("message", refusal.translations());
				stored.tUpdateByName("element", image == null ? null : image.elementAt(refusal.path()));
				refusals.add(stored);
			}
			deployment.tUpdateByName("refusals", refusals);
			if (outcome._accepted && !outcome._unload && outcome._image != null) {
				// The last valid deployment of the controller from now on (haumacher/ehome #176).
				LastValidImages.store(controller, deployment, outcome._image);
			}
			List<Object> deployments = new ArrayList<>();
			deployments.add(deployment);
			if (project.tValueByName("deployments") instanceof Collection<?> before) {
				deployments.addAll(before);
			}
			project.tUpdateByName("deployments", deployments);
			if (outcome._accepted && !outcome._unload && !outcome._foreign && revision != null) {
				project.tUpdateByName("imageRevision", revision);
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
		String controllerName = String.valueOf(controller.tValueByName("name"));
		if (outcome._unload) {
			return outcome._accepted ? I18NConstants.COMMIT_UNLOADED__PROJECT_CONTROLLER.fill(projectLabel, controllerName)
				: I18NConstants.COMMIT_UNLOAD_FAILED__PROJECT_CONTROLLER.fill(projectLabel, controllerName);
		}
		return outcome._accepted
			? I18NConstants.COMMIT_DEPLOYED__PROJECT_CONTROLLER_REVISION.fill(projectLabel, controllerName, revision)
			: I18NConstants.COMMIT_DEPLOY_FAILED__PROJECT_CONTROLLER.fill(projectLabel, controllerName);
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
