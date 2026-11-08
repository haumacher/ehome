/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.scan;

import java.math.BigInteger;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.Charset;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.top_logic.model.TLClassifier;
import com.top_logic.model.TLObject;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.error.TopLogicException;
import com.top_logic.util.Resources;

import de.haumacher.ehome.knx.GA;
import de.haumacher.ehome.knx.ParameterValues;
import de.haumacher.ehome.model.operational.Case;
import de.haumacher.ehome.model.operational.ConfigFragment;
import de.haumacher.ehome.model.operational.DateType;
import de.haumacher.ehome.model.operational.EnumerationType;
import de.haumacher.ehome.model.operational.EnumerationValue;
import de.haumacher.ehome.model.operational.FloatingPointType;
import de.haumacher.ehome.model.operational.IPAddressType;
import de.haumacher.ehome.model.operational.Interface;
import de.haumacher.ehome.model.operational.Location;
import de.haumacher.ehome.model.operational.MaskFeature;
import de.haumacher.ehome.model.operational.MaskResource;
import de.haumacher.ehome.model.operational.NumericType;
import de.haumacher.ehome.model.operational.Option;
import de.haumacher.ehome.model.operational.Parameter;
import de.haumacher.ehome.model.operational.ParameterType;
import de.haumacher.ehome.model.operational.ParameterView;
import de.haumacher.ehome.model.operational.Pointer;
import de.haumacher.ehome.model.operational.Port;
import de.haumacher.ehome.model.operational.Program;
import de.haumacher.ehome.model.operational.PropertyLocation;
import de.haumacher.ehome.model.operational.SharedPlacement;
import de.haumacher.ehome.model.operational.Switch;
import de.haumacher.ehome.model.operational.TextType;
import de.haumacher.ehome.model.operational.TimeType;
import de.haumacher.ehome.model.operational.VoidType;
import de.haumacher.ehome.programming.DeviceConfiguration;
import de.haumacher.ehome.programming.DeviceSettings;
import de.haumacher.ehome.programming.DownloadImage;
import de.haumacher.ehome.programming.DownloadImage.Block;
import de.haumacher.ehome.programming.GroupTables;
import de.haumacher.ehome.programming.ParameterEncoding;
import de.haumacher.ehome.programming.download.BusException;
import de.haumacher.ehome.programming.download.DeviceReader;
import de.haumacher.ehome.programming.download.DownloadPlan;
import de.haumacher.ehome.programming.download.DownloadPlanner;
import de.haumacher.ehome.programming.download.LoadControls;
import de.haumacher.ehome.modules.ModuleParts;

/**
 * Reads the configuration of a device back into the project: its group addresses and connections,
 * the flags of its communication objects and its parameter values.
 *
 * <p>
 * The device is read where its download image ({@link DownloadImage}) writes: the memory of every
 * block of the image, the group address table and the association table (at the places the device
 * names in its table pointers, property 7 of the interface objects 1 and 2, else at those of the
 * image; their lengths from their counts), and the properties carrying parameters. Nothing is
 * written to the device ({@link #read(TLObject, DeviceReader, Listener)}).
 * </p>
 *
 * <p>
 * The decoding ({@link #apply(TLObject, Memory)}) is the inverse of the image:
 * </p>
 * <ul>
 * <li>Parameters: for each parameter in effect, the bits at its place (pointer or property) are
 * compared with the encoding of the device's current value; a different value is decoded by the
 * parameter's type and set (a value equal to the default removes the setting). The parameters in
 * effect depend on the values (switches), so this repeats until nothing changes; settings of
 * placements no longer in effect are removed.</li>
 * <li>Connections: per association, the group address of its index in the address table (created
 * in the project where it has none, named by its address) is connected to the communication
 * object of its number; the first address of an object is the one it sends to (as ETS links
 * them), the others it receives from. Connections of the device the tables do not hold are
 * removed.</li>
 * <li>Flags: where the configuration byte of an object in the device's object table differs from
 * the one the project computes, the object's flags and priority are set at its connector.</li>
 * </ul>
 * <p>
 * Afterwards the image is computed again and compared with the memory read: every byte equal is the
 * proof of the decoding ({@link Report#differences()}).
 * </p>
 */
public class ReadBack {

	/** The number of passes over the parameters before the decoding gives up. */
	private static final int MAX_PASSES = 20;

	/**
	 * Is told what is read.
	 */
	public interface Listener {

		/**
		 * The given number of the reads is done; <code>what</code> is read next.
		 */
		void progress(int done, int total, String what);
	}

	/**
	 * What was read from a device.
	 *
	 * @param device
	 *        The device, for a person.
	 * @param memory
	 *        The memory (64 KiB), valid where {@link #known()} is set.
	 * @param known
	 *        The addresses read.
	 * @param properties
	 *        The values of the properties read, by <code>object/property</code>.
	 * @param addressTable
	 *        The address of the group address table, <code>null</code> if the program has none.
	 * @param associationTable
	 *        The address of the association table, <code>null</code> if the program has none.
	 * @param notes
	 *        What could not be read.
	 * @param unloaded
	 *        The addresses of the blocks of the image a download does not write (not read: the device
	 *        does not hold the image there, and the parameters there keep their defaults).
	 */
	public record Memory(String device, byte[] memory, BitSet known, Map<String, byte[]> properties, Integer addressTable,
			Integer associationTable, List<String> notes, BitSet unloaded) {

		/**
		 * The byte at the given address, <code>null</code> if it was not read.
		 */
		public Integer byteAt(int address) {
			return known.get(address) ? Integer.valueOf(memory[address] & 0xFF) : null;
		}
	}

	/**
	 * The outcome of {@link ReadBack#apply(TLObject, Memory)}.
	 *
	 * @param settings
	 *        The number of parameter values that differ from the defaults.
	 * @param connections
	 *        The number of connections of the device.
	 * @param createdAddresses
	 *        The group addresses created.
	 * @param flags
	 *        The number of communication objects whose flags were set.
	 * @param inferred
	 *        The values chosen for parameters without memory, for a person.
	 * @param problems
	 *        What could not be decoded.
	 * @param compared
	 *        The number of bytes of the image compared with the memory read.
	 * @param differences
	 *        The addresses at which the image of the device as read back differs from the memory
	 *        read.
	 */
	public record Report(int settings, int connections, List<String> createdAddresses, int flags, List<String> inferred,
			List<String> problems,
			int compared, List<Integer> differences) {

		/**
		 * Whether the image of the device as read back equals the memory read.
		 */
		public boolean identical() {
			return differences.isEmpty() && problems.isEmpty();
		}
	}

