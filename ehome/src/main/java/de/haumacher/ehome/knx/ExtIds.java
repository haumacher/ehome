/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.knx;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.top_logic.basic.sql.PooledConnection;
import com.top_logic.element.meta.MetaElementUtil;
import com.top_logic.knowledge.service.CommitHandler;
import com.top_logic.knowledge.service.KnowledgeBaseRuntimeException;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.db2.RowLevelLockingSequenceManager;
import com.top_logic.knowledge.service.db2.SequenceManager;
import com.top_logic.model.TLClass;
import com.top_logic.model.TLModule;
import com.top_logic.model.TLObject;
import com.top_logic.model.util.TLModelUtil;

/**
 * The external IDs (<code>ehome.core:Externalized#extId</code>) of objects created in the app, in the
 * manner of ETS.
 *
 * <p>
 * The patterns, one per {@link Kind}, are those ETS writes into a project file (<code>M-0008</code>,
 * <code>M-0008_A-10D7-11-A0E5</code>, <code>…_P-3762</code>, <code>…_P-3762_R-3762</code>,
 * <code>P-01A0-0_GA-1</code>, …). The ID of an object starts with the ID of the object it belongs to
 * (its <em>owner</em>: the program of a parameter, the parameter of a parameter reference, the
 * project of a group address); the running number at its end comes from a sequence of the
 * database scoped to the object that numbers it (its <em>scope</em>: the program for parameters,
 * blocks, channels and both kinds of references, the manufacturer for application programs and
 * hardware, the project for areas, lines, devices, group ranges, group addresses and building
 * parts). Numbers are unique per kind and scope, as in ETS.
 * </p>
 *
 * <p>
 * Some IDs are formed from a property instead of a number, as ETS does: a parameter type from its
 * name, an enumeration entry from its value, a communication object from its object number, a
 * product (variant) from its order number, an assignment of a program to hardware from the
 * program's ID. Where that ID is taken already (or the property is empty), a number is appended (or
 * used).
 * </p>
 *
 * <p>
 * A new number is always above every number of its kind in the scope, also above those ETS wrote:
 * before a number is allocated, the sequence is raised to the highest number found in the scope
 * ({@link #raise(TLObject, Kind)}). The import raises the sequences of every scope it imported into
 * the same way ({@link #raiseImported(Collection)}), so the sequences reflect the imported data even
 * before anything is created.
 * </p>
 *
 * <p>
 * An application program gets <code>&lt;manufacturer&gt;_A-&lt;number&gt;-01-&lt;counter&gt;</code>:
 * the application number from the manufacturer's sequence of programs, version 01, and as the last
 * group a second counter of the manufacturer, because the hash ETS writes there cannot be computed.
 * A manufacturer gets <code>M-</code> and its KNX manufacturer ID (four hex digits); one without KNX
 * ID gets a number of the private range from {@link #PRIVATE_MANUFACTURER_BASE} on (<code>M-FF01</code>,
 * …), above every ID the KNX master data assigns.
 * </p>
 *
 * <p>
 * Switches, cases and the channel independent part have no ID in ETS and get none here.
 * </p>
 *
 * @see ExtIdInitializer
 */
public final class ExtIds {

	/**
	 * Name of the attribute holding the external ID.
	 */
	public static final String EXT_ID = "extId";

	/**
	 * Base of the numbers of manufacturers created without KNX manufacturer ID: the first one gets
	 * <code>M-FF01</code>.
	 */
	public static final int PRIVATE_MANUFACTURER_BASE = 0xFF00;

	/**
	 * Version of an application program created in the app (the middle group of its ID).
	 */
	public static final String PROGRAM_VERSION = "01";

	/**
	 * The installation of a project in a project-side ID (<code>P-01A0-0_A-1</code>).
	 */
	public static final String INSTALLATION = "-0";

	private static final SequenceManager SEQUENCES = new RowLevelLockingSequenceManager();

	private static final int RETRY_COUNT = 5;

