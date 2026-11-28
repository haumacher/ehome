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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

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
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.wrap.person.Person;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.model.TLClassifier;
import com.top_logic.model.TLObject;
import com.top_logic.model.search.expr.config.SearchBuilder;
import com.top_logic.model.security.SecurityConfigurationService;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.ai.core.agent.AgentApplicationService;
import com.top_logic.ai.core.agent.AgentPolicyService;
import com.top_logic.ai.core.agent.AgentRuntimeService;
import com.top_logic.ai.core.agent.TopLogicAgentDefinitions;
import com.top_logic.ai.core.message.Message;
import com.top_logic.ai.core.message.ToolCall;
import com.top_logic.ai.core.message.ToolResultMessage;
import com.top_logic.ai.core.provider.ProviderRequest;
import com.top_logic.ai.core.provider.ScriptedModel.Reply;
import com.top_logic.ai.core.provider.ScriptedModel.Script;
import com.top_logic.ai.core.provider.ScriptedProvider;
import com.top_logic.ai.core.security.AiToolAccessService;
import com.top_logic.ai.tool.api.ToolResult;
import com.top_logic.tool.boundsec.manager.AccessManager;
import com.top_logic.util.Resources;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.ai.TemplateTools;
import de.haumacher.ehome.knx.ExtIds;
import de.haumacher.ehome.knx.ParameterValues;
import de.haumacher.ehome.model.datapoint.DatapointSubtype;
import de.haumacher.ehome.model.operational.Case;
import de.haumacher.ehome.model.operational.Interface;
import de.haumacher.ehome.model.operational.ParameterView;
import de.haumacher.ehome.model.operational.Switch;
import de.haumacher.ehome.model.template.ObjectOverlay;
import de.haumacher.ehome.model.template.Preset;
import de.haumacher.ehome.model.template.PresetObject;
import de.haumacher.ehome.model.template.PresetValue;
import de.haumacher.ehome.modules.ModuleParts;
import de.haumacher.ehome.programming.DeviceConfiguration;
import de.haumacher.ehome.programming.DeviceSettings;
import de.haumacher.ehome.programming.DownloadImage;
import de.haumacher.ehome.programming.GroupAddressConnections;
import de.haumacher.ehome.programming.GroupAddresses;
import de.haumacher.ehome.project.ProjectMembers;
import de.haumacher.ehome.project.ProjectMembers.Role;
import de.haumacher.ehome.runtime.Json;
import de.haumacher.ehome.template.ChannelShape;
import de.haumacher.ehome.template.DeviceTemplates;
import de.haumacher.ehome.template.I18NConstants;
import de.haumacher.ehome.template.ObjectKinds;
import de.haumacher.ehome.template.ObjectTypes;
import de.haumacher.ehome.template.Presets;

/**
 * The assistant's tools for the device templates (haumacher/ehome #174) on the KNX test system
 * (<code>samples/KNX-Testsystem.knxproj</code>), imported twice (two projects running the same
 * programs): the Gira Tastsensor 1.3.50 (<code>M-0008_A-10D7-11-A0E5-O000A</code>, 67 kinds of
 * objects, #166) and the MDT AKK-04 1.3.5 (<code>M-0083_A-000C-21-83B5</code>, presets of its channel
 * A, #146).
 *
 * <ul>
 * <li><code>objectKinds</code> answers the kinds of {@link ObjectKinds#kinds(TLObject, TLObject, Resources)}
 * in the program's base language, each with the ID of its first object.</li>
 * <li>With a project, only that project's devices mark a kind: the test connects the Temperaturwert of
 * a key in the first project only, and the second project does not mark it.</li>
 * <li><code>setObjectKindType</code> with 9.001 on "Temperaturwert" (setting "Funktion: Wertgeber 2Byte
 * › Funktionsweise: Temperaturwertgeber") types every object of the kind in the template and no other
 * object of the program; the empty type removes it again.</li>
 * <li>A preset saved through the tools equals one saved as the page saves it
 * ({@link Presets#save}); deleting it through the tools deletes it.</li>
 * <li>An installer who is no E-Home administrator reads with every tool and gets the application's
 * refusal from every writing tool, nothing changed - in the chat's agent as in a direct call.</li>
 * </ul>
 */
