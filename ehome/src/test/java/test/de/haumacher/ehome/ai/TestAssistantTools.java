/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.ai;

import static test.de.haumacher.ehome.ai.AssistantFixture.as;
import static test.de.haumacher.ehome.ai.AssistantFixture.conversation;
import static test.de.haumacher.ehome.ai.AssistantFixture.inTransaction;
import static test.de.haumacher.ehome.ai.AssistantFixture.tid;
import static test.de.haumacher.ehome.ai.AssistantFixture.toolNames;
import static test.de.haumacher.ehome.importer.TestEtsImport.importSample;
import static test.de.haumacher.ehome.importer.TestEtsImport.instances;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import junit.extensions.TestSetup;
import junit.framework.Test;
import junit.framework.TestSuite;

import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;
import test.de.haumacher.ehome.importer.SharedData;

import com.top_logic.base.security.device.TLSecurityDeviceManager;
import com.top_logic.base.services.InitialGroupManager;
import com.top_logic.base.services.InitialRolesManager;
import com.top_logic.basic.thread.ThreadContext;
import com.top_logic.basic.util.ResKey;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.wrap.person.Person;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.model.TLClass;
import com.top_logic.model.TLClassifier;
import com.top_logic.model.TLObject;
import com.top_logic.model.search.expr.config.SearchBuilder;
import com.top_logic.model.security.SecurityConfigurationService;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.tl.ai.core.agent.AgentApplicationService;
import com.top_logic.tl.ai.core.agent.AgentPolicyService;
import com.top_logic.tl.ai.core.agent.AgentRuntimeService;
import com.top_logic.tl.ai.core.agent.TopLogicAgentDefinitions;
import com.top_logic.tl.ai.core.message.Message;
import com.top_logic.tl.ai.core.message.ToolCall;
import com.top_logic.tl.ai.core.message.ToolResultMessage;
import com.top_logic.tl.ai.core.provider.ProviderRequest;
import com.top_logic.tl.ai.core.provider.ScriptedModel.Reply;
import com.top_logic.tl.ai.core.provider.ScriptedModel.Script;
import com.top_logic.tl.ai.core.provider.ScriptedProvider;
import com.top_logic.tl.ai.core.security.AiToolAccessService;
import com.top_logic.tl.ai.tool.api.AnnotatedTools;
import com.top_logic.tl.ai.tool.api.Tool;
import com.top_logic.tl.ai.tool.api.ToolResult;
import com.top_logic.tool.boundsec.manager.AccessManager;
import com.top_logic.util.Resources;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.ai.BuildingTools;
import de.haumacher.ehome.ai.DeviceTools;
import de.haumacher.ehome.ai.WiringTools;
import de.haumacher.ehome.ai.logic.FunctionTools;
import de.haumacher.ehome.knx.ExtIds;
import de.haumacher.ehome.knx.GA;
import de.haumacher.ehome.knx.ParameterValues;
import de.haumacher.ehome.model.operational.EnumerationType;
import de.haumacher.ehome.model.operational.EnumerationValue;
import de.haumacher.ehome.model.operational.Interface;
import de.haumacher.ehome.model.operational.Option;
import de.haumacher.ehome.model.operational.ParameterView;
import de.haumacher.ehome.modules.ModuleParts;
import de.haumacher.ehome.planning.DevicePlanning;
import de.haumacher.ehome.programming.DeviceConfiguration;
import de.haumacher.ehome.programming.DeviceSettings;
import de.haumacher.ehome.programming.GroupAddressConnections;
import de.haumacher.ehome.programming.GroupAddressTypes;
import de.haumacher.ehome.programming.GroupAddresses;
import de.haumacher.ehome.project.ProjectMembers;
import de.haumacher.ehome.project.ProjectMembers.Role;
import de.haumacher.ehome.runtime.DatapointNames;
import de.haumacher.ehome.runtime.Json;
import de.haumacher.ehome.template.ObjectTypes;

/**
 * E-Home's tools for the assistant (haumacher/ehome #127) on a scripted model, on the test system
 * (<code>samples/KNX-Testsystem.knxproj</code>: line 1.3 with the MDT switch actuator 1.3.5, the range
 * 0/0/x): the chat's agent lays out a building, plans a switch actuator of the catalog in the
 * kitchen and wires channel A's switching object to a new group address, through the operations
 * the views run; and it is refused, with the application's reason and without a change, an address
 * in use, an object or datapoint type of another size, a building part of another project, and -
 * as a viewer - every writing tool, also to an installer who only views the project.
 */
