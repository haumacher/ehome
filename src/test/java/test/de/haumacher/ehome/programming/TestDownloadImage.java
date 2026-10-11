/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.programming;

import static test.de.haumacher.ehome.importer.TestEtsImport.importSample;
import static test.de.haumacher.ehome.importer.TestEtsImport.instances;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collection;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

import junit.framework.Test;
import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;

import com.top_logic.basic.Logger;
import com.top_logic.basic.io.binary.BinaryDataFactory;
import com.top_logic.knowledge.service.KnowledgeBase;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.model.TLClass;
import com.top_logic.model.TLClassifier;
import com.top_logic.model.TLObject;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.knx.GA;
import de.haumacher.ehome.knx.GroupAddressStyle;
import de.haumacher.ehome.model.operational.CodeSegment;
import de.haumacher.ehome.model.operational.EhomeOperationalFactory;
import de.haumacher.ehome.model.operational.EnumerationType;
import de.haumacher.ehome.model.operational.EnumerationValue;
import de.haumacher.ehome.model.operational.NumericType;
import de.haumacher.ehome.model.operational.TextType;
import de.haumacher.ehome.model.operational.TimeType;
import de.haumacher.ehome.model.operational.DateType;
import de.haumacher.ehome.model.operational.FloatingPointType;
import de.haumacher.ehome.model.operational.IPAddressType;
import de.haumacher.ehome.model.operational.Location;
import de.haumacher.ehome.model.operational.Parameter;
import de.haumacher.ehome.model.operational.ParameterType;
import de.haumacher.ehome.model.operational.Pointer;
import de.haumacher.ehome.model.operational.Port;
import de.haumacher.ehome.model.operational.Program;
import de.haumacher.ehome.programming.DeviceConfiguration;
import de.haumacher.ehome.programming.DownloadImage;
import de.haumacher.ehome.programming.DownloadImage.Block;
import de.haumacher.ehome.programming.ParameterEncoding;

/**
 * Test of {@link DownloadImage} (haumacher/ehome #6) against what ETS wrote into real devices.
 *
 * <p>
 * Two references exist for the devices of <code>samples/EHomeDemo.knxproj</code> (DI-2, the MDT
 * actuator AKK-0416.03 at 1.3.5, and DI-3, the Gira Tastsensor 3 at 1.3.50, both mask 7.5):
 * </p>
 * <ul>
 * <li>The <em>loaded image</em> ETS recorded in the project for the Tastsensor
 * ({@link EtsLoadedImage}): the six application segments ETS wrote from exactly this project,
 * parameters and object table. The image must equal it byte by byte.</li>
 * <li>The <em>read-back</em> of both devices from the user's test system
 * (<code>src/test/fixtures/readback/</code>, taken with {@link ReadDeviceMemory}). The devices
 * there run the same programs (application IDs 0083 000C 21 and 0008 10D7 11), but ETS last
 * programmed them from another project: the actuator holds 21 group addresses 0/0/34 to 0/0/54,
 * the Tastsensor 33 addresses 0/0/1 to 0/0/33, while EHomeDemo gives them three. The read-back is
 * therefore the reference for the table formats and placement and for every byte that does not
 * depend on the configuration, not for the parameter values: the test rebuilds the read-back's
 * wiring on the imported device (in a transaction that is rolled back) and compares the tables and
 * the actuator's object table exactly.</li>
 * </ul>
 */
@SuppressWarnings("javadoc")
public class TestDownloadImage extends BasicTestCase {

	private static final File READBACK = new File("src/test/fixtures/readback");

	private static final String ACTUATOR = "P-0353-0_DI-2";

	private static final String SENSOR = "P-0353-0_DI-3";

	private static final String TEST_DEVICE = "P-01A0-0_DI-1";

	private List<TLObject> _projects;

