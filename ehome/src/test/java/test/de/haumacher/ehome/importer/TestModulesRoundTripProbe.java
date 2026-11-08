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
import de.haumacher.ehome.modules.ModuleInstances;
import de.haumacher.ehome.modules.ModuleParts;
import de.haumacher.ehome.projectfile.ProjectFile;
import de.haumacher.ehome.projectfile.ProjectFileExport;

/**
 * Probe of the dynamic modules (haumacher/ehome #119) with the project file (haumacher/ehome #117),
 * whose KNX part is the <code>.knxproj</code> export (schema 11, no modules; a part of a module
 * instance named by the ID of the instance's part): the devices of programs with modules keep their
 * module instances, module settings, module objects with their group addresses and their labels with
 * the filled text parameter through export and import, and the programs are not imported a second
 * time.
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
			String signature = signature(original);
			assertTrue(signature, signature.contains("_MD-1_M-2_MI-1_O-2-0_R-1"));
			assertTrue(signature, signature.contains("WZ"));
			// The repeat program (haumacher/ehome #119): an object of a sub-module's instance.
			assertTrue(signature, signature.contains("_MD-2_M-1_MI-2_SM-1_M-1_MI-2-1-2_O-1-0_R-1"));
			int programs = programs();

			ByteArrayOutputStream out = new ByteArrayOutputStream();
			ProjectFileExport.export(original, out);
			new RecordingJobMonitor().run(new ImportProjectFileJob(null, null), List.of(
				BinaryDataFactory.createBinaryData(out.toByteArray(), ProjectFile.CONTENT_TYPE, "Module.ehome")));
			List<TLObject> imported = SharedData.newProjects(before);
			imported.remove(original);
			projects.addAll(imported);
			assertEquals(1, imported.size());

			assertEquals(signature, signature(imported.get(0)));
			assertEquals(programs, programs());
		} finally {
			SharedData.deleteProjects(projects);
		}
	}

	private static int programs() {
		return (int) instances("ehome.operational:Program").stream()
			.filter(p -> PROGRAM.equals(p.tValueByName("extId"))).count();
	}

	/**
	 * The settings and objects of the devices of the project, see {@link #signature(TLObject, TreeSet)}.
	 */
	private static String signature(TLObject project) {
		TreeSet<String> result = new TreeSet<>();
		for (TLObject device : SharedData.within("ehome.physical:Device", List.of(project))) {
			signature(device, result);
		}
		return String.join("\n", result);
	}

	/**
	 * The settings and objects of the device: the parameter by its ID (in its module instance) with its
	 * value, the object by its ID with its label and its group addresses.
	 */
	private static void signature(TLObject device, TreeSet<String> result) {
		String prefix = device.tValueByName("address") + " ";
		for (TLObject setting : list(device, "settings")) {
			StringBuilder values = new StringBuilder();
			for (TLStructuredTypePart part : setting.tType().getAllParts()) {
				if (part.isDerived() || part.getName().equals("parameter") || part.getName().equals("instance")) {
					continue;
				}
				Object value = setting.tValue(part);
				if (value instanceof TLObject target) {
					value = target.tValueByName("extId") != null ? target.tValueByName("extId") : target.tValueByName("value");
				}
				values.append(' ').append(part.getName()).append('=').append(value);
			}
			result.add(prefix + "setting " + ModuleParts.partId(setting, ModuleParts.PARAMETER) + values);
		}
		for (TLObject connector : list(device, "connectors")) {
			TreeSet<String> connections = new TreeSet<>();
			for (TLObject connection : list(connector, "connections")) {
				connections.add(((TLClassifier) connection.tValueByName("kind")).getName() + " "
					+ ((TLObject) connection.tValueByName("target")).tValueByName("extId"));
			}
			result.add(prefix + "object " + ModuleParts.partId(connector, ModuleParts.PORT) + " '"
				+ derived(connector, "displayName") + "' " + connections);
		}
		for (String instance : ModuleInstances.stored(device).stream()
			.map(i -> i.tValueByName(ModuleInstances.NAME) + " " + i.tValueByName(ModuleInstances.ACTIVE)).toList()) {
			result.add(prefix + "instance " + instance);
		}
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
