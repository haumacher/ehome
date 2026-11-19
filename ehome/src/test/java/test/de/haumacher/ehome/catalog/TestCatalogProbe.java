/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.catalog;

import static test.de.haumacher.ehome.importer.TestEtsImport.derived;
import static test.de.haumacher.ehome.importer.TestEtsImport.importSample;
import static test.de.haumacher.ehome.importer.TestEtsImport.instances;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import junit.framework.Test;
import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;

import com.top_logic.basic.util.ResKey;
import com.top_logic.knowledge.service.KnowledgeBase;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.knowledge.wrap.person.Person;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.model.TLClass;
import com.top_logic.model.TLObject;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.TLContext;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.knx.ExtIds;
import de.haumacher.ehome.knx.ExtIds.Kind;
import de.haumacher.ehome.knx.ProgramStructure;
import de.haumacher.ehome.knx.ProgramStructure.Position;
import de.haumacher.ehome.model.catalog.Catalog;
import de.haumacher.ehome.model.catalog.Manufacturer;
import de.haumacher.ehome.model.catalog.ManufacturerRoot;
import de.haumacher.ehome.model.catalog.Product;
import de.haumacher.ehome.model.catalog.ProgramAssignment;
import de.haumacher.ehome.model.catalog.Variant;
import de.haumacher.ehome.model.core.Externalized;
import de.haumacher.ehome.model.operational.Channel;
import de.haumacher.ehome.model.operational.EhomeOperationalFactory;
import de.haumacher.ehome.model.operational.EnumerationType;
import de.haumacher.ehome.model.operational.EnumerationValue;
import de.haumacher.ehome.model.operational.NumericType;
import de.haumacher.ehome.model.operational.Parameter;
import de.haumacher.ehome.model.operational.ParameterGroup;
import de.haumacher.ehome.model.operational.ParameterView;
import de.haumacher.ehome.model.operational.Program;

/**
 * Probe of the catalog creation composed with an imported project: the KNX ID of an imported
 * manufacturer is taken, private IDs continue after the import, placements of one parameter are
 * numbered apart, a placement in an imported program continues its numbering, equal names and
 * values stay unique, the vetoes speak, and deleting a manufacturer leaves nothing behind.
 */
public class TestCatalogProbe extends BasicTestCase {

	private static final String PROGRAM = "M-0008_A-10D7-11-A0E5-O000A";

	private KnowledgeBase _kb;

	public void testProbe() throws Exception {
		_kb = PersistencyLayer.getKnowledgeBase();
		// The tests share one database: the projects this probe imports are removed at the end.
		List<TLObject> projectsBefore = new ArrayList<>(instances("ehome.project:Project"));
		try {
			probe();
		} finally {
			try (Transaction tx = _kb.beginTransaction()) {
				for (TLObject project : instances("ehome.project:Project")) {
					if (!projectsBefore.contains(project)) {
						project.tDelete();
					}
				}
				tx.commit();
			}
		}
	}