@SuppressWarnings("javadoc")
public class TestAssistantTools extends BasicTestCase {

	private static final String SCRIPTED_MODEL = "scripted-model";

	private static final String TESTSYSTEM = "samples/KNX-Testsystem.knxproj";

	private static final Pattern FIRST_ID = Pattern.compile("\"id\": \"([^\"]+)\"");

	private static TLObject _project;

	private static TLObject _foreign;

	private static Person _installer;

	private static Person _viewer;

	private static Person _viewingInstaller;

	/**
	 * The three sentences of the issue, one after the other, as an installer.
	 */
	public void testLayoutPlanAndWire() {
		// "Create ground floor with kitchen and living room."
		Map<String, Object> floor = part("Floor", "Erdgeschoss", part("Room", "Küche"), part("Room", "Wohnzimmer"));
		assertNull(named(ExtIds.list(_project, "buildings"), "Haus", false));
		List<ProviderRequest> sent = installerRuns("Create ground floor with kitchen and living room.",
			call("createBuildingParts", "parent", tid(_project), "parts", List.of(part("Building", "Haus", floor))));
		String created = toolAnswer(sent, "createBuildingParts");
		assertTrue(created, created.contains("Created."));
		// In the chat the calls of a turn share its transaction (over MCP each call commits, TestMcpEndpoint).
		assertTrue(created, created.contains("saved when this turn ends"));
		TLObject house = named(ExtIds.list(_project, "buildings"), "Haus");
		assertEquals("Haus", house.tValueByName("name"));
		TLObject groundFloor = single(ExtIds.list(house, "parts"));
		assertEquals("ehome.building:Floor", TLModelUtil.qualifiedName(groundFloor.tType()));
		List<TLObject> rooms = ExtIds.list(groundFloor, "parts");
		assertEquals(List.of("Küche", "Wohnzimmer"), rooms.stream().map(r -> r.tValueByName("name")).toList());
		TLObject kitchen = rooms.get(0);
		assertEquals("ehome.building:Room", TLModelUtil.qualifiedName(kitchen.tType()));

		// "Plan a switching actuator of the test catalog in line 1.3 and put it in the kitchen."
		TLObject line = line();
		TLObject actuator = device(line, 5);
		TLObject variant = (TLObject) actuator.tValueByName("type");
		Integer next = DevicePlanning.nextFree(line);
		List<TLObject> before = ExtIds.list(line, "devices");
		sent = installerRuns("Plan a switching actuator of the test catalog in line 1.3 and put it in the kitchen.",
			call("searchCatalog", "text", "MDT Switch Actuator"),
			call("planDevice", "line", tid(line), "product", tid(variant), "location", tid(kitchen)));
		assertTrue("The catalog search finds the product: " + toolAnswer(sent, "searchCatalog"),
			toolAnswer(sent, "searchCatalog").contains(tid(variant)));
		List<TLObject> planned = ExtIds.list(line, "devices");
		planned.removeAll(before);
		TLObject device = single(planned);
		assertEquals(next, device.tValueByName("address"));
		assertSame(variant, device.tValueByName("type"));
		assertSame(DevicePlanning.defaultProgram(variant), device.tValueByName("program"));
		assertSame(kitchen, device.tValueByName("location"));

		// "Create a group address for the kitchen light and connect channel A's switching object."
		TLObject range = GroupAddresses.ranges(_project).get(0);
		GA expected = GroupAddresses.nextFree(range);
		Interface channelA = switchingObject(device, "Channel A");
		String kind = DeviceSettings.defaultKind(device, channelA).getName();
		Script script = (request, turn) -> switch (turn) {
			case 0 -> Reply.of(toolCall("c0", "deviceObjects", "device", tid(device)),
				toolCall("c1", "createGroupAddress", "range", tid(range), "name", "Küche Licht schalten",
					"datapointType", "1.001"));
			case 1 -> Reply.of(toolCall("c2", "connectObject", "device", tid(device), "object",
				Integer.valueOf(channelA.getPort().getIndex()), "groupAddress", createdIn(request, "createGroupAddress")));
			case 2 -> Reply.text("Done.");
			default -> throw new IllegalStateException("No turn " + turn);
		};
		sent = runs(_installer, "Create a group address for the kitchen light and connect channel A's switching object.",
			script);
		assertEquals(3, sent.size());
		TLObject address = byAddress(expected);
		assertNotNull("The address is created at the next free address of the range.", address);
		assertSame(range, address.tContainer());
		assertTrue("In the project's notation: " + address.tValueByName("address"),
			address.tValueByName("address").toString().matches("\\d+/\\d+/\\d+"));
		assertEquals("1.001", GroupAddressTypes.dpt((TLObject) single(list(address, "datapointTypes"))).code());
		assertEquals(sent.get(2).messages().toString(), 1, GroupAddressConnections.connections(address).size());
		TLObject connection = single(GroupAddressConnections.connections(address));
		assertSame(device, GroupAddressConnections.device(connection));
		assertEquals(channelA.getPort().getIndex(), GroupAddressConnections.object(connection).getPort().getIndex());
		assertEquals("The direction the dialog proposes.", kind, ((TLClassifier) connection.tValueByName("kind")).getName());
		assertEquals(Boolean.TRUE, connection.tContainer().tValueByName("active"));
		String answer = toolAnswer(sent, "connectObject");
		assertFalse("The object's text with its placeholder filled: " + answer, answer.contains("{{"));
	}

