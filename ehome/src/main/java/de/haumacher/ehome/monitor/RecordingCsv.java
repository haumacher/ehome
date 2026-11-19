/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.monitor;

import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;

import com.top_logic.basic.io.binary.BinaryData;
import com.top_logic.basic.io.binary.BinaryDataFactory;
import com.top_logic.model.TLClassifier;
import com.top_logic.model.TLObject;

import de.haumacher.ehome.model.logical.GroupAddress;
import de.haumacher.ehome.model.monitor.Event;
import de.haumacher.ehome.model.monitor.Recording;
import de.haumacher.ehome.model.monitor.Telegram;
import de.haumacher.ehome.programming.GroupAddressConnections;

/**
 * The export of a recording of the group monitor (<code>ehome.monitor:Recording</code>) as a CSV
 * file: one line per telegram in the order they were received, the columns separated by
 * semicolons, a header line naming the columns.
 *
 * <p>
 * The events of a controller that are no telegrams (<code>ehome.monitor:Event</code>,
 * haumacher/ehome #55) are lines in the same order: their kind in the column <code>kind</code>
 * (<code>knx</code> for a telegram), what happened in the column <code>event</code>, the datapoint
 * and the value in <code>name</code> and <code>value</code>; the columns of a telegram stay empty.
 * </p>
 */
public class RecordingCsv {

	/** The content type of the export. */
	public static final String CONTENT_TYPE = "text/csv";

	/** The header line. */
	public static final String HEADER =
		"number;time;source;device;address;name;service;priority;repeated;hops;data;value;own;kind;event";

	/**
	 * The lines of the export of the given recording, the header first.
	 *
	 * @param timeZone
	 *        The time zone to write the times in.
	 */
	public static List<String> lines(TLObject recording, TimeZone timeZone) {
		SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
		format.setTimeZone(timeZone);
		List<TLObject> entries = RecordingEntries.entries(recording);
		List<String> result = new ArrayList<>(entries.size() + 1);
		result.add(HEADER);
		for (TLObject entry : entries) {
			if (RecordingEntries.isEvent(entry)) {
				Event event = (Event) entry;
				Object time = event.getTime();
				result.add(String.join(";",
					text(event.getNumber()),
					time instanceof Date date ? format.format(date) : "",
					"", "", "",
					field(text(event.getName())),
					"", "", "", "", "",
					field(text(event.getValue())),
					"",
					classifier(event.getKind()),
					field(text(event.getText()))));
				continue;
			}
			Telegram telegram = (Telegram) entry;
			TLObject sender = telegram.getSender();
			GroupAddress groupAddress = telegram.getGroupAddress();
			Object time = telegram.getTime();
			result.add(String.join(";",
				text(telegram.getNumber()),
				time instanceof Date date ? format.format(date) : "",
				text(telegram.getSource()),
				sender == null ? "" : field(GroupAddressConnections.deviceLabel(sender)),
				text(telegram.getAddress()),
				groupAddress == null ? "" : field(text(groupAddress.getName())),
				classifier(telegram.getService()),
				classifier(telegram.getPriority()),
				text(telegram.getRepeatedOrNull()),
				text(telegram.getHopCount()),
				text(telegram.getData()),
				field(text(telegram.getValue())),
				text(telegram.getOwnOrNull()),
				"knx",
				""));
		}
		return result;
	}

	/**
	 * The export of the given recording as file, named after the recording.
	 */
	public static BinaryData export(TLObject recording, TimeZone timeZone) {
		StringBuilder content = new StringBuilder();
		for (String line : lines(recording, timeZone)) {
			content.append(line).append("\r\n");
		}
		return BinaryDataFactory.createBinaryData(content.toString().getBytes(StandardCharsets.UTF_8),
			CONTENT_TYPE + ";charset=UTF-8", fileName(recording));
	}

	/**
	 * The file name of the export: the name of the recording with the extension <code>.csv</code>.
	 */
	public static String fileName(TLObject recording) {
		Object name = ((Recording) recording).getName();
		String base = name == null || name.toString().isBlank() ? "recording" : name.toString();
		return base.replaceAll("[\\\\/:*?\"<>|]", "_").trim() + ".csv";
	}

	private static String classifier(Object value) {
		return value instanceof TLClassifier classifier ? classifier.getName() : "";
	}

	private static String text(Object value) {
		return value == null ? "" : value.toString();
	}

	/**
	 * A text as CSV field: quoted where it contains a separator, a quote or a line break.
	 */
	static String field(String text) {
		if (text.indexOf(';') < 0 && text.indexOf('"') < 0 && text.indexOf('\n') < 0 && text.indexOf('\r') < 0) {
			return text;
		}
		return '"' + text.replace("\"", "\"\"") + '"';
	}

}
