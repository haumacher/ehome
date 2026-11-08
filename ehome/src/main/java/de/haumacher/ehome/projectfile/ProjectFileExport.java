/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.projectfile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import com.top_logic.basic.util.ResKey;
import com.top_logic.basic.util.ResourcesModule;
import com.top_logic.layout.provider.MetaLabelProvider;
import com.top_logic.model.TLClassifier;
import com.top_logic.model.TLObject;
import com.top_logic.model.TLPrimitive;
import com.top_logic.model.TLReference;
import com.top_logic.model.TLStructuredType;
import com.top_logic.model.TLStructuredTypePart;
import com.top_logic.model.instance.exporter.XMLInstanceExporter;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.Resources;
import com.top_logic.util.error.TopLogicException;

import de.haumacher.ehome.exporter.KnxprojExporter;
import de.haumacher.ehome.knx.GA;
import de.haumacher.ehome.runtime.Json;
import de.haumacher.ehome.runtime.RuntimeImage;

/**
 * Writes a project as project file ({@link ProjectFile}, haumacher/ehome #117).
 *
 * <p>
 * The KNX part goes through {@link KnxprojExporter}; the rest is written generically along the model:
 * every attribute of the project that the <code>.knxproj</code> does not hold and that is not
 * {@link ProjectFile#leftOut(TLStructuredTypePart) left out}, with the parts of its composite
 * references in place, and the settings of the group addresses. A reference to an object neither in
 * the file nor in the KNX part (nor a controller on the LAN) refuses the export, as the import could
 * not restore it.
 * </p>
 */
public final class ProjectFileExport {

	private final TLObject _project;

	/** The objects of the KNX part, written by the <code>.knxproj</code>. */
	private final Set<TLObject> _knx = new HashSet<>();

	/** The ID of each object written into the document. */
	private final Map<TLObject, String> _ids = new HashMap<>();

	private final Set<String> _usedIds = new HashSet<>();

	/** The objects of the document some reference points to: they get their {@link ProjectFile#OBJECT_ID}. */
	private final Set<TLObject> _referenced = new HashSet<>();

	private ProjectFileExport(TLObject project) {
		_project = project;
	}

