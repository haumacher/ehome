/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.runtime;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.top_logic.basic.util.ResKey;
import com.top_logic.model.TLObject;
import com.top_logic.util.Resources;

import de.haumacher.ehome.blocks.BlockType;
import de.haumacher.ehome.blocks.Manifest;
import de.haumacher.ehome.blocks.Manifest.Choice;
import de.haumacher.ehome.blocks.Manifest.Param;
import de.haumacher.ehome.blocks.Manifest.Port;
import de.haumacher.ehome.runtime.ControllerClient.Answer;

/**
 * The diagnostics of a deployed virtual device (haumacher/ehome #78), read from the controller of its
 * project on demand: <code>GET /api/blocks</code> (homecontrol <code>spec/api.md</code>, "Blocks",
 * <code>spec/blocks.md</code> 9), paged with <code>from</code> / <code>next</code> until the instance
 * is found, over the controller's E-Home connection or HTTP ({@link Deployer#client(TLObject)}).
 *
 * <p>
 * The result is a text for the user: the status (running, switched off, faulted with its reason),
 * the calls with the last one and the last error, the outputs and inputs with their values, the
 * timers, the parameters the inhabitant changed, and the state record. A project without controller,
 * a controller that cannot be reached, one that runs no image, another project's image or an image
 * without the instance (not deployed yet) is said as such instead.
 * </p>
 */
public final class BlockDiagnostics {

	/** The most pages read (an image holds at most {@link Manifest#MAX_BLOCKS} blocks). */
	private static final int MAX_PAGES = Manifest.MAX_BLOCKS + 1;

	private BlockDiagnostics() {
		// Static helpers.
	}

	/**
	 * Reads the diagnostics of the instance from the controller of its project.
	 */
	public static String read(TLObject instance) {
		String id = BlockInstances.identifier(instance);
		TLObject project = RuntimeFunctions.project(instance);
		TLObject controller = project == null ? null : Deployer.controller(project);
		if (id == null || controller == null) {
			return text(I18NConstants.BLOCK_DIAG_NO_CONTROLLER);
		}
		ControllerClient client = Deployer.client(controller);
		ControllerStatus.Loaded loaded = ControllerStatus.read(client);
		if (!loaded.answered()) {
			return text(I18NConstants.BLOCK_DIAG_UNREACHABLE__STATUS.fill(loaded.text()));
		}
		if (!loaded.loaded()) {
			return text(I18NConstants.BLOCK_DIAG_NOT_DEPLOYED__STATUS.fill(loaded.text()));
		}
		String projectId = Objects.toString(project.tValueByName("extId"), "");
		if (!projectId.equals(loaded.project())) {
			return text(I18NConstants.BLOCK_DIAG_OTHER_PROJECT__STATUS.fill(loaded.text()));
		}
		Object from = Long.valueOf(0);
		for (int page = 0; page < MAX_PAGES && from != null; page++) {
			Answer answer;
			try {
				answer = client.call("GET", "/api/blocks", "from=" + from, null, null);
			} catch (IOException ex) {
				return text(I18NConstants.BLOCK_DIAG_UNREACHABLE__STATUS.fill(Deployer.reason(ex)));
			}
			if (!answer.ok()) {
				String error = answer.string("error");
				return text(I18NConstants.BLOCK_DIAG_FAILED__STATUS_ERROR.fill(Integer.valueOf(answer.status()),
					error != null ? error : answer.text().strip()));
			}
			long now = answer.body().get("now_ms") instanceof Number number ? number.longValue() : 0;
			if (answer.body().get("blocks") instanceof List<?> blocks) {
				for (Object entry : blocks) {
					if (entry instanceof Map<?, ?> block && id.equals(block.get("id"))) {
						return format(instance, block, now, loaded.revision());
					}
				}
			}
			from = answer.body().get("next");
		}
		return text(I18NConstants.BLOCK_DIAG_NOT_IN_IMAGE__ID_REVISION.fill(id,
			loaded.revision() == null ? "?" : loaded.revision()));
	}

