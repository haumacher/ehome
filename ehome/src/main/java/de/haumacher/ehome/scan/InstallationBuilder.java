/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.scan;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import com.top_logic.model.TLClass;
import com.top_logic.model.TLClassifier;
import com.top_logic.model.TLObject;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.calculation.DeviceCalculations;
import de.haumacher.ehome.knx.ExtIds;
import de.haumacher.ehome.knx.GA;
import de.haumacher.ehome.knx.GroupAddressStyle;
import de.haumacher.ehome.model.building.BuildingDefinition;
import de.haumacher.ehome.model.catalog.ProgramAssignment;
import de.haumacher.ehome.model.catalog.Variant;
import de.haumacher.ehome.model.logical.GroupAddress;
import de.haumacher.ehome.model.logical.GroupAddressDefinition;
import de.haumacher.ehome.model.logical.GroupRange;
import de.haumacher.ehome.model.physical.Area;
import de.haumacher.ehome.model.physical.BusDevice;
import de.haumacher.ehome.model.physical.Device;
import de.haumacher.ehome.model.physical.Line;
import de.haumacher.ehome.model.project.Project;
import de.haumacher.ehome.modules.ModuleInstances;
import de.haumacher.ehome.programming.DeviceConfiguration;
import de.haumacher.ehome.programming.DeviceSettings;
import de.haumacher.ehome.programming.GroupAddresses;
import de.haumacher.ehome.scan.CatalogMatch.Candidate;