	/**
	 * What the application refuses, it refuses the assistant with its reason, and nothing changes.
	 */
	public void testRefusals() {
		TLObject line = line();
		TLObject actuator = device(line, 5);
		TLObject range = GroupAddresses.ranges(_project).get(0);
		TLObject[] address = { null };
		Interface channelA = switchingObject(actuator, "Channel A");
		Interface scene = objectOfSize(actuator, 8);
		inTransaction(() -> {
			address[0] = GroupAddresses.create(range, GroupAddresses.nextFree(range), "Refusals", null);
			DeviceSettings.connect(actuator, channelA, address[0], kind("receive"));
		});
		TLObject foreignBuilding = single(ExtIds.list(_foreign, "buildings"));
		int addresses = DeviceSettings.groupAddresses(_project).size();
		int devices = ExtIds.list(line, "devices").size();
		int connections = GroupAddressConnections.connections(address[0]).size();
		String taken = address[0].tValueByName("address").toString();

		List<ProviderRequest> sent = installerRuns("Try what cannot work.",
			call("createGroupAddress", "range", tid(range), "name", "Doppelt", "address", taken),
			call("setGroupAddressType", "groupAddress", tid(address[0]), "datapointType", "9.001"),
			call("connectObject", "device", tid(actuator), "object", Integer.valueOf(scene.getPort().getIndex()),
				"groupAddress", tid(address[0])),
			call("planDevice", "line", tid(line), "product", tid((TLObject) actuator.tValueByName("type")), "location",
				tid(foreignBuilding)));
		String inUse = toolAnswer(sent, "createGroupAddress");
		String typeSize = toolAnswer(sent, "setGroupAddressType");
		String objectSize = toolAnswer(sent, "connectObject");
		String foreign = toolAnswer(sent, "planDevice");

		assertTrue("An address in use: " + inUse, inUse.contains("\"refused\""));
		assertTrue(inUse, inUse.contains(taken));
		assertTrue("A datapoint type of another size: " + typeSize, typeSize.contains("\"refused\""));
		assertTrue(typeSize, typeSize.contains("9.001") && typeSize.contains("16"));
		assertTrue("An object of another size: " + objectSize, objectSize.contains("\"refused\""));
		assertTrue(objectSize, objectSize.contains(
			as(_installer, () -> text(de.haumacher.ehome.programming.I18NConstants.WARNING_OBJECT_SIZES_DIFFER))));
		// The installer holds no role in the other project and does not even find its building
		// (haumacher/ehome #152).
		assertTrue("A building part of another project: " + foreign, foreign.contains("not found"));

		assertEquals(addresses, DeviceSettings.groupAddresses(_project).size());
		assertTrue(list(address[0], "datapointTypes").isEmpty());
		assertEquals(connections, GroupAddressConnections.connections(address[0]).size());
		assertEquals(devices, ExtIds.list(line, "devices").size());

		// A viewer holds the writing tools (every account does) and is refused by the role in the project
		// (haumacher/ehome #152).
		sent = runs(_viewer, "Plan a device.", script(call("planDevice", "line", tid(line), "product",
			tid((TLObject) actuator.tValueByName("type")))));
		List<String> offered = toolNames(sent.get(0));
		assertTrue("A viewer reads: " + offered, offered.contains("listDevices"));
		String refusal = toolAnswer(sent, "planDevice");
		assertTrue("Refused by the role in the project (haumacher/ehome #152): " + refusal, refusal.contains("may not change"));
		assertEquals(devices, ExtIds.list(line, "devices").size());

		// The tool checks the right itself, also for a caller that does not ask the permission filter.
		ToolResult direct = as(_viewer, () -> new DeviceTools().planDevice(AssistantFixture.toolContext(), line,
			(TLObject) actuator.tValueByName("type"), null, null, null, null, null));
		assertTrue(direct.failed());
		assertTrue(direct.text(), direct.text().contains("Permission denied"));
		assertEquals(devices, ExtIds.list(line, "devices").size());
	}

