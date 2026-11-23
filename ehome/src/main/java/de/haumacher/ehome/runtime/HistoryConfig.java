/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.runtime;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.ToLongFunction;

import com.top_logic.basic.util.ResKey;
import com.top_logic.model.TLClassifier;
import com.top_logic.model.TLFormObjectBase;
import com.top_logic.model.TLObject;
import com.top_logic.util.Resources;

import de.haumacher.ehome.knx.ExtIds;
import de.haumacher.ehome.model.logical.GroupAddress;
import de.haumacher.ehome.model.logical.Recording;
import de.haumacher.ehome.model.project.Project;
import de.haumacher.ehome.model.runtime.ApplicationDatapoint;
import de.haumacher.ehome.model.runtime.ChartElement;
import de.haumacher.ehome.model.runtime.ChartSeries;
import de.haumacher.ehome.monitor.DatapointCodec.Dpt;
import de.haumacher.ehome.programming.GroupAddressTypes;

/**
 * The history the controller keeps of a datapoint (haumacher/ehome #221, homecontrol #115): its setting
 * "Aufzeichnung" ({@link Level}, the attribute <code>recording</code>) says how far back it reaches, and
 * its stores follow from it, a raw ring of samples and archives of consolidated slots; the flag
 * "Zählerstand" (<code>meter</code>) marks a meter reading, which the controller's generic view draws as
 * consumption bars. The control pages only choose what to show: a chart offers the datapoints with a
 * recording and the ranges their recordings reach.
 *
 * <h2>The stores</h2>
 *
 * <p>
 * What the controller's views draw decides the resolution: a line, band or on/off strip has
 * {@value #LINE_POINTS} points per range (6 h, 24 h, 7 d, 30 d, 1 y, 10 y up to the reach), rounded down to
 * the controller's steps; consumption bars have their width (15 min for 6 h, 1 h for 24 h, 1 d for 7 d
 * and 30 d, a month for 1 y, a year for 10 y), and their store reaches one bar further for the first
 * difference. Up to 24 hours a raw ring serves the ranges, beyond them an archive per resolution:
 * </p>
 *
 * <table>
 * <caption>The stores of a recording</caption>
 * <tr><th>Reach</th><th>Line, band, strip</th><th>Meter</th></tr>
 * <tr><td>6 h</td><td>ring 5 min × 6 h</td><td>ring 15 min × 6¼ h</td></tr>
 * <tr><td>24 h</td><td>ring 5 min × 24 h</td><td>ring 15 min × 25 h</td></tr>
 * <tr><td>7 days</td><td>+ archive 3 h × 7 d</td><td>+ archive 1 d × 8 d</td></tr>
 * <tr><td>30 days</td><td>+ archive 12 h × 30 d</td><td>archive 1 d × 31 d</td></tr>
 * <tr><td>1 year</td><td>+ archive 7 d × 1 y</td><td>archive 1 d × 13 months</td></tr>
 * <tr><td>10 years</td><td>+ archive 31 d × 10 y</td><td>+ archive 31 d × 10 y</td></tr>
 * </table>
 * <p>
 * A meter's month bars are summed from day slots, which start at local midnight like the bars; its year
 * bars come from the 31-day archive (a day archive over 10 years would exceed the 2048 slots of an
 * archive), which reaches no further than the controller keeps an archive (10 years).
 * </p>
 *
 * <h2>Bytes</h2>
 *
 * <p>
 * A ring or an archive has <code>ceil(retention / spacing) + 1</code> samples or slots
 * ({@link ImageValidator#samples(long, long)}). The controller (homecontrol #56, runtime image 3.7)
 * counts two budgets:
 * </p>
 * <ul>
 * <li>RAM (<code>history.budget</code>, at most {@value ImageValidator#HISTORY_CAP} bytes): per raw ring
 * {@value #SAMPLE_BYTES} bytes per sample, per archive its open slot and the closed slots of up to an
 * hour not yet written ({@link ImageValidator#archiveRamBytes(long)}; 96 bytes for an hour or more).</li>
 * <li>Flash (<code>history.archiveBudget</code>, at most {@value ImageValidator#ARCHIVE_CAP} bytes): per
 * archive a segment of 4096 bytes per 127 slots ({@link ImageValidator#archiveFlashBytes(long, long)}).</li>
 * </ul>
 * <p>
 * E-Home writes <code>history.budget</code> when the project sets <code>historyBudget</code>, and never
 * <code>history.archiveBudget</code>. Histories beyond the budget or the caps are refused by the image's
 * check at <code>history</code> ({@link ImageValidator}); the project's usage text
 * ({@link #usage(TLObject)}) names the biggest.
 * </p>
 */
