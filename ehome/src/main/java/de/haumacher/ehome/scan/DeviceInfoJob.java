/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.scan;

import java.util.List;

import com.top_logic.basic.CalledByReflection;
import com.top_logic.basic.config.InstantiationContext;
import com.top_logic.basic.config.PolymorphicConfiguration;
import com.top_logic.basic.config.annotation.defaults.ClassDefault;
import com.top_logic.basic.util.ResKey;
import com.top_logic.layout.view.job.JobBody;
import com.top_logic.layout.view.job.JobMonitor;
import com.top_logic.model.TLObject;
import com.top_logic.util.error.TopLogicException;

import de.haumacher.ehome.programming.DownloadImage;
import de.haumacher.ehome.programming.download.BusException;
import de.haumacher.ehome.programming.download.BusPath;
import de.haumacher.ehome.programming.download.CalimeroReader;
import de.haumacher.ehome.programming.download.DeviceReader;
import de.haumacher.ehome.programming.download.ProgrammingFunctions;

/**
 * The work of the command "Device info" of a device (tab "Programming", haumacher/ehome #175): reads
 * the device at its individual address on the project's {@link BusPath} (its gateway or its
 * controller) with {@link CalimeroReader}, nothing is written, and answers the {@link DeviceInfo} as
 * {@link DeviceInfo#toMap()}.
 *
 * <p>
 * The device is the last value the job is started with ({@link ProgrammingFunctions#device(List)}). A device that does not answer is a result
 * ("no device answers at 1.3.5 through ..."); only a path that cannot be opened ends the job with a
 * failure ({@link DeviceInfo#failure(TLObject, ResKey)}). A cancel ends the reading at once while it
 * waits for the device (the accesses of {@link CalimeroReader} are interruptible sections of the
 * job's {@link com.top_logic.basic.thread.CancelScope}, TL #30196).
 * </p>
 */
public class DeviceInfoJob implements JobBody {

	/**
	 * Configuration of {@link DeviceInfoJob}.
	 */
	public interface Config extends PolymorphicConfiguration<DeviceInfoJob> {

		@Override
		@ClassDefault(DeviceInfoJob.class)
		Class<? extends DeviceInfoJob> getImplementationClass();
	}

	/**
	 * Creates a {@link DeviceInfoJob} from configuration.
	 */
	@CalledByReflection
	public DeviceInfoJob(InstantiationContext context, Config config) {
		// No configuration.
	}

	@Override
	public Object run(JobMonitor job, List<Object> arguments) throws Exception {
		TLObject device = ProgrammingFunctions.device(arguments);
		ResKey veto = DeviceInfo.veto(device);
		if (veto != null) {
			throw new TopLogicException(veto);
		}
		BusPath path = BusPath.of(device);
		String pathText = path.text();
		int address = DownloadImage.individualAddress(device).intValue();
		String addressText = FoundDevice.formatAddress(address);

		job.message(de.haumacher.ehome.programming.download.I18NConstants.PROGRESS_CONNECTING__GATEWAY.fill(pathText));
		job.indeterminate();
		try (DeviceReader reader = openReader(path, address)) {
			job.message(I18NConstants.MESSAGE_READING__ADDRESS.fill(addressText));
			return DeviceInfo.read(device, pathText, reader).toMap();
		} catch (BusException ex) {
			return DeviceInfo.failure(device,
				I18NConstants.INFO_FAILED__ADDRESS_PATH_REASON.fill(addressText, pathText, ex.getErrorKey()));
		}
	}

	/**
	 * Opens the connection to the device with the given address on the given path (the project's
	 * gateway or controller); a test replaces it.
	 */
	protected DeviceReader openReader(BusPath path, int address) throws BusException {
		return CalimeroReader.open(path, address);
	}

}
