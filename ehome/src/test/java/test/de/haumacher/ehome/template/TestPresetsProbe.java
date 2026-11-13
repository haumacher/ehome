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

import com.top_logic.base.security.device.TLSecurityDeviceManager;
import com.top_logic.base.services.InitialGroupManager;
import com.top_logic.base.services.InitialRolesManager;
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
import com.top_logic.model.security.SecurityConfigurationService;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.tool.boundsec.manager.AccessManager;
import com.top_logic.tool.boundsec.simple.CommandGroupRegistry;
import com.top_logic.util.Resources;
import com.top_logic.util.TLContextManager;
import com.top_logic.util.error.TopLogicException;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.knx.ParameterValues;
import de.haumacher.ehome.model.operational.Interface;
import de.haumacher.ehome.model.operational.ParameterView;
import de.haumacher.ehome.planning.DevicePlanning;
import de.haumacher.ehome.programming.DeviceConfiguration;
import de.haumacher.ehome.programming.DownloadImage;
import de.haumacher.ehome.programming.GroupAddressConnections;
import de.haumacher.ehome.project.ProjectOwners;
import de.haumacher.ehome.template.ChannelShape;
import de.haumacher.ehome.template.DeviceTemplates;
import de.haumacher.ehome.template.Presets;

/**
 * Probe of the presets (haumacher/ehome #146) beyond {@link TestPresets}: a preset of one program is
 * not applied to a device of another; applying the same preset twice keeps one use; a preset is offered
 * on its own channel of another device of the program and gives it the same values; deleting a preset
 * removes its uses and keeps the values on the device.
 */
@SuppressWarnings("javadoc")
public class TestPresetsProbe extends BasicTestCase {

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

	public void testProbe() throws Exception {
		List<TLObject> before = instances("ehome.project:Project");
		importSample("samples/KNX-Testsystem.knxproj");
		List<TLObject> projects = SharedData.newProjects(before);
		try {
			TLObject actuator = device(projects, 1, 3, 5);
			TLObject keys = device(projects, 1, 3, 50);
			_channelA = key(actuator, "Channel A", "Kanal A");
			_key1 = key(keys, "Taste 1", "Key 1");
			List<Interface> objects = Presets.channelObjects(actuator, _channelA);
			Map<Interface, TLObject> roles = new LinkedHashMap<>();
			roles.put(object(objects, 0), role("command"));
			TLObject schalten = save(actuator, _channelA, "Schalten", purpose("actuator"), kind("light"), List.of(),
				proposed(actuator, _channelA), roles);

			// Another program: refused.
			assertNotNull(Presets.applyVeto(keys, _key1, schalten));
			try {
				inTransaction(() -> Presets.apply(keys, _key1, schalten));
				fail("A preset of another program was applied.");
			} catch (TopLogicException ex) {
				// Refused.
			}
			assertEquals(0, Presets.uses(keys).size());

			// Twice on the same channel: one use.
			inTransaction(() -> Presets.apply(actuator, _channelA, schalten));
			inTransaction(() -> Presets.apply(actuator, _channelA, schalten));
			assertEquals(1, Presets.uses(actuator).size());

			// A planned device of the same program: offered on its channel A, same values.
			TLObject planned = plan(actuator);
			assertEquals(List.of(schalten), Presets.offered(planned, _channelA));
			inTransaction(() -> Presets.apply(planned, _channelA, schalten));
			Map<ParameterView, Object> wanted = Presets.values(actuator, _channelA, schalten);
			Map<ParameterView, Object> got = Presets.values(planned, _channelA, schalten);
			assertEquals(wanted.size(), got.size());
			for (ParameterView view : got.keySet()) {
				assertTrue(view.toString(), wanted.containsKey(view));
				assertEquals(view.toString(), encoded(actuator, view), encoded(planned, view));
			}

			// Deleting the preset: the uses go, the values stay.
			String mode = encoded(planned, got.keySet().iterator().next());
			inTransaction(() -> schalten.tDelete());
			assertEquals(0, Presets.uses(planned).size());
			assertEquals(0, Presets.uses(actuator).size());
			assertEquals(mode, encoded(planned, got.keySet().iterator().next()));
		} finally {
			SharedData.deleteProjects(SharedData.newProjects(before));
			cleanUp();
		}
	}

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
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestPresetsProbe.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE,
			SearchBuilder.Module.INSTANCE, TLSecurityDeviceManager.Module.INSTANCE,
			InitialRolesManager.Module.INSTANCE, InitialGroupManager.Module.INSTANCE, AccessManager.Module.INSTANCE,
			SecurityConfigurationService.Module.INSTANCE, CommandGroupRegistry.Module.INSTANCE,
			ProjectOwners.Module.INSTANCE));
	}

}
