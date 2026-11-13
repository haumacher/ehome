/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.scan;

import java.util.List;

import com.top_logic.basic.CalledByReflection;
import com.top_logic.basic.config.InstantiationContext;
import com.top_logic.basic.config.PolymorphicConfiguration;
import com.top_logic.basic.config.annotation.Name;
import com.top_logic.basic.config.annotation.defaults.ClassDefault;
import com.top_logic.basic.util.ResKey;
import com.top_logic.layout.view.job.JobBody;
import com.top_logic.layout.view.job.JobMonitor;
import com.top_logic.util.error.TopLogicException;

import de.haumacher.ehome.programming.download.BusException;
import de.haumacher.ehome.programming.download.BusPath;
import de.haumacher.ehome.programming.download.CalimeroWriter;
import de.haumacher.ehome.programming.download.DeviceAccess;
import de.haumacher.ehome.programming.download.ProgrammingReport;

/**
 * The work of the commands "LED on" / "LED off" of a device found by a scan: switches the
 * programming mode of the device, so that its programming LED shows which device of the
 * installation it is (haumacher/ehome #9).
 *
 * <p>
 * This writes into the device, so it goes through {@link CalimeroWriter}, which refuses unless
 * writing is switched on for the installation (the configuration variable <code>tl_ehome_programming_write_enabled</code>).
 * The programming mode is property 54 of the device object; a device of BCU 1 (mask 001x) keeps it
 * in bit 0 of the memory byte 0060 with an even parity in bit 7, as Calimero's
 * <code>ManagementProcedures#setProgrammingMode</code> writes it. The state is read back afterwards.
 * </p>
 */
public class LedJob implements JobBody {

	/** BCU 1: the memory byte holding the programming mode. */
	public static final int BCU1_PROGRAMMING_MODE = 0x0060;

	/**
	 * Configuration of {@link LedJob}.
	 */
	public interface Config extends PolymorphicConfiguration<LedJob> {

		@Override
		@ClassDefault(LedJob.class)
		Class<? extends LedJob> getImplementationClass();

		/**
		 * Whether to switch the programming mode on (or off).
		 */
		@Name("on")
		boolean getOn();
	}

	private final boolean _on;

	/**
	 * Creates a {@link LedJob} from configuration.
	 */
	@CalledByReflection
	public LedJob(InstantiationContext context, Config config) {
		_on = config.getOn();
	}

	@Override
	public Object run(JobMonitor job, List<Object> arguments) throws Exception {
		Object row = arguments.get(0);
		ResKey veto = ScanFunctions.ledVeto(row);
		if (veto != null) {
			throw new TopLogicException(veto);
		}
		FoundDevice found = ScanRows.found(row);
		BusPath gateway = BusPath.of(ScanRows.project(row));
		String gatewayText = gateway.text();
		job.message(de.haumacher.ehome.programming.download.I18NConstants.PROGRESS_CONNECTING__GATEWAY.fill(gatewayText));
		ProgrammingReport report = new ProgrammingReport();
		boolean reported;
		try (CalimeroWriter access = CalimeroWriter.open(gateway, found.address())) {
			reported = setProgrammingMode(access, found, _on);
		} catch (BusException ex) {
			report.add("error", de.haumacher.ehome.programming.download.I18NConstants.LEVEL_FAILURE, found.addressText(),
				ex.getErrorKey());
			return report.toMap(I18NConstants.LED_FAILED__ADDRESS_GATEWAY_REASON.fill(found.addressText(), gatewayText,
				ex.getErrorKey()), false);
		}
		return report.toMap((_on ? I18NConstants.LED_DONE_ON__ADDRESS_GATEWAY_REPORTED
			: I18NConstants.LED_DONE_OFF__ADDRESS_GATEWAY_REPORTED).fill(found.addressText(), gatewayText,
				reported ? I18NConstants.LED_ON : I18NConstants.LED_OFF), true);
	}

	/**
	 * Switches the programming mode of the device.
	 *
	 * @return Whether the device reports its programming mode on afterwards.
	 */
	public static boolean setProgrammingMode(DeviceAccess access, FoundDevice found, boolean on) throws BusException {
		if (found.descriptor() != null && BusScanner.isBcu1(found.descriptor().intValue())) {
			byte[] current = access.readMemory(BCU1_PROGRAMMING_MODE, 1);
			int value = (current[0] & 0x7E) | (on ? 0x01 : 0x00);
			if (Integer.bitCount(value) % 2 != 0) {
				value |= 0x80;
			}
			access.writeMemory(BCU1_PROGRAMMING_MODE, new byte[] { (byte) value });
			byte[] after = access.readMemory(BCU1_PROGRAMMING_MODE, 1);
			return (after[0] & 0x01) != 0;
		}
		access.writeProperty(BusScanner.DEVICE_OBJECT, BusScanner.PID_PROGRAMMING_MODE, 1, 1,
			new byte[] { (byte) (on ? 0x01 : 0x00) });
		byte[] after = access.readProperty(BusScanner.DEVICE_OBJECT, BusScanner.PID_PROGRAMMING_MODE, 1, 1);
		return after.length > 0 && (after[0] & 0x01) != 0;
	}

}
