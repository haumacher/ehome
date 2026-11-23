/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.runtime;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.top_logic.basic.Logger;
import com.top_logic.dob.ex.NoSuchAttributeException;
import com.top_logic.element.meta.MetaElementUtil;
import com.top_logic.knowledge.objects.KnowledgeItem;
import com.top_logic.knowledge.service.migration.StartupAction;
import com.top_logic.model.TLClass;
import com.top_logic.model.TLClassifier;
import com.top_logic.model.TLObject;
import com.top_logic.model.util.TLModelUtil;

import de.haumacher.ehome.model.logical.GroupAddress;
import de.haumacher.ehome.model.project.Project;
import de.haumacher.ehome.model.runtime.ApplicationDatapoint;
import de.haumacher.ehome.model.runtime.ChartElement;
import de.haumacher.ehome.model.runtime.ChartSeries;
import de.haumacher.ehome.model.runtime.Function;
import de.haumacher.ehome.model.runtime.Page;
import de.haumacher.ehome.model.runtime.PageSection;
import de.haumacher.ehome.runtime.HistoryConfig.Level;

/**
 * The second step of the migration <code>Issue_221_History_recording</code> (haumacher/ehome #221):
 * every group address and application datapoint gets once its history effective before as its
 * recording, the reach of that history rounded up to the next level ({@link Level#covering(long,
 * boolean)}), so no chart loses data; a datapoint without history gets {@link Level#NONE}.
 *
 * <ul>
 * <li>A history set by hand: its reach, which the migration's SQL kept as the flex attribute
 * {@value #REACH} (the longest retention of its ring and archives; 0 for one without a raw ring, which
 * the image left out).</li>
 * <li>A history derived from the charts of the control pages (haumacher/ehome #73): the longest
 * coverage the charts asked for, a line the range it offers, consumption bars of a number one bar
 * more than the range (12 of 5 min for 1 h, 24 of 1 h for 24 h, 7 and 30 of a day, 12 of a twelfth of
 * a year), and 24 hours for a datapoint a function shows.</li>
 * </ul>
 * <p>
 * The charts follow (haumacher/ehome #221): a range or preset of 1 h, which no recording serves, becomes
 * 6 h, and a datapoint a chart draws is recorded at least as long as the longest range the chart
 * offers, so that no migrated chart is refused by the image's check.
 * </p>
 * <p>
 * A number a chart drew as consumption bars becomes a meter reading (a group address only where its
 * datapoint type does not say so already), so the controller's generic view draws it as the page did.
 * The flex attribute is removed.
 * </p>
 *
 * <p>
 * Runs once, in the started application after the migration, in a transaction of the migration
 * service.
 * </p>
 */
public class RecordingMigration implements StartupAction {

	/** The flex attribute the migration's SQL kept the reach of a history set by hand in. */
	public static final String REACH = "issue221.reach";

	/** The consumption bars of the controller's UI before #221 per range: their count and width in seconds. */
	private static final Map<Long, long[]> BARS = Map.of(
		Long.valueOf(3600), new long[] { 12, 300 },
		Long.valueOf(86400), new long[] { 24, 3600 },
		Long.valueOf(7 * 86400), new long[] { 7, 86400 },
		Long.valueOf(30 * 86400), new long[] { 30, 86400 },
		Long.valueOf(365 * 86400), new long[] { 12, 365 * 86400 / 12 });

	/** The classifier of ChartRange of 1 h, which no recording serves. */
	private static final String HOUR = "hour";

	/** The classifier of ChartRange of 6 h, the shortest range a recording serves. */
	private static final String SIX_HOURS = "sixHours";

	/** What a function asked of the datapoints of its roles before #221: 24 hours. */
	private static final long FUNCTION_COVERAGE = 86400;

	@Override
	public void perform() {
		int[] counts = migrate();
		Logger.info("Recordings of the datapoints (haumacher/ehome #221): " + counts[0] + " with a history, "
			+ counts[1] + " meter readings, " + counts[2] + " without.", RecordingMigration.class);
	}