@SuppressWarnings("javadoc")
public class TestTemplateTools extends BasicTestCase {

	private static final String SCRIPTED_MODEL = "scripted-model";

	private static final String TESTSYSTEM = "samples/KNX-Testsystem.knxproj";

	private static final String GIRA = "M-0008_A-10D7-11-A0E5-O000A";

	private static final String AKK = "M-0083_A-000C-21-83B5";

	private static final String TEMPERATURE = "Funktion: Wertgeber 2Byte › Funktionsweise: Temperaturwertgeber";

	private static List<TLObject> _projects;

	private static Person _administrator;

	private static Person _installer;

	/** The templates of the programs before the test: those it creates are deleted again. */
	private static Set<TLObject> _templatesBefore;

	private static int _callId;

	public void testObjectKinds() throws Exception {
		TLObject program = program(GIRA);
		// Every account reads the kinds; the texts in the program's base language (German).
		Map<String, Object> answer = answer(as(_installer, () -> new TemplateTools().objectKinds(program, null, null, null)));
		List<Map<String, Object>> expected = ObjectKinds.kinds(program, null, Resources.getInstance(Locale.GERMAN));
		assertEquals(67, expected.size());
		assertEquals(67, number(answer.get("kinds")));
		assertEquals(410, number(answer.get("objects")));
		List<Map<String, Object>> listed = list(answer.get("list"));
		assertEquals(expected.size(), listed.size());
		for (int n = 0; n < expected.size(); n++) {
			Map<String, Object> kind = expected.get(n);
			Map<String, Object> entry = listed.get(n);
			assertEquals(tid(ObjectKinds.placements(kind).get(0)), entry.get("id"));
			assertEquals(kind.get(ObjectKinds.OBJECT), entry.get("object"));
			assertEquals(kind.get(ObjectKinds.SETTING), entry.get("setting"));
			assertEquals(kind.get(ObjectKinds.CHANNELS), entry.get("channels"));
			assertEquals(((Number) kind.get(ObjectKinds.SIZE)).intValue(), number(entry.get("size")));
			assertEquals(ObjectKinds.placements(kind).size(), number(entry.get("objects")));
			assertFalse("No project, no marks: " + entry, entry.containsKey("untyped"));
		}

		// 'untypedOnly' is a question about a project.
		ToolResult noProject = as(_installer, () -> new TemplateTools().objectKinds(program, null, Boolean.TRUE, null));
		assertTrue(noProject.text(), noProject.failed());
	}