	/**
	 * An installer who only views the project is refused each kind of writing tool by the tool's own
	 * check of the right on the project, before anything is written: since 8.0.0-alpha13.823 no
	 * pre-commit guard of the agent framework checks a turn's changes again (TL #30255). Creating
	 * building parts with parts inside them, connecting an object (a new connector with its connection),
	 * duplicating a device (a new device with its settings), and a write of the logic tools.
	 */
	public void testViewingInstallerRefusedEveryKindOfWrite() {
		TLObject line = line();
		TLObject actuator = device(line, 5);
		Interface channelA = switchingObject(actuator, "Channel A");
		TLObject range = GroupAddresses.ranges(_project).get(0);
		TLObject[] address = { null };
		inTransaction(() -> address[0] = GroupAddresses.create(range, GroupAddresses.nextFree(range), "Nur lesen", null));
		int buildings = ExtIds.list(_project, "buildings").size();
		int devices = ExtIds.list(line, "devices").size();
		int functions = ExtIds.list(_project, "functions").size();

		Map<String, ToolResult> results = new LinkedHashMap<>();
		results.put("createBuildingParts", as(_viewingInstaller, () -> new BuildingTools().createBuildingParts(
			AssistantFixture.toolContext(), _project,
			List.of(part("Building", "Betrachterhaus", part("Floor", "Erdgeschoss", part("Room", "Küche")))), null,
			null)));
		results.put("connectObject", as(_viewingInstaller, () -> new WiringTools().connectObject(
			AssistantFixture.toolContext(), actuator, Integer.valueOf(channelA.getPort().getIndex()), address[0], null,
			null, null)));
		results.put("duplicateDevice", as(_viewingInstaller,
			() -> new DeviceTools().duplicateDevice(AssistantFixture.toolContext(), actuator, null)));
		results.put("proposeFunctions", as(_viewingInstaller,
			() -> new FunctionTools().proposeFunctions(AssistantFixture.toolContext(), _project, null)));
		for (Map.Entry<String, ToolResult> result : results.entrySet()) {
			ToolResult refusal = result.getValue();
			assertTrue(result.getKey() + ": " + refusal.text(), refusal.failed());
			assertTrue(result.getKey() + ": " + refusal.text(), refusal.text().contains("Permission denied"));
		}

		assertEquals(buildings, ExtIds.list(_project, "buildings").size());
		assertTrue(GroupAddressConnections.connections(address[0]).isEmpty());
		assertEquals(devices, ExtIds.list(line, "devices").size());
		assertEquals(functions, ExtIds.list(_project, "functions").size());
	}

