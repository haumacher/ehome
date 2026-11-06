/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.etsImport;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.top_logic.basic.util.ResKey;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.RefetchTimeout;
import com.top_logic.layout.view.job.JobBody;
import com.top_logic.layout.view.job.JobMonitor;
import com.top_logic.layout.view.job.JobPhase;
import com.top_logic.util.Resources;

/**
 * {@link JobMonitor} for tests of a job body run without a view: records the phases, the progress
 * and the messages (in English) the body reports, and never cancels.
 */
public class RecordingJobMonitor implements JobMonitor {

	private final List<String> _phases = new ArrayList<>();

	private final List<double[]> _progress = new ArrayList<>();

	private final List<String> _messages = new ArrayList<>();

	/**
	 * Runs the given job body with this monitor and the given arguments, and makes what it committed
	 * visible to the calling thread (an import job commits on a thread of its own).
	 */
	public Object run(JobBody body, List<Object> arguments) throws Exception {
		try {
			return body.run(this, arguments);
		} finally {
			refetch();
		}
	}

	private static void refetch() {
		try {
			PersistencyLayer.getKnowledgeBase().refetch();
		} catch (RefetchTimeout ex) {
			throw new RuntimeException("Refetch after the job timed out.", ex);
		}
	}

	/**
	 * The phases entered, in order.
	 */
	public List<String> getPhases() {
		return _phases;
	}

	/**
	 * The progress reported, each as <code>{done, total}</code>.
	 */
	public List<double[]> getProgress() {
		return _progress;
	}

	/**
	 * The messages reported, resolved.
	 */
	public List<String> getMessages() {
		return _messages;
	}

	@Override
	public void setPhases(List<JobPhase> phases) {
		// Ignored.
	}

	@Override
	public void beginPhase(String name) {
		_phases.add(name);
	}

	@Override
	public void progress(double done, double total) {
		_progress.add(new double[] { done, total });
	}

	@Override
	public void fraction(double fraction) {
		// Ignored.
	}

	@Override
	public void indeterminate() {
		// Ignored.
	}

	@Override
	public void message(ResKey message) {
		_messages.add(message == null ? null : Resources.getInstance(Locale.ENGLISH).getString(message));
	}

	@Override
	public void checkCancelled() {
		// Never cancelled.
	}

}
