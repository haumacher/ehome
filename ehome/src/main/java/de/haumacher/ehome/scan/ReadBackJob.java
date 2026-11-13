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
import com.top_logic.knowledge.service.HistoryUtils;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.layout.provider.MetaLabelProvider;
import com.top_logic.layout.view.job.JobBody;
import com.top_logic.layout.view.job.JobMonitor;
import com.top_logic.model.TLObject;
import com.top_logic.util.error.TopLogicException;

import de.haumacher.ehome.job.Uninterruptible;
import de.haumacher.ehome.programming.download.BusException;
import de.haumacher.ehome.programming.download.BusPath;
import de.haumacher.ehome.programming.download.CalimeroReader;
import de.haumacher.ehome.programming.download.DeviceReader;
import de.haumacher.ehome.programming.download.DownloadPlan;
import de.haumacher.ehome.programming.download.DownloadRecord;
import de.haumacher.ehome.programming.download.ProgrammingReport;

/**
 * The work of the command "Read the configuration" of a device found by a scan
 * (<code>&lt;start-job&gt;</code> body of <code>projects/scan.view.xml</code>): reads the device's
 * memory through the project's gateway with {@link CalimeroReader} (nothing is written), decodes it
 * into the project's device ({@link ReadBack}) in a transaction, and records that the device holds
 * the project's state (as a download would: <code>loaded</code>, <code>lastDownload</code>,
 * <code>downloadRevision</code>).
 *
 * <p>
 * The job is started with the row of the device (see {@link ScanRows}). The result is a
 * {@link ProgrammingReport}: what was read and decoded, and whether the image of the device as read
 * back equals the memory read.
 * </p>
 */
public class ReadBackJob implements JobBody {

	/**
	 * Configuration of {@link ReadBackJob}.
	 */
	public interface Config extends PolymorphicConfiguration<ReadBackJob> {

		@Override
		@ClassDefault(ReadBackJob.class)
		Class<? extends ReadBackJob> getImplementationClass();
	}

	/**
	 * Creates a {@link ReadBackJob} from configuration.
	 */
	@CalledByReflection
	public ReadBackJob(InstantiationContext context, Config config) {
		// No configuration.
	}

	@Override
	public Object run(JobMonitor job, List<Object> arguments) throws Exception {
		// The cancel interrupt reaches only the blocking bus accesses, never the database (TL #30196).
		return Uninterruptible.run(() -> work(job, arguments));
	}

	private Object work(JobMonitor job, List<Object> arguments) throws Exception {
		Object row = arguments.get(0);
		ResKey veto = ScanFunctions.readVeto(row);
		if (veto != null) {
			throw new TopLogicException(veto);
		}
		FoundDevice found = ScanRows.found(row);
		TLObject project = ScanRows.project(row);
		TLObject device = InstallationBuilder.device(project, found.address());
		BusPath gateway = BusPath.of(project);
		String gatewayText = gateway.text();

		job.message(de.haumacher.ehome.programming.download.I18NConstants.PROGRESS_CONNECTING__GATEWAY.fill(gatewayText));
		ProgrammingReport report = new ProgrammingReport();
		ReadBack.Memory memory;
		try (DeviceReader reader = openReader(gateway, found.address())) {
			memory = ReadBack.read(device, reader, (done, total, what) -> {
				job.progress(done, total);
				job.message(what == null ? null : de.haumacher.ehome.programming.download.I18NConstants.PROGRESS_READING__WHAT.fill(what));
			});
		} catch (BusException ex) {
			report.add("error", de.haumacher.ehome.programming.download.I18NConstants.LEVEL_FAILURE, "", ex.getErrorKey());
			return report.toMap(I18NConstants.READBACK_FAILED__DEVICE_GATEWAY_REASON.fill(found.addressText(), gatewayText,
				ex.getErrorKey()), false);
		}

		ReadBack.Report result;
		String deviceLabel = MetaLabelProvider.INSTANCE.getLabel(device);
		try (Transaction tx = PersistencyLayer.getKnowledgeBase()
			.beginTransaction(I18NConstants.COMMIT_READ_BACK__DEVICE.fill(deviceLabel))) {
			result = ReadBack.apply(device, memory);
			tx.commit();
		}
		if (result.identical()) {
			// The device holds what the project now says.
			DownloadRecord.record(device, DownloadPlan.Kind.FULL, HistoryUtils.getLastRevision().getCommitNumber(),
				I18NConstants.COMMIT_READ_BACK_IDENTICAL__DEVICE.fill(deviceLabel));
		}

		String where = found.addressText();
		for (ResKey problem : result.problems()) {
			report.add("warning", de.haumacher.ehome.programming.download.I18NConstants.LEVEL_DIFFERENT, where, problem);
		}
		if (!result.createdAddresses().isEmpty()) {
			report.add("info", de.haumacher.ehome.programming.download.I18NConstants.LEVEL_NOTE, where,
				I18NConstants.READBACK_CREATED_ADDRESSES__ADDRESSES.fill(String.join(", ", result.createdAddresses())));
		}
		if (result.flags() > 0) {
			report.add("info", de.haumacher.ehome.programming.download.I18NConstants.LEVEL_NOTE, where,
				I18NConstants.READBACK_FLAGS__COUNT.fill(result.flags()));
		}
		if (result.differences().isEmpty()) {
			report.add("info", de.haumacher.ehome.programming.download.I18NConstants.LEVEL_IDENTICAL, where,
				I18NConstants.READBACK_EQUAL__COUNT.fill(result.compared()));
		} else {
			report.add("error", de.haumacher.ehome.programming.download.I18NConstants.LEVEL_DIFFERENT, where,
				I18NConstants.READBACK_DIFFERENT__DIFFERENT_COUNT_ADDRESSES.fill(result.differences().size(), result.compared(),
					String.join(", ", result.differences().stream().map(a -> String.format("%04X", a)).toList())));
		}
		return report.toMap(I18NConstants.READBACK_DONE__DEVICE_GATEWAY_SETTINGS_CONNECTIONS.fill(where, gatewayText,
			result.settings(), result.connections()), result.identical());
	}

	/**
	 * Opens the connection to the device with the given address on the given path (the project's
	 * gateway or controller); a test replaces it.
	 */
	protected DeviceReader openReader(BusPath path, int address) throws BusException {
		return CalimeroReader.open(path, address);
	}

}