	/**
	 * The diagnostics of the controller's entry of the instance for the user.
	 *
	 * @param block
	 *        The entry of <code>GET /api/blocks</code>.
	 * @param now
	 *        The controller's monotonic time of the answer (<code>now_ms</code>).
	 * @param revision
	 *        The revision of the image the controller runs, <code>null</code> if unknown.
	 */
	public static String format(TLObject instance, Map<?, ?> block, long now, Long revision) {
		BlockType type = BlockInstances.type(instance);
		String typeId = String.valueOf(instance.tValueByName("blockType"));
		Manifest m = type == null ? null : type.manifest();
		List<String> lines = new ArrayList<>();
		Object rev = revision == null ? "?" : revision;
		String status = Objects.toString(block.get("status"), "");
		switch (status) {
			case "running":
				lines.add(text(I18NConstants.BLOCK_DIAG_RUNNING__REVISION.fill(rev)));
				break;
			case "disabled":
				lines.add(text(I18NConstants.BLOCK_DIAG_DISABLED__REVISION.fill(rev)));
				break;
			case "fault": {
				Map<?, ?> fault = block.get("fault") instanceof Map<?, ?> map ? map : Map.of();
				lines.add(text(I18NConstants.BLOCK_DIAG_FAULT__REASON_AGO.fill(Objects.toString(fault.get("reason"), "?"),
					ago(now, fault.get("ms")))));
				break;
			}
			default:
				lines.add(text(I18NConstants.BLOCK_DIAG_STATUS__STATUS.fill(status)));
		}
		if (Boolean.TRUE.equals(block.get("enabledOverridden"))) {
			lines.add(text(Boolean.TRUE.equals(block.get("enabled")) ? I18NConstants.BLOCK_DIAG_SWITCHED_ON
				: I18NConstants.BLOCK_DIAG_SWITCHED_OFF));
		}
		lines.add(text(I18NConstants.BLOCK_DIAG_CALLS__CALLS_TIME_LONGEST_ERRORS.fill(number(block.get("calls")),
			number(block.get("timeMs")), number(block.get("longestMs")), number(block.get("errors")))));
		if (block.get("lastCall") instanceof Map<?, ?> call) {
			lines.add(lastCall(typeId, m, call, now));
		}
		if (block.get("lastError") instanceof Map<?, ?> error) {
			lines.add(text(I18NConstants.BLOCK_DIAG_LAST_ERROR__CODE_TEXT_AGO.fill(Objects.toString(error.get("code"), "?"),
				Objects.toString(error.get("text"), ""), ago(now, error.get("ms")))));
		}

		List<String> outputs = new ArrayList<>();
		for (Object entry : list(block.get("outputs"))) {
			if (entry instanceof Map<?, ?> output) {
				String portId = String.valueOf(output.get("id"));
				String line = "  " + portLabel(typeId, m, portId) + ": "
					+ value(output.get("value"), Objects.toString(output.get("unit"), ""));
				if (output.get("datapoint") != null) {
					line += " → " + output.get("datapoint");
				}
				outputs.add(line);
			}
		}
		if (!outputs.isEmpty()) {
			lines.add(text(I18NConstants.BLOCK_DIAG_OUTPUTS));
			lines.addAll(outputs);
		}

		List<String> inputs = new ArrayList<>();
		for (Object entry : list(block.get("inputs"))) {
			if (entry instanceof Map<?, ?> input) {
				List<?> datapoints = list(input.get("datapoints"));
				if (datapoints.isEmpty()) {
					// Not bound: nothing to diagnose.
					continue;
				}
				String portId = String.valueOf(input.get("id"));
				Port decl = m == null || m.port(portId) < 0 ? null : m.ports().get(m.port(portId));
				String quality = Objects.toString(input.get("quality"), "");
				String value = input.get("value") == null || "unknown".equals(quality) ? text(I18NConstants.BLOCK_DIAG_UNKNOWN)
					: value(input.get("value"), decl == null ? "" : decl.unit());
				if ("stale".equals(quality)) {
					value = text(I18NConstants.BLOCK_DIAG_STALE__VALUE.fill(value));
				}
				StringBuilder bound = new StringBuilder();
				for (Object datapoint : datapoints) {
					bound.append(bound.length() == 0 ? "" : ", ").append(datapoint);
				}
				if (input.get("aggregation") instanceof String aggregation && datapoints.size() > 1) {
					bound.append(" (").append(BlockValues.aggregationLabel(Manifest.Aggregation.of(aggregation))).append(')');
				}
				inputs.add("  " + portLabel(typeId, m, portId) + ": " + value + " ← " + bound);
			}
		}
		if (!inputs.isEmpty()) {
			lines.add(text(I18NConstants.BLOCK_DIAG_INPUTS));
			lines.addAll(inputs);
		}

		List<String> timers = new ArrayList<>();
		for (Object entry : list(block.get("timers"))) {
			if (entry instanceof Map<?, ?> timer && timer.get("remaining_ms") instanceof Number remaining) {
				timers.add(text(I18NConstants.BLOCK_DIAG_TIMER__TIMER_IN.fill(String.valueOf(timer.get("id")),
					duration(remaining.longValue()))));
			}
		}
		if (!timers.isEmpty()) {
			lines.add(text(I18NConstants.BLOCK_DIAG_TIMERS__TIMERS.fill(String.join(", ", timers))));
		}

		for (Object entry : list(block.get("parameters"))) {
			if (entry instanceof Map<?, ?> parameter && Boolean.TRUE.equals(parameter.get("overridden"))) {
				String paramId = String.valueOf(parameter.get("id"));
				Param decl = m == null || m.param(paramId) < 0 ? null : m.params().get(m.param(paramId));
				lines.add(text(I18NConstants.BLOCK_DIAG_OVERRIDDEN__PARAMETER_VALUE_IMAGE.fill(
					decl == null ? paramId : BlockTexts.label(typeId, paramId, decl.label(), BlockValues.language()),
					parameterValue(typeId, decl, parameter.get("value")), parameterValue(typeId, decl, parameter.get("default")))));
			}
		}

		if (block.get("state") instanceof Map<?, ?> state && !state.isEmpty()) {
			List<String> fields = new ArrayList<>();
			state.forEach((field, value) -> fields.add(field + " = " + value(value, "")));
			lines.add(text(I18NConstants.BLOCK_DIAG_STATE__FIELDS.fill(String.join(", ", fields))));
		}
		return String.join("\n", lines);
	}