	public void testImages() throws Exception {
		// The tests share one database: both projects may be known already, both imports reuse them.
		List<TLObject> before = instances("ehome.project:Project");
		importSample("samples/Test.knxproj");
		importSample("samples/EHomeDemo.knxproj");
		_projects = new ArrayList<>(instances("ehome.project:Project"));
		_projects.removeAll(before);
		try {
			checkSensorAgainstLoadedImage();
			checkDemoTables();
			checkActuatorAgainstReadBack();
			checkSensorAgainstReadBack();
			checkTestProject();
			checkRouter();
		} finally {
			KnowledgeBase kb = PersistencyLayer.getKnowledgeBase();
			try (Transaction tx = kb.beginTransaction()) {
				for (TLObject project : _projects) {
					project.tDelete();
				}
				tx.commit();
			}
		}
	}

	/**
	 * Every kind of parameter type encodes the value its holder holds; built from scratch in a
	 * transaction that is rolled back.
	 */
	public void testEncodeByType() {
		KnowledgeBase kb = PersistencyLayer.getKnowledgeBase();
		EhomeOperationalFactory factory = EhomeOperationalFactory.getInstance();
		try (Transaction tx = kb.beginTransaction()) {
			Program program = factory.createProgram();
			program.setTextZeroTerminated(true);

			NumericType signed = factory.createNumericType();
			signed.setBits(8);
			signed.setMin(-128);
			signed.setKind(classifier("ehome.operational:NumberKind#signedInt"));
			assertEquals("fb", encode(signed, "number", Long.valueOf(-5), program));

			EnumerationType enumeration = factory.createEnumerationType();
			enumeration.setBits(3);
			for (int value : new int[] { 0, 2, 5 }) {
				EnumerationValue entry = factory.createEnumerationValue();
				entry.setValue(value);
				enumeration.addValue(entry);
			}
			assertEquals("05", encode(enumeration, "choice", enumeration.getValues().get(2), program));

			EnumerationType binary = factory.createEnumerationType();
			binary.setBits(16);
			binary.setKind(classifier("ehome.operational:ValueKind#BinaryValue"));
			EnumerationValue word = factory.createEnumerationValue();
			word.setValue(1);
			word.setBinary(BinaryDataFactory.createBinaryData(new byte[] { (byte) 0xBE, (byte) 0xEF }));
			binary.addValue(word);
			assertEquals("beef", encode(binary, "choice", word, program));

			FloatingPointType dpt9 = factory.createFloatingPointType();
			assertEquals("0c40", encode(dpt9, "decimal", Double.valueOf(21.76), program));
			FloatingPointType single = factory.createFloatingPointType();
			single.setEncoding(classifier("ehome.operational:FloatEncoding#IEEE754Single"));
			single.setMin(-1000);
			assertEquals("3fc00000", encode(single, "decimal", Double.valueOf(1.5), program));

			TextType text = factory.createTextType();
			text.setBits(32);
			assertEquals("41420000", encode(text, "text", "AB", program));
			program.setTextEncoding("windows-1252");
			assertEquals("80000000", encode(text, "text", "€", program));

			TimeType milliseconds = factory.createTimeType();
			milliseconds.setUnit(classifier("ehome.operational:TimeUnit#Milliseconds"));
			assertEquals("0bb8", encode(milliseconds, "number", Long.valueOf(3000), program));
			TimeType packed = factory.createTimeType();
			packed.setUnit(classifier("ehome.operational:TimeUnit#PackedSecondsAndMilliseconds"));
			try {
				encode(packed, "number", Long.valueOf(3000), program);
				fail("A packed time is not supported.");
			} catch (IllegalArgumentException ex) {
				// Expected: named in the problems of an image instead of guessed.
			}

			assertEquals("060712", encode(factory.createDateType(), "text", "2018-07-06", program));
			assertEquals("c0a8b251", encode(factory.createIPAddressType(), "text", "192.168.178.81", program));

			Parameter none = factory.createParameter();
			none.setType(factory.createVoidType());
			assertNull(ParameterEncoding.encode(none.getType(), none, program));
		}
	}

