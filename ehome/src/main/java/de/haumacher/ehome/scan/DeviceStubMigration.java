/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.scan;

import com.top_logic.basic.Logger;
import com.top_logic.element.meta.MetaElementUtil;
import com.top_logic.knowledge.service.migration.StartupAction;
import com.top_logic.model.TLClass;
import com.top_logic.model.TLObject;
import com.top_logic.model.util.TLModelUtil;

import de.haumacher.ehome.knx.ExtIds;
import de.haumacher.ehome.model.physical.DeviceStub;

/**
 * The migration <code>Issue_183_Device_stubs</code> (haumacher/ehome #183): the records of devices a
 * scan did not identify, which the migration turned into stubs (<code>ehome.physical:DeviceStub</code>)
 * among the unplaced devices of their project, are placed in the line of their address.
 *
 * <p>
 * A record held the whole individual address (area, line and device in 4, 4 and 8 bits) and the serial
 * number in hex; a stub holds the device number in its line, as a device does, and the serial number
 * in the form of ETS (Base64). A stub moves into the line of its address, which is created (with its
 * area) where the project has none, as "Take over" creates it for a device found there: among the
 * unplaced devices, a record would lose its area and line, and the next take-over of the device would
 * create a second stub in the line. Only where the line has another device at the number does the
 * stub stay among the unplaced devices, with its device number. Each stub gets an ID.
 * </p>
 *
 * <p>
 * Runs once, in the started application after the migration, in a transaction of the migration
 * service. The placement is that of {@link DeviceNumberRepair}, the startup action of
 * <code>Issue_203_Device_numbers</code>, which places a record this action did not reach
 * (haumacher/ehome #203).
 * </p>
 */
public class DeviceStubMigration implements StartupAction {

	@Override
	public void perform() {
		int placed = 0;
		int unplaced = 0;
		TLClass type = (TLClass) TLModelUtil.findType(DeviceStubs.STUB_TYPE);
		for (TLObject stub : MetaElementUtil.getAllDirectInstancesOf(type, TLObject.class)) {
			TLObject project = stub.tContainer();
			if (project == null || !ExtIds.isA(project, "ehome.project:Project")
				|| !(((DeviceStub) stub).getAddress() instanceof Integer address)) {
				continue;
			}
			if (DeviceNumberRepair.place(project, stub, address.intValue())) {
				placed++;
			} else {
				unplaced++;
			}
		}
		Logger.info("Unidentified devices turned into stubs (haumacher/ehome #183): " + placed + " placed in their line, "
			+ unplaced + " among the unplaced devices.", DeviceStubMigration.class);
	}

}
