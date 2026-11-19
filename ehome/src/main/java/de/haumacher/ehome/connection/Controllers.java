/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.connection;

import java.text.DateFormat;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import com.top_logic.basic.util.ResKey;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.model.TLObject;
import com.top_logic.model.TLReference;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.Resources;

import de.haumacher.ehome.model.project.Project;
import de.haumacher.ehome.model.runtime.Controller;
import de.haumacher.ehome.model.runtime.Deployment;

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
		Object contact = ((Controller) controller).getLastContact();
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
		Object expires = ((Project) project).getEnrollmentExpires();
		if (!(expires instanceof Date date) || ((Project) project).getEnrollmentCode() == null) {
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
	 * of projects (a project's only other controller becomes its target instead), as the controller
	 * a project is programmed through (none takes its place), and from the deployments that name it
	 * (they keep its name and address in their target).
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
			.beginTransaction(I18NConstants.COMMIT_CONTROLLER_FORGOTTEN__CONTROLLER.fill(String.valueOf(((Controller) controller).getName())))) {
			// The objects naming the controller, read backwards from it (haumacher/ehome #172): no scan of
			// the projects.
			for (TLObject project : referers(controller, "ehome.project:Project#controller")) {
				((Project) project).setController(soleOther((Project) project, controller));
			}
			// The path to the bus is the installer's explicit choice (haumacher/ehome #148): nothing
			// takes the forgotten controller's place.
			for (TLObject project : referers(controller, "ehome.project:Project#programmingController")) {
				((Project) project).setProgrammingController(null);
			}
			for (TLObject deployment : referers(controller, "ehome.runtime:Deployment#controller")) {
				((Deployment) deployment).setController(null);
			}
			controller.tDelete();
			tx.commit();
		}
	}

	/**
	 * The valid objects referencing the given one by the reference of the given qualified name.
	 */
	private static List<TLObject> referers(TLObject target, String reference) {
		List<TLObject> result = new ArrayList<>();
		for (TLObject referer : target.tReferers((TLReference) TLModelUtil.findPart(reference))) {
			if (referer.tValid()) {
				result.add(referer);
			}
		}
		return result;
	}

	/**
	 * The project's only controller besides the given one, <code>null</code> if it has none or
	 * several: after the deployment target is forgotten, a single remaining controller (typically the
	 * same device enrolled again) takes its place; among several, the choice stays the installer's.
	 */
	private static Controller soleOther(Project project, TLObject controller) {
		Controller result = null;
		for (Controller other : project.getControllers()) {
			if (other != null && other != controller && other.tValid()) {
				if (result != null) {
					return null;
				}
				result = other;
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
