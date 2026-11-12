/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.projectfile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import com.top_logic.basic.i18n.log.BufferingI18NLog;
import com.top_logic.basic.logging.Level;
import com.top_logic.element.meta.MetaElementUtil;
import com.top_logic.layout.provider.MetaLabelProvider;
import com.top_logic.model.TLClass;
import com.top_logic.model.TLClassifier;
import com.top_logic.model.TLEnumeration;
import com.top_logic.model.TLObject;
import com.top_logic.model.TLPrimitive;
import com.top_logic.model.TLReference;
import com.top_logic.model.TLStructuredType;
import com.top_logic.model.TLStructuredTypePart;
import com.top_logic.model.TLType;
import com.top_logic.model.instance.importer.XMLInstanceImporter;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.error.TopLogicException;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.connection.ProjectControllers;
import de.haumacher.ehome.knx.GA;
import de.haumacher.ehome.knx.GroupAddressStyle;
import de.haumacher.ehome.runtime.FunctionProposal;
import de.haumacher.ehome.runtime.Json;

/**
 * Reads a project file ({@link ProjectFile}, haumacher/ehome #117) into a project.
 *
 * <p>
 * The import of a file has two steps in one transaction: the ETS import of its
 * {@link ProjectFile#KNXPROJ_ENTRY} creates the project with its KNX part (and the external ID of the
 * project in the file), then {@link #apply(TLObject, Contents, List)} adds what the JSON document
 * holds. The objects of the document are created first with their values; the references between
 * them and into the KNX part are set when all objects exist. The model's constraints check the
 * result at the commit.
 * </p>
 *
 * <p>
 * What the import has to say goes into its {@link #getMessages() messages}: that the controllers have
 * to be paired again, a project of the installation with the same ID, attributes of a newer format
 * that the model does not know, references to objects the project does not have.
 * </p>
 */
public final class ProjectFileImport {

	private final BufferingI18NLog _log = new BufferingI18NLog();

	/** The objects of the document by their {@link ProjectFile#OBJECT_ID}. */
	private final Map<String, TLObject> _byId = new HashMap<>();

	/** The objects of the KNX part by their {@link ProjectFile#knxKey(TLObject) key}. */
	private final Map<String, List<TLObject>> _byExtId = new HashMap<>();

	/** The group addresses of the project by their address. */
	private final Map<Integer, TLObject> _byAddress = new HashMap<>();

	/** The references to set when all objects exist. */
	private final List<Runnable> _references = new ArrayList<>();

	/** The project the file is applied to ({@link #apply(TLObject, Contents, List)}). */
	private TLObject _project;

	/**
	 * The contents of a project file.
	 *
	 * @param document
	 *        The JSON document.
	 * @param knxproj
	 *        The KNX part as <code>.knxproj</code> archive.
	 */
	public record Contents(Map<?, ?> document, byte[] knxproj) {

		/**
		 * The external ID of the project in the file, <code>null</code> if it has none.
		 */
		public String projectId() {
			return project().get(ProjectFile.ID_MEMBER) instanceof String id ? id : null;
		}

		/**
		 * The JSON object of the project.
		 */
		public Map<?, ?> project() {
			return document.get(ProjectFile.PROJECT_MEMBER) instanceof Map<?, ?> project ? project : Map.of();
		}
	}

	/**
	 * The messages of the import.
	 */
	public List<BufferingI18NLog.Entry> getMessages() {
		return _log.getEntries();
	}

