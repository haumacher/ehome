/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.programming;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.top_logic.basic.util.ResKey2;
import com.top_logic.model.TLClassifier;
import com.top_logic.model.TLObject;

import de.haumacher.ehome.knx.GA;
import de.haumacher.ehome.model.operational.CodeSegment;
import de.haumacher.ehome.model.operational.Interface;
import de.haumacher.ehome.model.operational.Location;
import de.haumacher.ehome.model.operational.MaskResource;
import de.haumacher.ehome.model.operational.MaskVersion;
import de.haumacher.ehome.model.operational.Parameter;
import de.haumacher.ehome.model.operational.ParameterView;
import de.haumacher.ehome.model.operational.Pointer;
import de.haumacher.ehome.model.operational.Port;
import de.haumacher.ehome.model.operational.Program;
import de.haumacher.ehome.model.operational.PropertyLocation;
import de.haumacher.ehome.model.operational.RelativeSegment;
import de.haumacher.ehome.model.operational.ResourceLocation;
import de.haumacher.ehome.model.operational.Segment;
import de.haumacher.ehome.model.operational.TableLocation;
import de.haumacher.ehome.modules.ModuleParts;
import de.haumacher.ehome.programming.DownloadImage.Block;
import de.haumacher.ehome.programming.GroupTables.Association;

/**
 * Computes the {@link DownloadImage} of a device.
 *
 * <p>
 * The rules, as the memory ETS loaded into the devices of <code>samples/EHomeDemo.knxproj</code>
 * confirmed them:
 * </p>
 * <ul>
 * <li>A segment with static data is written as that data with the values of the parameters in
 * effect patched in ({@link DeviceConfiguration}); a parameter that is not in effect (in a case not
 * taken, a union member not shown) is not written, its memory keeps the static data.</li>
 * <li>A segment without static data holding parameters (the IP router's) is written only at the
 * bits of the parameters in effect (its mask); not verified, the router could not be read
 * back.</li>
 * <li>The segments of the address and association tables are written as the tables, not as their
 * static data (a template of the manufacturer).</li>
 * <li>With dynamic table management the association table follows the address table directly
 * (the device's table pointer then points there).</li>
 * <li>The object table is the static data with the configuration bytes computed from the flags of
 * the communication objects; the communication flag only for objects with an association. An object
 * not in effect of a program with the ETS 6 option <code>DownloadInvisibleParameters</code> keeps
 * the configuration byte of the static data, without the communication flag.</li>
 * </ul>
 *
 * <p>
 * A segment holding a table together with other content (BCU 1: the EEPROM 0100-01FF holds the
 * system header, the tables, the parameters and the application) is written as one block: its
 * static data (or, without static data, only the bits of the parameters) with the parameters, the
 * tables and the object table patched in, and the pointers to the tables the mask keeps in that
 * memory (<code>GroupAssociationTablePtr</code>, <code>GroupObjectTablePtr</code>) set to the
 * tables' places (see {@link GroupTables}). There the tables stay at the places the program gives
 * them, also with dynamic table management (the pointers in the static data name these places).
 * </p>
 */
class DownloadImageBuilder {

	private static final String ADDRESS_TABLE_FORMAT = "AddressTable_Bcu1";

	/** The formats of the association table with the bytes of {@link GroupTables#associationTable(List)}. */
	private static final List<String> ASSOCIATION_TABLE_FORMATS = List.of("AssociationTable_M112", "AssociationTable_Bcu1");

	/** The flavour of a mask's pointer to a table: one byte in memory, the table's address minus 0100. */
	private static final String POINTER_100 = "Ptr_StandardMemory100";

	private final TLObject _device;

	private final DeviceConfiguration _configuration;

	private final Program _program;

	private final DownloadImage _image;

	private final Map<Segment, Block> _blocks = new HashMap<>();

	private final Map<List<Integer>, byte[]> _properties = new LinkedHashMap<>();

	private final Set<Integer> _associatedObjects = new HashSet<>();

	/** Segments without static data: their mask holds the bits of the parameters written. */
	private final Set<Segment> _withoutData = new HashSet<>();

	/**
	 * Segments holding a table together with other content (BCU 1): the tables are written into
	 * their blocks.
	 */
	private final Set<Segment> _combined = new HashSet<>();