	private static String encode(ParameterType type, String attribute, Object value, Program program) {
		Parameter parameter = EhomeOperationalFactory.getInstance().createParameter();
		parameter.setType(type);
		parameter.tUpdateByName(attribute, value);
		return HexFormat.of().formatHex(ParameterEncoding.encode(type, parameter, program).toBytes());
	}

	private static TLClassifier classifier(String name) {
		return (TLClassifier) TLModelUtil.findPart(name);
	}

	/**
	 * The parameters in effect and the object table of the Tastsensor are exactly what ETS loaded
	 * from this project: the parameters of the cases taken (among them union members at offsets
	 * within their union and values of 1 to 16 bits at bit offsets), the static data elsewhere, the
	 * configuration bytes of 91 objects of which three are connected.
	 */
	private void checkSensorAgainstLoadedImage() throws IOException {
		DownloadImage image = DownloadImage.compute(device(SENSOR));
		assertEquals(image.dump(), List.of(), image.getProblems());
		Map<Integer, byte[]> loaded = EtsLoadedImage.records("samples/EHomeDemo.knxproj", "P-0353/0.xml", SENSOR);
		assertEquals(List.of(0x41E5, 0x435C, 0x4369, 0x436F, 0x4439, 0x444E), new ArrayList<>(loaded.keySet()));
		for (Map.Entry<Integer, byte[]> record : loaded.entrySet()) {
			assertBytes("ETS loaded image", record.getKey(), record.getValue(), image);
		}
		// The parameters ETS writes differ from the static data; the computation patched them in.
		Program program = DeviceConfiguration.program(device(SENSOR));
		byte[] staticData = ParameterEncoding.toBytes(segment(program, 0x444E).getData());
		assertEquals(0x01, staticData[0] & 0xFF);
		assertEquals(Integer.valueOf(0x81), image.byteAt(0x444E));

		Block objectTable = image.getObjectTable();
		assertEquals(0x41E5, objectTable.address());
		assertEquals(3 + 91 * 4, objectTable.data().length);
		// Object 0: transmit, write, communication, low priority (ETS: 0x57); object 1 without
		// association: communication cleared (0xDB instead of the static 0xDF).
		assertEquals(0x57, objectTable.data()[3 + 2] & 0xFF);
		assertEquals(0xDB, objectTable.data()[3 + 4 + 2] & 0xFF);
	}

	/**
	 * The tables of the EHomeDemo wiring, hand-computed: three group addresses 0/0/1 to 0/0/3, each
	 * sent to by one object.
	 */
	private void checkDemoTables() {
		DownloadImage actuator = DownloadImage.compute(device(ACTUATOR));
		assertEquals(actuator.dump(), List.of(), actuator.getProblems());
		assertBlock(actuator.getAddressTable(), 0x4000, "04 13 05 00 01 00 02 00 03");
		// O-0 -> 0/0/1, O-13 -> 0/0/2, O-25 -> 0/0/3; fixed place of the program.
		assertBlock(actuator.getAssociationTable(), 0x4201, "03 01 00 02 0D 03 19");

		DownloadImage sensor = DownloadImage.compute(device(SENSOR));
		assertBlock(sensor.getAddressTable(), 0x4000, "04 13 32 00 01 00 02 00 03");
		// The program's mask leaves the individual address to the device (written by programming it).
		assertEquals("ff0000ffffffffffff", HexFormat.of().formatHex(sensor.getAddressTable().mask()));
		// O-0 -> 0/0/1, O-2 -> 0/0/2, O-4 -> 0/0/3; dynamic table management: right after the
		// address table.
		assertBlock(sensor.getAssociationTable(), 0x4009, "03 01 00 02 02 03 04");
		assertTrue(sensor.dump(), sensor.dump().contains("4000: 04 -- -- 00 01 00 02 00 03\n"));
		assertTrue(sensor.dump(), sensor.dump().contains("4009: 03 01 00 02 02 03 04\n"));
		Logger.info("Download image of the Tastsensor:\n" + sensor.dump(), TestDownloadImage.class);
	}

