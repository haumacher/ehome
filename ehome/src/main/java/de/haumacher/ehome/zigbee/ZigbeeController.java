/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.zigbee;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.top_logic.basic.util.ResKey;
import com.top_logic.element.meta.MetaElementUtil;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.model.TLClass;
import com.top_logic.model.TLObject;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.Resources;
import com.top_logic.util.error.TopLogicException;

import de.haumacher.ehome.connection.ControllerConnections;
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
	 * own; the network facts of the export are stored on the controller ({@link #storeNetwork}).
	 *
	 * @return The sentence saying what was imported, followed by the notes of the import.
	 */
	public static String read(TLObject project) {
		TLObject controller = controller(project);
		ZigbeeExport.Export export = ZigbeeExport.read(fetch(controller));
		String result;
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			result = ZigbeeImport.importExport(project, export).summary();
			storeNetwork(controller, export, false);
			tx.commit();
		}
		ZigbeeLive.imported(ControllerConnections.controllerId(controller), export, false);
		return result;
	}

	/**
	 * Stores the network of the export and the time of the read on the controller; must run in a
	 * transaction. Only what changes is written.
	 *
	 * @param live
	 *        Whether the read followed the controller's events ({@link ZigbeeLive}), not a user's request.
	 */
	public static void storeNetwork(TLObject controller, ZigbeeExport.Export export, boolean live) {
		ZigbeeExport.Network network = export.network();
		set(controller, "zigbeeNetworkUp", network == null ? null : network.up());
		set(controller, "zigbeePanId", network == null ? null : network.panId());
		set(controller, "zigbeeChannel", network == null ? null : network.channel());
		set(controller, "zigbeeExtendedPanId", network == null ? null : network.extendedPanId());
		set(controller, "zigbeePermitJoin", network == null ? null : network.permitJoinSeconds());
		Date exported = export.exported() != null ? new Date(export.exported().longValue() * 1000) : new Date();
		set(controller, "zigbeeExported", exported);
		set(controller, "zigbeeReadLive", Boolean.valueOf(live));
	}

	private static void set(TLObject obj, String attribute, Object value) {
		Object before = obj.tValueByName(attribute);
		Object a = before instanceof Number number ? Long.valueOf(number.longValue()) : before;
		Object b = value instanceof Number number ? Long.valueOf(number.longValue()) : value;
		if (!Objects.equals(a, b)) {
			obj.tUpdateByName(attribute, value);
		}
	}

	/**
	 * The projects deploying to the controller (whose <code>controller</code> it is), in no particular
	 * order.
	 */
	public static List<TLObject> projects(TLObject controller) {
		List<TLObject> result = new ArrayList<>();
		TLClass type = (TLClass) TLModelUtil.findType("ehome.project:Project");
		for (TLObject project : MetaElementUtil.getAllInstancesOf(type, TLObject.class)) {
			if (project.tValid() && project.tValueByName("controller") == controller) {
				result.add(project);
			}
		}
		return result;
	}

	/**
	 * The address a controller reached over its E-Home connection is shown with.
	 */
	public static String connectionLabel() {
		return Resources.getInstance().getString(de.haumacher.ehome.runtime.I18NConstants.ADDRESS_CONNECTION);
	}

	/**
	 * The pages of the controller's export joined into one export, as the controller's Zigbee page
	 * downloads it, see {@link #fetch(String, ControllerClient)}.
	 */
	public static Map<String, Object> fetch(TLObject controller) {
		return fetch(String.valueOf(controller.tValueByName("name")), Deployer.client(controller));
	}

	/**
	 * The pages of the export of the controller of the given name, read through the client, joined
	 * into one export as the controller's Zigbee page downloads it: <code>format</code>,
	 * <code>version</code>, <code>controller</code>, <code>exported</code> and <code>network</code> of
	 * the first page, the devices of all.
	 *
	 * @throws TopLogicException
	 *         If the controller cannot be reached or refuses, with the reason.
	 */
	public static Map<String, Object> fetch(String name, ControllerClient client) {
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
