/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.job;

import static junit.framework.TestCase.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import com.top_logic.basic.util.ResKey;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.RefetchTimeout;
import com.top_logic.layout.view.channel.DefaultViewChannel;
import com.top_logic.layout.view.job.JobBody;
import com.top_logic.layout.view.job.JobMonitor;
import com.top_logic.layout.view.job.JobPhase;
import com.top_logic.layout.view.job.JobRunner;
import com.top_logic.layout.view.job.JobState;
import com.top_logic.util.Resources;

/**
 * Runs a job body as a cancelable <code>&lt;start-job&gt;</code> runs it, headless: by the engine's
 * {@link JobRunner} on a worker thread of the scheduler, in the job's
 * {@link com.top_logic.basic.thread.CancelScope}, so that a {@link #cancel()} reaches the body exactly
 * as the cancel button of the job's display does (TL #30196, #30250).
 *
 * <p>
 * Every report of the body is recorded as an {@link #events() event} and handed to the
 * {@link #onEvent(Consumer) hook} of the test, on the body's thread right after the report, so that a
 * test cancels the job at a chosen point of its work: <code>phase:&lt;name&gt;</code>,
 * <code>message:&lt;English text&gt;</code>, <code>uncancelable</code>.
 * </p>
 *
 * <p>
 * The suite of a test using it needs the <code>SchedulerService</code>.
 * </p>
 */
public class JobHarness {

	/** How long a test waits for a job before it counts as hanging. */
	private static final long TIMEOUT = 60_000;

	private final DefaultViewChannel _channel = new DefaultViewChannel("job");

	private final CountDownLatch _finished = new CountDownLatch(1);

	private final JobRunner _runner;

	private final List<String> _events = Collections.synchronizedList(new ArrayList<>());

	private volatile Consumer<String> _hook = event -> {
		// Nothing to do.
	};

	private volatile Thread _worker;

	/**
	 * Creates a {@link JobHarness} for a job announcing the given phases.
	 */
	public JobHarness(List<JobPhase> phases) {
		_runner = new JobRunner(null, _channel, phases, true, 0, state -> _finished.countDown());
	}

	/**
	 * The phases of the import jobs of the views: <code>read</code>, <code>import</code>,
	 * <code>save</code>.
	 */
	public static List<JobPhase> importPhases() {
		return List.of(new JobPhase("read", ResKey.text("Reading")), new JobPhase("import", ResKey.text("Importing")),
			new JobPhase("save", ResKey.text("Saving")));
	}

	/**
	 * Sets the hook called with every event of the body, on the body's thread.
	 */
	public JobHarness onEvent(Consumer<String> hook) {
		_hook = hook;
		return this;
	}

	/**
	 * Starts the given body with the given arguments.
	 */
	public void start(JobBody body, List<Object> arguments) {
		_runner.start((job, args) -> {
			_worker = Thread.currentThread();
			return body.run(new Recording(job), args);
		}, arguments);
	}

	/**
	 * The thread the body runs on, <code>null</code> before it started.
	 */
	public Thread worker() {
		return _worker;
	}

	/**
	 * Cancels the job, as the cancel button of its display does.
	 */
	public void cancel() {
		_runner.cancel();
	}

	/**
	 * Waits for the job to end.
	 *
	 * @return The state the job ended in.
	 */
	public JobState await() throws InterruptedException {
		assertTrue("The job must end within " + TIMEOUT + " ms.", _finished.await(TIMEOUT, TimeUnit.MILLISECONDS));
		// The body committed on the worker thread: the calling thread sees it after a refetch.
		try {
			PersistencyLayer.getKnowledgeBase().refetch();
		} catch (RefetchTimeout ex) {
			throw new RuntimeException("Refetch after the job timed out.", ex);
		}
		return (JobState) _channel.get();
	}

	/**
	 * The reports of the body, see the class comment.
	 */
	public List<String> events() {
		synchronized (_events) {
			return new ArrayList<>(_events);
		}
	}

	private void event(String event) {
		_events.add(event);
		_hook.accept(event);
	}

	/**
	 * The monitor the body sees: the job's, recording every report.
	 */
	private final class Recording implements JobMonitor {

		private final JobMonitor _job;

		Recording(JobMonitor job) {
			_job = job;
		}

		@Override
		public void setPhases(List<JobPhase> phases) {
			_job.setPhases(phases);
		}

		@Override
		public void beginPhase(String name) {
			_job.beginPhase(name);
			event("phase:" + name);
		}

		@Override
		public void progress(double done, double total) {
			_job.progress(done, total);
		}

		@Override
		public void fraction(double fraction) {
			_job.fraction(fraction);
		}

		@Override
		public void indeterminate() {
			_job.indeterminate();
		}

		@Override
		public void message(ResKey message) {
			_job.message(message);
			event("message:" + (message == null ? null : Resources.getInstance(Locale.ENGLISH).getString(message)));
		}

		@Override
		public void checkCancelled() {
			_job.checkCancelled();
		}

		@Override
		public void uncancelable() {
			_job.uncancelable();
			event("uncancelable");
		}
	}

}
