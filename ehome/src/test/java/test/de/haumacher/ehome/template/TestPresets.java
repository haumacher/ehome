/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.template;

import static test.de.haumacher.ehome.importer.TestEtsImport.importSample;
import static test.de.haumacher.ehome.importer.TestEtsImport.instances;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

import junit.framework.Test;

import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;
import test.de.haumacher.ehome.importer.SharedData;
import test.de.haumacher.ehome.security.AccountFixture;
import test.de.haumacher.ehome.view.ViewCommandScan;

import com.top_logic.base.security.device.TLSecurityDeviceManager;
import com.top_logic.base.services.InitialGroupManager;
import com.top_logic.base.services.InitialRolesManager;
import com.top_logic.basic.thread.ThreadContext;
import com.top_logic.basic.util.ResKey;
import com.top_logic.knowledge.service.KnowledgeBase;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.knowledge.wrap.person.Person;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.model.TLClassifier;
import com.top_logic.model.TLObject;
import com.top_logic.model.search.expr.config.SearchBuilder;
import com.top_logic.model.security.ModelAccessRights;
import com.top_logic.model.security.SecurityConfigurationService;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.tool.boundsec.manager.AccessManager;
import com.top_logic.tool.boundsec.simple.CommandGroupRegistry;
import com.top_logic.tool.boundsec.simple.SimpleBoundCommandGroup;
import com.top_logic.util.Resources;
import com.top_logic.util.TLContextManager;
import com.top_logic.util.error.TopLogicException;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.knx.ParameterValues;
import de.haumacher.ehome.model.operational.Interface;
import de.haumacher.ehome.model.operational.ParameterView;
import de.haumacher.ehome.model.operational.Program;
import de.haumacher.ehome.planning.DevicePlanning;
import de.haumacher.ehome.programming.DeviceConfiguration;
import de.haumacher.ehome.programming.DownloadImage;
import de.haumacher.ehome.programming.GroupAddressConnections;
import de.haumacher.ehome.project.ProjectMembers;
import de.haumacher.ehome.project.ProjectOwners;
import de.haumacher.ehome.template.ChannelShape;
import de.haumacher.ehome.template.DeviceTemplates;
import de.haumacher.ehome.template.I18NConstants;
import de.haumacher.ehome.template.Presets;

