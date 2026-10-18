/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.monitor;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;

import com.top_logic.layout.provider.MetaLabelProvider;
import com.top_logic.model.TLObject;
import com.top_logic.model.TLType;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.Resources;

import de.haumacher.ehome.programming.GroupAddressConnections;

/**
 * The entries of a recording of the group monitor (<code>ehome.monitor:Recording</code>): its
 * telegrams (<code>ehome.monitor:Telegram</code>) and the events of a controller that are no
 * telegrams (<code>ehome.monitor:Event</code>, haumacher/ehome #55), in the order they were
 * received, which their common numbering gives.
 */
public class RecordingEntries {

	/**
	 * The telegrams and events of the given recording, ordered by their number.
	 */
	public static List<TLObject> entries(TLObject recording) {
		List<TLObject> result = new ArrayList<>();
		add(result, recording.tValueByName("telegrams"));
		add(result, recording.tValueByName("events"));
		result.sort(Comparator.comparingInt(RecordingEntries::number));
		return result;
	}

	private static void add(List<TLObject> result, Object entries) {
		if (entries instanceof Collection<?> collection) {
			for (Object entry : collection) {
				result.add((TLObject) entry);
			}
		}
	}

	/**
	 * The number of an entry in its recording, 0 if it has none.
	 */
	public static int number(TLObject entry) {
		return entry.tValueByName("number") instanceof Integer n ? n.intValue() : 0;
	}

	/**
	 * Whether the given entry is an event of a controller (<code>ehome.monitor:Event</code>) rather
	 * than a telegram.
	 */
	public static boolean isEvent(TLObject entry) {
		TLType type = TLModelUtil.findType("ehome.monitor:Event");
		return entry.tType() == type;
	}

	/**
	 * The entries of the given recording as rows of a table, with the members of the rows of the log
	 * of the monitor ({@link GroupMonitor#snapshot()}): <code>number</code>, <code>time</code> (with
	 * the date), <code>kind</code> (the source for a person: KNX or the kind of the event),
	 * <code>sender</code> (the device or address of a telegram's sender, what an event reports),
	 * <code>source</code>, <code>address</code>, <code>name</code>, <code>value</code>,
	 * <code>service</code>, <code>priority</code>, <code>repeated</code>, <code>hops</code>,
	 * <code>data</code> and <code>own</code>.
	 *
	 * @param timeZone
	 *        The time zone to show the times in.
	 */
	public static List<MonitorRow> rows(TLObject recording, TimeZone timeZone) {
		SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
		format.setTimeZone(timeZone);
		String knx = Resources.getInstance().getString(I18NConstants.EVENT_KIND_KNX);
		List<MonitorRow> result = new ArrayList<>();
		for (TLObject entry : entries(recording)) {
			MonitorRow row = new MonitorRow(entry);
			row.put("number", Integer.valueOf(number(entry)));
			Object time = entry.tValueByName("time");
			row.put("time", time instanceof Date date ? format.format(date) : "");
			if (isEvent(entry)) {
				Object kind = entry.tValueByName("kind");
				row.put("kind", kind == null ? "" : MetaLabelProvider.INSTANCE.getLabel(kind));
				row.put("sender", text(entry.tValueByName("text")));
				row.put("source", "");
				row.put("address", "");
				row.put("name", text(entry.tValueByName("name")));
				row.put("value", text(entry.tValueByName("value")));
				row.put("data", "");
				row.put("own", Boolean.FALSE);
			} else {
				TLObject sender = (TLObject) entry.tValueByName("sender");
				TLObject groupAddress = (TLObject) entry.tValueByName("groupAddress");
				String source = text(entry.tValueByName("source"));
				row.put("kind", knx);
				row.put("sender",
					sender != null && sender.tValid() ? GroupAddressConnections.deviceLabel(sender) : source);
				row.put("source", source);
				row.put("address", text(entry.tValueByName("address")));
				row.put("name",
					groupAddress != null && groupAddress.tValid() ? text(groupAddress.tValueByName("name")) : "");
				row.put("value", text(entry.tValueByName("value")));
				row.put("service", entry.tValueByName("service"));
				row.put("priority", entry.tValueByName("priority"));
				row.put("repeated", entry.tValueByName("repeated"));
				row.put("hops", entry.tValueByName("hopCount"));
				row.put("data", text(entry.tValueByName("data")));
				row.put("own", Boolean.valueOf(Boolean.TRUE.equals(entry.tValueByName("own"))));
			}
			result.add(row);
		}
		return result;
	}

	private static String text(Object value) {
		return value == null ? "" : value.toString();
	}

}
