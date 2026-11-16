/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.runtime;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.top_logic.basic.CalledByReflection;
import com.top_logic.basic.config.InstantiationContext;
import com.top_logic.basic.config.PolymorphicConfiguration;
import com.top_logic.basic.config.annotation.TagName;
import com.top_logic.basic.config.annotation.defaults.ClassDefault;
import com.top_logic.basic.io.binary.BinaryDataFactory;
import com.top_logic.layout.react.ReactContext;
import com.top_logic.layout.view.command.ViewAction;
import com.top_logic.layout.view.command.WithResultActions;
import com.top_logic.model.TLObject;
import com.top_logic.util.Resources;
import com.top_logic.util.error.TopLogicException;

import de.haumacher.ehome.blocks.BlockSet;
import de.haumacher.ehome.connection.InstallationKeys;
import de.haumacher.ehome.connection.Signatures.Keys;
import de.haumacher.ehome.runtime.ImageReport.Refusal;
import de.haumacher.ehome.runtime.binary.CompileException;

/**
 * {@link ViewAction} exporting the runtime image of the input project
 * (<code>ehome.project:Project</code>) for download, after checking it as the controller would.
 *
 * <p>
 * The image carries the revision the next deployment would carry. The result is a map for a
 * dialog: <code>file</code> (the binary image the controller runs, homecontrol
 * <code>spec/runtime-image.md</code> section 9, named after the project with the extension
 * <code>.bin</code>, signed inside with the key the project's controller holds, see
 * {@link InstallationKeys#signer(TLObject, TLObject)}; <code>null</code> for a refused image),
 * <code>source</code> (its source form as JSON file, for people; <code>null</code> for a refused image),
 * <code>summary</code> (whether the image is valid; for a valid one what it holds and its size, see
 * {@link #summary(RuntimeImage, int)}, and the key it is signed with),
 * <code>refusals</code> (one map per refusal with <code>path</code>, <code>text</code> in the user's
 * language, <code>reason</code> in English as the controller gives it and the <code>element</code> of
 * the project the path leads to) and <code>notes</code> (what the export left
 * out, one line each).
 * </p>
 */
public class ExportRuntimeImageAction implements ViewAction {

	/**
	 * Configuration of {@link ExportRuntimeImageAction}.
	 */
	@TagName("export-runtime-image")
	public interface Config extends PolymorphicConfiguration<ExportRuntimeImageAction>, WithResultActions {

		@Override
		@ClassDefault(ExportRuntimeImageAction.class)
		Class<? extends ExportRuntimeImageAction> getImplementationClass();
	}

	/**
	 * Creates a {@link ExportRuntimeImageAction} from configuration.
	 */
	@CalledByReflection
	public ExportRuntimeImageAction(InstantiationContext context, Config config) {
		// No configuration.
	}

	@Override
	public Object execute(ReactContext context, Object input) {
		return export((TLObject) input, Instant.now());
	}

	/**
	 * The export of the given project as the dialog shows it, see the class comment.
	 */
	public static Map<String, Object> export(TLObject project, Instant now) {
		RuntimeImage image;
		try {
			image = RuntimeImage.build(project, Deployer.nextRevision(project), now);
		} catch (RuntimeException ex) {
			throw new TopLogicException(I18NConstants.ERROR_EXPORT_FAILED__DETAIL.fill(String.valueOf(ex.getMessage())), ex);
		}
		ImageReport report = image.validate();
		Map<String, Object> result = new LinkedHashMap<>();
		List<Object> refusals = new ArrayList<>();
		for (Refusal refusal : report.getRefusals()) {
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("path", refusal.path());
			row.put("text", refusal.text());
			row.put("reason", refusal.reason());
			row.put("element", image.elementAt(refusal.path()));
			refusals.add(row);
		}
		Resources resources = Resources.getInstance();
		Keys signer = report.isValid() ? InstallationKeys.signer(project, Deployer.controller(project)) : null;
		byte[] binary = null;
		String problem = null;
		if (report.isValid()) {
			try {
				binary = image.compile(signer == null ? null : signer.seed());
			} catch (CompileException ex) {
				problem = resources.getString(I18NConstants.DEPLOY_COMPILE_FAILED__PATH_REASON.fill(ex.getPath(), ex.getReason()));
			}
		}
		if (binary != null) {
			result.put("file", BinaryDataFactory.createBinaryData(binary, RuntimeImage.BINARY_CONTENT_TYPE,
				RuntimeImage.binaryFileName(project)));
			result.put("source", BinaryDataFactory.createBinaryData(Json.write(image.getJson()).getBytes(StandardCharsets.UTF_8),
				RuntimeImage.CONTENT_TYPE, RuntimeImage.fileName(project)));
			// A project without a key exports as before: no sentence about it.
			result.put("summary", signer == null ? summary(image, binary.length)
				: summary(image, binary.length) + " "
					+ resources.getString(I18NConstants.EXPORT_SIGNED__FINGERPRINT.fill(signer.fingerprint())));
		} else {
			result.put("file", null);
			result.put("source", null);
			result.put("summary", problem != null ? problem
				: resources.getString(I18NConstants.EXPORT_INVALID__COUNT.fill(Integer.valueOf(refusals.size()))));
		}
		result.put("refusals", refusals);
		result.put("notes", String.join("\n", image.getNotes()));
		return result;
	}

