/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.runtime;

import static test.de.haumacher.ehome.importer.TestEtsImport.importSample;
import static test.de.haumacher.ehome.importer.TestEtsImport.instances;
import static test.de.haumacher.ehome.runtime.TestFunctionProposal.address;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import junit.framework.Test;
import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;
import test.de.haumacher.ehome.importer.SharedData;

import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.model.TLObject;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.model.logical.ProjectDatapoint;
import de.haumacher.ehome.model.project.Project;
import de.haumacher.ehome.model.runtime.Function;
import de.haumacher.ehome.runtime.FunctionProposal;

/**
 * Function proposals for actuators whose objects name no datapoint subtype, beside functions the user
 * made (haumacher/ehome #211; <code>src/test/fixtures/TestFunctionBlinds.knxproj</code>, see
 * <code>make-fixtures.py</code>, the construct of an old blind actuator and a valve drive).
 *
 * <p>
 * A blind actuator's channel with its long-term and short-term operation (1 bit, no subtype) and its
 * position objects is a blind, not a dimmer: the long-term operation moves, the short-term one stops.
 * A valve drive's actuating value (5.001 "Position anfahren" of "Stellgröße") is no dimmer, and the
 * drive gives no function. No proposal binds a datapoint an existing function binds, in any role and
 * of any kind: the blind the user made for "Ausgang 1" keeps the channel's proposal away, and so does a
 * sensor the user made on the position report of "Ausgang 3", although that channel is a blind as
 * well. A second run adds and changes nothing.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestFunctionBlinds extends BasicTestCase {

	private static final String FIXTURE = "src/test/fixtures/TestFunctionBlinds.knxproj";

	public void testBlindsAndValve() throws Exception {
		List<TLObject> before = instances("ehome.project:Project");
		importSample(FIXTURE);
		List<TLObject> projects = SharedData.newProjects(before);
		try {
			assertEquals(1, projects.size());
			TLObject project = projects.get(0);

			// Each channel of the blind actuator is a blind; the valve drive gives nothing.
			List<String> proposed = new ArrayList<>();
			for (FunctionProposal.Proposal proposal : FunctionProposal.propose(project)) {
				proposed.add(proposal.kind() + " " + proposal.channel() + " " + roles(proposal.roles()));
			}
			assertEquals(List.of(
				"blind channel:M-0008_A-FFC8-01-0000_CH-1 move=6/1/1 stop=6/1/2 position=6/1/4 positionCommand=6/1/3",
				"blind channel:M-0008_A-FFC8-01-0000_CH-2 move=6/2/1 stop=6/2/2 position=6/2/4 positionCommand=6/2/3",
				"blind channel:M-0008_A-FFC8-01-0000_CH-3 move=6/3/1 stop=6/3/2 position=6/3/4 positionCommand=6/3/3"), proposed);

			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				Function blind = function(project, "Rollladen Fenster", "blind");
				blind.setMove(dp(project, "6/1/1"));
				blind.setStop(dp(project, "6/1/2"));
				blind.setPosition(dp(project, "6/1/4"));
				blind.setPositionCommand(dp(project, "6/1/3"));
				Function sensor = function(project, "Position Flur", "sensor");
				sensor.setValue(dp(project, "6/3/4"));
				tx.commit();
			}

			FunctionProposal.Run run;
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				run = FunctionProposal.run(project);
				tx.commit();
			}
			assertEquals(List.of(), run.updated());
			assertEquals(1, run.added().size());
			assertEquals(List.of(
				"Position Flur | sensor | value=6/3/4 | - | used",
				"Rollladen Fenster | blind | move=6/1/1, stop=6/1/2, position=6/1/4, positionCommand=6/1/3 | - | used",
				"Rollladen Küche fahren | blind | move=6/2/1, stop=6/2/2, position=6/2/4, positionCommand=6/2/3 | - | used"),
				TestFunctionLinks.table(project));

			// A second run adds and changes nothing.
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				run = FunctionProposal.run(project);
				tx.commit();
			}
			assertEquals(List.of(), run.added());
			assertEquals(List.of(), run.updated());
		} finally {
			SharedData.deleteProjects(projects);
		}
	}

	/** A function the user made, without device and channel. */
	private static Function function(TLObject project, String name, String kind) {
		Function result = (Function) TestFunctionProposal.create("ehome.runtime:Function");
		result.setNameKey(FunctionProposal.text(Map.of("de", name)));
		result.setKind(FunctionProposal.classifier(kind));
		List<Function> functions = new ArrayList<>(((Project) project).getFunctions());
		functions.add(result);
		((Project) project).setFunctions(functions);
		return result;
	}

	private static ProjectDatapoint dp(TLObject project, String threeLevel) {
		return (ProjectDatapoint) address(project, threeLevel);
	}

	private static String roles(Map<String, TLObject> roles) {
		List<String> result = new ArrayList<>();
		roles.forEach((role, address) -> result.add(role + "=" + TestFunctionProposal.text(address)));
		return String.join(" ", result);
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestFunctionBlinds.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