	DownloadImageBuilder(TLObject device) {
		this(new DeviceConfiguration(device));
	}

	DownloadImageBuilder(DeviceConfiguration configuration) {
		_device = configuration.getDevice();
		_configuration = configuration;
		_program = _configuration.getProgram();
		_image = new DownloadImage(describe(_device));
	}

	DownloadImage build() {
		if (_program == null) {
			_image.addProblem(de.haumacher.ehome.programming.download.I18NConstants.ERROR_NO_PROGRAM);
			return _image;
		}
		segments();
		parameters();
		tables();
		objectTable();
		checkOverlaps();
		for (Map.Entry<List<Integer>, byte[]> entry : _properties.entrySet()) {
			List<Integer> key = entry.getKey();
			_image.addPropertyWrite(new DownloadImage.PropertyWrite(key.get(0), key.get(1), key.get(2), key.get(3).intValue(),
				entry.getValue()));
		}
		return _image;
	}

	private void segments() {
		Set<Segment> tableSegments = new HashSet<>();
		for (TableLocation table : Arrays.asList(_program.getAddressTable(), _program.getAssociationTable())) {
			if (table != null && table.getSegment() != null) {
				tableSegments.add(table.getSegment());
			}
		}
		Segment objectSegment = _program.getObjectTable() == null ? null : _program.getObjectTable().getSegment();
		List<Segment> segments = new ArrayList<>(_program.getSegments());
		segments.sort(Comparator.comparingInt(DownloadImageBuilder::sortKey));
		for (Segment segment : segments) {
			boolean combined = false;
			if (tableSegments.contains(segment)) {
				if (segment != objectSegment && segment.getPointers().isEmpty()) {
					// The template of a table.
					continue;
				}
				// The tables share the memory with other content (BCU 1).
				combined = true;
			}
			byte[] data = ParameterEncoding.toBytes(segment.getData());
			byte[] mask = ParameterEncoding.toBytes(segment.getMask());
			if (data == null) {
				if (!combined && segment.getPointers().isEmpty() || segment.getSize() == null) {
					// Reserved memory only.
					continue;
				}
				// Memory without static data holding parameters: only the parameters' bits are written.
				data = new byte[segment.getSize()];
				mask = new byte[data.length];
				_withoutData.add(segment);
			} else if (mask == null || mask.length != data.length) {
				mask = new byte[data.length];
				Arrays.fill(mask, (byte) 0xFF);
			}
			Block block;
			if (segment instanceof RelativeSegment relative) {
				block = new Block(I18NConstants.BLOCK_RELATIVE_SEGMENT__SEGMENT.fill(name(segment)), relative.getOffset() == null ? 0 : relative.getOffset(),
					relative.getStateMachine(), data, mask);
			} else {
				block = new Block(I18NConstants.BLOCK_SEGMENT__SEGMENT.fill(name(segment)), ((CodeSegment) segment).getAddress(), null, data, mask);
			}
			_blocks.put(segment, block);
			if (combined) {
				_combined.add(segment);
			}
			_image.addSegment(block);
		}
	}

	private static int sortKey(Segment segment) {
		if (segment instanceof CodeSegment code && code.getAddress() != null) {
			return code.getAddress();
		}
		return Integer.MAX_VALUE;
	}

	private static String name(Segment segment) {
		String name = segment.getName();
		if (name != null && !name.isEmpty()) {
			return name;
		}
		return segment.getExtId() != null ? segment.getExtId() : "";
	}

