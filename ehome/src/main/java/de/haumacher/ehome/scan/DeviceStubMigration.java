/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.scan;

import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

import com.top_logic.basic.Logger;
import com.top_logic.element.meta.MetaElementUtil;
import com.top_logic.knowledge.service.migration.StartupAction;
import com.top_logic.layout.provider.MetaLabelProvider;
import com.top_logic.model.TLClass;
import com.top_logic.model.TLObject;
import com.top_logic.model.util.TLModelUtil;

import de.haumacher.ehome.knx.ExtIds;

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
 * service.
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
				|| !(stub.tValueByName("address") instanceof Integer address)) {
				continue;
			}
			int full = address.intValue();
			int number = full & 0xFF;
			if (stub.tValueByName("serialNumber") instanceof String hex && hex.matches("[0-9A-Fa-f]+")
				&& hex.length() % 2 == 0) {
				stub.tUpdateByName("serialNumber", Base64.getEncoder().encodeToString(HexFormat.of().parseHex(hex)));
			}
			String addressText = FoundDevice.formatAddress(full);
			if (InstallationBuilder.device(project, full) == null) {
				List<TLObject> unassigned = ExtIds.list(project, "unassignedDevices");
				unassigned.remove(stub);
				project.tUpdateByName("unassignedDevices", unassigned);
				stub.tUpdateByName("address", Integer.valueOf(number));
				TLObject line = InstallationBuilder.line(project, full >>> 12 & 0x0F, full >>> 8 & 0x0F);
				InstallationBuilder.add(line, "devices", stub);
				placed++;
				Logger.info("Unidentified device " + addressText + " of " + MetaLabelProvider.INSTANCE.getLabel(project)
					+ " placed in its line as a stub (haumacher/ehome #183).", DeviceStubMigration.class);
			} else {
				stub.tUpdateByName("address", Integer.valueOf(number));
				unplaced++;
				Logger.info("Unidentified device " + addressText + " of " + MetaLabelProvider.INSTANCE.getLabel(project)
					+ " left among the unplaced devices as a stub: the project has a device at " + addressText
					+ " (haumacher/ehome #183).", DeviceStubMigration.class);
			}
			ExtIds.assign(stub);
		}
		Logger.info("Unidentified devices turned into stubs (haumacher/ehome #183): " + placed + " placed in their line, "
			+ unplaced + " among the unplaced devices.", DeviceStubMigration.class);
	}

}
