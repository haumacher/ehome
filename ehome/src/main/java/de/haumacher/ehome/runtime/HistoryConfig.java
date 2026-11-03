/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.runtime;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import com.top_logic.basic.util.ResKey;
import com.top_logic.model.TLClass;
import com.top_logic.model.TLFormObjectBase;
import com.top_logic.model.TLObject;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.Resources;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.knx.ExtIds;

/**
 * The history the controller keeps of a datapoint (haumacher/ehome #73, homecontrol #56): a raw ring
 * of samples and up to four archives of consolidated slots, derived from the charts of the control
 * pages, or set by hand.
 *
 * <h2>Derived from the charts</h2>
 *
 * <p>
 * Each series of a chart asks, for each range the chart offers (its default range and its presets),
 * for a <i>resolution</i> over a <i>coverage</i> ({@link #needs(Pages.ChartUse, boolean)}), read from
 * what the controller's UI draws (homecontrol <code>web/ui/dist/elements.js</code>, <code>load()</code>):
 * </p>
 * <ul>
 * <li>a line, band or on/off strip: {@value #LINE_POINTS} points over the range, so the resolution is
 * the range divided by {@value #LINE_POINTS}, over the range;</li>
 * <li>the bars of a consumption: one bar per bucket of its own width (1 h: 12 of 5 min, 24 h: 24 of
 * 1 h, 7 d: 7 of 1 d, 30 d: 30 of 1 d, 1 y: 12 of a twelfth of the year), queried with one bucket
 * more for the first difference, so the resolution is the bar width, over the bars plus one.</li>
 * </ul>
 * <p>
 * A resolution is rounded down to the {@link #LADDER} (1, 5, 15 min, 1 h, 1 d). A coverage up to
 * {@link #RAW_COVERAGE 2 days} is served by the raw ring: its spacing is the finest such resolution,
 * its retention the longest such coverage. A longer coverage is served by an archive of its
 * resolution, kept for the longest coverage at that resolution. Over all charts of the datapoint the
 * needs are united: the finest resolution per coverage, the longest retention per resolution. A
 * datapoint a function shows (any role of a function) keeps, besides, the 24 hours at 15 minutes its
 * tile's sparkline and its sheet draw ({@link #FUNCTION_NEED}); a datapoint no chart draws keeps no
 * history.
 * </p>
 * <p>
 * The limits of the controller (homecontrol #56) are respected by these rules, each of which keeps
 * the coverage and gives up resolution:
 * </p>
 * <ul>
 * <li>an archive of more than {@value #MAX_SLOTS} slots takes the next coarser step of the ladder (and
 * joins an archive of that resolution), as does a raw ring of more than
 * {@value ImageValidator#MAX_SAMPLES} samples;</li>
 * <li>an archive whose coverage a finer store (the raw ring or a finer archive) reaches as well is
 * dropped: the controller serves its range from the finer one;</li>
 * <li>of more than {@value #MAX_ARCHIVES} archives the finest is merged into the next coarser one,
 * until four are left;</li>
 * <li>the raw ring's spacing is finer than the finest archive's resolution: if not, it takes the step
 * of the ladder below. Without a range of up to two days the raw ring only feeds the archives: the
 * step below the finest archive's resolution, kept for one slot of it.</li>
 * </ul>
 *
 * <h2>Set by hand</h2>
 *
 * <p>
 * With <code>historyManual</code>, the raw ring of <code>historyRetention</code> and
 * <code>historySpacing</code> and the archives <code>historyArchives</code> replace the derived history
 * entirely: no merging. A datapoint that had a history before haumacher/ehome #73 is migrated to one set
 * by hand without archives, so its image does not change.
 * </p>
 *
 * <h2>Bytes</h2>
 *
 * <p>
 * The controller allocates {@value #SAMPLE_BYTES} bytes per sample of the raw ring and
 * {@value #SLOT_BYTES} per slot of an archive; a ring or an archive has
 * <code>ceil(retention / spacing) + 1</code> samples or slots ({@link ImageValidator#samples(long, long)}).
 * </p>
 */
