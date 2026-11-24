/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.programming.download;

import com.top_logic.basic.util.ResKey;
import com.top_logic.model.TLObject;

/**
 * A download into a device: the plan performed by the {@link DownloadExecutor}, and on success the
 * record at the device ({@link DownloadRecord}). A failed download leaves the device's record as it
 * was.
 *
 * <p>
 * A download of the {@link DownloadPlan.Kind#PARAMETERS parameters only} needs the project's
 * application in the device. Its checks, before anything is written, read what the device holds
 * ({@link Operation.ApplicationCheck}: the program version or identity, the load states, the code the
 * download leaves to the device). A device answering otherwise gets the complete download instead
 * (haumacher/ehome #224), planned anew and performed on the same connection; the {@link Outcome}
 * names the reason. A failure of the access itself (no answer, access refused) is the download's
 * failure, as for any other operation.
 * </p>
 */
public class DownloadService {

	/**
	 * What a download did.
	 *
	 * @param plan
	 *        The plan performed: the one given, or the complete download that replaced a download of
	 *        the parameters only.
	 * @param result
	 *        The result of performing it.
	 * @param completeInstead
	 *        Why the complete download replaced the download of the parameters only (the check that
	 *        found another application in the device); <code>null</code> if the plan given was
	 *        performed.
	 */
	public record Outcome(DownloadPlan plan, DownloadExecutor.Result result, ResKey completeInstead) {
		// Pure value.
	}

	/**
	 * Performs the plan on the device and records it if it succeeds.
	 *
	 * @see #perform(TLObject, DownloadPlan, DeviceAccess, DownloadExecutor, DownloadExecutor.Listener)
	 */
	public static DownloadExecutor.Result download(TLObject device, DownloadPlan plan, DeviceAccess access,
			DownloadExecutor executor, DownloadExecutor.Listener listener) throws InterruptedException {
		return perform(device, plan, access, executor, listener).result();
	}

	/**
	 * Performs the plan on the device, the complete download instead of a download of the parameters
	 * only whose checks find another application in the device, and records what was performed if it
	 * succeeds.
	 */
	public static Outcome perform(TLObject device, DownloadPlan plan, DeviceAccess access, DownloadExecutor executor,
			DownloadExecutor.Listener listener) throws InterruptedException {
		DownloadExecutor.Result result = executor.execute(plan, access, listener);
		DownloadPlan performed = plan;
		ResKey completeInstead = null;
		if (plan.getKind() == DownloadPlan.Kind.PARAMETERS && result.otherApplication()
			&& nothingWritten(plan, result.done())) {
			completeInstead = result.reason();
			performed = DownloadPlanner.plan(device, DownloadPlan.Kind.FULL);
			if (!performed.isExecutable()) {
				// The complete download is refused: the reason of the check stays the outcome.
				return new Outcome(plan, result, null);
			}
			result = executor.execute(performed, access, listener);
		}
		if (result.success()) {
			DownloadRecord.record(device, performed.getKind(), performed.getRevision());
		}
		return new Outcome(performed, result, completeInstead);
	}

	private static boolean nothingWritten(DownloadPlan plan, int done) {
		for (int n = 0; n < done; n++) {
			if (plan.getOperations().get(n).writes()) {
				return false;
			}
		}
		return true;
	}

}
