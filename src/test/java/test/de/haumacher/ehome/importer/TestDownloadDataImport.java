/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.importer;

import static test.de.haumacher.ehome.importer.TestEtsImport.importSample;
import static test.de.haumacher.ehome.importer.TestEtsImport.instances;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import junit.framework.Test;
import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;

import com.top_logic.basic.io.binary.BinaryDataSource;
import com.top_logic.knowledge.service.KnowledgeBase;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.model.TLClassifier;
import com.top_logic.model.TLModule;
import com.top_logic.model.TLObject;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.knx.ProgramStructure;
import de.haumacher.ehome.model.operational.CodeSegment;
import de.haumacher.ehome.model.operational.EhomeOperationalFactory;
import de.haumacher.ehome.model.operational.LoadProcedure;
import de.haumacher.ehome.model.operational.LoadStep;
import de.haumacher.ehome.model.operational.Location;
import de.haumacher.ehome.model.operational.MaskResource;
import de.haumacher.ehome.model.operational.MaskVersion;
import de.haumacher.ehome.model.operational.Parameter;
import de.haumacher.ehome.model.operational.Pointer;
import de.haumacher.ehome.model.operational.Program;
import de.haumacher.ehome.model.operational.PropertyLocation;
import de.haumacher.ehome.model.operational.RelativeSegment;
import de.haumacher.ehome.model.operational.ResourceLocation;
import de.haumacher.ehome.model.operational.TableLocation;
import de.haumacher.ehome.model.operational.VoidType;

