/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.zigbee;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.top_logic.basic.io.binary.BinaryData;
import com.top_logic.basic.io.binary.BinaryDataFactory;
import com.top_logic.basic.util.ResKey;
import com.top_logic.basic.util.ResourcesModule;
import com.top_logic.model.TLClass;
import com.top_logic.model.TLEnumeration;
import com.top_logic.model.TLObject;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.Resources;
import com.top_logic.util.error.TopLogicException;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.runtime.FunctionProposal;
import de.haumacher.ehome.runtime.Json;
import de.haumacher.ehome.runtime.Json.JsonException;

/**
 * Device types of the Zigbee catalog ({@link ZigbeeCatalog}) as JSON, for sharing them between
 * installations and for the device types E-Home ships (<code>WEB-INF/zigbee-catalog/*.json</code>,
 * {@link ZigbeeCatalogSeed}).
 *
 * <p>
 * The format (<code>doc/zigbee.md</code>, "Catalog"): an object with <code>format</code>
 * (<code>"ehome-zigbee-catalog"</code>), <code>version</code> (1) and <code>types</code>, an array of
 * device types, each with <code>manufacturer</code> and <code>model</code> as the device reports them,
 * <code>name</code> and <code>description</code> (an object of texts by language, or one text for
 * every language) and <code>functions</code>: per function <code>endpoint</code>, <code>function</code>
 * (the controller's key), <code>name</code>, <code>type</code> (the recommended datapoint type, the
 * runtime image's names), <code>unit</code>, <code>hidden</code> and <code>reporting</code> (<code>min</code>,
 * <code>max</code> in seconds, <code>change</code> in the datapoint's unit). Optional members are
 * left out when empty. A file holds one device type or many.
 * </p>
 *
 * <p>
 * The import matches a type of the file to one of the catalog by manufacturer and model: an unknown
 * type is created, a known one takes the file's content (unless it equals it already); the seed only
 * creates unknown types and leaves the known ones as the user keeps them. Everything the catalog's
 * constraints refuse is refused before anything is written, with the type, the function and the
 * reason.
 * </p>
 */
public final class ZigbeeCatalogJson {

	/** The value of <code>format</code>. */
	public static final String FORMAT = "ehome-zigbee-catalog";

	/** The version written and the newest read. */
	public static final int VERSION = 1;

	/** The content type of a catalog file. */
	public static final String CONTENT_TYPE = "application/json";

	private static final int MAX_NESTING = 16;

	/**
	 * How the import treats a type the catalog knows already.
	 */
	public enum Mode {
		/** It takes the file's content: the user imports a file. */
		UPDATE,
		/** It stays as it is: the device types E-Home ships are seeded. */
		KEEP,
	}

	/**
	 * The outcome of an import.
	 *
	 * @param types
	 *        The types of the catalog the file's types are, in the file's order.
	 * @param created
	 *        How many of them were new.
	 * @param updated
	 *        How many known ones took the file's content.
	 * @param unchanged
	 *        How many known ones stayed as they were.
	 */
	public record Result(List<TLObject> types, int created, int updated, int unchanged) {

		/**
		 * The sentence saying what was imported.
		 */
		public String summary() {
			return Resources.getInstance().getString(I18NConstants.CATALOG_IMPORTED__TYPES_CREATED_UPDATED_UNCHANGED.fill(
				Integer.valueOf(types.size()), Integer.valueOf(created), Integer.valueOf(updated),
				Integer.valueOf(unchanged)));
		}
	}

	/**
	 * A device type as the file describes it.
	 */
	record TypeData(String manufacturer, String model, Map<String, String> name, Map<String, String> description,
			List<EntryData> functions) {
		// A record.
	}

	/**
	 * A function entry as the file describes it.
	 */
	record EntryData(long endpoint, String function, Map<String, String> name, String type, String unit,
			boolean hidden, Long min, Long max, Double change) {
		// A record.
	}

	private ZigbeeCatalogJson() {
		// Static helpers.
	}

	/**
	 * The device types as a catalog file.
	 */
	public static String write(Collection<? extends TLObject> types) {
		Map<String, Object> root = new LinkedHashMap<>();
		root.put("format", FORMAT);
		root.put("version", Long.valueOf(VERSION));
		List<Object> list = new ArrayList<>();
		for (TLObject type : types) {
			list.add(json(read(type)));
		}
		root.put("types", list);
		return Json.write(root);
	}

