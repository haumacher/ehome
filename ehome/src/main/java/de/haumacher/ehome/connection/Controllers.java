/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.connection;

import java.text.DateFormat;
import java.time.Instant;
import java.util.Collection;
import java.util.Date;

import com.top_logic.basic.util.ResKey;
import com.top_logic.element.meta.MetaElementUtil;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.model.TLClass;
import com.top_logic.model.TLObject;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.Resources;

/**
 * The controllers of the E-Home connection as the views show and change them (haumacher/ehome #54):
 * their connection state and the forgetting of one.
 */
public final class Controllers {

	private Controllers() {
		// Static helpers.
	}

	/**
	 * Whether the controller is connected to E-Home now.
	 */
	public static boolean online(TLObject controller) {
		return ControllerConnections.get(controller) != null;
	}

	/**
	 * The connection state of the controller, for a person: online since when, offline with the last
	 * contact, not connected yet, or reached on the LAN (a controller that is not enrolled).
	 */
	public static String state(TLObject controller) {
		if (ControllerConnections.controllerId(controller) == null) {
			return text(I18NConstants.STATE_LAN);
		}
		ControllerConnection connection = ControllerConnections.get(controller);
		if (connection != null) {
			return text(I18NConstants.STATE_ONLINE__SINCE.fill(format(Date.from(connection.since()))));
		}
		Object contact = controller.tValueByName("lastContact");
		if (contact instanceof Date date) {
			return text(I18NConstants.STATE_OFFLINE__LAST_CONTACT.fill(format(date)));
		}
		return text(I18NConstants.STATE_NEVER_CONNECTED);
	}

	/**
	 * The state of the project's enrollment code, for a person (without the code itself, which only
	 * the dialog creating it shows): open until when, or expired when; <code>null</code> for none.
	 */
	public static String codeState(TLObject project, Instant now) {
		Object expires = project.tValueByName("enrollmentExpires");
		if (!(expires instanceof Date date) || project.tValueByName("enrollmentCode") == null) {
			return null;
		}
		ResKey key = date.toInstant().isAfter(now) ? I18NConstants.CODE_OPEN__UNTIL.fill(format(date))
			: I18NConstants.CODE_EXPIRED__AT.fill(format(date));
		return text(key);
	}

	/**
	 * Forgets the controller: closes its connection with {@link ControllerConnection#REFUSED_FORGOTTEN}
	 * and deletes it, in a transaction of its own on the thread of
	 * {@link ControllerConnections#executeAndWait(Runnable)}, after removing it as the deployment target
	 * of projects (a project's only other controller becomes its target instead) and from the
	 * deployments that name it (they keep its name and address in their target).
	 */
	public static void forget(TLObject controller) {
		String id = ControllerConnections.controllerId(controller);
		// Closed first, and deleted on the thread that stores the connections' contacts, after them:
		// a contact stored meanwhile would conflict with the deletion.
		ControllerConnections.forget(id);
		ControllerConnections.executeAndWait(() -> delete(controller));
		// A connection that came back before the deletion.
		ControllerConnections.forget(id);
	}

	private static void delete(TLObject controller) {
		if (!controller.tValid()) {
			return;
		}
		try (Transaction tx = PersistencyLayer.getKnowledgeBase()
			.beginTransaction(I18NConstants.COMMIT_CONTROLLER_FORGOTTEN__CONTROLLER.fill(String.valueOf(controller.tValueByName("name"))))) {
			TLClass projectType = (TLClass) TLModelUtil.findType("ehome.project:Project");
			for (TLObject project : MetaElementUtil.getAllDirectInstancesOf(projectType, TLObject.class)) {
				if (project.tValueByName("controller") == controller) {
					project.tUpdateByName("controller", soleOther(project, controller));
				}
				if (project.tValueByName("deployments") instanceof Collection<?> deployments) {
					for (Object entry : deployments) {
						if (entry instanceof TLObject deployment && deployment.tValueByName("controller") == controller) {
							deployment.tUpdateByName("controller", null);
						}
					}
				}
			}
			controller.tDelete();
			tx.commit();
		}
	}

	/**
	 * The project's only controller besides the given one, <code>null</code> if it has none or
	 * several: after the deployment target is forgotten, a single remaining controller (typically the
	 * same device enrolled again) takes its place; among several, the choice stays the installer's.
	 */
	private static TLObject soleOther(TLObject project, TLObject controller) {
		TLObject result = null;
		if (project.tValueByName("controllers") instanceof Collection<?> controllers) {
			for (Object entry : controllers) {
				if (entry instanceof TLObject other && other != controller && other.tValid()) {
					if (result != null) {
						return null;
					}
					result = other;
				}
			}
		}
		return result;
	}

	private static String format(Date date) {
		Resources resources = Resources.getInstance();
		return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, resources.getLocale()).format(date);
	}

	private static String text(ResKey key) {
		return Resources.getInstance().getString(key);
	}

}