	/**
	 * The kinds of IDs: what is numbered, and how the number is read from an existing ID.
	 */
	public enum Kind {
		/** <code>M-0008</code>; numbered only without KNX ID, in the private range. */
		MANUFACTURER("^M-([0-9A-F]{4})$", 16),
		/** <code>P-01A0</code>. */
		PROJECT("^P-([0-9A-F]{4})$", 16),
		/** <code>M-0008_A-10D7-11-A0E5</code>: the application number. */
		PROGRAM("_A-([0-9A-F]{4})-", 16),
		/** The last group of a program ID (a counter instead of the ETS hash). */
		PROGRAM_HASH(null, 16),
		/** <code>M-0008_H-…</code> (ETS hardware, the app's product). */
		PRODUCT("_H-(\\d+)$", 10),
		/** <code>…_H-…_P-&lt;order number&gt;</code> (ETS product, the app's variant), numbered without order number. */
		VARIANT("_P-(\\d+)$", 10),
		/** <code>…_H-…_HP-&lt;program&gt;</code>, numbered without program. */
		ASSIGNMENT("_HP-(\\d+)$", 10),
		/** <code>M-0008_CS-1</code>, a catalog of a manufacturer. */
		CATALOG("_CS-(\\d+)$", 10),
		/** <code>M-0008_CS-1-2</code>, a section of a catalog. */
		SECTION("-(\\d+)$", 10),
		/** <code>…_PT-&lt;name&gt;</code>, numbered without name. */
		PARAMETER_TYPE(null, 10),
		/** <code>…_PT-…_EN-&lt;value&gt;</code>. */
		ENUMERATION_VALUE(null, 10),
		/** <code>…_P-3762</code>; union parameters (<code>_UP-</code>) count as well. */
		PARAMETER("_U?P-(\\d+)$", 10),
		/** <code>…_P-3762_R-3762</code>. */
		PARAMETER_REF("_R-(\\d+)$", 10),
		/** <code>…_O-0</code>: the object number if free, otherwise a number above all. */
		PORT("_O-(\\d+)$", 10),
		/** <code>…_O-0_R-81</code>. */
		PORT_REF("_R-(\\d+)$", 10),
		/** <code>…_PB-3762</code>. */
		BLOCK("_PB-(\\d+)$", 10),
		/** <code>…_CH-0</code>. */
		CHANNEL("_CH-(\\d+)$", 10),
		/** <code>P-01A0-0_A-0</code>. */
		AREA("_A-(\\d+)$", 10),
		/** <code>P-01A0-0_L-0</code>. */
		LINE("_L-(\\d+)$", 10),
		/** <code>P-01A0-0_DI-1</code>. */
		DEVICE("_DI-(\\d+)$", 10),
		/** <code>P-01A0-0_GR-1</code>. */
		GROUP_RANGE("_GR-(\\d+)$", 10),
		/** <code>P-01A0-0_GA-1</code>. */
		GROUP_ADDRESS("_GA-(\\d+)$", 10),
		/** <code>P-01A0-0_BP-1</code>, every kind of building part. */
		BUILDING_PART("_BP-(\\d+)$", 10),
		;

		private final Pattern _number;

		private final int _radix;

		Kind(String number, int radix) {
			_number = number == null ? null : Pattern.compile(number);
			_radix = radix;
		}

		/**
		 * The number of the given ID of this kind, <code>-1</code> if it has none.
		 */
		public long number(String id) {
			if (_number == null || id == null) {
				return -1;
			}
			Matcher matcher = _number.matcher(id);
			if (!matcher.find()) {
				return -1;
			}
			long result = Long.parseLong(matcher.group(1), _radix);
			if (this == MANUFACTURER) {
				return result > PRIVATE_MANUFACTURER_BASE ? result - PRIVATE_MANUFACTURER_BASE : -1;
			}
			return result;
		}
	}

	private ExtIds() {
		// Utility.
	}

