/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.template;

import static test.de.haumacher.ehome.importer.TestEtsImport.asRoot;
import static test.de.haumacher.ehome.importer.TestEtsImport.importSample;
import static test.de.haumacher.ehome.importer.TestEtsImport.instances;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import junit.framework.Test;
import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;
import test.de.haumacher.ehome.importer.SharedData;

import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.model.TLObject;
import com.top_logic.util.Resources;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.knx.ExtIds;
import de.haumacher.ehome.model.operational.Interface;
import de.haumacher.ehome.template.DeviceTemplates;
import de.haumacher.ehome.template.ObjectKinds;

/**
 * Probe of the kinds of communication objects (haumacher/ehome #166) beyond the Gira sensor of
 * {@link TestObjectKinds}: every program of the demo project, the module fixture (placements of
 * module definitions) and the dynamic fixture (conditional channels taking the default case) is
 * partitioned into kinds, each placement in exactly one, the same way in German and English, no two
 * rows reading alike, and a parameter text naming the channel keeps it; and the marks of a project
 * context depend on that project alone, not on another project running the same program.
 */
@SuppressWarnings("javadoc")
public class TestObjectKindsProbe extends BasicTestCase {

	/** The Gira push-button sensor, in the test system and in the demo project. */
	private static final String GIRA = "M-0008_A-10D7-11-A0E5-O000A";

	public void testPartitionOfEveryProgram() throws Exception {
		List<TLObject> before = instances("ehome.project:Project");
		importSample("samples/EHomeDemo.knxproj");
		importSample("src/test/fixtures/TestModules.knxproj");
		importSample("src/test/fixtures/TestDynamic.knxproj");
		List<TLObject> projects = SharedData.newProjects(before);
		try {
			Set<TLObject> programs = new LinkedHashSet<>();
			for (TLObject device : SharedData.within("ehome.physical:Device", projects)) {
				// The device's program assignment names the program.
				if (device.tValueByName("program") instanceof TLObject assignment
					&& assignment.tValueByName("program") instanceof TLObject program) {
					programs.add(program);
				}
			}
			assertTrue("Programs: " + programs, programs.size() >= 4);
			Resources german = Resources.getInstance(Locale.GERMAN);
			Resources english = Resources.getInstance(Locale.ENGLISH);
			for (TLObject program : programs) {
				String name = ExtIds.extId(program);
				List<Interface> placements = DeviceTemplates.placements(program);
				List<Map<String, Object>> de = asRoot(() -> ObjectKinds.kinds(program, null, german));
				List<Map<String, Object>> en = asRoot(() -> ObjectKinds.kinds(program, null, english));
				assertPartition(name, placements, de);
				if (name.equals(GIRA)) {
					// The channel's name is left out only where it is the whole title of the parameter.
					assertTrue(settings(de).contains("Schalten"));
					assertTrue(settings(de).contains(
						"Funktion der Betriebs-LED / Beschriftungsfeldbeleuchtung: Ansteuerung über Objekt"));
					assertTrue(settings(en).contains("Alarm signal display: Activated"));
				}
				assertPartition(name, placements, en);
				// The grouping follows the program's base language, not the reader's (the order of the rows
				// may follow the reader's texts).
				assertEquals(name, new HashSet<>(groups(de)), new HashSet<>(groups(en)));
				for (Map<String, Object> kind : de) {
					assertNotNull(name + ": " + kind, kind.get(ObjectKinds.SIZE));
				}
			}
		} finally {
			SharedData.deleteProjects(projects);
		}
	}

	public void testProjectContextIsolated() throws Exception {
		List<TLObject> before = instances("ehome.project:Project");
		importSample("samples/KNX-Testsystem.knxproj");
		List<TLObject> testSystem = SharedData.newProjects(before);
		List<TLObject> withTestSystem = instances("ehome.project:Project");
		importSample("samples/EHomeDemo.knxproj");
		List<TLObject> demo = SharedData.newProjects(withTestSystem);
		assertEquals(1, testSystem.size());
		assertEquals(1, demo.size());
		TLObject program = instances("ehome.operational:Program").stream()
			.filter(p -> GIRA.equals(ExtIds.extId(p))).findFirst().orElseThrow();
		Resources german = Resources.getInstance(Locale.GERMAN);
		try {
			// The test system's device connects its measured room temperature without a type.
			assertFalse(DeviceTemplates.untypedConnected(program, testSystem.get(0)).isEmpty());
			List<Map<String, Object>> demoKinds =
				asRoot(() -> ObjectKinds.kinds(program, demo.get(0), german));
			List<Interface> demoUntyped = DeviceTemplates.untypedConnected(program, demo.get(0));
			List<Boolean> demoMarks = marks(demoKinds, demo.get(0));

			// Without the test system's project, the demo's context reads the same.
			SharedData.deleteProjects(testSystem);
			assertEquals(demoUntyped, DeviceTemplates.untypedConnected(program, demo.get(0)));
			List<Map<String, Object>> after = asRoot(() -> ObjectKinds.kinds(program, demo.get(0), german));
			assertEquals(groups(demoKinds), groups(after));
			assertEquals(demoMarks, marks(after, demo.get(0)));
		} finally {
			SharedData.deleteProjects(testSystem);
			SharedData.deleteProjects(demo);
		}
	}

	private static void assertPartition(String program, List<Interface> placements,
			List<Map<String, Object>> kinds) {
		Set<Interface> covered = new HashSet<>();
		Set<List<Object>> keys = new HashSet<>();
		for (Map<String, Object> kind : kinds) {
			List<Interface> places = ObjectKinds.placements(kind);
			assertFalse(program + ": empty kind " + kind, places.isEmpty());
			for (Interface placed : places) {
				assertTrue(program + ": placed twice " + placed, covered.add(placed));
			}
			// Two rows read differently: in the object, size, setting or the channels they occur in.
			assertTrue(program + ": a kind twice " + kind, keys.add(List.of(String.valueOf(kind.get(ObjectKinds.OBJECT)),
				String.valueOf(kind.get(ObjectKinds.SIZE)), String.valueOf(kind.get(ObjectKinds.SETTING)),
				String.valueOf(kind.get(ObjectKinds.CHANNELS)))));
		}
		assertEquals(program, new HashSet<>(placements), covered);
	}

	/** The placements of each kind, in the order of the kinds. */
	private static List<Set<Interface>> groups(List<Map<String, Object>> kinds) {
		List<Set<Interface>> result = new ArrayList<>();
		for (Map<String, Object> kind : kinds) {
			result.add(new HashSet<>(ObjectKinds.placements(kind)));
		}
		return result;
	}

	private static Set<Object> settings(List<Map<String, Object>> kinds) {
		Set<Object> result = new HashSet<>();
		for (Map<String, Object> kind : kinds) {
			result.add(kind.get(ObjectKinds.SETTING));
		}
		return result;
	}

	private static List<Boolean> marks(List<Map<String, Object>> kinds, TLObject project) {
		List<Boolean> result = new ArrayList<>();
		for (Map<String, Object> kind : kinds) {
			result.add(Boolean.valueOf(ObjectKinds.untyped(kind, project)));
		}
		return result;
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestObjectKindsProbe.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
