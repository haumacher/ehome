/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.programming.download;

import com.top_logic.model.TLObject;

/**
 * A download into a device: the plan performed by the {@link DownloadExecutor}, and on success the
 * record at the device ({@link DownloadRecord}). A failed download leaves the device's record as it
 * was.
 */
public class DownloadService {

	/**
	 * Performs the plan on the device and records it if it succeeds.
	 */
	public static DownloadExecutor.Result download(TLObject device, DownloadPlan plan, DeviceAccess access,
			DownloadExecutor executor, DownloadExecutor.Listener listener) throws InterruptedException {
		DownloadExecutor.Result result = executor.execute(plan, access, listener);
		if (result.success()) {
			DownloadRecord.record(device, plan.getKind());
		}
		return result;
	}

}