	/**
	 * The kind of ID objects of the type of the given object get; <code>null</code> if ETS does not
	 * identify them.
	 */
	public static Kind kindOf(TLObject obj) {
		switch (TLModelUtil.qualifiedName(obj.tType())) {
			case "ehome.catalog:Manufacturer":
				return Kind.MANUFACTURER;
			case "ehome.catalog:Product":
				return Kind.PRODUCT;
			case "ehome.catalog:Variant":
				return Kind.VARIANT;
			case "ehome.catalog:ProgramAssignment":
				return Kind.ASSIGNMENT;
			case "ehome.catalog:Catalog":
				return obj.tContainer() != null && isA(obj.tContainer(), "ehome.catalog:Catalog") ? Kind.SECTION
					: Kind.CATALOG;
			case "ehome.operational:Program":
				return Kind.PROGRAM;
			case "ehome.operational:VoidType":
			case "ehome.operational:TextType":
			case "ehome.operational:NumericType":
			case "ehome.operational:FloatingPointType":
			case "ehome.operational:EnumerationType":
			case "ehome.operational:TimeType":
			case "ehome.operational:DateType":
			case "ehome.operational:IPAddressType":
			case "ehome.operational:ColorType":
				return Kind.PARAMETER_TYPE;
			case "ehome.operational:EnumerationValue":
				return Kind.ENUMERATION_VALUE;
			case "ehome.operational:Parameter":
				return Kind.PARAMETER;
			case "ehome.operational:ParameterView":
				return Kind.PARAMETER_REF;
			case "ehome.operational:Port":
				return Kind.PORT;
			case "ehome.operational:Interface":
				return Kind.PORT_REF;
			case "ehome.operational:ParameterGroup":
				return Kind.BLOCK;
			case "ehome.operational:Channel":
				return Kind.CHANNEL;
			case "ehome.project:Project":
				return Kind.PROJECT;
			case "ehome.physical:Area":
				return Kind.AREA;
			case "ehome.physical:Line":
				return Kind.LINE;
			case "ehome.physical:Device":
				return Kind.DEVICE;
			case "ehome.logical:GroupRange":
				return Kind.GROUP_RANGE;
			case "ehome.logical:GroupAddress":
				return Kind.GROUP_ADDRESS;
			case "ehome.building:Building":
			case "ehome.building:BuildingPart":
			case "ehome.building:Floor":
			case "ehome.building:Room":
			case "ehome.building:Corridor":
			case "ehome.building:Stairway":
			case "ehome.building:DistributionBoard":
			case "ehome.building:Location":
				return Kind.BUILDING_PART;
			default:
				return null;
		}
	}

	/**
	 * Gives the given object an external ID if it has none, and the objects its ID starts with
	 * first.
	 *
	 * <p>
	 * Must be called in the transaction creating the object: the number is allocated from a
	 * sequence of the database within that transaction.
	 * </p>
	 *
	 * @return The external ID of the object, <code>null</code> if it gets none (see
	 *         {@link #kindOf(TLObject)}), or if the object it belongs to is missing.
	 */
	public static String assign(TLObject obj) {
		String id = extId(obj);
		if (id != null) {
			return id;
		}
		Kind kind = kindOf(obj);
		if (kind == null) {
			return null;
		}
		String result = createId(obj, kind);
		if (result != null) {
			obj.tUpdateByName(EXT_ID, result);
		}
		return result;
	}

