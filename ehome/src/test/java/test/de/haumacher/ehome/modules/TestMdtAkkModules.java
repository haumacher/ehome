/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.modules;

import static test.de.haumacher.ehome.importer.TestEtsImport.importSample;
import static test.de.haumacher.ehome.importer.TestEtsImport.instances;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import junit.framework.Test;

import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;
import test.de.haumacher.ehome.importer.SharedData;

import com.top_logic.knowledge.service.KnowledgeBase;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.model.TLObject;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.model.operational.Interface;
import de.haumacher.ehome.model.operational.ParameterView;
import de.haumacher.ehome.model.operational.Program;
import de.haumacher.ehome.modules.InstanceInfo;
import de.haumacher.ehome.modules.ModuleParts;
import de.haumacher.ehome.programming.DeviceConfiguration;
import de.haumacher.ehome.programming.DeviceSettings;
import de.haumacher.ehome.programming.DeviceTexts;
import de.haumacher.ehome.programming.DownloadImage;
import de.haumacher.ehome.programming.download.DownloadPlan;
import de.haumacher.ehome.programming.download.DownloadPlanner;

/**
 * Fixed placements of dynamic modules in a real program (haumacher/ehome #119): the product database
 * MDT AKK V2.3 (<code>src/test/fixtures/MDT_AKK_V23.knxprod</code>, the public database of MDT) and its
 * program <code>M-0083_A-000C-23-216C</code> "Switching 4-fold AKK" (3 module definitions, 12
 * placements: switching, staircase and pulse mode for each of the channels A to D, with the arguments
 * ParamOffsBase 16, 88, 160, 232, ObjNumberBase 0, 12, 24, 36, ChNo A to D).
 *
 * <p>
 * The test system's actuator 1.3.5 (<code>samples/KNX-Testsystem.knxproj</code>) is the hardware
 * <code>M-0083_H-3.2E13-2</code>, for which the database assigns that program: the device is switched
 * to it, configured and connected, and its full download is planned with the instances' object numbers
 * and offsets. The download itself into the real device is a check on the desk, not of this test.
 * </p>
 *
 * <p>
 * The import of the database reads its seven programs (about 4 MB of XML): it is done once and shared
 * by the test's checks.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestMdtAkkModules extends BasicTestCase {

	private static final String DATABASE = "src/test/fixtures/MDT_AKK_V23.knxprod";

	private static final String PROGRAM = "M-0083_A-000C-23-216C";

	private static final String P = PROGRAM + "_";

	private static final String ASSIGNMENT = "M-0083_H-3.2E13-2_HP-000C-23-216C";

	/** The segment of the parameters of the channels (AS-44EC at 0x44EC). */
	private static final int PARAMETERS = 17644;

	public void testSwitchActuator() throws Exception {
		// The samples' master data first, which the database's programs refer to.
		List<TLObject> before = instances("ehome.project:Project");
		importSample("samples/KNX-Testsystem.knxproj");
		assertEquals(List.of(), ProductDatabases.importWithoutMasterData(DATABASE));
		Program program = program();
		assertFalse(program.getUsesModules());
		assertEquals(3, program.getModules().size());

		try {
			TLObject device = actuator(SharedData.newProjects(before));
			// The test system's actuator runs the program without modules of its old database.
			assertFalse(PROGRAM.equals(DeviceConfiguration.program(device).getExtId()));
			try (Transaction tx = kb().beginTransaction()) {
				// Switched to the program of AKK V2.3 for its hardware (in the app: the field "Program"
				// of the device's form).
				device.tUpdateByName("program", assignment());
				tx.commit();
			}
			assertSame(program, DeviceConfiguration.program(device));
			checkChannels(device);
		} finally {
			SharedData.deleteProjects(SharedData.newProjects(before));
		}
	}

	private void checkChannels(TLObject device) {
		DeviceConfiguration configuration = new DeviceConfiguration(device);
		// The channels in switching mode by default: one instance of the switching module each.
		List<String> ids = configuration.getInstances().stream().map(InstanceInfo::getId).toList();
		assertTrue(ids.toString(), ids.containsAll(List.of("MD-2_M-1_MI-1", "MD-2_M-4_MI-1", "MD-2_M-7_MI-1",
			"MD-2_M-10_MI-1")));
		assertTrue(ids.toString(), ids.stream().noneMatch(id -> id.startsWith("MD-1_") || id.startsWith("MD-3_")));
		// Without its old connectors: the objects of the old program are not part of the download.
		assertTrue(configuration.getConnectors().isEmpty());

		// Channel B: "Switch" is object 0 + 12, "Status" 7 + 12; "Mode" at offset 12 + 88.
		Interface switchB = object(configuration, P + "MD-2_M-4_MI-1_O-2-0_R-");
		Interface statusB = object(configuration, P + "MD-2_M-4_MI-1_O-2-7_R-");
		assertEquals(12, switchB.getPort().getIndex());
		assertEquals(19, statusB.getPort().getIndex());
		ParameterView modeB = view(configuration, P + "MD-2_M-4_MI-1_P-1_R-");
		ParameterView modeA = view(configuration, P + "MD-2_M-1_MI-1_P-1_R-");
		assertEquals(88 + 12, configuration.parameter(modeB).getAddress().getByteOffset());
		assertEquals(16 + 12, configuration.parameter(modeA).getAddress().getByteOffset());

		// The texts of the editor: no placeholder left, the channel titled by its text parameter (empty:
		// left out), the block by its default ({{0:...}}).
		List<String> texts = new ArrayList<>();
		collectTexts(device, configuration, device, texts);
		assertFalse(texts.toString(), texts.isEmpty());
		assertTrue(texts.toString(), texts.stream().noneMatch(t -> t.contains("{{")));

		DownloadImage defaults = plan(device).getImage();
		List<TLObject> addresses = DeviceSettings.groupAddresses(device);
		try (Transaction tx = kb().beginTransaction()) {
			DeviceSettings.connect(device, switchB, addresses.get(0), kind("send"));
			DeviceSettings.connect(device, statusB, addresses.get(1), kind("send"));
			Object normallyClosed = otherChoice(modeB, configuration.value(modeB));
			DeviceSettings.setValue(device, modeB, normallyClosed);
			tx.commit();
		}
		DownloadPlan plan = plan(device);
		DownloadImage image = plan.getImage();
		assertEquals(List.of(12, 19), associatedObjects(image));
		// The value of channel B in its byte, channel A's unchanged.
		assertFalse(defaults.byteAt(PARAMETERS + 100).equals(image.byteAt(PARAMETERS + 100)));
		assertEquals(defaults.byteAt(PARAMETERS + 28), image.byteAt(PARAMETERS + 28));
	}

	private static void collectTexts(TLObject device, DeviceConfiguration configuration, Object page,
			List<String> texts) {
		for (Object item : configuration.getItems(page)) {
			texts.add(DeviceTexts.label(device, item));
		}
		for (Object inner : configuration.getPages(page)) {
			texts.add(DeviceTexts.label(device, inner));
			collectTexts(device, configuration, inner, texts);
		}
	}

	private static Object otherChoice(ParameterView view, String current) {
		TLObject type = (TLObject) view.getParameter().getType();
		for (Object entry : (Collection<?>) type.tValueByName("values")) {
			Object value = ((TLObject) entry).tValueByName("value");
			if (!String.valueOf(value).equals(current)) {
				return value;
			}
		}
		fail("No other value of " + view);
		return null;
	}

	private static DownloadPlan plan(TLObject device) {
		DownloadPlan plan = DownloadPlanner.plan(device, DownloadPlan.Kind.FULL);
		assertTrue(plan.getProblems().toString(), plan.isExecutable());
		return plan;
	}

	private static Interface object(DeviceConfiguration configuration, String idStart) {
		for (Interface placed : configuration.getActiveInterfaces().values()) {
			if (ModuleParts.partId(placed).startsWith(idStart)) {
				return placed;
			}
		}
		fail("No object " + idStart);
		return null;
	}

	private static ParameterView view(DeviceConfiguration configuration, String idStart) {
		for (Object item : configuration.getShownItems().keySet()) {
			if (item instanceof ParameterView view && ModuleParts.partId(view).startsWith(idStart)) {
				return view;
			}
		}
		fail("No parameter " + idStart);
		return null;
	}

	/**
	 * The numbers of the objects in the association table of the image (the number of entries, then
	 * per entry the index of the address and the number of the object).
	 */
	private static List<Integer> associatedObjects(DownloadImage image) {
		byte[] data = image.getAssociationTable().data();
		List<Integer> result = new ArrayList<>();
		for (int n = 0, cnt = data[0] & 0xFF; n < cnt; n++) {
			result.add(Integer.valueOf(data[2 + 2 * n] & 0xFF));
		}
		return result;
	}

	private static TLObject actuator(List<TLObject> projects) {
		for (TLObject device : SharedData.within("ehome.physical:Device", projects)) {
			Integer address = DownloadImage.individualAddress(device);
			if (address != null && address.intValue() == (1 << 12 | 3 << 8 | 5)) {
				return device;
			}
		}
		fail("No device 1.3.5.");
		return null;
	}

	private static TLObject assignment() {
		return instances("ehome.catalog:ProgramAssignment").stream()
			.filter(a -> ASSIGNMENT.equals(a.tValueByName("extId"))).findFirst().orElseThrow();
	}

	private static Program program() {
		return (Program) instances("ehome.operational:Program").stream()
			.filter(p -> PROGRAM.equals(p.tValueByName("extId"))).findFirst().orElseThrow();
	}

	private static TLObject kind(String name) {
		return (TLObject) TLModelUtil.findPart("ehome.physical:ConnectionType#" + name);
	}

	private static KnowledgeBase kb() {
		return PersistencyLayer.getKnowledgeBase();
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestMdtAkkModules.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
