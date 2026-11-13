/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.modules;

import static test.de.haumacher.ehome.importer.TestEtsImport.importSample;
import static test.de.haumacher.ehome.importer.TestEtsImport.instances;

import java.util.Collection;
import java.util.List;
import java.util.Map;

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

import de.haumacher.ehome.knx.ParameterValues;
import de.haumacher.ehome.model.operational.ParameterView;
import de.haumacher.ehome.modules.ModuleParts;
import de.haumacher.ehome.programming.DeviceConfiguration;
import de.haumacher.ehome.programming.DeviceSettings;
import de.haumacher.ehome.programming.DownloadImage;
import de.haumacher.ehome.template.ChannelShape;
import de.haumacher.ehome.template.DeviceTemplates;
import de.haumacher.ehome.template.Presets;

/**
 * The presets of the device templates (haumacher/ehome #146) on the channels of module instances:
 * the program <code>M-0083_A-000C-23-216C</code> of MDT AKK V2.3
 * (<code>src/test/fixtures/MDT_AKK_V23.knxprod</code>, see {@link TestMdtAkkModules}) places each of
 * the channels A to D as an instance of a module definition. A preset of the switching instance of
 * channel A (its mode "normally closed", a parameter of the module) is offered on the switching
 * instance of channel B by the module, without a declaration, and sets B's mode.
 */
@SuppressWarnings("javadoc")
public class TestModulePresets extends BasicTestCase {

	public void testModuleChannels() throws Exception {
		List<TLObject> before = instances("ehome.project:Project");
		importSample("samples/KNX-Testsystem.knxproj");
		assertEquals(List.of(), ProductDatabases.importWithoutMasterData("src/test/fixtures/MDT_AKK_V23.knxprod"));
		TLObject[] template = { null };
		try {
			TLObject device = actuator(SharedData.newProjects(before));
			TLObject assignment = instances("ehome.catalog:ProgramAssignment").stream()
				.filter(a -> "M-0083_H-3.2E13-2_HP-000C-23-216C".equals(a.tValueByName("extId"))).findFirst()
				.orElseThrow();
			inTransaction(() -> device.tUpdateByName("program", assignment));
			assertNull(DeviceTemplates.template(DeviceConfiguration.program(device)));
			List<String> channels = Presets.channels(device);
			String a = channel(channels, "MD-2_M-1_MI-1");
			String b = channel(channels, "MD-2_M-4_MI-1");
			ChannelShape shapeA = ChannelShape.of(device).get(a);
			assertNotNull(shapeA.getInstance());
			assertSame(shapeA.getModuleChannel(), ChannelShape.of(device).get(b).getModuleChannel());

			ParameterView modeA = shapeA.getParameters().stream()
				.filter(v -> ModuleParts.partId(v).contains("MD-2_M-1_MI-1_P-1_R-")).findFirst().orElseThrow();
			Object other = otherChoice(modeA, encoded(device, modeA));
			inTransaction(() -> DeviceSettings.setValue(device, modeA, other));
			TLObject[] preset = { null };
			inTransaction(() -> preset[0] = Presets.save(device, a, "Öffner", classifier("PresetPurpose", "actuator"),
				(TLObject) TLModelUtil.findPart("ehome.runtime:FunctionKind#switch"), List.of(), List.of(modeA), Map.of()));
			template[0] = preset[0].tContainer();
			assertSame(ModuleParts.template(modeA),
				((Collection<?>) preset[0].tValueByName("values")).stream().map(v -> ((TLObject) v).tValueByName("parameter"))
					.findFirst().orElseThrow());

			assertEquals(List.of(preset[0]), Presets.offered(device, b));
			ParameterView modeB = Presets.values(device, b, preset[0]).keySet().iterator().next();
			assertTrue(ModuleParts.partId(modeB), ModuleParts.partId(modeB).contains("MD-2_M-4_MI-1_P-1_R-"));
			assertFalse(encoded(device, modeA).equals(encoded(device, modeB)));
			inTransaction(() -> Presets.apply(device, b, preset[0]));
			assertEquals(encoded(device, modeA), encoded(device, modeB));
		} finally {
			SharedData.deleteProjects(SharedData.newProjects(before));
			if (template[0] != null) {
				inTransaction(() -> template[0].tDelete());
			}
		}
	}

	private static String channel(List<String> channels, String instance) {
		return channels.stream().filter(k -> k.startsWith("channel:") && k.contains(instance)).findFirst()
			.orElseThrow(() -> new AssertionError("No channel of " + instance + " in " + channels));
	}

	private static Object otherChoice(ParameterView view, String current) {
		for (Object entry : (Collection<?>) ((TLObject) view.getParameter().getType()).tValueByName("values")) {
			Object value = ((TLObject) entry).tValueByName("value");
			if (!String.valueOf(value).equals(current)) {
				return value;
			}
		}
		fail("No other value of " + view);
		return null;
	}

	private static String encoded(TLObject device, ParameterView view) {
		return ParameterValues.encode(DeviceConfiguration.valueHolder(device, view));
	}

	private static TLObject classifier(String enumeration, String name) {
		return (TLObject) TLModelUtil.findPart("ehome.template:" + enumeration + "#" + name);
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

	private static void inTransaction(Runnable modification) {
		KnowledgeBase kb = PersistencyLayer.getKnowledgeBase();
		try (Transaction tx = kb.beginTransaction()) {
			modification.run();
			tx.commit();
		}
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestModulePresets.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
