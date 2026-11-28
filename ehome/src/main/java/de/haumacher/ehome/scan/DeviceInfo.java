/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.scan;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.top_logic.basic.util.ResKey;
import com.top_logic.model.TLObject;
import com.top_logic.util.Resources;

import de.haumacher.ehome.knx.ExtIds;
import de.haumacher.ehome.model.catalog.Manufacturer;
import de.haumacher.ehome.model.catalog.Product;
import de.haumacher.ehome.model.catalog.Variant;
import de.haumacher.ehome.model.operational.MaskVersion;
import de.haumacher.ehome.model.operational.Program;
import de.haumacher.ehome.model.physical.BusDevice;
import de.haumacher.ehome.model.physical.Device;
import de.haumacher.ehome.programming.DeviceConfiguration;
import de.haumacher.ehome.programming.DownloadImage;
import de.haumacher.ehome.programming.download.BusPath;
import de.haumacher.ehome.programming.download.DeviceReader;
import de.haumacher.ehome.programming.download.DownloadPlanner;

/**
 * The "device info" of a device of a project (haumacher/ehome #175): what the device at its
 * individual address tells about itself when asked over the project's path to the bus, beside what
 * the project says about it.
 *
 * <p>
 * The device is read point to point as the scan reads it ({@link BusScanner#read(DeviceReader, int)}),
 * and in addition its programming mode; nothing is written. The reading is a result of the moment,
 * with its time and path, and is not stored in the model. Its identity (mask version, manufacturer,
 * serial number, order info, hardware type, program) is compared with the project's values when it is
 * shown ({@link #identity()}), so that a serial number taken over ({@link #takeSerial()}) shows as
 * equal afterwards. A device that does not answer its descriptor is a result as well
 * ({@link #answered()}), not an error.
 * </p>
 */
public class DeviceInfo {

	/** Key of the {@link DeviceInfo} in the result of the job ({@link #toMap()}). */
	public static final String INFO = "info";

	/** Key of the sentence summarizing the reading. */
	public static final String SUMMARY = "summary";

	/** Key of the time and path of the reading, for a person. */
	public static final String READ = "read";

	/** Key of the project of the device, for the access check of the take-over. */
	public static final String PROJECT = "project";

	/** Key of the device of the project. */
	public static final String DEVICE = "device";

	/**
	 * How a value of the device compares with the project's.
	 */
	public enum Comparison {
		/** The device tells the project's value. */
		EQUAL,

		/** The device tells another value than the project. */
		DIFFERENT,

		/** The device tells a value the project does not have. */
		NOT_IN_PROJECT,

		/** The device did not tell the value. */
		NOT_READ,

		/**
		 * The device tells the value in a form that cannot be related to the project's (an order info
		 * in a manufacturer-specific encoding, haumacher/ehome #179).
		 */
		NOT_COMPARABLE;

		/**
		 * The comparison for a person.
		 */
		public ResKey label() {
			return switch (this) {
				case EQUAL -> I18NConstants.INFO_EQUAL;
				case DIFFERENT -> I18NConstants.INFO_DIFFERENT;
				case NOT_IN_PROJECT -> I18NConstants.INFO_NOT_IN_PROJECT;
				case NOT_READ -> I18NConstants.INFO_NOT_READ;
				case NOT_COMPARABLE -> I18NConstants.INFO_NOT_COMPARABLE;
			};
		}
	}

	/**
	 * A value of the identity of the device.
	 *
	 * @param name
	 *        The technical name of the value (<code>mask</code>, <code>manufacturer</code>,
	 *        <code>serialNumber</code>, <code>orderInfo</code>, <code>hardwareType</code>,
	 *        <code>program</code>).
	 * @param label
	 *        The value for a person.
	 * @param project
	 *        The project's value, <code>null</code> if it has none.
	 * @param device
	 *        The value the device tells, <code>null</code> if it did not tell it.
	 * @param comparison
	 *        How they compare.
	 */
	public record Row(String name, ResKey label, String project, String device, Comparison comparison) {
		// Value.
	}

	private final TLObject _device;

	private final int _address;

	private final String _path;

	private final Date _time;

	private final FoundDevice _found;

	private final Boolean _programmingMode;

	private final List<ResKey> _notes;

	/**
	 * Creates a {@link DeviceInfo}.
	 *
	 * @param device
	 *        The device of the project.
	 * @param address
	 *        The individual address the device was read at.
	 * @param path
	 *        The path to the bus it was read through ({@link BusPath#text()}).
	 * @param time
	 *        When it was read.
	 * @param found
	 *        What the device told about itself.
	 * @param programmingMode
	 *        Whether the device is in programming mode, <code>null</code> if not read.
	 * @param notes
	 *        What could not be read beyond the notes of <code>found</code>.
	 */
	public DeviceInfo(TLObject device, int address, String path, Date time, FoundDevice found, Boolean programmingMode,
			List<ResKey> notes) {
		_device = device;
		_address = address;
		_path = path;
		_time = time;
		_found = found;
		_programmingMode = programmingMode;
		_notes = notes;
	}

