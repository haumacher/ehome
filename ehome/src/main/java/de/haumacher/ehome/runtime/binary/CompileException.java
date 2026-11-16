/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.runtime.binary;

/**
 * Thrown when a source image cannot be compiled into the binary image: a source that was not
 * validated before (homecontrol <code>spec/runtime-image.md</code> 4: an image that does not
 * validate is never compiled), or one beyond what the binary layout holds.
 */
public class CompileException extends RuntimeException {

	private final String _path;

	private final String _reason;

	/**
	 * Creates a {@link CompileException}.
	 *
	 * @param path
	 *        Where in the source (or the binary image) the problem is.
	 * @param reason
	 *        What it is, in English.
	 */
	public CompileException(String path, String reason) {
		super(path + ": " + reason);
		_path = path;
		_reason = reason;
	}

	/** Where the problem is. */
	public String getPath() {
		return _path;
	}

	/** What the problem is. */
	public String getReason() {
		return _reason;
	}

}