	private static String createId(TLObject obj, Kind kind) {
		switch (kind) {
			case MANUFACTURER: {
				Object knxId = obj.tValueByName("id");
				if (knxId instanceof Number number) {
					return "M-" + hex4(number.longValue());
				}
				return unique(null, kind, n -> "M-" + hex4(PRIVATE_MANUFACTURER_BASE + n));
			}
			case PROJECT:
				return unique(null, kind, n -> "P-" + hex4(n));
			case PROGRAM: {
				TLObject manufacturer = obj.tContainer();
				String prefix = ownerId(manufacturer);
				if (prefix == null) {
					return null;
				}
				return unique(manufacturer, kind, n -> prefix + "_A-" + hex4(n) + "-" + PROGRAM_VERSION + "-"
					+ hex4(next(manufacturer, Kind.PROGRAM_HASH)));
			}
			case PRODUCT:
				return numbered(obj.tContainer(), obj.tContainer(), kind, "_H-");
			case VARIANT: {
				TLObject product = obj.tContainer();
				String prefix = ownerId(product);
				if (prefix == null) {
					return null;
				}
				String orderNumber = (String) obj.tValueByName("orderNumber");
				return named(product, kind, prefix + "_P-", orderNumber == null ? null : escape(orderNumber));
			}
			case ASSIGNMENT: {
				TLObject product = obj.tContainer();
				String prefix = ownerId(product);
				if (prefix == null) {
					return null;
				}
				TLObject program = (TLObject) obj.tValueByName("program");
				String programId = program == null ? null : assign(program);
				int application = programId == null ? -1 : programId.indexOf("_A-");
				return named(product, kind, prefix + "_HP-",
					application < 0 ? null : programId.substring(application + "_A-".length()));
			}
			case CATALOG:
				return numbered(obj.tContainer(), obj.tContainer(), kind, "_CS-");
			case SECTION:
				return numbered(obj.tContainer(), obj.tContainer(), kind, "-");
			case PARAMETER_TYPE: {
				TLObject program = obj.tContainer();
				String prefix = ownerId(program);
				if (prefix == null) {
					return null;
				}
				String name = (String) obj.tValueByName("name");
				return named(program, kind, prefix + "_PT-", name == null || name.isBlank() ? null : escape(name));
			}
			case ENUMERATION_VALUE: {
				TLObject type = obj.tContainer();
				String prefix = ownerId(type);
				if (prefix == null) {
					return null;
				}
				Object value = obj.tValueByName("value");
				return named(type, kind, prefix + "_EN-", value == null ? null : value.toString());
			}
			case PARAMETER:
				return numbered(obj.tContainer(), obj.tContainer(), kind, "_P-");
			case PARAMETER_REF:
				return numbered(programOf(obj), (TLObject) obj.tValueByName("parameter"), kind, "_R-");
			case PORT: {
				// ETS numbers a communication object by its object number, if that is free.
				TLObject program = obj.tContainer();
				String prefix = ownerId(program);
				if (prefix == null) {
					return null;
				}
				Object index = obj.tValueByName("index");
				String candidate = prefix + "_O-" + index;
				if (index != null && !ids(members(program, kind)).contains(candidate)) {
					return candidate;
				}
				return numbered(program, program, kind, "_O-");
			}
			case PORT_REF:
				return numbered(programOf(obj), (TLObject) obj.tValueByName("port"), kind, "_R-");
			case BLOCK:
				return numbered(programOf(obj), programOf(obj), kind, "_PB-");
			case CHANNEL:
				return numbered(programOf(obj), programOf(obj), kind, "_CH-");
			case AREA:
				return projectPart(obj, kind, "_A-");
			case LINE:
				return projectPart(obj, kind, "_L-");
			case DEVICE:
				return projectPart(obj, kind, "_DI-");
			case GROUP_RANGE:
				return projectPart(obj, kind, "_GR-");
			case GROUP_ADDRESS:
				return projectPart(obj, kind, "_GA-");
			case BUILDING_PART:
				return projectPart(obj, kind, "_BP-");
			case PROGRAM_HASH:
				break;
		}
		return null;
	}

	private static String projectPart(TLObject obj, Kind kind, String tag) {
		TLObject project = ancestor(obj, "ehome.project:Project");
		String prefix = ownerId(project);
		if (prefix == null) {
			return null;
		}
		return numbered(project, project, kind, INSTALLATION + tag);
	}

	/**
	 * <code>&lt;owner ID&gt;&lt;tag&gt;&lt;number&gt;</code>, the number from the scope's sequence.
	 */
	private static String numbered(TLObject scope, TLObject owner, Kind kind, String tag) {
		String prefix = ownerId(owner);
		if (scope == null || prefix == null) {
			return null;
		}
		return unique(scope, kind, n -> prefix + tag + n);
	}

	/**
	 * <code>&lt;prefix&gt;&lt;name&gt;</code>, unless that is taken in the scope: then with a number
	 * appended; without name, <code>&lt;prefix&gt;&lt;number&gt;</code>.
	 */
	private static String named(TLObject scope, Kind kind, String prefix, String name) {
		if (name == null || name.isEmpty()) {
			return unique(scope, kind, n -> prefix + n);
		}
		String candidate = prefix + name;
		if (!ids(members(scope, kind)).contains(candidate)) {
			return candidate;
		}
		return unique(scope, kind, n -> candidate + "-" + n);
	}