public final class HistoryConfig {

	/** The points of a line of a chart of the controller's UI. */
	public static final int LINE_POINTS = 48;

	/** The bytes of a sample of the raw ring. */
	public static final int SAMPLE_BYTES = 8;

	/** The longest retention of an archive the controller keeps: 10 years. */
	public static final long MAX_ARCHIVE_RETENTION = 3650L * 86400;

	private static final long MINUTE = 60;

	private static final long HOUR = 3600;

	private static final long DAY = 86400;

	/** The width of the month bars of a year: a twelfth of 365 days. */
	private static final long MONTH_BAR = 365 * DAY / 12;

	/**
	 * How far back the controller records a datapoint: the classifiers of
	 * <code>ehome.logical:Recording</code> and the reach in seconds of the chart range of the same
	 * length (a year is 365 days).
	 */
	public enum Level {

		/** No history. */
		NONE("none", 0),

		/** 6 hours. */
		SIX_HOURS("sixHours", 6 * HOUR),

		/** 24 hours. */
		DAY("day", HistoryConfig.DAY),

		/** 7 days. */
		WEEK("week", 7 * HistoryConfig.DAY),

		/** 30 days. */
		MONTH("month", 30 * HistoryConfig.DAY),

		/** A year of 365 days. */
		YEAR("year", 365 * HistoryConfig.DAY),

		/** 10 years of 365 days. */
		TEN_YEARS("tenYears", 3650 * HistoryConfig.DAY);

		private final String _classifier;

		private final long _reach;

		Level(String classifier, long reach) {
			_classifier = classifier;
			_reach = reach;
		}

		/** The name of the classifier of <code>ehome.logical:Recording</code>. */
		public String classifier() {
			return _classifier;
		}

		/** How far back the recording reaches: the length of the longest chart range it serves. */
		public long reach() {
			return _reach;
		}

		/** The level of the classifier name; {@link #NONE} for none or an unknown one. */
		public static Level of(String classifier) {
			for (Level level : values()) {
				if (level._classifier.equals(classifier)) {
					return level;
				}
			}
			return NONE;
		}

		/**
		 * The shortest level whose stores reach back at least the given seconds ({@link #NONE} for
		 * none, {@link #TEN_YEARS} for more than it reaches): a history reaching that far, rounded up.
		 */
		public static Level covering(long seconds, boolean meter) {
			if (seconds <= 0) {
				return NONE;
			}
			for (Level level : values()) {
				if (level != NONE && stores(level, meter).reach() >= seconds) {
					return level;
				}
			}
			return TEN_YEARS;
		}
	}

	private HistoryConfig() {
		// Static utility.
	}

	/**
	 * An archive: one slot per resolution, kept for the retention (both in seconds).
	 */
	public record Archive(long resolution, long retention) {

		/** The slots the controller allocates. */
		public long slots() {
			return ImageValidator.samples(retention, resolution);
		}

		/**
		 * The RAM the controller allocates: the open slot and the closed slots of an hour not yet written
		 * ({@link ImageValidator#archiveRamBytes(long)}).
		 */
		public long ramBytes() {
			return ImageValidator.archiveRamBytes(resolution);
		}