	/**
	 * Gives every group address and application datapoint its recording.
	 *
	 * @return The datapoints given a recording, those made meter readings and those given none.
	 */
	public static int[] migrate() {
		int[] counts = new int[3];
		Set<TLObject> done = new HashSet<>();
		TLClass projectType = (TLClass) TLModelUtil.findType("ehome.project:Project");
		for (TLObject project : MetaElementUtil.getAllDirectInstancesOf(projectType, TLObject.class)) {
			migrate(project, done, counts);
		}
		// Datapoints of no project.
		for (String name : List.of("ehome.logical:GroupAddress", "ehome.runtime:ApplicationDatapoint")) {
			TLClass type = (TLClass) TLModelUtil.findType(name);
			for (TLObject datapoint : MetaElementUtil.getAllDirectInstancesOf(type, TLObject.class)) {
				if (done.add(datapoint)) {
					set(datapoint, take(datapoint), 0, false, counts);
				}
			}
		}
		return counts;
	}

	/**
	 * Gives the group addresses and application datapoints of the project their recordings.
	 */
	public static void migrate(TLObject project, Set<TLObject> done, int[] counts) {
		List<TLObject> charts = sixHours(project);
		Set<TLObject> shown = shownByFunctions(project);
		Map<TLObject, List<Pages.ChartUse>> uses = Pages.chartUses(project);
		List<TLObject> datapoints = RuntimeFunctions.groupAddresses(project);
		datapoints.addAll(ApplicationDatapoints.of(project));
		for (TLObject datapoint : datapoints) {
			if (!done.add(datapoint)) {
				continue;
			}
			Long manual = take(datapoint);
			boolean number = HistoryConfig.canHaveHistory(datapoint)
				&& !ValueTypes.BOOLEAN.equals(Pages.valueType(datapoint));
			long derived = 0;
			boolean bars = false;
			for (Pages.ChartUse use : uses.getOrDefault(datapoint, List.of())) {
				boolean consumption = number
					&& "consumption".equals(Pages.name(((ChartSeries) use.series()).getConsolidation()));
				bars |= consumption;
				for (Long range : use.ranges()) {
					long[] bar = BARS.get(range);
					derived = Math.max(derived,
						consumption && bar != null ? (bar[0] + 1) * bar[1] : range.longValue());
				}
			}
			if (derived > 0 && shown.contains(datapoint)) {
				derived = Math.max(derived, FUNCTION_COVERAGE);
			}
			set(datapoint, manual, derived, bars, counts);
		}
		for (TLObject chart : charts) {
			reachRanges(chart, counts);
		}
	}

	/**
	 * The charts of the project with the range 1 h, which no recording serves (haumacher/ehome #221),
	 * moved to 6 h: the range 1 h becomes 6 h, a preset 1 h becomes 6 h (once, the presets keep the order
	 * of the ranges).
	 *
	 * @return Every chart of the project's pages.
	 */
	private static List<TLObject> sixHours(TLObject project) {
		List<TLObject> result = new ArrayList<>();
		TLClassifier hour = RuleImport.classifier("ChartRange", HOUR);
		TLClassifier sixHours = RuleImport.classifier("ChartRange", SIX_HOURS);
		for (TLObject page : RuleExport.objects(((Project) project).getPages())) {
			for (TLObject section : RuleExport.objects(((Page) page).getSections())) {
				for (TLObject element : RuleExport.objects(((PageSection) section).getElements())) {
					if (!(element instanceof ChartElement chart)) {
						continue;
					}
					result.add(chart);
					if (chart.getRange() == hour) {
						chart.setRange(sixHours);
					}
					if (chart.getPresets() instanceof Collection<?> presets && presets.contains(hour)) {
						Set<TLClassifier> moved = new LinkedHashSet<>();
						for (Object preset : presets) {
							moved.add(preset == hour ? sixHours : (TLClassifier) preset);
						}
						chart.setPresets(moved);
					}
				}
			}
		}
		return result;
	}

