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
import com.top_logic.layout.provider.MetaLabelProvider;
import com.top_logic.layout.view.job.JobBody;
import com.top_logic.layout.view.job.JobMonitor;
import com.top_logic.model.TLObject;
import com.top_logic.util.error.TopLogicException;

import de.haumacher.ehome.view.JobInputs;

/**
 * The work of the download command of a device (<code>&lt;start-job&gt;</code> body): plans the
 * download, connects to the bus on the project's {@link BusPath} (its gateway or its controller) through
 * {@link CalimeroWriter}, performs the plan with
 * progress per operation, and records it at the device on success.
 *
 * <p>
 * The device is the input <code>device</code> of the job ({@link ProgrammingFunctions#device(List)}). With
 * {@link Config#getParametersChoice()}, the input before it says whether the download writes the
 * parameters only ({@link DownloadPlan.Kind#PARAMETERS}, haumacher/ehome #195). The result is a
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

		/**
		 * Whether the job has two inputs, the first one (a Boolean) saying whether the download writes
		 * the parameters only, the second the device; <code>true</code> there makes a download of the
		 * {@link #getKind() kind} {@link DownloadPlan.Kind#FULL} one of the parameters only.
		 */
		@Name("parameters-choice")
		boolean getParametersChoice();
	}

	private final DownloadPlan.Kind _kind;

	private final boolean _parametersChoice;

	/**
	 * Creates a {@link DownloadJob} from configuration.
	 */
	@CalledByReflection
	public DownloadJob(InstantiationContext context, Config config) {
		_kind = config.getKind();
		_parametersChoice = config.getParametersChoice();
	}

	@Override
	public Object run(JobMonitor job, List<Object> arguments) throws Exception {
		TLObject device = ProgrammingFunctions.device(arguments);
		DownloadPlan.Kind kind = _kind;
		if (_parametersChoice && kind == DownloadPlan.Kind.FULL
			&& Boolean.TRUE.equals(JobInputs.of(arguments, 2).get(0))) {
			kind = DownloadPlan.Kind.PARAMETERS;
		}
		return download(job, device, kind);
	}

	/**
	 * Downloads into the device: refuses with the reason (a {@link TopLogicException}) what cannot be
	 * started, performs the plan of the given kind through the project's path to the bus, records it
	 * on success. Holds the project's {@link DownloadLock} meanwhile; refuses while another download of
	 * the project runs.
	 *
	 * @return The report ({@link ProgrammingReport#toMap(ResKey, boolean)}).
	 */
	public static Object download(JobMonitor job, TLObject device, DownloadPlan.Kind kind) throws Exception {
		// The downloads of a project run one after another (haumacher/ehome #200).
		try (DownloadLock lock = DownloadLock.acquire(device,
			I18NConstants.LOCK_DOWNLOAD__DEVICE.fill(MetaLabelProvider.INSTANCE.getLabel(device)))) {
			return downloadLocked(job, device, kind);
		}
	}

	private static Object downloadLocked(JobMonitor job, TLObject device, DownloadPlan.Kind kind) throws Exception {
		ResKey veto = ProgrammingFunctions.downloadVeto(device, kind);
		if (veto != null) {
			throw new TopLogicException(veto);
		}
		DownloadPlan plan = DownloadPlanner.plan(device, kind);
		BusPath path = BusPath.of(device);
		String gatewayText = path.text();

		job.message(I18NConstants.PROGRESS_CONNECTING__GATEWAY.fill(gatewayText));
		DownloadExecutor.Result result;
		try (CalimeroWriter access = CalimeroWriter.open(path, plan.getIndividualAddress())) {
			result = DownloadService.download(device, plan, access, new DownloadExecutor(), (number, count, operation) -> {
				job.progress(number - 1, count);
				job.message(I18NConstants.PROGRESS_OPERATION__NUMBER_COUNT_TEXT.fill(number, count, operation.description()));
			});
		} catch (BusException ex) {
			throw new TopLogicException(I18NConstants.ERROR_CONNECTION__PATH_DETAIL.fill(gatewayText, ex.getErrorKey()), ex);
		}
		job.progress(result.done(), result.count());

		ProgrammingReport report = new ProgrammingReport();
		if (!result.success()) {
			report.add("error", I18NConstants.LEVEL_FAILURE, result.failed().step(),
				I18NConstants.REPORT_OPERATION_FAILED__OPERATION_REASON.fill(result.failed().description(), result.reason()));
		}
		for (Operation operation : plan.getOperations()) {
			if (operation instanceof Operation.Skip) {
				report.add("info", I18NConstants.LEVEL_NOTE, operation.step(), operation.description());
			}
		}
		String target = plan.getDevice();
		ResKey summary = result.success()
			? I18NConstants.DOWNLOAD_DONE__DEVICE_GATEWAY_COUNT_SECONDS.fill(target, gatewayText, result.done(),
				Double.valueOf(result.duration().toMillis() / 1000.0))
			: I18NConstants.DOWNLOAD_FAILED__DEVICE_GATEWAY_NUMBER_REASON.fill(target, gatewayText, result.done() + 1,
				result.reason());
		return report.toMap(summary, result.success());
	}

}