/**
 * Builds the project of an installation from the devices a scan found: a matched device becomes a
 * <code>ehome.physical:Device</code> under the line of its address, an unidentified one a stub
 * (<code>ehome.physical:DeviceStub</code>, {@link DeviceStubs}) there.
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
	 * project's defaults until its configuration is read back, see {@link ReadBack}). A stub at the
	 * address (a device not identified before, haumacher/ehome #183) is replaced by the device, which
	 * keeps its name, serial number and location. An existing device with the address gets the
	 * serial number read; if it runs another program, nothing is changed and the conflict is reported.
	 * </p>
	 */
	public static Outcome apply(TLObject project, FoundDevice found, Candidate candidate) {
		TLObject existing = device(project, found.address());
		String address = found.addressText();
		if (DeviceStubs.isStub(existing)) {
			TLObject device = replace(existing, candidate);
			if (found.serialStored() != null) {
				((Device) device).setSerialNumber(found.serialStored());
			}
			return new Outcome(Action.CREATED, device, "The device " + address + " was created in place of its stub: "
				+ candidate.label() + ".");
		}
		if (existing != null) {
			if (DeviceConfiguration.program(existing) != candidate.program()) {
				Object program = DeviceConfiguration.program(existing) == null ? null
					: DeviceConfiguration.program(existing).getExtId();
				return new Outcome(Action.CONFLICT, existing, "The device " + address + " of the project runs the program "
					+ program + ", the device on the bus " + candidate.program().getExtId() + "; nothing changed.");
			}
			if (found.serialStored() != null) {
				((Device) existing).setSerialNumber(found.serialStored());
			}
			return new Outcome(Action.UPDATED, existing, "The device " + address + " was updated.");
		}
		TLObject line = line(project, found.address() >>> 12 & 0x0F, found.address() >>> 8 & 0x0F);
		TLObject device = createDevice(line, found.address() & 0xFF, candidate.variant(), candidate.assignment(), loadedFromBus());
		if (found.serialStored() != null) {
			((Device) device).setSerialNumber(found.serialStored());
		}
		return new Outcome(Action.CREATED, device, "The device " + address + " was created: " + candidate.label() + ".");
	}

	/**
	 * What a device found on the bus holds: its individual address and its program
	 * (<code>ehome.physical:DownloadPart</code>).
	 */
	private static Set<Object> loadedFromBus() {
		Set<Object> loaded = new LinkedHashSet<>();
		loaded.add(TLModelUtil.findPart("ehome.physical:DownloadPart#IndividualAddress"));
		loaded.add(TLModelUtil.findPart("ehome.physical:DownloadPart#ApplicationProgram"));
		return loaded;
	}

	/**
	 * Replaces the given stub (<code>ehome.physical:DeviceStub</code>) by a device of the candidate's
	 * product variant and program at its place (its line, or the unplaced devices of the project),
	 * keeping its name, device number, serial number, location and external ID (haumacher/ehome
	 * #183). The device records that its address and program are loaded: the stub was found on the
	 * bus. Its parameters have their defaults until its configuration is read back.
	 *
	 * @return The new device.
	 */
	public static TLObject replace(TLObject stub, Candidate candidate) {
		TLObject container = stub.tContainer();
		String reference = stub.tContainerReference().getName();
		List<TLObject> before = ExtIds.list(container, reference);
		int index = before.indexOf(stub);
		BusDevice busDevice = (BusDevice) stub;
		String title = busDevice.getTitle();
		Integer number = busDevice.getAddress();
		String serial = busDevice.getSerialNumber();
		BuildingDefinition location = busDevice.getLocation();
		Object extId = stub.tValueByName(ExtIds.EXT_ID);
		stub.tDelete();

		Device device = newDevice(number, candidate.variant(), candidate.assignment(), loadedFromBus());
		device.setTitle(title);
		device.setSerialNumber(serial);
		device.setLocation(location);
		List<TLObject> elements = ExtIds.list(container, reference);
		elements.add(index < 0 ? elements.size() : Math.min(index, elements.size()), device);
		container.tUpdateByName(reference, elements);
		if (extId != null) {
			device.tUpdateByName(ExtIds.EXT_ID, extId);
		} else {
			ExtIds.assign(device);
		}
		completeDevice(device);
		return device;
	}

	/**
	 * Creates a device (<code>ehome.physical:Device</code>) from the catalog in the given line: of
	 * the given product variant, running the program of the given assignment, with the given device
	 * number in the line, and with an external ID. It has no parameter settings of its own (the
	 * program's defaults apply) and no connections; it is marked as modified now.
	 *
	 * <p>
	 * Shared by the scan, which creates a device found on the bus, and the planning of a device in
	 * the app (<code>de.haumacher.ehome.planning.DevicePlanning</code>, haumacher/ehome #104). The
	 * caller checks that the number is free in the line.
	 * </p>
	 *
	 * @param line
	 *        The line (<code>ehome.physical:Line</code>) the device is added to; it belongs to a
	 *        project, which numbers the external ID.
	 * @param number
	 *        The device number in the line (the last part of the individual address).
	 * @param variant
	 *        The product variant (<code>ehome.catalog:Variant</code>).
	 * @param assignment
	 *        The program assignment (<code>ehome.catalog:ProgramAssignment</code>) of the product
	 *        of the variant.
	 * @param loaded
	 *        The parts of the configuration the real device holds already
	 *        (<code>ehome.physical:DownloadPart</code>): none for a planned device.
	 * @return The new device.
	 */
	public static TLObject createDevice(TLObject line, int number, TLObject variant, TLObject assignment,
			Collection<?> loaded) {
		TLObject device = newDevice(Integer.valueOf(number), variant, assignment, loaded);
		add(line, "devices", device);
		ExtIds.assign(device);
		completeDevice(device);
		return device;
	}

	private static Device newDevice(Integer number, TLObject variant, TLObject assignment, Collection<?> loaded) {
		Device device = (Device) create(DeviceStubs.DEVICE_TYPE);
		device.setAddress(number);
		device.setType((Variant) variant);
		device.setProgram((ProgramAssignment) assignment);
		Set<TLClassifier> parts = new LinkedHashSet<>();
		for (Object part : loaded) {
			parts.add((TLClassifier) part);
		}
		device.setLoaded(parts);
		device.setConfigurationChanged(new Date());
		return device;
	}

	/**
	 * Computes what a new device derives from its program and values.
	 */
	private static void completeDevice(TLObject device) {
		// The instances of its program's dynamic modules for the default values (haumacher/ehome #119).
		ModuleInstances.update(device);
		// The R values of its calculations from its L values (haumacher/ehome #123): the defaults of a
		// program need not be consistent.
		DeviceCalculations.recompute(device);
	}

	/**
	 * The device of the project with the given individual address, a stub of a device the catalog
	 * does not identify (<code>ehome.physical:DeviceStub</code>) among them; <code>null</code> if there
	 * is none.
	 */
	public static TLObject device(TLObject project, int address) {
		for (TLObject area : ((Project) project).getAreas()) {
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
	 * The individual addresses of the devices of the project, those with an area and a line of a
	 * number.
	 */
	public static Set<Integer> addresses(TLObject project) {
		Set<Integer> result = new TreeSet<>();
		for (TLObject area : ((Project) project).getAreas()) {
			if (!(((Area) area).getAddress() instanceof Integer areaNumber) || areaNumber.intValue() >>> 4 != 0) {
				continue;
			}
			for (TLObject line : ExtIds.list(area, "lines")) {
				if (!(((Line) line).getAddress() instanceof Integer lineNumber) || lineNumber.intValue() >>> 4 != 0) {
					continue;
				}
				for (TLObject device : ExtIds.list(line, "devices")) {
					if (((BusDevice) device).getAddress() instanceof Integer number && number.intValue() >>> 8 == 0) {
						result.add(Integer.valueOf(areaNumber.intValue() << 12 | lineNumber.intValue() << 8 | number.intValue()));
					}
				}
			}
		}
		return result;
	}

	/**
	 * The line of the project with the given address, created (with its area) if there is none;
	 * a new area or line is named by its address.
	 */
	public static TLObject line(TLObject project, int areaNumber, int lineNumber) {
		TLObject area = null;
		for (TLObject candidate : ((Project) project).getAreas()) {
			if (number(candidate, areaNumber)) {
				area = candidate;
				break;
			}
		}
		if (area == null) {
			Area newArea = (Area) create("ehome.physical:Area");
			newArea.setAddress(Integer.valueOf(areaNumber));
			newArea.setName(Integer.toString(areaNumber));
			((Project) project).addArea(newArea);
			area = newArea;
			ExtIds.assign(area);
		}
		for (TLObject candidate : ExtIds.list(area, "lines")) {
			if (number(candidate, lineNumber)) {
				return candidate;
			}
		}
		Line line = (Line) create("ehome.physical:Line");
		line.setAddress(Integer.valueOf(lineNumber));
		line.setName(areaNumber + "." + lineNumber);
		add(area, "lines", line);
		ExtIds.assign(line);
		return line;
	}

	/**
	 * The group address of the project with the given value, created where the project has none:
	 * in the group range taking it, or in ranges created for it (named by their prefix), in the
	 * notation of the project. A new address is named by its address.
	 */
	public static TLObject groupAddress(TLObject project, int value) {
		for (TLObject address : DeviceSettings.groupAddresses(project)) {
			if (((GroupAddress) address).getAddress() instanceof GA ga && ga.getValue() == value) {
				return address;
			}
		}
		GroupAddressStyle style = GroupAddressStyle.fromModel(((Project) project).getGroupAddressStyle());
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
			GroupAddress result = (GroupAddress) create("ehome.logical:GroupAddress");
			result.setAddress(ga);
			result.setName(ga.toString());
			((Project) project).addGroupAddresse(result);
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
		Collection<? extends GroupAddressDefinition> parts = container == project
			? ((Project) project).getGroupAddresses() : ((GroupRange) container).getParts();
		for (GroupAddressDefinition part : parts) {
			if (part instanceof GroupRange existing && existing.getRangeStart() instanceof GA from
				&& existing.getRangeEnd() instanceof GA to && from.getValue() == start && to.getValue() == end) {
				return part;
			}
		}
		GroupRange range = (GroupRange) create("ehome.logical:GroupRange");
		GA from = new GA(start, style);
		GA to = new GA(end, style);
		range.setRangeStart(from);
		range.setRangeEnd(to);
		range.setName(from.rangePrefix(to));
		if (container == project) {
			((Project) project).addGroupAddresse(range);
		} else {
			((GroupRange) container).addPart(range);
		}
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
		return address(obj) instanceof Integer value && value.intValue() == number;
	}

	/**
	 * The number of an area, a line or a device (<code>ehome.physical:BusDevice</code>) in its
	 * container, <code>null</code> if it has none.
	 */
	private static Integer address(TLObject obj) {
		if (obj instanceof Area area) {
			return area.getAddress();
		}
		if (obj instanceof Line line) {
			return line.getAddress();
		}
		return ((BusDevice) obj).getAddress();
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
