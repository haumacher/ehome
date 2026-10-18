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
		} else {
			outcome._before = ControllerStatus.describe(client);
			send(client, image, revision, outcome);
		}
		return record(project, controller, client, image, Long.valueOf(revision), now, outcome);
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

	private static void send(ControllerClient client, RuntimeImage image, long revision, Outcome outcome) {
		Answer answer;
		try {
			answer = client.putImage(image.getBytes());
		} catch (IOException ex) {
			outcome._code = CODE_UNREACHABLE;
			outcome._message = text(I18NConstants.DEPLOY_UNREACHABLE__ADDRESS_ERROR.fill(client.address(), reason(ex)));
			return;
		}
		outcome._status = Integer.valueOf(answer.status());
		if (answer.status() == 200) {
			outcome._accepted = true;
			Map<String, Object> json = image.getJson();
			String message = text(I18NConstants.DEPLOY_ACCEPTED__REVISION_DATAPOINTS_LOCATIONS_BYTES.fill(
				Long.valueOf(revision), size(json.get("datapoints")), size(json.get("locations")),
				Integer.valueOf(outcome._bytes)));
			if (Boolean.FALSE.equals(answer.body().get("stored"))) {
				message += " " + text(I18NConstants.DEPLOY_NOT_STORED__ERROR.fill(answer.string("storeError")));
			}
			outcome._message = message;
			return;
		}
		String code = answer.string("code");
		String error = answer.string("error");
		outcome._code = code != null ? code : "http." + answer.status();
		outcome._message = text(I18NConstants.DEPLOY_REFUSED__STATUS_ERROR.fill(Integer.valueOf(answer.status()),
			error != null ? error : answer.text().strip()));
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
			List<TLObject> refusals = new ArrayList<>();
			for (Refusal refusal : outcome._refusals) {
				TLObject stored = create("ehome.runtime:Refusal");
				stored.tUpdateByName("path", refusal.path());
				stored.tUpdateByName("reason", refusal.reason());
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

		int _total;

		final List<Refusal> _refusals = new ArrayList<>();
	}

}