	private void parameters() {
		boolean littleEndian = _program.getParameterByteOrder() != null
			&& "LittleEndian".equals(_program.getParameterByteOrder().getName());
		for (Map.Entry<Parameter, ParameterView> entry : _configuration.getActiveViews().entrySet()) {
			Parameter parameter = entry.getKey();
			Location location = parameter.getAddress();
			if (location == null) {
				continue;
			}
			ParameterEncoding.Bits bits;
			try {
				bits = _configuration.encode(entry.getValue());
			} catch (IllegalArgumentException ex) {
				_image.addProblem(I18NConstants.IMAGE_PARAMETER__PARAMETER_REASON.fill(label(parameter), ValueException.reason(ex)));
				continue;
			}
			if (bits == null) {
				continue;
			}
			int byteOffset = location.getByteOffset() + value(parameter.getUnionOffset());
			int bitOffset = location.getBitOffset() + value(parameter.getUnionBitOffset());
			try {
				if (location instanceof Pointer pointer) {
					Block block = _blocks.get(pointer.getSegment());
					if (block == null) {
						_image.addProblem(I18NConstants.IMAGE_PARAMETER_SEGMENT__PARAMETER_SEGMENT.fill(label(parameter),
							name(pointer.getSegment())));
						continue;
					}
					ParameterEncoding.write(block.data(), byteOffset, bitOffset, bits, littleEndian);
					if (_withoutData.contains(pointer.getSegment())) {
						ParameterEncoding.write(block.mask(), byteOffset, bitOffset, ones(bits.size()), false);
					}
				} else if (location instanceof PropertyLocation property) {
					List<Integer> key = Arrays.asList(property.getObjectIndex(), property.getObjectType(),
						property.getOccurrence(), Integer.valueOf(property.getPropertyId()));
					int length = (byteOffset * 8 + bitOffset + bits.size() + 7) / 8;
					byte[] value = _properties.getOrDefault(key, new byte[0]);
					if (value.length < length) {
						value = Arrays.copyOf(value, length);
					}
					ParameterEncoding.write(value, byteOffset, bitOffset, bits, littleEndian);
					_properties.put(key, value);
				}
			} catch (IllegalArgumentException ex) {
				_image.addProblem(I18NConstants.IMAGE_PARAMETER__PARAMETER_REASON.fill(label(parameter), ValueException.reason(ex)));
			}
		}
	}

	private static ParameterEncoding.Bits ones(int size) {
		return new ParameterEncoding.Bits(size, BigInteger.ONE.shiftLeft(size).subtract(BigInteger.ONE));
	}

	private static int value(Integer value) {
		return value == null ? 0 : value.intValue();
	}

	private static String label(Parameter parameter) {
		return parameter.getName() + " (" + parameter.getExtId() + ")";
	}

	private void tables() {
		TableLocation addressLocation = _program.getAddressTable();
		TableLocation associationLocation = _program.getAssociationTable();

		// The group addresses and associations of the device's connections.
		List<int[]> links = new ArrayList<>();
		for (TLObject connector : _configuration.getConnectors()) {
			Interface placed = ModuleParts.port(connector);
			Port port = placed == null ? null : placed.getPort();
			if (port == null) {
				continue;
			}
			for (Object connection : (Collection<?>) connector.tValueByName("connections")) {
				TLObject link = (TLObject) connection;
				TLObject target = (TLObject) link.tValueByName("target");
				if (target == null || !(target.tValueByName("address") instanceof GA address)) {
					continue;
				}
				TLObject kind = (TLObject) link.tValueByName("kind");
				boolean send = kind instanceof TLClassifier classifier && "send".equals(classifier.getName());
				links.add(new int[] { address.getValue(), port.getIndex(), send ? 1 : 0 });
			}
		}
		if (addressLocation == null && associationLocation == null) {
			if (!links.isEmpty()) {
				_image.addProblem(I18NConstants.IMAGE_NO_ADDRESS_TABLE);
			}
			return;
		}
		if (!supported("GroupAddressTable", List.of(ADDRESS_TABLE_FORMAT))
			|| !supported("GroupAssociationTable", ASSOCIATION_TABLE_FORMATS)) {
			return;
		}

		List<Integer> addresses = GroupTables.addressOrder(links.stream().map(l -> l[0]).toList());
		List<Association> associations = new ArrayList<>();
		for (int[] link : links) {
			associations.add(new Association(addresses.indexOf(link[0]) + 1, link[1], link[2] == 1));
		}
		associations = GroupTables.associationOrder(associations);
		for (Association association : associations) {
			_associatedObjects.add(association.objectNumber());
		}
		checkSize(I18NConstants.IMAGE_TOO_MANY_ADDRESSES__COUNT_MAX, addresses.size(), addressLocation);
		checkSize(I18NConstants.IMAGE_TOO_MANY_ASSOCIATIONS__COUNT_MAX, associations.size(), associationLocation);

		Integer individualAddress = individualAddress(_device);
		if (individualAddress == null) {
			_image.addProblem(de.haumacher.ehome.programming.download.I18NConstants.ERROR_NO_ADDRESS);
			return;
		}
		byte[] addressTable = GroupTables.addressTable(individualAddress.intValue(), addresses);
		Integer addressStart = start(addressLocation, "GroupAddressTable");
		if (addressStart == null) {
			_image.addProblem(I18NConstants.IMAGE_ADDRESS_TABLE_PLACE);
			return;
		}
		byte[] addressMask = mask(addressLocation, addressTable.length);
		boolean addressCombined = combined(addressLocation);
		if (addressCombined) {
			fixedPlace("GroupAddressTable", addressStart);
			excludeIndividualAddress(addressStart, addressMask);
		}
		Block addressBlock = new Block(I18NConstants.BLOCK_ADDRESS_TABLE, addressStart, null, addressTable, addressMask);
		_image.setAddressTable(addressBlock);
		if (addressCombined) {
			patch(addressLocation.getSegment(), addressBlock);
		}

		byte[] associationTable = GroupTables.associationTable(associations);
		int associationStart;
		byte[] associationMask;
		boolean associationCombined = combined(associationLocation);
		if (_program.getDynamicTableManagement() && !associationCombined) {
			// ETS places the association table right after the address table.
			associationStart = addressStart + addressTable.length;
			associationMask = full(associationTable.length);
		} else {
			Integer start = start(associationLocation, null);
			if (start == null) {
				_image.addProblem(I18NConstants.IMAGE_ASSOCIATION_TABLE_PLACE);
				return;
			}
			associationStart = start;
			associationMask = mask(associationLocation, associationTable.length);
		}
		Block associationBlock = new Block(I18NConstants.BLOCK_ASSOCIATION_TABLE, associationStart, null, associationTable, associationMask);
		_image.setAssociationTable(associationBlock);
		if (associationCombined) {
			pointer("GroupAssociationTablePtr", associationStart);
			patch(associationLocation.getSegment(), associationBlock);
		}
	}

