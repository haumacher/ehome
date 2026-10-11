/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.programming;

import static test.de.haumacher.ehome.importer.TestEtsImport.importSample;
import static test.de.haumacher.ehome.importer.TestEtsImport.instances;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

import junit.framework.Test;
import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;

import com.top_logic.basic.io.binary.BinaryDataFactory;
import com.top_logic.basic.util.ResKey;
import com.top_logic.basic.io.binary.BinaryDataSource;
import com.top_logic.knowledge.service.KnowledgeBase;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.model.TLClass;
import com.top_logic.model.TLClassifier;
import com.top_logic.model.TLModule;
import com.top_logic.model.TLObject;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.model.operational.CodeSegment;
import de.haumacher.ehome.model.operational.EhomeOperationalFactory;
import de.haumacher.ehome.model.operational.LoadProcedure;
import de.haumacher.ehome.model.operational.LoadStep;
import de.haumacher.ehome.model.operational.MaskResource;
import de.haumacher.ehome.model.operational.MaskVersion;
import de.haumacher.ehome.model.operational.Parameter;
import de.haumacher.ehome.model.operational.Pointer;
import de.haumacher.ehome.model.operational.Program;
import de.haumacher.ehome.model.operational.PropertyLocation;
import de.haumacher.ehome.model.operational.Segment;
import de.haumacher.ehome.model.operational.TableLocation;
import de.haumacher.ehome.model.operational.VoidType;

/**
 * Probe of the download data import (issue #5) composed with the rest of the application: the
 * second program of the same mask shares the mask version, the load procedure of a program refers
 * to segments the program has, a mask without procedures of its own has the generic ones, every
 * segment with data has exactly its size, and download data created from scratch is owned by the
 * program and goes with it.
 */
@SuppressWarnings("javadoc")
public class TestDownloadDataProbe extends BasicTestCase {

	private static final String ACTUATOR = "M-0083_A-000C-21-83B5";

	private static final String ROUTER = "M-006A_A-0703-10-FD20-O00C5";

	private static final String SENSOR = "M-0008_A-10D7-11-A0E5-O000A";

	public void testProbe() throws Exception {
		// The tests share one database: both projects may be known already, both imports reuse them.
		List<TLObject> before = instances("ehome.project:Project");
		importSample("samples/Test.knxproj");
		importSample("samples/EHomeDemo.knxproj");
		List<TLObject> projects = new ArrayList<>(instances("ehome.project:Project"));
		projects.removeAll(before);
		try {
			probe();
		} finally {
			KnowledgeBase kb = PersistencyLayer.getKnowledgeBase();
			try (Transaction tx = kb.beginTransaction()) {
				for (TLObject project : projects) {
					project.tDelete();
				}
				tx.commit();
			}
		}
	}

