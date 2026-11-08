/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.blocks;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.top_logic.basic.util.ResKey;
import com.top_logic.basic.util.ResKey1;

import de.haumacher.ehome.blocks.Manifest.Aggregation;
import de.haumacher.ehome.blocks.Manifest.Choice;
import de.haumacher.ehome.blocks.Manifest.Param;
import de.haumacher.ehome.blocks.Manifest.ParamType;
import de.haumacher.ehome.blocks.Manifest.Port;
import de.haumacher.ehome.rules.DatapointId;
import de.haumacher.ehome.rules.OwnerLabel;
import de.haumacher.ehome.rules.ParameterStore;
import de.haumacher.ehome.rules.RuleLoader.DatapointDirectory;
import de.haumacher.ehome.rules.ValueType;

/**
 * The <code>blocks</code> member of an image, compiled against the datapoints: every reference
 * resolved, every value checked (homecontrol <code>spec/blocks.md</code> 4 and 11). A port of
 * homecontrol <code>core/src/blocks/block_set.cpp</code>: the first problem refuses the whole member
 * with the device's path (<code>blocks[0].inputs.temperature[1]</code>) and reason.
 */
public final class BlockSet {

	/** The refusal reasons of <code>spec/blocks.md</code> 11. */
	public enum BlockError {
		/** Wrong JSON type, an unknown member, a malformed id, label or version. */
		MALFORMED("malformed"),
		/** Two blocks with the same id. */
		DUPLICATE_ID("duplicateId"),
		/** A type the firmware does not have. */
		UNKNOWN_TYPE("unknownType"),
		/** A port, parameter or datapoint that does not exist. */
		UNKNOWN_REFERENCE("unknownReference"),
		/** A required input is not bound. */
		MISSING_INPUT("missingInput"),
		/** A port's type does not accept the datapoint's, or a parameter value of the wrong type. */
		TYPE_ERROR("typeError"),
		/** A parameter value outside its bounds or not a choice, a cross-parameter check. */
		INVALID_PARAMETER("invalidParameter"),
		/** An output bound to a datapoint the application may not command. */
		NOT_WRITABLE("notWritable"),
		/** An output bound to a datapoint another component owns. */
		OUTPUT_CONFLICT("outputConflict"),
		/** A forbidden binding. */
		FORBIDDEN("forbidden"),
		/** More than 32 blocks, colliding persistence keys. */
		LIMIT_EXCEEDED("limitExceeded");

		private final String _name;

		BlockError(String name) {
			_name = name;
		}

		/** The reason code of the specification. */
		public String externalName() {
			return _name;
		}
	}

	/**
	 * A refusal of the <code>blocks</code> member.
	 *
	 * @param error
	 *        The reason.
	 * @param block
	 *        The id of the block, empty when unknown.
	 * @param path
	 *        The path in the image (<code>blocks[2].inputs.temperature</code>).
	 * @param text
	 *        The explanation, as the controller gives it (English).
	 * @param message
	 *        The same as a whole sentence for the user, in the languages of the application.
	 * @param strict
	 *        An unknown member: the image loader adds the hint that the firmware may be older than the
	 *        image (<code>spec/runtime-image.md</code> I4).
	 */
	public record Refusal(BlockError error, String block, String path, String text, ResKey message, boolean strict) {

		@Override
		public String toString() {
			return error.externalName() + " at " + path + ": " + text;
		}
	}

	/**
	 * Which datapoints another component owns (<code>spec/blocks.md</code> B14).
	 */
	public interface Owners {

		/**
		 * When no block output may bind the datapoint because another component owns it: the owner for
		 * the refusal (<code>a schedule entry</code>); <code>null</code> otherwise.
		 */
		String owner(String datapoint);
	}

	/**
	 * The binding of one port of an instance.
	 *
	 * @param datapoints
	 *        The datapoint names; an output has at most one; empty: unbound.
	 * @param aggregation
	 *        How an input bound to more than one datapoint combines them.
	 */
	public record PortBinding(List<String> datapoints, Aggregation aggregation) {

		/** Whether the port is bound. */
		public boolean bound() {
			return !datapoints.isEmpty();
		}
	}

	/**
	 * One compiled instance (<code>spec/blocks.md</code> 4).
	 *
	 * @param id
	 *        The stable id.
	 * @param type
	 *        The type.
	 * @param minor
	 *        The image's minor version.
	 * @param label
	 *        The label as the image gives it.
	 * @param enabled
	 *        The default of the enable switch.
	 * @param enabledAdjustable
	 *        Whether the inhabitant may switch it.
	 * @param ports
	 *        One binding per manifest port.
	 * @param defaults
	 *        One value per manifest parameter: the image's value or the manifest's default.
	 */
	public record BlockSpec(String id, BlockType type, int minor, Object label, boolean enabled,
			boolean enabledAdjustable, List<PortBinding> ports, double[] defaults) {

		/** The manifest of the type. */
		public Manifest manifest() {
			return type.manifest();
		}
	}

	/** The members of an instance (<code>spec/blocks.md</code> 4). */
	public static final Set<String> MEMBERS = Set.of("id", "type", "version", "label", "enabled", "enabledAdjustable",
		"inputs", "aggregation", "outputs", "parameters", "description", "comment");

