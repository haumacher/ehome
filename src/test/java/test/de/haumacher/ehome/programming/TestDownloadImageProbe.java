/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.programming;

import static test.de.haumacher.ehome.importer.TestEtsImport.importSample;
import static test.de.haumacher.ehome.importer.TestEtsImport.instances;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import junit.framework.Test;
import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;

import com.top_logic.basic.io.binary.BinaryDataFactory;
import com.top_logic.basic.util.ResKey;
import com.top_logic.knowledge.service.KnowledgeBase;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.model.TLClass;
import com.top_logic.model.TLObject;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.knx.ProgramStructure;
import de.haumacher.ehome.knx.ProgramStructure.Position;
import de.haumacher.ehome.model.operational.ChannelIndependent;
import de.haumacher.ehome.model.operational.CodeSegment;
import de.haumacher.ehome.model.operational.EhomeOperationalFactory;
import de.haumacher.ehome.model.operational.EnumerationType;
import de.haumacher.ehome.model.operational.EnumerationValue;
import de.haumacher.ehome.model.operational.NumericType;
import de.haumacher.ehome.model.operational.Parameter;
import de.haumacher.ehome.model.operational.ParameterView;
import de.haumacher.ehome.model.operational.Pointer;
import de.haumacher.ehome.model.operational.Program;
import de.haumacher.ehome.model.operational.TableLocation;
import de.haumacher.ehome.programming.DeviceConfiguration;
import de.haumacher.ehome.programming.DownloadImage;
import de.haumacher.ehome.programming.DownloadImage.Block;

/**
 * Probe of the download image (issue #6) composed with the rest of the application: the image is a
 * pure function of the project data; a setting edited in the project as the device view does it
 * changes exactly the bits of its parameter and nothing else, and removing it restores the image;
 * a device of a program built from scratch (as the catalog editor builds it) has an image too.
 */
@SuppressWarnings("javadoc")
public class TestDownloadImageProbe extends BasicTestCase {

	public void testProbe() throws Exception {
		List<TLObject> before = instances("ehome.project:Project");
		importSample("samples/Test.knxproj");
		List<TLObject> projects = new ArrayList<>(instances("ehome.project:Project"));
		projects.removeAll(before);
		assertEquals(1, projects.size());
		TLObject project = projects.get(0);
		List<TLObject> created = new ArrayList<>();
		try {
			settingEdit(project);
			fromScratch(project, created);
		} finally {
			KnowledgeBase kb = PersistencyLayer.getKnowledgeBase();
			try (Transaction tx = kb.beginTransaction()) {
				for (TLObject obj : created) {
					if (obj.tValid()) {
						obj.tDelete();
					}
				}
				project.tDelete();
				tx.commit();
			}
		}
	}

	/**
	 * A setting created in the project the way the device view creates it: the image changes at the
	 * parameter's bits only, and the ETS encoding of the setting is what the import would have read.
	 */
	private void settingEdit(TLObject project) throws Exception {
		TLObject device = device(project);
		DownloadImage original = DownloadImage.compute(device);
		assertEquals(original.getProblems().toString(), 0, original.getProblems().size());
		assertTrue(original.dump().contains("4000"));
		// A pure function of the data: a second computation is byte for byte the same.
		assertSameExcept(original, DownloadImage.compute(device), -1);

		// An enumeration parameter in effect, stored in memory in one byte or less, with an entry
		// other than the current value.
		DeviceConfiguration configuration = new DeviceConfiguration(device);
		ParameterView view = null;
		Pointer pointer = null;
		EnumerationValue other = null;
		for (Map.Entry<Parameter, ParameterView> active : configuration.getActiveViews().entrySet()) {
			Parameter parameter = active.getKey();
			// A member of a union sits at its own offset within the union's location.
			if (!(parameter.getType() instanceof EnumerationType type)
				|| !(parameter.getAddress() instanceof Pointer location)
				|| type.getBits() > 8
				|| (parameter.getUnionOffset() != null && parameter.getUnionOffset() != 0)
				|| location.getBitOffset() + memberBitOffset(parameter) + type.getBits() > 8) {
				continue;
			}
			String current = configuration.value(active.getValue());
			for (EnumerationValue entry : type.getValues()) {
				if (!String.valueOf(entry.getValue()).equals(current) && entry.getValue() < (1 << type.getBits())) {
					view = active.getValue();
					pointer = location;
					other = entry;
					break;
				}
			}
			if (view != null) {
				break;
			}
		}
		assertNotNull("No enumeration parameter in effect to edit.", view);

		int address = ((CodeSegment) pointer.getSegment()).getAddress() + pointer.getByteOffset();
		int bits = ((EnumerationType) view.getParameter().getType()).getBits();
		int shift = 8 - pointer.getBitOffset() - memberBitOffset(view.getParameter()) - bits;
		int fieldMask = ((1 << bits) - 1) << shift;
		Integer before = original.byteAt(address);
		assertNotNull("Parameter byte not in the image: " + Integer.toHexString(address), before);

		KnowledgeBase kb = PersistencyLayer.getKnowledgeBase();
		TLObject setting;
		try (Transaction tx = kb.beginTransaction()) {
			setting = create("ehome.physical:ParameterSetting");
			setting.tUpdateByName("parameter", view);
			setting.tUpdateByName("choice", other);
			add(device, "settings", setting);
			tx.commit();
		}
		try {
			assertEquals(String.valueOf(other.getValue()),
				test.de.haumacher.ehome.importer.TestEtsImport.derived(setting, "encodedValue"));
			DownloadImage edited = DownloadImage.compute(device);
			int after = edited.byteAt(address);
			assertEquals("Field of " + view.getParameter().getName() + " at " + Integer.toHexString(address),
				other.getValue(), (after & fieldMask) >> shift);
			assertEquals("Bits beside the field changed", before & ~fieldMask, after & ~fieldMask);
			assertSameExcept(original, edited, address);
		} finally {
			try (Transaction tx = kb.beginTransaction()) {
				setting.tDelete();
				tx.commit();
			}
		}
		assertSameExcept(original, DownloadImage.compute(device), -1);
	}