	/**
	 * Whether the table lies in a segment shared with other content (BCU 1), whose block holds it.
	 */
	private boolean combined(TableLocation location) {
		return location != null && location.getSegment() != null && _combined.contains(location.getSegment());
	}

	/**
	 * Writes a table into the block of the segment holding it: its bytes under its mask.
	 */
	private void patch(Segment segment, Block table) {
		Block block = _blocks.get(segment);
		int offset = table.address() - block.address();
		if (block.stateMachine() != null || offset < 0 || offset + table.data().length > block.data().length) {
			_image.addProblem(I18NConstants.IMAGE_TABLE_EXCEEDS_SEGMENT__TABLE_FROM_TO_SEGMENT.fill(table.label(),
				hex4(table.address()), hex4(table.end() - 1), name(segment)));
			return;
		}
		for (int n = 0; n < table.data().length; n++) {
			if (table.mask()[n] != 0) {
				block.data()[offset + n] = table.data()[n];
				block.mask()[offset + n] = table.mask()[n];
			}
		}
	}

	/**
	 * Checks that a table lies at the fixed place the mask gives it (a resource in standard memory),
	 * if the mask gives one.
	 */
	private void fixedPlace(String resourceName, int address) {
		ResourceLocation location = memoryResource(resourceName);
		if (location != null && location.getStartAddress().intValue() != address) {
			_image.addProblem(I18NConstants.IMAGE_FIXED_PLACE__RESOURCE_ADDRESS_MASK_EXPECTED.fill(resourceName, hex4(address),
				_program.getMask().getName(), hex4(location.getStartAddress().intValue())));
		}
	}

	/**
	 * Takes the individual address (the mask's resource <code>IndividualAddress</code> in memory) out
	 * of the mask of the address table: only the programming of the individual address writes it.
	 */
	private void excludeIndividualAddress(int tableAddress, byte[] mask) {
		MaskResource resource = memoryResourceOf("IndividualAddress");
		if (resource == null) {
			return;
		}
		int start = resource.getLocation().getStartAddress().intValue();
		int length = resource.getLength() == null ? 2 : resource.getLength().intValue();
		for (int address = start; address < start + length; address++) {
			if (address >= tableAddress && address < tableAddress + mask.length) {
				mask[address - tableAddress] = 0;
			}
		}
	}