	public void testKindTypeAndProjectMarks() throws Exception {
		TLObject program = program(GIRA);
		Map<String, Object> temperature = kind(program, "Temperaturwert", TEMPERATURE);
		Map<String, Object> value255 = kind(program, "Kanal 1 Wert",
			"Funktion: 2-Kanal Bedienung › Funktion Kanal 1: Wertgeber 0...255 (1 Byte)");
		String temperatureId = tid(ObjectKinds.placements(temperature).get(0));
		TLObject first = _projects.get(0);
		TLObject second = _projects.get(1);

		// A key of the first project's push-button set to "Temperaturwertgeber", its value connected.
		TLObject device = device(first, 50);
		Interface connected = ObjectKinds.placements(temperature).stream()
			.filter(p -> ObjectTypes.connector(device, p) == null).findFirst().orElseThrow();
		inTransaction(() -> {
			select(device, connected);
			TLObject range = GroupAddresses.ranges(first).get(0);
			TLObject address = GroupAddresses.create(range, Integer.valueOf(201), "Temperatur Tool", null);
			DeviceSettings.connect(device, connected, address, kind("send"));
		});

		// The first project marks the kind, the second (the same programs, its key not set) does not.
		List<String> firstMarks = untypedIds(program, first);
		List<String> secondMarks = untypedIds(program, second);
		assertTrue(firstMarks.toString(), firstMarks.contains(temperatureId));
		assertFalse(secondMarks.toString(), secondMarks.contains(temperatureId));
		assertEquals(List.of(temperatureId), firstMarks.stream().filter(id -> !secondMarks.contains(id)).toList());
		assertEquals(firstMarks.size(), secondMarks.size() + 1);

		// The installer is refused through the chat's agent, with the application's reason; nothing changes.
		List<ProviderRequest> sent = runs(_installer, "Type the temperature value.",
			call("setObjectKindType", "program", tid(program), "kind", temperatureId, "datapointType", "9.001"));
		assertTrue("Every account is offered the tools: " + toolNames(sent.get(0)),
			toolNames(sent.get(0)).containsAll(List.of("objectKinds", "setObjectKindType", "savePreset")));
		String refusal = toolAnswer(sent, "setObjectKindType");
		assertTrue(refusal, refusal.contains("\"refused\""));
		assertTrue(refusal, refusal.contains(as(_installer, () -> text(I18NConstants.ERROR_TEMPLATES_NOT_ALLOWED))));
		assertTrue(ObjectKinds.types(temperature).isEmpty());

		// The administrator types the kind through the chat's agent: every object of the kind, no other.
		sent = runs(_administrator, "Type the temperature value.",
			call("setObjectKindType", "program", tid(program), "kind", temperatureId, "datapointType", "9.001"));
		assertFalse(toolAnswer(sent, "setObjectKindType"), toolAnswer(sent, "setObjectKindType").contains("\"refused\""));
		TLObject celsius = subtype("DPST-9-1");
		TLObject template = DeviceTemplates.template(program);
		assertNotNull(template);
		assertEquals(celsius, ObjectKinds.type(template, temperature));
		Set<Interface> kindPlacements = new HashSet<>(ObjectKinds.placements(temperature));
		for (Interface placed : DeviceTemplates.placements(program)) {
			TLObject overlay = ObjectTypes.overlay(placed);
			boolean typed = overlay != null && !((ObjectOverlay) overlay).getDatapointTypes().isEmpty();
			assertEquals(placed.toString(), kindPlacements.contains(placed), typed);
		}
		assertEquals(celsius, ObjectTypes.effective(device, connected));
		assertEquals(List.of(), ObjectKinds.types(value255));
		assertFalse(untypedIds(program, first).contains(temperatureId));

		// A type of another size is refused, the empty type removes the template's type.
		ToolResult wrongSize = as(_administrator, () -> new TemplateTools().setObjectKindType(
			AssistantFixture.toolContext(), program, ObjectKinds.placements(temperature).get(0), "1.001"));
		assertTrue(wrongSize.text(), wrongSize.failed());
		assertEquals(celsius, ObjectKinds.type(template, temperature));
		ToolResult removed = asAdministratorInTransaction(() -> new TemplateTools().setObjectKindType(
			AssistantFixture.toolContext(), program, ObjectKinds.placements(temperature).get(3), null));
		assertFalse(removed.text(), removed.failed());
		assertNull(ObjectKinds.type(template, temperature));
		assertNull(ObjectTypes.effective(device, connected));
	}

