/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.projectfile;

import java.util.Set;

import com.top_logic.model.TLObject;
import com.top_logic.model.TLReference;
import com.top_logic.model.TLStructuredTypePart;
import com.top_logic.model.util.TLModelUtil;

import de.haumacher.ehome.knx.ProjectParts;
import de.haumacher.ehome.modules.ModuleParts;

/**
 * The project file <code>&lt;name&gt;.ehome</code> (haumacher/ehome #117): a whole project, to move it
 * to another E-Home installation or to back it up, without secrets and without operating history.
 *
 * <p>
 * The file is a zip archive of two entries:
 * </p>
 * <ul>
 * <li>{@value #KNXPROJ_ENTRY}: the KNX part as the export as <code>.knxproj</code> writes it
 * (<code>doc/knx/export.md</code>): topology, devices with their parameters, group addresses,
 * buildings, the master data and programs they need.</li>
 * <li>{@value #JSON_ENTRY}: everything else, in the terms of the model: the project's own attributes
 * and its compositions (rules, schedule entries, application datapoints, virtual devices, functions,
 * control pages, Zigbee devices, dismissed proposals, the simulation's followers, the controllers),
 * the settings E-Home keeps per group address, and the attributes of devices schema 11 of the
 * <code>.knxproj</code> has no place for (the channels of a device with their descriptions, ETS 6:
 * <code>ChannelInstances</code>, haumacher/ehome #122).</li>
 * </ul>
 *
 * <p>
 * The JSON document:
 * </p>
 *
 * <pre>
 * {
 *   "format": "ehome-project",
 *   "version": "1.0",
 *   "exported": "2026-11-08T10:00:00Z",
 *   "project": {
 *     "id": "P-0123", "name": "Test home",
 *     "timeZone": "CET-1CEST,M3.5.0,M10.5.0/3", "latitude": 49.45, "longitude": 11.08,
 *     "timeMasterTime": {"ga": "0/0/50"},
 *     "controller": "controllers/1",
 *     "controllers": [{"$id": "controllers/1", "name": "Flur", "host": "192.168.178.20", "port": 80}],
 *     "rules": [{"identifier": "frost", "name": {"de": "Frostschutz", "en": "Frost protection"},
 *       "reactions": [{"triggers": [{"$type": "ehome.runtime:DatapointTrigger", "datapoint": {"ga": "0/0/28"}}],
 *         "actions": [{"$type": "ehome.runtime:WriteAction", "datapoint": {"ga": "0/0/46"}, "value": "true"}]}]}],
 *     "applicationDatapoints": [{"identifier": "steckdose.on", "type": "boolean",
 *       "zigbeeFunction": "zigbeeDevices/001788010f069c8e/functions/on_off@11"}],
 *     "zigbeeDevices": [{"ieee": "001788010f069c8e", "location": {"knx": "P-0123-0_BP-3"},
 *       "functions": [{"$id": "zigbeeDevices/001788010f069c8e/functions/on_off@11", "function": "on_off", "endpoint": 11}]}]
 *   },
 *   "groupAddresses": [{"ga": "0/0/6", "runtimeWritable": true}],
 *   "devices": [{"knx": "P-0123-0_DI-1",
 *     "channelInstances": [{"extId": "P-0123-0_DI-1_CI-1", "channel": "CH-4", "description": "Küchentür", "active": true}]}]
 * }
 * </pre>
 *
 * <ul>
 * <li>An object is a JSON object of its attributes by their names in the model; derived attributes
 * and backward references are left out, as are attributes without a value (a truth value that is
 * false is written: an attribute with a default value is true on a new object). The parts of a composite
 * reference are written in place. <code>$type</code> names the type of an object (qualified,
 * <code>module:Type</code>) where it is not the type of the reference holding it.</li>
 * <li>Values: truth values and numbers as JSON values, texts as strings, multilingual texts as an
 * object of their texts by language, the classifiers of an enumeration by their names, other values
 * in the text form of the engine's instance export (<code>XMLInstanceExporter#serialize</code>).</li>
 * <li>References, by stable IDs: an object of the file by its <code>$id</code> (written only where it
 * is referenced), a path of the composite references and the object's key within them
 * (<code>identifier</code>, the IEEE address of a Zigbee device, the port or parameter of a virtual
 * device, function and endpoint of a Zigbee function, else the position); a group address by its
 * address in three levels (<code>{"ga": "0/0/40"}</code>); another object of the KNX part by its
 * external ID (<code>{"knx": "P-0123-0_BP-3"}</code>), as the <code>.knxproj</code> writes it (a
 * communication object of a device, which has no ID of its own, as
 * <code>&lt;device&gt;/&lt;object of the program&gt;</code>, see {@link #knxKey(TLObject)}); a
 * controller on the LAN that no project holds by its name, host and port
 * (<code>{"lan": {...}}</code>).</li>
 * <li><code>project.id</code> and <code>project.name</code> repeat what the <code>.knxproj</code>
 * holds, so the import can check the ID before it reads the archive.</li>
 * </ul>
 *
 * <p>
 * <b>Version.</b> <code>version</code> is <code>major.minor</code>. A file of a newer major version
 * is refused; one of a newer minor version is imported, attributes the model does not know are named
 * in the report and skipped. A change that makes older files unreadable or changes the meaning of an
 * attribute (a renamed attribute, a moved type) raises the major version; added attributes raise
 * the minor one.
 * </p>
 *
 * <p>
 * The split between the KNX part (the <code>.knxproj</code>) and E-Home's own data (the document) is
 * the one of {@link ProjectParts}, which the update of a project from ETS uses as well
 * (haumacher/ehome #125); the document carries besides what ETS owns but schema 11 has no place for.
 * </p>
 *
 * <p>
 * <b>Left out</b> ({@link #leftOut(TLStructuredTypePart)}): the secrets (the installation keys, the
 * enrollment code, of a controller everything but its name, host and port: its connection id and
 * secret, its HTTP token, and what it reported), and the operating history (the recordings of the
 * monitor, the deployments, the revision of the last image, the devices a scan did not identify, the
 * simulation's overrides of rules and parameters, the revision of a device's last download, which is
 * a revision of the database it was made in).
 * </p>
 */
