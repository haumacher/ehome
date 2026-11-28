/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.scan;

import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;

import com.top_logic.basic.Logger;
import com.top_logic.element.meta.MetaElementUtil;
import com.top_logic.knowledge.service.migration.StartupAction;
import com.top_logic.layout.provider.MetaLabelProvider;
import com.top_logic.model.TLClass;
import com.top_logic.model.TLObject;
import com.top_logic.model.util.TLModelUtil;

import de.haumacher.ehome.knx.ExtIds;
import de.haumacher.ehome.model.physical.BusDevice;
import de.haumacher.ehome.model.project.Project;
import de.haumacher.ehome.planning.DevicePlanning;

/**
 * Places the devices among the unplaced devices of a project that hold a whole individual address
 * instead of their device number in the line of that address (haumacher/ehome #203).
 *
 * <p>
 * <code>ehome.physical:BusDevice#address</code> is the device number in the line (0..255); area and
 * line follow from the line holding the device. Before haumacher/ehome #183, a device a scan did not
 * identify was a record holding the whole individual address (area, line and device in 4, 4 and 8
 * bits) and its serial number in hex; the startup action of the migration
 * <code>Issue_183_Device_stubs</code> ({@link DeviceStubMigration}) turned each record into a stub in
 * the line of its address. A record that action did not reach is left among the unplaced devices of
 * its project with the whole address, e.g. 4359 for 1.1.7.
 * </p>
 *
 * <p>
 * Each such device is placed in the line of its address with its device number (the line and its area
 * are created where the project has none, as "Take over" creates them for a device found there); where
 * the line has a device at the number, it stays among the unplaced devices with its device number, and
 * the log names the address it had. A stub gets its serial number in the form of ETS (Base64) and an
 * ID, as {@link DeviceStubMigration} gives them. Each step is logged.
 * </p>
 *
 * <p>
 * The startup action of the migration <code>Issue_203_Device_numbers</code>: runs once, in the started
 * application after the migration, in a transaction of the migration service.
 * </p>
 */
public class DeviceNumberRepair implements StartupAction {

	private static final String PROJECT_TYPE = "ehome.project:Project";

	@Override
	public void perform() {
		int placed = 0;
		int unplaced = 0;
		TLClass type = (TLClass) TLModelUtil.findType(PROJECT_TYPE);
		for (Project project : MetaElementUtil.getAllDirectInstancesOf(type, Project.class)) {
			for (TLObject device : new ArrayList<TLObject>(project.getUnassignedDevices())) {
				if (((BusDevice) device).getAddress() instanceof Integer address
					&& address.intValue() > DevicePlanning.MAX_NUMBER) {
					if (place(project, device, address.intValue())) {
						placed++;
					} else {
						unplaced++;
					}
				}
			}
		}
		Logger.info("Devices with a whole individual address instead of their device number (haumacher/ehome #203): "
			+ placed + " placed in their line, " + unplaced + " left among the unplaced devices.",
			DeviceNumberRepair.class);
	}

	/**
	 * Places the given device of the given project, which holds the given whole individual address,
	 * in the line of that address with its device number; where the line has a device at the number,
	 * the device is left among the unplaced devices with its device number. A stub gets its serial
	 * number in the form of ETS (Base64) where it is in hex; the device gets an ID.
	 *
	 * <p>
	 * Must be called in a transaction.
	 * </p>
	 *
	 * @param project
	 *        The project (<code>ehome.project:Project</code>) holding the device among its unplaced
	 *        devices.
	 * @param device
	 *        The device (<code>ehome.physical:BusDevice</code>).
	 * @param full
	 *        The individual address the device holds: area, line and device number in 4, 4 and 8
	 *        bits.
	 * @return Whether the device was placed in its line.
	 */
	static boolean place(TLObject project, TLObject device, int full) {
		int number = full & 0xFF;
		String addressText = FoundDevice.formatAddress(full);
		String what = MetaLabelProvider.INSTANCE.getLabel(device.tType()) + " " + addressText + " of "
			+ MetaLabelProvider.INSTANCE.getLabel(project);
		BusDevice busDevice = (BusDevice) device;
		if (DeviceStubs.isStub(device) && busDevice.getSerialNumber() instanceof String hex
			&& hex.matches("[0-9A-Fa-f]+") && hex.length() % 2 == 0) {
			busDevice.setSerialNumber(Base64.getEncoder().encodeToString(HexFormat.of().parseHex(hex)));
		}
		boolean free = InstallationBuilder.device(project, full) == null;
		busDevice.setAddress(Integer.valueOf(number));
		if (free) {
			((Project) project).removeUnassignedDevice(busDevice);
			TLObject line = InstallationBuilder.line(project, full >>> 12 & 0x0F, full >>> 8 & 0x0F);
			InstallationBuilder.add(line, "devices", device);
			Logger.info(what + " placed in its line with the device number " + number + ".", DeviceNumberRepair.class);
		} else {
			Logger.info(what + " left among the unplaced devices with the device number " + number
				+ ": the project has a device at " + addressText + ".", DeviceNumberRepair.class);
		}
		ExtIds.assign(device);
		return free;
	}

}