	/**
	 * Sets a pointer the mask keeps in memory (flavour {@value #POINTER_100}: one byte, the address
	 * minus 0100) to the place of its table, in the block holding it. Static data giving another
	 * place is a problem.
	 */
	private void pointer(String resourceName, int tableAddress) {
		MaskResource resource = memoryResourceOf(resourceName);
		if (resource == null || !POINTER_100.equals(resource.getFlavour())) {
			_image.addProblem(I18NConstants.IMAGE_NO_POINTER__MASK_RESOURCE_FORMAT.fill(_program.getMask().getName(), resourceName,
				POINTER_100));
			return;
		}
		int address = resource.getLocation().getStartAddress().intValue();
		int value = tableAddress - 0x100;
		if (value < 0 || value > 0xFF) {
			_image.addProblem(I18NConstants.IMAGE_POINTER_RANGE__TABLE_RESOURCE_ADDRESS.fill(hex4(tableAddress), resourceName,
				hex4(address)));
			return;
		}
		Block block = null;
		for (Block candidate : _blocks.values()) {
			if (candidate.stateMachine() == null && candidate.contains(address)) {
				block = candidate;
			}
		}
		if (block == null) {
			_image.addProblem(I18NConstants.IMAGE_POINTER_OUTSIDE__RESOURCE_ADDRESS.fill(resourceName, hex4(address)));
			return;
		}
		int index = address - block.address();
		int mask = block.mask()[index] & 0xFF;
		if (mask == 0) {
			block.data()[index] = (byte) value;
			block.mask()[index] = (byte) 0xFF;
		} else if (mask != 0xFF || (block.data()[index] & 0xFF) != value) {
			_image.addProblem(I18NConstants.IMAGE_POINTER_STATIC__VALUE_MASK_RESOURCE_ADDRESS_TABLE.fill(
				String.format("%02X", block.data()[index] & 0xFF), String.format("%02X", mask), resourceName, hex4(address),
				hex4(tableAddress)));
		}
	}

	private ResourceLocation memoryResource(String name) {
		MaskResource resource = memoryResourceOf(name);
		return resource == null ? null : resource.getLocation();
	}

	/**
	 * The mask's resource of the given name located in standard memory, <code>null</code> if there is
	 * none.
	 */
	private MaskResource memoryResourceOf(String name) {
		if (_program.getMask() == null) {
			return null;
		}
		for (MaskResource resource : _program.getMask().getResources()) {
			ResourceLocation location = resource.getLocation();
			if (name.equals(resource.getName()) && location != null && location.getAddressSpace() != null
				&& "StandardMemory".equals(location.getAddressSpace().getName()) && location.getStartAddress() != null) {
				return resource;
			}
		}
		return null;
	}

	private void checkSize(ResKey2 tooMany, int size, TableLocation location) {
		if (location != null && location.getMaxEntries() != null && size > location.getMaxEntries()) {
			_image.addProblem(tooMany.fill(size, location.getMaxEntries()));
		}
	}

	private static String hex4(int value) {
		return String.format("%04X", value);
	}

	private boolean supported(String resource, List<String> supported) {
		return format(resource, supported) != null;
	}

	/**
	 * The format of the mask's resource that is one of the supported ones; <code>null</code> (and a
	 * problem) if there is none.
	 */
	private String format(String resource, List<String> supported) {
		MaskVersion mask = _program.getMask();
		if (mask == null) {
			_image.addProblem(I18NConstants.IMAGE_NO_MASK);
			return null;
		}
		List<String> formats = new ArrayList<>();
		for (MaskResource candidate : mask.getResources()) {
			if (resource.equals(candidate.getName())) {
				if (supported.contains(candidate.getFlavour())) {
					return candidate.getFlavour();
				}
				formats.add(candidate.getFlavour());
			}
		}
		_image.addProblem(I18NConstants.IMAGE_FORMAT__RESOURCE_MASK_FORMATS_SUPPORTED.fill(resource, mask.getName(),
			String.join(", ", formats), String.join(", ", supported)));
		return null;
	}