	/**
	 * The device types as a catalog file to download.
	 */
	public static BinaryData export(Collection<? extends TLObject> types) {
		return BinaryDataFactory.createBinaryData(write(types).getBytes(StandardCharsets.UTF_8), CONTENT_TYPE,
			fileName(types));
	}

	/**
	 * The name of the download: the type's manufacturer and model for one type, else
	 * <code>zigbee-catalog.json</code>.
	 */
	public static String fileName(Collection<? extends TLObject> types) {
		if (types.size() == 1) {
			TLObject type = types.iterator().next();
			return FunctionProposal.slug(type.tValueByName("manufacturer") + " " + type.tValueByName("model"))
				+ ".json";
		}
		return "zigbee-catalog.json";
	}

	/**
	 * Imports the uploaded catalog file into the catalog; must run in a transaction.
	 *
	 * @return The sentence saying what was imported.
	 * @throws TopLogicException
	 *         If the file is no catalog file E-Home reads or holds a type the catalog refuses, with the
	 *         reason.
	 */
	public static String importFile(TLObject catalog, Object file) {
		if (!(file instanceof BinaryData data)) {
			throw new TopLogicException(I18NConstants.ERROR_EXPORT_NO_FILE);
		}
		String text;
		try (InputStream in = data.getStream()) {
			text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException ex) {
			throw new TopLogicException(I18NConstants.ERROR_EXPORT_UNREADABLE__REASON.fill(ex.getMessage()), ex);
		}
		return importText(catalog, text, Mode.UPDATE).summary();
	}

	/**
	 * Imports the catalog file's text into the catalog; must run in a transaction.
	 *
	 * @throws TopLogicException
	 *         If the text is no catalog file E-Home reads or holds a type the catalog refuses, with the
	 *         reason; nothing is written then.
	 */
	public static Result importText(TLObject catalog, String text, Mode mode) {
		Object root;
		try {
			root = Json.parse(text, MAX_NESTING);
		} catch (JsonException ex) {
			throw new TopLogicException(I18NConstants.ERROR_EXPORT_NO_JSON__REASON.fill(
				ex.getMessage() + " at character " + ex.getOffset()));
		}
		List<TypeData> types = parse(root);
		List<TLObject> result = new ArrayList<>();
		List<TLObject> all = new ArrayList<>(ZigbeeCatalog.types(catalog));
		int created = 0;
		int updated = 0;
		int unchanged = 0;
		for (TypeData data : types) {
			TLObject type = ZigbeeCatalog.type(catalog, data.manufacturer(), data.model());
			if (type == null) {
				type = create(ZigbeeCatalog.DEVICE_TYPE);
				update(type, data);
				all.add(type);
				created++;
			} else if (mode == Mode.KEEP || json(read(type)).equals(json(data))) {
				unchanged++;
			} else {
				update(type, data);
				updated++;
			}
			result.add(type);
		}
		if (created > 0) {
			catalog.tUpdateByName("types", all);
		}
		return new Result(result, created, updated, unchanged);
	}

	/**
	 * The device types of the catalog file's JSON objects.
	 *
	 * @throws TopLogicException
	 *         If the objects are no catalog file E-Home reads or hold a type the catalog refuses.
	 */
	static List<TypeData> parse(Object root) {
		if (!(root instanceof Map<?, ?> map) || !FORMAT.equals(map.get("format"))) {
			Object format = root instanceof Map<?, ?> m ? m.get("format") : null;
			throw new TopLogicException(I18NConstants.ERROR_CATALOG_FORMAT__FORMAT.fill(format == null ? "" : format));
		}
		if (!(map.get("version") instanceof Long version) || version.longValue() < 1) {
			throw invalid("version", I18NConstants.CATALOG_INVALID_VERSION);
		}
		if (version.longValue() > VERSION) {
			throw new TopLogicException(I18NConstants.ERROR_CATALOG_VERSION__VERSION_SUPPORTED.fill(version,
				Integer.valueOf(VERSION)));
		}
		if (!(map.get("types") instanceof List<?> list)) {
			throw invalid("types", I18NConstants.CATALOG_INVALID_TYPES);
		}
		List<TypeData> result = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		for (int i = 0; i < list.size(); i++) {
			TypeData type = type("types[" + i + "]", list.get(i));
			if (!seen.add(type.manufacturer() + "\n" + type.model())) {
				throw invalid("types[" + i + "]",
					I18NConstants.CATALOG_DUPLICATE_TYPE__MANUFACTURER_MODEL.fill(type.manufacturer(), type.model()));
			}
			result.add(type);
		}
		return result;
	}

