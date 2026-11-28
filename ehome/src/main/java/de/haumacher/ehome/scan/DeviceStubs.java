/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.scan;

import java.util.Base64;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;

import com.top_logic.basic.util.ResKey;
import com.top_logic.model.TLObject;
import com.top_logic.util.error.TopLogicException;

import de.haumacher.ehome.knx.ExtIds;
import de.haumacher.ehome.model.catalog.Manufacturer;
import de.haumacher.ehome.model.physical.DeviceStub;
import de.haumacher.ehome.programming.DownloadImage;
import de.haumacher.ehome.programming.GroupAddressConnections;

/**
 * The stub of a device a scan found that the catalog does not identify (haumacher/ehome #183,
 * <code>ehome.physical:DeviceStub</code>): it stands in the line of its address like a device, holds
 * what the device told about itself (manufacturer, program version, mask version, hardware type,
 * order info, serial number) and has no product and no program.
 *
 * <p>
 * "Take over" of the scan creates a stub for a row the catalog does not identify, or updates the
 * stub at its address ({@link #record(TLObject, FoundDevice)}). "Assign" at the stub
 * ({@link #assign(TLObject)}) looks its identity up in the catalog again: found, the stub is replaced
 * by a device (<code>ehome.physical:Device</code>) with product and program that keeps its name,
 * address, serial number and location; not found, it refuses naming the manufacturer and the order
 * number, which a person looks the product data up by.
 * </p>
 */
public final class DeviceStubs {

	/** The type of a stub. */
	public static final String STUB_TYPE = "ehome.physical:DeviceStub";

	/** The type of a device of the catalog, with product and program. */
	public static final String DEVICE_TYPE = "ehome.physical:Device";

	private DeviceStubs() {
		// Functions only.
	}

	/**
	 * Whether the given object is a stub of a device the catalog does not identify.
	 */
	public static boolean isStub(Object obj) {
		return obj instanceof TLObject object && ExtIds.isA(object, STUB_TYPE);
	}

	/**
	 * Whether the given object is a device of the catalog (with product and program), not a stub.
	 */
	public static boolean isDevice(Object obj) {
		return obj instanceof TLObject object && ExtIds.isA(object, DEVICE_TYPE);
	}

	/**
	 * Creates the stub of the given device in the line of its address (created with its area where
	 * the project has none, as for a device), or updates the stub at its address with what was read.
	 *
	 * <p>
	 * Must be called in a transaction.
	 * </p>
	 *
	 * @throws IllegalStateException
	 *         If the project has a device of the catalog at the address.
	 */
	public static TLObject record(TLObject project, FoundDevice found) {
		TLObject existing = InstallationBuilder.device(project, found.address());
		TLObject stub;
		if (existing == null) {
			TLObject line = InstallationBuilder.line(project, found.address() >>> 12 & 0x0F, found.address() >>> 8 & 0x0F);
			DeviceStub created = (DeviceStub) InstallationBuilder.create(STUB_TYPE);
			created.setAddress(Integer.valueOf(found.address() & 0xFF));
			stub = created;
			InstallationBuilder.add(line, "devices", stub);
			ExtIds.assign(stub);
		} else if (isStub(existing)) {
			stub = existing;
		} else {
			throw new IllegalStateException("The project has the device " + found.addressText() + ".");
		}
		update(stub, found);
		return stub;
	}

	/**
	 * Sets what the given device told about itself at the given stub.
	 */
	static void update(TLObject obj, FoundDevice found) {
		DeviceStub stub = (DeviceStub) obj;
		stub.setManufacturerId(found.manufacturerId());
		stub.setProgramVersion(found.programVersion() == null ? null : hex(found.programVersion()));
		stub.setDescriptor(found.descriptorText());
		stub.setHardwareType(found.hardwareType() == null ? null : hex(found.hardwareType()));
		stub.setOrderInfo(found.orderInfo());
		if (found.serialStored() != null) {
			stub.setSerialNumber(found.serialStored());
		}
		stub.setLastSeen(new Date());
	}

