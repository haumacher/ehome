/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.connection;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.top_logic.basic.Logger;
import com.top_logic.element.meta.MetaElementUtil;
import com.top_logic.knowledge.service.migration.StartupAction;
import com.top_logic.model.TLClass;
import com.top_logic.model.TLNamed;
import com.top_logic.model.TLObject;
import com.top_logic.model.util.TLModelUtil;

import de.haumacher.ehome.model.project.Project;
import de.haumacher.ehome.model.runtime.Controller;
import de.haumacher.ehome.model.runtime.Deployment;

/**
 * The data step of the migration <code>Issue_151_Controllers_in_projects</code> (haumacher/ehome
 * #151): every controller becomes a part of exactly one project ({@link ProjectControllers}).
 *
 * <p>
 * Before, a controller on the LAN was held by no project, and every project could deploy to it
 * (<code>ehome.project:Project#controller</code>). Now:
 * </p>
 * <ul>
 * <li>a controller without a project that exactly one project deploys to moves into that
 * project;</li>
 * <li>one that several projects deploy to moves into the first of them (by name, then by creation)
 * and is copied, with its token, into each of the others, which deploy to their copy from then on;
 * their recorded deployments name the copy;</li>
 * <li>one no project deploys to stays without a project: nobody but the E-Home administrators see it,
 * who assign it to a project or delete it;</li>
 * <li>a project deploying to an enrolled controller of another project (which the options never
 * offered) loses that target: the controller stays with its project, and copying it would duplicate
 * its connection.</li>
 * </ul>
 *
 * <p>
 * Runs once, in the started application after the migration, in a transaction of the migration
 * service; {@link #migrate()} is the logic, with what it did as one line per controller.
 * </p>
 */
public class ControllerOwnershipMigration implements StartupAction {

	@Override
	public void perform() {
		for (String line : migrate()) {
			Logger.info(line, ControllerOwnershipMigration.class);
		}
	}

	/**
	 * Moves or copies every controller without a project that a project deploys to into the projects
	 * deploying to it, inside the caller's transaction.
	 *
	 * @return What was done, one line per controller and project.
	 */
	public static List<String> migrate() {
		List<String> report = new ArrayList<>();
		List<Project> projects = new ArrayList<>(
			MetaElementUtil.getAllDirectInstancesOf((TLClass) TLModelUtil.findType("ehome.project:Project"), Project.class));
		projects.sort(Comparator.comparing((Project p) -> String.valueOf(p.getName()))
			.thenComparing(p -> p.tIdLocal()));

		Map<Controller, List<Project>> deployers = new LinkedHashMap<>();
		for (Project project : projects) {
			if (project.getController() instanceof Controller target && target.tValid()
				&& target.tContainer() != project) {
				deployers.computeIfAbsent(target, x -> new ArrayList<>()).add(project);
			}
		}

		for (Map.Entry<Controller, List<Project>> entry : deployers.entrySet()) {
			Controller controller = entry.getKey();
			List<Project> users = entry.getValue();
			String name = name(controller);
			TLObject holder = controller.tContainer();
			if (holder != null) {
				for (Project project : users) {
					project.setController(null);
					report.add("Project '" + name(project) + "' deployed to the controller '" + name + "' of project '"
						+ name(holder) + "'; the target is removed (haumacher/ehome #151).");
				}
				continue;
			}
			Project first = users.get(0);
			ProjectControllers.add(first, controller);
			report.add("Controller '" + name + "' moved into project '" + name(first) + "' (haumacher/ehome #151).");
			for (Project project : users.subList(1, users.size())) {
				Controller copy = (Controller) ProjectControllers.copy(controller);
				ProjectControllers.add(project, copy);
				project.setController(copy);
				renameDeployments(project, controller, copy);
				report.add("Controller '" + name + "' copied into project '" + name(project) + "' (haumacher/ehome #151).");
			}
		}

		TLClass controllerType = (TLClass) TLModelUtil.findType(ProjectControllers.CONTROLLER_TYPE);
		for (Controller controller : MetaElementUtil.getAllDirectInstancesOf(controllerType, Controller.class)) {
			if (controller.tContainer() == null && !deployers.containsKey(controller)) {
				report.add("Controller '" + name(controller)
					+ "' is deployed to by no project and stays without a project; an E-Home administrator assigns it to a project or deletes it (haumacher/ehome #151).");
			}
		}
		return report;
	}

	/**
	 * Lets the recorded deployments of the project that name the original controller name its copy.
	 */
	private static void renameDeployments(Project project, Controller original, Controller copy) {
		for (Deployment deployment : project.getDeployments()) {
			if (deployment != null && deployment.getController() == original) {
				deployment.setController(copy);
			}
		}
	}

	private static String name(TLObject obj) {
		return String.valueOf(((TLNamed) obj).getName());
	}

}
