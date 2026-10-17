/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.scan;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.top_logic.model.TLClass;
import com.top_logic.model.TLClassifier;
import com.top_logic.model.TLObject;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.knx.ExtIds;
import de.haumacher.ehome.knx.GA;
import de.haumacher.ehome.knx.GroupAddressStyle;
import de.haumacher.ehome.programming.DeviceConfiguration;
import de.haumacher.ehome.programming.DeviceSettings;
import de.haumacher.ehome.programming.GroupAddresses;
import de.haumacher.ehome.scan.CatalogMatch.Candidate;

/**
 * Builds the project of an installation from the devices a scan found: a matched device becomes a
 * <code>ehome.physical:Device</code> under the line of its address, an unidentified one an
 * <code>ehome.project:UnidentifiedDevice</code> of the project.
 *
 * <p>
 * Every method changes the model and must be called within a transaction.
 * </p>
 */
public class InstallationBuilder {

	/**
	 * What happened to a device.
	 */
	public enum Action {
		/** The device was created. */
		CREATED,

		/** A device with the address existed and runs the same program; it was updated. */
		UPDATED,

		/** A device with the address exists with another program; nothing was changed. */
		CONFLICT;
	}

	/**
	 * The outcome of {@link #apply(TLObject, FoundDevice, Candidate)}.
	 *
	 * @param action
	 *        What happened.
	 * @param device
	 *        The device of the project (<code>ehome.physical:Device</code>).
	 * @param message
	 *        What happened, for a person.
	 */
	public record Outcome(Action action, TLObject device, String message) {
		// Value.
	}

	/**
	 * Creates or updates the device of the project with the address of the found device.
	 *
	 * <p>
	 * A new device runs the program of the candidate, is of its product variant, and records that
	 * the individual address and the program are loaded (its parameters and group addresses are the
	 * project's defaults until its configuration is read back, see {@link ReadBack}). An existing
	 * device with the address gets the serial number read; if it runs another program, nothing is
	 * changed and the conflict is reported.
	 * </p>
	 */
	public static Outcome apply(TLObject project, FoundDevice found, Candidate candidate) {
		TLObject existing = device(project, found.address());
		String address = found.addressText();
		if (existing != null) {
			if (DeviceConfiguration.program(existing) != candidate.program()) {
				Object program = DeviceConfiguration.program(existing) == null ? null
					: DeviceConfiguration.program(existing).getExtId();
				return new Outcome(Action.CONFLICT, existing, "The device " + address + " of the project runs the program "
					+ program + ", the device on the bus " + candidate.program().getExtId() + "; nothing changed.");
			}
			if (found.serialStored() != null) {
				existing.tUpdateByName("serialNumber", found.serialStored());
			}
			removeUnidentified(project, found.address());
			return new Outcome(Action.UPDATED, existing, "The device " + address + " was updated.");
		}
		TLObject line = line(project, found.address() >>> 12 & 0x0F, found.address() >>> 8 & 0x0F);
		TLObject device = create("ehome.physical:Device");
		device.tUpdateByName("address", Integer.valueOf(found.address() & 0xFF));
		device.tUpdateByName("type", candidate.variant());
		device.tUpdateByName("program", candidate.assignment());
		if (found.serialStored() != null) {
			device.tUpdateByName("serialNumber", found.serialStored());
		}
		Set<Object> loaded = new LinkedHashSet<>();
		loaded.add(TLModelUtil.findPart("ehome.physical:DownloadPart#IndividualAddress"));
		loaded.add(TLModelUtil.findPart("ehome.physical:DownloadPart#ApplicationProgram"));
		device.tUpdateByName("loaded", loaded);
		device.tUpdateByName("lastModified", new Date());
		add(line, "devices", device);
		ExtIds.assign(device);
		removeUnidentified(project, found.address());
		return new Outcome(Action.CREATED, device, "The device " + address + " was created: " + candidate.label() + ".");
	}

	/**
	 * The device of the project with the given individual address, <code>null</code> if there is
	 * none.
	 */
	public static TLObject device(TLObject project, int address) {
		for (TLObject area : ExtIds.list(project, "areas")) {
			if (!number(area, address >>> 12 & 0x0F)) {
				continue;
			}
			for (TLObject line : ExtIds.list(area, "lines")) {
				if (!number(line, address >>> 8 & 0x0F)) {
					continue;
				}
				for (TLObject device : ExtIds.list(line, "devices")) {
					if (number(device, address & 0xFF)) {
						return device;
					}
				}
			}
		}
		return null;
	}

	/**
	 * The line of the project with the given address, created (with its area) if there is none;
	 * a new area or line is named by its address.
	 */
	public static TLObject line(TLObject project, int areaNumber, int lineNumber) {
		TLObject area = null;
		for (TLObject candidate : ExtIds.list(project, "areas")) {
			if (number(candidate, areaNumber)) {
				area = candidate;
				break;
			}
		}
		if (area == null) {
			area = create("ehome.physical:Area");
			area.tUpdateByName("address", Integer.valueOf(areaNumber));
			area.tUpdateByName("name", Integer.toString(areaNumber));
			add(project, "areas", area);
			ExtIds.assign(area);
		}
		for (TLObject candidate : ExtIds.list(area, "lines")) {
			if (number(candidate, lineNumber)) {
				return candidate;
			}
		}
		TLObject line = create("ehome.physical:Line");
		line.tUpdateByName("address", Integer.valueOf(lineNumber));
		line.tUpdateByName("name", areaNumber + "." + lineNumber);
		add(area, "lines", line);
		ExtIds.assign(line);
		return line;
	}

