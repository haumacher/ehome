/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.runtime;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.ToLongFunction;
import java.util.function.UnaryOperator;

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
 * step below the finest archive's resolution, kept for one slot of it;</li>
 * <li>a history whose archives need more flash than the device allows for all archives
 * ({@value ImageValidator#ARCHIVE_CAP} bytes), or whose ring and archives need more RAM than it allows
 * ({@value ImageValidator#HISTORY_CAP} bytes), is made coarser as below, until it fits or nothing can
 * be coarser. With the ranges of the charts (up to a year) a single datapoint never comes near either
 * cap; the rule keeps the derivation within the device's limits for any coverage.</li>
 * </ul>
 *
 * <h2>Made coarser, never shorter</h2>
 *
 * <p>
 * The controller answers a range of a chart from the finest of the datapoint's stores (the raw ring
 * and the archives) whose resolution is at most the range's bucket width (a line: the range divided by
 * {@value #LINE_POINTS}; bars: their width) and that reaches back over the range; if no archive is
 * fine enough, from the raw ring (runtime image 3.9, "Which history answers"). Where a history must
 * need less, a store takes a coarser of the {@link #COARSENING_STEPS}: an archive moves to the finest
 * step that needs less flash (or RAM), joining an archive of that resolution, or is split (it keeps its
 * resolution for the ranges of finer buckets, and an archive at the coarsest step the other ranges'
 * buckets allow takes its retention), whichever gives up the least resolution; the raw ring takes the
 * finest step that needs less RAM, for the same retention. A step is taken only when every range the
 * history answered is
 * still answered by a store fine enough for its bucket width and reaching back: a store gives up
 * resolution up to the finest bucket width of the ranges it serves, never coverage. Retentions are
 * never shortened.
 * </p>
 * <p>
 * The limits hold for the image as a whole as well: while the histories of all datapoints need more
 * flash or RAM than the device (or the project's budget) allows, the derived histories are made coarser,
 * one archive or ring at a time, the biggest first ({@link #projectHistories(TLObject)}); the histories
 * set by hand are never changed. Only where that cannot make them fit does the image's check refuse
 * them at <code>history</code> ({@link ImageValidator}), without giving up coverage; the project's usage text
 * ({@link #usage(TLObject)}) names the biggest, and the datapoints made coarser.
 * </p>
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
 * A ring or an archive has <code>ceil(retention / spacing) + 1</code> samples or slots
 * ({@link ImageValidator#samples(long, long)}). The controller (homecontrol #56, runtime image 3.7)
 * counts two budgets:
 * </p>
 * <ul>
 * <li>RAM (<code>history.budget</code>, at most {@value ImageValidator#HISTORY_CAP} bytes): per raw ring
 * {@value #SAMPLE_BYTES} bytes per sample, per archive its open slot and the closed slots of up to an
 * hour not yet written, <code>(ceil(3600 / resolution) + 2) * {@value #SLOT_BYTES}</code>
 * ({@link ImageValidator#archiveRamBytes(long)}; 96 bytes for an hour or more). The closed slots of an
 * archive are not in RAM.</li>
 * <li>Flash (<code>history.archiveBudget</code>, at most {@value ImageValidator#ARCHIVE_CAP} bytes): per
 * archive a segment of 4096 bytes per 127 slots, <code>ceil(slots / 127) * 4096</code>
 * ({@link ImageValidator#archiveFlashBytes(long, long)}).</li>
 * </ul>
 * <p>
 * E-Home writes <code>history.budget</code> when the project sets <code>historyBudget</code>, and never
 * <code>history.archiveBudget</code>: its default is what the archives need, and the device refuses the
 * archives beyond its cap without it.
 * </p>
 */
public final class HistoryConfig {

	/** The steps a derived resolution is rounded down to, in seconds. */
	public static final List<Long> LADDER =
		List.of(Long.valueOf(60), Long.valueOf(300), Long.valueOf(900), Long.valueOf(3600), Long.valueOf(86400));

	/**
	 * The steps a derived archive or raw ring is made coarser to where the device's limits ask for it, in
	 * seconds: the divisors of a day from 1 min (aligned to multiples in UTC), then 1, 2 and 7 days and
	 * 31 days, the coarsest resolution of the device (aligned to local days). A step is taken only within
	 * the bucket widths of the ranges the store answers ({@link Need#bucket()}). A chart's need is rounded
	 * down to the {@link #LADDER} alone.
	 */
	public static final List<Long> COARSENING_STEPS = List.of(Long.valueOf(60), Long.valueOf(300), Long.valueOf(900),
		Long.valueOf(1800), Long.valueOf(3600), Long.valueOf(7200), Long.valueOf(10800), Long.valueOf(21600),
		Long.valueOf(43200), Long.valueOf(86400), Long.valueOf(2 * 86400), Long.valueOf(7 * 86400),
		Long.valueOf(31 * 86400));

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

		/**
		 * The RAM the controller allocates: the open slot and the closed slots of an hour not yet written
		 * ({@link ImageValidator#archiveRamBytes(long)}).
		 */
		public long ramBytes() {
			return ImageValidator.archiveRamBytes(resolution);
		}

		/** The segments of the archive on flash. */
		public long segments() {
			return ImageValidator.archiveSegments(retention, resolution);
		}

		/**
		 * The flash the archive takes: 4096 bytes per segment ({@link ImageValidator#archiveFlashBytes(long, long)}).
		 */
		public long flashBytes() {
			return ImageValidator.archiveFlashBytes(retention, resolution);
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

		/** Whether the history is within the device's caps of RAM and flash, taken alone. */
		public boolean fits() {
			return ramBytes() <= ImageValidator.HISTORY_CAP && flashBytes() <= ImageValidator.ARCHIVE_CAP;
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
	 * What a chart asks of a datapoint for one of its ranges, in seconds: the resolution (the bucket
	 * width rounded down to the {@link #LADDER}) over the coverage, and the bucket width itself. The
	 * controller answers the range from a store whose resolution is at most the bucket width and that
	 * reaches back over the coverage (runtime image 3.9, "Which history answers").
	 */
	public record Need(long resolution, long coverage, long bucket) {

		/**
		 * A need whose bucket width is its resolution: a store answering it must not be coarser.
		 */
		public Need(long resolution, long coverage) {
			this(resolution, coverage, resolution);
		}

		/**
		 * Whether a store of the given resolution and retention answers the need: fine enough and
		 * reaching back.
		 */
		boolean answeredBy(long storeResolution, long storeRetention) {
			return storeResolution <= bucket && storeRetention >= coverage;
		}

		/**
		 * Whether a store of the history answers the need.
		 */
		public boolean answeredBy(History history) {
			if (answeredBy(history.spacing(), history.retention())) {
				return true;
			}
			for (Archive archive : history.archives()) {
				if (answeredBy(archive.resolution(), archive.retention())) {
					return true;
				}
			}
			return false;
		}
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
				result.add(new Need(floor(bar[1]), (bar[0] + 1) * bar[1], bar[1]));
			} else {
				result.add(new Need(floor(seconds / LINE_POINTS), seconds, seconds / LINE_POINTS));
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
		Derivation derivation = derivation(needs, shown);
		return derivation == null ? null : derivation.history();
	}

	/**
	 * The derivation of the needs, within the device's limits for the datapoint alone; <code>null</code>
	 * for no needs.
	 */
	private static Derivation derivation(List<Need> needs, boolean shown) {
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
		// The raw ring before the archives' redundancy is judged against it.
		boolean raw = spacing != Long.MAX_VALUE;
		if (raw) {
			while (ImageValidator.samples(retention, spacing) > ImageValidator.MAX_SAMPLES && coarser(spacing) > spacing) {
				spacing = coarser(spacing);
			}
		} else {
			spacing = 0;
		}
		Derivation result = new Derivation(raw, retention, spacing, List.copyOf(all),
			history(raw, retention, spacing, archives));
		// Beyond the device's RAM or flash: coarser within the bucket widths of the ranges.
		while (!result.history().fits()) {
			Derivation coarser = result.history().flashBytes() > ImageValidator.ARCHIVE_CAP
				? result.coarserArchive(History::flashBytes) : result.coarserForRam();
			if (coarser == null) {
				break;
			}
			result = coarser;
		}
		return result;
	}

	/**
	 * A derived history, the raw ring the needs ask for and the needs, from which it is derived again
	 * when it is made coarser.
	 *
	 * @param raw
	 *        Whether a need asks for the raw ring.
	 * @param retention
	 *        The retention of the raw ring the needs ask for.
	 * @param spacing
	 *        The spacing of the raw ring the needs ask for (or that it was made coarser to).
	 * @param needs
	 *        The needs of the history, a function's included.
	 * @param history
	 *        The history derived.
	 */
	private record Derivation(boolean raw, long retention, long spacing, List<Need> needs, History history) {

		/**
		 * A candidate of {@link Derivation#coarserArchive(ToLongFunction)}: the derivation and the
		 * resolution the coarsened coverage takes.
		 */
		private record Step(Derivation derivation, long resolution) {
			// A record.
		}

		/**
		 * The derivation with an archive made coarser, giving up the least resolution among the steps that
		 * need less of the given bytes; <code>null</code> where no archive can be made coarser. The
		 * candidates, each leaving every need the history answers answered:
		 * <ul>
		 * <li>a move: the archive at the finest of the {@link #COARSENING_STEPS} that needs less (joining
		 * an archive of that resolution), not beyond its retention and the device's coarsest
		 * resolution;</li>
		 * <li>a split: the needs the archive answers are parted at a bucket width; the archive keeps its
		 * resolution for the longest coverage of the needs with a finer bucket, and its whole retention
		 * goes to an archive at the coarsest step within the buckets of the others (joining an archive of
		 * that resolution); no fifth archive.</li>
		 * </ul>
		 * <p>
		 * The resolution given up is the factor from the archive's resolution to the new one; on a tie the
		 * candidate saving the most bytes, then the finest archive, a move before a split.
		 * </p>
		 */
		Derivation coarserArchive(ToLongFunction<History> bytes) {
			long before = bytes.applyAsLong(history);
			Derivation best = null;
			long bestFrom = 0;
			long bestTo = 0;
			long bestSaved = 0;
			for (Archive archive : history.archives()) {
				List<Step> candidates = new ArrayList<>();
				Step move = move(archive, bytes, before);
				if (move != null) {
					candidates.add(move);
				}
				candidates.addAll(splits(archive, bytes, before));
				for (Step step : candidates) {
					Derivation candidate = step.derivation();
					long to = step.resolution();
					long saved = before - bytes.applyAsLong(candidate.history());
					// Less resolution given up: to / from smaller; on a tie more saved.
					boolean better;
					if (best == null) {
						better = true;
					} else {
						long left = to * bestFrom;
						long right = bestTo * archive.resolution();
						better = left < right || (left == right && saved > bestSaved);
					}
					if (better) {
						best = candidate;
						bestFrom = archive.resolution();
						bestTo = to;
						bestSaved = saved;
					}
				}
			}
			return best;
		}

		/**
		 * The archive moved to the finest of the {@link #COARSENING_STEPS} that needs less of the bytes;
		 * <code>null</code> for none.
		 */
		private Step move(Archive archive, ToLongFunction<History> bytes, long before) {
			for (Long step : COARSENING_STEPS) {
				long next = step.longValue();
				if (next <= archive.resolution()) {
					continue;
				}
				if (next > archive.retention() || next > MAX_RESOLUTION) {
					break;
				}
				TreeMap<Long, Long> archives = new TreeMap<>();
				for (Archive other : history.archives()) {
					addArchive(archives, other == archive ? next : other.resolution(), other.retention());
				}
				Derivation candidate =
					new Derivation(raw, retention, spacing, needs, HistoryConfig.history(raw, retention, spacing, archives));
				if (!candidate.keepsAnswers(this)) {
					// A coarser step is not fine enough either.
					break;
				}
				if (bytes.applyAsLong(candidate.history()) < before) {
					return new Step(candidate, next);
				}
			}
			return null;
		}

		/**
		 * The splits of the archive that need less of the bytes, the parting bucket widths ascending.
		 */
		private List<Step> splits(Archive archive, ToLongFunction<History> bytes, long before) {
			List<Step> result = new ArrayList<>();
			List<Need> served = new ArrayList<>();
			TreeSet<Long> buckets = new TreeSet<>();
			for (Need need : needs) {
				if (need.answeredBy(archive.resolution(), archive.retention())) {
					served.add(need);
					buckets.add(Long.valueOf(need.bucket()));
				}
			}
			for (Long parting : buckets) {
				long kept = 0;
				long minBucket = Long.MAX_VALUE;
				for (Need need : served) {
					if (need.bucket() < parting.longValue()) {
						kept = Math.max(kept, need.coverage());
					} else {
						minBucket = Math.min(minBucket, need.bucket());
					}
				}
				if (kept == 0 || kept >= archive.retention()) {
					// All needs move (a move), or the archive keeps its whole retention.
					continue;
				}
				long coarse = 0;
				for (Long step : COARSENING_STEPS) {
					if (step.longValue() <= minBucket && step.longValue() <= archive.retention()
						&& step.longValue() <= MAX_RESOLUTION) {
						coarse = step.longValue();
					}
				}
				if (coarse <= archive.resolution() || kept < archive.resolution()) {
					continue;
				}
				TreeMap<Long, Long> archives = new TreeMap<>();
				for (Archive other : history.archives()) {
					if (other == archive) {
						addArchive(archives, archive.resolution(), kept);
						addArchive(archives, coarse, archive.retention());
					} else {
						addArchive(archives, other.resolution(), other.retention());
					}
				}
				if (archives.size() > MAX_ARCHIVES) {
					continue;
				}
				Derivation candidate =
					new Derivation(raw, retention, spacing, needs, HistoryConfig.history(raw, retention, spacing, archives));
				if (candidate.keepsAnswers(this) && bytes.applyAsLong(candidate.history()) < before) {
					result.add(new Step(candidate, coarse));
				}
			}
			return result;
		}

		/**
		 * The derivation with the raw ring made coarser for the same retention: to the finest of the
		 * {@link #COARSENING_STEPS} that needs less RAM, at most a day and the retention, finer than the
		 * finest archive, and leaving every need the history answers answered; <code>null</code> where
		 * it cannot be.
		 */
		Derivation coarserRing() {
			if (!raw) {
				return null;
			}
			long before = history.ramBytes();
			for (Long step : COARSENING_STEPS) {
				long next = step.longValue();
				if (next <= history.spacing()) {
					continue;
				}
				if (next > retention || next > ImageValidator.MAX_SPACING
					|| (!history.archives().isEmpty() && next >= history.archives().get(0).resolution())) {
					break;
				}
				TreeMap<Long, Long> archives = new TreeMap<>();
				for (Archive archive : history.archives()) {
					addArchive(archives, archive.resolution(), archive.retention());
				}
				Derivation candidate =
					new Derivation(raw, retention, next, needs, HistoryConfig.history(raw, retention, next, archives));
				if (!candidate.keepsAnswers(this)) {
					break;
				}
				if (candidate.history().ramBytes() < before) {
					return candidate;
				}
			}
			return null;
		}

		/**
		 * The derivation needing less RAM: the raw ring coarser, else an archive; <code>null</code> where
		 * neither can be.
		 */
		Derivation coarserForRam() {
			Derivation result = coarserRing();
			return result != null ? result : coarserArchive(History::ramBytes);
		}

		/**
		 * Whether this derivation's history answers every need the other's answers.
		 */
		boolean keepsAnswers(Derivation other) {
			for (Need need : needs) {
				if (need.answeredBy(other.history()) && !need.answeredBy(history)) {
					return false;
				}
			}
			return true;
		}
	}

	/**
	 * The history of the raw ring and the archives under the rules of the slots, the redundancy, the
	 * number of archives and the raw ring's spacing.
	 *
	 * @param raw
	 *        Whether a need asks for the raw ring of the given retention and spacing.
	 */
	private static History history(boolean raw, long rawRetention, long rawSpacing, TreeMap<Long, Long> needed) {
		TreeMap<Long, Long> archives = new TreeMap<>(needed);
		long spacing = rawSpacing;
		long retention = rawRetention;
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
	 * datapoint of a number or truth value), made coarser where the image would exceed the device
	 * ({@link #projectHistories(TLObject)}).
	 */
	public static Map<TLObject, History> derivedHistories(TLObject project) {
		return projectHistories(project).derived();
	}

	/**
	 * The derived histories of a project and the datapoints whose derived history in the image is
	 * coarser than their charts ask for, to fit the device.
	 *
	 * @param derived
	 *        The derived histories by datapoint, in the order of the pages.
	 * @param coarsened
	 *        The datapoints with a history set by derivation that was made coarser for the image, in
	 *        the order of the pages.
	 */
	public record ProjectHistories(Map<TLObject, History> derived, Set<TLObject> coarsened) {
		// A record.
	}

	/**
	 * The derived histories of the project's datapoints, within the device's limits for the image as a
	 * whole.
	 *
	 * <p>
	 * Each datapoint's history is first derived alone. The histories set by hand count as they are, and
	 * the derived ones of the datapoints whose history is not set by hand are then made coarser, one
	 * step at a time, while the image needs more than the device allows, as long as the histories set
	 * by hand alone fit:
	 * </p>
	 * <ul>
	 * <li>while the archives need more flash than {@value ImageValidator#ARCHIVE_CAP} bytes: of the
	 * datapoint with the most flash (on a tie, the smallest image id first) that can be coarser, the
	 * finest archive that can be coarser takes the finest of the {@link #COARSENING_STEPS} that needs
	 * less flash;</li>
	 * <li>else, while the rings and archives need more RAM than the project's <code>historyBudget</code>
	 * (or {@value ImageValidator#HISTORY_CAP} bytes): of the datapoint with the most RAM, the raw ring
	 * takes the finest coarser step that needs less RAM for the same retention, or else its finest
	 * archive such a step.</li>
	 * </ul>
	 * <p>
	 * Each step keeps every range of the charts answered by a store fine enough for its bucket width and
	 * reaching back ({@link Need#answeredBy(History)}). Where nothing can
	 * be coarser, or the histories set by hand alone need more than the device allows, the image's check
	 * refuses the image at <code>history</code>. An unchanged project gives the same histories.
	 * </p>
	 */
	public static ProjectHistories projectHistories(TLObject project) {
		Map<TLObject, Derivation> own = new LinkedHashMap<>();
		if (project == null) {
			return new ProjectHistories(new LinkedHashMap<>(), new LinkedHashSet<>());
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
			Derivation derivation = derivation(needs, shown.contains(datapoint));
			if (derivation != null) {
				own.put(datapoint, derivation);
			}
		}

		long manualRam = 0;
		long manualFlash = 0;
		for (TLObject datapoint : RuntimeFunctions.datapoints(project)) {
			if (isManual(datapoint)) {
				History history = manual(datapoint);
				if (history != null) {
					manualRam += history.ramBytes();
					manualFlash += history.flashBytes();
				}
			}
		}
		Object budget = project.tValueByName("historyBudget");
		long ramLimit = budget instanceof Number number ? number.longValue() : ImageValidator.HISTORY_CAP;

		Map<TLObject, Derivation> fitted = new LinkedHashMap<>();
		for (Map.Entry<TLObject, Derivation> entry : own.entrySet()) {
			if (!isManual(entry.getKey())) {
				fitted.put(entry.getKey(), entry.getValue());
			}
		}
		while (true) {
			long ram = manualRam;
			long flash = manualFlash;
			for (Derivation derivation : fitted.values()) {
				ram += derivation.history().ramBytes();
				flash += derivation.history().flashBytes();
			}
			boolean stepped = false;
			if (flash > ImageValidator.ARCHIVE_CAP && manualFlash <= ImageValidator.ARCHIVE_CAP) {
				stepped = step(fitted, History::flashBytes, derivation -> derivation.coarserArchive(History::flashBytes));
			}
			if (!stepped && ram > ramLimit && manualRam <= ramLimit) {
				stepped = step(fitted, History::ramBytes, Derivation::coarserForRam);
			}
			if (!stepped) {
				break;
			}
		}

		Map<TLObject, History> derived = new LinkedHashMap<>();
		Set<TLObject> coarsened = new LinkedHashSet<>();
		for (Map.Entry<TLObject, Derivation> entry : own.entrySet()) {
			Derivation result = fitted.getOrDefault(entry.getKey(), entry.getValue());
			derived.put(entry.getKey(), result.history());
			if (!result.history().equals(entry.getValue().history())) {
				coarsened.add(entry.getKey());
			}
		}
		return new ProjectHistories(derived, coarsened);
	}

	/**
	 * Makes the derivation of the datapoint with the most of the given bytes that can be coarser one
	 * step coarser; on a tie the smallest image id (then the object's id) first.
	 *
	 * @return Whether a derivation was made coarser.
	 */
	private static boolean step(Map<TLObject, Derivation> derivations, ToLongFunction<History> bytes,
			UnaryOperator<Derivation> coarser) {
		List<Map.Entry<TLObject, Derivation>> entries = new ArrayList<>(derivations.entrySet());
		entries.sort(Comparator.<Map.Entry<TLObject, Derivation>> comparingLong(
			entry -> -bytes.applyAsLong(entry.getValue().history()))
			.thenComparing(entry -> String.valueOf(Pages.imageId(entry.getKey())))
			.thenComparing(entry -> String.valueOf(entry.getKey().tId())));
		for (Map.Entry<TLObject, Derivation> entry : entries) {
			Derivation next = coarser.apply(entry.getValue());
			if (next != null) {
				derivations.put(entry.getKey(), next);
				return true;
			}
		}
		return false;
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
		Map<TLObject, History> derived = derivedHistories(project);
		for (TLObject datapoint : RuntimeFunctions.datapoints(project)) {
			History history = isManual(datapoint) ? manual(datapoint) : derived.get(datapoint);
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

	/**
	 * The RAM of the histories of the datapoints a chart draws, each datapoint once (a datapoint several
	 * charts draw counts in each of them).
	 */
	public static long chartRamBytes(TLObject chart) {
		return chartBytes(chart, HistoryConfig::ramBytes);
	}

	/**
	 * The flash of the archives of the datapoints a chart draws, each datapoint once (a datapoint several
	 * charts draw counts in each of them).
	 */
	public static long chartFlashBytes(TLObject chart) {
		return chartBytes(chart, HistoryConfig::flashBytes);
	}

	private static long chartBytes(TLObject chart, ToLongFunction<TLObject> bytes) {
		long result = 0;
		Set<TLObject> seen = new HashSet<>();
		for (TLObject series : RuleExport.objects(chart.tValueByName("series"))) {
			if (series.tValueByName("datapoint") instanceof TLObject drawn
				&& seen.add(BlockInstances.historyTarget(drawn))) {
				TLObject datapoint = BlockInstances.historyTarget(drawn);
				result += bytes.applyAsLong(datapoint);
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
	 * The bytes of the project's histories for the user: their RAM against the project's budget (or the
	 * device's cap without one) and the flash of their archives against the device's cap, each naming the
	 * biggest histories where it is exceeded, and the datapoints whose derived histories were made
	 * coarser to fit the device.
	 */
	public static String usage(TLObject project) {
		Map<TLObject, History> consumers = consumers(project);
		long ram = 0;
		long flash = 0;
		for (History history : consumers.values()) {
			ram += history.ramBytes();
			flash += history.flashBytes();
		}
		Object budget = project.tValueByName("historyBudget");
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
		String result = resources.getString(ramText) + " " + resources.getString(flashText);
		Set<TLObject> coarsened = projectHistories(project).coarsened();
		if (!coarsened.isEmpty()) {
			List<String> labels = new ArrayList<>();
			for (TLObject datapoint : coarsened) {
				labels.add(Pages.targetLabel(datapoint));
			}
			result += " " + resources.getString(I18NConstants.HISTORY_USAGE_COARSENED__DATAPOINTS.fill(String.join(", ", labels)));
		}
		return result;
	}

	/**
	 * The given number of biggest of the histories for the user by the given bytes: the datapoint and
	 * its bytes; histories without such bytes are left out.
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
			result.add(Pages.targetLabel(entry.getKey()) + " (" + value + " B)");
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
