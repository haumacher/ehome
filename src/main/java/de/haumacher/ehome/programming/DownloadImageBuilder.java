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
import de.haumacher.ehome.model.operational.Segment;
import de.haumacher.ehome.model.operational.TableLocation;
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
 * the communication objects; the communication flag only for objects with an association.</li>
 * </ul>
 */
class DownloadImageBuilder {

	private static final String ADDRESS_TABLE_FORMAT = "AddressTable_Bcu1";

	private static final String ASSOCIATION_TABLE_FORMAT = "AssociationTable_M112";

	private static final String OBJECT_TABLE_FORMAT = "GroupObjectTable_M112";

	private final TLObject _device;

	private final DeviceConfiguration _configuration;

	private final Program _program;

	private final DownloadImage _image;

	private final Map<Segment, Block> _blocks = new HashMap<>();

	private final Map<List<Integer>, byte[]> _properties = new LinkedHashMap<>();

	private final Set<Integer> _associatedObjects = new HashSet<>();

	/** Segments without static data: their mask holds the bits of the parameters written. */
	private final Set<Segment> _withoutData = new HashSet<>();

	DownloadImageBuilder(TLObject device) {
		_device = device;
		_configuration = new DeviceConfiguration(device);
		_program = _configuration.getProgram();
		_image = new DownloadImage(describe(device));
	}