	/**
	 * Why the device info of the given device cannot be read now, <code>null</code> if it can: the
	 * device has no complete individual address, or the project's path to the bus cannot be used
	 * ({@link BusPath#veto(TLObject)}). Nothing is written, so whether writing is switched on does
	 * not matter.
	 */
	public static ResKey veto(TLObject device) {
		ResKey path = BusPath.veto(device);
		if (path != null) {
			return path;
		}
		if (DownloadImage.individualAddress(device) == null) {
			return de.haumacher.ehome.programming.download.I18NConstants.ERROR_NO_ADDRESS;
		}
		return null;
	}

	/**
	 * Reads the device info of the given device.
	 *
	 * @param device
	 *        The device of the project, with a complete individual address.
	 * @param path
	 *        The path the reader reaches the bus through, for a person ({@link BusPath#text()}).
	 * @param reader
	 *        The access to the device at its individual address; nothing is written.
	 */
	public static DeviceInfo read(TLObject device, String path, DeviceReader reader) {
		int address = DownloadImage.individualAddress(device).intValue();
		FoundDevice found = BusScanner.read(reader, address);
		List<ResKey> notes = new ArrayList<>();
		Boolean programmingMode = null;
		if (found.descriptor() != null) {
			programmingMode = BusScanner.programmingMode(reader, found.descriptor().intValue(), notes);
		}
		BusScanner.disconnect(reader);
		return new DeviceInfo(device, address, path, new Date(), found, programmingMode, notes);
	}

	/**
	 * The device of the project.
	 */
	public TLObject device() {
		return _device;
	}

	/**
	 * The individual address the device was read at.
	 */
	public int address() {
		return _address;
	}

	/**
	 * The individual address as text (<code>1.3.5</code>).
	 */
	public String addressText() {
		return FoundDevice.formatAddress(_address);
	}

	/**
	 * The path to the bus the device was read through ({@link BusPath#text()}).
	 */
	public String path() {
		return _path;
	}

	/**
	 * When the device was read.
	 */
	public Date time() {
		return _time;
	}

	/**
	 * What the device told about itself.
	 */
	public FoundDevice found() {
		return _found;
	}

	/**
	 * Whether the device is in programming mode, <code>null</code> if it was not read.
	 */
	public Boolean programmingMode() {
		return _programmingMode;
	}

	/**
	 * Whether a device answered at the address (its descriptor).
	 */
	public boolean answered() {
		return _found.descriptor() != null;
	}

	/**
	 * The identity of the device beside the project's values, as the project is now.
	 */
	public List<Row> identity() {
		List<Row> result = new ArrayList<>();
		Program program = _device.tValid() ? DeviceConfiguration.program(_device) : null;
		Variant variant = _device.tValid() && _device instanceof Device device ? device.getType() : null;

		MaskVersion mask = program == null ? null : program.getMask();
		result.add(row("mask", I18NConstants.INFO_MASK, mask == null ? null : String.format("%04X", mask.getNumber()),
			_found.descriptorText(),
			() -> _found.descriptor().intValue() == mask.getNumber()));

		Integer manufacturerId = manufacturerId(variant, program);
		result.add(row("manufacturer", I18NConstants.READ_MANUFACTURER, manufacturer(manufacturerId),
			manufacturer(_found.manufacturerId()), () -> manufacturerId.equals(_found.manufacturerId())));

		byte[] serial = serial(_device.tValid() ? ((BusDevice) _device).getSerialNumber() : null);
		result.add(row("serialNumber", I18NConstants.READ_SERIAL_NUMBER, serial == null ? null : serialText(serial),
			_found.serialText(), () -> Arrays.equals(serial, _found.serialNumber())));

		Object orderNumber = variant == null ? null : variant.getOrderNumber();
		String order = orderNumber instanceof String text && !text.isBlank() ? text : null;
		result.add(row("orderInfo", I18NConstants.READ_ORDER_INFO, order, _found.orderInfo(),
			() -> _found.orderNumber() == null ? null
				: Boolean.valueOf(CatalogMatch.sameOrder(_found.orderNumber(), order))));

		byte[] hardware = program == null ? null : CatalogMatch.comparedHardwareType(program);
		result.add(row("hardwareType", I18NConstants.READ_HARDWARE_TYPE, hardware == null ? null : FoundDevice.hex(hardware),
			_found.hardwareTypeText(), () -> CatalogMatch.samePadded(hardware, _found.hardwareType())));

		byte[] applicationId = program == null ? null : DownloadPlanner.applicationId(program);
		result.add(row("program", I18NConstants.INFO_PROGRAM,
			program == null ? null : program.getExtId() + " (" + FoundDevice.hex(applicationId) + ")",
			programText(), () -> Arrays.equals(applicationId, _found.programVersion())));
		return result;
	}