	/**
	 * A writing call names its change and its target in the revision log (TL #30276): the commit
	 * message of the call, asked before it runs, from the arguments as the model sends them.
	 */
	public void testCommitMessages() {
		TLObject line = line();
		TLObject actuator = device(line, 5);
		Interface channelA = switchingObject(actuator, "Channel A");
		TLObject range = GroupAddresses.ranges(_project).get(0);
		String projectName = (String) _project.tValueByName("name");

		String created = commitMessage(new BuildingTools(), "createBuildingParts",
			Map.of("parent", tid(_project), "parts", List.of(part("Building", "Gartenhaus"), part("Building", "Garage"))));
		assertEquals("Building parts created in " + projectName + ": Gartenhaus, Garage", created);

		String planned = commitMessage(new DeviceTools(), "planDevice",
			Map.of("line", tid(line), "product", tid((TLObject) actuator.tValueByName("type")), "name", "Aktor Garage"));
		assertTrue(planned, planned.startsWith("Device planned in line ") && planned.endsWith(": Aktor Garage"));

		String address = commitMessage(new WiringTools(), "createGroupAddress",
			Map.of("range", tid(range), "name", "Garage Licht"));
		assertTrue(address, address.startsWith("Group address Garage Licht created in "));

		TLObject target = DeviceSettings.groupAddresses(_project).get(0);
		String connected = commitMessage(new WiringTools(), "connectObject",
			Map.of("device", tid(actuator), "object", Integer.valueOf(channelA.getPort().getIndex()), "groupAddress",
				tid(target)));
		assertTrue(connected, connected.startsWith("Object " + channelA.getPort().getIndex() + " "));
		assertTrue(connected, connected.contains(as(_installer, () -> GroupAddressConnections.deviceLabel(actuator))));
		assertTrue(connected, connected.endsWith(" connected to " + as(_installer, () -> DatapointNames.name(target))));

		// An argument that does not resolve fails no message: the engine logs the generic one naming the tool.
		assertNull(commitMessage(new DeviceTools(), "duplicateDevice", Map.of("device", "ehome.physical:Device:0")));
	}

	/**
	 * The commit message of a call of the given tool in English, as the installer runs it.
	 */
	private static String commitMessage(Object tools, String name, Map<String, Object> arguments) {
		Tool tool = AnnotatedTools.from(tools).stream().filter(t -> t.name().equals(name)).findFirst().orElseThrow();
		return as(_installer, () -> {
			ResKey message = tool.commitMessage(arguments, AssistantFixture.toolContext());
			return message == null ? null : Resources.getInstance(Locale.ENGLISH).getString(message);
		});
	}

	/**
	 * Review probe (haumacher/ehome #152): the reading tools resolve their arguments with the caller's
	 * rights; an installer without a role in a project neither lists its devices nor reads its
	 * building structure, by the project's id or an object's.
	 */
	public void testProbeReadingToolsOfForeignProject() {
		TLObject foreignBuilding = single(ExtIds.list(_foreign, "buildings"));
		List<ProviderRequest> sent = installerRuns("Read the other project.",
			call("listDevices", "project", tid(_foreign)),
			call("buildingStructure", "project", tid(foreignBuilding)));
		for (String tool : List.of("listDevices", "buildingStructure")) {
			String answer = toolAnswer(sent, tool);
			assertTrue(tool + " of a project without a role: " + answer, answer.contains("not found"));
			assertFalse(tool + ": " + answer, answer.contains("Fremdes Projekt"));
		}
	}

	/**
	 * Review probe (haumacher/ehome #174): the template tools take a project only as the caller may
	 * read it; an installer without a role in a project gets no marks of its devices, whichever
	 * program is asked about.
	 */
	public void testProbeTemplateToolsOfForeignProject() {
		TLObject assignment = (TLObject) device(line(), 5).tValueByName("program");
		TLObject program = (TLObject) assignment.tValueByName("program");
		List<ProviderRequest> sent = installerRuns("Which objects of the other project are untyped?",
			call("objectKinds", "program", tid(program), "project", tid(_foreign), "untypedOnly", Boolean.TRUE));
		String answer = toolAnswer(sent, "objectKinds");
		assertTrue("A project without a role: " + answer, answer.contains("not found"));
		assertFalse(answer, answer.contains("Fremdes Projekt"));
		assertFalse(answer, answer.contains("\"kinds\""));
	}