	DownloadImage build() {
		if (_program == null) {
			_image.addProblem("The device has no program.");
			return _image;
		}
		segments();
		parameters();
		tables();
		objectTable();
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
		List<Segment> segments = new ArrayList<>(_program.getSegments());
		segments.sort(Comparator.comparingInt(DownloadImageBuilder::sortKey));
		for (Segment segment : segments) {
			if (tableSegments.contains(segment)) {
				// The template of a table.
				continue;
			}
			byte[] data = ParameterEncoding.toBytes(segment.getData());
			byte[] mask = ParameterEncoding.toBytes(segment.getMask());
			if (data == null) {
				if (segment.getPointers().isEmpty() || segment.getSize() == null) {
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
				block = new Block("Relative segment " + name(segment), relative.getOffset() == null ? 0 : relative.getOffset(),
					relative.getStateMachine(), data, mask);
			} else {
				block = new Block("Segment " + name(segment), ((CodeSegment) segment).getAddress(), null, data, mask);
			}
			_blocks.put(segment, block);
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
				bits = ParameterEncoding.encode(parameter.getType(), _configuration.valueHolder(entry.getValue()), _program);
			} catch (IllegalArgumentException ex) {
				_image.addProblem("Parameter " + label(parameter) + ": " + ex.getMessage());
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
						_image.addProblem("Parameter " + label(parameter) + ": its segment " + name(pointer.getSegment())
							+ " is not written.");
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
				_image.addProblem("Parameter " + label(parameter) + ": " + ex.getMessage());
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
			Port port = connector.tValueByName("port") instanceof Interface placed ? placed.getPort() : null;
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
				_image.addProblem("The device has group addresses, but its program no group address table.");
			}
			return;
		}
		if (!supported("GroupAddressTable", ADDRESS_TABLE_FORMAT) || !supported("GroupAssociationTable", ASSOCIATION_TABLE_FORMAT)) {
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
		checkSize("group addresses", addresses.size(), addressLocation);
		checkSize("associations", associations.size(), associationLocation);

		Integer individualAddress = individualAddress(_device);
		if (individualAddress == null) {
			_image.addProblem("The device has no complete individual address (area, line, device).");
			return;
		}
		byte[] addressTable = GroupTables.addressTable(individualAddress.intValue(), addresses);
		Integer addressStart = start(addressLocation, "GroupAddressTable");
		if (addressStart == null) {
			_image.addProblem("The place of the group address table is not known.");
			return;
		}
		_image.setAddressTable(new Block("Group address table", addressStart, null, addressTable,
			mask(addressLocation, addressTable.length)));

		byte[] associationTable = GroupTables.associationTable(associations);
		int associationStart;
		byte[] associationMask;
		if (_program.getDynamicTableManagement()) {
			// ETS places the association table right after the address table.
			associationStart = addressStart + addressTable.length;
			associationMask = full(associationTable.length);
		} else {
			Integer start = start(associationLocation, null);
			if (start == null) {
				_image.addProblem("The place of the association table is not known.");
				return;
			}
			associationStart = start;
			associationMask = mask(associationLocation, associationTable.length);
		}
		_image.setAssociationTable(new Block("Association table", associationStart, null, associationTable, associationMask));
	}

	private void checkSize(String what, int size, TableLocation location) {
		if (location != null && location.getMaxEntries() != null && size > location.getMaxEntries()) {
			_image.addProblem("The device has " + size + " " + what + ", the program allows " + location.getMaxEntries() + ".");
		}
	}

	private boolean supported(String resource, String format) {
		MaskVersion mask = _program.getMask();
		if (mask == null) {
			_image.addProblem("The program has no mask version, the format of its tables is not known.");
			return false;
		}
		List<String> formats = new ArrayList<>();
		for (MaskResource candidate : mask.getResources()) {
			if (resource.equals(candidate.getName())) {
				if (format.equals(candidate.getFlavour())) {
					return true;
				}
				formats.add(candidate.getFlavour());
			}
		}
		_image.addProblem("The " + resource + " of mask " + mask.getName() + " has the format " + formats
			+ "; supported is " + format + " (verified with mask 7.5).");
		return false;
	}

	private Integer start(TableLocation location, String maskResource) {
		if (location != null && location.getSegment() instanceof CodeSegment segment && segment.getAddress() != null) {
			return segment.getAddress() + value(location.getOffset());
		}
		if (location != null && location.getSegment() instanceof RelativeSegment) {
			_image.addProblem("A table in a relative segment is not supported.");
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
			_image.addProblem("The object table has no static data in its segment.");
			return;
		}
		if (!supported("GroupObjectTable", OBJECT_TABLE_FORMAT)) {
			return;
		}
		if (_program.getObjectTableLengthAlwaysOne()) {
			_image.addProblem("The option SetObjectTableLengthAlwaysToOne is not supported.");
			return;
		}
		byte[] data = segment.data();
		int start = value(location.getOffset());
		int count = data[start] & 0xFF;
		int length = GroupTables.OBJECT_TABLE_HEADER + GroupTables.OBJECT_DESCRIPTOR * count;
		if (start + length > data.length) {
			_image.addProblem("The object table of " + count + " objects exceeds its segment.");
			return;
		}

		Map<Integer, Port> ports = new HashMap<>();
		for (Port port : _program.getPorts()) {
			ports.put(port.getIndex(), port);
		}
		Map<Port, TLObject> connectors = new HashMap<>();
		for (TLObject connector : _configuration.getConnectors()) {
			if (connector.tValueByName("port") instanceof Interface placed) {
				connectors.putIfAbsent(placed.getPort(), connector);
			}
		}
		for (int number = 0; number < count; number++) {
			Port port = ports.get(number);
			if (port == null) {
				_image.addProblem("The object table has an object " + number + " the program does not define.");
				continue;
			}
			TLObject connector = connectors.get(port);
			Interface placed = connector != null ? (Interface) connector.tValueByName("port")
				: _configuration.getActiveInterfaces().get(port);
			int position = start + GroupTables.OBJECT_TABLE_HEADER + GroupTables.OBJECT_DESCRIPTOR * number
				+ GroupTables.OBJECT_CONFIG;
			data[position] = (byte) GroupTables.objectConfig(data[position] & 0xFF,
				enabled("update", connector, placed, port),
				enabled("transmission", connector, placed, port),
				enabled("canWrite", connector, placed, port),
				enabled("canRead", connector, placed, port),
				enabled("communication", connector, placed, port),
				GroupTables.priorityCode(classifier("priority", connector, placed, port)),
				_associatedObjects.contains(number));
		}
		_image.setObjectTable(new Block("Object table", segment.address() + start, segment.stateMachine(),
			Arrays.copyOfRange(data, start, start + length), Arrays.copyOfRange(segment.mask(), start, start + length)));
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