	private final List<BlockSpec> _specs;

	private BlockSet(List<BlockSpec> specs) {
		_specs = specs;
	}

	/** No blocks. */
	public static BlockSet empty() {
		return new BlockSet(List.of());
	}

	/** The instances in block order. */
	public List<BlockSpec> specs() {
		return _specs;
	}

	/** The number of instances. */
	public int size() {
		return _specs.size();
	}

	/** The instance at the index. */
	public BlockSpec at(int index) {
		return _specs.get(index);
	}

	/** The index of the block, -1 if none. */
	public int find(String id) {
		for (int i = 0; i < _specs.size(); i++) {
			if (_specs.get(i).id().equals(id)) {
				return i;
			}
		}
		return -1;
	}

	/**
	 * Thrown by {@link BlockSet#load(Object, DatapointDirectory, Owners, BlockRegistry)}.
	 */
	public static final class LoadException extends Exception {

		private final Refusal _refusal;

		LoadException(Refusal refusal) {
			super(refusal.toString());
			_refusal = refusal;
		}

		/** The refusal. */
		public Refusal getRefusal() {
			return _refusal;
		}
	}

	/**
	 * Compiles <code>blocks</code> (an array, <code>null</code> for none) against the datapoints.
	 *
	 * @throws LoadException
	 *         The first problem.
	 */
	public static BlockSet load(Object blocks, DatapointDirectory datapoints, Owners owners, BlockRegistry registry)
			throws LoadException {
		if (blocks == null) {
			return empty();
		}
		Compiler c = new Compiler(datapoints, owners, registry);
		c.at("blocks");
		if (!(blocks instanceof List<?> list)) {
			throw c.refuse(BlockError.MALFORMED, "'blocks' must be an array", I18NConstants.BLOCK_NOT_AN_ARRAY);
		}
		if (list.size() > Manifest.MAX_BLOCKS) {
			throw c.refuse(BlockError.LIMIT_EXCEEDED,
				list.size() + " blocks exceed the limit of " + Manifest.MAX_BLOCKS,
					I18NConstants.BLOCK_TOO_MANY__COUNT_LIMIT.fill(String.valueOf(list.size()), String.valueOf(Manifest.MAX_BLOCKS)));
		}
		List<BlockSpec> specs = new ArrayList<>();
		for (int i = 0; i < list.size(); i++) {
			specs.add(c.compile(list.get(i), i, specs));
		}
		// The persistence keys of the device must not collide: the record blobs and the override keys
		// of the store of the rule parameters.
		for (int i = 0; i < specs.size(); i++) {
			for (int k = 0; k < i; k++) {
				if (fnv1a(specs.get(i).id()) == fnv1a(specs.get(k).id())) {
					c.at("blocks[" + i + "].id");
					throw c.refuse(BlockError.LIMIT_EXCEEDED, "the state records of '" + specs.get(i).id() + "' and '"
						+ specs.get(k).id() + "' collide in the device's storage",
							I18NConstants.BLOCK_RECORDS_COLLIDE__FIRST_SECOND.fill(specs.get(i).id(), specs.get(k).id()));
				}
			}
		}
		List<Long> hashes = new ArrayList<>();
		for (BlockSpec spec : specs) {
			Manifest m = spec.manifest();
			String key = storeKey(spec.id());
			for (int p = 0; p <= m.params().size(); p++) {
				hashes.add(Long.valueOf(ParameterStore.hash(key, p == m.params().size() ? "enabled" : m.params().get(p).id())));
			}
		}
		Collections.sort(hashes);
		for (int i = 1; i < hashes.size(); i++) {
			if (hashes.get(i).equals(hashes.get(i - 1))) {
				c.at("blocks");
				throw c.refuse(BlockError.LIMIT_EXCEEDED,
					"two stored parameter overrides of the blocks collide in the device's storage",
						I18NConstants.BLOCK_OVERRIDES_COLLIDE);
			}
		}
		return new BlockSet(Collections.unmodifiableList(specs));
	}

	/** The 32-bit FNV-1a hash of the text's UTF-8 bytes (homecontrol <code>persist::fnv1a</code>). */
	static int fnv1a(String text) {
		int hash = 0x811c9dc5;
		for (byte b : text.getBytes(StandardCharsets.UTF_8)) {
			hash ^= b & 0xFF;
			hash *= 0x01000193;
		}
		return hash;
	}

	/** The store's logical "rule" part of a block's overrides: <code>@&lt;blockId&gt;</code>. */
	public static String storeKey(String block) {
		return "@" + block;
	}

	/** <code>block/&lt;block&gt;/&lt;member&gt;</code>. */
	public static String mirrorName(String block, String member) {
		return "block/" + block + "/" + member;
	}

