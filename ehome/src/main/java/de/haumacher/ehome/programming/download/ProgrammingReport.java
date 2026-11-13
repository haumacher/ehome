/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.programming.download;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.top_logic.basic.util.ResKey;
import com.top_logic.util.Resources;

/**
 * The outcome of a download or a comparison as the report dialog
 * (<code>projects/programming-report.view.xml</code>) shows it: a dictionary with
 * {@value #SUMMARY} (a sentence), {@value #SUCCESS} and {@value #MESSAGES}, each message a
 * dictionary with <code>severity</code> (<code>error</code>, <code>warning</code>,
 * <code>info</code>), <code>level</code> (its label), <code>message</code> and <code>file</code>
 * (here: the step or block it belongs to), the keys of the messages of the ETS import, so that
 * <code>projects/import-message.view.xml</code> shows them.
 */
public class ProgrammingReport {

	/** Key of the sentence summarizing the outcome. */
	public static final String SUMMARY = "summary";

	/** Key of whether the download or comparison succeeded. */
	public static final String SUCCESS = "success";

	/** Key of the list of messages. */
	public static final String MESSAGES = "messages";

	/**
	 * The report: a dictionary that a person reads as its summary (the job status shows the result
	 * of a job by its label).
	 */
	private static final class Outcome extends HashMap<String, Object> {

		private final String _summary;

		Outcome(String summary) {
			_summary = summary;
		}

		@Override
		public String toString() {
			return _summary;
		}
	}

	private final Resources _resources = Resources.getInstance();

	private final List<Map<String, Object>> _messages = new ArrayList<>();

	/**
	 * Adds a message.
	 *
	 * @param severity
	 *        <code>error</code>, <code>warning</code> or <code>info</code>.
	 * @param level
	 *        The label of the message's kind.
	 * @param where
	 *        The step or block the message belongs to: a technical name (the label of a step of the
	 *        load procedure, an individual address), or a {@link ResKey} shown in the reader's
	 *        language.
	 * @param message
	 *        The text, shown in the reader's language.
	 */
	public void add(String severity, ResKey level, Object where, ResKey message) {
		Map<String, Object> entry = new HashMap<>();
		entry.put("severity", severity);
		entry.put("level", _resources.getString(level));
		entry.put("file", where instanceof ResKey key ? _resources.getString(key) : where);
		entry.put("message", _resources.getString(message));
		_messages.add(entry);
	}

	/**
	 * The report as dictionary.
	 */
	public Map<String, Object> toMap(ResKey summary, boolean success) {
		String text = _resources.getString(summary);
		Map<String, Object> result = new Outcome(text);
		result.put(SUMMARY, text);
		result.put(SUCCESS, Boolean.valueOf(success));
		result.put(MESSAGES, _messages);
		return result;
	}

}
