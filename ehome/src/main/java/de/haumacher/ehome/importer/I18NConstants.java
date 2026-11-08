/*
 * Copyright (c) 2018 Bernhard Haumacher
 */
package de.haumacher.ehome.importer;

import com.top_logic.basic.util.ResKey;
import com.top_logic.basic.util.ResKey1;
import com.top_logic.basic.util.ResKey2;
import com.top_logic.basic.util.ResKey3;
import com.top_logic.basic.util.ResKey4;
import com.top_logic.basic.util.ResKey5;
import com.top_logic.layout.I18NConstantsBase;

/**
 * Internationalization constants for this package.
 */
public class I18NConstants extends I18NConstantsBase {

	/**
	 * @en Error in file "{0}" line {1} column {2}: {3}
	 */
	public static ResKey ERROR_PROBLEM__FILE_LINE_COL_MSG;

	/**
	 * @en Error: No linking.
	 */
	public static ResKey ERROR_NO_LINKING;

	/**
	 * @en Error: Invalid part reference: {0}
	 */
	public static ResKey1 ERROR_INVALID_PART_REF__VALUE;

	/**
	 * @en Error: Cannot resolve part reference "{0}": {1}
	 */
	public static ResKey2 ERROR_UNRESOLVED_REFERENCE__ID_REF;

	/**
	 * @en Error: Cannot resolve ID: {0}
	 */
	public static ResKey1 ERROR_UNRESOLVED_ID__ID;

	/**
	 * @en Error: Duplicate ID: {0}
	 */
	public static ResKey1 ERROR_DUPLICATE_IDENTIFIER__ID;

	/**
	 * @en Error: No target for linking.
	 */
	public static ResKey ERROR_LINKING_NULL_TARGET;

	/**
	 * @en Error: No value for linking.
	 */
	public static ResKey ERROR_LINKING_NULL_VALUE;

	/**
	 * @en Parameter "{0}": {1}
	 */
	public static ResKey2 WARN_PARAMETER__NAME_PROBLEM;

	/**
	 * @en The file cannot be read: it holds an encrypted (password protected) entry, which follows the entry "{0}". Only unencrypted files can be imported.
	 */
	public static ResKey1 ERROR_ENCRYPTED_ENTRY__AFTER;

	/**
	 * @en The file is no readable ZIP archive: {0}
	 */
	public static ResKey1 ERROR_INVALID_ARCHIVE__DETAIL;

	/**
	 * @en The product file holds a project (entry "{0}"). Import a project file (.knxproj) in the projects view.
	 */
	public static ResKey1 ERROR_PROJECT_IN_PRODUCT_DATA__ENTRY;

	/**
	 * @en The product file holds baggage (entry "{0}"): pictures, help or plug-in files of ETS 5 or 6, which E-Home cannot import. Only product files without baggage can be imported.
	 */
	public static ResKey1 ERROR_BAGGAGES_IN_PRODUCT_DATA__ENTRY;

	/**
	 * @en The file holds no product data of a manufacturer (no folder "M-xxxx").
	 */
	public static ResKey ERROR_NO_MANUFACTURER_DATA;

	/**
	 * @en The application program "{0}" uses dynamic modules (ETS 5.6 and later) with "{1}", which E-Home cannot interpret: its modules are not imported, and a download of the program is refused.
	 */
	public static ResKey2 WARN_MODULES_NOT_READ__PROGRAM_CONSTRUCT;

	/**
	 * @en The application program "{0}", imported earlier without its dynamic modules, is completed: its module definitions and their placements are added. Import the projects of its devices again to give them their module instances, settings and group addresses.
	 */
	public static ResKey1 INFO_MODULES_COMPLETED__PROGRAM;

	/**
	 * @en The application program "{0}", imported earlier without its parameter calculations, is completed: its calculations are added. The devices of the program imported before keep their values; a change of a parameter computes the others from now on.
	 */
	public static ResKey1 INFO_CALCULATIONS_COMPLETED__PROGRAM;

	/**
	 * @en The application program "{0}" was imported earlier without its dynamic modules, and its structure in the database does not fit the file: the modules are not added, and a download of the program stays refused.
	 */
	public static ResKey1 WARN_MODULES_NOT_COMPLETED__PROGRAM;

	/**
	 * @en Device "{0}": {1} parameter settings and {2} communication objects refer to parts of its application program that were not found (e.g. of dynamic modules the import could not read, or a part a module does not have); they are left out.
	 */
	public static ResKey3 WARN_UNRESOLVED_PROGRAM_PARTS__DEVICE_SETTINGS_OBJECTS;

