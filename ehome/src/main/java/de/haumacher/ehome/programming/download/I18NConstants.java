/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.programming.download;

import com.top_logic.basic.util.ResKey;
import com.top_logic.basic.util.ResKey1;
import com.top_logic.basic.util.ResKey2;
import com.top_logic.basic.util.ResKey3;
import com.top_logic.basic.util.ResKey4;
import com.top_logic.layout.I18NConstantsBase;

/**
 * Internationalization constants for this package.
 */
public class I18NConstants extends I18NConstantsBase {

	/**
	 * @en The device has no program.
	 */
	public static ResKey ERROR_NO_PROGRAM;

	/**
	 * @en The device has no complete individual address (area, line, device).
	 */
	public static ResKey ERROR_NO_ADDRESS;

	/**
	 * @en The download image is incomplete: {0}
	 */
	public static ResKey1 ERROR_IMAGE__PROBLEM;

	/**
	 * @en The program uses dynamic modules (ETS 5.6 and later) with a construct the import could not interpret, or was imported without them: parts of its modules are missing.
	 */
	public static ResKey ERROR_PROGRAM_USES_MODULES;

	/**
	 * @en The device holds another layout of its dynamic modules than E-Home computes ({0}); a download with E-Home''s layout would scramble its configuration. Use "Download with E-Home''s module layout" to replace the device''s layout.
	 */
	public static ResKey1 ERROR_MODULE_LAYOUT_DIFFERS__DIFFERENCES;

	/**
	 * @en No difference between the layout of the device's dynamic modules and E-Home's is recorded.
	 */
	public static ResKey ERROR_NO_LAYOUT_DIFFERENCE;

	/**
	 * @en Download "{0}" ({1}) through {2} with E-Home''s layout of its dynamic modules? The device holds another layout (argument values: {3}); the download replaces it, so that the device''s configuration is E-Home''s.
	 */
	public static ResKey4 CONFIRM_LAYOUT__DEVICE_ADDRESS_GATEWAY_DIFFERENCES;

	/**
	 * @en The program has no mask version; its load procedure is not known.
	 */
	public static ResKey ERROR_NO_MASK;

	/**
	 * @en The medium {0} of mask {1} is not supported; supported is twisted pair (TP).
	 */
	public static ResKey2 ERROR_UNSUPPORTED_MEDIUM__MEDIUM_MASK;

	/**
	 * @en Neither the program nor its mask {0} has a load procedure for a full download.
	 */
	public static ResKey1 ERROR_NO_PROCEDURE__MASK;

	/**
	 * @en Step {0}: the step kind {1} is not supported (mask {2}).
	 */
	public static ResKey3 ERROR_UNSUPPORTED_STEP__STEP_KIND_MASK;

	/**
	 * @en Step {0}: mask {1} controls its load state machines in memory (no feature PropertyMappedLsms); only load state machines controlled through properties are supported (as on mask 7.5).
	 */
	public static ResKey2 ERROR_MEMORY_MAPPED_LSM__STEP_MASK;

	/**
	 * @en Step {0}: the load state machine is given by object type, not by index; not supported.
	 */
	public static ResKey1 ERROR_LSM_BY_TYPE__STEP;

	/**
	 * @en Step {0}: mask {1} has no interface object for load state machine {2}.
	 */
	public static ResKey3 ERROR_NO_LSM_OBJECT__STEP_MASK_LSM;

	/**
	 * @en Step {0}: the segment type {1} is not supported; supported are the absolute segments (types 0 and 1).
	 */
	public static ResKey2 ERROR_SEGMENT_TYPE__STEP_TYPE;

	/**
	 * @en Step {0}: the memory space {1} is not supported; only the standard memory is written.
	 */
	public static ResKey2 ERROR_MEMORY_SPACE__STEP_SPACE;

	/**
	 * @en Step {0}: the step has no data to compare with.
	 */
	public static ResKey1 ERROR_NO_INLINE_DATA__STEP;

	/**
	 * @en Step {0}: the step''s data has {1} bytes, the step names {2}.
	 */
	public static ResKey3 ERROR_DATA_SIZE__STEP_DATA_SIZE;

	/**
	 * @en Step {0}: the control variable {1} is not supported.
	 */
	public static ResKey2 ERROR_CONTROL_VARIABLE__STEP_VARIABLE;

	/**
	 * @en Step {0}: object {1} property {2} holds no image E-Home knows (supported: the download stamps of mask resources of the flavour Stamp_SystemB).
	 */
	public static ResKey3 ERROR_UNKNOWN_IMAGE__STEP_OBJECT_PROPERTY;

	/**
	 * @en Step {0}: {1} stamps are to be written, the load state machine has {2} stamped segments.
	 */
	public static ResKey3 ERROR_STAMP_COUNT__STEP_COUNT_SEGMENTS;

	/**
	 * @en Step {0}: the segment {1} of the association table (dynamic table management) does not hold the table of {2} bytes.
	 */
	public static ResKey3 ERROR_ASSOCIATION_SPACE__STEP_SEGMENT_LENGTH;