		/**
		 * The flash the archive takes: 4096 bytes per segment ({@link ImageValidator#archiveFlashBytes(long, long)}).
		 */
		public long flashBytes() {
			return ImageValidator.archiveFlashBytes(retention, resolution);
		}
	}

	/**
	 * The history of a datapoint: the raw ring (retention and spacing in seconds), the archives, finest
	 * first, and whether it is a meter reading.
	 */
	public record History(long retention, long spacing, List<Archive> archives, boolean meter) {

		/** The samples of the raw ring. */
		public long samples() {
			return ImageValidator.samples(retention, spacing);
		}

		/** The values the ring and the archives hold together. */
		public long values() {
			long result = samples();
			for (Archive archive : archives) {
				result += archive.slots();
			}
			return result;
		}

		/** How far back the history reaches: the longest retention of its ring and archives. */
		public long reach() {
			long result = retention;
			for (Archive archive : archives) {
				result = Math.max(result, archive.retention());
			}
			return result;
		}

		/** The RAM the controller allocates: the raw ring and the archives' open slots. */
		public long ramBytes() {
			long result = samples() * SAMPLE_BYTES;
			for (Archive archive : archives) {
				result += archive.ramBytes();
			}
			return result;
		}

		/** The flash the archives take. */
		public long flashBytes() {
			long result = 0;
			for (Archive archive : archives) {
				result += archive.flashBytes();
			}
			return result;
		}

		/**
		 * The member <code>history</code> of the datapoint in the image: <code>retention</code>,
		 * <code>spacing</code>, <code>meter</code> for a meter reading (schema 1.10) and, where there are
		 * some, <code>archives</code>.
		 */
		public Map<String, Object> json() {
			Map<String, Object> result = new LinkedHashMap<>();
			result.put("retention", Long.valueOf(retention));
			result.put("spacing", Long.valueOf(spacing));
			if (meter) {
				result.put("meter", Boolean.TRUE);
			}
			if (!archives.isEmpty()) {
				List<Object> list = new ArrayList<>();
				for (Archive archive : archives) {
					Map<String, Object> entry = new LinkedHashMap<>();
					entry.put("resolution", Long.valueOf(archive.resolution()));
					entry.put("retention", Long.valueOf(archive.retention()));
					list.add(entry);
				}
				result.put("archives", list);
			}
			return result;
		}
	}

	// ---- the stores of a level -----------------------------------------------------------------

	/**
	 * The stores of the level ({@link HistoryConfig}); <code>null</code> for {@link Level#NONE}.
	 *
	 * @param meter
	 *        Whether the datapoint is a meter reading, drawn as consumption bars.
	 */
	public static History stores(Level level, boolean meter) {
		if (level == Level.NONE) {
			return null;
		}
		TreeMap<Long, Long> archives = new TreeMap<>();
		long spacing;
		long retention;
		if (meter) {
			// 15-minute bars over 6 h, hourly ones over 24 h, each with one bar more.
			spacing = 15 * MINUTE;
			retention = level == Level.SIX_HOURS ? 6 * HOUR + spacing : DAY + HOUR;
			if (level.reach() >= Level.WEEK.reach()) {
				archive(archives, DAY, 8 * DAY);
			}
			if (level.reach() >= Level.MONTH.reach()) {
				archive(archives, DAY, 31 * DAY);
			}
			if (level.reach() >= Level.YEAR.reach()) {
				archive(archives, DAY, 13 * MONTH_BAR);
			}
			if (level.reach() >= Level.TEN_YEARS.reach()) {
				archive(archives, 31 * DAY, MAX_ARCHIVE_RETENTION);
			}
		} else {
			spacing = 5 * MINUTE;
			retention = level == Level.SIX_HOURS ? 6 * HOUR : DAY;
			if (level.reach() >= Level.WEEK.reach()) {
				archive(archives, 3 * HOUR, Level.WEEK.reach());
			}
			if (level.reach() >= Level.MONTH.reach()) {
				archive(archives, 12 * HOUR, Level.MONTH.reach());
			}
			if (level.reach() >= Level.YEAR.reach()) {
				archive(archives, 7 * DAY, Level.YEAR.reach());
			}
			if (level.reach() >= Level.TEN_YEARS.reach()) {
				archive(archives, 31 * DAY, Level.TEN_YEARS.reach());
			}
		}
		List<Archive> list = new ArrayList<>();
		archives.forEach((resolution, kept) -> list.add(new Archive(resolution.longValue(), kept.longValue())));
		return new History(retention, spacing, List.copyOf(list), meter);
	}