	/**
	 * An ID from the given pattern with the next number of the scope that no other member of the
	 * scope has.
	 */
	private static String unique(TLObject scope, Kind kind, LongFunction<String> pattern) {
		Collection<String> taken = ids(members(scope, kind));
		while (true) {
			String candidate = pattern.apply(next(scope, kind));
			if (!taken.contains(candidate)) {
				return candidate;
			}
		}
	}

	private static Collection<String> ids(Collection<TLObject> objects) {
		List<String> result = new ArrayList<>(objects.size());
		for (TLObject obj : objects) {
			String id = extId(obj);
			if (id != null) {
				result.add(id);
			}
		}
		return result;
	}

	/**
	 * The next number of the given kind in the given scope: above every number of that kind in the
	 * scope.
	 *
	 * @param scope
	 *        The object numbering, <code>null</code> for numbers unique in the whole application
	 *        (manufacturers, projects).
	 */
	public static long next(TLObject scope, Kind kind) {
		raise(scope, kind);
		try {
			PooledConnection connection = connection();
			return SEQUENCES.nextSequenceNumber(connection.getSQLDialect(), connection, RETRY_COUNT,
				sequenceName(scope, kind));
		} catch (SQLException ex) {
			throw new KnowledgeBaseRuntimeException("Cannot allocate a number for " + kind + ".", ex);
		}
	}

	/**
	 * Raises the sequence of the given kind in the given scope to the highest number of that kind
	 * the scope has, if it is lower.
	 *
	 * <p>
	 * The sequence manager has no setter for an existing sequence that creates a missing one, so the
	 * sequence is raised by the difference ({@link SequenceManager#nextSequenceNumber(com.top_logic.basic.sql.DBHelper, java.sql.Connection, int, String, int)}
	 * with an increment), which creates it if missing.
	 * </p>
	 *
	 * @return The value of the sequence afterwards.
	 */
	public static long raise(TLObject scope, Kind kind) {
		long max = -1;
		for (TLObject member : members(scope, kind)) {
			max = Math.max(max, kind.number(extId(member)));
		}
		try {
			PooledConnection connection = connection();
			String sequence = sequenceName(scope, kind);
			long last = Math.max(0, SEQUENCES.getLastSequenceNumber(connection, RETRY_COUNT, sequence));
			if (max <= last) {
				return last;
			}
			return SEQUENCES.nextSequenceNumber(connection.getSQLDialect(), connection, RETRY_COUNT, sequence,
				Math.toIntExact(max - last));
		} catch (SQLException ex) {
			throw new KnowledgeBaseRuntimeException("Cannot raise the numbers of " + kind + ".", ex);
		}
	}

	/**
	 * The current value of the sequence of the given kind in the given scope, <code>0</code> if
	 * nothing was numbered there yet.
	 */
	public static long current(TLObject scope, Kind kind) {
		try {
			return Math.max(0,
				SEQUENCES.getLastSequenceNumber(connection(), RETRY_COUNT, sequenceName(scope, kind)));
		} catch (SQLException ex) {
			throw new KnowledgeBaseRuntimeException("Cannot read the numbers of " + kind + ".", ex);
		}
	}

	/**
	 * Raises the sequences of every scope the given imported objects were numbered in (see
	 * {@link #raise(TLObject, Kind)}), so that numbers allocated afterwards are higher than the
	 * imported ones.
	 */
	public static void raiseImported(Collection<?> imported) {
		Map<String, Map.Entry<TLObject, Kind>> scopes = new LinkedHashMap<>();
		for (Object obj : imported) {
			if (!(obj instanceof TLObject object) || !object.tValid()) {
				continue;
			}
			Kind kind = kindOf(object);
			if (kind == null || kind.number(extId(object)) < 0) {
				continue;
			}
			TLObject scope = scopeOf(object, kind);
			if (scope == null && kind != Kind.MANUFACTURER && kind != Kind.PROJECT) {
				continue;
			}
			scopes.putIfAbsent(sequenceName(scope, kind), Map.entry(scope == null ? object : scope, kind));
		}
		for (Map.Entry<TLObject, Kind> entry : scopes.values()) {
			Kind kind = entry.getValue();
			raise(kind == Kind.MANUFACTURER || kind == Kind.PROJECT ? null : entry.getKey(), kind);
		}
	}

