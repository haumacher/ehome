/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.runtime;

import static test.de.haumacher.ehome.importer.TestEtsImport.importSample;
import static test.de.haumacher.ehome.importer.TestEtsImport.instances;
import static test.de.haumacher.ehome.runtime.TestFunctionProposal.address;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import junit.framework.Test;
import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;
import test.de.haumacher.ehome.importer.SharedData;

import com.top_logic.basic.util.ResKey;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.model.TLObject;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.blocks.RoomController;
import de.haumacher.ehome.model.building.BuildingDefinition;
import de.haumacher.ehome.model.logical.GroupAddress;
import de.haumacher.ehome.model.logical.ProjectDatapoint;
import de.haumacher.ehome.model.project.Project;
import de.haumacher.ehome.model.runtime.BlockInstance;
import de.haumacher.ehome.model.runtime.Function;
import de.haumacher.ehome.runtime.BlockInstances;
import de.haumacher.ehome.runtime.FunctionKinds;
import de.haumacher.ehome.runtime.FunctionProposal;
import de.haumacher.ehome.runtime.RoomControllers;
import de.haumacher.ehome.runtime.RuntimeImage;

/**
 * "Propose functions" gives the room of a room controller one climate function (haumacher/ehome #212;
 * <code>src/test/fixtures/TestFunctionClimate.knxproj</code>, see <code>make-fixtures.py</code>).
 *
 * <p>
 * The room "Wohnzimmer" has three keys measuring its temperature and a room controller whose input reads
 * one of them and whose valve output commands the valve drive's address: one climate, regulated by the
 * controller, with the three temperatures as its sensors (the controller's first), the controller's
 * filtered temperature as the actual value and the valve's address as the heating level; no sensor
 * function for a temperature of the room, no dimmer for the valve. The room "Küche" has a key and a
 * room controller bound to nothing: its climate takes the key's temperature, and the controller reads it.
 * The key in "Flur", which has no room controller, stays a sensor. A second run adds and changes nothing.
 * </p>
 *
 * <p>
 * The climates in their form before haumacher/ehome #212 - one made by hand with the controller's
 * roles, one proposed for the controller - keep a run without the option to overwrite from adding
 * anything (the temperatures of their rooms included); a run with the option moves both to the new form:
 * regulated by their controllers, with the sensors of their rooms, the hand-made one under its name.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestFunctionClimate extends BasicTestCase {

	private static final String FIXTURE = "src/test/fixtures/TestFunctionClimate.knxproj";

	private static final Instant NOW = Instant.parse("2026-11-20T12:00:00Z");

	public void testRoomClimates() throws Exception {
		List<TLObject> before = instances("ehome.project:Project");
		importSample(FIXTURE);
		List<TLObject> projects = SharedData.newProjects(before);
		try {
			assertEquals(1, projects.size());
			Project project = (Project) projects.get(0);
			BlockInstance wz;
			BlockInstance kueche;
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				((GroupAddress) address(project, "8/1/10")).setRuntimeWritable(Boolean.TRUE);
				wz = controller(project, "Wohnzimmer", "wz");
				BlockInstances.bind(BlockInstances.port(wz, RoomControllers.TEMPERATURE), address(project, "8/1/1"));
				BlockInstances.bind(BlockInstances.port(wz, "valve"), address(project, "8/1/10"));
				kueche = controller(project, "Küche", "kueche");
				tx.commit();
			}

			FunctionProposal.Run run = run(project, false);
			assertEquals(List.of(
				"Raumregler Küche | climate | temperature=block/kueche/filtered, setpoint=block/kueche/activeSetpoint,"
					+ " setpointCommand=block/kueche/comfort, heating=block/kueche/demand,"
					+ " heatingLevel=block/kueche/valve | sensors=8/2/1 | Küche",
				"Raumregler Wohnzimmer | climate | temperature=block/wz/filtered, setpoint=block/wz/activeSetpoint,"
					+ " setpointCommand=block/wz/comfort, heating=block/wz/demand, heatingLevel=8/1/10"
					+ " | sensors=8/1/1, 8/1/2, 8/1/3 | Wohnzimmer",
				"Raumtemperatur Flur | sensor | value=8/3/1 | sensors= | Flur"), table(project));
			assertEquals(3, run.added().size());
			assertEquals("climate", FunctionProposal.kind(wz.getFunction()));
			assertEquals("climate", FunctionProposal.kind(kueche.getFunction()));
			assertEquals(List.of(), BlockInstances.bound(BlockInstances.port(wz, RoomControllers.TEMPERATURE)));

			RuntimeImage image = RuntimeImage.build(project, 1, NOW);
			assertTrue(image.validate().toString(), image.validate().isValid());
			Map<?, ?> block = (Map<?, ?>) ((List<?>) image.getJson().get("blocks")).get(0);
			assertEquals(List.of("8/1/1", "8/1/2", "8/1/3"), ((Map<?, ?>) block.get("inputs")).get("temperature"));

			run = run(project, false);
			assertEquals(List.of(), run.added());
			assertEquals(List.of(), run.updated());

			// The climates as before haumacher/ehome #212: one made by hand with the controller's roles, one
			// proposed for the controller, both regulated by none, the controllers reading by bindings.
			Function handMade;
			Function proposed;
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				for (TLObject function : FunctionProposal.functions(project)) {
					if ("climate".equals(FunctionProposal.kind(function))) {
						RoomControllers.deleting(function);
						function.tDelete();
					}
				}
				handMade = oldClimate(project, wz, "Heizung", null);
				proposed = oldClimate(project, kueche, "Kueche alt", FunctionProposal.BLOCK_CHANNEL + "kueche");
				tx.commit();
			}
			// Released, the controller reads the deleted climate's sensors by bindings of its own.
			assertEquals(List.of(address(project, "8/1/1"), address(project, "8/1/2"), address(project, "8/1/3")),
				BlockInstances.bound(BlockInstances.port(wz, RoomControllers.TEMPERATURE)));

			run = run(project, false);
			assertEquals("The old climates bind the controllers' outputs: nothing is added.", List.of(), run.added());

			run = run(project, true);
			assertEquals(List.of(), run.added());
			assertEquals(2, run.overwritten().size());
			assertEquals(List.of(
				"Heizung | climate | temperature=block/wz/filtered, setpoint=block/wz/activeSetpoint,"
					+ " setpointCommand=block/wz/comfort, heating=block/wz/demand, heatingLevel=8/1/10"
					+ " | sensors=8/1/1, 8/1/2, 8/1/3 | Wohnzimmer",
				"Raumregler Küche | climate | temperature=block/kueche/filtered, setpoint=block/kueche/activeSetpoint,"
					+ " setpointCommand=block/kueche/comfort, heating=block/kueche/demand,"
					+ " heatingLevel=block/kueche/valve | sensors=8/2/1 | Küche",
				"Raumtemperatur Flur | sensor | value=8/3/1 | sensors= | Flur"), table(project));
			assertSame(handMade, wz.getFunction());
			assertSame(proposed, kueche.getFunction());

			run = run(project, false);
			assertEquals(List.of(), run.added());
			assertEquals(List.of(), run.updated());
		} finally {
			SharedData.deleteProjects(projects);
		}
	}

	private static FunctionProposal.Run run(TLObject project, boolean overwrite) {
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			FunctionProposal.Run result = FunctionProposal.run(project, overwrite);
			tx.commit();
			return result;
		}
	}

	/** A room controller in the room with the given name. */
	private static BlockInstance controller(Project project, String room, String id) {
		BlockInstance result = (BlockInstance) BlockInstances.create(project,
			BlockInstances.typeKey(RoomController.INSTANCE.manifest()), room(project, room),
			ResKey.text("Raumregler " + room));
		result.setIdentifier(id);
		return result;
	}

	/** A climate with the roles proposals before haumacher/ehome #212 gave a room controller. */
	private static Function oldClimate(Project project, BlockInstance controller, String name, String channel) {
		Function result = (Function) TestFunctionProposal.create("ehome.runtime:Function");
		result.setNameKey(ResKey.text(name));
		result.setKind(FunctionProposal.classifier("climate"));
		result.setRoom(controller.getRoom());
		result.setChannel(channel);
		result.setTemperature((ProjectDatapoint) BlockInstances.port(controller, "filtered"));
		result.setSetpoint((ProjectDatapoint) BlockInstances.port(controller, "activeSetpoint"));
		result.setSetpointCommand((ProjectDatapoint) BlockInstances.parameter(controller, "comfort"));
		result.setHeating((ProjectDatapoint) BlockInstances.port(controller, "demand"));
		List<Function> functions = new ArrayList<>(project.getFunctions());
		functions.add(result);
		project.setFunctions(functions);
		return result;
	}

	private static BuildingDefinition room(Project project, String name) {
		for (TLObject part : SharedData.within("ehome.building:Room", List.of(project))) {
			if (name.equals(((BuildingDefinition) part).getName())) {
				return (BuildingDefinition) part;
			}
		}
		throw new AssertionError("No room " + name);
	}

	/** The functions of the project: name, kind, roles and sensors by their ids in the image, room. */
	private static List<String> table(TLObject project) {
		List<String> result = new ArrayList<>();
		for (TLObject function : FunctionProposal.functions(project)) {
			List<String> roles = new ArrayList<>();
			for (String role : FunctionKinds.roles(FunctionProposal.kind(function))) {
				if (function.tValueByName(role) instanceof TLObject datapoint) {
					roles.add(role + "=" + BlockInstances.datapointImageId(datapoint));
				}
			}
			List<String> sensors = RoomControllers.sensors(function).stream().map(BlockInstances::datapointImageId).toList();
			BuildingDefinition room = ((Function) function).getRoom();
			result.add(TestFunctionProposal.name(function) + " | " + FunctionProposal.kind(function) + " | "
				+ String.join(", ", roles) + " | sensors=" + String.join(", ", sensors) + " | "
				+ (room == null ? "-" : room.getName()));
		}
		result.sort(null);
		return result;
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestFunctionClimate.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
