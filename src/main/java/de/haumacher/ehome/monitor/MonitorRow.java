/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.monitor;

import java.util.LinkedHashMap;
import java.util.Objects;

/**
 * A row of the state board or the log of the group monitor, as a dictionary the views read with
 * <code>$row["value"]</code>.
 *
 * <p>
 * A row stands for its group address (state board) or its telegram (log), named by the entry
 * {@link #KEY}: two rows with the same key are equal, whatever values they show. Every update of
 * the monitor delivers new rows, and a table keeps the selection of a row across the update because
 * the new row for the same address equals the selected one.
 * </p>
 */
public class MonitorRow extends LinkedHashMap<String, Object> {

	/** The entry identifying the row. */
	public static final String KEY = "key";

	/**
	 * Creates a {@link MonitorRow}.
	 *
	 * @param key
	 *        What the row stands for.
	 */
	public MonitorRow(Object key) {
		put(KEY, key);
	}

	/**
	 * A copy of this row.
	 */
	public MonitorRow copy() {
		MonitorRow result = new MonitorRow(get(KEY));
		result.putAll(this);
		return result;
	}

	@Override
	public boolean equals(Object o) {
		return o instanceof MonitorRow other && Objects.equals(other.get(KEY), get(KEY));
	}

	@Override
	public int hashCode() {
		return Objects.hashCode(get(KEY));
	}

}
