/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.runtime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import com.top_logic.basic.util.ResKey;
import com.top_logic.basic.util.ResourcesModule;
import com.top_logic.util.Resources;

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
	 *        The English reason, as the controller's loader gives it.
	 * @param message
	 *        The same for the user, in the languages of the application; <code>null</code> where there
	 *        is none (a refusal of the controller, a problem the export found).
	 */
	public record Refusal(String path, String reason, ResKey message) {

		/**
		 * A refusal without a message for the user, e.g. one the controller gave.
		 */
		public Refusal(String path, String reason) {
			this(path, reason, null);
		}

		/**
		 * What the user reads: the {@link #message()} in the language of the current user, the
		 * English {@link #reason()} where there is none.
		 */
		public String text() {
			return message == null ? reason : Resources.getInstance().getString(message);
		}

		/**
		 * The {@link #message()} in every language of the application, as an internationalized text
		 * that keeps them without the message's key (for an attribute of the type
		 * <code>tl.model.i18n:I18NString</code>); <code>null</code> where there is no message.
		 */
		public ResKey translations() {
			if (message == null) {
				return null;
			}
			ResKey.Builder builder = ResKey.builder();
			for (Locale locale : ResourcesModule.getInstance().getSupportedLocales()) {
				builder.add(locale, Resources.getInstance(locale).getString(message));
			}
			return builder.build();
		}

		@Override
		public String toString() {
			return (path.isEmpty() ? "(document)" : path) + ": " + reason;
		}
	}

	private Kind _kind = Kind.NONE;

	private final List<Refusal> _refusals = new ArrayList<>();

	private final List<String> _notes = new ArrayList<>();

	/**
	 * Adds a refusal without a message for the user (a problem the export found, a text that does not
	 * parse or a binding of a deleted datapoint); the first one decides the {@link #getKind() kind}.
	 */
	public void add(Kind kind, String path, String reason) {
		add(kind, path, reason, null);
	}

	/**
	 * Adds a refusal; the first one decides the {@link #getKind() kind}.
	 *
	 * @param reason
	 *        The English reason, as the controller's loader gives it.
	 * @param message
	 *        The same for the user.
	 */
	public void add(Kind kind, String path, String reason, ResKey message) {
		if (_kind == Kind.NONE) {
			_kind = kind;
		}
		_refusals.add(new Refusal(path, reason, message));
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