	private interface Same {
		/**
		 * Whether the device's value is the project's, <code>null</code> if they cannot be compared.
		 */
		Boolean test();
	}

	private static Row row(String name, ResKey label, String project, String device, Same same) {
		Comparison comparison;
		if (device == null) {
			comparison = Comparison.NOT_READ;
		} else if (project == null) {
			comparison = Comparison.NOT_IN_PROJECT;
		} else {
			Boolean equal = same.test();
			comparison = equal == null ? Comparison.NOT_COMPARABLE
				: equal.booleanValue() ? Comparison.EQUAL : Comparison.DIFFERENT;
		}
		return new Row(name, label, project, device, comparison);
	}

	/**
	 * The state of the device for a person: the load states of its load state machines, its run state
	 * and its programming mode.
	 */
	public List<ResKey> state() {
		List<ResKey> result = new ArrayList<>(_found.loadStates());
		if (_programmingMode != null) {
			result.add(I18NConstants.NOTE__LABEL_VALUE.fill(I18NConstants.READ_PROGRAMMING_MODE,
				_programmingMode.booleanValue() ? I18NConstants.LED_ON : I18NConstants.LED_OFF));
		}
		return result;
	}

	/**
	 * What could not be read, for a person.
	 */
	public List<ResKey> notes() {
		List<ResKey> result = new ArrayList<>(_found.notes());
		result.addAll(_notes);
		return result;
	}

	/**
	 * The number of the values of the identity with the given comparison.
	 */
	public long count(Comparison comparison) {
		return identity().stream().filter(row -> row.comparison() == comparison).count();
	}

	/**
	 * The outcome of the reading as a sentence.
	 */
	public ResKey summary() {
		if (!answered()) {
			return I18NConstants.INFO_NO_ANSWER__ADDRESS_PATH.fill(addressText(), _path);
		}
		return I18NConstants.INFO_DONE__ADDRESS_PATH_DIFFERENT_UNKNOWN.fill(addressText(), _path,
			count(Comparison.DIFFERENT), count(Comparison.NOT_IN_PROJECT));
	}