	private Integer start(TableLocation location, String maskResource) {
		if (location != null && location.getSegment() instanceof CodeSegment segment && segment.getAddress() != null) {
			return segment.getAddress() + value(location.getOffset());
		}
		if (location != null && location.getSegment() instanceof RelativeSegment) {
			_image.addProblem(I18NConstants.IMAGE_RELATIVE_TABLE);
			return null;
		}
		if (maskResource != null && _program.getMask() != null) {
			for (MaskResource resource : _program.getMask().getResources()) {
				if (maskResource.equals(resource.getName()) && resource.getLocation() != null
					&& resource.getLocation().getAddressSpace() != null
					&& "StandardMemory".equals(resource.getLocation().getAddressSpace().getName())
					&& resource.getLocation().getStartAddress() != null) {
					return resource.getLocation().getStartAddress().intValue();
				}
			}
		}
		return null;
	}

	private static byte[] mask(TableLocation location, int length) {
		byte[] result = full(length);
		byte[] segmentMask = location == null || location.getSegment() == null ? null
			: ParameterEncoding.toBytes(location.getSegment().getMask());
		if (segmentMask != null) {
			int offset = value(location.getOffset());
			for (int n = 0; n < length && offset + n < segmentMask.length; n++) {
				result[n] = segmentMask[offset + n];
			}
		}
		return result;
	}

	private static byte[] full(int length) {
		byte[] result = new byte[length];
		Arrays.fill(result, (byte) 0xFF);
		return result;
	}

	private void objectTable() {
		TableLocation location = _program.getObjectTable();
		if (location == null) {
			return;
		}
		Block segment = location.getSegment() == null ? null : _blocks.get(location.getSegment());
		if (segment == null) {
			_image.addProblem(I18NConstants.IMAGE_OBJECT_TABLE_NO_DATA);
			return;
		}
		String flavour = format("GroupObjectTable",
			List.of(GroupTables.ObjectTableFormat.M112.flavour(), GroupTables.ObjectTableFormat.BCU1.flavour()));
		if (flavour == null) {
			return;
		}
		GroupTables.ObjectTableFormat format = GroupTables.ObjectTableFormat.byFlavour(flavour);
		int start = value(location.getOffset());
		if (_combined.contains(location.getSegment())) {
			pointer("GroupObjectTablePtr", segment.address() + start);
		}
		if (_withoutData.contains(location.getSegment())) {
			// The addresses of the objects' values are part of the program, not of the configuration.
			_image.addProblem(I18NConstants.IMAGE_OBJECT_TABLE_NO_DATA);
			return;
		}
		if (_program.getObjectTableLengthAlwaysOne()) {
			_image.addProblem(I18NConstants.IMAGE_LENGTH_ALWAYS_ONE);
			return;
		}
		byte[] data = segment.data();
		if (start >= data.length) {
			_image.addProblem(I18NConstants.IMAGE_OBJECT_TABLE_OUTSIDE);
			return;
		}
		int count = data[start] & 0xFF;
		int length = format.header() + format.descriptor() * count;
		if (start + length > data.length) {
			_image.addProblem(I18NConstants.IMAGE_OBJECT_TABLE_EXCEEDS__COUNT.fill(count));
			return;
		}

		// The objects of the program and of the device's module instances, by their numbers in effect.
		Map<Integer, Port> ports = new HashMap<>();
		for (Port port : _configuration.getPorts()) {
			ports.putIfAbsent(port.getIndex(), port);
		}
		Map<Port, TLObject> connectors = new HashMap<>();
		for (TLObject connector : _configuration.getConnectors()) {
			Interface placed = ModuleParts.port(connector);
			if (placed != null) {
				connectors.putIfAbsent(placed.getPort(), connector);
			}
		}
		String invisible = _program.getDownloadInvisibleParameters();
		boolean keepsStaticConfig = invisible != null && !invisible.isBlank() && !"None".equals(invisible);
		for (int number = 0; number < count; number++) {
			Port port = ports.get(number);
			if (port == null) {
				_image.addProblem(I18NConstants.IMAGE_UNDEFINED_OBJECT__NUMBER.fill(number));
				continue;
			}
			TLObject connector = connectors.get(port);
			Interface placed = connector != null ? ModuleParts.port(connector)
				: _configuration.getActiveInterfaces().get(port);
			int position = start + format.header() + format.descriptor() * number + format.config();
			if (connector == null && placed == null && keepsStaticConfig) {
				// A program of ETS 6 with the option DownloadInvisibleParameters: an object not in
				// effect keeps the configuration of the static data, without communication (as ETS 6
				// writes it, ets6-download-1.3.5.txt). Otherwise its configuration is computed from
				// its flags, as ETS 4 writes it (the memory of the test system's devices).
				data[position] = (byte) ((data[position] & 0xFF) & ~GroupTables.COMMUNICATION);
				continue;
			}
			data[position] = (byte) GroupTables.objectConfig(data[position] & 0xFF,
				enabled("update", connector, placed, port),
				enabled("transmission", connector, placed, port),
				enabled("canWrite", connector, placed, port),
				enabled("canRead", connector, placed, port),
				enabled("communication", connector, placed, port),
				GroupTables.priorityCode(classifier("priority", connector, placed, port)),
				_associatedObjects.contains(number));
			// The type byte of an object with an association is the size in effect (a parameter may
			// decide it through the reference); an object without one keeps the static byte, as the
			// memory of the test devices shows.
			if (_associatedObjects.contains(number)) {
				int bits = placed != null && placed.getSize() != null ? placed.getSize() : port.getSize();
				int type = GroupTables.objectType(bits);
				if (type < 0 || type > format.maxType()) {
					_image.addProblem(I18NConstants.IMAGE_OBJECT_SIZE__NUMBER_BITS_FORMAT.fill(number, bits, format.flavour()));
					continue;
				}
				data[position - format.config() + format.type()] = (byte) type;
			}
		}
		_image.setObjectTable(new Block(I18NConstants.BLOCK_OBJECT_TABLE, segment.address() + start, segment.stateMachine(),
			Arrays.copyOfRange(data, start, start + length), Arrays.copyOfRange(segment.mask(), start, start + length)));
	}

