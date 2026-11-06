/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.importer;

import com.top_logic.basic.AbortExecutionException;
import com.top_logic.basic.util.ResKey;

/**
 * Callback of an {@link Importer} telling how far the import of a file has come (haumacher/ehome
 * #84).
 *
 * <p>
 * The measure is the XML entries of the archive (those of the project archive
 * <code>P-xxxx.zip</code> of ETS 5/6 included), counted beforehand by {@link ArchiveContents#read}.
 * The importer reports before each entry, with the number of entries imported so far, again when it
 * has learned the name of what the entry holds (an application program, a project), and once at the
 * end with all entries done.
 * </p>
 *
 * <p>
 * A callback may stop the import by throwing an {@link AbortExecutionException} (as a job does when
 * it was cancelled): the import ends with that exception, and nothing of it must be committed.
 * </p>
 */
@FunctionalInterface
public interface ImportProgress {

	/**
	 * The callback reporting nothing.
	 */
	ImportProgress NONE = (done, total, message) -> {
		// Nobody listens.
	};

	/**
	 * Reports the progress of the import.
	 *
	 * @param done
	 *        The number of entries imported so far.
	 * @param total
	 *        The number of entries of the file, 0 if not counted.
	 * @param message
	 *        What is being imported, in the user's terms (master data, the application program of a
	 *        manufacturer, the project).
	 */
	void progress(int done, int total, ResKey message);

}