	/**
	 * The sentences saying what a valid image holds: its datapoints (those it lists and those of its
	 * virtual devices, <code>spec/blocks.md</code> 6, haumacher/ehome #78), locations, functions and
	 * settings (the controller's settings and the history budget, each counted as one), the size of the
	 * binary image,
	 * the virtual devices with their datapoints, the addresses of its time master, its rules and
	 * schedule entries, and the unused functions it leaves out (haumacher/ehome #98).
	 */
	public static String summary(RuntimeImage image) {
		return summary(image, image.compile(null).length);
	}

	/**
	 * {@link #summary(RuntimeImage)} with the size of the binary image given.
	 */
	public static String summary(RuntimeImage image, int bytes) {
		Map<String, Object> json = image.getJson();
		int settings = size(json.get("settings")).intValue() + (json.containsKey("history") ? 1 : 0);
		int blockDatapoints = BlockSet.impliedDatapoints(json.get("blocks"), BlockInstances.registry()).size();
		Resources resources = Resources.getInstance();
		String result = resources.getString(I18NConstants.EXPORT_VALID__DATAPOINTS_LOCATIONS_FUNCTIONS_SETTINGS_BYTES.fill(
			Integer.valueOf(size(json.get("datapoints")).intValue() + blockDatapoints), size(json.get("locations")),
			size(json.get("functions")), Integer.valueOf(settings), Integer.valueOf(bytes)));
		if (json.containsKey("blocks")) {
			result += " " + resources.getString(I18NConstants.EXPORT_BLOCKS__BLOCKS_DATAPOINTS.fill(size(json.get("blocks")),
				Integer.valueOf(blockDatapoints)));
		}
		List<String> addresses = new ArrayList<>();
		if (json.get("knx") instanceof Map<?, ?> knx && knx.get("timeMaster") instanceof Map<?, ?> timeMaster) {
			DatapointNames names = DatapointNames.of(image.elementAt("knx"));
			for (String member : List.of("time", "date", "dateTime")) {
				if (timeMaster.get(member) != null) {
					addresses.add(names.groupAddress(timeMaster.get(member).toString()));
				}
			}
		}
		result += " " + resources.getString(addresses.isEmpty() ? I18NConstants.EXPORT_NO_TIME_MASTER
			: I18NConstants.EXPORT_TIME_MASTER__ADDRESSES.fill(String.join(", ", addresses)));
		if (json.containsKey("rules") || json.containsKey("schedules")) {
			result += " " + resources.getString(
				I18NConstants.EXPORT_AUTOMATION__RULES_SCHEDULES.fill(size(json.get("rules")), size(json.get("schedules"))));
		}
		if (image.getUnusedFunctions() > 0) {
			result += " " + resources.getString(
				I18NConstants.EXPORT_UNUSED__COUNT.fill(Integer.valueOf(image.getUnusedFunctions())));
		}
		return result;
	}

	private static Integer size(Object value) {
		int size = value instanceof List<?> elements ? elements.size() : value instanceof Map<?, ?> map ? map.size() : 0;
		return Integer.valueOf(size);
	}

}
