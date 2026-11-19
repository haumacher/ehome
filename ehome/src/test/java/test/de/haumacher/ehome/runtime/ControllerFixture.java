/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.runtime;

import java.util.ArrayList;
import java.util.List;

import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.model.TLObject;

import de.haumacher.ehome.connection.ProjectControllers;
import de.haumacher.ehome.model.project.Project;
import de.haumacher.ehome.model.runtime.Controller;

/**
 * Makes a controller a test created one of a project's (haumacher/ehome #151: every controller is a
 * part of exactly one project, and a project deploys to its own controllers only).
 */
public final class ControllerFixture {

	private ControllerFixture() {
		// Static helpers.
	}

	/**
	 * Makes the controller one of the project's controllers and the project's deployment target,
	 * inside the caller's transaction.
	 */
	public static void own(TLObject project, TLObject controller) {
		Project owner = (Project) project;
		if (controller.tContainer() != project) {
			List<Controller> controllers = new ArrayList<>();
			for (TLObject other : ProjectControllers.controllers(project)) {
				controllers.add((Controller) other);
			}
			controllers.add((Controller) controller);
			owner.setControllers(controllers);
		}
		owner.setController((Controller) controller);
	}

	/**
	 * {@link #own(TLObject, TLObject)} in a transaction of its own.
	 */
	public static void deployTo(TLObject project, TLObject controller) {
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			own(project, controller);
			tx.commit();
		}
	}

}