	/**
	 * The object numbering the given object (see {@link #next(TLObject, Kind)}).
	 */
	public static TLObject scopeOf(TLObject obj, Kind kind) {
		switch (kind) {
			case MANUFACTURER:
			case PROJECT:
				return null;
			case PARAMETER_REF:
			case PORT_REF:
			case BLOCK:
			case CHANNEL:
				return programOf(obj);
			case AREA:
			case LINE:
			case DEVICE:
			case GROUP_RANGE:
			case GROUP_ADDRESS:
			case BUILDING_PART:
				return ancestor(obj, "ehome.project:Project");
			default:
				return obj.tContainer();
		}
	}

	/**
	 * The objects numbered in the given scope with the given kind.
	 */
	public static Collection<TLObject> members(TLObject scope, Kind kind) {
		switch (kind) {
			case MANUFACTURER: {
				TLModule module = TLModelUtil.findType("ehome.catalog:ManufacturerRoot").getModule();
				return list(module.getSingleton(TLModule.DEFAULT_SINGLETON_NAME), "manufacturers");
			}
			case PROJECT:
				return MetaElementUtil.getAllDirectInstancesOf(
					(TLClass) TLModelUtil.findType("ehome.project:Project"), TLObject.class);
			default:
				break;
		}
		if (scope == null) {
			return Collections.emptyList();
		}
		switch (kind) {
			case PROGRAM:
			case PROGRAM_HASH:
				return list(scope, "programs");
			case PRODUCT:
				return list(scope, "products");
			case VARIANT:
				return list(scope, "variants");
			case ASSIGNMENT:
				return list(scope, "programs");
			case CATALOG:
				return list(scope, "catalogs");
			case SECTION:
				return list(scope, "sections");
			case PARAMETER_TYPE:
				return list(scope, "parameterTypes");
			case ENUMERATION_VALUE:
				return list(scope, "values");
			case PARAMETER:
				return list(scope, "parameters");
			case PARAMETER_REF:
				return flat(list(scope, "parameters"), "views");
			case PORT:
				return list(scope, "ports");
			case PORT_REF:
				return flat(list(scope, "ports"), "interfaces");
			case BLOCK:
				return descendants(scope, "ehome.operational:ParameterGroup", "contents", "alternatives");
			case CHANNEL:
				return descendants(scope, "ehome.operational:Channel", "contents", "alternatives");
			case AREA:
				return list(scope, "areas");
			case LINE:
				return flat(list(scope, "areas"), "lines");
			case DEVICE: {
				List<TLObject> result = flat(flat(list(scope, "areas"), "lines"), "devices");
				result.addAll(list(scope, "unassignedDevices"));
				return result;
			}
			case GROUP_RANGE:
				return descendants(scope, "ehome.logical:GroupRange", "groupAddresses", "parts");
			case GROUP_ADDRESS:
				return descendants(scope, "ehome.logical:GroupAddress", "groupAddresses", "parts");
			case BUILDING_PART:
				return descendants(scope, "ehome.building:BuildingDefinition", "buildings", "parts");
			default:
				return Collections.emptyList();
		}
	}

	/**
	 * The application program the given element of a program belongs to.
	 */
	public static TLObject programOf(TLObject obj) {
		TLObject program = ancestor(obj, "ehome.operational:Program");
		if (program != null) {
			return program;
		}
		// A reference not placed yet: the program of what it places.
		for (String reference : List.of("parameter", "port")) {
			if (obj.tType().getPart(reference) != null) {
				Object placed = obj.tValueByName(reference);
				if (placed instanceof TLObject definition) {
					return ancestor(definition, "ehome.operational:Program");
				}
			}
		}
		return null;
	}

