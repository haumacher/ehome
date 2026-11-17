/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.view;

import java.util.List;

import com.top_logic.layout.view.command.ActionScope;
import com.top_logic.layout.view.job.JobBody;
import com.top_logic.layout.view.job.StartJobAction;

/**
 * The values of the inputs of a <code>&lt;start-job&gt;</code> among the values its {@link JobBody} is
 * started with.
 *
 * <p>
 * The engine hands a body the arguments of the command followed by the inputs of the job, an input
 * named as an argument of the command moved behind the others
 * ({@link StartJobAction#jobScope(ActionScope, List)}). A body therefore reads its inputs from the
 * end: what stands before them depends on the inputs of the command, which the executability of the
 * command needs (e.g. <code>busState</code>, the device of the job run before), not on the job.
 * </p>
 */
public final class JobInputs {

	private JobInputs() {
		// Static helpers only.
	}

	/**
	 * The values of the given number of inputs of the job, in the order of its
	 * <code>&lt;inputs&gt;</code>.
	 *
	 * @param arguments
	 *        The values the body is started with.
	 * @param count
	 *        The number of inputs the job declares.
	 */
	public static List<Object> of(List<Object> arguments, int count) {
		if (arguments.size() < count) {
			throw new IllegalArgumentException("Not " + count + " inputs of the job among " + arguments);
		}
		return arguments.subList(arguments.size() - count, arguments.size());
	}

}
