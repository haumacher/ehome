/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.programming;

import static test.de.haumacher.ehome.importer.TestEtsImport.derived;
import static test.de.haumacher.ehome.importer.TestEtsImport.importSample;
import static test.de.haumacher.ehome.importer.TestEtsImport.instances;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import junit.framework.Test;
import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;
import test.de.haumacher.ehome.roundtrip.TestRoundTrip;

import com.top_logic.knowledge.service.KnowledgeBase;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.model.TLClassifier;
import com.top_logic.model.TLObject;
import com.top_logic.model.search.expr.config.ExprFormat;
import com.top_logic.model.search.expr.query.QueryExecutor;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.model.operational.Interface;
import de.haumacher.ehome.model.physical.Connector;
import de.haumacher.ehome.model.physical.Device;
import de.haumacher.ehome.modules.ModuleParts;
import de.haumacher.ehome.programming.DeviceChanges;
import de.haumacher.ehome.programming.DeviceChanges.Entry;
import de.haumacher.ehome.programming.DeviceChanges.Reference;
import de.haumacher.ehome.programming.DeviceConfiguration;
import de.haumacher.ehome.programming.DownloadImage;
import de.haumacher.ehome.programming.GroupTables;
import de.haumacher.ehome.programming.ObjectFlags;
import de.haumacher.ehome.template.ObjectTypes;

/**
 * The flags and the priority of a communication object of a device (haumacher/ehome #201), on
 * <code>src/test/fixtures/TestDetails.knxproj</code> (the Tastsensor of the Test project, downloaded
 * by ETS after its last change): the read flag ETS set at the device's room temperature object
 * (which the program disables) is imported, exported and imported again; a flag set through the
 * function of the UI is stored as a deviation from the program only, written into the object table
 * of the download image, makes the device changed since its last download and is listed there;
 * setting the program's value or resetting removes it again.
 */
@SuppressWarnings("javadoc")
public class TestObjectFlags extends BasicTestCase {

	private static final String FIXTURE = "src/test/fixtures/TestDetails.knxproj";

	/** The room temperature: the program's reference disables the read flag, the device sets it. */
	private static final String TEMPERATURE = "M-0008_A-10D7-11-A0E5-O000A_O-64_R-1249";

	/** "Taste 5": the program's reference disables the read and the update flag. */
	private static final String KEY = "M-0008_A-10D7-11-A0E5-O000A_O-4_R-2843";

	private static final String SET = "d -> i -> a -> v -> deviceSetObjectFlag($d, $i, $a, $v)";

	private static final String GET = "d -> i -> a -> deviceObjectFlag($d, $i, $a)";

	private static final String RESET = "d -> i -> deviceResetObjectFlags($d, $i)";

	private static final String CHANGED = "d -> i -> deviceObjectFlagsChanged($d, $i)";

	private static final String DEFAULT = "d -> i -> deviceObjectFlagsDefault($d, $i)";

	private static final String TEXT = "d -> i -> deviceObjectFlags($d, $i)";

	public void testFlags() throws Exception {
		List<TLObject> before = instances("ehome.project:Project");
		importSample(FIXTURE);
		List<TLObject> projects = new ArrayList<>(instances("ehome.project:Project"));
		projects.removeAll(before);
		assertEquals(1, projects.size());
		try {
			TLObject project = projects.get(0);
			TLObject device = device(project);
			checkImported(device);
			checkRoundTrip(project, projects);
			checkSet(device);
		} finally {
			try (Transaction tx = kb().beginTransaction()) {
				for (TLObject created : projects) {
					if (created.tValid()) {
						created.tDelete();
					}
				}
				tx.commit();
			}
		}
	}