	/**
	 * The given object or its first container of the given type.
	 */
	public static TLObject ancestor(TLObject obj, String typeName) {
		for (TLObject current = obj; current != null; current = current.tContainer()) {
			if (isA(current, typeName)) {
				return current;
			}
		}
		return null;
	}

	/**
	 * Whether the given object is an instance of the class with the given qualified name (the class
	 * itself or a specialization of it).
	 *
	 * <p>
	 * Compares the names of the object's class and its generalizations: resolving the name to a type
	 * (<code>TLModelUtil.findType</code>) and the compatibility check of the engine look up the module
	 * by its name among all modules on every call (TL #29890), which is expensive where it is asked for every
	 * object of a program.
	 * </p>
	 */
	public static boolean isA(TLObject obj, String typeName) {
		if (obj == null || !(obj.tType() instanceof TLClass type)) {
			return false;
		}
		int separator = typeName.indexOf(':');
		String moduleName = typeName.substring(0, separator);
		String localName = typeName.substring(separator + 1);
		// The generalizations from the model cache of the engine.
		for (TLClass generalization : TLModelUtil.getReflexiveTransitiveGeneralizations(type)) {
			if (generalization.getName().equals(localName)
				&& generalization.getModule().getName().equals(moduleName)) {
				return true;
			}
		}
		return false;
	}

	private static String ownerId(TLObject owner) {
		return owner == null ? null : assign(owner);
	}

	/**
	 * The external ID of the given object.
	 */
	public static String extId(TLObject obj) {
		if (obj.tType().getPart(EXT_ID) == null) {
			return null;
		}
		return (String) obj.tValueByName(EXT_ID);
	}

	/**
	 * The objects the given reference of the given object holds (none if the object has no such
	 * reference).
	 */
	public static List<TLObject> list(TLObject obj, String reference) {
		if (obj == null || obj.tType().getPart(reference) == null) {
			return new ArrayList<>();
		}
		Object value = obj.tValueByName(reference);
		List<TLObject> result = new ArrayList<>();
		if (value instanceof Collection<?> collection) {
			for (Object element : collection) {
				result.add((TLObject) element);
			}
		} else if (value instanceof TLObject single) {
			result.add(single);
		}
		return result;
	}

	private static List<TLObject> flat(List<TLObject> objects, String reference) {
		List<TLObject> result = new ArrayList<>();
		for (TLObject obj : objects) {
			result.addAll(list(obj, reference));
		}
		return result;
	}

	private static List<TLObject> descendants(TLObject root, String typeName, String... references) {
		List<TLObject> result = new ArrayList<>();
		List<TLObject> todo = new ArrayList<>();
		for (String reference : references) {
			todo.addAll(list(root, reference));
		}
		while (!todo.isEmpty()) {
			TLObject current = todo.remove(todo.size() - 1);
			if (isA(current, typeName)) {
				result.add(current);
			}
			for (String reference : references) {
				todo.addAll(list(current, reference));
			}
		}
		return result;
	}

	private static String sequenceName(TLObject scope, Kind kind) {
		return "ehome.extId." + kind.name() + (scope == null ? "" : "." + scope.tId().getObjectName());
	}

	private static PooledConnection connection() {
		return ((CommitHandler) PersistencyLayer.getKnowledgeBase()).createCommitContext().getConnection();
	}

	/**
	 * Four hex digits (more if needed), as ETS writes manufacturer, project and application numbers.
	 */
	public static String hex4(long number) {
		return String.format("%04X", Long.valueOf(number));
	}

	/**
	 * A text as part of an ID, as ETS writes it: letters and digits stay, every other character is
	 * written as its UTF-8 bytes, each as <code>.</code> and two hex digits (<code>"5133 00"</code>
	 * becomes <code>5133.2000</code>, <code>"1°C"</code> becomes <code>1.C2.B0C</code>).
	 */
	public static String escape(String text) {
		StringBuilder result = new StringBuilder();
		for (byte b : Objects.requireNonNull(text).getBytes(StandardCharsets.UTF_8)) {
			char c = (char) (b & 0xFF);
			if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
				result.append(c);
			} else {
				result.append('.').append(String.format("%02X", Integer.valueOf(c)));
			}
		}
		return result.toString();
	}

}