	private void probe() throws Exception {
		Program sensor = program(SENSOR);
		Program actuator = program(ACTUATOR);
		Program router = program(ROUTER);

		// One mask version object for both programs of mask 7.5; the master data of both files
		// describe the same 29 masks, imported once.
		MaskVersion mask = sensor.getMask();
		assertSame(mask, actuator.getMask());
		assertEquals(1797, mask.getNumber());
		assertEquals(1, masks("MV-0705").size());
		assertEquals(29, masks().size());

		// The table resources of the mask name the memory the tables live in, which is where the
		// program puts them: the address table of both programs starts at the mask's location.
		MaskResource addressTable = resource(mask, "GroupAddressTable");
		assertEquals("StandardMemory", addressTable.getLocation().getAddressSpace().getName());
		assertEquals(Long.valueOf(0x4000), addressTable.getLocation().getStartAddress());
		assertEquals(0x4000, ((CodeSegment) sensor.getAddressTable().getSegment()).getAddress().intValue());
		assertEquals(0x4000, ((CodeSegment) actuator.getAddressTable().getSegment()).getAddress().intValue());
		assertEquals(Integer.valueOf(255), actuator.getAddressTable().getMaxEntries());
		assertEquals(Integer.valueOf(120), sensor.getAddressTable().getMaxEntries());
		MaskResource programmingMode = resource(mask, "ProgrammingMode");
		assertEquals(Long.valueOf(0x60), programmingMode.getLocation().getStartAddress());
		assertEquals(Long.valueOf(1), programmingMode.getLength());

		// The actuator's product procedure: the hardware check before the load, and every segment
		// the procedure loads is a segment of the program.
		assertEquals("ProductProcedure", actuator.getLoadProcedureStyle().getName());
		assertEquals(1, actuator.getLoadProcedures().size());
		LoadProcedure procedure = actuator.getLoadProcedures().get(0);
		assertEquals(22, procedure.getSteps().size());
		LoadStep compare = procedure.getSteps().get(1);
		assertEquals("CompareProp", compare.getKind().getName());
		assertEquals(Integer.valueOf(0), compare.getObjectIndex());
		assertEquals(Integer.valueOf(78), compare.getPropertyId());
		assertEquals("00000000030d00000000", hex(compare.getInlineData()));
		List<Integer> segmentAddresses = codeSegments(actuator).stream().map(CodeSegment::getAddress).sorted().toList();
		assertEquals(List.of(0x0700, 0x0784, 0x4000, 0x4201, 0x4400, 0x44EC), segmentAddresses);
		List<Long> loaded = procedure.getSteps().stream()
			.filter(s -> "AbsSegment".equals(s.getKind().getName()))
			.map(LoadStep::getAddress)
			.toList();
		assertEquals(6, loaded.size());
		for (Long address : loaded) {
			assertTrue("Loads an address that is no segment: " + address, segmentAddresses.contains(address.intValue()));
		}
		assertEquals("Restart", procedure.getSteps().get(20).getKind().getName());
		assertEquals("Disconnect", procedure.getSteps().get(21).getKind().getName());

		// The router has no procedure of its own: the generic procedures of its mask apply.
		assertEquals("DefaultProcedure", router.getLoadProcedureStyle().getName());
		assertTrue(router.getLoadProcedures().isEmpty());
		MaskVersion coupler = router.getMask();
		assertEquals(2330, coupler.getNumber());
		assertEquals("Bcu1", coupler.getManagementModel().getName());
		// The master data of the two sample files differ: the export of EHomeDemo (a newer ETS)
		// adds a seventh procedure (scope par,grp) to this mask. The mask is shared, so the file
		// imported first (the tests share one database) defines it; the common part is checked.
		List<LoadProcedure> procedures = new ArrayList<>(coupler.getProcedures());
		assertTrue(procedures.size() == 6 || procedures.size() == 7);
		List<String> scopes = procedures.stream().map(p -> p.getScope().getName()).toList();
		assertEquals(List.of("all", "par", "all", "par", "grp"), scopes.subList(0, 5));
		assertEquals("all", scopes.get(scopes.size() - 1));
		assertEquals("Unload", procedures.get(procedures.size() - 1).getKind().getName());
		assertEquals(List.of(12, 9, 15, 11, 7), procedures.subList(0, 5).stream().map(p -> p.getSteps().size()).toList());
		assertEquals(15, procedures.get(procedures.size() - 1).getSteps().size());
		for (LoadProcedure generic : procedures) {
			assertTrue(generic.getAccess().stream().map(TLClassifier::getName).toList().contains("remote"));
		}
		assertEquals(1, router.getSegments().size());
		assertNull(router.getSegments().iterator().next().getData());

		// Every segment of every program carries as many bytes as its size says, in data and mask;
		// the decoding of the shortest and of the address table segment is exact.
		int withData = 0;
		int withMask = 0;
		for (TLObject obj : instances("ehome.operational:CodeSegment")) {
			Segment segment = (Segment) obj;
			if (segment.getData() != null) {
				withData++;
				assertEquals(segment.getSize().intValue(), bytes(segment.getData()).length);
				// The actuator's segments have data without a mask: all bytes may be written.
				if (segment.getMask() != null) {
					withMask++;
					assertEquals(segment.getSize().intValue(), bytes(segment.getMask()).length);
				}
			}
		}
		assertTrue(withData >= 13);
		assertTrue(withMask >= 10 && withMask < withData);
		assertEquals("a50000", hex(codeSegment(sensor, 0x454E).getData()));
		assertTrue(hex(codeSegment(sensor, 0x4000).getData()).startsWith("790000ff"));
		assertEquals(sensor.getAddressTable().getSegment(), codeSegment(sensor, 0x4000));

		// The parameters point into segments the program has; the segment knows its pointers.
		for (Parameter parameter : sensor.getParameters()) {
			if (parameter.getAddress() instanceof Pointer pointer) {
				assertTrue(sensor.getSegments().contains(pointer.getSegment()));
				assertTrue(pointer.getSegment().getPointers().contains(pointer));
			}
		}
		assertTrue(sensor.getParameters().stream().anyMatch(p -> p.getAddress() instanceof PropertyLocation));

		ownership(mask);
	}