	private static void archive(TreeMap<Long, Long> archives, long resolution, long retention) {
		archives.merge(Long.valueOf(resolution), Long.valueOf(retention), (a, b) -> Long.valueOf(Math.max(a, b)));
	}

	// ---- the datapoint's settings --------------------------------------------------------------

	/**
	 * Whether the object is a datapoint the controller can keep a history of: a group address or an
	 * application datapoint of a number or a truth value (a rule parameter has none).
	 */
	public static boolean canHaveHistory(TLObject datapoint) {
		if (!isDatapoint(datapoint)) {
			return false;
		}
		String type = Pages.valueType(datapoint);
		return ValueTypes.BOOLEAN.equals(type) || isNumber(type);
	}

	/** Whether the value type is a number: a meter reading must be one. */
	private static boolean isNumber(String type) {
		return ValueTypes.UNSIGNED.equals(type) || ValueTypes.SIGNED.equals(type) || ValueTypes.REAL.equals(type);
	}

	/**
	 * Whether the object is a group address or an application datapoint, which have the attributes
	 * <code>recording</code> and <code>meter</code>.
	 */
	public static boolean isDatapoint(TLObject obj) {
		return obj != null && obj.tValid()
			&& (ExtIds.isA(obj, "ehome.logical:GroupAddress") || ApplicationDatapoints.isApplicationDatapoint(obj));
	}

	/**
	 * The recording set for the datapoint; {@link Level#NONE} for none or an object without the setting.
	 */
	public static Level level(TLObject datapoint) {
		TLObject stored = stored(datapoint);
		if (stored instanceof GroupAddress address) {
			return Level.of(Pages.name(address.getRecording()));
		}
		if (stored instanceof ApplicationDatapoint application) {
			return Level.of(Pages.name(application.getRecording()));
		}
		return Level.NONE;
	}

	/**
	 * Whether the datapoint is marked as a meter reading: an application datapoint by its flag, a group
	 * address by its flag, else by its datapoint type ({@link #meterByType(TLObject)}).
	 */
	public static boolean meter(TLObject datapoint) {
		TLObject stored = stored(datapoint);
		if (stored instanceof GroupAddress address) {
			Boolean set = address.getMeter();
			return set != null ? set.booleanValue() : meterByType(address);
		}
		if (stored instanceof ApplicationDatapoint application) {
			return application.getMeter();
		}
		return false;
	}

	/**
	 * Whether the datapoint type of the group address is a meter reading: a counter of pulses (12.x)
	 * or of energy (13.010 to 13.016).
	 */
	public static boolean meterByType(TLObject address) {
		Dpt dpt = GroupAddressTypes.effective(address);
		if (dpt == null) {
			return false;
		}
		return dpt.main() == 12 || (dpt.main() == 13 && !dpt.isMainType() && dpt.sub() >= 10 && dpt.sub() <= 16);
	}

	/**
	 * Why the datapoint (a group address or an application datapoint, also one being edited) cannot be
	 * marked as a meter reading: it is no number; <code>null</code> if it can, or is not marked.
	 *
	 * @param meter
	 *        The value of <code>meter</code>.
	 */
	public static ResKey meterProblem(Object meter, TLObject datapoint) {
		if (!Boolean.TRUE.equals(meter) || datapoint == null) {
			return null;
		}
		String type = editedValueType(datapoint);
		if (type == null || isNumber(type)) {
			return null;
		}
		return I18NConstants.METER_NOT_NUMBER__TYPE.fill(type);
	}