	/**
	 * When and through which path the device was read, for a person.
	 */
	public ResKey readText() {
		return I18NConstants.INFO_READ__TIME_PATH.fill(
			DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM, Resources.getInstance().getLocale())
				.format(_time),
			_path);
	}

	/**
	 * Why the serial number read cannot be taken over into the project's device, <code>null</code> if
	 * it can.
	 *
	 * <p>
	 * As the scan's "Take over" does: the device read must be the project's (the same program
	 * and mask), and the project's device must still have the address it was read at.
	 * </p>
	 */
	public ResKey serialVeto() {
		if (!_device.tValid()) {
			return I18NConstants.ERROR_INFO_DEVICE_DELETED;
		}
		if (!answered()) {
			return I18NConstants.INFO_NO_ANSWER__ADDRESS_PATH.fill(addressText(), _path);
		}
		if (_found.serialStored() == null) {
			return I18NConstants.ERROR_INFO_NO_SERIAL__ADDRESS.fill(addressText());
		}
		if (!Objects.equals(DownloadImage.individualAddress(_device), Integer.valueOf(_address))) {
			return I18NConstants.ERROR_INFO_ADDRESS_CHANGED__ADDRESS.fill(addressText());
		}
		for (Row row : identity()) {
			switch (row.name()) {
				case "serialNumber":
					if (row.comparison() == Comparison.EQUAL) {
						return I18NConstants.ERROR_INFO_SERIAL_KNOWN__SERIAL.fill(row.device());
					}
					break;
				case "mask":
				case "program":
					if (row.comparison() != Comparison.EQUAL) {
						return I18NConstants.ERROR_INFO_OTHER_DEVICE__ADDRESS_WHAT.fill(addressText(), row.label());
					}
					break;
				default:
					break;
			}
		}
		return null;
	}

	/**
	 * Takes the serial number read over into the project's device; the caller provides the
	 * transaction.
	 *
	 * @return What was done, for a person.
	 * @throws com.top_logic.util.error.TopLogicException
	 *         If it is refused ({@link #serialVeto()}).
	 */
	public ResKey takeSerial() {
		ResKey veto = serialVeto();
		if (veto != null) {
			throw new com.top_logic.util.error.TopLogicException(veto);
		}
		((BusDevice) _device).setSerialNumber(_found.serialStored());
		return I18NConstants.INFO_SERIAL_TAKEN__ADDRESS_SERIAL.fill(addressText(), _found.serialText());
	}

	/**
	 * The reading as the result of the job, shown by <code>projects/device-info.view.xml</code>: the
	 * {@link DeviceInfo} ({@value #INFO}), the summary ({@value #SUMMARY}), when and how it was read
	 * ({@value #READ}), the project ({@value #PROJECT}) and the device ({@value #DEVICE}). A person
	 * reads it as its summary (the job status shows a result by its label).
	 */
	public Map<String, Object> toMap() {
		Resources resources = Resources.getInstance();
		Map<String, Object> result = new Outcome(resources.getString(summary()));
		result.put(INFO, this);
		result.put(SUMMARY, resources.getString(summary()));
		result.put(READ, resources.getString(readText()));
		result.put(PROJECT, BusPath.project(_device));
		result.put(DEVICE, _device);
		return result;
	}

	/**
	 * The result of a reading that failed before the device was asked (the path to the bus could not
	 * be opened), in the form of {@link #toMap()} without a {@link DeviceInfo}.
	 */
	public static Map<String, Object> failure(TLObject device, ResKey summary) {
		String text = Resources.getInstance().getString(summary);
		Map<String, Object> result = new Outcome(text);
		result.put(SUMMARY, text);
		result.put(PROJECT, BusPath.project(device));
		result.put(DEVICE, device);
		return result;
	}

	/**
	 * The {@link DeviceInfo} of a result of the job ({@link #toMap()}), <code>null</code> for none.
	 */
	public static DeviceInfo of(Object result) {
		if (result instanceof DeviceInfo info) {
			return info;
		}
		return result instanceof Map<?, ?> map && map.get(INFO) instanceof DeviceInfo info ? info : null;
	}

	/**
	 * The manufacturer of the device as the project has it: that of its product, else that of its
	 * program; <code>null</code> if unknown.
	 */
	private static Integer manufacturerId(Variant variant, Program program) {
		Manufacturer manufacturer = variant == null ? null
			: (Manufacturer) ExtIds.ancestor(variant, "ehome.catalog:Manufacturer");
		if (manufacturer == null && variant != null && variant.getProduct() instanceof Product product) {
			manufacturer = (Manufacturer) ExtIds.ancestor(product, "ehome.catalog:Manufacturer");
		}
		if (manufacturer == null && program != null) {
			manufacturer = program.getManufacturer();
		}
		if (manufacturer != null) {
			if (manufacturer.getId() instanceof Integer id) {
				return id;
			}
			if (manufacturer.getExtId() instanceof String extId) {
				Integer id = manufacturerOfExtId(extId);
				if (id != null) {
					return id;
				}
			}
		}
		return program == null ? null : manufacturerOfExtId(program.getExtId());
	}

	private static Integer manufacturerOfExtId(String extId) {
		if (extId != null && extId.startsWith("M-") && extId.length() >= 6) {
			try {
				return Integer.valueOf(Integer.parseInt(extId.substring(2, 6), 16));
			} catch (NumberFormatException ex) {
				return null;
			}
		}
		return null;
	}

	/**
	 * A manufacturer for a person: its name in the catalog with its KNX ID, else the ID alone.
	 */
	private static String manufacturer(Integer id) {
		if (id == null) {
			return null;
		}
		Manufacturer manufacturer = (Manufacturer) CatalogMatch.manufacturer(id.intValue());
		String text = String.format("M-%04X", id);
		return manufacturer == null ? text : manufacturer.getName() + " (" + text + ")";
	}

	/**
	 * The program the device runs for a person: its program version, with the program of the catalog
	 * it names where the catalog identifies exactly one.
	 */
	private String programText() {
		if (_found.programVersion() == null) {
			return null;
		}
		List<String> programs = CatalogMatch.match(_device.tValid() ? BusPath.project(_device) : null, _found)
			.candidates().stream().map(candidate -> candidate.program().getExtId()).distinct().toList();
		return programs.size() == 1 ? _found.programVersionText() + " (" + programs.get(0) + ")"
			: _found.programVersionText();
	}

	/**
	 * The serial number of a project device (ETS's base64 form), <code>null</code> if it has none or
	 * it is no serial number.
	 */
	private static byte[] serial(Object stored) {
		if (!(stored instanceof String text) || text.isBlank()) {
			return null;
		}
		try {
			return Base64.getDecoder().decode(text.trim());
		} catch (IllegalArgumentException ex) {
			return null;
		}
	}

	private static String serialText(byte[] serial) {
		return new FoundDevice(0, FoundDevice.Source.BUS, null, null, serial, null, null, null, List.of(), List.of())
			.serialText();
	}

	/**
	 * The result of the job: a dictionary that a person reads as its summary.
	 */
	private static final class Outcome extends HashMap<String, Object> {

		private final String _summary;

		Outcome(String summary) {
			_summary = summary;
		}

		@Override
		public String toString() {
			return _summary;
		}
	}

}