	/**
	 * A device of a program built from scratch: one segment of four bytes, a numeric parameter of 8
	 * bits placed in the structure, no objects.
	 */
	private void fromScratch(TLObject project, List<TLObject> created) throws Exception {
		KnowledgeBase kb = PersistencyLayer.getKnowledgeBase();
		EhomeOperationalFactory factory = EhomeOperationalFactory.getInstance();
		TLObject device;
		Parameter parameter;
		try (Transaction tx = kb.beginTransaction()) {
			TLObject root = TLModelUtil.findType("ehome.catalog:ManufacturerRoot").getModule().getSingleton("ROOT");
			TLObject manufacturer = create("ehome.catalog:Manufacturer");
			manufacturer.tUpdateByName("name", "Probe");
			add(root, "manufacturers", manufacturer);
			created.add(manufacturer);

			Program program = factory.createProgram();
			program.tUpdateByName("name", text("Probe program", "Probeprogramm"));
			program.setMask(((Program) instances("ehome.operational:Program").get(0)).getMask());
			add(manufacturer, "programs", program);

			CodeSegment segment = factory.createCodeSegment();
			segment.setAddress(0x4000);
			segment.setSize(4);
			segment.setData(BinaryDataFactory.createBinaryData(new byte[] { 1, 2, 3, 4 }));
			segment.setMask(BinaryDataFactory.createBinaryData(new byte[] { -1, -1, -1, -1 }));
			program.addSegment(segment);
			// The tables have segments of their own, as every program has.
			program.setAddressTable(table(factory, program, 0x4100, 3, 1, null));
			program.setAssociationTable(table(factory, program, 0x4200, 1, 0, null));
			// The object table descriptors are static data of the program: none here, no objects.
			program.setObjectTable(table(factory, program, 0x4300, 3, 0, new byte[] { 0, 0, 0 }));

			NumericType type = factory.createNumericType();
			type.setName("Byte");
			type.setMin(0L);
			type.setMax(255L);
			type.setBits(8);
			program.addParameterType(type);

			parameter = factory.createParameter();
			parameter.setName("Wert");
			parameter.setType(type);
			parameter.setNumber(0x7FL);
			Pointer pointer = factory.createPointer();
			pointer.setSegment(segment);
			pointer.setByteOffset(2);
			pointer.setBitOffset(0);
			pointer.setBits(8);
			parameter.setAddress(pointer);
			program.addLocation(pointer);
			program.addParameter(parameter);

			TLObject product = create("ehome.catalog:Product");
			product.tUpdateByName("name", "Probe product");
			add(manufacturer, "products", product);
			TLObject variant = create("ehome.catalog:Variant");
			variant.tUpdateByName("name", text("Probe variant", "Probevariante"));
			variant.tUpdateByName("orderNumber", "PROBE-1");
			add(product, "variants", variant);
			TLObject assignment = create("ehome.catalog:ProgramAssignment");
			assignment.tUpdateByName("program", program);
			add(product, "programs", assignment);

			TLObject line = (TLObject) ((Collection<?>) ((TLObject) ((Collection<?>) project.tValueByName("areas"))
				.iterator().next()).tValueByName("lines")).iterator().next();
			device = create("ehome.physical:Device");
			device.tUpdateByName("address", 200);
			device.tUpdateByName("type", variant);
			device.tUpdateByName("program", assignment);
			add(line, "devices", device);
			tx.commit();
		}
		// The parameter is placed in the structure as the editor does it (own transaction each).
		ChannelIndependent block = factory.createChannelIndependent();
		Program program = DeviceConfiguration.program(device);
		try (Transaction tx = kb.beginTransaction()) {
			ProgramStructure.insert(block, program, Position.INSIDE);
			tx.commit();
		}
		ParameterView view = factory.createParameterView();
		view.setParameter(parameter);
		try (Transaction tx = kb.beginTransaction()) {
			ProgramStructure.insert(view, block, Position.INSIDE);
			tx.commit();
		}

		DownloadImage image = DownloadImage.compute(device);
		assertEquals(image.getProblems().toString(), 0, image.getProblems().size());
		Block segment = image.getSegments().stream().filter(b -> b.address() == 0x4000).findFirst().orElseThrow();
		assertEquals(Arrays.toString(segment.data()), 0x7F, segment.data()[2] & 0xFF);
		assertEquals(1, segment.data()[0]);
		assertEquals(4, segment.data()[3]);
		// The own address of a device in line 1 area 1 of the Test project (P-01A0: area 1, line 1).
		Block addresses = image.getAddressTable();
		assertNotNull(addresses);
		assertEquals(1, addresses.data()[0]);
		int individual = ((addresses.data()[1] & 0xFF) << 8) | (addresses.data()[2] & 0xFF);
		assertEquals(200, individual & 0xFF);
		assertTrue(image.getPropertyWrites().isEmpty());
	}