	/** Whether the id is a block id: 1..32 characters of <code>A-Z a-z 0-9 _ -</code>. */
	public static boolean validId(String id) {
		if (id.isEmpty() || id.length() > Manifest.MAX_ID_LENGTH) {
			return false;
		}
		for (int i = 0; i < id.length(); i++) {
			char c = id.charAt(i);
			if (!((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-')) {
				return false;
			}
		}
		return true;
	}

	/** <code>"1.0"</code> as <code>{1, 0}</code>; <code>null</code> when malformed. */
	public static int[] parseVersion(String text) {
		int dot = text.indexOf('.');
		if (dot <= 0 || dot == text.length() - 1) {
			return null;
		}
		int major = number(text.substring(0, dot));
		int minor = number(text.substring(dot + 1));
		return major < 0 || minor < 0 ? null : new int[] { major, minor };
	}

	private static int number(String text) {
		if (text.isEmpty() || text.length() > 4) {
			return -1;
		}
		int value = 0;
		for (char c : text.toCharArray()) {
			if (c < '0' || c > '9') {
				return -1;
			}
			value = value * 10 + (c - '0');
		}
		return value;
	}

	private static boolean printable(String text) {
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if (c < 0x20 || c == 0x7F) {
				return false;
			}
		}
		return true;
	}

	private static boolean labelText(Object value) {
		if (!(value instanceof String text)) {
			return false;
		}
		int length = text.getBytes(StandardCharsets.UTF_8).length;
		return length >= 1 && length <= 63 && printable(text);
	}

	/** A label of <code>spec/rules.md</code> 2.2. */
	static boolean labelValid(Object label) {
		if (label instanceof String) {
			return labelText(label);
		}
		if (!(label instanceof Map<?, ?> map) || map.isEmpty() || map.size() > 16) {
			return false;
		}
		for (Map.Entry<?, ?> entry : map.entrySet()) {
			String tag = (String) entry.getKey();
			if (tag.length() < 2 || tag.length() > 8) {
				return false;
			}
			for (char c : tag.toCharArray()) {
				if (!((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-')) {
					return false;
				}
			}
			if (!labelText(entry.getValue())) {
				return false;
			}
		}
		return true;
	}

	/**
	 * The value of a parameter in its image form (<code>spec/blocks.md</code> 4): a number, a
	 * boolean, a choice's id or a time of day object.
	 *
	 * @param reason
	 *        Receives why it is not of the parameter's type (its first element).
	 * @return The value, <code>null</code> when it is not of the parameter's type.
	 */
	public static Double paramFromJson(Param decl, Object json, ParamProblem[] reason) {
		switch (decl.type()) {
			case BOOLEAN:
				if (!(json instanceof Boolean b)) {
					reason[0] = ParamProblem.NOT_BOOLEAN;
					return null;
				}
				return Double.valueOf(b.booleanValue() ? 1 : 0);
			case TIME_OF_DAY: {
				if (json instanceof Map<?, ?> map && map.get("hour") instanceof Number h
					&& map.get("minute") instanceof Number mi && map.get("second") instanceof Number se) {
					double hour = h.doubleValue();
					double minute = mi.doubleValue();
					double second = se.doubleValue();
					if (hour == Math.floor(hour) && minute == Math.floor(minute) && second == Math.floor(second)
						&& hour >= 0 && hour <= 23 && minute >= 0 && minute <= 59 && second >= 0 && second <= 59) {
						return Double.valueOf(hour * 3600 + minute * 60 + second);
					}
				}
				reason[0] = ParamProblem.NOT_TIME_OBJECT;
				return null;
			}
			default:
				break;
		}
		if (json instanceof String id && !decl.choices().isEmpty()) {
			Choice choice = decl.choice(id);
			if (choice != null) {
				return Double.valueOf(choice.value());
			}
			reason[0] = ParamProblem.NOT_A_CHOICE;
			return null;
		}
		if (!(json instanceof Number number) || !Double.isFinite(number.doubleValue())) {
			reason[0] = !decl.choices().isEmpty() ? ParamProblem.NOT_NUMBER_OR_CHOICE : ParamProblem.NOT_A_NUMBER;
			return null;
		}
		return Double.valueOf(number.doubleValue());
	}

	/**
	 * Why the value of a parameter is refused: the reason as the controller gives it and the
	 * message for the user about the parameter.
	 */
	public enum ParamProblem {
		/** Not a boolean. */
		NOT_BOOLEAN("not true or false", I18NConstants.BLOCK_PARAM_NOT_BOOLEAN__PARAMETER),

		/** Not a time of day object. */
		NOT_TIME_OBJECT("not a time of day {\"hour\", \"minute\", \"second\"}",
				I18NConstants.BLOCK_PARAM_NOT_TIME_OBJECT__PARAMETER),

		/** Not one of the choices. */
		NOT_A_CHOICE("not one of the choices", I18NConstants.BLOCK_PARAM_NOT_A_CHOICE__PARAMETER),

		/** Neither a number nor the id of a choice. */
		NOT_NUMBER_OR_CHOICE("not a number or a choice's id", I18NConstants.BLOCK_PARAM_NOT_NUMBER_OR_CHOICE__PARAMETER),

		/** Not a number. */
		NOT_A_NUMBER("not a number", I18NConstants.BLOCK_PARAM_NOT_A_NUMBER__PARAMETER),

		/** Not finite. */
		NOT_FINITE("not a finite number", I18NConstants.BLOCK_PARAM_NOT_FINITE__PARAMETER),

		/** A fraction where the type takes whole numbers. */
		NOT_WHOLE("not a whole number", I18NConstants.BLOCK_PARAM_NOT_WHOLE__PARAMETER),

		/** A duration or enumeration outside 0..2^32-1. */
		OUTSIDE_UNSIGNED("outside 0..4294967295", I18NConstants.BLOCK_PARAM_OUTSIDE_UNSIGNED__PARAMETER),

		/** An integer outside the signed 32-bit range. */
		OUTSIDE_SIGNED("outside the signed 32-bit range", I18NConstants.BLOCK_PARAM_OUTSIDE_SIGNED__PARAMETER),

		/** A time of day outside the day. */
		NOT_TIME("not a time of day", I18NConstants.BLOCK_PARAM_NOT_TIME__PARAMETER),

		/** Outside the bounds of the parameter. */
		OUTSIDE_MIN_MAX("outside min..max", I18NConstants.BLOCK_PARAM_OUTSIDE_MIN_MAX__PARAMETER);

		private final String _text;

		private final ResKey1 _message;

		ParamProblem(String text, ResKey1 message) {
			_text = text;
			_message = message;
		}

		/** The reason as the controller gives it. */
		public String text() {
			return _text;
		}

		/** The same for the user, about the given parameter. */
		public ResKey message(String parameter) {
			return _message.fill(parameter);
		}
	}

	/**
	 * Why the value is not of the parameter's type (finite; integral for every type but real; 0 or
	 * 1 for a boolean; within the range of the type), <code>null</code> when it is.
	 */
	public static String paramTypeProblem(Param decl, double v) {
		return text(typeProblemOf(decl, v));
	}

	/**
	 * Why the value is not of the parameter's type, <code>null</code> when it is.
	 *
	 * @see #paramTypeProblem(Param, double)
	 */
	public static ParamProblem typeProblemOf(Param decl, double v) {
		if (!Double.isFinite(v)) {
			return ParamProblem.NOT_FINITE;
		}
		ParamType type = decl.type();
		if (type != ParamType.REAL && v != Math.floor(v)) {
			return ParamProblem.NOT_WHOLE;
		}
		if (type == ParamType.BOOLEAN && v != 0 && v != 1) {
			return ParamProblem.NOT_BOOLEAN;
		}
		if ((type == ParamType.DURATION || type == ParamType.ENUMERATION) && (v < 0 || v > 4294967295.0)) {
			return ParamProblem.OUTSIDE_UNSIGNED;
		}
		if (type == ParamType.INTEGER && (v < -2147483648.0 || v > 2147483647.0)) {
			return ParamProblem.OUTSIDE_SIGNED;
		}
		if (type == ParamType.TIME_OF_DAY && (v < 0 || v > 86399)) {
			return ParamProblem.NOT_TIME;
		}
		return null;
	}

	/**
	 * Why the value is not valid for the parameter (of its type, within its bounds, one of its
	 * choices), <code>null</code> when it is.
	 */
	public static String paramProblem(Param decl, double v) {
		return text(problemOf(decl, v));
	}

	/**
	 * Why the value is not valid for the parameter, <code>null</code> when it is.
	 *
	 * @see #paramProblem(Param, double)
	 */
	public static ParamProblem problemOf(Param decl, double v) {
		ParamProblem problem = typeProblemOf(decl, v);
		if (problem != null) {
			return problem;
		}
		if ((decl.min() != null && v < decl.min().doubleValue()) || (decl.max() != null && v > decl.max().doubleValue())) {
			return ParamProblem.OUTSIDE_MIN_MAX;
		}
		if (!decl.choices().isEmpty() && decl.choice(v) == null) {
			return ParamProblem.NOT_A_CHOICE;
		}
		return null;
	}

	private static String text(ParamProblem problem) {
		return problem == null ? null : problem.text();
	}

	/**
	 * A datapoint the image implies for a block (<code>spec/blocks.md</code> 6).
	 *
	 * @param name
	 *        <code>block/&lt;id&gt;/&lt;x&gt;</code>.
	 * @param type
	 *        Its value type.
	 * @param writable
	 *        Whether it may be commanded (and transmits).
	 * @param block
	 *        The block's id.
	 * @param member
	 *        <code>enabled</code>, a parameter or a port.
	 */
	public record ImpliedDatapoint(String name, ValueType type, boolean writable, String block, String member) {
		// A value.
	}

	/**
	 * The implied datapoints of <code>blocks</code> in block order (<code>enabled</code>, the
	 * adjustable parameters, the outputs, the aggregated inputs). Lenient: an instance that is not
	 * well-formed is skipped, since {@link #load(Object, DatapointDirectory, Owners, BlockRegistry)}
	 * refuses it with the precise reason.
	 */
	public static List<ImpliedDatapoint> impliedDatapoints(Object blocks, BlockRegistry registry) {
		List<ImpliedDatapoint> result = new ArrayList<>();
		if (!(blocks instanceof List<?> list)) {
			return result;
		}
		for (Object item : list) {
			if (!(item instanceof Map<?, ?> block) || !(block.get("id") instanceof String id) || !validId(id)
				|| !(block.get("type") instanceof String type) || !(block.get("version") instanceof String version)) {
				continue;
			}
			int[] v = parseVersion(version);
			BlockType t = v == null ? null : registry.find(type, v[0]);
			if (t == null) {
				continue;
			}
			Manifest m = t.manifest();
			boolean adjustable = !(block.get("enabledAdjustable") instanceof Boolean b) || b.booleanValue();
			result.add(new ImpliedDatapoint(mirrorName(id, "enabled"), ValueType.BOOLEAN, adjustable, id, "enabled"));
			for (Param p : m.params()) {
				if (p.adjustable()) {
					result.add(new ImpliedDatapoint(mirrorName(id, p.id()), p.type().datapointType(), true, id, p.id()));
				}
			}
			for (Port p : m.ports()) {
				if (!p.isInput()) {
					result.add(new ImpliedDatapoint(mirrorName(id, p.id()), p.type().datapointType(), false, id, p.id()));
				}
			}
			if (block.get("inputs") instanceof Map<?, ?> inputs) {
				for (Port p : m.ports()) {
					if (p.isInput() && inputs.get(p.id()) instanceof List<?> bound && bound.size() > 1) {
						result.add(new ImpliedDatapoint(mirrorName(id, p.id()), p.type().datapointType(), false, id, p.id()));
					}
				}
			}
		}
		return result;
	}

	private static final class Compiler {

		private final DatapointDirectory _datapoints;

		private final Owners _owners;

		private final BlockRegistry _registry;

		private String _path = "";

		private String _block = "";

		Compiler(DatapointDirectory datapoints, Owners owners, BlockRegistry registry) {
			_datapoints = datapoints;
			_owners = owners;
			_registry = registry;
		}

		void at(String path) {
			_path = path;
		}

		LoadException refuse(BlockError error, String text, ResKey message) {
			return new LoadException(new Refusal(error, _block, _path, text, message, false));
		}

		LoadException strict(String text, ResKey message) {
			return new LoadException(new Refusal(BlockError.MALFORMED, _block, _path, text, message, true));
		}

		/** A datapoint name, checked. */
		String datapoint(Object json) throws LoadException {
			if (!(json instanceof String name) || name.isEmpty()
				|| name.getBytes(StandardCharsets.UTF_8).length > 63) {
				throw refuse(BlockError.MALFORMED, "a datapoint name must be a string of 1..63 bytes",
					I18NConstants.BLOCK_DATAPOINT_NAME);
			}
			if (_datapoints.type(name) == null) {
				throw refuse(BlockError.UNKNOWN_REFERENCE, "no datapoint '" + name + "'",
					I18NConstants.BLOCK_NO_DATAPOINT__NAME.fill(DatapointId.of(name)));
			}
			return name;
		}

		BlockSpec compile(Object item, int index, List<BlockSpec> earlier) throws LoadException {
			String base = "blocks[" + index + "]";
			_block = "";
			at(base);
			if (!(item instanceof Map<?, ?> block)) {
				throw refuse(BlockError.MALFORMED, "a block must be an object", I18NConstants.BLOCK_NOT_AN_OBJECT);
			}
			for (Object key : block.keySet()) {
				if (!MEMBERS.contains(key)) {
					throw strict("unknown member '" + key + "'",
						I18NConstants.BLOCK_UNKNOWN_MEMBER__NAME.fill(String.valueOf(key)));
				}
			}
			at(base + ".id");
			if (!block.containsKey("id")) {
				at(base);
				throw refuse(BlockError.MALFORMED, "the member 'id' is missing",
					I18NConstants.BLOCK_MEMBER_MISSING__NAME.fill("id"));
			}
			if (!(block.get("id") instanceof String id) || !validId(id)) {
				throw refuse(BlockError.MALFORMED, "a block id has 1..32 characters of A-Z a-z 0-9 _ -",
					I18NConstants.BLOCK_ID_PATTERN);
			}
			_block = id;
			for (int k = 0; k < earlier.size(); k++) {
				if (earlier.get(k).id().equals(id)) {
					throw refuse(BlockError.DUPLICATE_ID, "blocks[" + k + "] has the id '" + id + "' too",
						I18NConstants.BLOCK_ID_TWICE__INDEX_ID.fill(String.valueOf(k), id));
				}
			}
			at(base + ".type");
			if (!(block.get("type") instanceof String type)) {
				throw refuse(BlockError.MALFORMED, "the member 'type' (a type id) is missing or not a string",
					I18NConstants.BLOCK_TYPE_MISSING);
			}
			at(base + ".version");
			int[] version = block.get("version") instanceof String text ? parseVersion(text) : null;
			if (version == null) {
				throw refuse(BlockError.MALFORMED, "the member 'version' must be \"<major>.<minor>\"",
					I18NConstants.BLOCK_VERSION_MALFORMED);
			}
			String versionText = (String) block.get("version");
			at(base + ".type");
			BlockType blockType = _registry.find(type, version[0]);
			if (blockType == null || blockType.manifest().minor() < version[1]) {
				BlockType any = blockType != null ? blockType : _registry.findAny(type);
				if (any == null) {
					throw refuse(BlockError.UNKNOWN_TYPE, type + " " + versionText + " (this firmware has no such block type)",
						I18NConstants.BLOCK_UNKNOWN_TYPE__TYPE_VERSION.fill(type, versionText));
				}
				throw refuse(BlockError.UNKNOWN_TYPE,
					type + " " + versionText + " (this firmware has " + any.manifest().version() + ")",
						I18NConstants.BLOCK_TYPE_VERSION__TYPE_VERSION_HAS.fill(type, versionText, any.manifest().version()));
			}
			at(base + ".label");
			if (!block.containsKey("label")) {
				at(base);
				throw refuse(BlockError.MALFORMED, "the member 'label' is missing",
					I18NConstants.BLOCK_MEMBER_MISSING__NAME.fill("label"));
			}
			Object label = block.get("label");
			if (!labelValid(label)) {
				throw refuse(BlockError.MALFORMED,
					"the label must be a string of 1..63 bytes or an object of language tags to such strings",
						I18NConstants.BLOCK_LABEL_MALFORMED);
			}
			boolean[] flags = { true, true };
			String[] flagNames = { "enabled", "enabledAdjustable" };
			for (int f = 0; f < 2; f++) {
				if (block.containsKey(flagNames[f])) {
					at(base + "." + flagNames[f]);
					if (!(block.get(flagNames[f]) instanceof Boolean b)) {
						throw refuse(BlockError.MALFORMED, "'" + flagNames[f] + "' must be true or false",
							I18NConstants.BLOCK_NOT_A_FLAG__NAME.fill(flagNames[f]));
					}
					flags[f] = b.booleanValue();
				}
			}
			Manifest m = blockType.manifest();
			List<List<String>> bindings = new ArrayList<>();
			Aggregation[] aggregations = new Aggregation[m.ports().size()];
			for (int p = 0; p < m.ports().size(); p++) {
				bindings.add(new ArrayList<>());
				aggregations[p] = m.ports().get(p).defaultAggregation();
			}
			double[] defaults = new double[m.params().size()];
			for (int p = 0; p < defaults.length; p++) {
				defaults[p] = m.params().get(p).defaultValue();
			}
			inputs(block, base, id, m, bindings, aggregations);
			outputs(block, base, id, m, bindings, earlier);
			parameters(block, base, blockType, defaults);
			List<PortBinding> ports = new ArrayList<>();
			for (int p = 0; p < m.ports().size(); p++) {
				ports.add(new PortBinding(List.copyOf(bindings.get(p)), aggregations[p]));
			}
			return new BlockSpec(id, blockType, version[1], label, flags[0], flags[1], Collections.unmodifiableList(ports),
				defaults);
		}

		private void inputs(Map<?, ?> block, String base, String id, Manifest m, List<List<String>> bindings,
				Aggregation[] aggregations) throws LoadException {
			if (block.containsKey("inputs")) {
				at(base + ".inputs");
				if (!(block.get("inputs") instanceof Map<?, ?> list)) {
					throw refuse(BlockError.MALFORMED, "'inputs' must be an object of port ids to datapoint names",
						I18NConstants.BLOCK_INPUTS_MALFORMED);
				}
				for (Map.Entry<?, ?> entry : list.entrySet()) {
					String portId = (String) entry.getKey();
					if (portId.getBytes(StandardCharsets.UTF_8).length > 31) {
						throw refuse(BlockError.MALFORMED, "a port id is too long",
							I18NConstants.BLOCK_PORT_ID_TOO_LONG);
					}
					at(base + ".inputs." + portId);
					int p = m.port(portId);
					if (p < 0) {
						throw refuse(BlockError.UNKNOWN_REFERENCE, m.type() + " has no port '" + portId + "'",
							I18NConstants.BLOCK_NO_PORT__TYPE_PORT.fill(m.type(), portId));
					}
					Port decl = m.ports().get(p);
					if (!decl.isInput()) {
						throw refuse(BlockError.FORBIDDEN,
							"'" + portId + "' is an output of " + m.type() + ": bind it under 'outputs'",
								I18NConstants.BLOCK_OUTPUT_UNDER_INPUTS__PORT_TYPE.fill(portId, m.type()));
					}
					Object value = entry.getValue();
					List<?> names;
					boolean array = value instanceof List<?>;
					if (array) {
						names = (List<?>) value;
						if (names.size() > decl.multiple()) {
							throw refuse(BlockError.FORBIDDEN, "the port '" + portId + "' takes at most " + decl.multiple()
								+ " datapoint" + (decl.multiple() == 1 ? "" : "s"),
								decl.multiple() == 1 ? I18NConstants.BLOCK_PORT_ONE__PORT.fill(portId)
									: I18NConstants.BLOCK_PORT_TOO_MANY__PORT_MAX.fill(portId, String.valueOf(decl.multiple())));
						}
						if (names.isEmpty()) {
							throw refuse(BlockError.MALFORMED, "an input binds 1.." + decl.multiple() + " datapoints",
								I18NConstants.BLOCK_INPUT_EMPTY__MAX.fill(String.valueOf(decl.multiple())));
						}
					} else {
						names = Collections.singletonList(value);
					}
					List<String> bound = bindings.get(p);
					for (int k = 0; k < names.size(); k++) {
						if (array) {
							at(base + ".inputs." + portId + "[" + k + "]");
						}
						String name = datapoint(names.get(k));
						if (name.startsWith("block/" + id + "/")) {
							throw refuse(BlockError.FORBIDDEN,
								"'" + name + "' is a datapoint of this block: it cannot be its input",
									I18NConstants.BLOCK_OWN_DATAPOINT_INPUT__NAME.fill(DatapointId.of(name)));
						}
						ValueType type = _datapoints.type(name);
						if (!decl.type().reads(type)) {
							throw refuse(BlockError.TYPE_ERROR, "the " + decl.type().externalName() + " port '" + portId
								+ "' cannot read the " + type.externalName() + " datapoint '" + name + "'",
									I18NConstants.BLOCK_INPUT_TYPE__PORTTYPE_PORT_TYPE_NAME.fill(decl.type().externalName(), portId,
										type.externalName(), DatapointId.of(name)));
						}
						if (bound.contains(name)) {
							throw refuse(BlockError.MALFORMED, "'" + name + "' is listed twice",
								I18NConstants.BLOCK_LISTED_TWICE__NAME.fill(name));
						}
						bound.add(name);
					}
				}
			}
			for (int p = 0; p < m.ports().size(); p++) {
				Port decl = m.ports().get(p);
				if (decl.isInput() && decl.required() && bindings.get(p).isEmpty()) {
					at(base + ".inputs." + decl.id());
					throw refuse(BlockError.MISSING_INPUT,
						"the required input '" + decl.id() + "' of " + m.type() + " is not bound",
							I18NConstants.BLOCK_REQUIRED_INPUT__PORT_TYPE.fill(decl.id(), m.type()));
				}
			}
			if (block.containsKey("aggregation")) {
				at(base + ".aggregation");
				if (!(block.get("aggregation") instanceof Map<?, ?> list)) {
					throw refuse(BlockError.MALFORMED, "'aggregation' must be an object of port ids to aggregations",
						I18NConstants.BLOCK_AGGREGATION_MALFORMED);
				}
				for (Map.Entry<?, ?> entry : list.entrySet()) {
					String portId = (String) entry.getKey();
					if (portId.getBytes(StandardCharsets.UTF_8).length > 31) {
						throw refuse(BlockError.MALFORMED, "a port id is too long",
							I18NConstants.BLOCK_PORT_ID_TOO_LONG);
					}
					at(base + ".aggregation." + portId);
					int p = m.port(portId);
					if (p < 0 || !m.ports().get(p).isInput()) {
						throw refuse(BlockError.UNKNOWN_REFERENCE, m.type() + " has no input '" + portId + "'",
							I18NConstants.BLOCK_NO_INPUT__TYPE_PORT.fill(m.type(), portId));
					}
					Aggregation a = entry.getValue() instanceof String name ? Aggregation.of(name) : Aggregation.NONE;
					if (a == Aggregation.NONE) {
						throw refuse(BlockError.MALFORMED, "an aggregation is one of mean, min, max, any, all",
							I18NConstants.BLOCK_UNKNOWN_AGGREGATION);
					}
					if (bindings.get(p).size() < 2) {
						throw refuse(BlockError.FORBIDDEN,
							"the input '" + portId + "' does not bind several datapoints: nothing to aggregate",
								I18NConstants.BLOCK_NOTHING_TO_AGGREGATE__PORT.fill(portId));
					}
					if (!m.ports().get(p).aggregations().contains(a)) {
						throw refuse(BlockError.FORBIDDEN,
							"the input '" + portId + "' does not allow the aggregation '" + a.externalName() + "'",
								I18NConstants.BLOCK_AGGREGATION_NOT_ALLOWED__PORT_AGGREGATION.fill(portId, a.externalName()));
					}
					aggregations[p] = a;
				}
			}
		}

		private void outputs(Map<?, ?> block, String base, String id, Manifest m, List<List<String>> bindings,
				List<BlockSpec> earlier) throws LoadException {
			if (!block.containsKey("outputs")) {
				return;
			}
			at(base + ".outputs");
			if (!(block.get("outputs") instanceof Map<?, ?> list)) {
				throw refuse(BlockError.MALFORMED, "'outputs' must be an object of port ids to datapoint names",
					I18NConstants.BLOCK_OUTPUTS_MALFORMED);
			}
			for (Map.Entry<?, ?> entry : list.entrySet()) {
				String portId = (String) entry.getKey();
				if (portId.getBytes(StandardCharsets.UTF_8).length > 31) {
					throw refuse(BlockError.MALFORMED, "a port id is too long", I18NConstants.BLOCK_PORT_ID_TOO_LONG);
				}
				at(base + ".outputs." + portId);
				int p = m.port(portId);
				if (p < 0) {
					throw refuse(BlockError.UNKNOWN_REFERENCE, m.type() + " has no port '" + portId + "'",
						I18NConstants.BLOCK_NO_PORT__TYPE_PORT.fill(m.type(), portId));
				}
				Port decl = m.ports().get(p);
				if (decl.isInput()) {
					throw refuse(BlockError.FORBIDDEN, "'" + portId + "' is an input of " + m.type() + ": bind it under 'inputs'",
						I18NConstants.BLOCK_INPUT_UNDER_OUTPUTS__PORT_TYPE.fill(portId, m.type()));
				}
				String name = datapoint(entry.getValue());
				String owner = _owners.owner(name);
				if (owner != null) {
					throw refuse(BlockError.OUTPUT_CONFLICT, "'" + name + "' is owned by " + owner,
						I18NConstants.BLOCK_OUTPUT_OWNED__NAME_OWNER.fill(DatapointId.of(name), OwnerLabel.of(owner)));
				}
				if (!_datapoints.writable(name)) {
					throw refuse(BlockError.NOT_WRITABLE, "'" + name + "' is not writable",
						I18NConstants.BLOCK_NOT_WRITABLE__NAME.fill(DatapointId.of(name)));
				}
				ValueType type = _datapoints.type(name);
				if (!decl.type().commands(type)) {
					throw refuse(BlockError.TYPE_ERROR, "the " + decl.type().externalName() + " port '" + portId
						+ "' cannot command the " + type.externalName() + " datapoint '" + name + "'",
							I18NConstants.BLOCK_OUTPUT_TYPE__PORTTYPE_PORT_TYPE_NAME.fill(decl.type().externalName(), portId,
								type.externalName(), DatapointId.of(name)));
				}
				for (int q = 0; q < m.ports().size(); q++) {
					// spec/blocks.md B22: a block does not read its own output.
					if (m.ports().get(q).isInput() && bindings.get(q).contains(name)) {
						throw refuse(BlockError.FORBIDDEN, "'" + name + "' is bound to the input '" + m.ports().get(q).id()
							+ "': a block does not read its own output",
								I18NConstants.BLOCK_READS_OWN_OUTPUT__NAME_PORT.fill(DatapointId.of(name), m.ports().get(q).id()));
					}
				}
				for (int q = 0; q < m.ports().size(); q++) {
					if (!m.ports().get(q).isInput() && bindings.get(q).contains(name)) {
						throw refuse(BlockError.OUTPUT_CONFLICT,
							"'" + name + "' is bound to the output '" + m.ports().get(q).id() + "' already",
								I18NConstants.BLOCK_OUTPUT_BOUND_TWICE__NAME_PORT.fill(DatapointId.of(name), m.ports().get(q).id()));
					}
				}
				for (BlockSpec other : earlier) {
					List<Port> ports = other.manifest().ports();
					for (int q = 0; q < ports.size(); q++) {
						if (!ports.get(q).isInput() && other.ports().get(q).datapoints().contains(name)) {
							throw refuse(BlockError.OUTPUT_CONFLICT, "'" + name + "' is the output '" + ports.get(q).id()
								+ "' of the block '" + other.id() + "'",
									I18NConstants.BLOCK_OUTPUT_OF_OTHER__NAME_PORT_BLOCK.fill(DatapointId.of(name), ports.get(q).id(), other.id()));
						}
					}
				}
				bindings.get(p).add(name);
			}
		}

		private void parameters(Map<?, ?> block, String base, BlockType type, double[] defaults) throws LoadException {
			Manifest m = type.manifest();
			if (block.containsKey("parameters")) {
				at(base + ".parameters");
				if (!(block.get("parameters") instanceof Map<?, ?> list)) {
					throw refuse(BlockError.MALFORMED, "'parameters' must be an object of parameter ids to values",
						I18NConstants.BLOCK_PARAMETERS_MALFORMED);
				}
				for (Map.Entry<?, ?> entry : list.entrySet()) {
					String paramId = (String) entry.getKey();
					if (paramId.getBytes(StandardCharsets.UTF_8).length > 31) {
						throw refuse(BlockError.MALFORMED, "a parameter id is too long",
							I18NConstants.BLOCK_PARAMETER_ID_TOO_LONG);
					}
					at(base + ".parameters." + paramId);
					int p = m.param(paramId);
					if (p < 0) {
						throw refuse(BlockError.UNKNOWN_REFERENCE, m.type() + " has no parameter '" + paramId + "'",
							I18NConstants.BLOCK_NO_PARAMETER__TYPE_PARAMETER.fill(m.type(), paramId));
					}
					Param decl = m.params().get(p);
					ParamProblem[] reason = { null };
					Double v = paramFromJson(decl, entry.getValue(), reason);
					ParamProblem typeProblem = v == null ? reason[0] : typeProblemOf(decl, v.doubleValue());
					if (typeProblem != null) {
						throw refuse(BlockError.TYPE_ERROR, paramId + ": " + typeProblem.text(), typeProblem.message(paramId));
					}
					ParamProblem problem = problemOf(decl, v.doubleValue());
					if (problem != null) {
						throw refuse(BlockError.INVALID_PARAMETER, paramId + ": " + problem.text(), problem.message(paramId));
					}
					defaults[p] = v.doubleValue();
				}
			}
			BlockType.Blame blame = type.check(Arrays.copyOf(defaults, defaults.length));
			if (blame != null) {
				if (blame.parameter() >= 0 && blame.parameter() < m.params().size()) {
					at(base + ".parameters." + m.params().get(blame.parameter()).id());
				} else {
					at(base + ".parameters");
				}
				throw refuse(BlockError.INVALID_PARAMETER, blame.reason(), blame.message());
			}
		}
	}

}