public final class HistoryConfig {

	/** The steps a derived resolution is rounded down to, in seconds. */
	public static final List<Long> LADDER =
		List.of(Long.valueOf(60), Long.valueOf(300), Long.valueOf(900), Long.valueOf(3600), Long.valueOf(86400));

	/** The longest coverage the raw ring serves; longer ones are served by archives. */
	public static final long RAW_COVERAGE = 2 * 86400;

	/** The points of a line of a chart of the controller's UI. */
	public static final int LINE_POINTS = 48;

	/** The most archives of a datapoint. */
	public static final int MAX_ARCHIVES = 4;

	/** The most slots of an archive. */
	public static final int MAX_SLOTS = 2048;

	/** The bytes of a slot of an archive. */
	public static final int SLOT_BYTES = 32;

	/** The bytes of a sample of the raw ring. */
	public static final int SAMPLE_BYTES = 8;

	/** The finest resolution of an archive. */
	public static final long MIN_RESOLUTION = 60;

	/** The coarsest resolution of an archive: 31 days. */
	public static final long MAX_RESOLUTION = 31 * 86400;

	/** The longest retention of an archive: 10 years. */
	public static final long MAX_ARCHIVE_RETENTION = 3650L * 86400;

	/** What a function asks of the datapoints of its roles: its sparkline and sheet, 24 h at 15 min. */
	public static final Need FUNCTION_NEED = new Need(900, 86400);

	/** The consumption bars of the controller's UI per range: their count and width in seconds. */
	private static final Map<Long, long[]> BARS = Map.of(
		Long.valueOf(3600), new long[] { 12, 300 },
		Long.valueOf(86400), new long[] { 24, 3600 },
		Long.valueOf(7 * 86400), new long[] { 7, 86400 },
		Long.valueOf(30 * 86400), new long[] { 30, 86400 },
		Long.valueOf(365 * 86400), new long[] { 12, 365 * 86400 / 12 });

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

