/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.programming.download;

import java.net.InetSocketAddress;
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

import de.haumacher.ehome.job.Uninterruptible;
import de.haumacher.ehome.programming.DeviceConfiguration;
import de.haumacher.ehome.programming.DownloadImage.Block;

/**
 * The work of the compare command of a device (<code>&lt;start-job&gt;</code> body): reads the
 * device through the project's gateway with {@link CalimeroReader} (nothing is written) and compares
 * it with the download image ({@link DeviceComparison}).
 *
 * <p>
 * The device is the first object the job is started with (the <code>device</code> input). The result is a
 * {@link ProgrammingReport}: the facts read, and per block of the image "identical" or the
 * differing addresses.
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
		// The cancel interrupt reaches only the blocking bus accesses, never the database (TL #30196).
		return Uninterruptible.run(() -> work(job, arguments));
	}

	private static Object work(JobMonitor job, List<Object> arguments) throws Exception {
		TLObject device = ProgrammingFunctions.device(arguments);
		ResKey veto = ProgrammingFunctions.compareVeto(device);
		if (veto != null) {
			throw new TopLogicException(veto);
		}
		DownloadPlan plan = DownloadPlanner.plan(device, DownloadPlan.Kind.FULL);
		InetSocketAddress gateway = Gateways.parse(Gateways.of(device));
		String gatewayText = Gateways.format(gateway);

		job.message(I18NConstants.PROGRESS_CONNECTING__GATEWAY.fill(gatewayText));
		ProgrammingReport report = new ProgrammingReport();
		DeviceComparison.Result result;
		try (CalimeroReader reader = CalimeroReader.open(gateway, plan.getIndividualAddress())) {
			result = DeviceComparison.compare(DeviceConfiguration.program(device), plan, reader,
				(number, count, block) -> {
					job.checkCancelled();
					job.progress(number - 1, count);
					job.message(I18NConstants.PROGRESS_READING__WHAT.fill(block.name()));
				});
		} catch (BusException ex) {
			report.add("error", I18NConstants.LEVEL_FAILURE, "", ex.getMessage());
			return report.toMap(I18NConstants.COMPARE_FAILED__DEVICE_GATEWAY_REASON.fill(plan.getDevice(), gatewayText,
				ex.getMessage()), false);
		}
		report.add("info", I18NConstants.LEVEL_NOTE, "", "Device descriptor (mask version): " + result.descriptor());
		for (String fact : result.facts()) {
			report.add("info", I18NConstants.LEVEL_NOTE, "", fact);
		}
		for (DeviceComparison.BlockResult block : result.blocks()) {
			String where = String.format("%s, %04X-%04X", block.name(), block.address(), block.address() + block.length() - 1);
			if (block.failure() != null) {
				report.add("error", I18NConstants.LEVEL_FAILURE, where, block.failure());
			} else if (block.identical()) {
				report.add("info", I18NConstants.LEVEL_IDENTICAL, where, block.compared() + " bytes compared, all equal.");
			} else {
				report.add("warning", I18NConstants.LEVEL_DIFFERENT, where, block.different().size() + " of "
					+ block.compared() + " bytes differ: " + block.ranges());
			}
		}
		for (Block skipped : plan.getUnloadedBlocks()) {
			report.add("info", I18NConstants.LEVEL_NOTE, String.format("%s, %04X-%04X", skipped.name(), skipped.address(),
				skipped.end() - 1), "Not compared: a download does not write it (its segment lacks the flag 0x80).");
		}
		return report.toMap(I18NConstants.COMPARE_DONE__DEVICE_GATEWAY_EQUAL_BLOCKS.fill(plan.getDevice(), gatewayText,
			result.identicalBlocks(), result.blocks().size()), true);
	}

}