	private void probe() throws Exception {
		importSample("samples/Test.knxproj");
		ManufacturerRoot root =
			(ManufacturerRoot) TLModelUtil.findType("ehome.catalog:ManufacturerRoot").getModule().getSingleton("ROOT");
		Program imported = (Program) byExtId("ehome.operational:Program", PROGRAM);

		// The KNX ID of GIRA is taken: the constraint refuses the commit.
		try (Transaction tx = _kb.beginTransaction()) {
			Manufacturer clash = create("ehome.catalog:Manufacturer");
			clash.setName("Doppelt");
			clash.setId(8);
			root.addManufacturer(clash);
			try {
				tx.commit();
				fail("Manufacturer with the KNX ID of GIRA committed.");
			} catch (Exception expected) {
				// Refused.
			}
		}

		// Manufacturers without KNX ID get consecutive private IDs after an import of 318 manufacturers.
		Manufacturer first;
		Manufacturer second;
		try (Transaction tx = _kb.beginTransaction()) {
			first = create("ehome.catalog:Manufacturer");
			first.setName("Eigenbau (Testdaten)");
			root.addManufacturer(first);
			second = create("ehome.catalog:Manufacturer");
			second.setName("Eigenbau 2 (Testdaten)");
			root.addManufacturer(second);
			tx.commit();
		}
		long firstNumber = number(ExtIds.extId(first), "^M-FF([0-9A-F]{2})$", 16);
		long secondNumber = number(ExtIds.extId(second), "^M-FF([0-9A-F]{2})$", 16);
		// The numbers are given when the transaction commits, in the order the engine initializes the new
		// objects, which need not be the order of creation.
		assertEquals(1, Math.abs(secondNumber - firstNumber));

		// A program from scratch under the first manufacturer.
		EhomeOperationalFactory factory = EhomeOperationalFactory.getInstance();
		Program program;
		EnumerationType choice;
		NumericType level;
		NumericType levelAgain;
		Parameter mode;
		Parameter brightness;
		ParameterGroup block;
		Channel channel;
		ParameterView placement1;
		ParameterView placement2;
		ParameterView importedPlacement;
		Parameter importedParameter = imported.getParameters().iterator().next();
		ParameterGroup importedBlock = (ParameterGroup) ExtIds.members(imported, Kind.BLOCK).iterator().next();
		long importedRefs = max(ExtIds.members(imported, Kind.PARAMETER_REF), "_R-(\\d+)$");
		try (Transaction tx = _kb.beginTransaction()) {
			program = factory.createProgram();
			program.setNameKey(ResKey.builder().add(Locale.GERMAN, "Probe").add(Locale.ENGLISH, "Probe").build());
			first.addProgram(program);

			choice = factory.createEnumerationType();
			choice.setName("Modus");
			program.addParameterType(choice);
			for (int i = 0; i < 3; i++) {
				EnumerationValue entry = factory.createEnumerationValue();
				entry.setNameKey(ResKey.builder().add(Locale.GERMAN, "Eintrag " + i).add(Locale.ENGLISH, "Entry " + i).build());
				// Two entries share the value 1: their IDs must still differ.
				entry.setValue(i == 0 ? 1 : i);
				entry.setIndex(i);
				choice.addValue(entry);
			}
			level = factory.createNumericType();
			level.setName("Helligkeit");
			program.addParameterType(level);
			levelAgain = factory.createNumericType();
			levelAgain.setName("Helligkeit");
			program.addParameterType(levelAgain);

			mode = factory.createParameter();
			mode.setName("mode");
			mode.setType(choice);
			mode.setChoice(choice.getValues().get(2));
			program.addParameter(mode);
			brightness = factory.createParameter();
			brightness.setName("brightness");
			brightness.setType(level);
			brightness.setNumber(42L);
			program.addParameter(brightness);

			channel = factory.createChannel();
			channel.setName("Taste 1");
			channel.setNumber("1");
			program.addContent(channel);
			block = factory.createParameterGroup();
			block.setName("Allgemein");
			channel.addContent(block);

			placement1 = factory.createParameterView();
			placement1.setParameter(brightness);
			block.addContent(placement1);
			placement2 = factory.createParameterView();
			placement2.setParameter(brightness);
			block.addContent(placement2);

			// A placement in the imported program continues its numbering.
			importedPlacement = factory.createParameterView();
			importedPlacement.setParameter(importedParameter);
			importedBlock.addContent(importedPlacement);
			tx.commit();
		}

		String programId = ExtIds.extId(program);
		assertTrue(programId, programId.matches("^M-FF[0-9A-F]{2}_A-[0-9A-F]{4}-01-[0-9A-F]{4}$"));
		assertTrue(programId, programId.startsWith(ExtIds.extId(first) + "_"));

		// Equal names and values stay unique in their scope.
		assertNotSame(ExtIds.extId(level), ExtIds.extId(levelAgain));
		assertFalse(ExtIds.extId(level).equals(ExtIds.extId(levelAgain)));
		assertTrue(ExtIds.extId(level).startsWith(programId + "_PT-"));
		List<String> entryIds = choice.getValues().stream().map(ExtIds::extId).toList();
		assertEquals(3, entryIds.stream().distinct().count());
		for (String id : entryIds) {
			assertTrue(id, id.startsWith(ExtIds.extId(choice) + "_EN-"));
		}

		// Two placements of one parameter: numbered apart, both named by the parameter.
		String p1 = ExtIds.extId(placement1);
		String p2 = ExtIds.extId(placement2);
		assertFalse(p1.equals(p2));
		assertTrue(p1, p1.matches(Pattern.quote(ExtIds.extId(brightness)) + "_R-\\d+"));
		assertTrue(p2, p2.matches(Pattern.quote(ExtIds.extId(brightness)) + "_R-\\d+"));

		// The placement in the imported program continues above the imported reference numbers.
		String importedId = ExtIds.extId(importedPlacement);
		assertTrue(importedId, importedId.startsWith(ExtIds.extId(importedParameter) + "_R-"));
		assertTrue(importedId, number(importedId, "_R-(\\d+)$", 10) > importedRefs);

		// Labels in both languages.
		for (TLObject obj : List.of(channel, block, placement1, mode, brightness, choice)) {
			for (Locale locale : List.of(Locale.GERMAN, Locale.ENGLISH)) {
				String label = inLocale(locale, obj);
				assertTrue(obj + " in " + locale + ": " + label, label != null && !label.isBlank() && !label.contains("AttributedWrapper"));
			}
		}
		assertEquals("Eintrag 2", inLocale(Locale.GERMAN, mode, "displayValue"));
		assertEquals("Entry 2", inLocale(Locale.ENGLISH, mode, "displayValue"));
		assertEquals("42", derived(brightness, "encodedValue"));

		// The vetoes speak.
		assertNotNull("Block moved into itself.", ProgramStructure.moveVeto(List.of(channel), block, null));
		assertNotNull("Placement moved into the program.", ProgramStructure.moveVeto(List.of(placement1), program, null));
		assertNotNull("Placement moved into another program.",
			ProgramStructure.moveVeto(List.of(placement1), importedBlock, null));
		assertNotNull("Program moved.", ProgramStructure.moveVeto(List.of(program), channel, null));
		assertNotNull("Placement moved beside the program.", ProgramStructure.moveVeto(List.of(placement1), null, null));
		assertNotNull("Nothing moved.", ProgramStructure.moveVeto(List.of(), block, null));
		assertNull(ProgramStructure.moveVeto(List.of(placement1), channel, block));

		// Drag and drop in the structure tree: before an element, before itself, into another element, at the end.
		move(List.of(placement2), block, placement1);
		assertEquals(List.of(placement2, placement1), block.getContents());
		move(List.of(placement2), block, placement2);
		assertEquals(List.of(placement2, placement1), block.getContents());
		move(List.of(placement1), channel, block);
		assertEquals(List.of(placement1, block), channel.getContents());
		assertEquals(List.of(placement2), block.getContents());
		assertSame(channel, placement1.tContainer());
		move(List.of(placement1), block, null);
		assertEquals(List.of(placement2, placement1), block.getContents());
		move(List.of(placement1, placement2), block, null);
		assertEquals(List.of(placement1, placement2), block.getContents());
		assertEquals(List.of(block), channel.getContents());
		assertNotNull("Case inserted into a block.",
			ProgramStructure.insertVeto(block, (TLClass) TLModelUtil.findType("ehome.operational:Case")));
		assertFalse(ProgramStructure.canInsert(program, (TLClass) TLModelUtil.findType("ehome.operational:Channel"), Position.AFTER));
		assertTrue(ProgramStructure.canInsert(program, (TLClass) TLModelUtil.findType("ehome.operational:Channel"), Position.INSIDE));
		assertNotNull("Type in use deleted.", ProgramStructure.deleteVeto(choice));
		assertNull(ProgramStructure.deleteVeto(levelAgain));

		// A product with a variant and the assignment of the program, as the dialogs create them.
		Product product;
		Variant variant;
		ProgramAssignment assignment;
		try (Transaction tx = _kb.beginTransaction()) {
			Catalog catalog = create("ehome.catalog:Catalog");
			catalog.setNameKey(ResKey.builder().add(Locale.GERMAN, "Probe").add(Locale.ENGLISH, "Probe").build());
			first.addCatalog(catalog);
			product = create("ehome.catalog:Product");
			product.setName("Probe");
			first.addProduct(product);
			variant = create("ehome.catalog:Variant");
			variant.setOrderNumber("1234 00");
			product.addVariant(variant);
			catalog.addVariant(variant);
			assignment = create("ehome.catalog:ProgramAssignment");
			assignment.setProgram(program);
			product.addProgram(assignment);
			tx.commit();
		}
		assertTrue(ExtIds.extId(assignment), ExtIds.extId(assignment).startsWith(ExtIds.extId(product) + "_HP-"));

		// The imported program and its placement survive a re-import; the created objects keep their IDs.
		importSample("samples/Test.knxproj");
		assertEquals(importedId, ExtIds.extId(importedPlacement));
		assertEquals(p1, ExtIds.extId(placement1));

		// Deleting the manufacturer takes everything of it along, the assignment of its product included.
		try (Transaction tx = _kb.beginTransaction()) {
			importedPlacement.tDelete();
			second.tDelete();
			first.tDelete();
			tx.commit();
		}
		for (TLObject obj : List.of(program, choice, level, mode, brightness, channel, block, placement1, placement2, product, variant)) {
			assertFalse(obj + " survived the deletion of its manufacturer.", obj.tValid());
		}
		assertFalse("The program assignment survived the deletion of its product and program.", assignment.tValid());
		for (TLObject dangling : instances("ehome.catalog:ProgramAssignment")) {
			TLObject owner = ((ProgramAssignment) dangling).getProduct();
			assertTrue("Assignment without product: " + ExtIds.extId(dangling), owner != null && owner.tValid());
		}

	}

