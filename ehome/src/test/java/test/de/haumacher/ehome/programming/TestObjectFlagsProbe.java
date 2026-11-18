/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.programming;

import static test.de.haumacher.ehome.importer.TestEtsImport.importSample;
import static test.de.haumacher.ehome.importer.TestEtsImport.instances;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import junit.framework.Test;
import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;

import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.model.TLObject;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.model.operational.Interface;
import de.haumacher.ehome.modules.ModuleParts;
import de.haumacher.ehome.programming.DeviceChanges;
import de.haumacher.ehome.programming.DeviceConfiguration;
import de.haumacher.ehome.programming.DownloadImage;
import de.haumacher.ehome.programming.ObjectFlags;
import de.haumacher.ehome.template.ObjectTypes;

/**
 * Probe of the flags of a communication object (haumacher/ehome #201) composed with dynamic
 * modules: two instances of the same module's object in one device
 * (<code>TestModules.knxproj</code>) are separate objects. A flag set on one instance changes that
 * instance's object only, in its own connector and in the object table of the image (exactly the
 * configuration byte of its own descriptor, haumacher/ehome #202); the reset of one leaves the other
 * as it is.
 */
@SuppressWarnings("javadoc")
public class TestObjectFlagsProbe extends BasicTestCase {

	public void testModuleInstances() throws Exception {
		List<TLObject> before = instances("ehome.project:Project");
		importSample("src/test/fixtures/TestModules.knxproj");
		List<TLObject> projects = new ArrayList<>(instances("ehome.project:Project"));
		projects.removeAll(before);
		assertEquals(1, projects.size());
		TLObject project = projects.get(0);
		try {
			Interface[] pair = null;
			TLObject device = null;
			for (TLObject candidate : DeviceChanges.devices(project)) {
				pair = instancesOfOneObject(candidate);
				if (pair != null) {
					device = candidate;
					break;
				}
			}
			assertNotNull("A device with two instances of one module's object.", pair);
			Interface first = pair[0];
			Interface second = pair[1];
			boolean read = Boolean.TRUE.equals(ObjectFlags.get(device, first, "canRead"));
			byte[] initial = table(device);
			// Both instances inside the table, each with its own descriptor.
			Integer firstConfig = configPosition(initial, first);
			Integer secondConfig = configPosition(initial, second);
			assertFalse(firstConfig.equals(secondConfig));

			TLObject d = device;
			run(() -> ObjectFlags.set(d, first, "canRead", Boolean.valueOf(!read)));
			assertEquals(Boolean.valueOf(!read), ObjectFlags.get(device, first, "canRead"));
			assertEquals("The other instance keeps the program's flag.", Boolean.valueOf(read),
				ObjectFlags.get(device, second, "canRead"));
			assertTrue(ObjectFlags.objectDiffers(device, first));
			assertFalse(ObjectFlags.objectDiffers(device, second));
			assertEquals("The configuration byte of the first instance's descriptor.", List.of(firstConfig),
				differences(initial, table(device)));

			// Both changed: two connectors, one per instance; the first object's byte stays as set.
			run(() -> ObjectFlags.set(d, second, "canRead", Boolean.valueOf(!read)));
			assertTrue(ObjectFlags.objectDiffers(device, second));
			assertEquals(Boolean.valueOf(!read), ObjectFlags.get(device, first, "canRead"));
			assertNotSame(ObjectTypes.connector(device, first), ObjectTypes.connector(device, second));
			assertEquals("The configuration bytes of both instances' descriptors.",
				List.of(Math.min(firstConfig, secondConfig), Math.max(firstConfig, secondConfig)),
				differences(initial, table(device)));

			// The first reset: the second stays changed.
			run(() -> {
				ObjectFlags.reset(d, first);
				return null;
			});
			assertFalse(ObjectFlags.objectDiffers(device, first));
			assertTrue(ObjectFlags.objectDiffers(device, second));
			assertEquals(Boolean.valueOf(!read), ObjectFlags.get(device, second, "canRead"));
			assertEquals("The configuration byte of the second instance's descriptor.", List.of(secondConfig),
				differences(initial, table(device)));
			run(() -> {
				ObjectFlags.reset(d, second);
				return null;
			});
			assertFalse(ObjectFlags.objectDiffers(device, second));
			assertEquals(List.of(), differences(initial, table(device)));
		} finally {
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				project.tDelete();
				tx.commit();
			}
		}
	}

	/**
	 * Two active objects of the device that are instances of the same object of a module,
	 * <code>null</code> if there are none.
	 */
	private static Interface[] instancesOfOneObject(TLObject device) {
		if (DeviceConfiguration.program(device) == null) {
			return null;
		}
		Map<Object, Interface> byTemplate = new LinkedHashMap<>();
		for (Interface placed : new DeviceConfiguration(device).getActiveInterfaces().values()) {
			if (ModuleParts.instance(placed) == null) {
				continue;
			}
			Interface other = byTemplate.putIfAbsent(ModuleParts.template(placed), placed);
			if (other != null) {
				return new Interface[] { other, placed };
			}
		}
		return null;
	}

	/**
	 * The position of the configuration byte of the object's descriptor in the object table (BCU 1:
	 * the count and the RAM flags pointer, then per object value pointer, configuration and type).
	 */
	private static Integer configPosition(byte[] table, Interface placed) {
		int number = placed.getPort().getIndex();
		assertTrue("Object " + number + " inside the table of " + (table[0] & 0xFF), number < (table[0] & 0xFF));
		return Integer.valueOf(2 + 3 * number + 1);
	}

	private static byte[] table(TLObject device) {
		DownloadImage image = DownloadImage.compute(device);
		assertEquals(image.dump(), List.of(), image.getProblems());
		return image.getObjectTable().data();
	}

	private static List<Integer> differences(byte[] a, byte[] b) {
		assertEquals(a.length, b.length);
		List<Integer> result = new ArrayList<>();
		for (int n = 0; n < a.length; n++) {
			if (a[n] != b[n]) {
				result.add(Integer.valueOf(n));
			}
		}
		return result;
	}

	private static Object run(java.util.function.Supplier<Object> change) {
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			Object result = change.get();
			tx.commit();
			return result;
		}
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestObjectFlagsProbe.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
