/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.monitor;

import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;

import com.top_logic.basic.io.binary.BinaryData;
import com.top_logic.basic.io.binary.BinaryDataFactory;
import com.top_logic.model.TLClassifier;
import com.top_logic.model.TLObject;

import de.haumacher.ehome.programming.GroupAddressConnections;

/**
 * The export of a recording of the group monitor (<code>ehome.monitor:Recording</code>) as a CSV
 * file: one line per telegram in the order they were received, the columns separated by
 * semicolons, a header line naming the columns.
 */
public class RecordingCsv {

	/** The content type of the export. */
	public static final String CONTENT_TYPE = "text/csv";

	/** The header line. */
	public static final String HEADER =
		"number;time;source;device;address;name;service;priority;repeated;hops;data;value;own";

	/**
	 * The lines of the export of the given recording, the header first.
	 *
	 * @param timeZone
	 *        The time zone to write the times in.
	 */
	public static List<String> lines(TLObject recording, TimeZone timeZone) {
		SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
		format.setTimeZone(timeZone);
		List<TLObject> telegrams = new ArrayList<>();
		for (Object telegram : (Collection<?>) recording.tValueByName("telegrams")) {
			telegrams.add((TLObject) telegram);
		}
		telegrams.sort(Comparator.comparingInt(t -> t.tValueByName("number") instanceof Integer n ? n : 0));
		List<String> result = new ArrayList<>(telegrams.size() + 1);
		result.add(HEADER);
		for (TLObject telegram : telegrams) {
			TLObject sender = (TLObject) telegram.tValueByName("sender");
			TLObject groupAddress = (TLObject) telegram.tValueByName("groupAddress");
			Object time = telegram.tValueByName("time");
			result.add(String.join(";",
				text(telegram.tValueByName("number")),
				time instanceof Date date ? format.format(date) : "",
				text(telegram.tValueByName("source")),
				sender == null ? "" : field(GroupAddressConnections.deviceLabel(sender)),
				text(telegram.tValueByName("address")),
				groupAddress == null ? "" : field(text(groupAddress.tValueByName("name"))),
				classifier(telegram.tValueByName("service")),
				classifier(telegram.tValueByName("priority")),
				text(telegram.tValueByName("repeated")),
				text(telegram.tValueByName("hopCount")),
				text(telegram.tValueByName("data")),
				field(text(telegram.tValueByName("value"))),
				text(telegram.tValueByName("own"))));
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
		Object name = recording.tValueByName("name");
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