	private static String lastCall(String typeId, Manifest m, Map<?, ?> call, long now) {
		String kind = Objects.toString(call.get("kind"), "");
		String arg = Objects.toString(call.get("arg"), "");
		String ago = ago(now, call.get("ms"));
		switch (kind) {
			case "start":
				return text(I18NConstants.BLOCK_DIAG_CALL_START__REASON_AGO.fill(arg, ago));
			case "input":
				return text(I18NConstants.BLOCK_DIAG_CALL_INPUT__PORT_AGO.fill(portLabel(typeId, m, arg), ago));
			case "timer":
				return text(I18NConstants.BLOCK_DIAG_CALL_TIMER__TIMER_AGO.fill(arg, ago));
			case "period":
				return text(I18NConstants.BLOCK_DIAG_CALL_PERIOD__AGO.fill(ago));
			case "parameter": {
				Param decl = m == null || m.param(arg) < 0 ? null : m.params().get(m.param(arg));
				return text(I18NConstants.BLOCK_DIAG_CALL_PARAMETER__PARAMETER_AGO.fill(
					decl == null ? arg : BlockTexts.label(typeId, arg, decl.label(), BlockValues.language()), ago));
			}
			default:
				return text(I18NConstants.BLOCK_DIAG_CALL__KIND_AGO.fill(kind, ago));
		}
	}

	private static String portLabel(String typeId, Manifest m, String portId) {
		Port decl = m == null || m.port(portId) < 0 ? null : m.ports().get(m.port(portId));
		return decl == null ? portId : BlockTexts.label(typeId, portId, decl.label(), BlockValues.language());
	}

	/** A parameter's value of the controller's JSON (spec/blocks.md 4) as the views show it. */
	private static String parameterValue(String typeId, Param decl, Object json) {
		if (decl == null) {
			return value(json, "");
		}
		Double number = jsonNumber(decl, json);
		return number == null ? value(json, "") : BlockValues.display(typeId, decl, number.doubleValue());
	}

	private static Double jsonNumber(Param decl, Object json) {
		if (json instanceof Number number) {
			return Double.valueOf(number.doubleValue());
		}
		if (json instanceof Boolean bool) {
			return Double.valueOf(bool.booleanValue() ? 1 : 0);
		}
		if (json instanceof String id) {
			Choice choice = decl.choice(id);
			return choice == null ? null : Double.valueOf(choice.value());
		}
		if (json instanceof Map<?, ?> time && time.get("hour") instanceof Number hour) {
			return Double.valueOf(hour.longValue() * 3600 + seconds(time.get("minute")) * 60 + seconds(time.get("second")));
		}
		return null;
	}

	private static long seconds(Object value) {
		return value instanceof Number number ? number.longValue() : 0;
	}

	/** A value of the controller's JSON for the user, with the unit. */
	private static String value(Object json, String unit) {
		String result;
		if (json == null) {
			return text(I18NConstants.BLOCK_DIAG_UNKNOWN);
		} else if (json instanceof Boolean bool) {
			return text(bool.booleanValue() ? I18NConstants.BLOCK_VALUE_YES : I18NConstants.BLOCK_VALUE_NO);
		} else if (json instanceof Number number) {
			result = BlockValues.format(number.doubleValue());
		} else if (json instanceof Map<?, ?> time && time.get("hour") instanceof Number hour) {
			result = String.format("%02d:%02d:%02d", Long.valueOf(hour.longValue()), Long.valueOf(seconds(time.get("minute"))),
				Long.valueOf(seconds(time.get("second"))));
		} else {
			result = String.valueOf(json);
		}
		return unit == null || unit.isBlank() ? result : result + " " + unit.trim();
	}

	private static String ago(long now, Object ms) {
		if (!(ms instanceof Number number)) {
			return "?";
		}
		return duration(Math.max(0, now - number.longValue()));
	}

	/** A span of time for the user, in s below 2 min, in min below 2 h, else in h. */
	static String duration(long ms) {
		long s = ms / 1000;
		if (s < 120) {
			return s + " s";
		}
		if (s < 7200) {
			return s / 60 + " min";
		}
		return s / 3600 + " h";
	}

	private static String number(Object value) {
		return value instanceof Number number ? BlockValues.format(number.doubleValue()) : "?";
	}

	private static List<?> list(Object value) {
		return value instanceof List<?> list ? list : List.of();
	}

	private static String text(ResKey key) {
		return Resources.getInstance().getString(key);
	}

}
