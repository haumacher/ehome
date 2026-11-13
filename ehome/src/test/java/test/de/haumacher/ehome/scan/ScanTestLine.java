/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.scan;

import test.de.haumacher.ehome.programming.ReadDeviceMemory;

import com.top_logic.basic.util.ResKey;

import de.haumacher.ehome.scan.BusScanner;
import de.haumacher.ehome.scan.CalimeroScanBus;
import de.haumacher.ehome.scan.FoundDevice;

/**
 * Read-only tool scanning a line of the user's KNX test system (haumacher/ehome #9): the devices
 * that answer, what each tells about itself, and the gateway through local device management.
 *
 * <p>
 * Usage: <code>ScanTestLine &lt;area&gt; &lt;line&gt;</code>, e.g. <code>ScanTestLine 1 3</code>. It
 * connects to the one gateway of the test system ({@link ReadDeviceMemory#GATEWAY}), never to a
 * discovered one: another reachable router is the user's home installation. The scan only connects
 * and reads descriptors and properties.
 * </p>
 */
public class ScanTestLine {

	/**
	 * Entry point, see the class comment.
	 */
	public static void main(String[] args) throws Exception {
		int area = Integer.parseInt(args[0]);
		int line = Integer.parseInt(args[1]);
		try (CalimeroScanBus bus = CalimeroScanBus.open(ReadDeviceMemory.GATEWAY)) {
			BusScanner.Result result = BusScanner.scan(bus, new BusScanner.Request(area, line, false, true, false),
				new BusScanner.Listener() {
					@Override
					public void message(ResKey text) {
						System.out.println("# " + text);
					}

					@Override
					public void progress(int done, int total) {
						// Silent.
					}
				});
			for (FoundDevice device : result.devices()) {
				System.out.println(device.addressText() + " via " + device.source() + ": descriptor "
					+ device.descriptorText() + ", manufacturer " + device.manufacturerId() + ", serial "
					+ device.serialText() + ", program " + device.programVersionText() + ", hardware "
					+ device.hardwareTypeText() + ", order " + device.orderInfo() + ", states " + device.loadStates()
					+ ", not readable " + device.notes());
			}
			for (ResKey note : result.notes()) {
				System.out.println("note: " + note);
			}
		}
	}

}
