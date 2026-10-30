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

import de.haumacher.ehome.connection.InstallationKeys;
import de.haumacher.ehome.connection.Signatures;
import de.haumacher.ehome.connection.Signatures.Keys;
import de.haumacher.ehome.runtime.ImageReport.Refusal;

/**
 * {@link ViewAction} exporting the runtime image of the input project
 * (<code>ehome.project:Project</code>) for download, after checking it as the controller would.
 *
 * <p>
 * The image carries the revision the next deployment would carry. The result is a map for a
 * dialog: <code>file</code> (the image as JSON file named after the project, <code>null</code> for a
 * refused image), <code>signature</code> (the detached signature <code>&lt;file&gt;.sig</code>, 128 hex
 * digits and a line end, made with the key the project's controller holds, see
 * {@link InstallationKeys#signer(TLObject, TLObject)}; <code>null</code> for a project without a key or
 * a refused image), <code>summary</code> (whether the image is valid; for a valid one what it holds, see
 * {@link #summary(RuntimeImage)}, and the key it is signed with),
 * <code>refusals</code> (one map per refusal with <code>path</code>, <code>reason</code> and the
 * <code>element</code> of the project the path leads to) and <code>notes</code> (what the export left
 * out, one line each).
 * </p>
 */
public class ExportRuntimeImageAction implements ViewAction {

	/** The suffix of the detached signature file beside the image (homecontrol issue #35). */
	public static final String SIGNATURE_SUFFIX = ".sig";

	/** The content type of the signature file: 128 hex digits and a line end. */
	public static final String SIGNATURE_CONTENT_TYPE = "text/plain";

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
			row.put("reason", refusal.reason());
			row.put("element", image.elementAt(refusal.path()));
			refusals.add(row);
		}
		Resources resources = Resources.getInstance();
		if (report.isValid()) {
			result.put("file", BinaryDataFactory.createBinaryData(image.getBytes(), RuntimeImage.CONTENT_TYPE,
				RuntimeImage.fileName(project)));
			Keys signer = InstallationKeys.signer(project, Deployer.controller(project));
			if (signer != null) {
				result.put("signature", BinaryDataFactory.createBinaryData(
					(Signatures.signImage(signer.seed(), image.getBytes()) + "\n").getBytes(StandardCharsets.US_ASCII),
					SIGNATURE_CONTENT_TYPE, RuntimeImage.fileName(project) + SIGNATURE_SUFFIX));
			} else {
				result.put("signature", null);
			}
			// A project without a key exports as before: no signature, no sentence about it.
			result.put("summary", signer == null ? summary(image)
				: summary(image) + " " + resources.getString(I18NConstants.EXPORT_SIGNED__FINGERPRINT.fill(signer.fingerprint())));
		} else {
			result.put("file", null);
			result.put("signature", null);
			result.put("summary",
				resources.getString(I18NConstants.EXPORT_INVALID__COUNT.fill(Integer.valueOf(refusals.size()))));
		}
		result.put("refusals", refusals);
		result.put("notes", String.join("\n", image.getNotes()));
		return result;
	}

	/**
	 * The sentences saying what a valid image holds: its datapoints, locations, functions and
	 * settings (the controller's settings and the history budget, each counted as one), its size,
	 * the addresses of its time master, and its rules and schedule entries.
	 */
	public static String summary(RuntimeImage image) {
		Map<String, Object> json = image.getJson();
		int settings = size(json.get("settings")).intValue() + (json.containsKey("history") ? 1 : 0);
		Resources resources = Resources.getInstance();
		String result = resources.getString(I18NConstants.EXPORT_VALID__DATAPOINTS_LOCATIONS_FUNCTIONS_SETTINGS_BYTES.fill(
			size(json.get("datapoints")), size(json.get("locations")), size(json.get("functions")),
			Integer.valueOf(settings), Integer.valueOf(image.getBytes().length)));
		List<String> addresses = new ArrayList<>();
		if (json.get("knx") instanceof Map<?, ?> knx && knx.get("timeMaster") instanceof Map<?, ?> timeMaster) {
			for (String member : List.of("time", "date", "dateTime")) {
				if (timeMaster.get(member) != null) {
					addresses.add(timeMaster.get(member).toString());
				}
			}
		}
		result += " " + resources.getString(addresses.isEmpty() ? I18NConstants.EXPORT_NO_TIME_MASTER
			: I18NConstants.EXPORT_TIME_MASTER__ADDRESSES.fill(String.join(", ", addresses)));
		if (json.containsKey("rules") || json.containsKey("schedules")) {
			result += " " + resources.getString(
				I18NConstants.EXPORT_AUTOMATION__RULES_SCHEDULES.fill(size(json.get("rules")), size(json.get("schedules"))));
		}
		return result;
	}

	private static Integer size(Object value) {
		int size = value instanceof List<?> elements ? elements.size() : value instanceof Map<?, ?> map ? map.size() : 0;
		return Integer.valueOf(size);
	}

}
