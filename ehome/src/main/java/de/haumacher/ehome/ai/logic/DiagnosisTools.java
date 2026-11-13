/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.ai.logic;

import static de.haumacher.ehome.ai.logic.LogicSupport.answer;
import static de.haumacher.ehome.ai.logic.LogicSupport.failure;
import static de.haumacher.ehome.ai.logic.LogicSupport.id;
import static de.haumacher.ehome.ai.logic.LogicSupport.label;
import static de.haumacher.ehome.ai.logic.LogicSupport.project;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.top_logic.basic.thread.ThreadContext;
import com.top_logic.model.TLObject;
import com.top_logic.tl.ai.tool.api.P;
import com.top_logic.tl.ai.tool.api.ToolGroups;
import com.top_logic.tl.ai.tool.api.ToolMethod;
import com.top_logic.tl.ai.tool.api.ToolMode;
import com.top_logic.tl.ai.tool.api.ToolResult;

import de.haumacher.ehome.monitor.MonitorRow;
import de.haumacher.ehome.monitor.RecordingEntries;
import de.haumacher.ehome.runtime.DatapointNames;
import de.haumacher.ehome.runtime.ImageReport;
import de.haumacher.ehome.runtime.RuleReferences;
import de.haumacher.ehome.runtime.RuntimeImage;

/**
 * The assistant's tools for diagnosing a project's logic (haumacher/ehome #128): the problems of its
 * runtime image as the controller would refuse it (the check of the runtime image view, without
 * deploying), and the recordings of the group monitor for a datapoint in a time range.
 */
public class DiagnosisTools {

	/** The most entries of the recordings one answer lists. */
	private static final int MAX_ENTRIES = 500;

	/**
	 * The problems of the runtime image.
	 */
	@ToolMethod(description = "Checks the runtime image of an E-Home project as the controller would check it"
		+ " before loading it - what the runtime image view shows, without deploying anything: every refusal"
		+ " with the object of the project it concerns (rule, schedule entry, datapoint, function, page, virtual"
		+ " device) and the application's message, the notes of the export (group addresses without a datapoint"
		+ " type, unused functions), and the size of the image. A project with refusals cannot be deployed and"
		+ " not simulated.",
		mode = ToolMode.READ, group = ToolGroups.DATA_READ)
	public ToolResult imageProblems(
			@P("The project, or any object of it") TLObject project) {
		TLObject owner = project(project);
		if (owner == null) {
			return failure("'" + id(project) + "' belongs to no project.");
		}
		RuntimeImage image = RuntimeImage.build(owner, 1, Instant.now());
		ImageReport report = image.validate();
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("valid", Boolean.valueOf(report.isValid()));
		List<Object> refusals = new ArrayList<>();
		for (ImageReport.Refusal refusal : report.getRefusals()) {
			Map<String, Object> entry = new LinkedHashMap<>();
			entry.put("message", LogicSupport.text(image.describe(refusal)));
			entry.put("path", refusal.path());
			TLObject element = image.elementAt(refusal.path());
			if (element != null) {
				entry.put("object", LogicSupport.ref(element));
			}
			refusals.add(entry);
		}
		result.put("refusals", refusals);
		List<String> notes = new ArrayList<>(image.getNotes());
		notes.addAll(report.getNotes());
		if (image.getUnusedFunctions() > 0) {
			notes.add(image.getUnusedFunctions() + " unused functions (their main role has no datapoint) are left out.");
		}
		result.put("notes", notes);
		result.put("bytes", Integer.valueOf(image.getBytes().length));
		return answer(report.isValid() ? "The runtime image is valid: the controller would load it."
			: "The controller would refuse the runtime image:", result);
	}

	/**
	 * What the group monitor recorded of a datapoint.
	 */
	@ToolMethod(description = "Reads what the group monitor of E-Home recorded of a datapoint in a time range: the"
		+ " telegrams on its group address (time, sender, service, value) and the events a controller reported"
		+ " for it (the commands of its rules), from every recording of the project, in the order received. Use"
		+ " it to answer why something did or did not happen at a time (\"why did the light not switch at 7?\"),"
		+ " together with explainRule and simulate. Only what a recording captured is there; the times are local"
		+ " times of the user.",
		mode = ToolMode.READ, group = ToolGroups.DATA_READ)
	public ToolResult datapointRecordings(
			@P("The datapoint: a group address, an application datapoint, ...") TLObject datapoint,
			@P(value = "The start of the range, local, e.g. \"2026-11-10T06:00\"", required = false) String from,
			@P(value = "The end of the range, local, e.g. \"2026-11-10T08:00\"", required = false) String until) {
		TLObject owner = project(datapoint);
		if (owner == null) {
			return failure("'" + id(datapoint) + "' belongs to no project.");
		}
		String imageId = RuleReferences.datapointId(owner, datapoint);
		String low = from == null ? null : from.replace('T', ' ');
		String high = until == null ? null : until.replace('T', ' ');
		List<Object> entries = new ArrayList<>();
		List<String> recordings = new ArrayList<>();
		int found = 0;
		for (TLObject recording : AutomationChange.objects(owner.tValueByName("recordings"))) {
			recordings.add(label(recording));
			List<TLObject> objects = RecordingEntries.entries(recording);
			List<MonitorRow> rows = RecordingEntries.rows(recording, ThreadContext.getTimeZone());
			for (int n = 0; n < objects.size() && n < rows.size(); n++) {
				TLObject entry = objects.get(n);
				boolean match = RecordingEntries.isEvent(entry)
					? imageId != null && imageId.equals(entry.tValueByName("name"))
					: entry.tValueByName("groupAddress") == datapoint;
				if (!match) {
					continue;
				}
				MonitorRow row = rows.get(n);
				String time = String.valueOf(row.get("time"));
				if (low != null && time.compareTo(low) < 0 || high != null && time.compareTo(high) > 0) {
					continue;
				}
				found++;
				if (entries.size() < MAX_ENTRIES) {
					Map<String, Object> item = new LinkedHashMap<>();
					item.put("time", time);
					item.put("kind", row.get("kind"));
					item.put("sender", row.get("sender"));
					if (row.get("service") != null) {
						item.put("service", label(row.get("service")));
					}
					item.put("value", row.get("value"));
					entries.add(item);
				}
			}
		}
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("datapoint", imageId == null ? label(datapoint) : DatapointNames.of(owner).datapoint(imageId));
		result.put("recordings", recordings);
		result.put("entries", entries);
		if (found > entries.size()) {
			result.put("more", (found - entries.size()) + " further entries; narrow the range.");
		}
		return answer(recordings.isEmpty() ? "The project has no recordings of the group monitor."
			: found + " entries recorded:", result);
	}

}