	public void testPresets() throws Exception {
		TLObject program = program(AKK);
		TLObject actuator = device(_projects.get(0), 5);
		String channel = channelKey(actuator, "Channel A", "Kanal A");

		// The candidates as the dialog offers them.
		Map<String, Object> candidates =
			answer(as(_installer, () -> new TemplateTools().presetCandidates(actuator, channel)));
		List<Map<String, Object>> offered = list(candidates.get("values"));
		List<String> proposed = new ArrayList<>();
		for (Presets.Candidate candidate : Presets.candidates(actuator, channel)) {
			if (candidate.proposed()) {
				proposed.add(ModuleParts.partId(candidate.view()));
			}
		}
		assertEquals(proposed, offered.stream().filter(v -> Boolean.TRUE.equals(v.get("proposed")))
			.map(v -> v.get("id")).toList());
		List<Interface> objects = Presets.channelObjects(actuator, channel);
		Interface command = object(objects, 0);
		Interface state = object(objects, 7);
		List<Map<String, Object>> offeredObjects = list(candidates.get("objects"));
		assertTrue(offeredObjects.toString(), offeredObjects.stream()
			.anyMatch(o -> ModuleParts.partId(command).equals(o.get("id"))));

		List<Map<String, Object>> roles = List.of(
			Map.of("object", ModuleParts.partId(command), "role", "command"),
			Map.of("object", ModuleParts.partId(state), "role", "state"));

		// Not an administrator: refused, nothing saved.
		ToolResult refused = as(_installer, () -> new TemplateTools().savePreset(AssistantFixture.toolContext(),
			actuator, channel, "Schalten Werkzeug", "actuator", "light", null, null, roles));
		assertTrue(refused.text(), refused.failed() && refused.text().contains("\"refused\""));
		assertTrue(presetsOf(program).stream().noneMatch(p -> "Schalten Werkzeug".equals(((Preset) p).getName())));

		// The page's preset and the tool's: equal but for the name.
		TLObject[] page = { null };
		inTransaction(() -> page[0] = Presets.save(actuator, channel, "Schalten Seite",
			classifier("ehome.template:PresetPurpose#actuator"), classifier("ehome.runtime:FunctionKind#light"),
			List.of(), views(actuator, channel), Map.of(command, classifier("ehome.template:PresetRole#command"), state,
				classifier("ehome.template:PresetRole#state"))));
		ToolResult saved = asAdministratorInTransaction(() -> new TemplateTools().savePreset(
			AssistantFixture.toolContext(), actuator, channel, "Schalten Werkzeug", "actuator", "light", null, null,
			roles));
		assertFalse(saved.text(), saved.failed());
		TLObject tool = presetsOf(program).stream().filter(p -> "Schalten Werkzeug".equals(((Preset) p).getName()))
			.findFirst().orElseThrow();
		assertEquals(describe(page[0]), describe(tool));

		// The reading tools list it and show its values.
		String listing = as(_installer, () -> new TemplateTools().presets(program)).text();
		assertTrue(listing, listing.contains(tid(tool)) && listing.contains(tid(page[0])));
		Map<String, Object> read = answer(as(_installer, () -> new TemplateTools().preset(tool)));
		assertEquals(ExtIds.list(tool, "values").size(), list(read.get("values")).size());
		assertEquals("light", read.get("kind"));

		// Deleting: refused for the installer, done for the administrator.
		ToolResult notDeleted = as(_installer,
			() -> new TemplateTools().deletePreset(AssistantFixture.toolContext(), tool));
		assertTrue(notDeleted.text(), notDeleted.failed() && notDeleted.text().contains("\"refused\""));
		assertTrue(tool.tValid());
		ToolResult deleted = asAdministratorInTransaction(
			() -> new TemplateTools().deletePreset(AssistantFixture.toolContext(), tool));
		assertFalse(deleted.text(), deleted.failed());
		assertFalse(tool.tValid());
		inTransaction(() -> page[0].tDelete());
	}

	// ---------------------------------------------------------------------------------------------

	/**
	 * What a preset holds, without its name: channel, purpose, kind, operations, the values (parameter,
	 * encoded value) and the objects with their roles.
	 */
	private static List<Object> describe(TLObject preset) {
		List<Object> result = new ArrayList<>();
		Preset typed = (Preset) preset;
		result.add(typed.getChannel());
		result.add(typed.getPurpose());
		result.add(typed.getKind());
		result.add(new ArrayList<>(typed.getOperations()));
		for (TLObject value : ExtIds.list(preset, "values")) {
			result.add(List.of(((PresetValue) value).getParameter(), ParameterValues.encode(value)));
		}
		for (TLObject object : ExtIds.list(preset, "objects")) {
			result.add(List.of(((PresetObject) object).getPlacement(), ((PresetObject) object).getRole()));
		}
		return result;
	}

