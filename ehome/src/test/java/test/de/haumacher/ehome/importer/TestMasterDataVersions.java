/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.importer;

import static test.de.haumacher.ehome.importer.TestEtsImport.instances;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import junit.framework.Test;

import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;

import com.top_logic.basic.i18n.log.BufferingI18NLog;
import com.top_logic.basic.io.binary.BinaryDataSource;
import com.top_logic.basic.logging.Level;
import com.top_logic.basic.util.ResKey;
import com.top_logic.basic.util.ResourcesModule;
import com.top_logic.knowledge.objects.KnowledgeItem;
import com.top_logic.knowledge.service.KnowledgeBase;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.model.TLClassifier;
import com.top_logic.model.TLObject;
import com.top_logic.model.TLReference;
import com.top_logic.model.TLStructuredTypePart;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.importer.I18NConstants;
import de.haumacher.ehome.importer.MasterDataUpdates;

/**
 * The mask versions by the version of the master data they come from (haumacher/ehome #34), with the
 * artificial master data <code>TestMasterDataOld.knxproj</code> (version 1001) and
 * <code>TestMasterDataNew.knxproj</code> (version 1002), see <code>make-fixtures.py</code>: the new
 * master data swap the legacy marking and the order of the configurations of the mask version
 * <code>MV-FFF1</code> (as ETS 6 against ETS 4 for the IP router mask <code>MV-091A</code>), and
 * define <code>MV-FFF2</code> as the old ones.
 *
 * <p>
 * Newer master data update a known mask version in place, older ones keep it and warn, the same ones
 * change nothing: the mask versions in effect do not depend on the order of the imports.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestMasterDataVersions extends BasicTestCase {

	private static final String OLD = "src/test/fixtures/TestMasterDataOld.knxproj";

	private static final String NEW = "src/test/fixtures/TestMasterDataNew.knxproj";

	private static final String MASK = "ehome.operational:MaskVersion";

	private static final String VERSION = "masterDataVersion";

	private static final Set<String> TEST_MASKS = Set.of("MV-FFF1", "MV-FFF2");

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		deleteTestMasks();
	}

	@Override
	protected void tearDown() throws Exception {
		deleteTestMasks();
		super.tearDown();
	}

	/**
	 * Old master data, then new ones: the new ones update the mask version they define differently
	 * in place, to what an import of the new ones alone gives, and record their version.
	 */
	public void testNewerUpdates() throws Exception {
		assertEquals(List.of(), masterDataMessages(importSample(NEW)));
		Map<String, String> fromNew = dumps();
		assertEquals(Map.of("MV-FFF1", 1002, "MV-FFF2", 1002), versions());
		deleteTestMasks();

		assertEquals(List.of(), masterDataMessages(importSample(OLD)));
		Map<String, String> fromOld = dumps();
		assertFalse(fromOld.get("MV-FFF1").equals(fromNew.get("MV-FFF1")));
		assertEquals(fromOld.get("MV-FFF2"), fromNew.get("MV-FFF2"));
		Map<String, TLObject> before = masks();
		// The old master data: the configuration by memory is the current one.
		assertFalse(((Collection<?>) configuration(before.get("MV-FFF1"), false).tValueByName("memory")).isEmpty());

		List<BufferingI18NLog.Entry> messages = masterDataEntries(importSample(NEW));
		assertEquals(1, messages.size());
		assertEquals(Level.INFO, messages.get(0).getLevel());
		ResKey updated = I18NConstants.INFO_MASTER_DATA_UPDATED__OBJECT_KNOWN_VERSION.fill(
			MasterDataUpdates.label(before.get("MV-FFF1"), "MV-FFF1"), "1001", "1002");
		assertEquals(updated, inner(messages.get(0).getMessage()));
		// The message names the kind of object and its name, not only its ID.
		assertEquals("Mask version \"TEST Maske A (künstliche Testdaten)\" (MV-FFF1) is updated from its definition"
			+ " in the master data of version 1001 to the one in the master data of version 1002.",
			ResourcesModule.getInstance().getBundle(Locale.ENGLISH).getString(updated));
		assertEquals("Maskenversion „TEST Maske A (künstliche Testdaten)“ (MV-FFF1) wird von der Definition der"
			+ " Stammdaten der Version 1001 auf die der Stammdaten der Version 1002 aktualisiert.",
			ResourcesModule.getInstance().getBundle(Locale.GERMAN).getString(updated));
		assertEquals(fromNew, dumps());
		assertEquals(Map.of("MV-FFF1", 1002, "MV-FFF2", 1002), versions());
		// The new master data: the configuration by memory is the legacy one.
		assertFalse(((Collection<?>) configuration(before.get("MV-FFF1"), true).tValueByName("memory")).isEmpty());
		assertTrue(((Collection<?>) configuration(before.get("MV-FFF1"), false).tValueByName("memory")).isEmpty());
		// In place: the mask versions keep their identity, the compatible mask refers to the known one.
		assertEquals(before, masks());
		assertEquals(List.of(before.get("MV-FFF2")), before.get("MV-FFF1").tValueByName("compatibleMasks"));
		// No duplicates of the parts, no candidates left over.
		assertEquals(2, instances(MASK).stream().filter(m -> TEST_MASKS.contains(m.tValueByName("extId"))).count());
	}

	/**
	 * New master data, then old ones: the mask versions of the new ones stay, and a warning names
	 * the mask version the old ones define differently, with both versions.
	 */
	public void testOlderKeeps() throws Exception {
		importSample(NEW);
		Map<String, String> fromNew = dumps();
		Map<String, TLObject> before = masks();

		List<BufferingI18NLog.Entry> messages = masterDataEntries(importSample(OLD));
		assertEquals(1, messages.size());
		BufferingI18NLog.Entry warning = messages.get(0);
		assertEquals(Level.WARN, warning.getLevel());
		ResKey message = I18NConstants.WARN_MASTER_DATA_DIFFER__OBJECT_VERSION_KNOWN.fill(
			MasterDataUpdates.label(before.get("MV-FFF1"), "MV-FFF1"), "1001", "1002");
		assertEquals(message, inner(warning.getMessage()));
		assertEquals("Mask version \"TEST Maske A (künstliche Testdaten)\" (MV-FFF1) is defined differently in the"
			+ " master data of version 1001 than in those of version 1002, from which E-Home took its definition."
			+ " The definition of version 1002 is kept.",
			ResourcesModule.getInstance().getBundle(Locale.ENGLISH).getString(message));
		// The warning points to the mask version in the master data.
		Object[] location = warning.getMessage().arguments();
		assertEquals("knxproj:knx_master.xml", location[0]);
		assertTrue(((Number) location[1]).intValue() > 0);

		assertEquals(fromNew, dumps());
		assertEquals(before, masks());
		assertEquals(Map.of("MV-FFF1", 1002, "MV-FFF2", 1002), versions());
	}

	/**
	 * The same master data twice: nothing changes and nothing is reported.
	 */
	public void testSameTwice() throws Exception {
		for (String file : List.of(OLD, NEW)) {
			deleteTestMasks();
			importSample(file);
			Map<String, String> dumped = dumps();
			Map<String, TLObject> before = masks();
			int parts = parts();
			assertEquals(List.of(), masterDataMessages(importSample(file)));
			assertEquals(dumped, dumps());
			assertEquals(before, masks());
			assertEquals(parts, parts());
		}
	}

	/**
	 * Imports the given fixture, which must give no error.
	 */
	private static List<BufferingI18NLog.Entry> importSample(String file) throws Exception {
		List<BufferingI18NLog.Entry> messages = TestEtsImport.importSample(file);
		for (BufferingI18NLog.Entry entry : messages) {
			assertFalse(String.valueOf(entry.getMessage()), entry.getLevel().is(Level.ERROR));
		}
		return messages;
	}

	private static TLObject configuration(TLObject mask, boolean legacy) {
		for (Object configuration : (Collection<?>) mask.tValueByName("configurations")) {
			if (Boolean.valueOf(legacy).equals(((TLObject) configuration).tValueByName("legacy"))) {
				return (TLObject) configuration;
			}
		}
		return null;
	}

	/**
	 * The message wrapped in its location.
	 */
	private static ResKey inner(ResKey message) {
		Object[] arguments = message.arguments();
		if (arguments.length > 0 && arguments[arguments.length - 1] instanceof ResKey detail) {
			return detail;
		}
		return message;
	}

	private static List<BufferingI18NLog.Entry> masterDataEntries(List<BufferingI18NLog.Entry> messages) {
		return messages.stream().filter(m -> String.valueOf(m.getMessage()).contains("_MASTER_DATA_")).toList();
	}

	private static List<ResKey> masterDataMessages(List<BufferingI18NLog.Entry> messages) {
		return masterDataEntries(messages).stream().map(BufferingI18NLog.Entry::getMessage).toList();
	}

	private static Map<String, TLObject> masks() {
		Map<String, TLObject> result = new TreeMap<>();
		for (TLObject mask : instances(MASK)) {
			String id = (String) mask.tValueByName("extId");
			if (TEST_MASKS.contains(id)) {
				assertNull("Duplicate mask version " + id + ".", result.put(id, mask));
			}
		}
		return result;
	}

	private static Map<String, Integer> versions() {
		Map<String, Integer> result = new TreeMap<>();
		masks().forEach((id, mask) -> result.put(id, (Integer) mask.tValueByName(VERSION)));
		return result;
	}

	/**
	 * The number of parts of the test mask versions.
	 */
	private static int parts() {
		int result = 0;
		for (String type : List.of("MaskConfiguration", "MaskEntry", "MaskFeature", "MaskResource", "ResourceLocation",
			"MaskMemory", "LoadProcedure", "LoadStep")) {
			result += instances("ehome.operational:" + type).size();
		}
		return result;
	}

	private static Map<String, String> dumps() {
		Map<String, String> result = new TreeMap<>();
		masks().forEach((id, mask) -> {
			StringBuilder out = new StringBuilder();
			dump(out, "", mask);
			result.put(id, out.toString());
		});
		return result;
	}

	/**
	 * The stored content of an object as text, its composite parts nested, other objects by their ID
	 * or name.
	 */
	private static void dump(StringBuilder out, String indent, TLObject obj) {
		out.append(obj.tType().getName()).append('\n');
		List<TLStructuredTypePart> parts = new ArrayList<>(obj.tType().getAllParts());
		parts.sort(Comparator.comparing(TLStructuredTypePart::getName));
		for (TLStructuredTypePart part : parts) {
			if (part.isDerived() || part.getName().equals(VERSION)) {
				continue;
			}
			Object value = obj.tValue(part);
			if (part instanceof TLReference reference && reference.isComposite()) {
				Collection<?> children = value instanceof Collection<?> collection ? collection
					: value == null ? List.of() : List.of(value);
				for (Object child : children) {
					out.append(indent).append(part.getName()).append(": ");
					dump(out, indent + "  ", (TLObject) child);
				}
			} else {
				out.append(indent).append(part.getName()).append(" = ").append(text(value)).append('\n');
			}
		}
	}

	private static String text(Object value) {
		if (value instanceof TLClassifier classifier) {
			return classifier.getName();
		}
		if (value instanceof TLObject obj) {
			return String.valueOf(obj.tValueByName("extId"));
		}
		if (value instanceof BinaryDataSource data) {
			ByteArrayOutputStream buffer = new ByteArrayOutputStream();
			try {
				data.deliverTo(buffer);
			} catch (IOException ex) {
				throw new AssertionError(ex);
			}
			return HexFormat.of().formatHex(buffer.toByteArray());
		}
		if (value instanceof Collection<?> collection) {
			List<String> texts = new ArrayList<>();
			for (Object element : collection) {
				texts.add(text(element));
			}
			if (value instanceof Set<?>) {
				texts.sort(null);
			}
			return texts.toString();
		}
		return String.valueOf(value);
	}

	private static void deleteTestMasks() throws Exception {
		KnowledgeBase kb = PersistencyLayer.getKnowledgeBase();
		try (Transaction tx = kb.beginTransaction()) {
			List<KnowledgeItem> items = new ArrayList<>();
			for (TLObject mask : masks().values()) {
				items.add(mask.tHandle());
			}
			kb.deleteAll(items);
			tx.commit();
		}
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestMasterDataVersions.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
