/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.programming.download;

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

import de.haumacher.ehome.programming.DeviceConfiguration;
import de.haumacher.ehome.programming.DownloadImage.Block;

/**
 * The work of the compare command of a device (<code>&lt;start-job&gt;</code> body): reads the
 * device on the project's {@link BusPath} (its gateway or its controller) with {@link CalimeroReader} (nothing is written) and compares
 * it with the download image ({@link DeviceComparison}).
 *
 * <p>
 * The device is the input <code>device</code> of the job ({@link ProgrammingFunctions#device(List)}). The result is a
 * {@link ProgrammingReport}: the facts read, and per block of the image "identical" or the
 * differing addresses.
 * </p>
 *
 * <p>
 * The progress counts the bytes read of all bytes to compare and moves with every read of at most
 * {@link DownloadPlanner#MAX_CHUNK} bytes; the message names the block and the address read
 * (haumacher/ehome #190). A cancel ends the comparison before its next read, or at once while a read
 * waits for the device (the accesses of {@link CalimeroReader} are interruptible sections of the
 * job's {@link com.top_logic.basic.thread.CancelScope}, TL #30196); through a controller only the bus
 * session ends (haumacher/ehome #189). The cancelled job returns the report of what was compared up
 * to then.
 * </p>
 */
public class CompareJob implements JobBody {

	/**
	 * Configuration of {@link CompareJob}.
	 */
	public interface Config extends PolymorphicConfiguration<CompareJob> {

		@Override
		@ClassDefault(CompareJob.class)
		Class<? extends CompareJob> getImplementationClass();
	}

	/**
	 * Creates a {@link CompareJob} from configuration.
	 */
	@CalledByReflection
	public CompareJob(InstantiationContext context, Config config) {
		// No configuration.
	}

	@Override
	public Object run(JobMonitor job, List<Object> arguments) throws Exception {
		TLObject device = ProgrammingFunctions.device(arguments);
		ResKey veto = ProgrammingFunctions.compareVeto(device);
		if (veto != null) {
			throw new TopLogicException(veto);
		}
		DownloadPlan plan = DownloadPlanner.plan(device, DownloadPlan.Kind.FULL);
		BusPath path = BusPath.of(device);
		String gatewayText = path.text();

		job.message(I18NConstants.PROGRESS_CONNECTING__GATEWAY.fill(gatewayText));
		ProgrammingReport report = new ProgrammingReport();
		DeviceComparison.Result result;
		long[] total = { 0 };
		try (DeviceReader reader = openReader(path, plan.getIndividualAddress())) {
			result = DeviceComparison.compare(DeviceConfiguration.program(device), plan, reader,
				new DeviceComparison.Listener() {
					@Override
					public void block(int number, int count, Block block) {
						job.checkCancelled();
					}

					@Override
					public void read(Block block, int address, long done, long all) {
						job.checkCancelled();
						total[0] = all;
						job.progress(done, all);
						job.message(I18NConstants.PROGRESS_READING_AT__BLOCK_ADDRESS_RANGE.fill(block.label(),
							String.format("%04X", address), String.format("%04X-%04X", block.address(), block.end() - 1)));
					}
				});
		} catch (BusException ex) {
			report.add("error", I18NConstants.LEVEL_FAILURE, "", ex.getErrorKey());
			return report.toMap(I18NConstants.COMPARE_FAILED__DEVICE_GATEWAY_REASON.fill(plan.getDevice(), gatewayText,
				ex.getErrorKey()), false);
		}
		if (!result.aborted()) {
			job.progress(total[0], total[0]);
		}
		if (result.descriptor() != null) {
			report.add("info", I18NConstants.LEVEL_NOTE, "", I18NConstants.REPORT_DESCRIPTOR__VALUE.fill(result.descriptor()));
		}
		for (ResKey fact : result.factKeys()) {
			report.add("info", I18NConstants.LEVEL_NOTE, "", fact);
		}
		for (DeviceComparison.BlockResult block : result.blocks()) {
			ResKey where = blockRange(block.label(), block.address(), block.address() + block.length());
			if (block.abortedAt() != null) {
				String at = String.format("%04X", block.abortedAt());
				if (block.different().isEmpty()) {
					report.add("info", I18NConstants.LEVEL_NOTE, where,
						I18NConstants.REPORT_BLOCK_ABORTED_EQUAL__ADDRESS_COUNT.fill(at, block.compared()));
				} else {
					report.add("warning", I18NConstants.LEVEL_DIFFERENT, where,
						I18NConstants.REPORT_BLOCK_ABORTED_DIFFERENT__ADDRESS_DIFFERENT_COUNT_RANGES.fill(at,
							block.different().size(), block.compared(), block.ranges()));
				}
			} else if (block.failureKey() != null) {
				report.add("error", I18NConstants.LEVEL_FAILURE, where, block.failureKey());
			} else if (block.identical()) {
				report.add("info", I18NConstants.LEVEL_IDENTICAL, where,
					I18NConstants.REPORT_BLOCK_EQUAL__COUNT.fill(block.compared()));
			} else {
				report.add("warning", I18NConstants.LEVEL_DIFFERENT, where,
					I18NConstants.REPORT_BLOCK_DIFFERENT__DIFFERENT_COUNT_RANGES.fill(block.different().size(),
						block.compared(), block.ranges()));
			}
		}
		if (result.aborted()) {
			return report.toMap(I18NConstants.COMPARE_ABORTED__DEVICE_GATEWAY_EQUAL_BLOCKS.fill(plan.getDevice(),
				gatewayText, result.identicalBlocks(), result.blocks().size()), false);
		}
		for (Block skipped : plan.getUnloadedBlocks()) {
			report.add("info", I18NConstants.LEVEL_NOTE, blockRange(skipped.label(), skipped.address(), skipped.end()),
				I18NConstants.REPORT_NOT_COMPARED);
		}
		return report.toMap(I18NConstants.COMPARE_DONE__DEVICE_GATEWAY_EQUAL_BLOCKS.fill(plan.getDevice(), gatewayText,
			result.identicalBlocks(), result.blocks().size()), true);
	}

	/**
	 * Opens the connection to the device with the given address on the given path (the project's
	 * gateway or controller); a test replaces it.
	 */
	protected DeviceReader openReader(BusPath path, int address) throws BusException {
		return CalimeroReader.open(path, address);
	}

	/**
	 * A block with its range of addresses, for a person: <code>Association table, 4100-4123</code>.
	 *
	 * @param end
	 *        The address after the last byte.
	 */
	public static ResKey blockRange(ResKey block, int address, int end) {
		return I18NConstants.BLOCK_RANGE__BLOCK_FROM_TO.fill(block, String.format("%04X", address),
			String.format("%04X", end - 1));
	}

}