	/**
	 * The reading tools, a parameter set with the editor's checks, a duplicate with the settings, and
	 * a part moved and a device placed.
	 */
	public void testParametersAndStructure() {
		TLObject line = line();
		TLObject actuator = device(line, 5);
		TLObject[] parts = new TLObject[3];
		inTransaction(() -> {
			parts[0] = create("ehome.building:Building");
			parts[0].tUpdateByName("name", "Nebengebäude");
			parts[1] = create("ehome.building:Room");
			parts[1].tUpdateByName("name", "Werkstatt");
			parts[2] = create("ehome.building:DistributionBoard");
			parts[2].tUpdateByName("name", "UV Nebengebäude");
			parts[0].tUpdateByName("parts", List.of(parts[1], parts[2]));
			List<TLObject> buildings = ExtIds.list(_project, "buildings");
			buildings.add(parts[0]);
			_project.tUpdateByName("buildings", buildings);
		});

		// A choice the actuator's configuration shows at the top or on a page, which can be changed.
		DeviceConfiguration configuration = new DeviceConfiguration(actuator);
		ParameterView choice = null;
		Object page = null;
		for (Map.Entry<Option, Object> entry : configuration.getShownItems().entrySet()) {
			if (entry.getKey() instanceof ParameterView view
				&& ParameterValues.valueType(view) instanceof EnumerationType enumeration
				&& enumeration.getValues().size() > 1 && DeviceSettings.editVeto(actuator, view) == null) {
				choice = view;
				page = entry.getValue();
				break;
			}
		}
		assertNotNull("The actuator has a choice to change.", choice);
		EnumerationValue current = (EnumerationValue) DeviceSettings.value(actuator, choice);
		EnumerationValue other = ((EnumerationType) ParameterValues.valueType(choice)).getValues().stream()
			.filter(entry -> entry != current).findFirst().get();
		String parameter = ModuleParts.partId(choice);
		String pageId = page == actuator ? null : ModuleParts.partId((TLObject) page);

		List<ProviderRequest> sent = installerRuns("Show me the project and change a parameter.",
			call("buildingStructure", "project", tid(_project)),
			call("listDevices", "project", tid(_project)),
			call("listGroupAddresses", "project", tid(_project)),
			call("deviceObjects", "device", tid(actuator)),
			call("deviceParameters", "device", tid(actuator), "page", pageId),
			call("setDeviceParameter", "device", tid(actuator), "parameter", parameter, "value",
				Integer.toString(other.getValue())),
			call("moveBuildingParts", "parts", List.of(tid(parts[2])), "container", tid(parts[0]), "before",
				tid(parts[1])),
			call("placeDevice", "device", tid(actuator), "location", tid(parts[2])));
		assertTrue(toolAnswer(sent, "buildingStructure"), toolAnswer(sent, "buildingStructure").contains("Werkstatt"));
		assertTrue(toolAnswer(sent, "listDevices"), toolAnswer(sent, "listDevices").contains("1.3.5"));
		assertTrue(toolAnswer(sent, "listGroupAddresses"), toolAnswer(sent, "listGroupAddresses").contains("ThreeLevel"));
		assertTrue(toolAnswer(sent, "deviceObjects"), toolAnswer(sent, "deviceObjects").contains("\"flags\""));
		assertTrue(toolAnswer(sent, "deviceParameters"), toolAnswer(sent, "deviceParameters").contains(parameter));
		assertTrue(toolAnswer(sent, "setDeviceParameter"), toolAnswer(sent, "setDeviceParameter").contains("Set."));
		assertSame(other, DeviceSettings.value(actuator, choice));
		assertEquals(List.of(parts[2], parts[1]), ExtIds.list(parts[0], "parts"));
		assertSame(parts[2], actuator.tValueByName("location"));

		// A duplicate takes the settings along: parts of a device created in the same turn.
		List<TLObject> before = ExtIds.list(line, "devices");
		installerRuns("Duplicate the actuator.", call("duplicateDevice", "device", tid(actuator)));
		List<TLObject> copies = ExtIds.list(line, "devices");
		copies.removeAll(before);
		TLObject copy = single(copies);
		assertSame(other, DeviceSettings.value(copy, ModuleParts.parameter(single(ExtIds.list(copy, "settings").stream()
			.filter(setting -> parameter.equals(ModuleParts.partId(setting, ModuleParts.PARAMETER))).toList()))));

		// An invalid value is refused with the editor's reason, nothing changes.
		sent = installerRuns("Set an impossible value.", call("setDeviceParameter", "device", tid(actuator), "parameter",
			parameter, "value", "999999"));
		assertTrue(toolAnswer(sent, "setDeviceParameter"), toolAnswer(sent, "setDeviceParameter").contains("\"refused\""));
		assertSame(other, DeviceSettings.value(actuator, choice));
	}