	/**
	 * The read flag ETS set at the device: stored at the connector, other than the program's, in
	 * effect, and no change since the download ETS made.
	 */
	private void checkImported(TLObject device) {
		Interface temperature = placement(device, TEMPERATURE);
		assertEquals("Disabled", ObjectFlags.programValue(temperature, "canRead"));
		TLObject connector = ObjectTypes.connector(device, temperature);
		assertEquals("Enabled", name(((Connector) connector).getCanRead()));
		assertNull(((Connector) connector).getCanWrite());
		assertEquals(Boolean.TRUE, script(GET).execute(device, temperature, "canRead"));
		assertEquals(Boolean.TRUE, script(CHANGED).execute(device, temperature));
		assertEquals("Vorgabe: K Ü, Niedrig", TestDeviceChanges.asRoot(Locale.GERMAN,
			() -> script(DEFAULT).execute(device, temperature)));
		assertEquals("K L Ü, Niedrig", TestDeviceChanges.asRoot(Locale.GERMAN, () -> script(TEXT).execute(device, temperature)));

		// The download of ETS holds the flag: no change since; against the program, the flag differs.
		assertEquals(0, DeviceChanges.sinceDownload(device).getCount());
		assertEquals(Boolean.FALSE, derived(device, "downloadPending"));
		Entry entry = DeviceChanges.of(device, Reference.DEFAULT).getEntry(temperature);
		assertNotNull(entry);
		assertTrue(entry.flagsChanged());
		assertFalse(entry.connectionsChanged());
		assertFalse(entry.referenceFlags().enabled("canRead"));
		assertTrue(entry.currentFlags().enabled("canRead"));
	}

	/**
	 * The export writes the flag at the object of the device, a new import of the export reads it.
	 */
	private void checkRoundTrip(TLObject project, List<TLObject> projects) throws Exception {
		byte[] exported = TestRoundTrip.export(project);
		String xml = projectFile(exported);
		Matcher ref = Pattern.compile("<ComObjectInstanceRef [^>]*RefId=\"" + TEMPERATURE + "\"[^>]*>").matcher(xml);
		assertTrue(xml, ref.find());
		assertTrue(ref.group(), ref.group().contains("ReadFlag=\"Enabled\""));
		assertFalse(ref.group(), ref.group().contains("WriteFlag="));

		TLObject reimported = TestRoundTrip.importProject(exported, projects);
		TLObject device = device(reimported);
		Interface temperature = placement(device, TEMPERATURE);
		assertEquals("Enabled", name(((Connector) ObjectTypes.connector(device, temperature)).getCanRead()));
		assertEquals(Boolean.TRUE, script(CHANGED).execute(device, temperature));
	}

	/**
	 * A flag and the priority set as the UI sets them: the object table of the download image, the
	 * changes since the download, the program's value and the reset.
	 */
	private void checkSet(TLObject device) {
		Interface key = placement(device, KEY);
		int number = key.getPort().getIndex();
		assertTrue(new DeviceConfiguration(device).getActiveInterfaces().containsKey(key.getPort()));
		// Communication, write, transmit, low priority (the program's read and update flags disabled).
		assertEquals(0x57, config(device, number));
		assertEquals(Boolean.FALSE, script(GET).execute(device, key, "canRead"));
		assertEquals(Boolean.FALSE, script(CHANGED).execute(device, key));
		assertNull(script(DEFAULT).execute(device, key));

		// Set the read flag: stored at the device, written into the object table.
		assertEquals(Boolean.TRUE, run(() -> script(SET).execute(device, key, "canRead", Boolean.TRUE)));
		TLObject connector = ObjectTypes.connector(device, key);
		assertEquals("Enabled", name(((Connector) connector).getCanRead()));
		assertNull(((Connector) connector).getUpdate());
		assertEquals(0x5F, config(device, number));
		assertEquals(Boolean.TRUE, script(CHANGED).execute(device, key));
		assertEquals("Vorgabe: K S Ü, Niedrig",
			TestDeviceChanges.asRoot(Locale.GERMAN, () -> script(DEFAULT).execute(device, key)));

		// Changed since the download: the device and the object, with the device's flags.
		assertEquals(Boolean.TRUE, derived(device, "downloadPending"));
		DeviceChanges changes = DeviceChanges.sinceDownload(device);
		assertEquals(1, changes.getCount());
		assertTrue(changes.isChanged(device));
		assertTrue(changes.isChanged(key));
		Entry entry = changes.getEntry(key);
		assertTrue(entry.flagsChanged());
		assertFalse(entry.connectionsChanged());
		assertFalse(entry.referenceFlags().enabled("canRead"));
		assertTrue(entry.currentFlags().enabled("canRead"));
		assertEquals("K S Ü, Niedrig", TestDeviceChanges.asRoot(Locale.GERMAN, () -> changes.referenceText(key)));

		// The program's value: nothing stored, no change any more.
		assertEquals(Boolean.FALSE, run(() -> script(SET).execute(device, key, "canRead", Boolean.FALSE)));
		assertNull(((Connector) connector).getCanRead());
		assertEquals(Boolean.FALSE, script(CHANGED).execute(device, key));
		assertEquals(0, DeviceChanges.sinceDownload(device).getCount());
		assertEquals(0x57, config(device, number));

		// The priority.
		TLClassifier high = (TLClassifier) TLModelUtil.findPart("ehome.physical:Priority#High");
		assertEquals(high, run(() -> script(SET).execute(device, key, "priority", high)));
		assertEquals(0x55, config(device, number));
		assertEquals(1, DeviceChanges.sinceDownload(device).getCount());

		// Reset: the program's flags and priority again, no difference.
		run(() -> script(SET).execute(device, key, "canRead", Boolean.TRUE));
		assertEquals(0x5D, config(device, number));
		run(() -> script(RESET).execute(device, key));
		assertNull(((Connector) connector).getCanRead());
		assertNull(((Connector) connector).getPriority());
		assertEquals(Boolean.FALSE, script(CHANGED).execute(device, key));
		assertNull(script(DEFAULT).execute(device, key));
		assertEquals(0, DeviceChanges.sinceDownload(device).getCount());
		assertNull(DeviceChanges.of(device, Reference.DEFAULT).getEntry(key));
		assertEquals(0x57, config(device, number));

		// Back to the state of the download from the list of changes.
		run(() -> script(SET).execute(device, key, "canWrite", Boolean.FALSE));
		assertEquals(0x47, config(device, number));
		run(() -> {
			DeviceChanges.sinceDownload(device).reset(key);
			return null;
		});
		assertNull(((Connector) connector).getCanWrite());
		assertEquals(0, DeviceChanges.sinceDownload(device).getCount());
	}