	/**
	 * The device a stub describes, to be matched against the catalog: at the individual address of
	 * the stub (its number alone where it is in no line).
	 */
	public static FoundDevice found(TLObject obj) {
		DeviceStub stub = (DeviceStub) obj;
		Integer address = DownloadImage.individualAddress(stub);
		if (address == null) {
			address = stub.getAddress() instanceof Integer number ? number : Integer.valueOf(0);
		}
		String descriptor = stub.getDescriptor();
		String serial = stub.getSerialNumber();
		return new FoundDevice(address.intValue(), FoundDevice.Source.BUS,
			descriptor != null && !descriptor.isEmpty() ? Integer.valueOf(descriptor, 16) : null,
			stub.getManufacturerId(),
			serial != null && !serial.isEmpty() ? Base64.getDecoder().decode(serial) : null,
			bytes(stub.getProgramVersion()), bytes(stub.getHardwareType()),
			stub.getOrderInfo(), List.of(), List.of());
	}

	/**
	 * The manufacturer of the stub for a person: its name in the catalog, else its KNX ID
	 * (<code>M-0008</code>); <code>null</code> if the device reported none.
	 */
	public static String manufacturer(TLObject stub) {
		FoundDevice found = found(stub);
		Integer id = found.manufacturerId() != null ? found.manufacturerId() : found.programManufacturer();
		if (id == null) {
			return null;
		}
		Manufacturer manufacturer = (Manufacturer) CatalogMatch.manufacturer(id.intValue());
		return manufacturer != null && manufacturer.getName() instanceof String name && !name.isBlank() ? name
			: String.format("M-%04X", id);
	}

	/**
	 * The order number the stub's device reports ({@link FoundDevice#orderNumber()}), <code>null</code>
	 * where it reports no text there (haumacher/ehome #206: the hardware type is shown on its own).
	 */
	public static String orderNumber(TLObject stub) {
		return found(stub).orderNumber();
	}

	/**
	 * The stub for a person, without a name of its own: manufacturer and order number
	 * (<code>GIRA 5133 00</code>).
	 */
	public static String product(TLObject stub) {
		String manufacturer = manufacturer(stub);
		String order = orderNumber(stub);
		if (manufacturer == null) {
			return order == null ? "?" : order;
		}
		return order == null ? manufacturer : manufacturer + " " + order;
	}

	/**
	 * "Assign": looks the identity of the stub up in the catalog again and replaces the stub by a
	 * device of the product and program found, keeping its name, address, serial number and location.
	 *
	 * <p>
	 * Must be called in a transaction.
	 * </p>
	 *
	 * @return The device replacing the stub.
	 * @throws TopLogicException
	 *         If the catalog does not identify the device as exactly one product and program, naming
	 *         the manufacturer and the order number.
	 */
	public static TLObject assign(TLObject stub) {
		if (!isStub(stub) || !stub.tValid()) {
			throw new TopLogicException(I18NConstants.ERROR_NO_STUB);
		}
		CatalogMatch.Match match = CatalogMatch.match(found(stub));
		switch (match.kind()) {
			case UNIQUE:
				return InstallationBuilder.replace(stub, match.candidates().get(0));
			case CHOICE:
				throw new TopLogicException(I18NConstants.ERROR_ASSIGN_CHOICE__DEVICE_COUNT.fill(
					GroupAddressConnections.deviceLabel(stub), match.candidates().size()));
			default:
				throw new TopLogicException(noProductData(stub));
		}
	}

	/**
	 * Why the catalog has no product for the stub, naming what a person looks it up by: the
	 * manufacturer and the order number (else the hardware type).
	 */
	static ResKey noProductData(TLObject stub) {
		String manufacturer = manufacturer(stub);
		if (manufacturer == null) {
			manufacturer = "?";
		}
		FoundDevice found = found(stub);
		if (found.orderNumber() != null) {
			return I18NConstants.ERROR_NO_PRODUCT_DATA__MANUFACTURER_ORDER.fill(manufacturer, found.orderNumber());
		}
		if (found.hardwareType() != null) {
			return I18NConstants.ERROR_NO_PRODUCT_DATA_HARDWARE__MANUFACTURER_TYPE.fill(manufacturer,
				found.hardwareTypeText());
		}
		return I18NConstants.ERROR_NO_PRODUCT_DATA__MANUFACTURER.fill(manufacturer);
	}

	private static String hex(byte[] data) {
		return HexFormat.of().withUpperCase().formatHex(data);
	}

	private static byte[] bytes(Object hex) {
		return hex instanceof String text && !text.isEmpty() ? HexFormat.of().parseHex(text.replace(" ", "")) : null;
	}

}