	private static TableLocation table(EhomeOperationalFactory factory, Program program, int address, int size,
			int maxEntries, byte[] data) {
		CodeSegment segment = factory.createCodeSegment();
		segment.setAddress(address);
		segment.setSize(size);
		if (data != null) {
			segment.setData(BinaryDataFactory.createBinaryData(data));
		}
		program.addSegment(segment);
		TableLocation result = factory.createTableLocation();
		result.setSegment(segment);
		result.setOffset(0);
		result.setMaxEntries(maxEntries);
		return result;
	}

	private static int memberBitOffset(Parameter parameter) {
		return parameter.getUnionBitOffset() == null ? 0 : parameter.getUnionBitOffset();
	}

	private static void assertSameExcept(DownloadImage expected, DownloadImage actual, int exceptAddress) {
		List<Block> expectedBlocks = expected.getBlocks();
		List<Block> actualBlocks = actual.getBlocks();
		assertEquals(expectedBlocks.size(), actualBlocks.size());
		for (int n = 0; n < expectedBlocks.size(); n++) {
			Block a = expectedBlocks.get(n);
			Block b = actualBlocks.get(n);
			assertEquals(a.name(), b.name());
			assertEquals(a.address(), b.address());
			assertEquals(a.data().length, b.data().length);
			assertTrue(Arrays.equals(a.mask(), b.mask()));
			for (int i = 0; i < a.data().length; i++) {
				if (a.address() + i == exceptAddress) {
					continue;
				}
				assertEquals(a.name() + " at " + Integer.toHexString(a.address() + i), a.data()[i], b.data()[i]);
			}
		}
	}

	private static TLObject device(TLObject project) {
		List<TLObject> devices = new ArrayList<>();
		for (Object area : (Collection<?>) project.tValueByName("areas")) {
			for (Object line : (Collection<?>) ((TLObject) area).tValueByName("lines")) {
				for (Object device : (Collection<?>) ((TLObject) line).tValueByName("devices")) {
					devices.add((TLObject) device);
				}
			}
		}
		assertEquals(1, devices.size());
		return devices.get(0);
	}

	private static ResKey text(String en, String de) {
		return ResKey.builder().add(Locale.ENGLISH, en).add(Locale.GERMAN, de).build();
	}

	private static TLObject create(String typeName) {
		return ModelService.getInstance().getFactory().createObject((TLClass) TLModelUtil.findType(typeName));
	}

	private static void add(TLObject container, String reference, TLObject element) {
		List<Object> elements = new ArrayList<>((Collection<?>) container.tValueByName(reference));
		elements.add(element);
		container.tUpdateByName(reference, elements);
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestDownloadImageProbe.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