	/**
	 * The actuator wired as the read-back shows: tables and object table equal the device's memory;
	 * the parameter segments equal it wherever no parameter lies.
	 */
	private void checkActuatorAgainstReadBack() throws IOException {
		KnowledgeBase kb = PersistencyLayer.getKnowledgeBase();
		TLObject device = device(ACTUATOR);
		try (Transaction tx = kb.beginTransaction()) {
			rewire(device, readBack("DI-2-1.3.5-4000.hex"), readBack("DI-2-1.3.5-4201.hex"));
			DownloadImage image = DownloadImage.compute(device);
			assertEquals(image.dump(), List.of(), image.getProblems());

			// The table pointers the device reports (property 7 of the address and association
			// table objects) are where the image places the tables.
			assertEquals("4000", property("DI-2-1.3.5", "1 7"));
			assertEquals("4201", property("DI-2-1.3.5", "2 7"));
			assertTable(image.getAddressTable(), readBack("DI-2-1.3.5-4000.hex"));
			assertTable(image.getAssociationTable(), readBack("DI-2-1.3.5-4201.hex"));

			// The whole object table: 58 objects, the configuration of 21 associated ones with
			// communication, the others without.
			Block objectTable = image.getObjectTable();
			assertEquals(3 + 58 * 4, objectTable.data().length);
			assertBytes("read-back", objectTable.address(), slice(readBack("DI-2-1.3.5-4400.hex"), objectTable), image);

			// The parameter segments: equal wherever no parameter of the program lies. The parameter
			// bytes hold the other project's configuration.
			BitSet parameterBytes = parameterBytes(DeviceConfiguration.program(device));
			for (String segment : List.of("DI-2-1.3.5-4400.hex", "DI-2-1.3.5-44EC.hex")) {
				compareOutsideParameters(image, readBack(segment), parameterBytes, objectTable);
			}
		}
	}

	/**
	 * The Tastsensor wired as the read-back shows: the tables equal the device's memory, the
	 * association table following the address table (dynamic table management), where the device's
	 * table pointer points (0x4045). The object table is not compared: its configuration bytes
	 * depend on the communication objects in effect, i.e. on the parameters of the other project.
	 */
	private void checkSensorAgainstReadBack() throws IOException {
		KnowledgeBase kb = PersistencyLayer.getKnowledgeBase();
		TLObject device = device(SENSOR);
		byte[][] memory = readBack("DI-3-1.3.50-4000.hex");
		// The association table lies in the memory read at 4000, after the address table.
		int addressTableLength = 1 + 2 * (memory[1][0] & 0xFF);
		int associationStart = 0x4000 + addressTableLength;
		byte[][] associations = { { (byte) (associationStart >>> 8), (byte) associationStart },
			slice(memory[1], addressTableLength, memory[1].length - addressTableLength) };
		try (Transaction tx = kb.beginTransaction()) {
			rewire(device, memory, associations);
			DownloadImage image = DownloadImage.compute(device);
			assertEquals(image.dump(), List.of(), image.getProblems());
			assertEquals("4000", property("DI-3-1.3.50", "1 7"));
			assertEquals("4045", property("DI-3-1.3.50", "2 7"));
			assertEquals(0x4045, image.getAssociationTable().address());
			assertTable(image.getAddressTable(), memory);
			assertTable(image.getAssociationTable(), associations);

			// The application segments equal the device's memory wherever no parameter lies and
			// outside the object table.
			BitSet parameterBytes = parameterBytes(DeviceConfiguration.program(device));
			for (String segment : List.of("DI-3-1.3.50-435C.hex", "DI-3-1.3.50-4369.hex", "DI-3-1.3.50-436F.hex",
				"DI-3-1.3.50-4439.hex", "DI-3-1.3.50-444E.hex", "DI-3-1.3.50-454E.hex")) {
				compareOutsideParameters(image, readBack(segment), parameterBytes, image.getObjectTable());
			}
		}
	}

