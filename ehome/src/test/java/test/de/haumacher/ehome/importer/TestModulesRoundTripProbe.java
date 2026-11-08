/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.importer;

import static test.de.haumacher.ehome.importer.TestEtsImport.derived;
import static test.de.haumacher.ehome.importer.TestEtsImport.importSample;
import static test.de.haumacher.ehome.importer.TestEtsImport.instances;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.TreeSet;

import junit.framework.Test;

import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;
import test.de.haumacher.ehome.etsImport.RecordingJobMonitor;

import com.top_logic.basic.io.binary.BinaryDataFactory;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.model.TLClassifier;
import com.top_logic.model.TLObject;
import com.top_logic.model.TLStructuredTypePart;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.etsImport.ImportProjectFileJob;
import de.haumacher.ehome.projectfile.ProjectFile;
import de.haumacher.ehome.projectfile.ProjectFileExport;

/**
 * Probe of the module expansion (haumacher/ehome #119) with the project file (haumacher/ehome #117),
 * whose KNX part is the <code>.knxproj</code> export (schema 11, no modules): a device of a program
 * with expanded modules keeps its module settings, its module objects with their group addresses and
 * their labels with the filled text parameter through export and import, and the program is not
 * imported a second time.
 */
@SuppressWarnings("javadoc")
public class TestModulesRoundTripProbe extends BasicTestCase {

	private static final String PROGRAM = "M-0008_A-FFF7-01-0000";

	public void testRoundTrip() throws Exception {
		List<TLObject> before = instances(ProjectFile.PROJECT_TYPE);
		importSample("src/test/fixtures/TestModules.knxproj");
		List<TLObject> projects = new ArrayList<>(SharedData.newProjects(before));
		try {
			assertEquals(1, projects.size());
			TLObject original = projects.get(0);
			String signature = signature(device(original));
			assertTrue(signature, signature.contains("_MD-1_M-2_MI-1_O-2-0_R-1"));
			assertTrue(signature, signature.contains("WZ"));
			int programs = programs();

			ByteArrayOutputStream out = new ByteArrayOutputStream();
			ProjectFileExport.export(original, out);
			new RecordingJobMonitor().run(new ImportProjectFileJob(null, null), List.of(
				BinaryDataFactory.createBinaryData(out.toByteArray(), ProjectFile.CONTENT_TYPE, "Module.ehome")));
			List<TLObject> imported = SharedData.newProjects(before);
			imported.remove(original);
			projects.addAll(imported);
			assertEquals(1, imported.size());

			assertEquals(signature, signature(device(imported.get(0))));
			assertEquals(programs, programs());
		} finally {
			SharedData.deleteProjects(projects);
		}
	}

	private static int programs() {
		return (int) instances("ehome.operational:Program").stream()
			.filter(p -> PROGRAM.equals(p.tValueByName("extId"))).count();
	}

	private static TLObject device(TLObject project) {
		List<TLObject> devices = SharedData.within("ehome.physical:Device", List.of(project));
		assertEquals(1, devices.size());
		return devices.get(0);
	}

	/**
	 * The settings and objects of the device: the parameter by its ID with its value, the object by
	 * its ID with its label and its group addresses.
	 */
	private static String signature(TLObject device) {
		TreeSet<String> result = new TreeSet<>();
		for (TLObject setting : list(device, "settings")) {
			StringBuilder values = new StringBuilder();
			for (TLStructuredTypePart part : setting.tType().getAllParts()) {
				if (part.isDerived() || part.getName().equals("parameter")) {
					continue;
				}
				Object value = setting.tValue(part);
				if (value instanceof TLObject target) {
					value = target.tValueByName("extId") != null ? target.tValueByName("extId") : target.tValueByName("value");
				}
				values.append(' ').append(part.getName()).append('=').append(value);
			}
			result.add("setting " + ((TLObject) setting.tValueByName("parameter")).tValueByName("extId") + values);
		}
		for (TLObject connector : list(device, "connectors")) {
			TreeSet<String> connections = new TreeSet<>();
			for (TLObject connection : list(connector, "connections")) {
				connections.add(((TLClassifier) connection.tValueByName("kind")).getName() + " "
					+ ((TLObject) connection.tValueByName("target")).tValueByName("extId"));
			}
			result.add("object " + ((TLObject) connector.tValueByName("port")).tValueByName("extId") + " '"
				+ derived(connector, "displayName") + "' " + connections);
		}
		return String.join("\n", result);
	}

	@SuppressWarnings("unchecked")
	private static List<TLObject> list(TLObject obj, String attribute) {
		Object value = obj.tValueByName(attribute);
		return value instanceof Collection<?> collection ? List.copyOf((Collection<TLObject>) collection) : List.of();
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestModulesRoundTripProbe.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
