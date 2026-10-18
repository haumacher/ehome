/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.zigbee;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.top_logic.basic.util.ResKey;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.model.TLObject;
import com.top_logic.util.Resources;
import com.top_logic.util.error.TopLogicException;

import de.haumacher.ehome.runtime.ControllerClient;
import de.haumacher.ehome.runtime.ControllerClient.Answer;
import de.haumacher.ehome.runtime.Deployer;

/**
 * The Zigbee coordinator of a project's controller, over its API (homecontrol <code>spec/api.md</code>,
 * "Zigbee"; haumacher/ehome #40): opening the network for pairing and reading the paired devices into
 * the project.
 *
 * <p>
 * E-Home writes nothing else to the controller's device database: the controller owns the keys, the
 * addresses and the database; the project gets what the export carries ({@link ZigbeeImport}).
 * Every refusal of the controller is a visible error: an unreachable controller, a refused token
 * (401), a controller without a coordinator (501 <code>system.unavailable</code> with its reason), any
 * other refusal with its status and reason.
 * </p>
 */
public final class ZigbeeController {

	/** The time a network is opened for, the most the controller allows. */
	public static final int PERMIT_SECONDS = 254;

	/** The most pages of the export read (16 devices each; a controller keeps at most 32). */
	private static final int MAX_PAGES = 64;

	private ZigbeeController() {
		// Static helpers.
	}

	/**
	 * Opens the Zigbee network of the controller for joining for the given time, or closes it (0).
	 *
	 * @param obj
	 *        The controller (<code>ehome.runtime:Controller</code>) or a project
	 *        (<code>ehome.project:Project</code>), whose controller is meant.
	 * @return The sentence saying what the controller did.
	 */
	public static String permit(TLObject obj, int seconds) {
		TLObject controller = controller(obj);
		String name = String.valueOf(controller.tValueByName("name"));
		ControllerClient client = Deployer.client(controller);
		Answer answer;
		try {
			answer = client.zigbeePermit(seconds);
		} catch (IOException ex) {
			throw unreachable(client, ex);
		}
		check(name, answer);
		Resources resources = Resources.getInstance();
		if (seconds == 0) {
			return resources.getString(I18NConstants.PERMIT_CLOSED__CONTROLLER.fill(name));
		}
		Object open = answer.body().get("seconds") instanceof Number number ? Long.valueOf(number.longValue())
			: Integer.valueOf(seconds);
		return resources.getString(I18NConstants.PERMIT_OPEN__CONTROLLER_SECONDS.fill(name, open));
	}

	/**
	 * Reads the Zigbee devices of the project's controller (all pages of
	 * <code>GET /api/zigbee/export</code>) and imports them into the project, in a transaction of its
	 * own.
	 *
	 * @return The sentence saying what was imported, followed by the notes of the import.
	 */
	public static String read(TLObject project) {
		TLObject controller = controller(project);
		ZigbeeExport.Export export = ZigbeeExport.read(fetch(controller));
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			ZigbeeImport.Result result = ZigbeeImport.importExport(project, export);
			tx.commit();
			return result.summary();
		}
	}

	/**
	 * The pages of the controller's export joined into one export, as the controller's Zigbee page
	 * downloads it: <code>format</code>, <code>version</code>, <code>controller</code>,
	 * <code>exported</code> and <code>network</code> of the first page, the devices of all.
	 */
	public static Map<String, Object> fetch(TLObject controller) {
		String name = String.valueOf(controller.tValueByName("name"));
		ControllerClient client = Deployer.client(controller);
		Map<String, Object> result = new LinkedHashMap<>();
		List<Object> devices = new ArrayList<>();
		int from = 0;
		for (int page = 0; page < MAX_PAGES; page++) {
			Answer answer;
			try {
				answer = client.zigbeeExport(from);
			} catch (IOException ex) {
				throw unreachable(client, ex);
			}
			check(name, answer);
			Map<String, Object> body = answer.body();
			if (page == 0) {
				for (String member : List.of("format", "version", "controller", "exported", "network")) {
					if (body.containsKey(member)) {
						result.put(member, body.get(member));
					}
				}
			}
			if (body.get("devices") instanceof List<?> list) {
				devices.addAll(list);
			} else if (body.containsKey("devices")) {
				throw new TopLogicException(I18NConstants.ERROR_BAD_ANSWER__CONTROLLER_DETAIL.fill(name,
					"devices is no array"));
			}
			if (!(body.get("next") instanceof Number next)) {
				result.put("devices", devices);
				return result;
			}
			if (next.intValue() <= from) {
				throw new TopLogicException(I18NConstants.ERROR_BAD_ANSWER__CONTROLLER_DETAIL.fill(name,
					"the next page " + next + " does not follow the page " + from));
			}
			from = next.intValue();
		}
		throw new TopLogicException(I18NConstants.ERROR_BAD_ANSWER__CONTROLLER_DETAIL.fill(name,
			"more than " + MAX_PAGES + " pages"));
	}

	private static TLObject controller(TLObject obj) {
		if ("Controller".equals(obj.tType().getName())) {
			ResKey veto = Deployer.controllerVeto(obj);
			if (veto != null) {
				throw new TopLogicException(veto);
			}
			return obj;
		}
		ResKey veto = Deployer.veto(obj);
		if (veto != null) {
			throw new TopLogicException(veto);
		}
		return Deployer.controller(obj);
	}

	private static TopLogicException unreachable(ControllerClient client, IOException ex) {
		return new TopLogicException(
			I18NConstants.ERROR_UNREACHABLE__ADDRESS_ERROR.fill(client.address(), Deployer.reason(ex)), ex);
	}

	private static void check(String name, Answer answer) {
		if (answer.ok()) {
			return;
		}
		String error = answer.string("error");
		String code = answer.string("code");
		String reason = error != null ? error : answer.text().strip();
		switch (answer.status()) {
			case 401:
				throw new TopLogicException(I18NConstants.ERROR_TOKEN__CONTROLLER_ERROR.fill(name,
					code != null ? code : reason));
			case 501:
				throw new TopLogicException(I18NConstants.ERROR_UNAVAILABLE__CONTROLLER_REASON.fill(name, reason));
			default:
				throw new TopLogicException(I18NConstants.ERROR_REFUSED__CONTROLLER_STATUS_ERROR.fill(name,
					Integer.valueOf(answer.status()), code != null ? reason + " (" + code + ")" : reason));
		}
	}

}