	/**
	 * Raises the recording of each datapoint the chart draws to the longest range it offers, so the
	 * migrated chart passes the image's check: the recording derived from the charts reaches them, a
	 * history set by hand that was shorter now reaches them, too.
	 */
	private static void reachRanges(TLObject chart, int[] counts) {
		long longest = 0;
		for (String range : Pages.offeredRanges(chart)) {
			Long seconds = Pages.rangeSeconds(range);
			longest = Math.max(longest, seconds == null ? 0 : seconds.longValue());
		}
		for (TLObject datapoint : HistoryConfig.drawn(chart)) {
			if (!HistoryConfig.canHaveHistory(datapoint) || HistoryConfig.level(datapoint).reach() >= longest) {
				continue;
			}
			Level level = Level.NONE;
			for (Level candidate : Level.values()) {
				if (candidate.reach() >= longest) {
					level = candidate;
					break;
				}
			}
			if (level == Level.NONE) {
				level = Level.TEN_YEARS;
			}
			if (HistoryConfig.level(datapoint) == Level.NONE) {
				// Counted as one without history before.
				counts[2]--;
				counts[0]++;
			}
			if (datapoint instanceof GroupAddress address) {
				address.setRecording(HistoryConfig.classifier(level));
			} else if (datapoint instanceof ApplicationDatapoint application) {
				application.setRecording(HistoryConfig.classifier(level));
			}
		}
	}

	/**
	 * Sets the recording (and the meter flag) of the datapoint.
	 *
	 * @param manual
	 *        The reach of its history set by hand, <code>null</code> for a derived one.
	 * @param derived
	 *        The reach of the history the charts derived.
	 * @param bars
	 *        Whether a chart drew the datapoint as consumption bars.
	 */
	private static void set(TLObject datapoint, Long manual, long derived, boolean bars, int[] counts) {
		if (bars) {
			if (datapoint instanceof GroupAddress address) {
				if (!HistoryConfig.meterByType(address)) {
					address.setMeter(Boolean.TRUE);
				}
			} else {
				((ApplicationDatapoint) datapoint).setMeter(true);
			}
			counts[1]++;
		}
		long reach = manual != null ? manual.longValue() : derived;
		Level level = HistoryConfig.canHaveHistory(datapoint)
			? Level.covering(reach, HistoryConfig.meter(datapoint) && !ValueTypes.BOOLEAN.equals(Pages.valueType(datapoint)))
			: Level.NONE;
		if (datapoint instanceof GroupAddress address) {
			address.setRecording(HistoryConfig.classifier(level));
		} else {
			((ApplicationDatapoint) datapoint).setRecording(HistoryConfig.classifier(level));
		}
		counts[level == Level.NONE ? 2 : 0]++;
	}

	/**
	 * The reach of the history set by hand the migration's SQL kept for the datapoint, removed;
	 * <code>null</code> for none.
	 */
	private static Long take(TLObject datapoint) {
		KnowledgeItem item = datapoint.tHandle();
		Object reach;
		try {
			reach = item.getAttributeValue(REACH);
		} catch (NoSuchAttributeException ex) {
			return null;
		}
		if (reach == null) {
			return null;
		}
		item.setAttributeValue(REACH, null);
		return Long.valueOf(((Number) reach).longValue());
	}

	/**
	 * The datapoints the roles of the project's functions refer to.
	 */
	private static Set<TLObject> shownByFunctions(TLObject project) {
		Set<TLObject> result = new HashSet<>();
		for (TLObject function : FunctionProposal.functions(project)) {
			for (String role : FunctionKinds.roles(Pages.name(((Function) function).getKind()))) {
				if (function.tValueByName(role) instanceof TLObject datapoint) {
					result.add(datapoint);
				}
			}
		}
		return result;
	}

}
