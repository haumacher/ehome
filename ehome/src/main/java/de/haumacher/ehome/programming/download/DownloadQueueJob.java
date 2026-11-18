/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.programming.download;

import java.util.List;

import com.top_logic.basic.CalledByReflection;
import com.top_logic.basic.config.InstantiationContext;
import com.top_logic.basic.config.PolymorphicConfiguration;
import com.top_logic.basic.config.annotation.defaults.ClassDefault;
import com.top_logic.layout.view.job.JobBody;
import com.top_logic.layout.view.job.JobMonitor;

import de.haumacher.ehome.view.JobInputs;

/**
 * The work of "Start" of the programming of several devices (<code>&lt;start-job&gt;</code> body,
 * haumacher/ehome #200): works through the {@link DownloadQueue}, its only input, over the project's
 * path to the bus ({@link DownloadQueue#run(JobMonitor, DownloadQueue.Opener)}).
 *
 * <p>
 * The result is the summary of what was done; a cancel of the job aborts the download running, cancels
 * the waiting ones and ends the job with that summary.
 * </p>
 */
public class DownloadQueueJob implements JobBody {

	/**
	 * Configuration of {@link DownloadQueueJob}.
	 */
	public interface Config extends PolymorphicConfiguration<DownloadQueueJob> {

		@Override
		@ClassDefault(DownloadQueueJob.class)
		Class<? extends DownloadQueueJob> getImplementationClass();
	}

	/**
	 * Creates a {@link DownloadQueueJob} from configuration.
	 */
	@CalledByReflection
	public DownloadQueueJob(InstantiationContext context, Config config) {
		// No configuration.
	}

	@Override
	public Object run(JobMonitor job, List<Object> arguments) throws Exception {
		if (!(JobInputs.of(arguments, 1).get(0) instanceof DownloadQueue queue)) {
			throw new IllegalArgumentException("No download queue as the input of the job among " + arguments);
		}
		return queue.run(job, DownloadQueue.BUS);
	}

}