	/**
	 * The device of <code>samples/Test.knxproj</code> (a Tastsensor 3 at 1.0.1): its address table
	 * holds exactly its group addresses in ascending order, the association table one entry per
	 * connection, the parameter memory differs from the static data only at parameters in effect.
	 */
	private void checkTestProject() {
		TLObject device = device(TEST_DEVICE);
		DownloadImage image = DownloadImage.compute(device);
		assertEquals(image.dump(), List.of(), image.getProblems());
		List<Integer> addresses = new ArrayList<>();
		int connections = 0;
		for (Object connector : (Collection<?>) device.tValueByName("connectors")) {
			for (Object connection : (Collection<?>) ((TLObject) connector).tValueByName("connections")) {
				addresses.add(((GA) ((TLObject) ((TLObject) connection).tValueByName("target")).tValueByName("address")).getValue());
				connections++;
			}
		}
		List<Integer> expected = addresses.stream().distinct().sorted().toList();
		byte[] table = image.getAddressTable().data();
		assertEquals(expected.size() + 1, table[0] & 0xFF);
		assertEquals(0x1001, u16(table, 1));
		for (int n = 0; n < expected.size(); n++) {
			assertEquals(expected.get(n).intValue(), u16(table, 3 + 2 * n));
		}
		assertEquals(connections, image.getAssociationTable().data()[0] & 0xFF);
		assertTrue(connections > 0);

		Program program = DeviceConfiguration.program(device);
		BitSet active = new BitSet();
		DeviceConfiguration configuration = new DeviceConfiguration(device);
		for (Parameter parameter : configuration.getActiveViews().keySet()) {
			mark(active, parameter);
		}
		for (Block block : image.getSegments()) {
			CodeSegment segment = segment(program, block.address());
			byte[] data = ParameterEncoding.toBytes(segment.getData());
			for (int n = 0; n < data.length; n++) {
				int address = block.address() + n;
				boolean inObjectTable = image.getObjectTable().contains(address);
				if (data[n] != block.data()[n] && !inObjectTable) {
					assertTrue(String.format("Changed byte %04X is no parameter in effect.", address), active.get(address));
				}
			}
		}
	}

	/**
	 * The IP router has no group tables, and its parameters lie in a segment without static data:
	 * only their bits are written. Hand-computed: the routing multicast address 224.0.23.12 at
	 * 0143. Not compared with the device: its read-back failed (no confirmation from 1.3.0).
	 */
	private void checkRouter() {
		DownloadImage image = DownloadImage.compute(device("P-0353-0_DI-1"));
		assertEquals(image.dump(), List.of(), image.getProblems());
		assertNull(image.getAddressTable());
		assertNull(image.getObjectTable());
		assertEquals(1, image.getBlocks().size());
		Block block = image.getBlocks().get(0);
		assertEquals(0x0100, block.address());
		assertEquals("e000170c", HexFormat.of().formatHex(block.data(), 0x43, 0x47));
		assertEquals("ffffffff", HexFormat.of().formatHex(block.mask(), 0x43, 0x47));
		// No parameter at 0100: not written. The device name (a text of 30 bytes from 0124) is padded
		// with zero bytes.
		assertEquals(0, block.mask()[0]);
		assertTrue(image.dump(), image.dump().contains("0120: 00 80 -- -- 4B 4E 58 20 49 50 20 52 6F 75 74 65\n"));
		assertTrue(image.dump(), image.dump().contains("0140: 00 00 04 E0 00 17 0C --"));
	}