	/**
	 * The tables must not overlap (a table larger than the space the program leaves it).
	 */
	private void checkOverlaps() {
		List<Block> tables = new ArrayList<>();
		for (Block table : Arrays.asList(_image.getAddressTable(), _image.getAssociationTable(), _image.getObjectTable())) {
			if (table != null && table.stateMachine() == null) {
				tables.add(table);
			}
		}
		for (int n = 0; n < tables.size(); n++) {
			for (int k = n + 1; k < tables.size(); k++) {
				Block a = tables.get(n);
				Block b = tables.get(k);
				if (a.address() < b.end() && b.address() < a.end()) {
					_image.addProblem(I18NConstants.IMAGE_OVERLAP__TABLE_FROM_TO_OTHER_FROM_TO.fill(a.label(), hex4(a.address()),
						hex4(a.end() - 1), b.label(), hex4(b.address()), hex4(b.end() - 1)));
				}
			}
		}
	}

	private static boolean enabled(String flag, TLObject connector, Interface placed, Port port) {
		return "Enabled".equals(classifier(flag, connector, placed, port));
	}

	/**
	 * The name of a flag or the priority of a communication object: the device's own (connector),
	 * else the one of its placement in effect (interface), else the object's (port).
	 */
	private static String classifier(String attribute, TLObject connector, Interface placed, Port port) {
		for (TLObject holder : Arrays.asList(connector, placed, port)) {
			if (holder != null && holder.tValueByName(attribute) instanceof TLClassifier classifier) {
				return classifier.getName();
			}
		}
		return null;
	}

	/**
	 * The individual address of a device: area, line and device number in 4, 4 and 8 bits;
	 * <code>null</code> if one of them is missing.
	 */
	static Integer individualAddress(TLObject device) {
		TLObject line = device.tContainer();
		TLObject area = line == null ? null : line.tContainer();
		Object number = device.tValueByName("address");
		Object lineNumber = line == null ? null : line.tValueByName("address");
		Object areaNumber = area == null ? null : area.tValueByName("address");
		if (!(number instanceof Integer d) || !(lineNumber instanceof Integer l) || !(areaNumber instanceof Integer a)) {
			return null;
		}
		return Integer.valueOf((a.intValue() & 0x0F) << 12 | (l.intValue() & 0x0F) << 8 | (d.intValue() & 0xFF));
	}

	private static String describe(TLObject device) {
		Integer address = individualAddress(device);
		String text = address == null ? "(no address)"
			: (address >>> 12) + "." + ((address >>> 8) & 0x0F) + "." + (address & 0xFF);
		return text + " " + Objects.toString(device.tValueByName("extId"), "");
	}

}