	private static List<ProviderRequest> installerRuns(String question, ToolCall... calls) {
		return runs(_installer, question, script(calls));
	}

	/**
	 * A script calling the given tools in one turn, then answering.
	 */
	private static Script script(ToolCall... calls) {
		return Script.of(Reply.of(calls), Reply.text("Done."));
	}

	private static List<ProviderRequest> runs(Person person, String question, Script script) {
		ScriptedProvider.install(script);
		as(person, () -> AssistantFixture.run(conversation(question), SCRIPTED_MODEL));
		try {
			PersistencyLayer.getKnowledgeBase().refetch();
		} catch (com.top_logic.knowledge.service.RefetchTimeout ex) {
			throw new RuntimeException(ex);
		}
		return ScriptedProvider.requests();
	}

	private static int _callId;

	private static ToolCall call(String tool, Object... arguments) {
		return toolCall("t" + (++_callId), tool, arguments);
	}

	private static ToolCall toolCall(String id, String tool, Object... arguments) {
		Map<String, Object> json = new LinkedHashMap<>();
		for (int n = 0; n < arguments.length; n += 2) {
			json.put((String) arguments[n], arguments[n + 1]);
		}
		return new ToolCall(id, tool, Json.write(json));
	}

	private static Map<String, Object> part(String kind, String name, Object... parts) {
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("kind", kind);
		result.put("name", name);
		if (parts.length > 0) {
			result.put("parts", List.of(parts));
		}
		return result;
	}

	/**
	 * The answer of the given tool as the model read it in the last request.
	 */
	private static String toolAnswer(List<ProviderRequest> sent, String tool) {
		StringBuilder result = new StringBuilder();
		for (Message message : sent.get(sent.size() - 1).messages()) {
			if (message instanceof ToolResultMessage answer && tool.equals(answer.toolName())) {
				result.append(answer);
			}
		}
		assertTrue("No answer of " + tool, result.length() > 0);
		return result.toString();
	}

	/**
	 * The identifier of the object the last answer of the given tool in the request reports first.
	 */
	private static String createdIn(ProviderRequest request, String tool) {
		String text = null;
		for (Message message : request.messages()) {
			if (message instanceof ToolResultMessage answer && tool.equals(answer.toolName())) {
				text = answer.toString();
			}
		}
		assertNotNull("No answer of " + tool, text);
		Matcher matcher = FIRST_ID.matcher(text);
		assertTrue("No identifier in: " + text, matcher.find());
		return matcher.group(1);
	}

	private static TLObject byAddress(GA address) {
		for (TLObject candidate : DeviceSettings.groupAddresses(_project)) {
			if (address.equals(candidate.tValueByName("address"))) {
				return candidate;
			}
		}
		return null;
	}

	private static Interface switchingObject(TLObject device, String text) {
		for (Interface placed : GroupAddressConnections.objects(device)) {
			if (GroupAddressConnections.objectName(placed).contains(text)
				&& "1.001".equals(code(ObjectTypes.effective(device, placed)))) {
				return placed;
			}
		}
		fail("No switching object of " + text + " at " + GroupAddressConnections.deviceLabel(device));
		return null;
	}

	private static Interface objectOfSize(TLObject device, int size) {
		for (Interface placed : GroupAddressConnections.objects(device)) {
			if (ObjectTypes.size(placed) == size) {
				return placed;
			}
		}
		fail("No object of " + size + " bits at " + GroupAddressConnections.deviceLabel(device));
		return null;
	}

	private static String code(TLObject type) {
		return type == null || GroupAddressTypes.dpt(type) == null ? null : GroupAddressTypes.dpt(type).code();
	}

	private static TLClassifier kind(String name) {
		return ((com.top_logic.model.TLEnumeration) TLModelUtil.findType("ehome.physical:ConnectionType"))
			.getClassifier(name);
	}