	/**
	 * Writes the project file of the given project to the given stream.
	 */
	public static void export(TLObject project, OutputStream out) throws IOException {
		Map<String, Object> document = document(project, Instant.now());
		ByteArrayOutputStream knxproj = new ByteArrayOutputStream();
		KnxprojExporter.export(project, knxproj);

		ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8);
		zip.putNextEntry(new ZipEntry(ProjectFile.JSON_ENTRY));
		zip.write(Json.write(document).getBytes(StandardCharsets.UTF_8));
		zip.closeEntry();
		zip.putNextEntry(new ZipEntry(ProjectFile.KNXPROJ_ENTRY));
		zip.write(knxproj.toByteArray());
		zip.closeEntry();
		zip.finish();
	}

	/**
	 * The JSON document of the given project, the second entry of its project file.
	 *
	 * @param project
	 *        The project to export.
	 * @param time
	 *        The time of the export.
	 */
	public static Map<String, Object> document(TLObject project, Instant time) {
		return new ProjectFileExport(project).document(time);
	}

	private Map<String, Object> document(Instant time) {
		collectKnx();
		assignIds(_project, "");
		for (TLObject address : groupAddresses()) {
			assignIds(address, ProjectFile.GROUP_ADDRESSES_MEMBER + "/"
				+ RuntimeImage.threeLevel(((GA) address.tValueByName("address")).getValue()));
		}

		Map<String, Object> result = new LinkedHashMap<>();
		result.put(ProjectFile.FORMAT_MEMBER, ProjectFile.FORMAT);
		result.put(ProjectFile.VERSION_MEMBER, ProjectFile.version());
		result.put(ProjectFile.EXPORTED_MEMBER, time.truncatedTo(ChronoUnit.SECONDS).toString());

		Map<String, Object> project = new LinkedHashMap<>();
		project.put(ProjectFile.ID_MEMBER, _project.tValueByName("extId"));
		project.put(ProjectFile.NAME_MEMBER, _project.tValueByName("name"));
		writeAttributes(project, _project);
		result.put(ProjectFile.PROJECT_MEMBER, project);

		List<Object> settings = new ArrayList<>();
		for (TLObject address : groupAddresses()) {
			Map<String, Object> entry = new LinkedHashMap<>();
			entry.put(ProjectFile.GA, RuntimeImage.threeLevel(((GA) address.tValueByName("address")).getValue()));
			writeAttributes(entry, address);
			if (entry.size() > 1) {
				settings.add(entry);
			}
		}
		result.put(ProjectFile.GROUP_ADDRESSES_MEMBER, settings);
		return result;
	}

	/**
	 * The group addresses of the project, by their address.
	 */
	private List<TLObject> groupAddresses() {
		TreeMap<Integer, TLObject> result = new TreeMap<>();
		for (TLObject obj : _knx) {
			if (isGroupAddress(obj) && obj.tValueByName("address") instanceof GA address) {
				result.put(Integer.valueOf(address.getValue()), obj);
			}
		}
		return new ArrayList<>(result.values());
	}

	private static boolean isGroupAddress(TLObject obj) {
		return TLModelUtil.qualifiedName(obj.tType()).equals(ProjectFile.GROUP_ADDRESS_TYPE);
	}

	private void collectKnx() {
		for (String name : ProjectFile.knxReferences()) {
			for (TLObject part : values(_project.tValueByName(name))) {
				collectComposition(part);
			}
		}
	}

	private void collectComposition(TLObject obj) {
		_knx.add(obj);
		for (TLStructuredTypePart part : obj.tType().getAllParts()) {
			if (part instanceof TLReference reference && reference.isComposite() && !reference.isBackwards()
				&& !reference.isDerived()) {
				for (TLObject child : values(obj.tValue(part))) {
					collectComposition(child);
				}
			}
		}
	}

	/**
	 * The attributes of the given object the document holds.
	 */
	private List<TLStructuredTypePart> parts(TLObject obj) {
		List<TLStructuredTypePart> result = new ArrayList<>();
		boolean project = obj == _project;
		boolean address = _knx.contains(obj) && isGroupAddress(obj);
		for (TLStructuredTypePart part : obj.tType().getAllParts()) {
			if (ProjectFile.leftOut(part)) {
				continue;
			}
			if (project && ProjectFile.projectKnx(part)) {
				continue;
			}
			if (address && !ProjectFile.groupAddressSetting(part)) {
				continue;
			}
			result.add(part);
		}
		return result;
	}

	/**
	 * Gives the objects of the document their IDs and records the referenced ones.
	 */
	private void assignIds(TLObject obj, String path) {
		for (TLStructuredTypePart part : parts(obj)) {
			if (!(part instanceof TLReference reference)) {
				continue;
			}
			Object value = obj.tValue(part);
			if (reference.isComposite()) {
				int index = 0;
				for (TLObject child : values(value)) {
					index++;
					String id = uniqueId((path.isEmpty() ? "" : path + "/") + part.getName() + "/" + key(child, index));
					_ids.put(child, id);
					assignIds(child, id);
				}
			} else {
				for (TLObject target : values(value)) {
					_referenced.add(target);
				}
			}
		}
	}

	private String uniqueId(String id) {
		String result = id;
		int n = 2;
		while (!_usedIds.add(result)) {
			result = id + "~" + n++;
		}
		return result;
	}

	/**
	 * The key of an object within its reference: an identifying attribute, else its position.
	 */
	private static String key(TLObject obj, int index) {
		for (String name : List.of("identifier", "ieee", "port", "parameter")) {
			if (obj.tType().getPart(name) != null && obj.tValueByName(name) instanceof String text && !text.isBlank()) {
				return text;
			}
		}
		if (obj.tType().getPart("function") != null && obj.tType().getPart("endpoint") != null
			&& obj.tValueByName("function") instanceof String function) {
			return function + "@" + obj.tValueByName("endpoint");
		}
		return Integer.toString(index);
	}

	private void writeAttributes(Map<String, Object> out, TLObject obj) {
		for (TLStructuredTypePart part : parts(obj)) {
			Object value = obj.tValue(part);
			Object json;
			if (part instanceof TLReference reference) {
				json = reference.isComposite() ? composite(reference, value) : references(obj, reference, value);
			} else {
				json = primitive(part, value);
			}
			if (json == null || json instanceof Collection<?> list && list.isEmpty()) {
				continue;
			}
			out.put(part.getName(), json);
		}
	}

	private Object composite(TLReference reference, Object value) {
		if (reference.isMultiple()) {
			List<Map<String, Object>> result = new ArrayList<>();
			for (TLObject child : values(value)) {
				result.add(object(child, (TLStructuredType) reference.getType()));
			}
			if (!reference.isOrdered()) {
				// A set: in the order of the written parts, so that the document does not depend on
				// the order the database returns them in.
				result.sort(Comparator.comparing(Json::writeCompact));
			}
			return result;
		}
		return value == null ? null : object((TLObject) value, (TLStructuredType) reference.getType());
	}

	private Map<String, Object> object(TLObject obj, TLStructuredType declared) {
		Map<String, Object> result = new LinkedHashMap<>();
		if (_referenced.contains(obj)) {
			result.put(ProjectFile.OBJECT_ID, _ids.get(obj));
		}
		if (obj.tType() != declared) {
			result.put(ProjectFile.OBJECT_TYPE, TLModelUtil.qualifiedName(obj.tType()));
		}
		writeAttributes(result, obj);
		return result;
	}

	private Object references(TLObject obj, TLReference reference, Object value) {
		if (reference.isMultiple()) {
			List<TLObject> targets = values(value);
			if (!reference.isOrdered()) {
				// A set: in the order of the enumeration's classifiers, else of the references.
				targets = new ArrayList<>(targets);
				targets.sort(Comparator.comparing(target -> target instanceof TLClassifier classifier
					? String.format("%08d", Integer.valueOf(classifier.getIndex()))
					: Json.writeCompact(reference(obj, reference, target))));
			}
			List<Object> result = new ArrayList<>();
			for (TLObject target : targets) {
				result.add(reference(obj, reference, target));
			}
			return result;
		}
		return value == null ? null : reference(obj, reference, (TLObject) value);
	}

	private Object reference(TLObject obj, TLReference reference, TLObject target) {
		if (target instanceof TLClassifier classifier) {
			return classifier.getName();
		}
		String id = _ids.get(target);
		if (id != null) {
			return id;
		}
		if (_knx.contains(target)) {
			if (isGroupAddress(target) && target.tValueByName("address") instanceof GA address) {
				return Map.of(ProjectFile.GA, RuntimeImage.threeLevel(address.getValue()));
			}
			String key = ProjectFile.knxKey(target);
			if (key != null) {
				return Map.of(ProjectFile.KNX, key);
			}
		}
		if (TLModelUtil.qualifiedName(target.tType()).equals(ProjectFile.CONTROLLER_TYPE)
			&& target.tContainer() == null) {
			Map<String, Object> lan = new LinkedHashMap<>();
			for (String name : List.of("name", "host", "port")) {
				Object value = target.tValueByName(name);
				if (value != null) {
					lan.put(name, value instanceof Number number ? Long.valueOf(number.longValue()) : value);
				}
			}
			return Map.of(ProjectFile.LAN, lan);
		}
		throw new TopLogicException(I18NConstants.ERROR_REFERENCE_OUTSIDE__ATTRIBUTE_OBJECT_TARGET.fill(
			TLModelUtil.qualifiedName(reference), label(obj), label(target)));
	}

	private static String label(TLObject obj) {
		return MetaLabelProvider.INSTANCE.getLabel(obj);
	}

	private static Object primitive(TLStructuredTypePart part, Object value) {
		if (value == null) {
			return null;
		}
		if (value instanceof Boolean || value instanceof String) {
			// A false truth value is written: an attribute with a default is true on a new object.
			return value;
		}
		if (value instanceof Double || value instanceof Float) {
			return Double.valueOf(((Number) value).doubleValue());
		}
		if (value instanceof Number number) {
			return Long.valueOf(number.longValue());
		}
		if (value instanceof ResKey key) {
			return texts(key);
		}
		return XMLInstanceExporter.serialize((TLPrimitive) part.getType(), value);
	}

	/**
	 * The texts of a multilingual value by language.
	 */
	static Map<String, String> texts(ResKey key) {
		Map<String, String> result = new LinkedHashMap<>();
		if (key instanceof ResKey.LiteralKey literal) {
			Map<String, String> byLanguage = new TreeMap<>();
			for (Map.Entry<Locale, String> entry : literal.getTranslations().entrySet()) {
				byLanguage.put(entry.getKey().getLanguage(), entry.getValue());
			}
			result.putAll(byLanguage);
		} else {
			for (Locale locale : ResourcesModule.getInstance().getSupportedLocales()) {
				result.put(locale.getLanguage(), Resources.getInstance(locale).getString(key));
			}
		}
		return result;
	}

	/**
	 * The objects of a reference's value.
	 */
	static List<TLObject> values(Object value) {
		if (value instanceof Collection<?> collection) {
			List<TLObject> result = new ArrayList<>(collection.size());
			for (Object element : collection) {
				result.add((TLObject) element);
			}
			return result;
		}
		return value == null ? List.of() : List.of((TLObject) value);
	}

}