	/**
	 * @en Program {0}: E-Home cannot evaluate the parameter calculation {1} ({2}). Its parameters cannot be changed in E-Home; the devices keep the values of the project file.
	 */
	public static ResKey3 WARN_CALCULATION_NOT_EVALUATED__PROGRAM_CALCULATION_PROBLEM;

	/**
	 * @en Device "{0}": the values of a parameter calculation could not be checked, the device keeps the values of the project file: {1}
	 */
	public static ResKey2 WARN_CALCULATION_KEPT__DEVICE_PROBLEM;

	/**
	 * @en The application program "{0}", imported earlier without its parameter validations, is completed: its validations are added. They check the values entered from now on.
	 */
	public static ResKey1 INFO_VALIDATIONS_COMPLETED__PROGRAM;

	/**
	 * @en Program {0}: E-Home cannot evaluate the parameter validation {1} ({2}). Its parameters cannot be changed in E-Home; the devices keep the values of the project file.
	 */
	public static ResKey3 WARN_VALIDATION_NOT_EVALUATED__PROGRAM_VALIDATION_PROBLEM;

	/**
	 * @en Device "{0}": the values of the project file do not pass a check of the manufacturer, the device keeps them: {1}
	 */
	public static ResKey2 WARN_VALIDATION_FAILED__DEVICE_PROBLEM;

	/**
	 * @en Device "{0}", module instance {1}: the project file gives the argument {2} the value "{3}", E-Home computes "{4}". The device''s memory was written with the other layout: a download of the device is refused until a full download you confirm writes E-Home''s layout.
	 */
	public static ResKey5 WARN_ARGUMENT_DIFFERS__DEVICE_INSTANCE_ARGUMENT_FILE_EHOME;

	/**
	 * @en Device "{0}": {1}
	 */
	public static ResKey2 WARN_MODULE_LAYOUT__DEVICE_PROBLEM;

	/**
	 * @en The named value "{0}" of a datapoint type is not a number: {1}
	 */
	public static ResKey2 WARN_NAMED_VALUE_NOT_A_NUMBER__ID_VALUE;

	/**
	 * @en The project "{0}" is protected by a password. Enter the project password to import it.
	 */
	public static ResKey1 ERROR_PASSWORD_REQUIRED__ENTRY;

	/**
	 * @en The password does not open the protected project "{0}". Check the project password.
	 */
	public static ResKey1 ERROR_WRONG_PASSWORD__ENTRY;

	/**
	 * @en The project "{0}" cannot be read: {1}
	 */
	public static ResKey2 ERROR_INVALID_PROJECT_ARCHIVE__ENTRY_DETAIL;

	/**
	 * @en {0} is defined differently in the master data of version {1} than in those of version {2}, from which E-Home took its definition. The definition of version {2} is kept.
	 */
	public static ResKey3 WARN_MASTER_DATA_DIFFER__OBJECT_VERSION_KNOWN;

	/**
	 * @en {0} is updated from its definition in the master data of version {1} to the one in the master data of version {2}.
	 */
	public static ResKey3 INFO_MASTER_DATA_UPDATED__OBJECT_KNOWN_VERSION;

	/**
	 * An object of the master data in the messages of {@link #INFO_MASTER_DATA_UPDATED__OBJECT_KNOWN_VERSION}
	 * and {@link #WARN_MASTER_DATA_DIFFER__OBJECT_VERSION_KNOWN}: the label of its type, its name and its ID.
	 *
	 * @en {0} "{1}" ({2})
	 */
	public static ResKey3 MASTER_DATA_OBJECT__TYPE_NAME_ID;

	/**
	 * An object of the master data without a name, see {@link #MASTER_DATA_OBJECT__TYPE_NAME_ID}.
	 *
	 * @en {0} {1}
	 */
	public static ResKey2 MASTER_DATA_OBJECT__TYPE_ID;

	/**
	 * @en Master data
	 */
	public static ResKey PROGRESS_MASTER_DATA;

	/**
	 * @en Manufacturer {0}: application program {1} ({2} of {3})
	 */
	public static ResKey4 PROGRESS_PROGRAM__MANUFACTURER_PROGRAM_NUMBER_COUNT;

	/**
	 * @en Manufacturer {0}: application program {1} already known
	 */
	public static ResKey2 PROGRESS_PROGRAM_KNOWN__MANUFACTURER_PROGRAM;

	/**
	 * @en Hardware and catalog of {0}
	 */
	public static ResKey1 PROGRESS_HARDWARE__MANUFACTURER;

	/**
	 * @en Project {0}
	 */
	public static ResKey1 PROGRESS_PROJECT__NAME;

	static {
		initConstants(I18NConstants.class);
	}
}