	private static List<ParameterView> views(TLObject device, String channel) {
		List<ParameterView> result = new ArrayList<>();
		for (Presets.Candidate candidate : Presets.candidates(device, channel)) {
			if (candidate.proposed()) {
				result.add(candidate.view());
			}
		}
		return result;
	}

	private static List<TLObject> presetsOf(TLObject program) {
		TLObject template = DeviceTemplates.template(program);
		return template == null ? List.of() : ExtIds.list(template, "presets");
	}

	/** The IDs of the kinds objectKinds marks as connected without a type in the project. */
	private static List<String> untypedIds(TLObject program, TLObject project) throws Exception {
		Map<String, Object> answer =
			answer(as(_administrator, () -> new TemplateTools().objectKinds(program, project, Boolean.TRUE, null)));
		List<String> result = new ArrayList<>();
		for (Map<String, Object> entry : list(answer.get("list"))) {
			assertEquals(Boolean.TRUE, entry.get("untyped"));
			result.add((String) entry.get("id"));
		}
		return result;
	}

	/** Runs a writing tool as the administrator in a transaction committed afterwards. */
	private static ToolResult asAdministratorInTransaction(java.util.function.Supplier<ToolResult> call) {
		ToolResult[] result = { null };
		as(_administrator, () -> {
			try (com.top_logic.knowledge.service.Transaction tx =
				PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				result[0] = call.get();
				tx.commit();
			}
			return null;
		});
		return result[0];
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> answer(ToolResult result) throws Exception {
		assertFalse(result.text(), result.failed());
		String text = result.text();
		return (Map<String, Object>) Json.parse(text.substring(text.indexOf('\n') + 1), 20);
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> list(Object value) {
		return (List<Map<String, Object>>) value;
	}

	private static int number(Object value) {
		return ((Number) value).intValue();
	}

	private static Map<String, Object> kind(TLObject program, String object, String setting) {
		for (Map<String, Object> kind : ObjectKinds.kinds(program, null, Resources.getInstance(Locale.GERMAN))) {
			if (object.equals(kind.get(ObjectKinds.OBJECT)) && setting.equals(kind.get(ObjectKinds.SETTING))) {
				return kind;
			}
		}
		fail("No kind " + object + " / " + setting);
		return null;
	}

	/**
	 * Sets the device's parameters to the values of the cases of the switches above the placed object,
	 * so that it is in effect.
	 */
	private static void select(TLObject device, Interface placed) {
		List<TLObject> path = new ArrayList<>();
		for (TLObject current = placed; current != null; current = current.tContainer()) {
			path.add(0, current);
		}
		for (int n = 1; n < path.size(); n++) {
			if (path.get(n - 1) instanceof Switch choice && path.get(n) instanceof Case alternative
				&& alternative.getPattern() != null && choice.getInput() != null) {
				DeviceSettings.setValue(device, choice.getInput(),
					Integer.valueOf(alternative.getPattern().trim().split("\\s+")[0]));
			}
		}
	}

	private static String channelKey(TLObject device, String... names) {
		List<String> wanted = List.of(names);
		for (String key : Presets.channels(device)) {
			for (Interface placed : ChannelShape.of(device).get(key).getObjects()) {
				for (Locale locale : List.of(Locale.ENGLISH, Locale.GERMAN)) {
					if (wanted.contains(GroupAddressConnections.objectName(placed, Resources.getInstance(locale)))) {
						return key;
					}
				}
				break;
			}
		}
		fail("No channel " + wanted);
		return null;
	}

	private static Interface object(List<Interface> objects, int number) {
		for (Interface placed : objects) {
			if (placed.getPort().getIndex() == number) {
				return placed;
			}
		}
		fail("No object " + number + " in " + objects);
		return null;
	}

	/** The device of line 1.3 with the given number in the project. */
	private static TLObject device(TLObject project, int number) {
		int address = 1 << 12 | 3 << 8 | number;
		for (TLObject device : SharedData.within("ehome.physical:Device", List.of(project))) {
			Integer individual = DownloadImage.individualAddress(device);
			if (individual != null && individual.intValue() == address) {
				return device;
			}
		}
		fail("No device 1.3." + number);
		return null;
	}

	private static TLObject program(String extId) {
		TLObject result = instances("ehome.operational:Program").stream()
			.filter(p -> extId.equals(ExtIds.extId(p))).findFirst().orElseThrow();
		assertEquals(extId, DeviceConfiguration.program(device(_projects.get(0), extId.equals(GIRA) ? 50 : 5))
			.getExtId());
		return result;
	}

	private static TLObject subtype(String extId) {
		return instances("ehome.datapoint:DatapointSubtype").stream()
			.filter(t -> extId.equals(((DatapointSubtype) t).getExtId())).findFirst().orElseThrow();
	}

	private static TLClassifier kind(String name) {
		return classifier("ehome.physical:ConnectionType#" + name);
	}

	private static TLClassifier classifier(String name) {
		return (TLClassifier) TLModelUtil.findPart(name);
	}

	private static String text(com.top_logic.basic.util.ResKey key) {
		return Resources.getInstance().getString(key);
	}

	private static List<ProviderRequest> runs(Person person, String question, ToolCall... calls) {
		ScriptedProvider.install(Script.of(Reply.of(calls), Reply.text("Done.")));
		as(person, () -> AssistantFixture.run(conversation(question), SCRIPTED_MODEL));
		try {
			PersistencyLayer.getKnowledgeBase().refetch();
		} catch (com.top_logic.knowledge.service.RefetchTimeout ex) {
			throw new RuntimeException(ex);
		}
		return ScriptedProvider.requests();
	}

	private static ToolCall call(String tool, Object... arguments) {
		Map<String, Object> json = new LinkedHashMap<>();
		for (int n = 0; n < arguments.length; n += 2) {
			json.put((String) arguments[n], arguments[n + 1]);
		}
		return new ToolCall("t" + (++_callId), tool, Json.write(json));
	}

	/** The answer of the given tool as the model read it in the last request. */
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
	 * Imports the test system twice for all tests of the class, with an E-Home administrator and an
	 * installer editing the first project, and deletes them again with the templates the tests created.
	 */
	private static Test withData(Test test) {
		return new TestSetup(test) {
			@Override
			protected void setUp() throws Exception {
				ThreadContext.inSystemContext(TestTemplateTools.class, () -> {
					_templatesBefore = new HashSet<>(instances("ehome.template:DeviceTemplate"));
					List<TLObject> before = new ArrayList<>(instances("ehome.project:Project"));
					importSample(TESTSYSTEM);
					importSample(TESTSYSTEM);
					_projects = SharedData.newProjects(before);
					assertEquals(2, _projects.size());
					inTransaction(() -> {
						_administrator = AssistantFixture.person("ehomeTemplateAdministrator", "ehome.administrators");
						_installer = AssistantFixture.person("ehomeTemplateInstaller", "ehome.installers");
						ProjectMembers.add(_projects.get(0), _installer, Role.editor);
					});
					return null;
				});
			}

			@Override
			protected void tearDown() throws Exception {
				ThreadContext.inSystemContext(TestTemplateTools.class, () -> {
					SharedData.deleteProjects(_projects);
					inTransaction(() -> {
						for (TLObject template : instances("ehome.template:DeviceTemplate")) {
							if (!_templatesBefore.contains(template) && template.tValid()) {
								template.tDelete();
							}
						}
						for (Person person : List.of(_administrator, _installer)) {
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
			withData(new TestSuite(TestTemplateTools.class)),
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE,
			SearchBuilder.Module.INSTANCE, TLSecurityDeviceManager.Module.INSTANCE,
			InitialRolesManager.Module.INSTANCE, InitialGroupManager.Module.INSTANCE, AccessManager.Module.INSTANCE,
			SecurityConfigurationService.Module.INSTANCE, AiToolAccessService.Module.INSTANCE,
			TopLogicAgentDefinitions.Module.INSTANCE, AgentApplicationService.Module.INSTANCE,
			AgentPolicyService.Module.INSTANCE, AgentRuntimeService.Module.INSTANCE));
	}

}