	/**
	 * Reads the entries of a project file, refusing a file of another format or of a newer major
	 * version.
	 */
	public static Contents read(InputStream in) throws IOException {
		Map<?, ?> document = null;
		byte[] knxproj = null;
		ZipInputStream zip = new ZipInputStream(in, StandardCharsets.UTF_8);
		for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
			if (entry.getName().equals(ProjectFile.JSON_ENTRY)) {
				String text = new String(zip.readAllBytes(), StandardCharsets.UTF_8);
				try {
					if (Json.parse(text, 200) instanceof Map<?, ?> map) {
						document = map;
					}
				} catch (Json.JsonException ex) {
					throw notAProjectFile(ProjectFile.JSON_ENTRY + ": " + ex.getMessage());
				}
			} else if (entry.getName().equals(ProjectFile.KNXPROJ_ENTRY)) {
				knxproj = zip.readAllBytes();
			}
		}
		if (document == null || !ProjectFile.FORMAT.equals(document.get(ProjectFile.FORMAT_MEMBER))) {
			throw notAProjectFile(ProjectFile.JSON_ENTRY);
		}
		String version = Objects.toString(document.get(ProjectFile.VERSION_MEMBER), "");
		int major;
		try {
			major = Integer.parseInt(version.contains(".") ? version.substring(0, version.indexOf('.')) : version);
		} catch (NumberFormatException ex) {
			throw notAProjectFile(ProjectFile.VERSION_MEMBER + " " + version);
		}
		if (major > ProjectFile.MAJOR) {
			throw new TopLogicException(
				I18NConstants.ERROR_NEWER_VERSION__VERSION_MAJOR.fill(version, Integer.valueOf(ProjectFile.MAJOR)));
		}
		if (knxproj == null) {
			throw notAProjectFile(ProjectFile.KNXPROJ_ENTRY);
		}
		return new Contents(document, knxproj);
	}

	private static TopLogicException notAProjectFile(String detail) {
		return new TopLogicException(I18NConstants.ERROR_NOT_A_PROJECT_FILE__DETAIL.fill(detail));
	}

	/**
	 * The projects of the installation with the given external ID.
	 */
	public static List<TLObject> projectsWithId(String id) {
		List<TLObject> result = new ArrayList<>();
		if (id == null) {
			return result;
		}
		TLClass type = (TLClass) TLModelUtil.findType(ProjectFile.PROJECT_TYPE);
		for (TLObject project : MetaElementUtil.getAllDirectInstancesOf(type,
			TLObject.class)) {
			if (id.equals(project.tValueByName("extId"))) {
				result.add(project);
			}
		}
		return result;
	}

	/**
	 * Adds what the document of a project file holds to the project its <code>.knxproj</code> created.
	 *
	 * @param project
	 *        The project the ETS import of the file's archive created.
	 * @param contents
	 *        The file.
	 * @param existing
	 *        The projects of the installation with the file's project ID before the import.
	 */
	public void apply(TLObject project, Contents contents, List<TLObject> existing) {
		_project = project;
		indexKnx(project);

		Map<?, ?> json = contents.project();
		readAttributes(project, json, "project");

		if (contents.document().get(ProjectFile.GROUP_ADDRESSES_MEMBER) instanceof List<?> settings) {
			for (Object element : settings) {
				if (!(element instanceof Map<?, ?> entry)) {
					continue;
				}
				String text = Objects.toString(entry.get(ProjectFile.GA), "");
				TLObject address = address(text);
				if (address == null) {
					_log.log(Level.WARN, I18NConstants.WARN_UNKNOWN_GROUP_ADDRESS__ADDRESS.fill(text), null);
					continue;
				}
				readAttributes(address, entry, ProjectFile.GROUP_ADDRESSES_MEMBER + "/" + text);
			}
		}

		if (contents.document().get(ProjectFile.DEVICES_MEMBER) instanceof List<?> devices) {
			for (Object element : devices) {
				if (!(element instanceof Map<?, ?> entry)) {
					continue;
				}
				String key = Objects.toString(entry.get(ProjectFile.KNX), "");
				TLObject device = device(key);
				if (device == null) {
					_log.log(Level.WARN, I18NConstants.WARN_UNKNOWN_DEVICE__DEVICE.fill(key), null);
					continue;
				}
				readAttributes(device, entry, ProjectFile.DEVICES_MEMBER + "/" + key);
			}
		}

		for (Runnable reference : _references) {
			reference.run();
		}

		List<TLObject> others = new ArrayList<>(existing);
		others.remove(project);
		if (!others.isEmpty()) {
			List<String> names = new ArrayList<>();
			for (TLObject other : others) {
				names.add(MetaLabelProvider.INSTANCE.getLabel(other));
			}
			_log.log(Level.WARN,
				I18NConstants.WARN_SAME_PROJECT_ID__ID_PROJECTS.fill(contents.projectId(), String.join(", ", names)),
				null);
		}
		boolean controllers = !ProjectFileExport.values(project.tValueByName("controllers")).isEmpty()
			|| project.tValueByName("controller") != null;
		_log.log(controllers ? Level.WARN : Level.INFO, I18NConstants.INFO_PAIR_CONTROLLERS_AGAIN, null);
	}

	private void indexKnx(TLObject project) {
		for (String name : ProjectFile.knxReferences()) {
			for (TLObject part : ProjectFileExport.values(project.tValueByName(name))) {
				index(part);
			}
		}
	}

	private void index(TLObject obj) {
		String key = ProjectFile.knxKey(obj);
		if (key != null) {
			_byExtId.computeIfAbsent(key, x -> new ArrayList<>()).add(obj);
		}
		if (TLModelUtil.qualifiedName(obj.tType()).equals(ProjectFile.GROUP_ADDRESS_TYPE)
			&& obj.tValueByName("address") instanceof GA address) {
			_byAddress.put(Integer.valueOf(address.getValue()), obj);
		}
		for (TLStructuredTypePart part : obj.tType().getAllParts()) {
			if (part instanceof TLReference reference && reference.isComposite() && !reference.isBackwards()
				&& !reference.isDerived()) {
				for (TLObject child : ProjectFileExport.values(obj.tValue(part))) {
					index(child);
				}
			}
		}
	}

	private TLObject device(String key) {
		for (TLObject candidate : _byExtId.getOrDefault(key, List.of())) {
			if (TLModelUtil.qualifiedName(candidate.tType()).equals(ProjectFile.DEVICE_TYPE)) {
				return candidate;
			}
		}
		return null;
	}

	private TLObject address(String text) {
		GA address;
		try {
			address = GA.parse(text, GroupAddressStyle.ThreeLevel);
		} catch (RuntimeException ex) {
			return null;
		}
		return address == null ? null : _byAddress.get(Integer.valueOf(address.getValue()));
	}

	private void readAttributes(TLObject obj, Map<?, ?> json, String path) {
		boolean project = TLModelUtil.qualifiedName(obj.tType()).equals(ProjectFile.PROJECT_TYPE);
		boolean address = TLModelUtil.qualifiedName(obj.tType()).equals(ProjectFile.GROUP_ADDRESS_TYPE);
		boolean device = TLModelUtil.qualifiedName(obj.tType()).equals(ProjectFile.DEVICE_TYPE);
		for (Map.Entry<?, ?> entry : json.entrySet()) {
			String name = String.valueOf(entry.getKey());
			if (name.startsWith("$")) {
				continue;
			}
			if (project && (name.equals(ProjectFile.ID_MEMBER) || name.equals(ProjectFile.NAME_MEMBER))) {
				// From the .knxproj.
				continue;
			}
			if (address && name.equals(ProjectFile.GA) || device && name.equals(ProjectFile.KNX)) {
				continue;
			}
			String at = path + "." + name;
			TLStructuredTypePart part = obj.tType().getPart(name);
			if (part == null || ProjectFile.leftOut(part) || project && ProjectFile.projectKnx(part)
				|| address && !ProjectFile.groupAddressSetting(part) || device && !ProjectFile.deviceSetting(part)) {
				_log.log(Level.WARN, I18NConstants.WARN_UNKNOWN_ATTRIBUTE__ATTRIBUTE_PATH.fill(name, path), null);
				continue;
			}
			Object value = entry.getValue();
			if (part instanceof TLReference reference) {
				if (reference.isComposite()) {
					readComposite(obj, reference, value, at);
				} else if (reference.getType() instanceof TLEnumeration enumeration) {
					obj.tUpdate(part, classifiers(enumeration, reference, value, at));
				} else {
					_references.add(() -> obj.tUpdate(part, targets(reference, value, at)));
				}
			} else {
				obj.tUpdate(part, primitive(part, value, at));
			}
		}
	}

	private void readComposite(TLObject obj, TLReference reference, Object value, String path) {
		if (reference.isMultiple()) {
			List<TLObject> parts = new ArrayList<>();
			int index = 0;
			for (Object element : list(value)) {
				parts.add(readObject((TLStructuredType) reference.getType(), element, path + "[" + index++ + "]"));
			}
			obj.tUpdate(reference, parts);
		} else {
			obj.tUpdate(reference, value == null ? null : readObject((TLStructuredType) reference.getType(), value, path));
		}
	}

	private TLObject readObject(TLStructuredType declared, Object value, String path) {
		if (!(value instanceof Map<?, ?> json)) {
			throw invalid(path, "object expected");
		}
		TLType type = declared;
		if (json.get(ProjectFile.OBJECT_TYPE) instanceof String name) {
			type = TLModelUtil.findType(name);
		}
		if (!(type instanceof TLClass concrete) || concrete.isAbstract()
			|| !TLModelUtil.isGeneralization(declared, concrete)) {
			throw new TopLogicException(I18NConstants.ERROR_UNKNOWN_TYPE__TYPE_PATH
				.fill(Objects.toString(json.get(ProjectFile.OBJECT_TYPE), TLModelUtil.qualifiedName(declared)), path));
		}
		TLObject obj = ModelService.getInstance().getFactory().createObject(concrete);
		if (json.get(ProjectFile.OBJECT_ID) instanceof String id) {
			_byId.put(id, obj);
		}
		readAttributes(obj, json, path);
		clearDefaults(obj, json);
		return obj;
	}

	/**
	 * Clears the values a new object got as defaults of its type for the attributes the document
	 * does not name: the document writes every value, so a missing one is none.
	 */
	private static void clearDefaults(TLObject obj, Map<?, ?> json) {
		for (TLStructuredTypePart part : obj.tType().getAllParts()) {
			if (json.containsKey(part.getName()) || ProjectFile.leftOut(part)) {
				continue;
			}
			Object value = obj.tValue(part);
			if (value != null && !(value instanceof Collection<?> collection && collection.isEmpty())) {
				obj.tUpdate(part, part.isMultiple() ? List.of() : null);
			}
		}
	}

	private static Object classifiers(TLEnumeration enumeration, TLReference reference, Object value, String path) {
		if (reference.isMultiple()) {
			List<TLClassifier> result = new ArrayList<>();
			for (Object element : list(value)) {
				result.add(classifier(enumeration, element, path));
			}
			return result;
		}
		return value == null ? null : classifier(enumeration, value, path);
	}

	private static TLClassifier classifier(TLEnumeration enumeration, Object value, String path) {
		TLClassifier result = enumeration.getClassifier(String.valueOf(value));
		if (result == null) {
			throw invalid(path, "unknown classifier " + value + " of " + TLModelUtil.qualifiedName(enumeration));
		}
		return result;
	}

	private Object targets(TLReference reference, Object value, String path) {
		if (reference.isMultiple()) {
			List<TLObject> result = new ArrayList<>();
			for (Object element : list(value)) {
				TLObject target = target(reference, element, path);
				if (target != null) {
					result.add(target);
				}
			}
			return result;
		}
		return value == null ? null : target(reference, value, path);
	}

	private TLObject target(TLReference reference, Object value, String path) {
		TLObject result = null;
		if (value instanceof String id) {
			result = _byId.get(id);
		} else if (value instanceof Map<?, ?> ref) {
			if (ref.get(ProjectFile.GA) instanceof String ga) {
				result = address(ga);
			} else if (ref.get(ProjectFile.KNX) instanceof String extId) {
				for (TLObject candidate : _byExtId.getOrDefault(extId, List.of())) {
					if (TLModelUtil.isGeneralization((TLStructuredType) reference.getType(), candidate.tType())) {
						result = candidate;
						break;
					}
				}
			} else if (ref.get(ProjectFile.LAN) instanceof Map<?, ?> lan) {
				result = lanController(lan);
			}
		}
		if (result == null || !TLModelUtil.isGeneralization((TLStructuredType) reference.getType(), result.tType())) {
			_log.log(Level.WARN,
				I18NConstants.WARN_UNRESOLVED_REFERENCE__PATH_TARGET.fill(path, Json.writeCompact(value)), null);
			return null;
		}
		return result;
	}

	/**
	 * The controller on the LAN a file of an earlier version names by name, host and port (a
	 * controller no project held then): the project's controller with this host and port, created in
	 * the project if it has none (haumacher/ehome #151: every controller is a part of one project).
	 */
	private TLObject lanController(Map<?, ?> lan) {
		String host = Objects.toString(lan.get("host"), null);
		Number port = lan.get("port") instanceof Number number ? number : Long.valueOf(80);
		if (host == null || host.isBlank()) {
			return null;
		}
		TLObject known = ProjectControllers.find(_project, host.trim(), port.intValue());
		if (known != null) {
			return known;
		}
		TLClass type = (TLClass) TLModelUtil.findType(ProjectFile.CONTROLLER_TYPE);
		TLObject controller = ModelService.getInstance().getFactory().createObject(type);
		String name = Objects.toString(lan.get("name"), host);
		controller.tUpdateByName("name", name);
		controller.tUpdateByName("host", host.trim());
		controller.tUpdateByName("port", primitive(type.getPart("port"), port, ProjectFile.LAN));
		ProjectControllers.add(_project, controller);
		_log.log(Level.INFO, I18NConstants.INFO_LAN_CONTROLLER_CREATED__NAME_ADDRESS.fill(name, host + ":" + port),
			null);
		return controller;
	}

	private static Object primitive(TLStructuredTypePart part, Object value, String path) {
		if (value == null) {
			return null;
		}
		if (value instanceof Map<?, ?> texts) {
			Map<String, String> byLanguage = new LinkedHashMap<>();
			for (Map.Entry<?, ?> entry : texts.entrySet()) {
				byLanguage.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
			}
			return FunctionProposal.text(byLanguage);
		}
		TLPrimitive type = (TLPrimitive) part.getType();
		if (type.getKind() == TLPrimitive.Kind.STRING && value instanceof String text) {
			return text;
		}
		if ((type.getKind() == TLPrimitive.Kind.BOOLEAN || type.getKind() == TLPrimitive.Kind.TRISTATE)
			&& value instanceof Boolean) {
			return value;
		}
		BufferingI18NLog log = new BufferingI18NLog();
		Object result = XMLInstanceImporter.parse(log, type, String.valueOf(value));
		if (log.hasErrors()) {
			throw invalid(path, String.valueOf(value));
		}
		return result;
	}

	private static List<?> list(Object value) {
		if (value instanceof Collection<?> collection) {
			return new ArrayList<>(collection);
		}
		return value == null ? List.of() : List.of(value);
	}

	private static TopLogicException invalid(String path, String detail) {
		return new TopLogicException(I18NConstants.ERROR_INVALID_VALUE__PATH_DETAIL.fill(path, detail));
	}

}