	private static int config(TLObject device, int number) {
		DownloadImage image = DownloadImage.compute(device);
		assertEquals(image.dump(), List.of(), image.getProblems());
		byte[] table = image.getObjectTable().data();
		return table[GroupTables.OBJECT_TABLE_HEADER + GroupTables.OBJECT_DESCRIPTOR * number
			+ GroupTables.OBJECT_CONFIG] & 0xFF;
	}

	private static Object run(java.util.function.Supplier<Object> change) {
		try (Transaction tx = kb().beginTransaction()) {
			Object result = change.get();
			tx.commit();
			return result;
		}
	}

	private static TLObject device(TLObject project) {
		for (TLObject device : DeviceChanges.devices(project)) {
			if ("Taster Küche".equals(((Device) device).getTitle())) {
				return device;
			}
		}
		throw new AssertionError("No device \"Taster Küche\".");
	}

	/**
	 * The placement of the communication object of the given ID among the objects of the device.
	 */
	private static Interface placement(TLObject device, String id) {
		for (Object element : ((Device) device).getConnectors()) {
			Interface placed = ModuleParts.port((TLObject) element);
			if (placed != null && id.equals(placed.getExtId())) {
				return placed;
			}
		}
		for (Interface placed : new DeviceConfiguration(device).getActiveInterfaces().values()) {
			if (id.equals(placed.getExtId())) {
				return placed;
			}
		}
		throw new AssertionError("No communication object " + id);
	}

	private static String name(Object classifier) {
		return classifier instanceof TLClassifier value ? value.getName() : null;
	}

	private static String projectFile(byte[] archive) throws Exception {
		try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(archive))) {
			for (ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
				if (entry.getName().matches("P-[0-9A-F]+/0\\.xml")) {
					return new String(in.readAllBytes(), StandardCharsets.UTF_8);
				}
			}
		}
		throw new AssertionError("No project file in the export.");
	}

	private static QueryExecutor script(String expr) {
		try {
			return QueryExecutor.compile(ExprFormat.INSTANCE.getValue("expr", expr));
		} catch (Exception ex) {
			throw new AssertionError(ex);
		}
	}

	private static KnowledgeBase kb() {
		return PersistencyLayer.getKnowledgeBase();
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestObjectFlags.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