	private static TypeData type(String path, Object item) {
		if (!(item instanceof Map<?, ?> map)) {
			throw invalid(path, I18NConstants.CATALOG_INVALID_OBJECT);
		}
		String manufacturer = requiredText(path + ".manufacturer", map.get("manufacturer"));
		String model = requiredText(path + ".model", map.get("model"));
		Map<String, String> name = texts(path + ".name", map.get("name"));
		if (name == null) {
			throw invalid(path + ".name", I18NConstants.CATALOG_INVALID_TEXT);
		}
		Map<String, String> description = texts(path + ".description", map.get("description"));
		List<EntryData> functions = new ArrayList<>();
		Object list = map.get("functions");
		if (list != null && !(list instanceof List<?>)) {
			throw invalid(path + ".functions", I18NConstants.CATALOG_INVALID_FUNCTIONS);
		}
		Set<String> seen = new HashSet<>();
		if (list != null) {
			List<?> items = (List<?>) list;
			for (int i = 0; i < items.size(); i++) {
				String entryPath = path + ".functions[" + i + "]";
				EntryData entry = entry(entryPath, items.get(i));
				if (!seen.add(entry.endpoint() + "/" + entry.function())) {
					throw invalid(entryPath, I18NConstants.CATALOG_DUPLICATE_FUNCTION__FUNCTION_ENDPOINT
						.fill(entry.function(), Long.valueOf(entry.endpoint())));
				}
				functions.add(entry);
			}
		}
		return new TypeData(manufacturer, model, name, description, functions);
	}

	private static EntryData entry(String path, Object item) {
		if (!(item instanceof Map<?, ?> map)) {
			throw invalid(path, I18NConstants.CATALOG_INVALID_OBJECT);
		}
		if (!(map.get("endpoint") instanceof Long endpoint) || endpoint.longValue() < 1 || endpoint.longValue() > 240) {
			throw invalid(path + ".endpoint", I18NConstants.CATALOG_INVALID_ENDPOINT);
		}
		String function = requiredText(path + ".function", map.get("function"));
		Map<String, String> name = texts(path + ".name", map.get("name"));
		String type = optionalText(path + ".type", map.get("type"));
		if (type != null) {
			if (valueType(type) == null) {
				throw invalid(path + ".type", I18NConstants.CATALOG_INVALID_TYPE__TYPE.fill(type));
			}
			ResKey misfit = CatalogTypeConstraint.check(function, type);
			if (misfit != null) {
				throw invalid(path + ".type", misfit);
			}
		}
		String unit = optionalText(path + ".unit", map.get("unit"));
		Object hidden = map.get("hidden");
		if (hidden != null && !(hidden instanceof Boolean)) {
			throw invalid(path + ".hidden", I18NConstants.CATALOG_INVALID_HIDDEN);
		}
		Long min = null;
		Long max = null;
		Double change = null;
		Object reporting = map.get("reporting");
		if (reporting != null) {
			if (!(reporting instanceof Map<?, ?> members)) {
				throw invalid(path + ".reporting", I18NConstants.CATALOG_INVALID_OBJECT);
			}
			min = integer(path + ".reporting.min", members.get("min"));
			max = integer(path + ".reporting.max", members.get("max"));
			Object value = members.get("change");
			if (value != null && !(value instanceof Number)) {
				throw invalid(path + ".reporting.change", I18NConstants.CATALOG_INVALID_NUMBER);
			}
			change = value == null ? null : Double.valueOf(((Number) value).doubleValue());
			ZigbeeFunctionTable.Info info = ZigbeeFunctionTable.byKey(function);
			if (info != null) {
				ZigbeeReporting.Refusal refusal = ZigbeeReporting.check(info, min, max, change);
				if (refusal != null) {
					throw invalid(path + ".reporting" + refusal.member().path(), refusal.reason());
				}
			}
		}
		return new EntryData(endpoint.longValue(), function, name, type, unit, Boolean.TRUE.equals(hidden), min, max,
			change);
	}

	private static Long integer(String path, Object value) {
		if (value == null) {
			return null;
		}
		if (!(value instanceof Long number)) {
			throw invalid(path, I18NConstants.CATALOG_INVALID_INTEGER);
		}
		return number;
	}

	private static String requiredText(String path, Object value) {
		String text = optionalText(path, value);
		if (text == null) {
			throw invalid(path, I18NConstants.CATALOG_INVALID_TEXT);
		}
		return text;
	}

