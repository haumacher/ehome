/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.importer;

import com.top_logic.knowledge.service.KnowledgeBaseException;

/**
 * The transactions of the import of a product file of a manufacturer (haumacher/ehome #160).
 *
 * <p>
 * A complete product database holds hundreds of megabytes of application programs. Imported in
 * one transaction, the database holds all of them in memory until the commit. Imported step by
 * step, the import commits the data the programs share (master data, catalogs, hardware, files)
 * first, then each application program in a transaction of its own, and lets go of what a step
 * committed (see {@link JoiningModelBinding#release(String)}). A program that cannot be imported
 * is rolled back alone and reported (see {@link Importer#getFailedPrograms()}); the others are
 * imported.
 * </p>
 *
 * @see Importer#importProductData(com.top_logic.basic.io.binary.BinaryData, ArchiveContents,
 *      ImportProgress, ImportSteps)
 */
public interface ImportSteps {

	/**
	 * No steps: the whole import runs in the caller's transaction, and a program that cannot be
	 * imported fails the import.
	 */
	ImportSteps NONE = new ImportSteps() {
		@Override
		public boolean isStepwise() {
			return false;
		}

		@Override
		public void commitShared() {
			// One transaction.
		}

		@Override
		public void commitProgram(String program) {
			// One transaction.
		}

		@Override
		public void rollback(Throwable cause) {
			throw new UnsupportedOperationException("No steps to roll back.");
		}
	};

	/**
	 * Whether the import is committed step by step; otherwise the methods committing do nothing.
	 */
	boolean isStepwise();

	/**
	 * Commits the data the application programs of the file share (master data, catalogs, hardware,
	 * files), and begins the transaction of the next step.
	 */
	void commitShared() throws KnowledgeBaseException;

	/**
	 * Commits the application program just read, and begins the transaction of the next step.
	 *
	 * @param program
	 *        The ID of the program.
	 */
	void commitProgram(String program) throws KnowledgeBaseException;

	/**
	 * Rolls back the current step, and begins the transaction of the next step.
	 *
	 * @param cause
	 *        Why the step failed.
	 */
	void rollback(Throwable cause);

}
