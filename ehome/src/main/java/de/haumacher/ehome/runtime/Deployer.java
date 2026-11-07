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
import java.util.List;
import java.util.Map;

import com.top_logic.basic.util.ResKey;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.model.TLClass;
import com.top_logic.model.TLObject;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.Resources;
import com.top_logic.util.error.TopLogicException;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.connection.ControllerConnection;
import de.haumacher.ehome.connection.ControllerConnections;
import de.haumacher.ehome.connection.InstallationKeys;
import de.haumacher.ehome.connection.Signatures;
import de.haumacher.ehome.connection.Signatures.Keys;
import de.haumacher.ehome.runtime.ControllerClient.Answer;
import de.haumacher.ehome.runtime.ImageReport.Refusal;

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
 * with the object of the project its path leads to ({@link RuntimeImage#elementAt(String)}).
 * </p>
 *
 * <p>
 * The image is signed with the key the controller holds ({@link InstallationKeys#signer(TLObject,
 * TLObject)}, homecontrol issue #35) and sent with its signature; the deployment records the signature
 * and the key's fingerprint. A project without a key deploys unsigned. A device refusing the signature
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
	 * The controller the project is deployed to, <code>null</code> if none is chosen.
	 */
	public static TLObject controller(TLObject project) {
		return project.tValueByName("controller") instanceof TLObject controller && controller.tValid() ? controller
			: null;
	}

	/**
	 * The client talking to the given controller.
	 */
	public static ControllerClient client(TLObject controller) {
		ControllerConnection connection = ControllerConnections.get(controller);
		if (connection != null) {
			return ControllerClient.over(connection, text(I18NConstants.ADDRESS_CONNECTION), TIMEOUT);
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
		outcome._bytes = image.getBytes().length;
		if (!report.isValid()) {
			outcome._code = CODE_INVALID;
			outcome._message = text(I18NConstants.DEPLOY_REFUSED_LOCALLY__COUNT.fill(report.getRefusals().size()));
			outcome._refusals.addAll(report.getRefusals());
			outcome._total = report.getRefusals().size();
		} else if (blocksRefusal(image, controller) != null) {
			// An older firmware would ignore the blocks and run none (homecontrol spec/blocks.md B1): nothing is sent.
			outcome._code = CODE_SCHEMA;
			outcome._message = text(blocksRefusal(image, controller));
		} else if (pressesRefusal(image, controller) != null) {
			// An older firmware refuses an enumeration press, so the whole image (homecontrol
			// spec/runtime-image.md 3.5, 1.4): nothing is sent.
			outcome._code = CODE_SCHEMA;
			outcome._message = text(pressesRefusal(image, controller));
		} else {
			ControllerStatus.Loaded before = ControllerStatus.read(client);
			outcome._before = before.text();
			outcome._storable = before.storable();
			Keys signer = InstallationKeys.signer(project, controller);
			if (signer != null) {
				outcome._signature = Signatures.signImage(signer.seed(), image.getBytes());
				outcome._fingerprint = signer.fingerprint();
			}
			send(client, image, revision, outcome);
		}
		return record(project, controller, client, image, Long.valueOf(revision), now, outcome);
	}

	/**
	 * The warning that an image of the given size is larger than the controller can store and load
	 * again (<code>image.storable</code> of its status, homecontrol #52): it is accepted and runs, but may
	 * not survive a restart of the controller.
	 *
	 * @param bytes
	 *        The size of the image.
	 * @param storable
	 *        What the controller reports, <code>null</code> when it reports nothing (no warning).
	 * @return The warning; <code>null</code> when the image fits or the controller reports no limit.
	 */
	public static ResKey storableWarning(int bytes, Long storable) {
		if (storable == null || bytes <= storable.longValue()) {
			return null;
		}
		return I18NConstants.CONFIRM_TOO_LARGE__BYTES_STORABLE.fill(Integer.valueOf(bytes), storable);
	}

	/** The code of a deployment E-Home refused because the controller reads a schema without blocks. */
	public static final String CODE_SCHEMA = "ehome.schema";

	/** The schema that brings the blocks (homecontrol spec/blocks.md B1). */
	static final int[] BLOCKS_SCHEMA = { 1, 3 };

	/**
	 * Why the image cannot go to the controller (homecontrol <code>spec/blocks.md</code> B1): an image
	 * with blocks needs a controller that reports schema 1.3 or newer; one that reports none counts as
	 * at most 1.1. <code>null</code> for an image without blocks or a controller that reads them.
	 */
	static ResKey blocksRefusal(RuntimeImage image, TLObject controller) {
		if (!(image.getJson().get("blocks") instanceof List<?> blocks) || blocks.isEmpty()) {
			return null;
		}
		return blocksRefusal(ImageComparison.reportedSchema(controller), controller.tValueByName("name"));
	}

	/**
	 * Why an image with blocks cannot go to a controller reporting the schema (<code>null</code> or
	 * empty: none), <code>null</code> when it reads blocks.
	 */
	public static ResKey blocksRefusal(String reported, Object controller) {
		Boolean reads = reads(reported, BLOCKS_SCHEMA);
		if (reads == null) {
			return I18NConstants.DEPLOY_REFUSED_BLOCKS_NO_SCHEMA__CONTROLLER.fill(controller);
		}
		return reads.booleanValue() ? null
			: I18NConstants.DEPLOY_REFUSED_BLOCKS_SCHEMA__CONTROLLER_SCHEMA.fill(controller, reported.trim());
	}

	/** The schema that brings the enumeration press of a button (homecontrol spec/runtime-image.md 3.5). */
	static final int[] PRESSES_SCHEMA = { 1, 4 };

	/**
	 * Why the image cannot go to the controller (homecontrol <code>spec/runtime-image.md</code> 3.5,
	 * haumacher/ehome #70): an image with a button whose press is an enumeration (a Zigbee remote, the
	 * function's <code>presses</code>) needs a controller that reports schema 1.4 or newer, since an
	 * older one refuses the whole image. <code>null</code> for an image without one or a controller
	 * that reads it.
	 */
	static ResKey pressesRefusal(RuntimeImage image, TLObject controller) {
		if (!(image.getJson().get("functions") instanceof List<?> functions)
			|| functions.stream().noneMatch(f -> f instanceof Map<?, ?> map && map.containsKey("presses"))) {
			return null;
		}
		return pressesRefusal(ImageComparison.reportedSchema(controller), controller.tValueByName("name"));
	}

	/**
	 * Why an image with an enumeration press cannot go to a controller reporting the schema
	 * (<code>null</code> or empty: none), <code>null</code> when it reads it.
	 */
	public static ResKey pressesRefusal(String reported, Object controller) {
		Boolean reads = reads(reported, PRESSES_SCHEMA);
		if (reads == null) {
			return I18NConstants.DEPLOY_REFUSED_PRESSES_NO_SCHEMA__CONTROLLER.fill(controller);
		}
		return reads.booleanValue() ? null
			: I18NConstants.DEPLOY_REFUSED_PRESSES_SCHEMA__CONTROLLER_SCHEMA.fill(controller, reported.trim());
	}

	/**
	 * Whether the reported schema (<code>major.minor</code>) is the given one or newer;
	 * <code>null</code> when none is reported or it is no version.
	 */
	private static Boolean reads(String reported, int[] schema) {
		String[] parts = reported == null ? new String[0] : reported.trim().split("\\.");
		if (parts.length != 2) {
			return null;
		}
		try {
			int major = Integer.parseInt(parts[0]);
			int minor = Integer.parseInt(parts[1]);
			return Boolean.valueOf(major > schema[0] || (major == schema[0] && minor >= schema[1]));
		} catch (NumberFormatException ex) {
			return null;
		}
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

	private static void send(ControllerClient client, RuntimeImage image, long revision, Outcome outcome) {
		Answer answer;
		try {
			answer = client.putImage(image.getBytes(), outcome._signature);
			// The controller still stores the previous image (homecontrol #68: 503 image.busy, Retry-After 1):
			// a store takes under a second, so the image is sent again after a second, a few times.
			for (int retry = 0; retry < BUSY_RETRIES && answer.status() == 503 && CODE_BUSY.equals(answer.string("code"));
					retry++) {
				Thread.sleep(BUSY_WAIT_MS);
				answer = client.putImage(image.getBytes(), outcome._signature);
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
			Map<String, Object> json = image.getJson();
			String message = text(I18NConstants.DEPLOY_ACCEPTED__REVISION_DATAPOINTS_LOCATIONS_BYTES.fill(
				Long.valueOf(revision), size(json.get("datapoints")), size(json.get("locations")),
				Integer.valueOf(outcome._bytes)));
			if (image.getUnusedFunctions() > 0) {
				message += " " + text(I18NConstants.EXPORT_UNUSED__COUNT.fill(Integer.valueOf(image.getUnusedFunctions())));
			}
			if (Boolean.FALSE.equals(answer.body().get("stored"))) {
				message += " " + text(I18NConstants.DEPLOY_NOT_STORED__ERROR.fill(answer.string("storeError")));
			} else {
				// Stored, but beyond what the controller is sure to load again (its staging, homecontrol #52).
				ResKey warning = storableWarning(outcome._bytes, outcome._storable);
				if (warning != null) {
					message += " " + text(warning);
				}
			}
			outcome._message = message;
			return;
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
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
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
			deployment.tUpdateByName("bytes", image == null ? null : Integer.valueOf(outcome._bytes));
			deployment.tUpdateByName("total", Integer.valueOf(outcome._total));
			deployment.tUpdateByName("signature", outcome._signature);
			deployment.tUpdateByName("keyFingerprint", outcome._fingerprint);
			List<TLObject> refusals = new ArrayList<>();
			for (Refusal refusal : outcome._refusals) {
				TLObject stored = create("ehome.runtime:Refusal");
				stored.tUpdateByName("path", refusal.path());
				stored.tUpdateByName("reason", refusal.reason());
				stored.tUpdateByName("message", refusal.translations());
				stored.tUpdateByName("element", image.elementAt(refusal.path()));
				refusals.add(stored);
			}
			deployment.tUpdateByName("refusals", refusals);
			List<Object> deployments = new ArrayList<>();
			deployments.add(deployment);
			if (project.tValueByName("deployments") instanceof Collection<?> before) {
				deployments.addAll(before);
			}
			project.tUpdateByName("deployments", deployments);
			if (outcome._accepted && !outcome._unload) {
				project.tUpdateByName("imageRevision", revision);
			}
			tx.commit();
			return deployment;
		}
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

		boolean _accepted;

		Integer _status;

		String _code;

		String _message;

		String _before;

		int _bytes;

		/** What the controller reported it can store and load again before the image was sent. */
		Long _storable;

		int _total;

		String _signature;

		String _fingerprint;

		final List<Refusal> _refusals = new ArrayList<>();
	}

}