	private static long max(Collection<TLObject> objects, String pattern) {
		long result = 0;
		for (TLObject obj : objects) {
			String id = ExtIds.extId(obj);
			if (id != null) {
				Matcher m = Pattern.compile(pattern).matcher(id);
				if (m.find()) {
					result = Math.max(result, Long.parseLong(m.group(1)));
				}
			}
		}
		return result;
	}

	private static long number(String id, String pattern, int radix) {
		assertNotNull(id);
		Matcher m = Pattern.compile(pattern).matcher(id);
		assertTrue(id + " does not match " + pattern, m.find());
		return Long.parseLong(m.group(1), radix);
	}

	private static String inLocale(Locale locale, TLObject obj) {
		return (String) inLocale(locale, obj, obj.tType().getPart("displayName") != null ? "displayName"
			: obj.tType().getPart("title") != null ? "title" : "name");
	}

	private static Object inLocale(Locale locale, TLObject obj, String attribute) {
		TLContext context = TLContext.getContext();
		Person before = context.getPerson();
		String contextId = context.getContextId();
		context.setPerson(PersonManager.getManager().getRoot());
		Locale localeBefore = context.getCurrentLocale();
		context.setCurrentLocale(locale);
		try {
			Object value = obj.tValueByName(attribute);
			return value instanceof ResKey key ? com.top_logic.util.Resources.getInstance(locale).getString(key) : value;
		} finally {
			context.setCurrentLocale(localeBefore);
			context.setPerson(before);
			if (before == null) {
				context.setContextId(contextId);
			}
		}
	}

	private static TLObject byExtId(String typeName, String extId) {
		List<TLObject> result = instances(typeName).stream()
			.filter(obj -> extId.equals(((Externalized) obj).getExtId()))
			.toList();
		assertEquals("Objects " + typeName + " with ID " + extId, 1, result.size());
		return result.get(0);
	}

	@SuppressWarnings("unchecked")
	private static <T extends TLObject> T create(String typeName) {
		return (T) ModelService.getInstance().getFactory().createObject((TLClass) TLModelUtil.findType(typeName));
	}

	private void move(List<TLObject> nodes, TLObject container, TLObject before) {
		try (Transaction tx = _kb.beginTransaction()) {
			ProgramStructure.move(nodes, container, before);
			tx.commit();
		}
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestCatalogProbe.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