public final class ProjectFile {

	/**
	 * The extension of a project file.
	 */
	public static final String EXTENSION = ".ehome";

	/**
	 * The content type of a project file (a zip archive).
	 */
	public static final String CONTENT_TYPE = "application/zip";

	/**
	 * The entry holding the KNX part as <code>.knxproj</code>.
	 */
	public static final String KNXPROJ_ENTRY = "project.knxproj";

	/**
	 * The entry holding the JSON document.
	 */
	public static final String JSON_ENTRY = "ehome.json";

	/**
	 * The value of {@value #FORMAT_MEMBER}.
	 */
	public static final String FORMAT = "ehome-project";

	/**
	 * The major version of the format this version of E-Home writes and reads.
	 */
	public static final int MAJOR = 1;

	/**
	 * The minor version of the format this version of E-Home writes.
	 */
	public static final int MINOR = 1;

	/** Member of the document naming the format. */
	public static final String FORMAT_MEMBER = "format";

	/** Member of the document holding the version, <code>major.minor</code>. */
	public static final String VERSION_MEMBER = "version";

	/** Member of the document holding the time of the export. */
	public static final String EXPORTED_MEMBER = "exported";

	/** Member of the document holding the project. */
	public static final String PROJECT_MEMBER = "project";

	/** Member of the document holding the settings of the group addresses. */
	public static final String GROUP_ADDRESSES_MEMBER = "groupAddresses";

	/** Member of the document holding the attributes of devices the <code>.knxproj</code> does not hold (version 1.1). */
	public static final String DEVICES_MEMBER = "devices";

	/** Member of the project holding its external ID. */
	public static final String ID_MEMBER = "id";

	/** Member of the project holding its name. */
	public static final String NAME_MEMBER = "name";

	/** Member of an object holding its ID, where it is referenced. */
	public static final String OBJECT_ID = "$id";

	/** Member of an object holding its type, where it is not the type of its reference. */
	public static final String OBJECT_TYPE = "$type";

	/** Member of a reference to (and of the settings of) a group address: its address. */
	public static final String GA = "ga";

	/** Member of a reference to an object of the KNX part: its external ID. */
	public static final String KNX = "knx";

	/** Member of a reference to a controller on the LAN no project holds. */
	public static final String LAN = "lan";

	/** The type of a project. */
	public static final String PROJECT_TYPE = "ehome.project:Project";

	/** The type of a group address. */
	public static final String GROUP_ADDRESS_TYPE = "ehome.logical:GroupAddress";