/**
 * The presets of the device templates (haumacher/ehome #146) on the programs of the KNX test system
 * (<code>samples/KNX-Testsystem.knxproj</code>): the MDT AKK-04 1.3.5 (<code>M-0083_A-000C-21-83B5</code>,
 * four channels "Channel A" to "Channel D", each in a switch on its mode) and the Gira Tastsensor 3
 * 1.3.50 (<code>M-0008_A-10D7-11-A0E5-O000A</code>, keys and status LEDs as blocks in the switches on
 * the operation concept of two keys).
 *
 * <ul>
 * <li>The actuator: "Schalten" saved from channel A holds the channel's mode (its default, an
 * external input of the channel) and the values of A that differ; declared alike, channels B and D
 * take it by position (on D the mode changes from switch impulse to switching, and the objects of
 * A's mode come into effect at their counterparts). Channel C differs from A in the program: in
 * switching mode its scene object is in effect for "Learn scene" = 1 only, A's for 1 and 2. The
 * declaration of C is refused with that reason.</li>
 * <li>The push-button: "Taste: Umschalten" saved from key 1 (with the operation concept of keys 1
 * and 2, "keys") and "LED: Status" from its status LED, applied to a planned device, give it the
 * values and objects in effect of the configured one. "Wippe: Umschalten" saved from rocker 1 of a
 * planned device in its default (rocker) wants the other operation concept and is refused on a
 * device whose key 1 has "Taste: Umschalten", naming both.</li>
 * <li>A second import of the project duplicates no template and no preset; deleting the projects
 * keeps the presets (master data) and takes the uses with the devices.</li>
 * <li>Access: an editor of the project applies a preset to a device but neither saves nor changes
 * one; the commands of the views say the same.</li>
 * </ul>
 *
 * <p>
 * The checks share one import of the test system. The presets and the templates the test creates are
 * deleted again (TestModulePresets checks the presets of module instances).
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestPresets extends BasicTestCase {

	private static final String AKK = "M-0083_A-000C-21-83B5";

	private static final String GIRA = "M-0008_A-10D7-11-A0E5-O000A";

	/** The keys of the channels: by the names of their objects in the fallback language, which the suite's data decides. */
	private String _channelA;

	private String _channelB;

	private String _channelC;

	private String _channelD;

	private String _key1;

	private String _led1;

	private String _rocker1;

	private final List<TLObject> _presets = new ArrayList<>();

	private final List<Person> _persons = new ArrayList<>();

	/** The templates the test created by saving presets. */
	private final List<TLObject> _templates = new ArrayList<>();

	public void testPresets() throws Exception {
		List<TLObject> before = instances("ehome.project:Project");
		importSample("samples/KNX-Testsystem.knxproj");
		List<TLObject> projects = SharedData.newProjects(before);
		try {
			assertEquals(1, projects.size());
			TLObject project = projects.get(0);
			TLObject actuator = device(projects, 1, 3, 5);
			TLObject keys = device(projects, 1, 3, 50);
			assertEquals(AKK, DeviceConfiguration.program(actuator).getExtId());
			assertEquals(GIRA, DeviceConfiguration.program(keys).getExtId());

			_channelA = key(actuator, "Channel A", "Kanal A");
			_channelB = key(actuator, "Channel B", "Kanal B");
			_channelC = key(actuator, "Channel C", "Kanal C");
			_channelD = key(actuator, "Channel D", "Kanal D");
			_key1 = key(keys, "Taste 1", "Key 1");
			_led1 = key(keys, "Status-LED 1", "Status LED 1");
			_rocker1 = key(keys, "Wippe 1", "Rocker 1");
			checkActuator(actuator);
			checkKeys(keys);
			checkAccess(project, actuator);
			checkReimport(before, actuator, keys);
		} finally {
			SharedData.deleteProjects(SharedData.newProjects(before));
			cleanUp();
		}
	}

	private void checkActuator(TLObject device) {
		assertTrue(Presets.channels(device).containsAll(List.of(_channelA, _channelB, _channelC, _channelD)));
		String labelA = Presets.channelLabel(device, _channelA);
		String labelB = Presets.channelLabel(device, _channelB);
		String labelC = Presets.channelLabel(device, _channelC);
		ParameterView modeA = mode(device, _channelA);
		assertEquals("ChannelMode_0", modeA.getParameter().getName());

		// The candidates: the mode (external input, at its default "switching") is proposed.
		Presets.Candidate mode = candidate(device, _channelA, modeA);
		assertTrue(mode.proposed());
		assertFalse(mode.inChannel());
		assertFalse(mode.differs());

		List<Interface> objects = Presets.channelObjects(device, _channelA);
		Map<Interface, TLObject> roles = new LinkedHashMap<>();
		roles.put(object(objects, 0), role("command"));
		roles.put(object(objects, 7), role("state"));
		TLObject schalten = save(device, _channelA, "Schalten", purpose("actuator"), kind("light"), List.of(),
			proposed(device, _channelA), roles);
		TLObject template = schalten.tContainer();
		assertSame(template, DeviceTemplates.template(DeviceConfiguration.program(device)));
		assertTrue(Presets.values(device, _channelA, schalten).containsKey(modeA));
		assertEquals(labelA, Presets.presetChannelLabel(schalten));

		// Not declared alike: offered on its own channel only.
		assertEquals(List.of(schalten), Presets.offered(device, _channelA));
		assertEquals(List.of(), Presets.offered(device, _channelB));
		assertEquals(I18NConstants.ERROR_PRESET_OTHER_CHANNEL__PRESET_CHANNEL.fill(labelA, labelB),
			Presets.offerVeto(device, _channelB, schalten));

		// Channel C differs from A in a condition of its scene object: the declaration is refused.
		try {
			inTransaction(() -> Presets.declareAlike(template, List.of(_channelA, _channelB, _channelC, _channelD)));
			fail("Channel C must not be declared alike to channel A.");
		} catch (TopLogicException ex) {
			String text = text(ex.getErrorKey());
			assertTrue(text, text.startsWith(labelC + " cannot be declared alike to " + labelA + ": "));
			assertTrue(text, text.contains(ChannelShape.objectLabel(device, object(shape(device, _channelA).getObjects(), 6)))
				&& text.contains(ChannelShape.objectLabel(device, object(shape(device, _channelC).getObjects(), 30))));
			assertTrue(text, text.contains("under other conditions"));
		}
		assertEquals(_channelB, Presets.model(template, _channelB));
		assertNotNull(Presets.alikeVeto(template, _channelC, _channelA));
		assertNull(Presets.alikeVeto(template, _channelB, _channelA));
		inTransaction(() -> Presets.declareAlike(template, List.of(_channelA, _channelB, _channelD)));
		assertEquals(_channelA, Presets.model(template, _channelD));
		assertEquals(List.of(_channelA, _channelB, _channelD), Presets.alikeGroup(template, _channelB));
		assertEquals(List.of(schalten), Presets.offered(device, _channelB));
		assertEquals(List.of(schalten), Presets.offered(device, _channelD));
		assertEquals(List.of(), Presets.offered(device, _channelC));

		// Channel D is in switch impulse mode (3): "Schalten" switches it, B stays in switching mode.
		ParameterView modeD = mode(device, _channelD);
		assertEquals("ChannelMode_3", modeD.getParameter().getName());
		assertEquals("3", encoded(device, modeD));
		Map<ParameterView, Object> wanted = Presets.values(device, _channelA, schalten);
		inTransaction(() -> {
			Presets.apply(device, _channelB, schalten);
			Presets.apply(device, _channelD, schalten);
		});
		assertEquals("1", encoded(device, modeD));
		for (String key : List.of(_channelB, _channelD)) {
			ChannelShape.Mapping mapping = shape(device, _channelA).map(shape(device, key), device);
			assertNull(mapping.getProblem());
			for (Map.Entry<ParameterView, Object> entry : wanted.entrySet()) {
				ParameterView counterpart = (ParameterView) mapping.counterpart(entry.getKey());
				assertEquals(key + ": " + entry.getKey(), encoded(device, entry.getKey()), encoded(device, counterpart));
			}
			// The objects in effect: the counterparts of A's.
			List<Interface> mapped = new ArrayList<>();
			for (Interface placed : Presets.channelObjects(device, _channelA)) {
				mapped.add((Interface) mapping.counterpart(placed));
			}
			assertEquals(key, mapped, Presets.channelObjects(device, key));
			assertEquals(schalten, Presets.use(device, key).tValueByName("preset"));
			Map<String, Interface> byRole = Presets.objects(device, key, schalten);
			assertEquals(mapping.counterpart(object(objects, 0)), byRole.get("command"));
			assertEquals(mapping.counterpart(object(objects, 7)), byRole.get("state"));
		}
		assertEquals(12, Presets.objects(device, _channelB, schalten).get("command").getPort().getIndex());

		// Taking the declaration of the model back dissolves the group.
		inTransaction(() -> Presets.removeAlike(template, List.of(_channelA)));
		assertEquals(_channelD, Presets.model(template, _channelD));
		assertEquals(List.of(), Presets.offered(device, _channelD));
	}

	private void checkKeys(TLObject configured) {
		assertTrue(Presets.channels(configured).containsAll(List.of(_key1, _led1, _rocker1)));
		ParameterView concept = concept(configured);
		// Key 1 is in effect through the operation concept "keys" (1), the default being the rocker (0).
		assertEquals("1", encoded(configured, concept));
		assertTrue(candidate(configured, _key1, concept).proposed());

		List<Interface> keyObjects = Presets.channelObjects(configured, _key1);
		assertEquals(1, keyObjects.size());
		TLObject toggle = save(configured, _key1, "Taste: Umschalten", purpose("source"), null,
			List.of(operation("toggle")), proposed(configured, _key1), Map.of(keyObjects.get(0), role("sendCommand")));
		List<Interface> ledObjects = Presets.channelObjects(configured, _led1);
		assertFalse(ledObjects.isEmpty());
		TLObject status = save(configured, _led1, "LED: Status", purpose("source"), null,
			List.of(operation("showStatus")), proposed(configured, _led1), Map.of(ledObjects.get(0), role("receiveStatus")));
		assertFalse("The status LED does not depend on the operation concept.",
			Presets.values(configured, _led1, status).containsKey(concept));

		// A role outside the purpose is refused.
		try {
			save(configured, _key1, "Falsch", purpose("source"), null, List.of(operation("toggle")),
				proposed(configured, _key1), Map.of(keyObjects.get(0), role("command")));
			fail("A source preset has no command role.");
		} catch (TopLogicException ex) {
			assertEquals(I18NConstants.ERROR_PRESET_ROLE__OBJECT_ROLE.fill(
				ChannelShape.objectLabel(configured, keyObjects.get(0)), role("command")), ex.getErrorKey());
		}

		// A planned device of the program: key 1 is not in effect (rocker), the presets bring it.
		TLObject planned = plan(configured);
		assertEquals("0", encoded(planned, concept));
		assertEquals(List.of(), Presets.channelObjects(planned, _key1));
		assertEquals(List.of(toggle), Presets.offered(planned, _key1));
		inTransaction(() -> {
			Presets.apply(planned, _key1, toggle);
			Presets.apply(planned, _led1, status);
		});
		for (TLObject preset : List.of(toggle, status)) {
			String key = (String) preset.tValueByName("channel");
			for (ParameterView view : Presets.values(configured, key, preset).keySet()) {
				assertEquals(view.toString(), encoded(configured, view), encoded(planned, view));
			}
			assertEquals(key, Presets.channelObjects(configured, key), Presets.channelObjects(planned, key));
		}

		// The rocker's preset wants the other operation concept: refused on the planned device, naming both.
		TLObject rocker = plan(configured);
		List<Interface> rockerObjects = Presets.channelObjects(rocker, _rocker1);
		assertFalse(rockerObjects.isEmpty());
		TLObject rockerToggle = save(rocker, _rocker1, "Wippe: Umschalten", purpose("source"), null,
			List.of(operation("toggle")), proposed(rocker, _rocker1), Map.of(rockerObjects.get(0), role("sendCommand")));
		assertTrue(Presets.values(rocker, _rocker1, rockerToggle).containsKey(concept));
		ResKey veto = Presets.applyVeto(planned, _rocker1, rockerToggle);
		assertNotNull(veto);
		String text = text(veto);
		assertTrue(text, text.contains("Wippe: Umschalten") && text.contains("Taste: Umschalten"));
		assertTrue(text, text.contains(ChannelShape.parameterLabel(concept)));
		try {
			inTransaction(() -> Presets.apply(planned, _rocker1, rockerToggle));
			fail("The conflict must be refused.");
		} catch (TopLogicException ex) {
			assertEquals(veto, ex.getErrorKey());
		}
		assertEquals("1", encoded(planned, concept));
		assertNull(Presets.use(planned, _rocker1));
		// The other way round on its own device: the rocker's preset applies.
		assertNull(Presets.applyVeto(rocker, _rocker1, rockerToggle));
	}

	private void checkAccess(TLObject project, TLObject device) {
		Person[] editor = { null };
		inTransaction(() -> {
			editor[0] = AccountFixture.person("presetEditor", null);
			_persons.add(editor[0]);
			ProjectMembers.add(project, editor[0], ProjectMembers.Role.editor);
		});
		TLObject schalten = presetNamed(device, "Schalten");
		ThreadContext.withSecurityChecks(true, () -> {
			// The editor applies a preset, but neither saves nor changes one.
			assertNull(as(editor[0], () -> Presets.applyVeto(device, _channelA, schalten)));
			assertFalse(ModelAccessRights.getInstance().isAllowed(editor[0], schalten, SimpleBoundCommandGroup.WRITE));
			assertFalse(ModelAccessRights.getInstance().isAllowed(editor[0], schalten, SimpleBoundCommandGroup.DELETE));
			assertTrue(ModelAccessRights.getInstance().isAllowed(editor[0], schalten, SimpleBoundCommandGroup.READ));
			assertEquals(I18NConstants.ERROR_TEMPLATES_NOT_ALLOWED, as(editor[0], Presets::templateWriteVeto));
			try {
				as(editor[0], () -> save(device, _channelA, "Fremd", purpose("actuator"), kind("light"), List.of(),
					proposed(device, _channelA), Map.of()));
				fail("An editor must not save a preset.");
			} catch (TopLogicException ex) {
				assertEquals(I18NConstants.ERROR_TEMPLATES_NOT_ALLOWED, ex.getErrorKey());
			}
			ViewCommandScan.Site apply = ViewCommandScan.command("projects/device-presets.view.xml", "presetApply()");
			assertTrue(as(editor[0], () -> apply.decide(device, _channelA, schalten)).isExecutable());
			ViewCommandScan.Site saveCommand =
				ViewCommandScan.command("projects/device-presets.view.xml", "presetSaveRequest()");
			assertFalse(as(editor[0], () -> saveCommand.decide(device, _channelA, DeviceTemplates.root())).isExecutable());
			ViewCommandScan.Site declare = ViewCommandScan.command("device-templates.view.xml", "presetDeclareAlike()");
			assertFalse(as(editor[0], () -> declare.decide(schalten.tContainer(), List.of(_channelA))).isExecutable());
			// As the device's editor writes the device through the preset.
			as(editor[0], () -> {
				inTransaction(() -> Presets.apply(device, _channelA, schalten));
				return null;
			});
		});
		assertEquals(schalten, Presets.use(device, _channelA).tValueByName("preset"));
	}

	private void checkReimport(List<TLObject> before, TLObject actuator, TLObject keys) throws Exception {
		Program program = DeviceConfiguration.program(actuator);
		TLObject template = DeviceTemplates.template(program);
		int templates = instances("ehome.template:DeviceTemplate").size();
		int presets = instances("ehome.template:Preset").size();
		int uses = instances("ehome.physical:PresetUse").size();
		importSample("samples/KNX-Testsystem.knxproj");
		assertEquals(2, SharedData.newProjects(before).size());
		assertEquals(templates, instances("ehome.template:DeviceTemplate").size());
		assertEquals(presets, instances("ehome.template:Preset").size());
		assertEquals(uses, instances("ehome.physical:PresetUse").size());
		assertSame(template, DeviceTemplates.template(program));

		SharedData.deleteProjects(SharedData.newProjects(before));
		assertEquals(presets, instances("ehome.template:Preset").size());
		assertTrue(template.tValid());
		assertEquals(0, instances("ehome.physical:PresetUse").stream()
			.filter(u -> _presets.contains(u.tValueByName("preset"))).count());
		assertFalse(keys.tValid());
	}

	// ---------------------------------------------------------------------------------------------

	private TLObject save(TLObject device, String key, String name, TLObject purpose, TLObject kind,
			List<TLObject> operations, List<ParameterView> values, Map<Interface, TLObject> roles) {
		TLObject existing = DeviceTemplates.template(DeviceConfiguration.program(device));
		TLObject[] result = { null };
		inTransaction(() -> result[0] = Presets.save(device, key, name, purpose, kind, operations, values, roles));
		_presets.add(result[0]);
		if (existing == null) {
			_templates.add(result[0].tContainer());
		}
		return result[0];
	}

	private static List<ParameterView> proposed(TLObject device, String key) {
		List<ParameterView> result = new ArrayList<>();
		for (Presets.Candidate candidate : Presets.candidates(device, key)) {
			if (candidate.proposed()) {
				result.add(candidate.view());
			}
		}
		assertFalse(result.isEmpty());
		return result;
	}

	private static Presets.Candidate candidate(TLObject device, String key, ParameterView view) {
		for (Presets.Candidate candidate : Presets.candidates(device, key)) {
			if (candidate.view().equals(view)) {
				return candidate;
			}
		}
		fail("No candidate " + view);
		return null;
	}

	/** The external input of the channel: its mode (AKK-04). */
	private static ParameterView mode(TLObject device, String key) {
		List<ParameterView> inputs = shape(device, key).getExternalInputs();
		assertEquals(1, inputs.size());
		return inputs.get(0);
	}

	/** The operation concept of the keys 1 and 2 of the Tastsensor (Funktion_T1T2). */
	private ParameterView concept(TLObject device) {
		for (ParameterView view : shape(device, _key1).getExternalInputs()) {
			if ("Funktion_T1T2".equals(view.getParameter().getName())) {
				return view;
			}
		}
		fail("No operation concept.");
		return null;
	}

	/**
	 * The key of the channel of the device whose objects have the given name in one of the languages.
	 */
	private static String key(TLObject device, String... names) {
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

	private static ChannelShape shape(TLObject device, String key) {
		ChannelShape result = ChannelShape.of(device).get(key);
		assertNotNull(key, result);
		return result;
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

	private static String encoded(TLObject device, ParameterView view) {
		return ParameterValues.encode(DeviceConfiguration.valueHolder(device, view));
	}

	private TLObject presetNamed(TLObject device, String name) {
		for (TLObject preset : _presets) {
			if (name.equals(preset.tValueByName("name"))) {
				return preset;
			}
		}
		fail("No preset " + name);
		return null;
	}

	private static TLObject plan(TLObject like) {
		TLObject[] result = { null };
		inTransaction(() -> {
			TLObject line = DevicePlanning.line(like);
			result[0] = DevicePlanning.create(line, (TLObject) like.tValueByName("type"),
				(TLObject) like.tValueByName("program"), DevicePlanning.nextFree(line), "Planned", null);
		});
		return result[0];
	}

	private static TLObject device(List<TLObject> projects, int area, int line, int number) {
		int address = area << 12 | line << 8 | number;
		for (TLObject device : SharedData.within("ehome.physical:Device", projects)) {
			Integer individual = DownloadImage.individualAddress(device);
			if (individual != null && individual.intValue() == address) {
				return device;
			}
		}
		fail("No device " + area + "." + line + "." + number);
		return null;
	}

	private static TLClassifier role(String name) {
		return (TLClassifier) TLModelUtil.findPart("ehome.template:PresetRole#" + name);
	}

	private static TLClassifier purpose(String name) {
		return (TLClassifier) TLModelUtil.findPart("ehome.template:PresetPurpose#" + name);
	}

	private static TLClassifier operation(String name) {
		return (TLClassifier) TLModelUtil.findPart("ehome.template:SourceOperation#" + name);
	}

	private static TLClassifier kind(String name) {
		return (TLClassifier) TLModelUtil.findPart("ehome.runtime:FunctionKind#" + name);
	}

	private static String text(ResKey key) {
		return Resources.getInstance(Locale.ENGLISH).getString(key);
	}

	private static <T> T as(Person person, Supplier<T> computation) {
		return TLContextManager.inPersonContext(person, computation::get);
	}

	private void cleanUp() {
		inTransaction(() -> {
			for (TLObject preset : _presets) {
				if (preset.tValid()) {
					preset.tDelete();
				}
			}
			for (TLObject template : _templates) {
				if (template.tValid()) {
					template.tDelete();
				}
			}
			for (Person person : _persons) {
				if (person.tValid()) {
					person.tDelete();
				}
			}
		});
		_presets.clear();
		_templates.clear();
		_persons.clear();
	}

	private static void inTransaction(Runnable modification) {
		KnowledgeBase kb = PersistencyLayer.getKnowledgeBase();
		try (Transaction tx = kb.beginTransaction()) {
			modification.run();
			tx.commit();
		}
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestPresets.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE,
			SearchBuilder.Module.INSTANCE, TLSecurityDeviceManager.Module.INSTANCE,
			InitialRolesManager.Module.INSTANCE, InitialGroupManager.Module.INSTANCE, AccessManager.Module.INSTANCE,
			SecurityConfigurationService.Module.INSTANCE, CommandGroupRegistry.Module.INSTANCE,
			ProjectOwners.Module.INSTANCE));
	}

}
