/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.programming.download;

import java.util.List;

import com.top_logic.basic.CalledByReflection;
import com.top_logic.basic.config.InstantiationContext;
import com.top_logic.basic.config.PolymorphicConfiguration;
import com.top_logic.basic.config.annotation.Name;
import com.top_logic.basic.config.annotation.defaults.ClassDefault;
import com.top_logic.basic.config.annotation.defaults.FormattedDefault;
import com.top_logic.basic.util.ResKey;
import com.top_logic.layout.view.job.JobBody;
import com.top_logic.layout.view.job.JobMonitor;
import com.top_logic.model.TLObject;
import com.top_logic.util.error.TopLogicException;

/**
 * The work of the download command of a device (<code>&lt;start-job&gt;</code> body): plans the
 * download, connects to the bus on the project's {@link BusPath} (its gateway or its controller) through
 * {@link CalimeroWriter}, performs the plan with
 * progress per operation, and records it at the device on success.
 *
 * <p>
 * The device is the first object the job is started with (the <code>device</code> input). The result is a
 * {@link ProgrammingReport}. A refusal (writing switched off, no gateway or controller, a plan with problems, no
 * connection) fails the job with its reason; a failure of the device is the report's outcome.
 * </p>
 */
public class DownloadJob implements JobBody {

	/**
	 * Configuration of {@link DownloadJob}.
	 */
	public interface Config extends PolymorphicConfiguration<DownloadJob> {

		@Override
		@ClassDefault(DownloadJob.class)
		Class<? extends DownloadJob> getImplementationClass();

		/**
		 * What the download writes.
		 */
		@Name("kind")
		@FormattedDefault("FULL")
		DownloadPlan.Kind getKind();
	}

	private final DownloadPlan.Kind _kind;

	/**
	 * Creates a {@link DownloadJob} from configuration.
	 */
	@CalledByReflection
	public DownloadJob(InstantiationContext context, Config config) {
		_kind = config.getKind();
	}

	@Override
	public Object run(JobMonitor job, List<Object> arguments) throws Exception {
		TLObject device = ProgrammingFunctions.device(arguments);
		ResKey veto = ProgrammingFunctions.downloadVeto(device, _kind);
		if (veto != null) {
			throw new TopLogicException(veto);
		}
		DownloadPlan plan = DownloadPlanner.plan(device, _kind);
		BusPath path = BusPath.of(device);
		String gatewayText = path.text();

		job.message(I18NConstants.PROGRESS_CONNECTING__GATEWAY.fill(gatewayText));
		DownloadExecutor.Result result;
		try (CalimeroWriter access = CalimeroWriter.open(path, plan.getIndividualAddress())) {
			result = DownloadService.download(device, plan, access, new DownloadExecutor(), (number, count, operation) -> {
				job.progress(number - 1, count);
				job.message(I18NConstants.PROGRESS_OPERATION__NUMBER_COUNT_TEXT.fill(number, count, operation.describe()));
			});
		} catch (BusException ex) {
			throw new TopLogicException(I18NConstants.ERROR_CONNECTION__PATH_DETAIL.fill(gatewayText, ex.getMessage()), ex);
		}
		job.progress(result.done(), result.count());

		ProgrammingReport report = new ProgrammingReport();
		if (!result.success()) {
			report.add("error", I18NConstants.LEVEL_FAILURE, result.failed().step(),
				result.failed().describe() + ": " + result.failure());
		}
		for (Operation operation : plan.getOperations()) {
			if (operation instanceof Operation.Skip) {
				report.add("info", I18NConstants.LEVEL_NOTE, operation.step(), operation.describe());
			}
		}
		String target = plan.getDevice();
		ResKey summary = result.success()
			? I18NConstants.DOWNLOAD_DONE__DEVICE_GATEWAY_COUNT_SECONDS.fill(target, gatewayText, result.done(),
				String.format("%.1f", result.duration().toMillis() / 1000.0))
			: I18NConstants.DOWNLOAD_FAILED__DEVICE_GATEWAY_NUMBER_REASON.fill(target, gatewayText, result.done() + 1,
				result.failure());
		return report.toMap(summary, result.success());
	}

}