	/**
	 * Replaces the connections of a device by the wiring an address and an association table
	 * describe, in random order, so that the computation has to find the order of the tables.
	 */
	private static void rewire(TLObject device, byte[][] addressTable, byte[][] associationTable) {
		byte[] addresses = addressTable[1];
		byte[] associations = associationTable[1];
		Program program = DeviceConfiguration.program(device);
		List<int[]> links = new ArrayList<>();
		for (int n = 0; n < (associations[0] & 0xFF); n++) {
			int index = associations[1 + 2 * n] & 0xFF;
			int object = associations[2 + 2 * n] & 0xFF;
			links.add(new int[] { u16(addresses, 1 + 2 * index), object });
		}
		Collections.shuffle(links, new Random(42));
		List<TLObject> connectors = new ArrayList<>();
		for (int[] link : links) {
			Port port = program.getPorts().stream().filter(p -> p.getIndex() == link[1]).findFirst().orElseThrow();
			TLObject connector = create("ehome.physical:Connector");
			connector.tUpdateByName("port", port.getInterfaces().iterator().next());
			connector.tUpdateByName("active", Boolean.TRUE);
			TLObject target = create("ehome.logical:GroupAddress");
			target.tUpdateByName("address", new GA(link[0], GroupAddressStyle.ThreeLevel));
			TLObject connection = create("ehome.physical:Connection");
			connection.tUpdateByName("kind", TLModelUtil.findPart("ehome.physical:ConnectionType#send"));
			connection.tUpdateByName("target", target);
			connector.tUpdateByName("connections", List.of(connection));
			connectors.add(connector);
		}
		device.tUpdateByName("connectors", connectors);
	}

	private static TLObject create(String type) {
		return ModelService.getInstance().getFactory().createObject((TLClass) TLModelUtil.findType(type));
	}

	/**
	 * Compares the image with a read-back at every byte that is no parameter (of any placement or
	 * case) and outside the given object table; logs how many parameter bytes differ.
	 */
	private static void compareOutsideParameters(DownloadImage image, byte[][] readBack, BitSet parameterBytes, Block objectTable) {
		int start = u16(readBack[0], 0);
		byte[] memory = readBack[1];
		int differentParameters = 0;
		for (int n = 0; n < memory.length; n++) {
			int address = start + n;
			if (objectTable != null && objectTable.contains(address)) {
				continue;
			}
			Integer computed = image.byteAt(address);
			assertNotNull(String.format("No byte at %04X", address), computed);
			if (computed.intValue() != (memory[n] & 0xFF)) {
				if (parameterBytes.get(address)) {
					differentParameters++;
				} else {
					fail(String.format("Image and device differ at %04X outside the parameters: image %02X, device %02X.",
						address, computed, memory[n] & 0xFF));
				}
			}
		}
		Logger.info(String.format("Segment %04X: %d parameter bytes differ from the device (configured by another project).",
			start, differentParameters), TestDownloadImage.class);
	}

	private static BitSet parameterBytes(Program program) {
		BitSet result = new BitSet();
		for (Parameter parameter : program.getParameters()) {
			mark(result, parameter);
		}
		return result;
	}

	private static void mark(BitSet bytes, Parameter parameter) {
		Location location = parameter.getAddress();
		if (!(location instanceof Pointer pointer) || !(pointer.getSegment() instanceof CodeSegment segment)) {
			return;
		}
		int bits = location.getBits() != null ? location.getBits() : typeBits(parameter);
		int startBit = (segment.getAddress() + location.getByteOffset()) * 8 + location.getBitOffset()
			+ value(parameter.getUnionOffset()) * 8 + value(parameter.getUnionBitOffset());
		for (int bit = startBit; bit < startBit + Math.max(bits, 1); bit++) {
			bytes.set(bit / 8);
		}
	}

	private static int typeBits(Parameter parameter) {
		ParameterType type = parameter.getType();
		if (type instanceof FloatingPointType floating) {
			String encoding = floating.getEncoding() == null ? "DPT9" : floating.getEncoding().getName();
			return encoding.equals("IEEE754Double") ? 64 : encoding.equals("IEEE754Single") ? 32 : 16;
		}
		if (type instanceof DateType) {
			return 24;
		}
		if (type instanceof IPAddressType) {
			return 128;
		}
		Object bits = type.tType().getPart("bits") == null ? null : type.tValueByName("bits");
		return bits instanceof Integer value ? value : 0;
	}

	private static int value(Integer value) {
		return value == null ? 0 : value;
	}

