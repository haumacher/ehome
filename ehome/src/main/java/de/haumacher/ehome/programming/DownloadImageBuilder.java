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
import java.util.TreeSet;

import com.top_logic.basic.util.ResKey2;
import com.top_logic.model.TLClassifier;
import com.top_logic.model.TLObject;

import de.haumacher.ehome.knx.ExtIds;
import de.haumacher.ehome.knx.GA;
import de.haumacher.ehome.model.logical.GroupAddress;
import de.haumacher.ehome.model.operational.CodeSegment;
import de.haumacher.ehome.model.operational.Interface;
import de.haumacher.ehome.model.operational.LoadProcedure;
import de.haumacher.ehome.model.operational.LoadStep;
import de.haumacher.ehome.model.operational.Location;
import de.haumacher.ehome.model.operational.MaskConfiguration;
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
import de.haumacher.ehome.monitor.BusTelegram;
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
 * static data (a template of the manufacturer). The first entry of the address table is the
 * individual address where the device's mask keeps it there, else the static data
 * ({@link #individualAddressInTable(MaskConfiguration, int)}).</li>
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

	private static final String LINE = "ehome.physical:Line";

	private static final String ADDRESS_TABLE_FORMAT = "AddressTable_Bcu1";

	/** The mask's resource of the individual address in memory (BCU 1, BCU 2). */
	private static final String INDIVIDUAL_ADDRESS = "IndividualAddress";

	/** The mask's resource of the stamp of the group address table (BIM M112 with download stamps). */
	private static final String ADDRESS_TABLE_STAMP = "GroupAddressTableStamp";

	/** The format of the association table of BCU 2, with a reserved place per object ({@link GroupTables#associationSlots(Collection, int)}). */
	private static final String ASSOCIATION_TABLE_BCU2 = "AssociationTable_Bcu2";

	/** The format of the association table of BCU 1. */
	private static final String ASSOCIATION_TABLE_BCU1 = "AssociationTable_Bcu1";

	/**
	 * The formats of the association table with a reserved place per object
	 * ({@link GroupTables#associationSlots(Collection, int)}): BCU 2, and BCU 1 as ETS writes it (a
	 * device of mask 0012 holds an entry per object, <code>FE</code> for an object without an
	 * association, haumacher/ehome #222).
	 */
	private static final List<String> SLOT_FORMATS = List.of(ASSOCIATION_TABLE_BCU1, ASSOCIATION_TABLE_BCU2);

	/** The formats of the association table with the bytes of {@link GroupTables#associationTable(List)}. */
	private static final List<String> ASSOCIATION_TABLE_FORMATS =
		List.of("AssociationTable_M112", ASSOCIATION_TABLE_BCU1, ASSOCIATION_TABLE_BCU2);

	/**
	 * The feature of a mask whose segments with checksum control (allocation attribute
	 * {@value #CHECKSUM_SEGMENT}) take one byte more than their content: the checksum.
	 */
	private static final String ALLOC_EXTRA_BYTE = "AllocExtraByte";

	/** The attribute of a segment allocation switching the checksum control of the segment on. */
	private static final int CHECKSUM_SEGMENT = 0x80;

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
		identity();
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

	/**
	 * Writes the program's identity where the mask keeps it in memory (BCU 1: the resource
	 * <code>ApplicationId</code>, four bytes at 0104: the low byte of the manufacturer, the
	 * application number and its version). ETS writes it there with the static data, whose bytes
	 * at that place are a placeholder (a device of mask 0012 holds <code>01 20 71 01</code> for
	 * <code>M-0001_A-2071-01</code> where the static data has <code>0A 70 53 00</code>,
	 * haumacher/ehome #222).
	 */
	private void identity() {
		MaskResource resource = memoryResourceOf("ApplicationId");
		if (resource == null || resource.getLength() == null) {
			return;
		}
		byte[] id = de.haumacher.ehome.programming.download.DownloadPlanner.applicationId(_program);
		int length = resource.getLength().intValue();
		if (length > id.length) {
			return;
		}
		int address = resource.getLocation().getStartAddress().intValue();
		for (Block block : _blocks.values()) {
			if (block.stateMachine() != null || !block.contains(address) || !block.contains(address + length - 1)) {
				continue;
			}
			for (int n = 0; n < length; n++) {
				block.data()[address - block.address() + n] = id[id.length - length + n];
				block.mask()[address - block.address() + n] = (byte) 0xFF;
			}
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
				GroupAddress target = (GroupAddress) link.tValueByName("target");
				GA address = target == null ? null : target.getAddress();
				if (address == null) {
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
		if (!supported("GroupAddressTable", List.of(ADDRESS_TABLE_FORMAT))) {
			return;
		}
		String associationFormat = format("GroupAssociationTable", ASSOCIATION_TABLE_FORMATS);
		if (associationFormat == null) {
			return;
		}

		List<Integer> addresses = GroupTables.addressOrder(links.stream().map(l -> l[0]).toList());
		List<Association> associations = new ArrayList<>();
		for (int[] link : links) {
			associations.add(new Association(addresses.indexOf(link[0]) + 1, link[1], link[2] == 1));
		}
		for (Association association : associations) {
			_associatedObjects.add(association.objectNumber());
		}
		if (slots(_program, associationFormat, objectCount())) {
			associations = GroupTables.associationSlots(associations, objectCount());
		} else {
			associations = GroupTables.associationOrder(associations);
		}
		checkSize(I18NConstants.IMAGE_TOO_MANY_ADDRESSES__COUNT_MAX, addresses.size(), addressLocation);
		checkSize(I18NConstants.IMAGE_TOO_MANY_ASSOCIATIONS__COUNT_MAX, associations.size(), associationLocation);

		Integer individualAddress = individualAddress(_device);
		if (individualAddress == null) {
			_image.addProblem(de.haumacher.ehome.programming.download.I18NConstants.ERROR_NO_ADDRESS);
			return;
		}
		Integer addressStart = start(addressLocation, "GroupAddressTable");
		if (addressStart == null) {
			_image.addProblem(I18NConstants.IMAGE_ADDRESS_TABLE_PLACE);
			return;
		}
		// The first entry: the individual address where the mask keeps it in the table, else the
		// program's static data (haumacher/ehome #219).
		boolean keepsAddress = individualAddressInTable(_program.getMaskConfiguration(), addressStart.intValue());
		Integer staticEntry = keepsAddress ? null : staticFirstEntry(addressLocation);
		int firstEntry = keepsAddress || staticEntry == null ? individualAddress.intValue() : staticEntry.intValue();
		byte[] addressTable = GroupTables.addressTable(firstEntry, addresses);
		byte[] addressMask = mask(addressLocation, addressTable.length);
		if (!keepsAddress && staticEntry == null) {
			// Neither the device's nor static data: the download leaves the first entry as it is.
			addressMask[1] = 0;
			addressMask[2] = 0;
		}
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
		boolean dynamic = _program.getDynamicTableManagement();
		if (dynamic) {
			// ETS places the association table right after the address table (and the checksum of its
			// segment, see tableChecksum(Program)), also within a segment shared with other content
			// (BCU 1: the pointer then names that place, haumacher/ehome #222).
			associationStart = addressStart + addressTable.length + tableChecksum(_program);
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
			pointer("GroupAssociationTablePtr", associationStart, dynamic);
			patch(associationLocation.getSegment(), associationBlock);
			if (dynamic && addressCombined) {
				freeTableSpace(addressLocation, associationLocation, addressBlock, associationBlock);
			}
		}
	}

	/**
	 * Leaves out of the image the space the program reserves for its tables in a segment shared with
	 * other content that the tables of a dynamic table management do not take: from the place of the
	 * address table to the end of the place the static data reserves for the association table (its
	 * count and two bytes for each of its <code>MaxEntries</code>), not beyond the object table.
	 *
	 * <p>
	 * The static data has templates of both tables there. ETS writes the tables it computes at the
	 * start of that space and leaves the rest as the device holds it (BCU 1, haumacher/ehome #225):
	 * two valve drives of one program hold zeros from the end of their association table to the
	 * object table, where the static data has the templates, a switching actuator of another program
	 * holds the template of its static data there; all three last loaded by ETS 4.2, whose load
	 * procedure verifies what it writes. No rule that writes these bytes gives both; one that leaves
	 * them does.
	 * </p>
	 */
	private void freeTableSpace(TableLocation addressLocation, TableLocation associationLocation, Block addressBlock,
			Block associationBlock) {
		if (associationLocation.getSegment() != addressLocation.getSegment() || associationLocation.getMaxEntries() == null) {
			return;
		}
		Integer reserved = start(associationLocation, null);
		if (reserved == null) {
			return;
		}
		int end = reserved.intValue() + 1 + 2 * associationLocation.getMaxEntries().intValue();
		TableLocation objectLocation = _program.getObjectTable();
		Integer objects = objectLocation != null && objectLocation.getSegment() == associationLocation.getSegment()
			? start(objectLocation, null) : null;
		if (objects != null && objects.intValue() > addressBlock.address()) {
			end = Math.min(end, objects.intValue());
		}
		Block segment = _blocks.get(associationLocation.getSegment());
		for (int address = addressBlock.address(); address < end; address++) {
			if (addressBlock.contains(address) || associationBlock.contains(address) || !segment.contains(address)) {
				continue;
			}
			segment.mask()[address - segment.address()] = 0;
		}
	}

	/**
	 * Whether a device of the given mask keeps its individual address in the first entry of its group
	 * address table (see {@link GroupTables}): the mask has the resource <code>IndividualAddress</code>
	 * in memory at the place of the first entry (BCU 1, BCU 2), or the resource
	 * <code>GroupAddressTableStamp</code>, the stamp of the address table, whose checksum covers the
	 * table as the device holds it (BIM M112 with download stamps, mask 0705).
	 *
	 * @param tableAddress
	 *        The address of the group address table.
	 */
	static boolean individualAddressInTable(MaskConfiguration mask, int tableAddress) {
		if (mask == null) {
			return false;
		}
		for (MaskResource resource : mask.getResources()) {
			ResourceLocation location = resource.getLocation();
			if (ADDRESS_TABLE_STAMP.equals(resource.getName())) {
				return true;
			}
			if (INDIVIDUAL_ADDRESS.equals(resource.getName()) && location != null && location.getAddressSpace() != null
				&& "StandardMemory".equals(location.getAddressSpace().getName()) && location.getStartAddress() != null
				&& location.getStartAddress().intValue() == tableAddress + 1) {
				return true;
			}
		}
		return false;
	}

	/**
	 * The first entry of the address table in the program's static data, <code>null</code> if the
	 * program has no static data there.
	 */
	private static Integer staticFirstEntry(TableLocation location) {
		byte[] data = location == null || location.getSegment() == null ? null
			: ParameterEncoding.toBytes(location.getSegment().getData());
		int offset = location == null ? 0 : value(location.getOffset());
		if (data == null || offset + 3 > data.length) {
			return null;
		}
		return Integer.valueOf((data[offset + 1] & 0xFF) << 8 | (data[offset + 2] & 0xFF));
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
		MaskResource resource = memoryResourceOf(INDIVIDUAL_ADDRESS);
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
	 * place is a problem, unless the place is the download's to choose.
	 *
	 * @param placed
	 *        Whether the download places the table (dynamic table management): the pointer is
	 *        written whatever the static data holds.
	 */
	private void pointer(String resourceName, int tableAddress, boolean placed) {
		MaskResource resource = memoryResourceOf(resourceName);
		if (resource == null && propertyResource(resourceName)) {
			// The mask keeps the pointer in a property only (BCU 2.5, PID_TABLE_REFERENCE): the load
			// procedure sets it with the task segment of the table's load state machine.
			return;
		}
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
		if (mask == 0 || placed) {
			block.data()[index] = (byte) value;
			block.mask()[index] = (byte) 0xFF;
		} else if (mask != 0xFF || (block.data()[index] & 0xFF) != value) {
			_image.addProblem(I18NConstants.IMAGE_POINTER_STATIC__VALUE_MASK_RESOURCE_ADDRESS_TABLE.fill(
				String.format("%02X", block.data()[index] & 0xFF), String.format("%02X", mask), resourceName, hex4(address),
				hex4(tableAddress)));
		}
	}

	/**
	 * Whether the mask names a resource of the given name as a property of an interface object.
	 */
	private boolean propertyResource(String name) {
		if (_program.getMask() == null) {
			return false;
		}
		for (MaskResource resource : _program.getMask().getResources(_program)) {
			ResourceLocation location = resource.getLocation();
			if (name.equals(resource.getName()) && location != null && location.getAddressSpace() != null
				&& "SystemProperty".equals(location.getAddressSpace().getName())) {
				return true;
			}
		}
		return false;
	}

	/**
	 * The number of communication objects of the program: the count of the object table in its
	 * static data, else one more than the highest object number.
	 */
	private int objectCount() {
		TableLocation location = _program.getObjectTable();
		if (location != null && location.getSegment() != null) {
			byte[] data = ParameterEncoding.toBytes(location.getSegment().getData());
			int offset = value(location.getOffset());
			if (data != null && offset < data.length) {
				return data[offset] & 0xFF;
			}
		}
		int count = 0;
		for (Port port : _configuration.getPorts()) {
			count = Math.max(count, port.getIndex() + 1);
		}
		return count;
	}

	/**
	 * The checksum byte at the end of the segment of the address table (load state machine 1): 1 if
	 * the mask allocates a byte more for the checksum of a segment with checksum control (feature
	 * {@value #ALLOC_EXTRA_BYTE}, BCU 2) and the load procedure allocates that segment with checksum
	 * control (attribute {@value #CHECKSUM_SEGMENT}), else 0.
	 *
	 * <p>
	 * With dynamic table management, the address table's segment is then one byte longer than the
	 * table, and the association table follows after that byte. Source: the rule ETS follows as the
	 * open-source download tool xknx-editor recorded it against ETS (BCU 2 with dynamic tables: the
	 * association table at 0116 + 3 + 2n + 1 with <code>AllocExtraByte</code> and
	 * <code>SegFlags</code> 80 of the segment of load state machine 1); the feature's name says the
	 * same. Not verified against a device of E-Home's.
	 * </p>
	 */
	static boolean associationSlots(Program program) {
		if (program.getMask() == null) {
			return false;
		}
		for (MaskResource resource : program.getMask().getResources(program)) {
			if ("GroupAssociationTable".equals(resource.getName())) {
				return slots(program, resource.getFlavour(), staticObjectCount(program));
			}
		}
		return false;
	}

	/**
	 * Whether the association table of the given format reserves a place per object: BCU 2 always;
	 * BCU 1 where the program's table can hold a place for each of its objects (the device of mask
	 * 0012 ETS programmed: 24 places of 27 entries; a program whose table holds fewer entries than it
	 * has objects cannot have them).
	 */
	private static boolean slots(Program program, String format, int objectCount) {
		if (ASSOCIATION_TABLE_BCU2.equals(format)) {
			return true;
		}
		if (!ASSOCIATION_TABLE_BCU1.equals(format)) {
			return false;
		}
		TableLocation location = program.getAssociationTable();
		return location == null || location.getMaxEntries() == null || location.getMaxEntries().intValue() >= objectCount;
	}

	/**
	 * The number of objects of the program's object table in its static data, else the number of
	 * its objects.
	 */
	private static int staticObjectCount(Program program) {
		TableLocation location = program.getObjectTable();
		byte[] data = location == null || location.getSegment() == null ? null
			: ParameterEncoding.toBytes(location.getSegment().getData());
		int offset = location == null ? 0 : value(location.getOffset());
		if (data != null && offset < data.length) {
			return data[offset] & 0xFF;
		}
		int count = 0;
		for (Port port : program.getPorts()) {
			count = Math.max(count, port.getIndex() + 1);
		}
		return count;
	}

	static int tableChecksum(Program program) {
		MaskVersion mask = program.getMask();
		if (mask == null || mask.getFeatures(program).stream()
			.noneMatch(f -> ALLOC_EXTRA_BYTE.equals(f.getName()) && Objects.equals(f.getValue(), 1))) {
			return 0;
		}
		List<LoadProcedure> procedures = new ArrayList<>(program.getLoadProcedures());
		procedures.addAll(mask.getProcedures(program));
		for (LoadProcedure procedure : procedures) {
			for (LoadStep step : procedure.getSteps()) {
				if (step.getKind() != null && "AbsSegment".equals(step.getKind().getName())
					&& Objects.equals(step.getStateMachine(), 1)) {
					Integer flags = step.getSegmentFlags();
					return flags != null && (flags & CHECKSUM_SEGMENT) != 0 ? 1 : 0;
				}
			}
		}
		return 0;
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
		for (MaskResource resource : _program.getMask().getResources(_program)) {
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
		for (MaskResource candidate : mask.getResources(_program)) {
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
			for (MaskResource resource : _program.getMask().getResources(_program)) {
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
		String flavour = format("GroupObjectTable", List.of(GroupTables.ObjectTableFormat.M112.flavour(),
			GroupTables.ObjectTableFormat.BCU1.flavour(), GroupTables.ObjectTableFormat.BCU2.flavour()));
		if (flavour == null) {
			return;
		}
		GroupTables.ObjectTableFormat format = GroupTables.ObjectTableFormat.byFlavour(flavour);
		int start = value(location.getOffset());
		if (_combined.contains(location.getSegment())) {
			pointer("GroupObjectTablePtr", segment.address() + start, false);
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
		// An object with group addresses that the table cannot hold is not silently left out: the
		// association table would point to a descriptor the device does not have (haumacher/ehome
		// #202). An object without an association never communicates; the table need not hold it.
		for (Integer number : new TreeSet<>(_associatedObjects)) {
			if (number.intValue() >= count) {
				_image.addProblem(I18NConstants.IMAGE_OBJECT_BEYOND_TABLE__NUMBER_COUNT.fill(number, count));
			}
		}
		Map<Port, TLObject> connectors = new HashMap<>();
		for (TLObject connector : _configuration.getConnectors()) {
			Interface placed = ModuleParts.port(connector);
			if (placed != null) {
				connectors.putIfAbsent(placed.getPort(), connector);
			}
		}
		boolean combinedTable = _combined.contains(location.getSegment());
		String invisible = _program.getDownloadInvisibleParameters();
		boolean keepsStaticConfig = invisible != null && !invisible.isBlank() && !"None".equals(invisible);
		for (int number = 0; number < count; number++) {
			Port port = ports.get(number);
			if (port == null) {
				// A number the program defines no object for: the slot of a module instance not in
				// effect (a repeat counting fewer, haumacher/ehome #202), or a gap in the numbering of the
				// program (a program converted from ETS 3 with the objects 0-9 and 11-35 in a table of
				// 36, haumacher/ehome #226). No setting, flag or association reaches it, so it keeps the
				// descriptor of the static data, as an object not in effect: without communication
				// where such an object keeps the static configuration (a program with modules, the
				// option DownloadInvisibleParameters, a table within the memory image of BCU 1), else
				// unchanged (there are no flags to compute its configuration from).
				if (!_program.getModules().isEmpty() || keepsStaticConfig || combinedTable) {
					int position = start + format.header() + format.descriptor() * number + format.config();
					data[position] = (byte) ((data[position] & 0xFF) & ~GroupTables.COMMUNICATION);
				}
				continue;
			}
			TLObject connector = connectors.get(port);
			Interface placed = connector != null ? ModuleParts.port(connector)
				: _configuration.getActiveInterfaces().get(port);
			int position = start + format.header() + format.descriptor() * number + format.config();
			if (connector == null && placed == null && (keepsStaticConfig || combinedTable)) {
				// A program of ETS 6 with the option DownloadInvisibleParameters, and a table within the
				// memory image of BCU 1: an object not in effect keeps the configuration of the static
				// data, without communication (as ETS 6 writes it, ets6-download-1.3.5.txt; a device of
				// mask 0012 holds D3 where the static data has D7, haumacher/ehome #222). Otherwise its
				// configuration is computed from its flags, as ETS 4 writes it (the memory of the test
				// system's devices).
				data[position] = (byte) ((data[position] & 0xFF) & ~GroupTables.COMMUNICATION);
				continue;
			}
			data[position] = (byte) GroupTables.objectConfig(format, data[position] & 0xFF,
				enabled("update", connector, placed, port),
				enabled("transmission", connector, placed, port),
				enabled("canWrite", connector, placed, port),
				enabled("canRead", connector, placed, port),
				enabled("communication", connector, placed, port),
				GroupTables.priorityCode(classifier("priority", connector, placed, port)),
				_associatedObjects.contains(number));
			// The type byte of an object with an association or in effect is the size in effect (a
			// parameter may decide it through the reference): a device of mask 0705 holds 07 for two
			// objects in effect of a byte each without association where the static data has 00 and 03
			// (haumacher/ehome #222); an object neither associated nor in effect keeps the static byte.
			if (_associatedObjects.contains(number) || placed != null) {
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
	 * The individual address of a device: area, line and device number in 4, 4 and 8 bits, from the
	 * line and area holding the device and its device number; <code>null</code> for a device in no
	 * line, or if one of the numbers is missing.
	 *
	 * <p>
	 * The one computation of the individual address: its text
	 * ({@link #individualAddressText(TLObject)}) is the derived attribute
	 * <code>ehome.physical:BusDevice#individualAddress</code> (haumacher/ehome #203).
	 * </p>
	 */
	static Integer individualAddress(TLObject device) {
		TLObject line = device.tContainer();
		if (line == null || !ExtIds.isA(line, LINE)) {
			return null;
		}
		TLObject area = line.tContainer();
		Object number = device.tValueByName("address");
		Object lineNumber = line.tValueByName("address");
		Object areaNumber = area == null ? null : area.tValueByName("address");
		if (!(number instanceof Integer d) || !(lineNumber instanceof Integer l) || !(areaNumber instanceof Integer a)) {
			return null;
		}
		return Integer.valueOf((a.intValue() & 0x0F) << 12 | (l.intValue() & 0x0F) << 8 | (d.intValue() & 0xFF));
	}

	/**
	 * The individual address of a device in its notation area.line.device (<code>1.3.50</code>);
	 * <code>null</code> where {@link #individualAddress(TLObject)} has none.
	 */
	static String individualAddressText(TLObject device) {
		Integer address = individualAddress(device);
		return address == null ? null : BusTelegram.individualAddress(address.intValue());
	}

	private static String describe(TLObject device) {
		String text = individualAddressText(device);
		return (text == null ? "(no address)" : text) + " " + Objects.toString(device.tValueByName("extId"), "");
	}

}