	private static String optionalText(String path, Object value) {
		if (value == null) {
			return null;
		}
		if (!(value instanceof String text)) {
			throw invalid(path, I18NConstants.CATALOG_INVALID_TEXT);
		}
		return text.isEmpty() ? null : text;
	}

	/**
	 * The texts of a multilingual member by language: one text stands for every language of the
	 * application; a language of the application the object leaves out takes the text of the first
	 * one given (a literal text needs one in every language). <code>null</code> for none.
	 */
	private static Map<String, String> texts(String path, Object value) {
		if (value == null) {
			return null;
		}
		Map<String, String> given = new LinkedHashMap<>();
		if (value instanceof String text) {
			if (text.isEmpty()) {
				return null;
			}
			for (String language : languages()) {
				given.put(language, text);
			}
			return given;
		}
		if (!(value instanceof Map<?, ?> map)) {
			throw invalid(path, I18NConstants.CATALOG_INVALID_TEXT);
		}
		for (Map.Entry<?, ?> entry : map.entrySet()) {
			if (!(entry.getValue() instanceof String text)) {
				throw invalid(path + "." + entry.getKey(), I18NConstants.CATALOG_INVALID_TEXT);
			}
			if (!text.isEmpty()) {
				given.put(String.valueOf(entry.getKey()), text);
			}
		}
		if (given.isEmpty()) {
			return null;
		}
		String first = given.values().iterator().next();
		Map<String, String> result = new LinkedHashMap<>();
		for (String language : languages()) {
			result.put(language, given.getOrDefault(language, first));
		}
		for (Map.Entry<String, String> entry : given.entrySet()) {
			result.putIfAbsent(entry.getKey(), entry.getValue());
		}
		return result;
	}

	private static List<String> languages() {
		List<String> result = new ArrayList<>();
		for (Locale locale : ResourcesModule.getInstance().getSupportedLocales()) {
			result.add(locale.getLanguage());
		}
		return result;
	}

	private static TopLogicException invalid(String path, ResKey reason) {
		return new TopLogicException(I18NConstants.ERROR_CATALOG_INVALID__PATH_REASON.fill(path, reason));
	}

	/**
	 * The device type of the catalog as the file describes it.
	 */
	static TypeData read(TLObject type) {
		List<EntryData> functions = new ArrayList<>();
		for (TLObject entry : ZigbeeCatalog.entries(type)) {
			functions.add(new EntryData(number(entry.tValueByName("endpoint")).longValue(),
				(String) entry.tValueByName("function"), texts(entry.tValueByName("name")),
				ZigbeeCatalog.recommendedType(entry), ZigbeeCatalog.recommendedUnit(entry),
				Boolean.TRUE.equals(entry.tValueByName("hidden")), integerValue(entry.tValueByName(ZigbeeReporting.MIN)),
				integerValue(entry.tValueByName(ZigbeeReporting.MAX)),
				entry.tValueByName(ZigbeeReporting.CHANGE) instanceof Number change ? Double.valueOf(change.doubleValue())
					: null));
		}
		return new TypeData((String) type.tValueByName("manufacturer"), (String) type.tValueByName("model"),
			texts(type.tValueByName("name")), texts(type.tValueByName("description")), functions);
	}

	private static Number number(Object value) {
		return value instanceof Number number ? number : Long.valueOf(0);
	}

	private static Long integerValue(Object value) {
		return value instanceof Number number ? Long.valueOf(number.longValue()) : null;
	}

	/**
	 * The texts of a multilingual attribute's value by language, <code>null</code> for none.
	 */
	private static Map<String, String> texts(Object value) {
		if (!(value instanceof ResKey key)) {
			return null;
		}
		Map<String, String> result = new LinkedHashMap<>();
		if (key instanceof ResKey.LiteralKey literal) {
			Map<String, String> byLanguage = new HashMap<>();
			for (Map.Entry<Locale, String> entry : literal.getTranslations().entrySet()) {
				byLanguage.put(entry.getKey().getLanguage(), entry.getValue());
			}
			for (String language : languages()) {
				if (byLanguage.containsKey(language)) {
					result.put(language, byLanguage.remove(language));
				}
			}
			result.putAll(new java.util.TreeMap<>(byLanguage));
		} else {
			for (Locale locale : ResourcesModule.getInstance().getSupportedLocales()) {
				result.put(locale.getLanguage(), Resources.getInstance(locale).getString(key));
			}
		}
		return result.isEmpty() ? null : result;
	}