	/**
	 * What is wrong with the recording of the datapoint (also one being edited): a history of a value
	 * that is neither a number nor a truth value; <code>null</code> if it fits or is none.
	 *
	 * @param recording
	 *        The value of <code>recording</code>.
	 */
	public static ResKey recordingProblem(Object recording, TLObject datapoint) {
		if (Level.of(Pages.name(recording)) == Level.NONE || datapoint == null) {
			return null;
		}
		String type = editedValueType(datapoint);
		if (type == null || ValueTypes.BOOLEAN.equals(type) || isNumber(type)) {
			return null;
		}
		return I18NConstants.HISTORY_WRONG_TYPE__TYPE.fill(type);
	}

	/** The value type of a datapoint, also one being edited, from the values of the form. */
	private static String editedValueType(TLObject datapoint) {
		if (ApplicationDatapoints.isApplicationDatapoint(datapoint)) {
			return ApplicationDatapoints.valueType(datapoint);
		}
		Dpt dpt = GroupAddressTypes.effective(datapoint);
		return dpt == null ? null : ValueTypes.valueType(dpt.main(), dpt.isMainType() ? 0 : dpt.sub());
	}

	/** The classifier of <code>ehome.logical:Recording</code> of the level. */
	public static TLClassifier classifier(Level level) {
		return Recording.type().getClassifier(level.classifier());
	}

	private static TLObject stored(TLObject obj) {
		if (obj instanceof TLFormObjectBase form && form.getEditedObject() != null) {
			return form.getEditedObject();
		}
		return obj;
	}

	// ---- in effect -------------------------------------------------------------------------------

	/**
	 * The history of the datapoint in the image: the stores of its recording, a meter reading only of a
	 * number; <code>null</code> for none (no recording, or a datapoint that can have no history).
	 */
	public static History effective(TLObject datapoint) {
		TLObject stored = stored(datapoint);
		if (!canHaveHistory(stored)) {
			return null;
		}
		return stores(level(stored), meter(stored) && isNumber(Pages.valueType(stored)));
	}

	/**
	 * How far back the history of the datapoint in the image reaches, as the chart ranges count it (the
	 * reach of its recording); 0 without history.
	 */
	public static long reach(TLObject datapoint) {
		return effective(datapoint) == null ? 0 : level(datapoint).reach();
	}

	/**
	 * The member <code>history</code> of the datapoint in the image; <code>null</code> for none.
	 */
	public static Map<String, Object> json(TLObject datapoint) {
		History history = effective(datapoint);
		return history == null ? null : history.json();
	}

	/**
	 * The RAM the history of the datapoint in the image takes on the controller; 0 for none.
	 */
	public static long ramBytes(TLObject datapoint) {
		History history = effective(datapoint);
		return history == null ? 0 : history.ramBytes();
	}

	/**
	 * The flash the archives of the datapoint's history in the image take on the controller; 0 for none.
	 */
	public static long flashBytes(TLObject datapoint) {
		History history = effective(datapoint);
		return history == null ? 0 : history.flashBytes();
	}

	/**
	 * The datapoints of the project with a history in the image and their histories, the biggest first:
	 * by RAM, then by flash (then in the order of the project's datapoints).
	 */
	public static Map<TLObject, History> consumers(TLObject project) {
		Map<TLObject, History> histories = new LinkedHashMap<>();
		if (project == null) {
			return histories;
		}
		for (TLObject datapoint : RuntimeFunctions.datapoints(project)) {
			History history = effective(datapoint);
			if (history != null) {
				histories.put(datapoint, history);
			}
		}
		List<Map.Entry<TLObject, History>> entries = new ArrayList<>(histories.entrySet());
		entries.sort(Comparator.<Map.Entry<TLObject, History>> comparingLong(entry -> entry.getValue().ramBytes())
			.thenComparingLong(entry -> entry.getValue().flashBytes()).reversed());
		Map<TLObject, History> result = new LinkedHashMap<>();
		for (Map.Entry<TLObject, History> entry : entries) {
			result.put(entry.getKey(), entry.getValue());
		}
		return result;
	}