	/**
	 * Reads the configuration of the device from the bus.
	 *
	 * @param device
	 *        The device of the project (<code>ehome.physical:Device</code>), with its program.
	 * @param reader
	 *        The access to the real device.
	 */
	public static Memory read(TLObject device, DeviceReader reader, Listener listener) throws BusException {
		DownloadImage image = DownloadImage.compute(device);
		Program program = DeviceConfiguration.program(device);
		if (program == null) {
			throw new BusException("The device has no program.");
		}
		byte[] memory = new byte[0x10000];
		BitSet known = new BitSet();
		Map<String, byte[]> properties = new LinkedHashMap<>();
		List<String> notes = new ArrayList<>();

		Block addressBlock = image.getAddressTable();
		Block associationBlock = image.getAssociationTable();
		List<Block> blocks = new ArrayList<>();
		for (Block block : loadedBlocks(device, image)) {
			if (block == addressBlock || block == associationBlock) {
				continue;
			}
			if (block.stateMachine() != null) {
				notes.add(block.name() + ": a relative segment, its place in the device is not known.");
				continue;
			}
			blocks.add(block);
		}
		int total = blocks.size() + 2 + image.getPropertyWrites().size();
		int done = 0;
		for (Block block : blocks) {
			listener.progress(done++, total, block.name());
			byte[] mask = block.mask();
			int n = 0;
			while (n < mask.length) {
				if (mask[n] == 0) {
					n++;
					continue;
				}
				int start = n;
				while (n < mask.length && mask[n] != 0 && n - start < DownloadPlanner.MAX_CHUNK) {
					n++;
				}
				readInto(reader, block.address() + start, n - start, memory, known);
			}
		}

		listener.progress(done++, total, "Group address table");
		boolean propertyMapped = propertyMapped(program);
		Integer addressTable = null;
		Integer associationTable = null;
		if (addressBlock != null) {
			addressTable = propertyMapped ? tablePointer(reader, 1, notes) : null;
			if (addressTable == null) {
				addressTable = Integer.valueOf(addressBlock.address());
			}
			readTable(reader, addressTable.intValue(), memory, known);
		}
		listener.progress(done++, total, "Association table");
		if (associationBlock != null) {
			associationTable = propertyMapped ? tablePointer(reader, 2, notes) : null;
			if (associationTable == null) {
				boolean separate = image.getBlocks().contains(associationBlock);
				if (program.getDynamicTableManagement() && separate && addressTable != null) {
					associationTable = Integer.valueOf(addressTable.intValue() + 1 + 2 * (memory[addressTable] & 0xFF));
				} else {
					associationTable = Integer.valueOf(associationBlock.address());
				}
			}
			readTable(reader, associationTable.intValue(), memory, known);
		}

		for (DownloadImage.PropertyWrite write : image.getPropertyWrites()) {
			listener.progress(done++, total, "Property " + write.propertyId());
			if (write.objectIndex() == null) {
				notes.add("Parameters in property " + write.propertyId() + " of an object given by type ("
					+ write.objectType() + "): not supported.");
				continue;
			}
			properties.put(write.objectIndex() + "/" + write.propertyId(),
				reader.readProperty(write.objectIndex(), write.propertyId(), 1, 1));
		}
		listener.progress(total, total, "");
		reader.disconnect();
		BitSet unloaded = new BitSet();
		Set<Integer> unloadedStarts = unloaded(device);
		for (Block block : image.getBlocks()) {
			if (block.stateMachine() == null && unloadedStarts.contains(block.address())) {
				unloaded.set(block.address(), block.end());
			}
		}
		return new Memory(image.getDevice(), memory, known, properties, addressTable, associationTable, notes, unloaded);
	}

	/**
	 * The blocks of the image a download writes (and a comparison compares): without those whose
	 * segment the load procedure does not load.
	 */
	private static List<Block> loadedBlocks(TLObject device, DownloadImage image) {
		Set<Integer> unloaded = unloaded(device);
		List<Block> result = new ArrayList<>(image.getBlocks());
		result.removeIf(block -> block.stateMachine() == null && unloaded.contains(block.address()));
		return result;
	}

	/**
	 * The addresses of the blocks of the device's image that the load procedure does not load.
	 */
	private static Set<Integer> unloaded(TLObject device) {
		Set<Integer> result = new HashSet<>();
		for (Block block : DownloadPlanner.plan(device, DownloadPlan.Kind.FULL).getUnloadedBlocks()) {
			if (block.stateMachine() == null) {
				result.add(block.address());
			}
		}
		return result;
	}

	private static void readInto(DeviceReader reader, int address, int length, byte[] memory, BitSet known)
			throws BusException {
		int position = 0;
		while (position < length) {
			int chunk = Math.min(DownloadPlanner.MAX_CHUNK, length - position);
			byte[] data = reader.readMemory(address + position, chunk);
			if (data.length != chunk) {
				throw new BusException(String.format("Reading %04X answered %d of %d bytes.", address + position,
					data.length, chunk));
			}
			System.arraycopy(data, 0, memory, address + position, chunk);
			known.set(address + position, address + position + chunk);
			position += chunk;
		}
	}

	/**
	 * Reads a table: its count (first byte), then two bytes per entry.
	 */
	private static void readTable(DeviceReader reader, int address, byte[] memory, BitSet known) throws BusException {
		readInto(reader, address, 1, memory, known);
		int count = memory[address] & 0xFF;
		if (count > 0) {
			readInto(reader, address + 1, 2 * count, memory, known);
		}
	}

	private static Integer tablePointer(DeviceReader reader, int object, List<String> notes) {
		try {
			byte[] value = reader.readProperty(object, LoadControls.PID_TABLE_REFERENCE, 1, 1);
			if (value.length == 2 || value.length == 4) {
				return Integer.valueOf(new BigInteger(1, value).intValue());
			}
			notes.add("Table pointer of object " + object + ": " + value.length + " bytes.");
		} catch (BusException ex) {
			notes.add("Table pointer of object " + object + ": " + ex.getMessage());
		}
		return null;
	}