	/**
	 * @en The byte at {0} is written only in some of its bits (mask {1}); writing parts of a byte is not supported.
	 */
	public static ResKey2 ERROR_PARTIAL_MASK__ADDRESS_MASK;

	/**
	 * @en The memory {0} ({1}) is not written by the load procedure: it lies in no segment the procedure allocates and is no memory it writes.
	 */
	public static ResKey2 ERROR_NOT_WRITTEN__ADDRESS_BLOCK;

	/**
	 * @en Step {0}: the memory {1}, whose image the step takes, is not written from the image by a later step.
	 */
	public static ResKey2 ERROR_IMAGE_NOT_WRITTEN__STEP_RANGE;

	/**
	 * @en Step {0}: the merge point {1} is left in the procedure.
	 */
	public static ResKey2 ERROR_MERGE__STEP_ID;

	/**
	 * @en Writing to the KNX bus is switched off in this installation. Start the application with -Dehome.programming.writeEnabled=true to allow downloads.
	 */
	public static ResKey ERROR_WRITE_DISABLED;

	/**
	 * @en The project of the device has no KNX gateway. Enter one (address or address:port) or choose one from the search.
	 */
	public static ResKey ERROR_NO_GATEWAY;

	/**
	 * @en The KNX gateway "{0}" is no address (address or address:port).
	 */
	public static ResKey1 ERROR_BAD_GATEWAY__VALUE;

	/**
	 * @en The connection to the gateway {0} failed: {1}
	 */
	public static ResKey2 ERROR_CONNECTION__GATEWAY_DETAIL;

	/**
	 * @en Download into {0} through {1}: {2} operations done in {3} s.
	 */
	public static ResKey4 DOWNLOAD_DONE__DEVICE_GATEWAY_COUNT_SECONDS;

	/**
	 * @en Download into {0} through {1} failed at operation {2}: {3}
	 */
	public static ResKey4 DOWNLOAD_FAILED__DEVICE_GATEWAY_NUMBER_REASON;

	/**
	 * @en Comparison of {0} through {1}: the device equals the download image in {2} of {3} blocks.
	 */
	public static ResKey4 COMPARE_DONE__DEVICE_GATEWAY_EQUAL_BLOCKS;

	/**
	 * @en Comparison of {0} through {1} failed: {2}
	 */
	public static ResKey3 COMPARE_FAILED__DEVICE_GATEWAY_REASON;

	/**
	 * @en Failure
	 */
	public static ResKey LEVEL_FAILURE;

	/**
	 * @en Note
	 */
	public static ResKey LEVEL_NOTE;

	/**
	 * @en Identical
	 */
	public static ResKey LEVEL_IDENTICAL;

	/**
	 * @en Different
	 */
	public static ResKey LEVEL_DIFFERENT;

	/**
	 * @en Download into {0} ({1}) through the gateway {2}? {3} The device''s application is unloaded and loaded anew; the device does not work until the download is complete.
	 */
	public static ResKey4 CONFIRM_DOWNLOAD__DEVICE_ADDRESS_GATEWAY_CHANGES;

	/**
	 * @en Changes since the last download: {0}.
	 */
	public static ResKey1 CHANGES_SINCE_DOWNLOAD__COUNT;

	/**
	 * @en No earlier download of the device is known.
	 */
	public static ResKey CHANGES_NO_DOWNLOAD;

	/**
	 * @en Write the individual address {0} into the device in programming mode, through the gateway {1}? Press the programming button of exactly one device first.
	 */
	public static ResKey2 CONFIRM_ADDRESS__ADDRESS_GATEWAY;

	/**
	 * @en Connecting to {0}
	 */
	public static ResKey1 PROGRESS_CONNECTING__GATEWAY;

	/**
	 * @en Operation {0} of {1}: {2}
	 */
	public static ResKey3 PROGRESS_OPERATION__NUMBER_COUNT_TEXT;

	/**
	 * @en Reading {0}
	 */
	public static ResKey1 PROGRESS_READING__WHAT;

	/**
	 * @en The download is refused: {0}
	 */
	public static ResKey1 ERROR_REFUSED__PROBLEMS;

	/**
	 * @en {0} operations, {1} bytes to write; {2}.
	 */
	public static ResKey3 PLAN_SUMMARY__COUNT_BYTES_PROCEDURE;

	/**
	 * @en Not loaded (as ETS does not load it): {0}
	 */
	public static ResKey1 NOTE_NOT_LOADED__BLOCK;

	/**
	 * @en the load procedure of the program (ProductProcedure)
	 */
	public static ResKey PROCEDURE_PRODUCT;

	/**
	 * @en the {0} procedure of mask {1} (DefaultProcedure)
	 */
	public static ResKey2 PROCEDURE_DEFAULT__SCOPE_MASK;

	/**
	 * @en the {0} procedure of mask {1} merged with the program''s (MergedProcedure)
	 */
	public static ResKey2 PROCEDURE_MERGED__SCOPE_MASK;

	static {
		initConstants(I18NConstants.class);
	}
}