	/** The type of a device. */
	public static final String DEVICE_TYPE = "ehome.physical:Device";

	/** The type of a controller. */
	public static final String CONTROLLER_TYPE = "ehome.runtime:Controller";

	/**
	 * The attributes ETS owns that the <code>.knxproj</code> of schema 11 has no place for: written
	 * into the document (haumacher/ehome #122).
	 */
	private static final Set<String> NOT_IN_KNXPROJ = Set.of("ehome.physical:Device#channelInstances");

	/**
	 * The attributes of a controller the file holds.
	 */
	private static final Set<String> CONTROLLER_KEPT = Set.of("name", "host", "port");

	/**
	 * Attributes left out: secrets and operating history, by the type defining them.
	 */
	private static final Set<String> LEFT_OUT = Set.of(
		// Secrets.
		"ehome.project:Project#enrollmentCode",
		"ehome.project:Project#enrollmentExpires",
		"ehome.project:Project#enrollmentCreatedBy",
		"ehome.project:Project#installationKey",
		"ehome.project:Project#installationPublicKey",
		"ehome.project:Project#newInstallationKey",
		"ehome.project:Project#newInstallationPublicKey",
		// Operating history (besides the references of ProjectParts#isHistory).
		"ehome.project:Project#imageRevision",
		"ehome.runtime:Rule#simulationEnabled",
		"ehome.runtime:Rule#simulationState",
		"ehome.runtime:RuleParameter#simulationValue",
		"ehome.physical:Device#downloadRevision");

	private ProjectFile() {
		// Constants and rules only.
	}

	/**
	 * The version this E-Home writes.
	 */
	public static String version() {
		return MAJOR + "." + MINOR;
	}

	/**
	 * Whether the given attribute is never written: a derived attribute, a backward reference, a
	 * secret or operating history.
	 */
	public static boolean leftOut(TLStructuredTypePart part) {
		if (part.isDerived() || part instanceof TLReference reference && reference.isBackwards()) {
			return true;
		}
		String owner = TLModelUtil.qualifiedName(part.getDefinition().getOwner());
		if (owner.equals(CONTROLLER_TYPE)) {
			return !CONTROLLER_KEPT.contains(part.getName());
		}
		return LEFT_OUT.contains(owner + "#" + part.getName()) || ProjectParts.isHistory(part);
	}

	/**
	 * Whether the given attribute of a project is held by the <code>.knxproj</code>.
	 */
	public static boolean projectKnx(TLStructuredTypePart part) {
		return ProjectParts.isEtsOwned(part);
	}

	/**
	 * Whether the given attribute of a group address is a setting of E-Home (not held by the
	 * <code>.knxproj</code>).
	 */
	public static boolean groupAddressSetting(TLStructuredTypePart part) {
		return !ProjectParts.isEtsOwned(part) && !leftOut(part);
	}

	/**
	 * Whether the given attribute of a device is written into the document (not held by the
	 * <code>.knxproj</code>).
	 */
	public static boolean deviceSetting(TLStructuredTypePart part) {
		return (!ProjectParts.isEtsOwned(part) || NOT_IN_KNXPROJ.contains(ProjectParts.qualifiedName(part)))
			&& !leftOut(part);
	}

	/**
	 * The key of an object of the KNX part in a reference to it ({@link #KNX}): its external ID; for
	 * a communication object of a device, which has none, the external ID of the device and of the
	 * program's communication object it instantiates (<code>&lt;device&gt;/&lt;object&gt;</code>).
	 *
	 * @return The key, <code>null</code> if the object has none.
	 */
	public static String knxKey(TLObject obj) {
		if (obj.tType().getPart("extId") != null && obj.tValueByName("extId") instanceof String extId) {
			return extId;
		}
		if (obj.tType().getPart("port") != null && obj.tValueByName("port") instanceof TLObject
			&& obj.tContainer() != null) {
			String device = knxKey(obj.tContainer());
			// An object of a module instance by the ID of the instance's part (haumacher/ehome #119).
			String object = ModuleParts.partId(obj, ModuleParts.PORT);
			return device == null || object == null ? null : device + "/" + object;
		}
		return null;
	}

	/**
	 * The references of a project leading into its KNX part.
	 */
	public static Set<String> knxReferences() {
		return ProjectParts.PROJECT_REFERENCES;
	}

}