	/**
	 * The datapoints of the project with a history in the image, the biggest first
	 * ({@link #consumers(TLObject)}).
	 */
	public static List<TLObject> datapoints(TLObject project) {
		return new ArrayList<>(consumers(project).keySet());
	}

	/**
	 * The RAM the histories of the project's datapoints take together: against
	 * <code>history.budget</code>.
	 */
	public static long totalRamBytes(TLObject project) {
		long result = 0;
		for (History history : consumers(project).values()) {
			result += history.ramBytes();
		}
		return result;
	}

	/**
	 * The flash the archives of the project's datapoints take together: against
	 * <code>history.archiveBudget</code>.
	 */
	public static long totalFlashBytes(TLObject project) {
		long result = 0;
		for (History history : consumers(project).values()) {
			result += history.flashBytes();
		}
		return result;
	}

	// ---- charts ----------------------------------------------------------------------------------

	/**
	 * The datapoints a chart draws whose history it shows, each once: the datapoint of a series, or the
	 * one an output of a virtual device is bound to.
	 */
	public static List<TLObject> drawn(TLObject chart) {
		List<TLObject> result = new ArrayList<>();
		Set<TLObject> seen = new HashSet<>();
		for (TLObject series : RuleExport.objects(((ChartElement) chart).getSeries())) {
			if (((ChartSeries) series).getDatapoint() instanceof TLObject drawn) {
				TLObject datapoint = BlockInstances.historyTarget(drawn);
				if (seen.add(datapoint)) {
					result.add(datapoint);
				}
			}
		}
		return result;
	}

	/**
	 * How far back every series of the chart is recorded: the shortest reach of the datapoints it draws;
	 * {@link Long#MAX_VALUE} for a chart without series.
	 */
	public static long chartReach(TLObject chart) {
		long result = Long.MAX_VALUE;
		for (TLObject datapoint : drawn(chart)) {
			result = Math.min(result, reach(datapoint));
		}
		return result;
	}

	/**
	 * Whether a chart reaching back the given seconds ({@link #chartReach(TLObject)}) can offer the range
	 * of the given classifier of <code>ChartRange</code>: a range a recording serves (not 1 h) and that
	 * the reach covers.
	 */
	public static boolean offers(long reach, String range) {
		Long seconds = Pages.rangeSeconds(range);
		return seconds != null && !"hour".equals(range) && seconds.longValue() <= reach;
	}

	// ---- texts -------------------------------------------------------------------------------

	/**
	 * The history for the user: the raw ring, the archives, the bytes of RAM and, with archives, of flash.
	 */
	public static String describe(History history) {
		Resources resources = Resources.getInstance();
		if (history == null) {
			return resources.getString(I18NConstants.HISTORY_NONE);
		}
		String raw = resources.getString(I18NConstants.HISTORY_RAW__SPACING_RETENTION
			.fill(duration(history.spacing()), duration(history.retention())));
		String text;
		if (history.archives().isEmpty()) {
			text = raw;
		} else {
			List<String> archives = new ArrayList<>();
			for (Archive archive : history.archives()) {
				archives.add(resources.getString(I18NConstants.HISTORY_ARCHIVE__RESOLUTION_RETENTION
					.fill(duration(archive.resolution()), duration(archive.retention()))));
			}
			text = resources.getString(I18NConstants.HISTORY_WITH_ARCHIVES__RAW_ARCHIVES.fill(raw, String.join(", ", archives)));
		}
		if (history.archives().isEmpty()) {
			return resources.getString(I18NConstants.HISTORY_BYTES__HISTORY_RAM.fill(text, Long.valueOf(history.ramBytes())));
		}
		return resources.getString(I18NConstants.HISTORY_BYTES__HISTORY_RAM_FLASH.fill(text, Long.valueOf(history.ramBytes()),
			Long.valueOf(history.flashBytes())));
	}

