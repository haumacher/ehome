/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.runtime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The outcome of checking a runtime image: whether it is valid, and if not, why, with the JSON path
 * of each refused value (spec/runtime-image.md, section 4).
 */
public final class ImageReport {

	/**
	 * The kinds of refusals, named as the conformance cases name them.
	 */
	public enum Kind {
		/** Valid. */
		NONE("none"),
		/** Not JSON (<code>image.bad_json</code>). */
		SYNTAX("syntax"),
		/** A newer major version of the schema (<code>image.version</code>). */
		VERSION("version"),
		/** Everything else the document says wrong (<code>image.invalid</code>). */
		INVALID("invalid"),
		/** More than the limit of bytes (<code>image.too_large</code>). */
		TOO_LARGE("too large");

		private final String _name;

		Kind(String name) {
			_name = name;
		}

		/**
		 * The name of the kind in the conformance cases (<code>expect.kind</code>).
		 */
		public String externalName() {
			return _name;
		}
	}

	/**
	 * A refused value: where it is in the document and why it is refused.
	 *
	 * @param path
	 *        The JSON path, e.g. <code>datapoints[3].knx.send</code>; empty for the whole document.
	 * @param reason
	 *        The English reason.
	 */
	public record Refusal(String path, String reason) {

		@Override
		public String toString() {
			return (path.isEmpty() ? "(document)" : path) + ": " + reason;
		}
	}

	private Kind _kind = Kind.NONE;

	private final List<Refusal> _refusals = new ArrayList<>();

	private final List<String> _notes = new ArrayList<>();

	/**
	 * Adds a refusal; the first one decides the {@link #getKind() kind}.
	 */
	public void add(Kind kind, String path, String reason) {
		if (_kind == Kind.NONE) {
			_kind = kind;
		}
		_refusals.add(new Refusal(path, reason));
	}

	/**
	 * Adds a remark that refuses nothing, e.g. a part of the image E-Home does not check.
	 */
	public void note(String note) {
		_notes.add(note);
	}

	/**
	 * Whether the image is valid.
	 */
	public boolean isValid() {
		return _refusals.isEmpty();
	}

	/**
	 * The kind of the first refusal, {@link Kind#NONE} for a valid image.
	 */
	public Kind getKind() {
		return _kind;
	}

	/**
	 * All refusals in the order they were found.
	 */
	public List<Refusal> getRefusals() {
		return Collections.unmodifiableList(_refusals);
	}

	/**
	 * The remarks that refuse nothing.
	 */
	public List<String> getNotes() {
		return Collections.unmodifiableList(_notes);
	}

	@Override
	public String toString() {
		return _kind.externalName() + " " + _refusals;
	}

}
