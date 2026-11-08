/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.projectfile;

import com.top_logic.basic.util.ResKey;
import com.top_logic.basic.util.ResKey1;
import com.top_logic.basic.util.ResKey2;
import com.top_logic.basic.util.ResKey3;
import com.top_logic.layout.I18NConstantsBase;

/**
 * Internationalization constants for this package.
 */
public class I18NConstants extends I18NConstantsBase {

	/**
	 * @en Export failed: {0}
	 */
	public static ResKey1 ERROR_EXPORT_FAILED__DETAIL;

	/**
	 * @en The project cannot be exported: the attribute {0} of {1} refers to {2}, which belongs neither to the project nor to its KNX part.
	 */
	public static ResKey3 ERROR_REFERENCE_OUTSIDE__ATTRIBUTE_OBJECT_TARGET;

	/**
	 * @en The file is not an E-Home project file (.ehome): {0}
	 */
	public static ResKey1 ERROR_NOT_A_PROJECT_FILE__DETAIL;

	/**
	 * @en The file was written by a newer E-Home in format version {0}; this E-Home reads the format up to version {1}.x. Update E-Home to import the file.
	 */
	public static ResKey2 ERROR_NEWER_VERSION__VERSION_MAJOR;

	/**
	 * @en The file names an unknown type {0} at {1}.
	 */
	public static ResKey2 ERROR_UNKNOWN_TYPE__TYPE_PATH;

	/**
	 * @en The value at {0} cannot be read: {1}
	 */
	public static ResKey2 ERROR_INVALID_VALUE__PATH_DETAIL;

	/**
	 * @en The installation already has a project with the ID {0} ({1}). The imported project has the same ID: both would deploy to the same controllers.
	 */
	public static ResKey2 WARN_SAME_PROJECT_ID__ID_PROJECTS;

	/**
	 * @en The file holds no secrets: the project has no installation key, its controllers no connection secrets and no HTTP tokens. Pair the controllers again (with an enrollment code, or by entering the HTTP token again) before deploying.
	 */
	public static ResKey INFO_PAIR_CONTROLLERS_AGAIN;

	/**
	 * @en The model does not know the attribute {0} at {1} (written by a newer E-Home); it is skipped.
	 */
	public static ResKey2 WARN_UNKNOWN_ATTRIBUTE__ATTRIBUTE_PATH;

	/**
	 * @en The reference at {0} names {1}, which the project does not have; it is left empty.
	 */
	public static ResKey2 WARN_UNRESOLVED_REFERENCE__PATH_TARGET;

	/**
	 * @en The group address {0} of the settings in the file is not in the project; its settings are skipped.
	 */
	public static ResKey1 WARN_UNKNOWN_GROUP_ADDRESS__ADDRESS;

	/**
	 * @en The project''s controller on the LAN {0} ({1}) was not known in this installation and has been created; enter its HTTP token again.
	 */
	public static ResKey2 INFO_LAN_CONTROLLER_CREATED__NAME_ADDRESS;

	static {
		initConstants(I18NConstants.class);
	}
}