	/**
	 * A duration in its largest whole unit: <code>1 d</code>, <code>15 min</code>, <code>90 s</code>.
	 */
	public static String duration(long seconds) {
		if (seconds > 0 && seconds % 86400 == 0) {
			return seconds / 86400 + " d";
		}
		if (seconds > 0 && seconds % 3600 == 0) {
			return seconds / 3600 + " h";
		}
		if (seconds > 0 && seconds % 60 == 0) {
			return seconds / 60 + " min";
		}
		return seconds + " s";
	}

	/**
	 * The history of the datapoint in the image for the user.
	 */
	public static String describeEffective(TLObject datapoint) {
		return describe(effective(datapoint));
	}

	/**
	 * The bytes of the project's histories for the user: their RAM against the project's budget (or the
	 * device's cap without one) and the flash of their archives against the device's cap, each naming the
	 * biggest histories where it is exceeded.
	 */
	public static String usage(TLObject project) {
		Map<TLObject, History> consumers = consumers(project);
		long ram = 0;
		long flash = 0;
		for (History history : consumers.values()) {
			ram += history.ramBytes();
			flash += history.flashBytes();
		}
		Object budget = ((Project) project).getHistoryBudget();
		long limit = budget instanceof Number number ? number.longValue() : ImageValidator.HISTORY_CAP;
		Integer cap = Integer.valueOf(ImageValidator.HISTORY_CAP);
		ResKey ramText;
		if (ram > limit) {
			ramText = I18NConstants.HISTORY_USAGE_RAM_EXCEEDED__BYTES_LIMIT_BIGGEST.fill(Long.valueOf(ram),
				Long.valueOf(limit), biggest(consumers, 3, History::ramBytes));
		} else if (budget instanceof Number) {
			ramText = I18NConstants.HISTORY_USAGE_RAM__BYTES_BUDGET_CAP.fill(Long.valueOf(ram), Long.valueOf(limit), cap);
		} else {
			ramText = I18NConstants.HISTORY_USAGE_RAM__BYTES_CAP.fill(Long.valueOf(ram), cap);
		}
		Integer archiveCap = Integer.valueOf(ImageValidator.ARCHIVE_CAP);
		ResKey flashText;
		if (flash > ImageValidator.ARCHIVE_CAP) {
			flashText = I18NConstants.HISTORY_USAGE_FLASH_EXCEEDED__BYTES_CAP_BIGGEST.fill(Long.valueOf(flash), archiveCap,
				biggest(consumers, 3, History::flashBytes));
		} else {
			flashText = I18NConstants.HISTORY_USAGE_FLASH__BYTES_CAP.fill(Long.valueOf(flash), archiveCap);
		}
		Resources resources = Resources.getInstance();
		return resources.getString(ramText) + " " + resources.getString(flashText);
	}

	/**
	 * The given number of biggest of the histories for the user by the given bytes: the datapoint and
	 * its bytes (<code>Temperatur (1/3/1): 12288 B</code>); histories without such bytes are left out.
	 */
	private static String biggest(Map<TLObject, History> consumers, int count, ToLongFunction<History> bytes) {
		List<Map.Entry<TLObject, History>> entries = new ArrayList<>(consumers.entrySet());
		entries.sort(Comparator.<Map.Entry<TLObject, History>> comparingLong(entry -> bytes.applyAsLong(entry.getValue()))
			.reversed());
		List<String> result = new ArrayList<>();
		for (Map.Entry<TLObject, History> entry : entries) {
			long value = bytes.applyAsLong(entry.getValue());
			if (result.size() == count || value == 0) {
				break;
			}
			result.add(Pages.targetLabel(entry.getKey()) + ": " + value + " B");
		}
		return String.join(", ", result);
	}

}