/**
 * The download data of an application program (issue #5): memory segments with their content, table
 * locations, load procedures, parameter locations, options, and the mask version shared as master
 * data.
 *
 * <p>
 * {@link #testTastsensor()} imports <code>samples/Test.knxproj</code> on its own (twice) and removes
 * its projects again, because the tests share one database. The checks of the fixture
 * <code>TestDynamic.knxproj</code> ({@link #checkFixture()}) and of <code>EHomeDemo.knxproj</code>
 * ({@link #checkDemo()}) run within {@link TestEtsImport}, which imports these projects, so that the
 * catalog data of the shared database stays what that test expects.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestDownloadDataImport extends BasicTestCase {

	private static final String TASTSENSOR = "M-0008_A-10D7-11-A0E5-O000A";

	public void testTastsensor() throws Exception {
		KnowledgeBase kb = PersistencyLayer.getKnowledgeBase();
		List<TLObject> projectsBefore = new ArrayList<>(instances("ehome.project:Project"));
		try {
			importSample("samples/Test.knxproj");
			checkTastsensor();

			// A second import reuses the mask version and skips the known program with its content.
			importSample("samples/Test.knxproj");
			checkTastsensor();
		} finally {
			try (Transaction tx = kb.beginTransaction()) {
				for (TLObject project : instances("ehome.project:Project")) {
					if (!projectsBefore.contains(project)) {
						project.tDelete();
					}
				}
				tx.commit();
			}
		}
	}

	private static void checkTastsensor() throws IOException {
		Program program = program(TASTSENSOR);

		// Segments: address, size, and the static content of the size given.
		List<CodeSegment> segments = codeSegments(program);
		assertEquals(List.of(0x0700, 0x4000, 0x40F3, 0x41E5, 0x435C, 0x4369, 0x436F, 0x4439, 0x444E, 0x454E),
			segments.stream().map(CodeSegment::getAddress).toList());
		assertEquals(List.of(250, 243, 241, 375, 13, 6, 202, 21, 256, 3),
			segments.stream().map(CodeSegment::getSize).toList());
		for (CodeSegment segment : segments) {
			assertEquals(segment.getExtId(), Boolean.FALSE, segment.tValueByName("userMemory"));
			if (segment.getData() != null) {
				assertEquals(segment.getExtId(), segment.getSize().intValue(), bytes(segment.getData()).length);
				assertEquals(segment.getExtId(), segment.getSize().intValue(), bytes(segment.getMask()).length);
			}
		}
		CodeSegment reserved = segment(program, 0x0700);
		assertNull("Segment without data.", reserved.getData());
		assertNull(reserved.getMask());
		CodeSegment small = segment(program, 0x4369);
		assertEquals("000000000185", hex(small.getData()));
		assertEquals("ffffffffffff", hex(small.getMask()));
		assertEquals(TASTSENSOR + "_AS-4369", small.getExtId());

		// Table locations.
		TableLocation addresses = program.getAddressTable();
		assertSame(segment(program, 0x4000), addresses.getSegment());
		assertEquals(Integer.valueOf(0), addresses.getOffset());
		assertEquals(Integer.valueOf(120), addresses.getMaxEntries());
		TableLocation associations = program.getAssociationTable();
		assertSame(segment(program, 0x40F3), associations.getSegment());
		assertEquals(Integer.valueOf(0), associations.getOffset());
		assertEquals(Integer.valueOf(120), associations.getMaxEntries());
		TableLocation objects = program.getObjectTable();
		assertSame(segment(program, 0x41E5), objects.getSegment());
		assertEquals(Integer.valueOf(0), objects.getOffset());
		assertNull(objects.getMaxEntries());

		// Program header and options.
		assertEquals(Integer.valueOf(0x10D7), program.getApplicationNumber());
		assertEquals(Integer.valueOf(0x11), program.getApplicationVersion());
		assertEquals(Integer.valueOf(0), program.getPeiType());
		assertEquals("ProductProcedure", program.getLoadProcedureStyle().getName());
		assertEquals("BigEndian", program.getParameterByteOrder().getName());
		assertEquals("iso-8859-1", program.getTextEncoding());
		assertEquals("UseTextParameterEncodingCodePage", program.getTextEncodingSource().getName());
		assertEquals(Boolean.TRUE, program.tValueByName("preferPartialDownload"));
		assertEquals(Boolean.FALSE, program.tValueByName("textZeroTerminated"));
		assertEquals(Boolean.FALSE, program.tValueByName("objectTableLengthAlwaysOne"));
		assertEquals(Boolean.FALSE, program.tValueByName("noPartialDownload"));

		// The mask version is the shared master data object.
		MaskVersion mask = program.getMask();
		assertNotNull(mask);
		assertEquals(1, masks("MV-0705").size());
		assertSame(mask, masks("MV-0705").get(0));
		assertEquals(1797, mask.getNumber());
		assertEquals("7.5", mask.getName());
		assertEquals("BimM112", mask.getManagementModel().getName());
		assertEquals("TP", mask.getMedium().getName());
		assertTrue(maskRoot().contains(mask));
		assertEquals(29, maskRoot().size());
		MaskResource applicationId = resource(mask, "ApplicationId");
		ResourceLocation location = applicationId.getLocation();
		assertEquals("SystemProperty", location.getAddressSpace().getName());
		assertEquals(Integer.valueOf(3), location.getInterfaceObject());
		assertEquals(Integer.valueOf(13), location.getPropertyId());
		assertEquals(Long.valueOf(5), applicationId.getLength());
		assertEquals(Set.of("remote"), names(applicationId.getAccess()));
		assertEquals("Runtime", applicationId.getReadRights().getName());
		assertEquals("Configuration", applicationId.getWriteRights().getName());
		// Where the device of this mask keeps its group address table.
		MaskResource table = resource(mask, "GroupAddressTable");
		assertEquals("StandardMemory", table.getLocation().getAddressSpace().getName());
		assertEquals(Long.valueOf(0x4000), table.getLocation().getStartAddress());
		assertEquals("AddressTable_Bcu1", table.getFlavour());
		assertEquals("Pointer", resource(mask, "GroupAssociationTable").getLocation().getAddressSpace().getName());
		assertEquals("GroupAssociationTablePtr",
			resource(mask, "GroupAssociationTable").getLocation().getPointerResource());
		assertEquals(List.of("FirstAppObjectIdx=5", "AuthorizeLevels=16", "PropertyMappedLsms=1", "VerifyMode=1",
			"DownloadStamp=1"),
			mask.getFeatures().stream().map(f -> f.getName() + "=" + f.tValueByName("value")).toList());
		assertEquals(3, mask.getMemory().size());
		assertEquals(Long.valueOf(8192), mask.getMemory().get(0).getLength());
		assertEquals("EEPROM", mask.getMemory().get(0).getMemoryType().getName());
		// The generic procedure of the mask: unload everything.
		assertEquals(1, mask.getProcedures().size());
		LoadProcedure unload = mask.getProcedures().get(0);
		assertEquals("Unload", unload.getKind().getName());
		assertEquals("all", unload.getScope().getName());
		assertEquals(List.of("Connect", "Unload", "Unload", "Unload", "Unload", "Disconnect"), kinds(unload));
		assertEquals(Integer.valueOf(4), unload.getSteps().get(4).getStateMachine());
		// The masks whose programs run on a coupler 091A.
		MaskVersion router = (MaskVersion) masks("MV-091A").get(0);
		assertEquals(List.of(masks("MV-0910").get(0), masks("MV-0911").get(0), masks("MV-0912").get(0)),
			router.getCompatibleMasks());

		// The load procedure of the program, in the order of the file.
		assertEquals(1, program.getLoadProcedures().size());
		LoadProcedure procedure = program.getLoadProcedures().get(0);
		assertEquals(List.of("Connect", "Unload", "Unload", "Unload", "Load", "AbsSegment", "TaskSegment",
			"LoadCompleted", "Load", "AbsSegment", "TaskSegment", "LoadCompleted", "Load", "AbsSegment",
			"AbsSegment", "AbsSegment", "AbsSegment", "AbsSegment", "AbsSegment", "AbsSegment", "AbsSegment",
			"TaskSegment", "TaskCtrl1", "LoadCompleted", "LoadImageProp", "LoadImageProp", "LoadImageProp", "Restart",
			"TaskSegment", "Disconnect"), kinds(procedure));
		List<? extends LoadStep> steps = procedure.getSteps();
		assertEquals("auto", steps.get(0).getAppliesTo().getName());
		checkAbsSegment(steps.get(5), 1, 16384, 243, 242, 3, 128);
		checkAbsSegment(steps.get(9), 2, 16627, 241, 242, 3, 128);
		checkAbsSegment(steps.get(13), 3, 1792, 250, 242, 2, 0);
		LoadStep image = steps.get(26);
		assertEquals(Integer.valueOf(3), image.getObjectIndex());
		assertEquals(Integer.valueOf(27), image.getPropertyId());
		assertEquals(Integer.valueOf(6), image.getCount());
		assertEquals(Integer.valueOf(1), image.getStartElement());
		assertEquals(Integer.valueOf(0), image.getOccurrence());
		assertNull(image.getStateMachine());
		LoadStep task = steps.get(22);
		assertEquals("TaskCtrl1", task.getKind().getName());
		assertEquals(Long.valueOf(0), task.getAddress());
		assertEquals(Integer.valueOf(0), task.getCount());

		// A parameter stored in a property of an interface object, and one in memory.
		Parameter hardware = parameter(program, TASTSENSOR + "_P-3720");
		PropertyLocation property = (PropertyLocation) hardware.getAddress();
		assertEquals(Integer.valueOf(0), property.getObjectIndex());
		assertEquals(78, property.getPropertyId());
		assertEquals(0, property.getByteOffset());
		assertEquals(0, property.getBitOffset());
		assertEquals(Integer.valueOf(0), property.getOccurrence());
		Parameter memory = parameter(program, TASTSENSOR + "_P-989");
		Pointer pointer = (Pointer) memory.getAddress();
		assertSame(segment(program, 0x444E), pointer.getSegment());
		assertEquals(81, pointer.getByteOffset());
		assertTrue(segment(program, 0x444E).getPointers().contains(pointer));

		// The program owns the locations of its parameters.
		assertSame(program, pointer.tContainer());
		assertSame(program, property.tContainer());
		assertTrue(program.getLocations().contains(pointer));
		for (Parameter parameter : program.getParameters()) {
			if (parameter.getAddress() != null) {
				assertSame(parameter.getExtId(), program, parameter.getAddress().tContainer());
			}
		}
	}

	/**
	 * Deleting a parameter deletes its location with it, unless another parameter of its union still
	 * uses it; deleting the program deletes the rest of its download data, but not its mask version.
	 */
	public void testOwnership() throws Exception {
		KnowledgeBase kb = PersistencyLayer.getKnowledgeBase();
		EhomeOperationalFactory factory = EhomeOperationalFactory.getInstance();
		Program program;
		Parameter first;
		Parameter second;
		Parameter alone;
		PropertyLocation shared;
		Pointer pointer;
		CodeSegment segment;
		try (Transaction tx = kb.beginTransaction()) {
			program = factory.createProgram();
			VoidType type = factory.createVoidType();
			type.setName("Test");
			program.addParameterType(type);
			segment = factory.createCodeSegment();
			segment.setAddress(0x4000);
			segment.setSize(2);
			program.addSegment(segment);

			shared = factory.createPropertyLocation();
			shared.setPropertyId(51);
			shared.setByteOffset(0);
			shared.setBitOffset(0);
			program.addLocation(shared);
			first = parameter(factory, program, type, "First", shared);
			second = parameter(factory, program, type, "Second", shared);

			pointer = factory.createPointer();
			pointer.setSegment(segment);
			pointer.setByteOffset(1);
			pointer.setBitOffset(0);
			program.addLocation(pointer);
			alone = parameter(factory, program, type, "Alone", pointer);
			tx.commit();
		}
		assertEquals(Set.of(pointer), Set.copyOf(segment.getPointers()));

		try (Transaction tx = kb.beginTransaction()) {
			ProgramStructure.delete(first);
			ProgramStructure.delete(alone);
			tx.commit();
		}
		assertTrue("Location still used by the union.", shared.tValid());
		assertSame(shared, second.getAddress());
		assertFalse("Location left behind by its last parameter.", pointer.tValid());
		assertTrue(segment.getPointers().isEmpty());

		try (Transaction tx = kb.beginTransaction()) {
			ProgramStructure.delete(second);
			tx.commit();
		}
		assertFalse(shared.tValid());

		try (Transaction tx = kb.beginTransaction()) {
			program.tDelete();
			tx.commit();
		}
		assertFalse(segment.tValid());
	}

	private static Parameter parameter(EhomeOperationalFactory factory, Program program, VoidType type, String name,
			Location location) {
		Parameter result = factory.createParameter();
		result.setName(name);
		result.setType(type);
		result.setAddress(location);
		program.addParameter(result);
		return result;
	}

	private static void checkAbsSegment(LoadStep step, int stateMachine, long address, long size, int access,
			int memoryType, int flags) {
		assertEquals("AbsSegment", step.getKind().getName());
		assertEquals(Integer.valueOf(stateMachine), step.getStateMachine());
		assertEquals(Integer.valueOf(0), step.getSegmentType());
		assertEquals(Long.valueOf(address), step.getAddress());
		assertEquals(Long.valueOf(size), step.getSize());
		assertEquals(Integer.valueOf(access), step.getSegmentAccess());
		assertEquals(Integer.valueOf(memoryType), step.getMemoryType());
		assertEquals(Integer.valueOf(flags), step.getSegmentFlags());
		assertEquals(Integer.valueOf(0), step.getOccurrence());
		assertEquals("auto", step.getAppliesTo().getName());
	}

	/**
	 * Checks the download data of the fixture <code>TestDynamic.knxproj</code>: a relative segment
	 * with data, a parameter stored in it, and a union stored in a property.
	 *
	 * <p>
	 * Called by {@link TestEtsImport} after it imported the fixture.
	 * </p>
	 */
	public static void checkFixture() throws IOException {
		String prefix = "M-0008_A-10D7-11-A0E5-O000D_";
		Program program = program(prefix.substring(0, prefix.length() - 1));
		List<RelativeSegment> relative = program.getSegments().stream()
			.filter(RelativeSegment.class::isInstance)
			.map(RelativeSegment.class::cast)
			.toList();
		assertEquals(1, relative.size());
		RelativeSegment segment = relative.get(0);
		assertEquals(prefix + "RS-04-00000", segment.getExtId());
		assertEquals("Test relative", segment.getName());
		assertEquals(Integer.valueOf(0), segment.getOffset());
		assertEquals(Integer.valueOf(4), segment.getSize());
		assertEquals(Integer.valueOf(4), segment.getStateMachine());
		assertEquals("01020304", hex(segment.getData()));
		assertEquals("ffffffff", hex(segment.getMask()));
		// The absolute segments of the copied program are there as well.
		assertEquals(10, codeSegments(program).size());

		Parameter stored = parameter(program, prefix + "P-9007");
		Pointer pointer = (Pointer) stored.getAddress();
		assertSame(segment, pointer.getSegment());
		assertEquals(2, pointer.getByteOffset());
		assertEquals(Set.of(pointer), Set.copyOf(segment.getPointers()));

		// The parameters of a union share its location, a property here.
		Parameter first = parameter(program, prefix + "UP-9008");
		Parameter second = parameter(program, prefix + "UP-9009");
		PropertyLocation shared = (PropertyLocation) first.getAddress();
		assertSame(shared, second.getAddress());
		assertEquals(Integer.valueOf(4), shared.getObjectIndex());
		assertEquals(51, shared.getPropertyId());
		assertEquals(1, shared.getByteOffset());
		assertEquals(Integer.valueOf(8), shared.getBits());
		assertEquals(Set.of(first, second), Set.copyOf(shared.getUsage()));

		// The memory union of the copied program still shares one pointer.
		long unionPointers = program.getParameters().stream()
			.filter(p -> p.getAddress() instanceof Pointer && ((Pointer) p.getAddress()).getBits() != null)
			.map(Parameter::getAddress)
			.distinct()
			.count();
		assertTrue(unionPointers > 0);
	}

	/**
	 * Checks the download data of the programs of <code>EHomeDemo.knxproj</code>: the IP router uses
	 * the procedures of its mask version, the actuator brings its own.
	 *
	 * <p>
	 * Called by {@link TestEtsImport} after it imported the project.
	 * </p>
	 */
	public static void checkDemo() throws IOException {
		Program router = program("M-006A_A-0703-10-FD20-O00C5");
		assertSame(masks("MV-091A").get(0), router.getMask());
		assertEquals("DefaultProcedure", router.getLoadProcedureStyle().getName());
		assertTrue(router.getLoadProcedures().isEmpty());
		assertNull(router.getAddressTable());
		List<CodeSegment> routerSegments = codeSegments(router);
		assertEquals(1, routerSegments.size());
		assertEquals(Integer.valueOf(256), routerSegments.get(0).getAddress());
		assertNull(routerSegments.get(0).getData());
		assertFalse("The mask version has procedures of its own.", router.getMask().getProcedures().isEmpty());

		Program actuator = program("M-0083_A-000C-21-83B5");
		assertSame(masks("MV-0705").get(0), actuator.getMask());
		assertEquals(1, masks("MV-0705").size());
		assertEquals(22, actuator.getLoadProcedures().get(0).getSteps().size());
		assertEquals(Integer.valueOf(255), actuator.getAddressTable().getMaxEntries());
		assertEquals(6, codeSegments(actuator).size());
		for (CodeSegment segment : codeSegments(actuator)) {
			if (segment.getData() != null) {
				assertEquals(segment.getSize().intValue(), bytes(segment.getData()).length);
			}
		}
		assertEquals(29, maskRoot().size());
	}

	private static Program program(String extId) {
		List<TLObject> result = instances("ehome.operational:Program").stream()
			.filter(p -> extId.equals(p.tValueByName("extId")))
			.toList();
		assertEquals(1, result.size());
		return (Program) result.get(0);
	}

	private static List<CodeSegment> codeSegments(Program program) {
		return program.getSegments().stream()
			.filter(CodeSegment.class::isInstance)
			.map(CodeSegment.class::cast)
			.sorted(Comparator.comparing(CodeSegment::getAddress))
			.toList();
	}

	private static CodeSegment segment(Program program, int address) {
		return codeSegments(program).stream().filter(s -> s.getAddress() == address).findFirst().orElseThrow();
	}

	private static Parameter parameter(Program program, String extId) {
		return program.getParameters().stream()
			.filter(p -> extId.equals(p.getExtId()))
			.findFirst()
			.orElseThrow(() -> new AssertionError("No parameter " + extId));
	}

	private static List<TLObject> masks(String extId) {
		return instances("ehome.operational:MaskVersion").stream()
			.filter(m -> extId.equals(m.tValueByName("extId")))
			.toList();
	}

	private static List<?> maskRoot() {
		TLObject root = TLModelUtil.findType("ehome.mask:MaskVersions").getModule()
			.getSingleton(TLModule.DEFAULT_SINGLETON_NAME);
		return (List<?>) root.tValueByName("masks");
	}

	private static MaskResource resource(MaskVersion mask, String name) {
		return mask.getResources().stream()
			.filter(r -> name.equals(r.getName()))
			.findFirst()
			.orElseThrow(() -> new AssertionError("No resource " + name));
	}

	private static List<String> kinds(LoadProcedure procedure) {
		return procedure.getSteps().stream().map(s -> s.getKind().getName()).toList();
	}

	private static Set<String> names(Set<TLClassifier> classifiers) {
		return classifiers.stream().map(TLClassifier::getName).collect(Collectors.toSet());
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
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestDownloadDataImport.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