	private static void assertBlock(Block block, int address, String hex) {
		assertNotNull(block);
		assertEquals(String.format("%04X", address), String.format("%04X", block.address()));
		assertEquals(hex.replace(" ", "").toLowerCase(), HexFormat.of().formatHex(block.data()));
	}

	/**
	 * The block equals the read-back in its length, compared within its mask.
	 */
	private static void assertTable(Block table, byte[][] readBack) {
		assertEquals(String.format("%04X", u16(readBack[0], 0)), String.format("%04X", table.address()));
		for (int n = 0; n < table.data().length; n++) {
			int mask = table.mask()[n] & 0xFF;
			int device = readBack[1][n] & 0xFF;
			int computed = table.data()[n] & 0xFF;
			assertEquals(String.format("%s at %04X: image %02X, device %02X", table.name(), table.address() + n, computed, device),
				device & mask, computed & mask);
		}
	}

	/**
	 * Compares the image with reference bytes at the given address; reports the first difference.
	 */
	private static void assertBytes(String reference, int address, byte[] expected, DownloadImage image) {
		for (int n = 0; n < expected.length; n++) {
			int at = address + n;
			Integer computed = image.byteAt(at);
			assertNotNull(String.format("The image writes nothing at %04X.", at), computed);
			if (computed.intValue() != (expected[n] & 0xFF)) {
				fail(String.format("Image and %s differ at %04X: image %02X, %s %02X.", reference, at, computed, reference,
					expected[n] & 0xFF));
			}
		}
	}

	private static byte[] slice(byte[][] readBack, Block block) {
		return slice(readBack[1], block.address() - u16(readBack[0], 0), block.data().length);
	}

	private static byte[] slice(byte[] data, int start, int length) {
		byte[] result = new byte[length];
		System.arraycopy(data, start, result, 0, length);
		return result;
	}

	private static int u16(byte[] data, int position) {
		return (data[position] & 0xFF) << 8 | (data[position + 1] & 0xFF);
	}

	/**
	 * A read-back fixture: the start address (2 bytes) and the memory.
	 */
	static byte[][] readBack(String file) throws IOException {
		TreeMap<Integer, byte[]> lines = new TreeMap<>();
		for (String line : Files.readAllLines(new File(READBACK, file).toPath(), StandardCharsets.UTF_8)) {
			if (line.startsWith("#") || line.isBlank()) {
				continue;
			}
			int colon = line.indexOf(':');
			lines.put(Integer.parseInt(line.substring(0, colon), 16),
				HexFormat.of().parseHex(line.substring(colon + 1).replace(" ", "")));
		}
		int start = lines.firstKey();
		int length = lines.lastKey() - start + lines.lastEntry().getValue().length;
		byte[] memory = new byte[length];
		lines.forEach((address, bytes) -> System.arraycopy(bytes, 0, memory, address - start, bytes.length));
		return new byte[][] { { (byte) (start >>> 8), (byte) start }, memory };
	}

	private static String property(String device, String key) throws IOException {
		for (String line : Files.readAllLines(new File(READBACK, device + "-properties.txt").toPath(), StandardCharsets.UTF_8)) {
			if (line.startsWith(key + ": ")) {
				return line.substring(key.length() + 2);
			}
		}
		return null;
	}

	private TLObject device(String extId) {
		List<TLObject> result = instances("ehome.physical:Device").stream()
			.filter(d -> extId.equals(d.tValueByName("extId")))
			.filter(d -> _projects.contains(root(d)))
			.toList();
		assertEquals(extId, 1, result.size());
		return result.get(0);
	}

	private static TLObject root(TLObject obj) {
		TLObject result = obj;
		while (result.tContainer() != null) {
			result = result.tContainer();
		}
		return result;
	}

	private static CodeSegment segment(Program program, int address) {
		return program.getSegments().stream()
			.filter(s -> s instanceof CodeSegment code && code.getAddress() == address)
			.map(CodeSegment.class::cast)
			.findFirst()
			.orElseThrow(() -> new AssertionError("No segment at " + address));
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestDownloadImage.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