	/**
	 * Records a device the catalog does not identify in the project (or updates the record of its
	 * address).
	 */
	public static TLObject recordUnidentified(TLObject project, FoundDevice found, String reason) {
		TLObject record = unidentified(project, found.address());
		if (record == null) {
			record = create("ehome.project:UnidentifiedDevice");
			add(project, "unidentifiedDevices", record);
		}
		record.tUpdateByName("name", found.addressText());
		record.tUpdateByName("address", Integer.valueOf(found.address()));
		record.tUpdateByName("descriptor", found.descriptorText());
		record.tUpdateByName("manufacturerId", found.manufacturerId());
		record.tUpdateByName("serialNumber", found.serialNumber() == null ? null : hex(found.serialNumber()));
		record.tUpdateByName("programVersion", found.programVersion() == null ? null : hex(found.programVersion()));
		record.tUpdateByName("hardwareType", found.hardwareType() == null ? null : hex(found.hardwareType()));
		record.tUpdateByName("orderInfo", found.orderInfo());
		record.tUpdateByName("reason", reason);
		record.tUpdateByName("lastSeen", new Date());
		return record;
	}

	/**
	 * The device a record of an unidentified device (<code>ehome.project:UnidentifiedDevice</code>)
	 * describes, to be matched again.
	 */
	public static FoundDevice found(TLObject record) {
		Object descriptor = record.tValueByName("descriptor");
		return new FoundDevice(((Integer) record.tValueByName("address")).intValue(), FoundDevice.Source.BUS,
			descriptor == null ? null : Integer.valueOf((String) descriptor, 16),
			(Integer) record.tValueByName("manufacturerId"),
			bytes(record.tValueByName("serialNumber")), bytes(record.tValueByName("programVersion")),
			bytes(record.tValueByName("hardwareType")), (String) record.tValueByName("orderInfo"), List.of(), List.of());
	}

	/**
	 * The record of an unidentified device with the given address, <code>null</code> if there is
	 * none.
	 */
	public static TLObject unidentified(TLObject project, int address) {
		for (TLObject record : ExtIds.list(project, "unidentifiedDevices")) {
			if (record.tValueByName("address") instanceof Integer value && value.intValue() == address) {
				return record;
			}
		}
		return null;
	}

	private static void removeUnidentified(TLObject project, int address) {
		TLObject record = unidentified(project, address);
		if (record != null) {
			record.tDelete();
		}
	}

	/**
	 * The group address of the project with the given value, created where the project has none:
	 * in the group range taking it, or in ranges created for it (named by their prefix), in the
	 * notation of the project. A new address is named by its address.
	 */
	public static TLObject groupAddress(TLObject project, int value) {
		for (TLObject address : DeviceSettings.groupAddresses(project)) {
			if (address.tValueByName("address") instanceof GA ga && ga.getValue() == value) {
				return address;
			}
		}
		GroupAddressStyle style = GroupAddressStyle.fromModel(project.tValueByName("groupAddressStyle"));
		if (style == null) {
			style = GroupAddressStyle.ThreeLevel;
		}
		GA ga = new GA(value, style);
		for (TLObject range : GroupAddresses.ranges(project)) {
			if (GroupAddresses.veto(range, ga) == null) {
				return GroupAddresses.create(range, ga, ga.toString(), null);
			}
		}
		if (style == GroupAddressStyle.Free) {
			TLObject result = create("ehome.logical:GroupAddress");
			result.tUpdateByName("address", ga);
			result.tUpdateByName("name", ga.toString());
			add(project, "groupAddresses", result);
			ExtIds.assign(result);
			return result;
		}
		int mainStart = value & 0xF800;
		TLObject main = range(project, project, mainStart, mainStart | 0x07FF, style);
		TLObject target = main;
		if (style == GroupAddressStyle.ThreeLevel) {
			int middleStart = value & 0xFF00;
			target = range(project, main, middleStart, middleStart | 0x00FF, style);
		}
		return GroupAddresses.create(target, ga, ga.toString(), null);
	}

	/**
	 * The group range with the given bounds in the given container (the project or a range),
	 * created if there is none.
	 */
	private static TLObject range(TLObject project, TLObject container, int start, int end, GroupAddressStyle style) {
		String reference = container == project ? "groupAddresses" : "parts";
		for (TLObject part : ExtIds.list(container, reference)) {
			if (ExtIds.isA(part, "ehome.logical:GroupRange") && part.tValueByName("rangeStart") instanceof GA from
				&& part.tValueByName("rangeEnd") instanceof GA to && from.getValue() == start && to.getValue() == end) {
				return part;
			}
		}
		TLObject range = create("ehome.logical:GroupRange");
		GA from = new GA(start, style);
		GA to = new GA(end, style);
		range.tUpdateByName("rangeStart", from);
		range.tUpdateByName("rangeEnd", to);
		range.tUpdateByName("name", from.rangePrefix(to));
		add(container, reference, range);
		ExtIds.assign(range);
		return range;
	}

	/**
	 * The name of an enumeration value, <code>null</code> if there is none.
	 */
	static String name(Object classifier) {
		return classifier instanceof TLClassifier value ? value.getName() : null;
	}

	private static boolean number(TLObject obj, int number) {
		return obj.tValueByName("address") instanceof Integer value && value.intValue() == number;
	}

	private static String hex(byte[] data) {
		return HexFormat.of().withUpperCase().formatHex(data);
	}

	private static byte[] bytes(Object hex) {
		return hex instanceof String text && !text.isEmpty() ? HexFormat.of().parseHex(text) : null;
	}

	static TLObject create(String typeName) {
		return ModelService.getInstance().getFactory().createObject((TLClass) TLModelUtil.findType(typeName));
	}

	static void add(TLObject container, String reference, TLObject element) {
		List<Object> elements = new ArrayList<>((Collection<?>) container.tValueByName(reference));
		elements.add(element);
		container.tUpdateByName(reference, elements);
	}

}