	private static String text(ResKey key) {
		return Resources.getInstance().getString(key);
	}

	private static String label(Object obj) {
		return com.top_logic.layout.provider.MetaLabelProvider.INSTANCE.getLabel(obj);
	}

	private static TLObject line() {
		for (TLObject area : ExtIds.list(_project, "areas")) {
			for (TLObject line : ExtIds.list(area, "lines")) {
				if (Integer.valueOf(3).equals(line.tValueByName("address"))) {
					return line;
				}
			}
		}
		fail("No line 1.3.");
		return null;
	}

	private static TLObject device(TLObject line, int number) {
		for (TLObject device : ExtIds.list(line, "devices")) {
			if (Integer.valueOf(number).equals(device.tValueByName("address"))) {
				return device;
			}
		}
		fail("No device " + number + " in the line.");
		return null;
	}

	private static List<?> list(TLObject obj, String attribute) {
		return new ArrayList<>((Collection<?>) obj.tValueByName(attribute));
	}

	private static TLObject named(List<TLObject> objects, String name) {
		return named(objects, name, true);
	}

	private static TLObject named(List<TLObject> objects, String name, boolean mandatory) {
		for (TLObject obj : objects) {
			if (name.equals(obj.tValueByName("name"))) {
				return obj;
			}
		}
		if (mandatory) {
			fail("No " + name + " in " + objects);
		}
		return null;
	}

	private static <T> T single(List<T> values) {
		assertEquals("Exactly one: " + values, 1, values.size());
		return values.get(0);
	}

	private static TLObject create(String type) {
		return ModelService.getInstance().getFactory().createObject((TLClass) TLModelUtil.findType(type));
	}

	/**
	 * Imports the test system once for all tests of the class, with an installer, a viewer, an installer
	 * who only views the imported project, and a project of its own with a building, and deletes them
	 * again.
	 */
	private static Test withData(Test test) {
		return new TestSetup(test) {
			@Override
			protected void setUp() throws Exception {
				ThreadContext.inSystemContext(TestAssistantTools.class, () -> {
					List<TLObject> before = new ArrayList<>(instances("ehome.project:Project"));
					importSample(TESTSYSTEM);
					_project = single(SharedData.newProjects(before));
					inTransaction(() -> {
						_installer = AssistantFixture.person("ehomeToolsInstaller", "ehome.installers");
						_viewer = AssistantFixture.person("ehomeToolsViewer", null);
						_viewingInstaller = AssistantFixture.person("ehomeToolsViewingInstaller", "ehome.installers");
						// Access per project (haumacher/ehome #152): the installer edits the imported
						// project, the viewer reads it; neither holds a role in the foreign one.
						ProjectMembers.add(_project, _installer, Role.editor);
						ProjectMembers.add(_project, _viewer, Role.viewer);
						ProjectMembers.add(_project, _viewingInstaller, Role.viewer);
						_foreign = create("ehome.project:Project");
						_foreign.tUpdateByName("name", "Fremdes Projekt");
						TLObject building = create("ehome.building:Building");
						building.tUpdateByName("name", "Fremdhaus");
						_foreign.tUpdateByName("buildings", List.of(building));
					});
					return null;
				});
			}

			@Override
			protected void tearDown() throws Exception {
				ThreadContext.inSystemContext(TestAssistantTools.class, () -> {
					SharedData.deleteProjects(List.of(_project, _foreign));
					inTransaction(() -> {
						for (Person person : List.of(_installer, _viewer, _viewingInstaller)) {
							if (person.tValid()) {
								person.tDelete();
							}
						}
					});
					return null;
				});
			}
		};
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(
			withData(new TestSuite(TestAssistantTools.class)),
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE,
			SearchBuilder.Module.INSTANCE, TLSecurityDeviceManager.Module.INSTANCE,
			InitialRolesManager.Module.INSTANCE, InitialGroupManager.Module.INSTANCE, AccessManager.Module.INSTANCE,
			SecurityConfigurationService.Module.INSTANCE, AiToolAccessService.Module.INSTANCE,
			TopLogicAgentDefinitions.Module.INSTANCE, AgentApplicationService.Module.INSTANCE,
			AgentPolicyService.Module.INSTANCE, AgentRuntimeService.Module.INSTANCE));
	}

}