	private static boolean propertyMapped(Program program) {
		if (program.getMask() == null) {
			return false;
		}
		for (MaskFeature feature : program.getMask().getFeatures()) {
			if ("PropertyMappedLsms".equals(feature.getName()) && Integer.valueOf(1).equals(feature.getValue())) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Decodes what was read into the project's device: parameter values, connections and flags.
	 * Must be called within a transaction.
	 */
	public static Report apply(TLObject device, Memory memory) {
		Program program = DeviceConfiguration.program(device);
		List<String> problems = new ArrayList<>(memory.notes());
		Decoder decoder = new Decoder(device, program, memory);
		List<String> inferred = parametersWithoutMemory(decoder, links(memory, new ArrayList<>()).keySet(),
			unloaded(device), problems);
		for (Map.Entry<Parameter, String> failure : parameters(decoder, problems).entrySet()) {
			problems.add("Parameter " + failure.getKey().getName() + " (" + failure.getKey().getExtId() + "): "
				+ failure.getValue() + ".");
		}
		List<String> created = new ArrayList<>();
		Set<Integer> associated = connections(device, program, memory, created, problems);
		int flags = flags(device, program, memory, associated, problems);

		DeviceConfiguration configuration = new DeviceConfiguration(device);
		int connections = 0;
		for (TLObject connector : configuration.getConnectors()) {
			connections += ((Collection<?>) connector.tValueByName("connections")).size();
		}
		int settings = ((Collection<?>) device.tValueByName("settings")).size();

		// The proof: the image of the device as read back is what the device holds.
		DownloadImage image = DownloadImage.compute(device);
		problems.addAll(image.getProblems());
		int compared = 0;
		List<Integer> differences = new ArrayList<>();
		for (Block block : loadedBlocks(device, image)) {
			if (block.stateMachine() != null) {
				continue;
			}
			for (int n = 0; n < block.data().length; n++) {
				int bits = block.mask()[n] & 0xFF;
				Integer actual = memory.byteAt(block.address() + n);
				if (bits == 0 || actual == null) {
					continue;
				}
				compared++;
				if (((block.data()[n] ^ actual.intValue()) & bits) != 0) {
					differences.add(block.address() + n);
				}
			}
		}
		for (DownloadImage.PropertyWrite write : image.getPropertyWrites()) {
			byte[] actual = memory.properties().get(write.objectIndex() + "/" + write.propertyId());
			if (actual != null && !Arrays.equals(actual, write.value())) {
				problems.add("Property " + write.objectIndex() + "/" + write.propertyId() + " differs: device "
					+ FoundDevice.hex(actual) + ", project " + FoundDevice.hex(write.value()) + ".");
			}
		}
		return new Report(settings, connections, created, flags, inferred, problems, compared, differences);
	}

	/**
	 * What the memory read holds for a parameter: its bits, the value they decode to, or why the
	 * parameter cannot be decoded. Depends on the memory and the program only, so it is computed
	 * once per parameter.
	 *
	 * @param skip
	 *        Whether the parameter has nothing to decode: no place, no type, no value, or a place in
	 *        memory not loaded.
	 * @param failure
	 *        Why the parameter cannot be read, <code>null</code> if it can.
	 * @param size
	 *        The size of its values in bits.
	 * @param actual
	 *        The bits at its place.
	 * @param value
	 *        The value the bits decode to.
	 * @param valueFailure
	 *        Why the bits decode to no value, <code>null</code> if they do.
	 */
	private record Reading(boolean skip, String failure, int size, BigInteger actual, Object value,
			String valueFailure) {

		static final Reading SKIP = new Reading(true, null, 0, null, null, null);

		static Reading failure(String failure) {
			return new Reading(false, failure, 0, null, null, null);
		}
	}

	/**
	 * The decoding of the memory read from a device, kept over the many configurations the search for
	 * the values of the parameters without memory tries: the program's structure is read once (see
	 * {@link DeviceConfiguration.Structure}), each parameter's place in memory once, and a placement
	 * whose value was found to match the memory is not encoded again while it keeps that value.
	 */
	private static final class Decoder {

		final TLObject _device;

		final Program _program;

		final Memory _memory;

		final DeviceConfiguration.Structure _structure = new DeviceConfiguration.Structure();

		private final boolean _littleEndian;

		private final Map<Parameter, Reading> _readings = new HashMap<>();

		/** The value (ETS notation) of a placement whose encoding equals the memory. */
		private final Map<ParameterView, String> _verified = new HashMap<>();

		Decoder(TLObject device, Program program, Memory memory) {
			_device = device;
			_program = program;
			_memory = memory;
			_littleEndian = program.getParameterByteOrder() != null
				&& "LittleEndian".equals(program.getParameterByteOrder().getName());
		}

		DeviceConfiguration configuration() {
			return new DeviceConfiguration(_device, _structure);
		}

		Reading reading(Parameter parameter) {
			Reading result = _readings.get(parameter);
			if (result == null) {
				result = read(parameter);
				_readings.put(parameter, result);
			}
			return result;
		}

		private Reading read(Parameter parameter) {
			Location location = parameter.getAddress();
			ParameterType type = parameter.getType();
			if (location == null || type == null || type instanceof VoidType) {
				return Reading.SKIP;
			}
			int size = size(type);
			if (size <= 0) {
				return Reading.failure("the size of its type is not known");
			}
			BigInteger actual;
			try {
				actual = bits(location, parameter, size, _memory, _littleEndian);
			} catch (IllegalArgumentException ex) {
				return Reading.failure(ex.getMessage());
			}
			if (actual == null) {
				return unloadedMemory(location, parameter, _memory) ? Reading.SKIP
					: Reading.failure("its memory was not read");
			}
			try {
				Object value = decode(type, new ParameterEncoding.Bits(size, actual),
					ParameterEncoding.textEncoding(_program));
				return new Reading(false, null, size, actual, value, null);
			} catch (IllegalArgumentException ex) {
				return new Reading(false, null, size, actual, null, ex.getMessage());
			}
		}

		/**
		 * Whether the value of the given placement in the given configuration is the one in memory.
		 */
		boolean matches(DeviceConfiguration configuration, Parameter parameter, ParameterView view, Reading reading) {
			String text = configuration.value(view);
			if (text != null && text.equals(_verified.get(view))) {
				return true;
			}
			ParameterEncoding.Bits current;
			try {
				current = configuration.encode(view);
			} catch (IllegalArgumentException ex) {
				current = null;
			}
			if (current != null && current.size() == reading.size() && current.value().equals(reading.actual())) {
				if (text != null) {
					_verified.put(view, text);
				}
				return true;
			}
			return false;
		}
	}

	/**
	 * Decodes the values of the parameters in effect that lie in memory or properties, until the
	 * parameters in effect no longer change.
	 *
	 * @return Why parameters could not be decoded, by parameter.
	 */
	private static Map<Parameter, String> parameters(Decoder decoder, List<String> problems) {
		return decodeParameters(decoder, problems).failures();
	}

	/**
	 * The outcome of {@link ReadBack#decodeParameters(Decoder, List)}.
	 *
	 * @param failures
	 *        Why parameters could not be decoded, by parameter.
	 * @param configuration
	 *        The configuration of the device after the decoding; <code>null</code> if it is to be
	 *        computed again.
	 */
	private record Decoding(Map<Parameter, String> failures, DeviceConfiguration configuration) {
		// Value.
	}

	/**
	 * Decodes the values of the parameters in effect that lie in memory or properties, until the
	 * parameters in effect no longer change, see {@link #parameters(Decoder, List)}.
	 */
	private static Decoding decodeParameters(Decoder decoder, List<String> problems) {
		TLObject device = decoder._device;
		Map<Parameter, String> failures = new LinkedHashMap<>();
		DeviceConfiguration unchanged = null;
		for (int pass = 0; pass < MAX_PASSES; pass++) {
			DeviceConfiguration configuration = decoder.configuration();
			boolean changed = false;
			failures.clear();
			for (Map.Entry<Parameter, ParameterView> entry : configuration.getActiveViews().entrySet()) {
				Parameter parameter = entry.getKey();
				ParameterView view = entry.getValue();
				Reading reading = decoder.reading(parameter);
				if (reading.skip()) {
					continue;
				}
				if (reading.failure() != null) {
					failures.put(parameter, reading.failure());
					continue;
				}
				if (decoder.matches(configuration, parameter, view, reading)) {
					continue;
				}
				if (reading.valueFailure() != null) {
					failures.put(parameter, reading.valueFailure());
					continue;
				}
				try {
					DeviceSettings.restoreValue(device, view, reading.value());
					changed = true;
				} catch (TopLogicException ex) {
					failures.put(parameter, Resources.getInstance().getString(ex.getErrorKey()));
				}
			}
			// A switch may read the parameter through another placement than the one in effect first.
			for (Switch choice : configuration.getActiveSwitches()) {
				ParameterView input = configuration.input(choice);
				Parameter parameter = input == null ? null : configuration.parameter(input);
				ParameterView first = parameter == null ? null : configuration.getActiveViews().get(parameter);
				if (first == null || first == input || parameter.getAddress() == null) {
					continue;
				}
				String value = configuration.value(first);
				if (!java.util.Objects.equals(configuration.value(input), value)) {
					DeviceSettings.restoreValue(device, input, DeviceSettings.value(device, first));
					changed = true;
				}
			}
			if (!changed) {
				unchanged = configuration;
				break;
			}
			if (pass == MAX_PASSES - 1) {
				problems.add("The parameter values did not settle after " + MAX_PASSES + " passes.");
			}
		}

		// Settings of parameters no longer in effect.
		DeviceConfiguration configuration = unchanged != null ? unchanged : decoder.configuration();
		Set<Object> active = new HashSet<>(configuration.getActiveViews().keySet());
		for (Object setting : new ArrayList<>((Collection<?>) device.tValueByName("settings"))) {
			TLObject value = (TLObject) setting;
			if (!(ModuleParts.parameter(value) instanceof ParameterView view) || !active.contains(view.getParameter())) {
				value.tDelete();
				configuration = null;
			}
		}
		return new Decoding(failures, configuration);
	}

	/**
	 * Chooses the values of the parameters without memory that decide which parts of the program are
	 * in effect (ETS shows them to open pages and objects, the device never holds them): the value of
	 * such a switch input that explains the device best, that is with the fewest associated objects
	 * out of effect and the fewest bytes of the image differing from the memory read. A value that
	 * explains the device no better than the current one is not taken, so a parameter whose cases
	 * leave no trace in the device keeps its default.
	 *
	 * @return The values chosen, for a person.
	 */
	private static List<String> parametersWithoutMemory(Decoder decoder, Set<Integer> associated, Set<Integer> unloaded,
			List<String> problems) {
		TLObject device = decoder._device;
		Search search = new Search(decoder, associated, unloaded, problems);
		List<String> chosen = new ArrayList<>();
		Fit fit = search.placeObjects(search.evaluate(), chosen);
		for (int round = 0; round < MAX_PASSES && fit.score() > 0; round++) {
			Fit next = search.optimize(search.switches(), fit, 0, chosen);
			if (next.score() >= fit.score()) {
				break;
			}
			fit = next;
		}
		// The values chosen in the end (a move may have been undone by a later one).
		List<String> result = new ArrayList<>();
		for (Object element : (Collection<?>) device.tValueByName("settings")) {
			TLObject setting = (TLObject) element;
			if (ModuleParts.parameter(setting) instanceof ParameterView view && view.getParameter() != null
				&& view.getParameter().getAddress() == null) {
				result.add(view.getParameter().getName() + " (" + view.getParameter().getExtId() + ") = "
					+ ParameterValues.encode(setting));
			}
		}
		return result;
	}

	/**
	 * How well the device's configuration in the project explains the memory read.
	 *
	 * @param score
	 *        How badly: associated objects out of effect and parameters in effect whose memory holds
	 *        no value of their type weigh most, then each byte of the image (without the group
	 *        address and association tables, which follow the connections) differing from the
	 *        memory.
	 * @param mismatch
	 *        The addresses at which the image differs from the memory.
	 * @param missing
	 *        The numbers of the associated objects out of effect.
	 */
	private record Fit(int score, BitSet mismatch, Set<Integer> missing) {
		// Value.
	}

	/**
	 * The place of the object table of a device.
	 *
	 * @param address
	 *        Its first address.
	 * @param format
	 *        Its format.
	 * @param count
	 *        The number of objects.
	 */
	private record ObjectTable(int address, GroupTables.ObjectTableFormat format, int count) {

		static ObjectTable of(TLObject device, DownloadImage image) {
			Block table = image.getObjectTable();
			Program program = DeviceConfiguration.program(device);
			if (table == null || table.stateMachine() != null || program == null || program.getMask() == null) {
				return null;
			}
			for (MaskResource resource : program.getMask().getResources()) {
				if ("GroupObjectTable".equals(resource.getName())) {
					GroupTables.ObjectTableFormat format = GroupTables.ObjectTableFormat.byFlavour(resource.getFlavour());
					if (format != null) {
						return new ObjectTable(table.address(), format, table.data()[0] & 0xFF);
					}
				}
			}
			return null;
		}

		int typeAddress(int object) {
			return address + format.header() + format.descriptor() * object + format.type();
		}

		int configAddress(int object) {
			return address + format.header() + format.descriptor() * object + format.config();
		}

		boolean isConfig(int memoryAddress) {
			int offset = memoryAddress - address - format.header();
			return offset >= 0 && offset < format.descriptor() * count && offset % format.descriptor() == format.config();
		}

		int objectAt(int memoryAddress) {
			return (memoryAddress - address - format.header()) / format.descriptor();
		}
	}

	private static Fit fit(DeviceConfiguration configuration, Memory memory, Set<Integer> associated, int failures,
			Set<Integer> unloaded) {
		TLObject device = configuration.getDevice();
		Set<Integer> active = new HashSet<>();
		for (Port port : configuration.getActiveInterfaces().keySet()) {
			active.add(port.getIndex());
		}
		int score = failures * 10000;
		Set<Integer> missing = new HashSet<>();
		for (Integer object : associated) {
			if (!active.contains(object)) {
				missing.add(object);
				score += 10000;
			}
		}
		BitSet mismatch = new BitSet();
		DownloadImage image = DownloadImage.compute(configuration);
		Block objectTable = image.getObjectTable();
		ObjectTable table = ObjectTable.of(device, image);
		for (Block block : image.getBlocks()) {
			if (block.stateMachine() != null || unloaded.contains(block.address()) || block == image.getAddressTable()
				|| block == image.getAssociationTable()) {
				continue;
			}
			for (int n = 0; n < block.data().length; n++) {
				int address = block.address() + n;
				if (objectTable != null && objectTable.stateMachine() == null && objectTable.contains(address)) {
					// The configuration of an associated object follows its connections, made after the
					// search, its type byte the size of its placement (below); that of an object without
					// association is the one of its placement in effect.
					if (table == null || !table.isConfig(address) || associated.contains(table.objectAt(address))) {
						continue;
					}
					int bits = block.mask()[n] & 0xFF & ~GroupTables.COMMUNICATION;
					Integer actual = memory.byteAt(address);
					if (actual != null && ((block.data()[n] ^ actual.intValue()) & bits) != 0) {
						mismatch.set(address);
						score += 100;
					}
					continue;
				}
				int bits = block.mask()[n] & 0xFF;
				Integer actual = memory.byteAt(address);
				if (bits != 0 && actual != null && ((block.data()[n] ^ actual.intValue()) & bits) != 0) {
					mismatch.set(address);
					score++;
				}
			}
		}
		// The type (size) byte of an associated object is the size of its placement in effect.
		if (table != null) {
			for (Integer object : associated) {
				Port port = configuration.getActiveInterfaces().keySet().stream()
					.filter(p -> p.getIndex() == object.intValue()).findFirst().orElse(null);
				if (port == null || object.intValue() >= table.count()) {
					continue;
				}
				Interface placed = configuration.getActiveInterfaces().get(port);
				int bits = placed.getSize() != null ? placed.getSize() : port.getSize();
				int address = table.typeAddress(object.intValue());
				Integer actual = memory.byteAt(address);
				if (actual != null && actual.intValue() != GroupTables.objectType(bits)) {
					mismatch.set(address);
					score += 1000;
				}
			}
		}
		return new Fit(score, mismatch, missing);
	}

	/**
	 * A switch and the case taken on the way to a part of the program.
	 */
	private record Step(Switch choice, Case alternative) {
		// Value.
	}

	/**
	 * The search for the values of the parameters without memory, see
	 * {@link ReadBack#parametersWithoutMemory(Decoder, Set, Set, List)}.
	 *
	 * <p>
	 * Only a value whose case holds evidence is tried: an associated object out of effect, or a
	 * parameter whose memory differs from the image (somewhere below the case, also in the cases of
	 * switches within it). A device the project explains completely is not searched at all.
	 * </p>
	 */
	private static final class Search {

		/** How deep the search follows the switches a value opens. */
		private static final int MAX_DEPTH = 3;

		/** The number of ways to a placement the search tries. */
		private static final int MAX_PATHS = 4;

		private final Decoder _decoder;

		private final TLObject _device;

		private final Program _program;

		private final Memory _memory;

		/** The communication objects of the program. */
		private final List<Port> _ports;

		/** The communication objects of the program by their numbers (the first of a number). */
		private final Map<Integer, Port> _portByNumber = new HashMap<>();

		/** The numbers of the communication objects. */
		private final Map<Port, Integer> _numbers = new HashMap<>();

		private final Set<Integer> _associated;

		private final Set<Integer> _unloaded;

		private final List<String> _problems;

		private DeviceConfiguration _configuration;

		private final ObjectTable _objectTable;

		private Map<Interface, List<List<Step>>> _paths;

		/** Whether the parameters with memory are decoded for the current values. */
		private boolean _decoded;

		/** The number of parameters in effect whose memory holds no value of their type, when decoded. */
		private int _failures;

		Search(Decoder decoder, Set<Integer> associated, Set<Integer> unloaded, List<String> problems) {
			_decoder = decoder;
			_device = decoder._device;
			_program = decoder._program;
			_memory = decoder._memory;
			_associated = associated;
			_unloaded = unloaded;
			_problems = problems;
			_objectTable = ObjectTable.of(_device, DownloadImage.compute(configuration()));
			_ports = new ArrayList<>(_program.getPorts());
			for (Port port : _ports) {
				Integer number = Integer.valueOf(port.getIndex());
				_numbers.put(port, number);
				_portByNumber.putIfAbsent(number, port);
			}
		}

		private DeviceConfiguration configuration() {
			if (_configuration == null) {
				_configuration = _decoder.configuration();
			}
			return _configuration;
		}

		List<Switch> switches() {
			return new ArrayList<>(configuration().getActiveSwitches());
		}

		/**
		 * Decodes the parameters with memory for the current values and scores the outcome.
		 */
		Fit evaluate() {
			decode();
			return fit(configuration(), _memory, _associated, _failures, _unloaded);
		}

		/**
		 * Chooses, one switch after the other, the value of its input that explains the device best;
		 * a value opening further switches is scored with the best values of these.
		 *
		 * @return The fit of the values chosen.
		 */
		Fit optimize(List<Switch> switches, Fit start, int depth, List<String> chosen) {
			Fit best = start;
			for (Switch choice : switches) {
				if (best.score() == 0) {
					break;
				}
				ParameterView input = choice.getInput();
				if (input == null || input.getParameter() == null || input.getParameter().getAddress() != null
					|| !(input.getParameter().getType() instanceof EnumerationType type)
					|| !switches().contains(choice)) {
					continue;
				}
				Case selected = configuration().select(choice);
				Map<ConfigFragment, Boolean> evidence = new HashMap<>();
				Object current = DeviceSettings.value(_device, input);
				Map<ParameterView, Object> before = snapshot();
				Map<ParameterView, Object> bestState = null;
				List<String> bestChosen = null;
				for (EnumerationValue entry : type.getValues()) {
					if (entry == current) {
						continue;
					}
					Case target = caseFor(choice, Integer.toString(entry.getValue()));
					if (target == selected
						|| !(target != null && hasEvidence(target, best, evidence)
							|| selected != null && hasEvidence(selected, best, evidence))) {
						continue;
					}
					List<String> nested = new ArrayList<>();
					nested.add(input.getParameter().getName() + " (" + input.getParameter().getExtId() + ") = "
						+ entry.getValue());
					Fit fit = tryValue(input, entry, depth, nested);
					if (fit.score() < best.score()) {
						best = fit;
						bestState = snapshot();
						bestChosen = nested;
					}
					restore(before);
				}
				if (bestState != null) {
					restore(bestState);
					chosen.addAll(bestChosen);
				}
			}
			return best;
		}

		private Fit tryValue(ParameterView input, Object entry, int depth, List<String> chosen) {
			Set<Switch> before = new HashSet<>(switches());
			DeviceSettings.restoreValue(_device, input, entry);
			_configuration = null;
			_decoded = false;
			Fit fit = evaluate();
			List<Switch> opened = new ArrayList<>(switches());
			opened.removeAll(before);
			if (depth < MAX_DEPTH && !opened.isEmpty()) {
				fit = optimize(opened, fit, depth + 1, chosen);
			}
			return fit;
		}

		/**
		 * Brings the associated objects into effect with a placement of the size the device's object
		 * table gives them: for an object out of effect or in effect with another size, a placement
		 * of that size is put into effect by choosing the cases on its way through the structure
		 * (values of switch inputs without memory), and a placement of another size in effect before
		 * it is put out of effect by choosing another case above it; the other objects that drop out
		 * or change size by that are placed again. A move is kept if it explains the device better.
		 */
		Fit placeObjects(Fit start, List<String> chosen) {
			Fit fit = start;
			for (int round = 0; round < MAX_PASSES; round++) {
				Fit before = fit;
				fit = placeEach(fit, chosen, false);
				fit = placeEach(fit, chosen, true);
				if (fit.score() >= before.score()) {
					break;
				}
			}
			return fit;
		}

		/**
		 * Tries for each object out of place a placement that fits it, alone or (cascading) with the
		 * other objects the move puts out of place placed again.
		 */
		private Fit placeEach(Fit start, List<String> chosen, boolean cascade) {
			Fit fit = start;
			for (Integer object : badObjects(fit)) {
				Port port = port(object);
				if (fits(placed(port), fit)) {
					// Placed by an earlier move.
					continue;
				}
				boolean done = false;
				for (Interface candidate : candidates(port, fit)) {
					for (List<Step> path : paths(candidate)) {
						Map<ParameterView, Object> before = snapshot();
						List<String> moves = new ArrayList<>();
						if (place(port, candidate, path, moves)) {
							if (cascade) {
								for (Integer other : badObjects(fit)) {
									if (other.intValue() != object.intValue()) {
										placeAny(other, port, candidate, moves, fit);
									}
								}
							}
							Fit next = evaluate();
							if (next.score() < fit.score()) {
								fit = next;
								chosen.addAll(moves);
								done = true;
								break;
							}
						}
						restore(before);
					}
					if (done) {
						break;
					}
				}
			}
			return fit;
		}

		/**
		 * Places an object with any placement of its size, keeping the given placement of another
		 * object in effect; leaves everything as it was if that is not possible.
		 */
		private void placeAny(Integer object, Port keepPort, Interface keep, List<String> moves, Fit fit) {
			Port port = port(object);
			if (fits(placed(port), fit)) {
				return;
			}
			for (Interface candidate : candidates(port, fit)) {
				for (List<Step> path : paths(candidate)) {
					Map<ParameterView, Object> before = snapshot();
					List<String> tried = new ArrayList<>();
					if (place(port, candidate, path, tried) && placed(keepPort) == keep) {
						moves.addAll(tried);
						return;
					}
					restore(before);
				}
			}
		}

		private Interface placed(Port port) {
			decode();
			return port == null ? null : configuration().getActiveInterfaces().get(port);
		}

		private static boolean enabled(String flag, Interface placed, Port port) {
			return "Enabled".equals(classifier(flag, placed, port));
		}

		private static String classifier(String attribute, Interface placed, Port port) {
			for (TLObject holder : List.of(placed, port)) {
				if (holder.tValueByName(attribute) instanceof TLClassifier value) {
					return value.getName();
				}
			}
			return null;
		}

		/**
		 * The objects out of place: associated objects out of effect or in effect with a size other
		 * than the device's object table gives them, and objects without association whose
		 * configuration byte differs from the device's.
		 */
		private List<Integer> badObjects(Fit fit) {
			decode();
			List<Integer> result = new ArrayList<>();
			for (Port port : _ports) {
				int object = _numbers.get(port).intValue();
				boolean associated = _associated.contains(object);
				boolean configDiffers = _objectTable != null && object < _objectTable.count()
					&& fit.mismatch().get(_objectTable.configAddress(object));
				if (associated || configDiffers) {
					if (!fits(placed(port), fit)) {
						result.add(object);
					}
				}
			}
			result.sort(null);
			return result;
		}

		/**
		 * The placements of a port that fit the device.
		 */
		private List<Interface> candidates(Port port, Fit fit) {
			List<Interface> result = new ArrayList<>();
			if (port != null) {
				for (Interface placed : port.getInterfaces()) {
					if (fits(placed, fit)) {
						result.add(placed);
					}
				}
				result.sort(java.util.Comparator.comparing(i -> String.valueOf(i.getExtId())));
			}
			return result;
		}

		/**
		 * Whether a placement fits the device: for an associated object the size the device's object
		 * table gives it, for an object without association its configuration byte (but the
		 * communication flag).
		 */
		private boolean fits(Interface placed, Fit fit) {
			if (placed == null) {
				return false;
			}
			Port port = placed.getPort();
			if (_objectTable == null || port == null || port.getIndex() >= _objectTable.count()) {
				return true;
			}
			int object = port.getIndex();
			if (_associated.contains(object)) {
				Integer actual = _memory.byteAt(_objectTable.typeAddress(object));
				int bits = placed.getSize() != null ? placed.getSize() : port.getSize();
				return actual == null || actual.intValue() == GroupTables.objectType(bits);
			}
			Integer actual = _memory.byteAt(_objectTable.configAddress(object));
			if (actual == null) {
				return true;
			}
			int config = GroupTables.objectConfig(actual.intValue(), enabled("update", placed, port),
				enabled("transmission", placed, port), enabled("canWrite", placed, port),
				enabled("canRead", placed, port), false, GroupTables.priorityCode(classifier("priority", placed, port)),
				false);
			return ((config ^ actual.intValue()) & ~GroupTables.COMMUNICATION & 0xFF) == 0;
		}

		private Port port(int object) {
			return _portByNumber.get(Integer.valueOf(object));
		}

		/**
		 * Puts the given placement of a port into effect along the given way through the structure.
		 */
		private boolean place(Port port, Interface candidate, List<Step> path, List<String> moves) {
			// The values without memory on the way are set at once, then decoded once: a switch with
			// memory takes its case by the values decoded.
			for (Step step : path) {
				ParameterView input = step.choice().getInput();
				if (!withoutMemory(input)) {
					continue;
				}
				EnumerationValue entry = entry(step);
				if (entry == null) {
					return false;
				}
				String current = ParameterValues.encode(DeviceConfiguration.valueHolder(_device, input));
				if (caseFor(step.choice(), current) != step.alternative()) {
					set(input, entry, moves);
				}
			}
			decode();
			for (Step step : path) {
				if (configuration().select(step.choice()) != step.alternative()) {
					return false;
				}
			}
			for (int attempt = 0; attempt < MAX_DEPTH * 2; attempt++) {
				decode();
				Interface now = configuration().getActiveInterfaces().get(port);
				if (candidate.equals(now)) {
					return true;
				}
				if (now == null || !unblock(now, moves)) {
					return false;
				}
			}
			decode();
			return candidate.equals(configuration().getActiveInterfaces().get(port));
		}

		/**
		 * Whether the given switch input is a parameter without memory, whose value the search chooses.
		 */
		private static boolean withoutMemory(ParameterView input) {
			return input != null && input.getParameter() != null && input.getParameter().getAddress() == null
				&& input.getParameter().getType() instanceof EnumerationType;
		}

		/**
		 * The value of the input of the switch of a step (without memory) that takes the case of the
		 * step, <code>null</code> if none does.
		 */
		private static EnumerationValue entry(Step step) {
			EnumerationType type = (EnumerationType) step.choice().getInput().getParameter().getType();
			for (EnumerationValue entry : type.getValues()) {
				if (caseFor(step.choice(), Integer.toString(entry.getValue())) == step.alternative()) {
					return entry;
				}
			}
			return null;
		}

		/**
		 * Puts a placement out of effect: the innermost switch without memory on its way takes another
		 * case.
		 */
		private boolean unblock(Interface placed, List<String> moves) {
			DeviceConfiguration configuration = configuration();
			for (List<Step> path : paths().getOrDefault(placed, List.of())) {
				if (!path.stream().allMatch(step -> configuration.select(step.choice()) == step.alternative())) {
					continue;
				}
				for (int n = path.size() - 1; n >= 0; n--) {
					Step step = path.get(n);
					ParameterView input = step.choice().getInput();
					if (input == null || input.getParameter() == null || input.getParameter().getAddress() != null
						|| !(input.getParameter().getType() instanceof EnumerationType type)) {
						continue;
					}
					for (EnumerationValue entry : type.getValues()) {
						Case target = caseFor(step.choice(), Integer.toString(entry.getValue()));
						if (target != step.alternative()) {
							set(input, entry, moves);
							return true;
						}
					}
				}
			}
			return false;
		}

		private void set(ParameterView input, EnumerationValue entry, List<String> moves) {
			DeviceSettings.restoreValue(_device, input, entry);
			_configuration = null;
			_decoded = false;
			moves.add(input.getParameter().getName() + " (" + input.getParameter().getExtId() + ") = " + entry.getValue());
		}

		/**
		 * Decodes the parameters with memory for the current values (without scoring).
		 */
		private void decode() {
			if (_decoded) {
				return;
			}
			Decoding decoding = decodeParameters(_decoder, _problems);
			_failures = decoding.failures().size();
			_configuration = decoding.configuration();
			_decoded = true;
		}

		/**
		 * The ways through the structure to each placement of a communication object: the switches
		 * and the cases taken.
		 */
		private Map<Interface, List<List<Step>>> paths() {
			if (_paths == null) {
				_paths = new HashMap<>();
				collectPaths(_program, new ArrayList<>());
			}
			return _paths;
		}

		/**
		 * The ways to a placement whose switches with memory take the case of the way already (only
		 * those can be put into effect by choosing values without memory), at most
		 * {@value #MAX_PATHS}.
		 */
		private List<List<Step>> paths(Interface placed) {
			decode();
			DeviceConfiguration configuration = configuration();
			List<List<Step>> result = new ArrayList<>();
			for (List<Step> path : paths().getOrDefault(placed, List.of())) {
				boolean possible = true;
				for (Step step : path) {
					ParameterView input = step.choice().getInput();
					boolean withoutMemory = input != null && input.getParameter() != null
						&& input.getParameter().getAddress() == null
						&& input.getParameter().getType() instanceof EnumerationType;
					if (!withoutMemory && configuration.select(step.choice()) != step.alternative()) {
						possible = false;
						break;
					}
				}
				if (possible) {
					result.add(path);
					if (result.size() == MAX_PATHS) {
						break;
					}
				}
			}
			return result;
		}

		private void collectPaths(ConfigFragment fragment, List<Step> stack) {
			for (Option content : fragment.getContents()) {
				Option option = content instanceof SharedPlacement shared ? shared.getPlaced() : content;
				if (option instanceof Interface placed) {
					_paths.computeIfAbsent(placed, x -> new ArrayList<>()).add(new ArrayList<>(stack));
				} else if (option instanceof Switch choice) {
					for (Case alternative : choice.getAlternatives()) {
						stack.add(new Step(choice, alternative));
						collectPaths(alternative, stack);
						stack.remove(stack.size() - 1);
					}
				} else if (option instanceof ConfigFragment inner) {
					collectPaths(inner, stack);
				}
			}
		}

		/**
		 * The case of a switch the given value selects.
		 */
		private static Case caseFor(Switch choice, String value) {
			Case fallback = null;
			for (Case alternative : choice.getAlternatives()) {
				if (alternative.getPattern() != null && DeviceConfiguration.matches(alternative.getPattern(), value)) {
					return alternative;
				}
				if (alternative.getIsDefault() && fallback == null) {
					fallback = alternative;
				}
			}
			return fallback;
		}

		/**
		 * Whether a part of the program holds an associated object out of effect or a parameter
		 * whose memory differs from the image.
		 */
		private boolean hasEvidence(ConfigFragment fragment, Fit fit, Map<ConfigFragment, Boolean> cache) {
			Boolean known = cache.get(fragment);
			if (known != null) {
				return known.booleanValue();
			}
			cache.put(fragment, Boolean.FALSE);
			boolean result = false;
			for (Option content : fragment.getContents()) {
				Option option = content instanceof SharedPlacement shared ? shared.getPlaced() : content;
				if (option instanceof ParameterView view) {
					result = differs(view.getParameter(), fit.mismatch());
				} else if (option instanceof Interface placed) {
					int object = placed.getPort() == null ? -1 : placed.getPort().getIndex();
					result = fit.missing().contains(object) || _objectTable != null && object >= 0
						&& object < _objectTable.count() && fit.mismatch().get(_objectTable.typeAddress(object));
				} else if (option instanceof Switch inner) {
					for (Case alternative : inner.getAlternatives()) {
						if (hasEvidence(alternative, fit, cache)) {
							result = true;
							break;
						}
					}
				} else if (option instanceof ConfigFragment inner) {
					result = hasEvidence(inner, fit, cache);
				}
				if (result) {
					break;
				}
			}
			cache.put(fragment, Boolean.valueOf(result));
			return result;
		}

		private static boolean differs(Parameter parameter, BitSet mismatch) {
			if (parameter == null || !(parameter.getAddress() instanceof Pointer pointer)
				|| !(pointer.getSegment() instanceof de.haumacher.ehome.model.operational.CodeSegment segment)
				|| segment.getAddress() == null || parameter.getType() == null) {
				return false;
			}
			int size = size(parameter.getType());
			if (size <= 0) {
				return false;
			}
			int byteOffset = pointer.getByteOffset() + value(parameter.getUnionOffset());
			int bitOffset = pointer.getBitOffset() + value(parameter.getUnionBitOffset());
			int start = segment.getAddress() + byteOffset + bitOffset / 8;
			int end = segment.getAddress() + (byteOffset * 8 + bitOffset + size + 7) / 8;
			int next = mismatch.nextSetBit(start);
			return next >= 0 && next < end;
		}

		/**
		 * The values the device sets, by placement.
		 */
		private Map<ParameterView, Object> snapshot() {
			Map<ParameterView, Object> result = new LinkedHashMap<>();
			for (Object element : (Collection<?>) _device.tValueByName("settings")) {
				TLObject setting = (TLObject) element;
				if (ModuleParts.parameter(setting) instanceof ParameterView view) {
					result.put(view, ParameterValues.value(setting));
				}
			}
			return result;
		}

		/**
		 * Sets the values of the device back to a {@link #snapshot()}.
		 */
		private void restore(Map<ParameterView, Object> state) {
			Map<ParameterView, TLObject> now = new HashMap<>();
			List<TLObject> removed = new ArrayList<>();
			for (Object element : (Collection<?>) _device.tValueByName("settings")) {
				TLObject setting = (TLObject) element;
				if (ModuleParts.parameter(setting) instanceof ParameterView view) {
					if (state.containsKey(view)) {
						now.put(view, setting);
					} else {
						removed.add(setting);
					}
				}
			}
			DeviceSettings.removeSettings(_device, removed);
			for (Map.Entry<ParameterView, Object> entry : state.entrySet()) {
				TLObject setting = now.get(entry.getKey());
				Object value = setting == null ? null : ParameterValues.value(setting);
				if (!java.util.Objects.equals(value, entry.getValue())) {
					DeviceSettings.restoreValue(_device, entry.getKey(), entry.getValue());
				}
			}
			_configuration = null;
			_decoded = false;
		}
	}

	/**
	 * The size in bits of the values of a parameter type, as {@link ParameterEncoding} writes them.
	 */
	static int size(ParameterType type) {
		if (type instanceof EnumerationType enumeration) {
			return enumeration.getBits();
		}
		if (type instanceof NumericType numeric) {
			return numeric.getBits();
		}
		if (type instanceof TimeType time) {
			return time.getBits();
		}
		if (type instanceof TextType text) {
			return text.getBits();
		}
		if (type instanceof FloatingPointType floating) {
			String encoding = floating.getEncoding() == null ? "DPT9" : floating.getEncoding().getName();
			return switch (encoding) {
				case "IEEE754Single" -> 32;
				case "IEEE754Double" -> 64;
				default -> 16;
			};
		}
		if (type instanceof DateType) {
			return 24;
		}
		if (type instanceof IPAddressType) {
			return 32;
		}
		return -1;
	}

	/**
	 * The bits of a parameter in the memory or property read, <code>null</code> if they were not
	 * read.
	 */
	private static BigInteger bits(Location location, Parameter parameter, int size, Memory memory, boolean littleEndian) {
		int byteOffset = location.getByteOffset() + value(parameter.getUnionOffset());
		int bitOffset = location.getBitOffset() + value(parameter.getUnionBitOffset());
		if (location instanceof Pointer pointer) {
			if (!(pointer.getSegment() instanceof de.haumacher.ehome.model.operational.CodeSegment segment)
				|| segment.getAddress() == null) {
				throw new IllegalArgumentException("its segment has no absolute address");
			}
			int start = segment.getAddress() + byteOffset;
			int end = segment.getAddress() + (byteOffset * 8 + bitOffset + size + 7) / 8;
			for (int address = start; address < end; address++) {
				if (!memory.known().get(address)) {
					return null;
				}
			}
			return read(memory.memory(), segment.getAddress() * 8 + byteOffset * 8 + bitOffset, size, littleEndian);
		}
		if (location instanceof PropertyLocation property) {
			byte[] value = memory.properties().get(property.getObjectIndex() + "/" + property.getPropertyId());
			if (value == null || (byteOffset * 8 + bitOffset + size + 7) / 8 > value.length) {
				return null;
			}
			return read(value, byteOffset * 8 + bitOffset, size, littleEndian);
		}
		throw new IllegalArgumentException("its location is not supported");
	}

	/**
	 * Whether a parameter lies in memory a download does not write.
	 */
	private static boolean unloadedMemory(Location location, Parameter parameter, Memory memory) {
		return location instanceof Pointer pointer
			&& pointer.getSegment() instanceof de.haumacher.ehome.model.operational.CodeSegment segment
			&& segment.getAddress() != null
			&& memory.unloaded().get(segment.getAddress() + location.getByteOffset() + value(parameter.getUnionOffset()));
	}

	/**
	 * Reads a value of the given size at the given bit (counted from the most significant bit of the
	 * first byte), the inverse of {@link ParameterEncoding#write(byte[], int, int, ParameterEncoding.Bits, boolean)}.
	 */
	static BigInteger read(byte[] memory, int start, int size, boolean littleEndian) {
		BigInteger result = BigInteger.ZERO;
		for (int n = 0; n < size; n++) {
			int bit = start + n;
			if ((memory[bit / 8] & (0x80 >>> (bit % 8))) != 0) {
				result = result.setBit(size - 1 - n);
			}
		}
		if (littleEndian && size > 8) {
			byte[] bytes = new ParameterEncoding.Bits(size, result).toBytes();
			for (int n = 0; n < bytes.length / 2; n++) {
				byte tmp = bytes[n];
				bytes[n] = bytes[bytes.length - 1 - n];
				bytes[bytes.length - 1 - n] = tmp;
			}
			result = new BigInteger(1, bytes);
		}
		return result;
	}

	/**
	 * The value a parameter of the given type has in the given bits, as
	 * {@link DeviceSettings#restoreValue(TLObject, ParameterView, Object)} takes it.
	 *
	 * @throws IllegalArgumentException
	 *         If the bits are no value of the type.
	 */
	static Object decode(ParameterType type, ParameterEncoding.Bits bits, Charset textEncoding) {
		BigInteger value = bits.value();
		int size = bits.size();
		if (type instanceof EnumerationType enumeration) {
			boolean binary = enumeration.getKind() != null && "BinaryValue".equals(enumeration.getKind().getName());
			for (EnumerationValue entry : enumeration.getValues()) {
				if (binary && entry.getBinary() != null) {
					if (Arrays.equals(bits.toBytes(), ParameterEncoding.bytes(ParameterEncoding.toBytes(entry.getBinary()), size).toBytes())) {
						return entry;
					}
				} else if (BigInteger.valueOf(entry.getValue()).equals(value)) {
					return entry;
				}
			}
			throw new IllegalArgumentException("the value " + value + " is no entry of " + type.getName());
		}
		if (type instanceof NumericType numeric) {
			boolean signed = numeric.getKind() != null && "signedInt".equals(numeric.getKind().getName());
			BigInteger result = signed && value.testBit(size - 1) ? value.subtract(BigInteger.ONE.shiftLeft(size)) : value;
			return Long.valueOf(result.longValue());
		}
		if (type instanceof TimeType) {
			return Long.valueOf(value.longValue());
		}
		if (type instanceof FloatingPointType) {
			long raw = value.longValue();
			return switch (size) {
				case 32 -> Double.valueOf(Float.intBitsToFloat((int) raw));
				case 64 -> Double.valueOf(Double.longBitsToDouble(raw));
				default -> Double.valueOf(dpt9(raw));
			};
		}
		byte[] bytes = bits.toBytes();
		if (type instanceof TextType) {
			int end = 0;
			while (end < bytes.length && bytes[end] != 0) {
				end++;
			}
			return new String(bytes, 0, end, textEncoding);
		}
		if (type instanceof DateType) {
			int year = bytes[2] & 0xFF;
			try {
				return LocalDate.of(year >= 90 ? 1900 + year : 2000 + year, bytes[1] & 0xFF, bytes[0] & 0xFF).toString();
			} catch (DateTimeException ex) {
				throw new IllegalArgumentException("no date: " + FoundDevice.hex(bytes), ex);
			}
		}
		if (type instanceof IPAddressType) {
			try {
				return InetAddress.getByAddress(bytes).getHostAddress();
			} catch (UnknownHostException ex) {
				throw new IllegalArgumentException("no IP address: " + FoundDevice.hex(bytes), ex);
			}
		}
		throw new IllegalArgumentException("its type " + type.getName() + " is not supported");
	}

	private static double dpt9(long raw) {
		int exponent = (int) (raw >>> 11 & 0x0F);
		long mantissa = raw & 0x7FF;
		if ((raw & 0x8000) != 0) {
			mantissa -= 2048;
		}
		return Math.round(mantissa * (1 << exponent)) / 100.0;
	}

	/**
	 * Connects the device's communication objects as the tables read say.
	 *
	 * @return The numbers of the objects with an association.
	 */
	private static Set<Integer> connections(TLObject device, Program program, Memory memory, List<String> created,
			List<String> problems) {
		Set<Integer> associated = new HashSet<>();
		if (memory.addressTable() == null || memory.associationTable() == null) {
			return associated;
		}
		Map<Integer, List<Integer>> links = links(memory, problems);
		associated.addAll(links.keySet());

		DeviceConfiguration configuration = new DeviceConfiguration(device);
		Map<Integer, Port> ports = new HashMap<>();
		for (Port port : program.getPorts()) {
			ports.put(port.getIndex(), port);
		}
		TLObject project = project(device);
		Map<Integer, TLObject> groupAddresses = new HashMap<>();
		for (TLObject address : DeviceSettings.groupAddresses(device)) {
			if (address.tValueByName("address") instanceof GA ga) {
				groupAddresses.putIfAbsent(Integer.valueOf(ga.getValue()), address);
			}
		}
		TLClassifier send = (TLClassifier) TLModelUtil.findPart("ehome.physical:ConnectionType#send");
		TLClassifier receive = (TLClassifier) TLModelUtil.findPart("ehome.physical:ConnectionType#receive");

		// Remove the connections the tables do not hold.
		Set<String> wanted = new HashSet<>();
		for (Map.Entry<Integer, List<Integer>> entry : links.entrySet()) {
			for (int k = 0; k < entry.getValue().size(); k++) {
				wanted.add(entry.getKey() + "/" + entry.getValue().get(k) + "/" + (k == 0 ? "send" : "receive"));
			}
		}
		Set<String> existing = new HashSet<>();
		for (TLObject connector : configuration.getConnectors()) {
			Port port = ModuleParts.port(connector) instanceof Interface placed ? placed.getPort() : null;
			for (Object element : new ArrayList<>((Collection<?>) connector.tValueByName("connections"))) {
				TLObject connection = (TLObject) element;
				String key = (port == null ? "?" : port.getIndex()) + "/"
					+ (connection.tValueByName("target") instanceof TLObject target
						&& target.tValueByName("address") instanceof GA ga ? ga.getValue() : -1)
					+ "/" + InstallationBuilder.name(connection.tValueByName("kind"));
				if (wanted.contains(key) && existing.add(key)) {
					continue;
				}
				DeviceSettings.disconnect(connection);
			}
		}

		for (Map.Entry<Integer, List<Integer>> entry : links.entrySet()) {
			Port port = ports.get(entry.getKey());
			Interface placed = port == null ? null : configuration.getActiveInterfaces().get(port);
			if (placed == null) {
				problems.add("Object " + entry.getKey() + " is associated, but not in effect with the parameters read.");
				continue;
			}
			List<Integer> addresses = entry.getValue();
			for (int k = 0; k < addresses.size(); k++) {
				String kind = k == 0 ? "send" : "receive";
				if (existing.contains(entry.getKey() + "/" + addresses.get(k) + "/" + kind)) {
					continue;
				}
				TLObject groupAddress = groupAddresses.get(addresses.get(k));
				if (groupAddress == null) {
					groupAddress = InstallationBuilder.groupAddress(project, addresses.get(k));
					groupAddresses.put(addresses.get(k), groupAddress);
					created.add(String.valueOf(groupAddress.tValueByName("address")));
				}
				try {
					DeviceSettings.connect(configuration, placed, groupAddress, k == 0 ? send : receive);
				} catch (TopLogicException ex) {
					problems.add("Object " + entry.getKey() + ": " + Resources.getInstance().getString(ex.getErrorKey()));
				}
			}
		}
		return associated;
	}

	/**
	 * The associations of the tables read: per object number the group addresses, in the order of
	 * the association table (the first one the object sends to).
	 */
	private static Map<Integer, List<Integer>> links(Memory memory, List<String> problems) {
		Map<Integer, List<Integer>> links = new LinkedHashMap<>();
		if (memory.addressTable() == null || memory.associationTable() == null) {
			return links;
		}
		byte[] bytes = memory.memory();
		int addressStart = memory.addressTable().intValue();
		int addressCount = bytes[addressStart] & 0xFF;
		int associationStart = memory.associationTable().intValue();
		int associationCount = bytes[associationStart] & 0xFF;
		for (int n = 0; n < associationCount; n++) {
			int index = bytes[associationStart + 1 + 2 * n] & 0xFF;
			int object = bytes[associationStart + 2 + 2 * n] & 0xFF;
			if (index == 0 || index >= addressCount) {
				problems.add("Association " + (n + 1) + " names the address " + index + " of " + (addressCount - 1)
					+ ", which the address table does not hold.");
				continue;
			}
			int value = (bytes[addressStart + 1 + 2 * index] & 0xFF) << 8 | (bytes[addressStart + 2 + 2 * index] & 0xFF);
			links.computeIfAbsent(object, x -> new ArrayList<>()).add(value);
		}
		return links;
	}

	/**
	 * Sets the flags of the objects whose configuration byte in the device differs from the
	 * project's.
	 *
	 * @return The number of objects whose flags were set.
	 */
	private static int flags(TLObject device, Program program, Memory memory, Set<Integer> associated,
			List<String> problems) {
		DownloadImage image = DownloadImage.compute(device);
		Block table = image.getObjectTable();
		if (table == null || table.stateMachine() != null) {
			return 0;
		}
		GroupTables.ObjectTableFormat format = null;
		for (MaskResource resource : program.getMask().getResources()) {
			if ("GroupObjectTable".equals(resource.getName())) {
				format = GroupTables.ObjectTableFormat.byFlavour(resource.getFlavour());
			}
		}
		if (format == null) {
			return 0;
		}
		DeviceConfiguration configuration = new DeviceConfiguration(device);
		Map<Integer, Port> ports = new HashMap<>();
		for (Port port : program.getPorts()) {
			ports.put(port.getIndex(), port);
		}
		int count = table.data()[0] & 0xFF;
		int result = 0;
		for (int number = 0; number < count; number++) {
			int offset = format.header() + format.descriptor() * number + format.config();
			Integer actual = memory.byteAt(table.address() + offset);
			if (actual == null || ((table.data()[offset] ^ actual.intValue()) & table.mask()[offset] & 0xFF) == 0) {
				continue;
			}
			Port port = ports.get(number);
			Interface placed = port == null ? null : configuration.getActiveInterfaces().get(port);
			if (placed == null) {
				problems.add("Object " + number + " has other flags in the device, but is not in effect.");
				continue;
			}
			TLObject connector = connector(device, configuration, placed);
			int config = actual.intValue();
			connector.tUpdateByName("update", enablement(config, GroupTables.UPDATE));
			connector.tUpdateByName("transmission", enablement(config, GroupTables.TRANSMIT));
			connector.tUpdateByName("canWrite", enablement(config, GroupTables.WRITE));
			connector.tUpdateByName("canRead", enablement(config, GroupTables.READ));
			if (associated.contains(number)) {
				connector.tUpdateByName("communication", enablement(config, GroupTables.COMMUNICATION));
			}
			String priority = switch (config & 0x03) {
				case 1 -> "High";
				case 2 -> "Alert";
				case 3 -> "Low";
				default -> null;
			};
			if (priority == null) {
				problems.add("Object " + number + " has the priority code 0 (system), which a project cannot express.");
			} else {
				connector.tUpdateByName("priority", TLModelUtil.findPart("ehome.physical:Priority#" + priority));
			}
			device.tUpdateByName("lastModified", new java.util.Date());
			result++;
		}
		return result;
	}

	private static TLObject connector(TLObject device, DeviceConfiguration configuration, Interface placed) {
		for (TLObject connector : configuration.getConnectors()) {
			if (ModuleParts.port(connector) instanceof Interface other && other.getPort().equals(placed.getPort())) {
				return connector;
			}
		}
		TLObject connector = InstallationBuilder.create("ehome.physical:Connector");
		ModuleParts.bind(connector, ModuleParts.PORT, placed);
		connector.tUpdateByName("active", Boolean.TRUE);
		InstallationBuilder.add(device, "connectors", connector);
		return connector;
	}

	private static Object enablement(int config, int bit) {
		return TLModelUtil.findPart("ehome.common:Enablement#" + ((config & bit) != 0 ? "Enabled" : "Disabled"));
	}

	private static TLObject project(TLObject obj) {
		TLObject root = obj;
		while (root.tContainer() != null) {
			root = root.tContainer();
		}
		return root;
	}

	private static int value(Integer value) {
		return value == null ? 0 : value.intValue();
	}

}