		/** The bytes the controller allocates. */
		public long bytes() {
			return slots() * SLOT_BYTES;
		}
	}

	/**
	 * The history of a datapoint: the raw ring (retention and spacing in seconds) and the archives,
	 * finest first.
	 */
	public record History(long retention, long spacing, List<Archive> archives) {

		/** The samples of the raw ring. */
		public long samples() {
			return ImageValidator.samples(retention, spacing);
		}

		/** The bytes the controller allocates for the raw ring and the archives. */
		public long bytes() {
			long result = samples() * SAMPLE_BYTES;
			for (Archive archive : archives) {
				result += archive.bytes();
			}
			return result;
		}

		/**
		 * The member <code>history</code> of the datapoint in the image: <code>retention</code>,
		 * <code>spacing</code> and, where there are some, <code>archives</code>.
		 */
		public Map<String, Object> json() {
			Map<String, Object> result = new LinkedHashMap<>();
			result.put("retention", Long.valueOf(retention));
			result.put("spacing", Long.valueOf(spacing));
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

	/**
	 * What a chart asks of a datapoint for one of its ranges: the resolution over the coverage, in
	 * seconds.
	 */
	public record Need(long resolution, long coverage) {
		// A record.
	}

	// ---- derivation ------------------------------------------------------------------------------

	/**
	 * The needs of the series of a use for each range of its chart.
	 *
	 * @param bars
	 *        Whether the series is drawn as consumption bars.
	 */
	public static List<Need> needs(Pages.ChartUse use, boolean bars) {
		List<Need> result = new ArrayList<>();
		for (Long range : use.ranges()) {
			long seconds = range.longValue();
			long[] bar = BARS.get(range);
			if (bars && bar != null) {
				result.add(new Need(floor(bar[1]), (bar[0] + 1) * bar[1]));
			} else {
				result.add(new Need(floor(seconds / LINE_POINTS), seconds));
			}
		}
		return result;
	}

	/**
	 * The history the needs ask for; <code>null</code> for no needs.
	 *
	 * @param shown
	 *        Whether a function shows the datapoint, which adds {@link #FUNCTION_NEED}.
	 */
	public static History derive(List<Need> needs, boolean shown) {
		if (needs.isEmpty()) {
			return null;
		}
		List<Need> all = new ArrayList<>(needs);
		if (shown) {
			all.add(FUNCTION_NEED);
		}
		long spacing = Long.MAX_VALUE;
		long retention = 0;
		TreeMap<Long, Long> archives = new TreeMap<>();
		for (Need need : all) {
			if (need.coverage() <= RAW_COVERAGE) {
				spacing = Math.min(spacing, need.resolution());
				retention = Math.max(retention, need.coverage());
			} else {
				addArchive(archives, need.resolution(), need.coverage());
			}
		}
		// Archives beyond the slots take a coarser resolution.
		boolean changed = true;
		while (changed) {
			changed = false;
			for (Map.Entry<Long, Long> entry : archives.entrySet()) {
				long resolution = entry.getKey().longValue();
				long coarser = coarser(resolution);
				if (ImageValidator.samples(entry.getValue().longValue(), resolution) > MAX_SLOTS && coarser > resolution) {
					archives.remove(entry.getKey());
					addArchive(archives, coarser, entry.getValue().longValue());
					changed = true;
					break;
				}
			}
		}
		// The raw ring before the archives' redundancy is judged against it.
		boolean raw = spacing != Long.MAX_VALUE;
		if (raw) {
			while (ImageValidator.samples(retention, spacing) > ImageValidator.MAX_SAMPLES && coarser(spacing) > spacing) {
				spacing = coarser(spacing);
			}
		}
		// Archives a finer store reaches as far are dropped.
		List<Archive> kept = new ArrayList<>();
		long reach = raw ? retention : 0;
		for (Map.Entry<Long, Long> entry : archives.entrySet()) {
			long archiveRetention = entry.getValue().longValue();
			if (archiveRetention > reach) {
				kept.add(new Archive(entry.getKey().longValue(), archiveRetention));
				reach = archiveRetention;
			}
		}
		while (kept.size() > MAX_ARCHIVES) {
			Archive finest = kept.remove(0);
			Archive next = kept.get(0);
			kept.set(0, new Archive(next.resolution(), Math.max(next.retention(), finest.retention())));
		}
		if (!kept.isEmpty()) {
			long finest = kept.get(0).resolution();
			if (!raw) {
				spacing = below(finest);
				retention = finest;
			} else if (spacing >= finest) {
				spacing = below(finest);
			}
		}
		return new History(retention, spacing, List.copyOf(kept));
	}

	private static void addArchive(TreeMap<Long, Long> archives, long resolution, long retention) {
		archives.merge(Long.valueOf(resolution), Long.valueOf(retention), (a, b) -> Long.valueOf(Math.max(a, b)));
	}

	/**
	 * The step of the {@link #LADDER} at or below the given seconds; the finest step for less.
	 */
	public static long floor(long seconds) {
		long result = LADDER.get(0).longValue();
		for (Long step : LADDER) {
			if (step.longValue() <= seconds) {
				result = step.longValue();
			}
		}
		return result;
	}

	/** The next coarser step of the ladder; the given seconds where there is none. */
	private static long coarser(long seconds) {
		for (Long step : LADDER) {
			if (step.longValue() > seconds) {
				return step.longValue();
			}
		}
		return seconds;
	}

	/** The next finer step of the ladder; half of the seconds (at least 10) where there is none. */
	private static long below(long seconds) {
		long result = -1;
		for (Long step : LADDER) {
			if (step.longValue() < seconds) {
				result = step.longValue();
			}
		}
		return result > 0 ? result : Math.max(10, seconds / 2);
	}

	/**
	 * The derived histories of the datapoints of the project, by datapoint in the order of the pages:
	 * the datapoints the charts draw that can have a history (a group address or an application
	 * datapoint of a number or truth value).
	 */
	public static Map<TLObject, History> derivedHistories(TLObject project) {
		Map<TLObject, History> result = new LinkedHashMap<>();
		if (project == null) {
			return result;
		}
		Set<TLObject> shown = shownByFunctions(project);
		for (Map.Entry<TLObject, List<Pages.ChartUse>> entry : Pages.chartUses(project).entrySet()) {
			TLObject datapoint = entry.getKey();
			if (!canHaveHistory(datapoint)) {
				continue;
			}
			List<Need> needs = new ArrayList<>();
			boolean bars = !ValueTypes.BOOLEAN.equals(Pages.valueType(datapoint));
			for (Pages.ChartUse use : entry.getValue()) {
				needs.addAll(needs(use, bars && "consumption".equals(Pages.name(use.series().tValueByName("consolidation")))));
			}
			History history = derive(needs, shown.contains(datapoint));
			if (history != null) {
				result.put(datapoint, history);
			}
		}
		return result;
	}

	/**
	 * The derived history of the datapoint; <code>null</code> where no chart draws it.
	 */
	public static History derived(TLObject datapoint) {
		TLObject stored = stored(datapoint);
		if (stored == null) {
			return null;
		}
		return derivedHistories(RuntimeFunctions.project(stored)).get(stored);
	}

	/**
	 * The datapoints the roles of the project's functions refer to.
	 */
	private static Set<TLObject> shownByFunctions(TLObject project) {
		Set<TLObject> result = new HashSet<>();
		for (TLObject function : FunctionProposal.functions(project)) {
			for (String role : FunctionKinds.roles(Pages.name(function.tValueByName("kind")))) {
				if (function.tValueByName(role) instanceof TLObject datapoint) {
					result.add(datapoint);
				}
			}
		}
		return result;
	}

	/**
	 * Whether the object is a datapoint the controller can keep a history of: a group address or an
	 * application datapoint of a number or a truth value (a rule parameter has none).
	 */
	public static boolean canHaveHistory(TLObject datapoint) {
		if (!isDatapoint(datapoint)) {
			return false;
		}
		String type = Pages.valueType(datapoint);
		return ValueTypes.BOOLEAN.equals(type) || ValueTypes.UNSIGNED.equals(type) || ValueTypes.SIGNED.equals(type)
			|| ValueTypes.REAL.equals(type);
	}

	/**
	 * Whether the object is a group address or an application datapoint, which have the history
	 * attributes.
	 */
	public static boolean isDatapoint(TLObject obj) {
		return obj != null && obj.tValid()
			&& (ExtIds.isA(obj, "ehome.logical:GroupAddress") || ApplicationDatapoints.isApplicationDatapoint(obj));
	}

	private static TLObject stored(TLObject obj) {
		if (obj instanceof TLFormObjectBase form) {
			return form.getEditedObject();
		}
		return obj;
	}

	// ---- set by hand -----------------------------------------------------------------------------

	/**
	 * Whether the history of the datapoint is set by hand.
	 */
	public static boolean isManual(TLObject datapoint) {
		return datapoint != null && Boolean.TRUE.equals(datapoint.tValueByName("historyManual"));
	}

	/**
	 * The archives set by hand, in their order.
	 */
	public static List<TLObject> manualArchives(TLObject datapoint) {
		return RuleExport.objects(datapoint.tValueByName("historyArchives"));
	}

	/**
	 * The history set by hand; <code>null</code> without a complete raw ring. An archive without its
	 * resolution or retention is left out.
	 */
	public static History manual(TLObject datapoint) {
		Integer retention = DatapointSettings.historyRetention(datapoint);
		Integer spacing = DatapointSettings.historySpacing(datapoint);
		if (retention == null || spacing == null) {
			return null;
		}
		List<Archive> archives = new ArrayList<>();
		for (TLObject archive : manualArchives(datapoint)) {
			if (archive.tValueByName("resolution") instanceof Number resolution
				&& archive.tValueByName("retention") instanceof Number archiveRetention) {
				archives.add(new Archive(resolution.longValue(), archiveRetention.longValue()));
			}
		}
		return new History(retention.longValue(), spacing.longValue(), archives);
	}

	// ---- in effect -------------------------------------------------------------------------------

	/**
	 * The history of the datapoint in the image: the one set by hand, else the derived one;
	 * <code>null</code> for none.
	 */
	public static History effective(TLObject datapoint) {
		if (!isDatapoint(datapoint)) {
			return null;
		}
		return isManual(datapoint) ? manual(datapoint) : derived(datapoint);
	}

	/**
	 * The member <code>history</code> of the datapoint in the image; <code>null</code> for none.
	 *
	 * <p>
	 * Set by hand, it is written as set, even incomplete (the image's check then refuses it with its
	 * path, as it did before haumacher/ehome #73); derived, it is the given derivation's.
	 * </p>
	 *
	 * @param derived
	 *        The derived histories of the project ({@link #derivedHistories(TLObject)}).
	 */
	public static Map<String, Object> json(TLObject datapoint, Map<TLObject, History> derived) {
		if (!isManual(datapoint)) {
			History history = derived.get(datapoint);
			return history == null ? null : history.json();
		}
		Integer retention = DatapointSettings.historyRetention(datapoint);
		Integer spacing = DatapointSettings.historySpacing(datapoint);
		List<TLObject> archives = manualArchives(datapoint);
		if (retention == null && spacing == null && archives.isEmpty()) {
			return null;
		}
		Map<String, Object> result = new LinkedHashMap<>();
		if (retention != null) {
			result.put("retention", Long.valueOf(retention.longValue()));
		}
		if (spacing != null) {
			result.put("spacing", Long.valueOf(spacing.longValue()));
		}
		if (!archives.isEmpty()) {
			List<Object> list = new ArrayList<>();
			for (TLObject archive : archives) {
				Map<String, Object> entry = new LinkedHashMap<>();
				if (archive.tValueByName("resolution") instanceof Number resolution) {
					entry.put("resolution", Long.valueOf(resolution.longValue()));
				}
				if (archive.tValueByName("retention") instanceof Number archiveRetention) {
					entry.put("retention", Long.valueOf(archiveRetention.longValue()));
				}
				list.add(entry);
			}
			result.put("archives", list);
		}
		return result;
	}

	/**
	 * The bytes the history of the datapoint in the image takes; 0 for none.
	 */
	public static long bytes(TLObject datapoint) {
		History history = effective(datapoint);
		return history == null ? 0 : history.bytes();
	}

	/**
	 * The datapoints of the project with a history in the image and the bytes it takes, the biggest
	 * first (then in the order of the project's datapoints).
	 */
	public static Map<TLObject, Long> consumers(TLObject project) {
		Map<TLObject, Long> bytes = new LinkedHashMap<>();
		if (project == null) {
			return bytes;
		}
		Map<TLObject, History> derived = derivedHistories(project);
		for (TLObject datapoint : RuntimeFunctions.datapoints(project)) {
			History history = isManual(datapoint) ? manual(datapoint) : derived.get(datapoint);
			if (history != null) {
				bytes.put(datapoint, Long.valueOf(history.bytes()));
			}
		}
		List<Map.Entry<TLObject, Long>> entries = new ArrayList<>(bytes.entrySet());
		entries.sort(Map.Entry.<TLObject, Long> comparingByValue().reversed());
		Map<TLObject, Long> result = new LinkedHashMap<>();
		for (Map.Entry<TLObject, Long> entry : entries) {
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
	 * The bytes the histories of the project's datapoints take together.
	 */
	public static long totalBytes(TLObject project) {
		long result = 0;
		for (Long bytes : consumers(project).values()) {
			result += bytes.longValue();
		}
		return result;
	}

	/**
	 * The bytes of the histories of the datapoints a chart draws, each datapoint once (a datapoint
	 * several charts draw counts in each of them).
	 */
	public static long chartBytes(TLObject chart) {
		long result = 0;
		Set<TLObject> seen = new HashSet<>();
		for (TLObject series : RuleExport.objects(chart.tValueByName("series"))) {
			if (series.tValueByName("datapoint") instanceof TLObject datapoint && seen.add(datapoint)) {
				result += bytes(datapoint);
			}
		}
		return result;
	}

	/**
	 * The charts of the project's pages, in the order of the pages.
	 */
	public static List<TLObject> charts(TLObject project) {
		List<TLObject> result = new ArrayList<>();
		if (project == null) {
			return result;
		}
		for (List<Pages.ChartUse> uses : Pages.chartUses(project).values()) {
			for (Pages.ChartUse use : uses) {
				if (!result.contains(use.chart())) {
					result.add(use.chart());
				}
			}
		}
		return result;
	}

	// ---- commands ----------------------------------------------------------------------------

	/**
	 * Sets the history of the datapoint by hand, starting from the derived one (no history where no
	 * chart draws it): the raw ring and the archives become the datapoint's own.
	 */
	public static void setManual(TLObject datapoint) {
		History derived = derived(datapoint);
		clearManual(datapoint);
		datapoint.tUpdateByName("historyManual", Boolean.TRUE);
		if (derived == null) {
			return;
		}
		datapoint.tUpdateByName("historyRetention", Integer.valueOf((int) derived.retention()));
		datapoint.tUpdateByName("historySpacing", Integer.valueOf((int) derived.spacing()));
		List<TLObject> archives = new ArrayList<>();
		TLClass type = (TLClass) TLModelUtil.findType("ehome.logical:HistoryArchive");
		for (Archive archive : derived.archives()) {
			TLObject part = ModelService.getInstance().getFactory().createObject(type);
			part.tUpdateByName("resolution", Integer.valueOf((int) archive.resolution()));
			part.tUpdateByName("retention", Integer.valueOf((int) archive.retention()));
			archives.add(part);
		}
		datapoint.tUpdateByName("historyArchives", archives);
	}

	/**
	 * Returns the datapoint to its derived history: the raw ring and the archives set by hand are
	 * removed.
	 */
	public static void reset(TLObject datapoint) {
		clearManual(datapoint);
		datapoint.tUpdateByName("historyManual", null);
	}

	private static void clearManual(TLObject datapoint) {
		datapoint.tUpdateByName("historyRetention", null);
		datapoint.tUpdateByName("historySpacing", null);
		List<TLObject> archives = manualArchives(datapoint);
		datapoint.tUpdateByName("historyArchives", List.of());
		for (TLObject archive : archives) {
			if (archive.tValid()) {
				archive.tDelete();
			}
		}
	}

	// ---- texts -------------------------------------------------------------------------------

	/**
	 * The history for the user: the raw ring, the archives, the bytes.
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
		return resources.getString(I18NConstants.HISTORY_BYTES__HISTORY_BYTES.fill(text, Long.valueOf(history.bytes())));
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
	 * The derived history of the datapoint for the user.
	 */
	public static String describeDerived(TLObject datapoint) {
		if (!canHaveHistory(stored(datapoint))) {
			return Resources.getInstance().getString(I18NConstants.HISTORY_NOT_POSSIBLE);
		}
		History derived = derived(datapoint);
		return derived == null ? Resources.getInstance().getString(I18NConstants.HISTORY_NOT_CHARTED) : describe(derived);
	}

	/**
	 * The history of the datapoint in the image for the user, saying whether it is set by hand.
	 */
	public static String describeEffective(TLObject datapoint) {
		Resources resources = Resources.getInstance();
		History history = effective(datapoint);
		return resources.getString((isManual(datapoint) ? I18NConstants.HISTORY_MANUAL__HISTORY
			: I18NConstants.HISTORY_DERIVED__HISTORY).fill(describe(history)));
	}

	/**
	 * The bytes of the project's histories against its budget and the device's cap, for the user.
	 */
	public static String usage(TLObject project) {
		long total = totalBytes(project);
		Object budget = project.tValueByName("historyBudget");
		long limit = budget instanceof Number number ? number.longValue() : ImageValidator.HISTORY_CAP;
		ResKey text;
		if (total > limit) {
			text = I18NConstants.HISTORY_USAGE_EXCEEDED__BYTES_LIMIT_BIGGEST.fill(Long.valueOf(total), Long.valueOf(limit),
				biggest(project, 3));
		} else if (budget instanceof Number) {
			text = I18NConstants.HISTORY_USAGE__BYTES_BUDGET_CAP.fill(Long.valueOf(total), Long.valueOf(limit),
				Integer.valueOf(ImageValidator.HISTORY_CAP));
		} else {
			text = I18NConstants.HISTORY_USAGE__BYTES_CAP.fill(Long.valueOf(total), Integer.valueOf(ImageValidator.HISTORY_CAP));
		}
		return Resources.getInstance().getString(text);
	}

	/**
	 * The given number of biggest histories of the project for the user: the datapoint and its bytes.
	 */
	static String biggest(TLObject project, int count) {
		List<String> result = new ArrayList<>();
		for (Map.Entry<TLObject, Long> entry : consumers(project).entrySet()) {
			if (result.size() == count) {
				break;
			}
			result.add(Pages.targetLabel(entry.getKey()) + " (" + entry.getValue() + " B)");
		}
		return String.join(", ", result);
	}

	// ---- checks ------------------------------------------------------------------------------

	/**
	 * What is wrong with the archives set by hand for the datapoint (the limits of homecontrol #56),
	 * or that it has archives without its history being set by hand; <code>null</code> if they fit. The raw ring is checked by
	 * {@link DatapointSettings#historyProblem(TLObject)}.
	 */
	public static ResKey archivesProblem(TLObject datapoint) {
		List<TLObject> archives = manualArchives(datapoint);
		if (archives.isEmpty()) {
			return null;
		}
		if (!isManual(datapoint)) {
			// The form shows the archives also of a derived history (the composition table does not
			// follow the dynamic visibility of its attribute): the image would ignore them.
			return I18NConstants.HISTORY_ARCHIVES_NOT_MANUAL;
		}
		if (archives.size() > MAX_ARCHIVES) {
			return I18NConstants.HISTORY_TOO_MANY_ARCHIVES__MAX.fill(Integer.valueOf(MAX_ARCHIVES));
		}
		if (DatapointSettings.historyRetention(datapoint) == null || DatapointSettings.historySpacing(datapoint) == null) {
			return I18NConstants.HISTORY_ARCHIVES_WITHOUT_RAW;
		}
		for (TLObject archive : archives) {
			ResKey problem = archiveProblem(archive, datapoint);
			if (problem != null) {
				return problem;
			}
		}
		return null;
	}

	/**
	 * What is wrong with an archive set by hand: its resolution not coarser than the raw ring's
	 * spacing, its retention below its resolution, beyond 10 years or giving more than
	 * {@value #MAX_SLOTS} slots; <code>null</code> if it fits or is incomplete.
	 */
	public static ResKey archiveProblem(TLObject archive, TLObject datapoint) {
		if (!(archive.tValueByName("resolution") instanceof Number resolution)
			|| !(archive.tValueByName("retention") instanceof Number retention)) {
			return null;
		}
		Integer spacing = datapoint == null ? null : DatapointSettings.historySpacing(datapoint);
		if (spacing != null && resolution.longValue() <= spacing.longValue()) {
			return I18NConstants.HISTORY_ARCHIVE_NOT_COARSER__RESOLUTION_SPACING.fill(duration(resolution.longValue()),
				duration(spacing.longValue()));
		}
		if (retention.longValue() < resolution.longValue()) {
			return I18NConstants.HISTORY_ARCHIVE_RETENTION_BELOW_RESOLUTION;
		}
		if (retention.longValue() > MAX_ARCHIVE_RETENTION) {
			return I18NConstants.HISTORY_ARCHIVE_TOO_LONG;
		}
		long slots = ImageValidator.samples(retention.longValue(), resolution.longValue());
		if (slots > MAX_SLOTS) {
			return I18NConstants.HISTORY_TOO_MANY_SLOTS__SLOTS_MAX.fill(Long.valueOf(slots), Integer.valueOf(MAX_SLOTS));
		}
		return null;
	}

}