	/**
	 * Download data created from scratch, as the catalog editor will create it, is owned by the
	 * program: deleting the program takes the segments, the pointers, the tables and the procedures
	 * with it, but not the shared mask version.
	 */
	private void ownership(MaskVersion mask) throws Exception {
		KnowledgeBase kb = PersistencyLayer.getKnowledgeBase();
		EhomeOperationalFactory factory = EhomeOperationalFactory.getInstance();
		TLObject manufacturer;
		Program program;
		CodeSegment segment;
		Pointer pointer;
		PropertyLocation property;
		TableLocation table;
		LoadProcedure procedure;
		LoadStep step;
		int segmentsBefore = instances("ehome.operational:CodeSegment").size();
		int stepsBefore = instances("ehome.operational:LoadStep").size();
		try (Transaction tx = kb.beginTransaction()) {
			TLObject root = TLModelUtil.findType("ehome.catalog:ManufacturerRoot").getModule().getSingleton("ROOT");
			manufacturer = create("ehome.catalog:Manufacturer");
			manufacturer.tUpdateByName("name", "Probe");
			add(root, "manufacturers", manufacturer);
			program = factory.createProgram();
			program.tUpdateByName("name", ResKey.builder().add(Locale.ENGLISH, "Probe program").add(Locale.GERMAN, "Probeprogramm").build());
			program.setMask(mask);
			program.setLoadProcedureStyle(classifier("ehome.operational:LoadProcedureStyle", "ProductProcedure"));
			add(manufacturer, "programs", program);

			segment = factory.createCodeSegment();
			segment.setAddress(0x4000);
			segment.setSize(4);
			segment.setData(BinaryDataFactory.createBinaryData(new byte[] { 1, 2, 3, 4 }));
			segment.setMask(BinaryDataFactory.createBinaryData(new byte[] { -1, -1, -1, -1 }));
			program.addSegment(segment);

			table = factory.createTableLocation();
			table.setSegment(segment);
			table.setOffset(0);
			table.setMaxEntries(1);
			program.setAddressTable(table);

			VoidType type = factory.createVoidType();
			type.setName("Überschrift");
			program.addParameterType(type);

			Parameter memory = factory.createParameter();
			memory.setName("In memory");
			memory.setType(type);
			pointer = factory.createPointer();
			pointer.setSegment(segment);
			pointer.setByteOffset(2);
			pointer.setBitOffset(0);
			memory.setAddress(pointer);
			// The program owns the locations of its parameters (Program#locations).
			program.addLocation(pointer);
			program.addParameter(memory);

			Parameter stored = factory.createParameter();
			stored.setName("In property");
			stored.setType(type);
			property = factory.createPropertyLocation();
			property.setObjectIndex(4);
			property.setPropertyId(51);
			property.setByteOffset(0);
			property.setBitOffset(0);
			stored.setAddress(property);
			program.addLocation(property);
			program.addParameter(stored);

			procedure = factory.createLoadProcedure();
			procedure.setKind(classifier("ehome.operational:ProcedureKind", "Load"));
			step = factory.createLoadStep();
			step.setKind(classifier("ehome.operational:LoadStepKind", "AbsSegment"));
			step.setStateMachine(1);
			step.setAddress(0x4000L);
			step.setSize(4L);
			procedure.addStep(step);
			program.addLoadProcedure(procedure);
			tx.commit();
		}
		assertEquals("01020304", hex(segment.getData()));
		assertTrue(segment.getPointers().contains(pointer));
		assertEquals(segmentsBefore + 1, instances("ehome.operational:CodeSegment").size());
		assertEquals(stepsBefore + 1, instances("ehome.operational:LoadStep").size());

		try (Transaction tx = kb.beginTransaction()) {
			manufacturer.tDelete();
			tx.commit();
		}
		for (TLObject part : List.of(program, segment, pointer, property, table, procedure, step)) {
			assertFalse("Survives its program: " + part, part.tValid());
		}
		assertTrue(mask.tValid());
		assertEquals(segmentsBefore, instances("ehome.operational:CodeSegment").size());
		assertEquals(stepsBefore, instances("ehome.operational:LoadStep").size());
	}

	private static Program program(String extId) {
		List<TLObject> result = instances("ehome.operational:Program").stream()
			.filter(p -> extId.equals(p.tValueByName("extId")))
			.toList();
		assertEquals(extId, 1, result.size());
		return (Program) result.get(0);
	}

	private static List<CodeSegment> codeSegments(Program program) {
		return program.getSegments().stream()
			.filter(CodeSegment.class::isInstance)
			.map(CodeSegment.class::cast)
			.toList();
	}

	private static CodeSegment codeSegment(Program program, int address) {
		return codeSegments(program).stream()
			.filter(s -> s.getAddress() == address)
			.findFirst()
			.orElseThrow(() -> new AssertionError("No segment at " + address));
	}

	private static List<?> masks() {
		TLObject root = TLModelUtil.findType("ehome.mask:MaskVersions").getModule()
			.getSingleton(TLModule.DEFAULT_SINGLETON_NAME);
		return (List<?>) root.tValueByName("masks");
	}

	private static List<?> masks(String extId) {
		return masks().stream().filter(m -> extId.equals(((TLObject) m).tValueByName("extId"))).toList();
	}

	private static MaskResource resource(MaskVersion mask, String name) {
		return mask.getResources().stream()
			.filter(r -> name.equals(r.getName()))
			.findFirst()
			.orElseThrow(() -> new AssertionError("No resource " + name));
	}

	private static TLClassifier classifier(String enumName, String name) {
		return TLModelUtil.findPart(enumName + "#" + name) instanceof TLClassifier c ? c : null;
	}

	private static TLObject create(String typeName) {
		return ModelService.getInstance().getFactory().createObject((TLClass) TLModelUtil.findType(typeName));
	}

	private static void add(TLObject container, String reference, TLObject element) {
		List<Object> elements = new ArrayList<>((Collection<?>) container.tValueByName(reference));
		elements.add(element);
		container.tUpdateByName(reference, elements);
	}

	private static String hex(BinaryDataSource data) throws IOException {
		return HexFormat.of().formatHex(bytes(data));
	}

	private static byte[] bytes(BinaryDataSource data) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		data.deliverTo(out);
		return out.toByteArray();
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestDownloadDataProbe.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