	/**
	 * The device type as the file writes it.
	 */
	static Map<String, Object> json(TypeData type) {
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("manufacturer", type.manufacturer());
		result.put("model", type.model());
		result.put("name", type.name());
		if (type.description() != null) {
			result.put("description", type.description());
		}
		List<Object> functions = new ArrayList<>();
		for (EntryData entry : type.functions()) {
			Map<String, Object> function = new LinkedHashMap<>();
			function.put("endpoint", Long.valueOf(entry.endpoint()));
			function.put("function", entry.function());
			if (entry.name() != null) {
				function.put("name", entry.name());
			}
			if (entry.type() != null) {
				function.put("type", entry.type());
			}
			if (entry.unit() != null) {
				function.put("unit", entry.unit());
			}
			if (entry.hidden()) {
				function.put("hidden", Boolean.TRUE);
			}
			if (entry.min() != null || entry.max() != null || entry.change() != null) {
				Map<String, Object> reporting = new LinkedHashMap<>();
				if (entry.min() != null) {
					reporting.put("min", entry.min());
				}
				if (entry.max() != null) {
					reporting.put("max", entry.max());
				}
				if (entry.change() != null) {
					reporting.put("change", entry.change());
				}
				function.put("reporting", reporting);
			}
			functions.add(function);
		}
		result.put("functions", functions);
		return result;
	}

	/**
	 * Writes the file's description into the type; must run in a transaction.
	 */
	private static void update(TLObject type, TypeData data) {
		set(type, "manufacturer", data.manufacturer());
		set(type, "model", data.model());
		set(type, "name", text(data.name()));
		set(type, "description", text(data.description()));
		Map<String, TLObject> byKey = new HashMap<>();
		List<TLObject> before = ZigbeeCatalog.entries(type);
		for (TLObject entry : before) {
			byKey.putIfAbsent(number(entry.tValueByName("endpoint")).longValue() + "/" + entry.tValueByName("function"),
				entry);
		}
		List<TLObject> after = new ArrayList<>();
		for (EntryData data2 : data.functions()) {
			TLObject entry = byKey.remove(data2.endpoint() + "/" + data2.function());
			if (entry == null) {
				entry = create(ZigbeeCatalog.FUNCTION_ENTRY);
				entry.tUpdateByName("endpoint", Integer.valueOf((int) data2.endpoint()));
				entry.tUpdateByName("function", data2.function());
			}
			set(entry, "name", text(data2.name()));
			set(entry, "type", data2.type() == null ? null : valueType(data2.type()));
			set(entry, "unit", data2.unit());
			set(entry, "hidden", data2.hidden() ? Boolean.TRUE : null);
			set(entry, ZigbeeReporting.MIN, data2.min() == null ? null : Integer.valueOf(data2.min().intValue()));
			set(entry, ZigbeeReporting.MAX, data2.max() == null ? null : Integer.valueOf(data2.max().intValue()));
			set(entry, ZigbeeReporting.CHANGE, data2.change());
			after.add(entry);
		}
		if (!after.equals(before)) {
			type.tUpdateByName("functions", after);
		}
		for (TLObject removed : byKey.values()) {
			removed.tDelete();
		}
	}

	private static void set(TLObject obj, String attribute, Object value) {
		Object before = obj.tValueByName(attribute);
		if (before instanceof ResKey || value instanceof ResKey) {
			if (!Objects.equals(texts(before), texts(value))) {
				obj.tUpdateByName(attribute, value);
			}
			return;
		}
		if (!Objects.equals(normalized(before), normalized(value))) {
			obj.tUpdateByName(attribute, value);
		}
	}

	private static Object normalized(Object value) {
		if (value instanceof Double || value instanceof Float) {
			return Double.valueOf(((Number) value).doubleValue());
		}
		if (value instanceof Number number) {
			return Long.valueOf(number.longValue());
		}
		// An unset flag reads as false (stored and transient alike): a flag the export leaves out (null) is false.
		return Boolean.FALSE.equals(value) ? null : value;
	}

	private static ResKey text(Map<String, String> texts) {
		return texts == null ? null : FunctionProposal.text(texts);
	}

	static TLObject valueType(String name) {
		return ((TLEnumeration) TLModelUtil.findType("ehome.runtime:DatapointValueType")).getClassifier(name);
	}

	private static TLObject create(String type) {
		return ModelService.getInstance().getFactory().createObject((TLClass) TLModelUtil.findType(type));
	}

}
