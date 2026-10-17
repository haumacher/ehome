/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.virtual.device;

import java.util.Arrays;
import java.util.Objects;

import com.top_logic.basic.util.ResKey;
import com.top_logic.model.TLObject;
import com.top_logic.util.error.TopLogicException;

import de.haumacher.ehome.model.operational.CodeSegment;
import de.haumacher.ehome.model.operational.LoadProcedure;
import de.haumacher.ehome.model.operational.LoadStep;
import de.haumacher.ehome.model.operational.MaskFeature;
import de.haumacher.ehome.model.operational.MaskVersion;
import de.haumacher.ehome.model.operational.Program;
import de.haumacher.ehome.model.operational.Segment;
import de.haumacher.ehome.programming.DeviceConfiguration;
import de.haumacher.ehome.programming.DownloadImage;
import de.haumacher.ehome.programming.DownloadImage.PropertyWrite;
import de.haumacher.ehome.programming.ParameterEncoding;
import de.haumacher.ehome.programming.download.DownloadPlanner;
import de.haumacher.ehome.virtual.I18NConstants;
import de.haumacher.ehome.virtual.device.SimulatedDevice.LoadControlKind;

/**
 * Creates the {@link SimulatedDevice} of a device of a project (<code>ehome.physical:Device</code>)
 * from E-Home's data.
 *
 * <ul>
 * <li>Address: the individual address of the device.</li>
 * <li>Device descriptor: the mask version of its program.</li>
 * <li>Load state machines: by the mask, see {@link #kind(MaskVersion)}; all unloaded.</li>
 * <li>Memory: the static data of the program's absolute segments (zero elsewhere); the memory a
 * download does not write keeps it, as with a device fresh from the factory that holds its
 * application.</li>
 * <li>Properties (only masks with property-mapped load state machines): the object types of the
 * objects 0 to 4, the manufacturer of the program (device object, property 12), and the hardware
 * type (device object, property 78) - the program's static value for that property if it has one,
 * else the value its load procedure compares the property with (<code>CompareProp</code>): the
 * hardware the program declares it runs on.</li>
 * </ul>
 * <p>
 * Everything else a tool may read (serial number, security, ...) is not known and answered as not
 * present.
 * </p>
 */
public class ProjectDevices {

	/** The feature of a mask whose load state machines are controlled through properties. */
	private static final String PROPERTY_MAPPED_LSMS = "PropertyMappedLsms";

	/** The masks of BIM M112 whose load state machines are controlled through memory. */
	private static final int[] MEMORY_MAPPED_MASKS = { 0x0700, 0x0701 };

	/** The length of the hardware type (PDT_GENERIC_06). */
	private static final int HARDWARE_TYPE_LENGTH = 6;

	/**
	 * The simulated device of the given project device.
	 *
	 * @throws TopLogicException
	 *         If the device has no program, no mask or no complete individual address.
	 */
	public static SimulatedDevice create(TLObject device, MappedLoadStateRule rule) {
		Integer address = DownloadImage.individualAddress(device);
		if (address == null) {
			throw new TopLogicException(I18NConstants.ERROR_NO_ADDRESS);
		}
		Program program = DeviceConfiguration.program(device);
		if (program == null) {
			throw new TopLogicException(I18NConstants.ERROR_NO_PROGRAM);
		}
		MaskVersion mask = program.getMask();
		if (mask == null) {
			throw new TopLogicException(I18NConstants.ERROR_NO_MASK);
		}
		LoadControlKind kind = kind(mask);
		String name = DownloadPlanner.formatAddress(address) + " (" + device.tValueByName("extId") + ")";
		SimulatedDevice result = new SimulatedDevice(name, address, mask.getNumber(), kind, rule);
		for (Segment segment : program.getSegments()) {
			if (segment instanceof CodeSegment code && code.getAddress() != null) {
				byte[] data = ParameterEncoding.toBytes(code.getData());
				if (data != null && code.getAddress() + data.length <= SimulatedDevice.MEMORY_SIZE) {
					result.initMemory(code.getAddress(), data);
				}
			}
		}
		if (kind == LoadControlKind.PROPERTIES) {
			byte[] id = DownloadPlanner.applicationId(program);
			result.initProperty(0, SimulatedDevice.PID_MANUFACTURER_ID, Arrays.copyOf(id, 2));
			byte[] hardware = hardwareType(device, program);
			if (hardware != null) {
				result.initProperty(0, SimulatedDevice.PID_HARDWARE_TYPE, hardware);
			}
		}
		return result;
	}

	/**
	 * How the load state machines of a mask are controlled: through properties with the feature
	 * <code>PropertyMappedLsms</code>, through memory on the BIM M112 masks 0700 and 0701 (as
	 * Calimero's device implementation assumes), none otherwise.
	 */
	public static LoadControlKind kind(MaskVersion mask) {
		for (MaskFeature feature : mask.getFeatures()) {
			if (PROPERTY_MAPPED_LSMS.equals(feature.getName()) && Objects.equals(feature.getValue(), 1)) {
				return LoadControlKind.PROPERTIES;
			}
		}
		for (int memoryMapped : MEMORY_MAPPED_MASKS) {
			if (mask.getNumber() == memoryMapped) {
				return LoadControlKind.MEMORY;
			}
		}
		return LoadControlKind.NONE;
	}

	private static byte[] hardwareType(TLObject device, Program program) {
		DownloadImage image = DownloadImage.compute(device);
		for (PropertyWrite write : image.getPropertyWrites()) {
			if (Objects.equals(write.objectIndex(), 0) && write.propertyId() == SimulatedDevice.PID_HARDWARE_TYPE) {
				return write.value();
			}
		}
		for (LoadProcedure procedure : program.getLoadProcedures()) {
			for (LoadStep step : procedure.getSteps()) {
				if (step.getKind() != null && "CompareProp".equals(step.getKind().getName())
					&& Objects.equals(step.getObjectIndex(), 0)
					&& Objects.equals(step.getPropertyId(), SimulatedDevice.PID_HARDWARE_TYPE)) {
					byte[] data = ParameterEncoding.toBytes(step.getInlineData());
					if (data != null && data.length > 0) {
						return data.length > HARDWARE_TYPE_LENGTH ? Arrays.copyOf(data, HARDWARE_TYPE_LENGTH) : data;
					}
				}
			}
		}
		return null;
	}

	/**
	 * Why no simulated device can be created for the given project device, <code>null</code> if
	 * one can.
	 */
	public static ResKey veto(TLObject device) {
		if (device == null || !device.tValid()) {
			return I18NConstants.ERROR_NO_DEVICE;
		}
		if (DownloadImage.individualAddress(device) == null) {
			return I18NConstants.ERROR_NO_ADDRESS;
		}
		Program program = DeviceConfiguration.program(device);
		if (program == null) {
			return I18NConstants.ERROR_NO_PROGRAM;
		}
		if (program.getMask() == null) {
			return I18NConstants.ERROR_NO_MASK;
		}
		return null;
	}

}
