/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.programming.download;

import com.top_logic.basic.util.ResKey;
import com.top_logic.basic.util.ResKey1;
import com.top_logic.basic.util.ResKey2;
import com.top_logic.basic.util.ResKey3;
import com.top_logic.basic.util.ResKey4;
import com.top_logic.basic.util.ResKey5;
import com.top_logic.basic.util.ResKeyN;
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
	 * @en Writing to the KNX bus is switched off in this installation. The configuration variable tl_ehome_programming_write_enabled=true allows downloads.
	 */
	public static ResKey ERROR_WRITE_DISABLED;

	/**
	 * @en The KNX gateway "{0}" is no address (address or address:port).
	 */
	public static ResKey1 ERROR_BAD_GATEWAY__VALUE;

	/**
	 * @en The project has neither a KNX gateway nor a controller to reach the bus through: choose one with "Choose the gateway".
	 */
	public static ResKey ERROR_NO_BUS_PATH;

	/**
	 * @en The connection through {0} failed: {1}
	 */
	public static ResKey2 ERROR_CONNECTION__PATH_DETAIL;

	/**
	 * @en Download into {0} through {1}: {2} operations done in {3,number,0.0} s.
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
	 * @en Download into {0} ({1}) through {2}? {3} The device''s application is unloaded and loaded anew; the device does not work until the download is complete.
	 */
	public static ResKey4 CONFIRM_DOWNLOAD__DEVICE_ADDRESS_PATH_CHANGES;

	/**
	 * @en Changes since the last download: {0}.
	 */
	public static ResKey1 CHANGES_SINCE_DOWNLOAD__COUNT;

	/**
	 * @en No earlier download of the device is known.
	 */
	public static ResKey CHANGES_NO_DOWNLOAD;

	/**
	 * @en Write the individual address {0} into the device in programming mode, through {1}? Press the programming button of exactly one device first.
	 */
	public static ResKey2 CONFIRM_ADDRESS__ADDRESS_PATH;

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

	/**
	 * @en Programming of {0}
	 */
	public static ResKey1 COMMIT_PROGRAMMED__DEVICE;

	/**
	 * @en Individual address of {0} programmed
	 */
	public static ResKey1 COMMIT_ADDRESS_PROGRAMMED__DEVICE;

	/**
	 * @en Programming of {0} with E-Home''s module layout
	 */
	public static ResKey1 COMMIT_LAYOUT_PROGRAMMED__DEVICE;

	/**
	 * @en Reading the device descriptor of {0} through {1} failed: {2}
	 */
	public static ResKey3 ACCESS_DESCRIPTOR_FAILED__DEVICE_PATH_REASON;

	/**
	 * @en Reading object {0} property {1} of {2} through {3} failed: {4}
	 */
	public static ResKey5 ACCESS_READ_PROPERTY_FAILED__OBJECT_PROPERTY_DEVICE_PATH_REASON;

	/**
	 * @en Reading object {0} property {1} of {2} through {3} failed: the device refuses the access (failed/forbidden).
	 */
	public static ResKey4 ACCESS_READ_PROPERTY_REFUSED__OBJECT_PROPERTY_DEVICE_PATH;

	/**
	 * @en Reading memory {0} ({1} bytes) of {2} through {3} failed: {4}
	 */
	public static ResKey5 ACCESS_READ_MEMORY_FAILED__ADDRESS_LENGTH_DEVICE_PATH_REASON;

	/**
	 * @en Reading memory {0} ({1} bytes) of {2} through {3} failed: the device answered with {4} bytes.
	 */
	public static ResKey5 ACCESS_READ_MEMORY_SHORT__ADDRESS_LENGTH_DEVICE_PATH_COUNT;

	/**
	 * @en Interrupted.
	 */
	public static ResKey ACCESS_INTERRUPTED;

	/**
	 * @en The load control "{0}" of object {1} of {2} through {3} failed: {4}
	 */
	public static ResKey5 ACCESS_LOAD_CONTROL_FAILED__CONTROL_OBJECT_DEVICE_PATH_REASON;

	/**
	 * @en The load control "{0}" of object {1} of {2} through {3} failed: the device refused the load control.
	 */
	public static ResKey4 ACCESS_LOAD_CONTROL_REFUSED__CONTROL_OBJECT_DEVICE_PATH;

	/**
	 * @en Writing object {0} property {1} of {2} through {3} failed: {4}
	 */
	public static ResKey5 ACCESS_WRITE_PROPERTY_FAILED__OBJECT_PROPERTY_DEVICE_PATH_REASON;

	/**
	 * @en Writing object {0} property {1} of {2} through {3} failed: the device refuses the access (failed/forbidden).
	 */
	public static ResKey4 ACCESS_WRITE_PROPERTY_REFUSED__OBJECT_PROPERTY_DEVICE_PATH;

	/**
	 * @en Writing object {0} property {1} of {2} through {3} failed: the device answered with {4}, written was {5}.
	 */
	public static ResKeyN ACCESS_WRITE_PROPERTY_ANSWER__OBJECT_PROPERTY_DEVICE_PATH_VALUE_WRITTEN;

	/**
	 * @en Writing memory {0} ({1} bytes) of {2} through {3} failed: {4}
	 */
	public static ResKey5 ACCESS_WRITE_MEMORY_FAILED__ADDRESS_LENGTH_DEVICE_PATH_REASON;

	/**
	 * @en The restart of {0} through {1} failed: {2}
	 */
	public static ResKey3 ACCESS_RESTART_FAILED__DEVICE_PATH_REASON;

	/**
	 * @en A device with the address {0} exists, but is not in programming mode.
	 */
	public static ResKey1 ACCESS_ADDRESS_NOT_IN_PROGRAMMING_MODE__ADDRESS;

	/**
	 * @en Writing the individual address {0} through {1} failed: {2}
	 */
	public static ResKey3 ACCESS_WRITE_ADDRESS_FAILED__ADDRESS_PATH_REASON;

	/**
	 * @en No connection to the gateway {0}: {1}
	 */
	public static ResKey2 ACCESS_NO_GATEWAY__ADDRESS_REASON;

	/**
	 * @en Interrupted while connecting to {0}.
	 */
	public static ResKey1 ACCESS_CONNECT_INTERRUPTED__ADDRESS;

	/**
	 * @en Start loading
	 */
	public static ResKey CONTROL_LOAD;

	/**
	 * @en Load completed
	 */
	public static ResKey CONTROL_LOAD_COMPLETED;

	/**
	 * @en Unload
	 */
	public static ResKey CONTROL_UNLOAD;

	/**
	 * @en Allocate absolute segment {0}-{1} ({2} bytes, access {3}, memory type {4}, flags {5})
	 */
	public static ResKeyN CONTROL_ALLOCATE_ABSOLUTE__FROM_TO_LENGTH_ACCESS_TYPE_FLAGS;

	/**
	 * @en Allocate stack segment {0}-{1} ({2} bytes, access {3}, memory type {4}, flags {5})
	 */
	public static ResKeyN CONTROL_ALLOCATE_STACK__FROM_TO_LENGTH_ACCESS_TYPE_FLAGS;

	/**
	 * @en Task segment {0}, PEI type {1}, application {2}
	 */
	public static ResKey3 CONTROL_TASK_SEGMENT__ADDRESS_PEI_APPLICATION;

	/**
	 * @en Task control 1, event handler {0}, {1} objects
	 */
	public static ResKey2 CONTROL_TASK_CONTROL__HANDLER_OBJECTS;

	/**
	 * @en Additional load control {0}
	 */
	public static ResKey1 CONTROL_ADDITIONAL__TYPE;

	/**
	 * @en Load event {0}
	 */
	public static ResKey1 CONTROL_EVENT__EVENT;

	/**
	 * @en Unloaded
	 */
	public static ResKey LOAD_STATE_UNLOADED;

	/**
	 * @en Loaded
	 */
	public static ResKey LOAD_STATE_LOADED;

	/**
	 * @en Loading
	 */
	public static ResKey LOAD_STATE_LOADING;

	/**
	 * @en Error
	 */
	public static ResKey LOAD_STATE_ERROR;

	/**
	 * @en Unloading
	 */
	public static ResKey LOAD_STATE_UNLOADING;

	/**
	 * @en Load completing
	 */
	public static ResKey LOAD_STATE_LOAD_COMPLETING;

	/**
	 * @en State {0}
	 */
	public static ResKey1 LOAD_STATE_OTHER__NUMBER;

	/**
	 * @en Connect
	 */
	public static ResKey OP_CONNECT;

	/**
	 * @en Disconnect
	 */
	public static ResKey OP_DISCONNECT;

	/**
	 * @en Check that the device has the mask version {0}
	 */
	public static ResKey1 OP_CHECK_MASK__MASK;

	/**
	 * @en Check that the device has one of the mask versions {0}
	 */
	public static ResKey1 OP_CHECK_MASKS__MASKS;

	/**
	 * @en Compare object {0} property {1} with {2}
	 */
	public static ResKey3 OP_COMPARE_PROPERTY__OBJECT_PROPERTY_VALUE;

	/**
	 * @en Load control of load state machine {0} (object {1}): {2} [{3}]; expected load state: {4}
	 */
	public static ResKey5 OP_LOAD_CONTROL__LSM_OBJECT_CONTROL_BYTES_STATE;

	/**
	 * @en Write memory {0}-{1} ({2} bytes, {3}): {4}
	 */
	public static ResKey5 OP_WRITE_MEMORY__FROM_TO_LENGTH_WHAT_DATA;

	/**
	 * @en Write memory {0}-{1} ({2} bytes, {3}), verified: {4}
	 */
	public static ResKey5 OP_WRITE_MEMORY_VERIFIED__FROM_TO_LENGTH_WHAT_DATA;

	/**
	 * @en Compare memory {0}-{1} with {2}
	 */
	public static ResKey3 OP_COMPARE_MEMORY__FROM_TO_VALUE;

	/**
	 * @en Write object {0} property {1} element {2} ({3}): {4}
	 */
	public static ResKey5 OP_WRITE_PROPERTY__OBJECT_PROPERTY_ELEMENTS_WHAT_DATA;

	/**
	 * @en Restart the device
	 */
	public static ResKey OP_RESTART;

	/**
	 * @en Wait {0} ms
	 */
	public static ResKey1 OP_DELAY__MILLISECONDS;

	/**
	 * @en Not executed: {0}
	 */
	public static ResKey1 OP_SKIP__REASON;

	/**
	 * @en Write the individual address {0} into the device in programming mode
	 */
	public static ResKey1 OP_WRITE_ADDRESS__ADDRESS;

	/**
	 * @en A progress text of ETS, nothing is sent to the device.
	 */
	public static ResKey SKIP_PROGRESS_TEXT;

	/**
	 * @en Task segment {0} of load state machine {1}: mask {2} controls its load state machines through interface objects (PropertyMappedLsms) and has no interface object {1}.
	 */
	public static ResKey3 SKIP_TASK_SEGMENT__ADDRESS_LSM_MASK;

	/**
	 * @en Takes the image of the memory {0} for ETS; nothing is sent to the device, a later step writes the bytes of the image there.
	 */
	public static ResKey1 SKIP_IMAGE_MEMORY__RANGE;

	/**
	 * @en Takes the image of the memory {0} ({1}) for ETS; nothing is sent to the device, a later step writes the bytes of the image there.
	 */
	public static ResKey2 SKIP_IMAGE_MEMORY_RESOURCES__RANGE_RESOURCES;

	/**
	 * @en The parameter value {0} of object {1} property {2}: the load procedure writes no such property.
	 */
	public static ResKey3 SKIP_PARAMETER__VALUE_OBJECT_PROPERTY;

	/**
	 * @en The parameter value {0} of object {1} property {2}: the load procedure writes no such property (a property of the device itself, e.g. its hardware type).
	 */
	public static ResKey3 SKIP_DEVICE_PARAMETER__VALUE_OBJECT_PROPERTY;

	/**
	 * @en The parameter value {0} of object type {1} property {2}: the load procedure writes no such property.
	 */
	public static ResKey3 SKIP_PARAMETER_OF_TYPE__VALUE_TYPE_PROPERTY;

	/**
	 * @en Zero fill of a stamped segment
	 */
	public static ResKey WHAT_ZERO_FILL;

	/**
	 * @en Download stamp of segment {0}
	 */
	public static ResKey1 WHAT_STAMP__SEGMENT;

	/**
	 * @en Inline data
	 */
	public static ResKey WHAT_INLINE_DATA;

	/**
	 * @en Inline data ({0})
	 */
	public static ResKey1 WHAT_INLINE_DATA_RESOURCES__RESOURCES;

	/**
	 * @en Parameters
	 */
	public static ResKey WHAT_PARAMETERS;

	/**
	 * @en {0}, static data the procedure does not write
	 */
	public static ResKey1 BLOCK_STATIC_PART__BLOCK;

	/**
	 * @en Mask version of the program: {0} ({1})
	 */
	public static ResKey2 FACT_MASK__NUMBER_NAME;

	/**
	 * @en Program version: {0}, the program''s: {1}
	 */
	public static ResKey2 FACT_PROGRAM_VERSION__DEVICE_PROGRAM;

	/**
	 * @en Program identity (memory {0}): {1}, the image''s: {2}
	 */
	public static ResKey3 FACT_PROGRAM_IDENTITY__ADDRESS_DEVICE_IMAGE;

	/**
	 * @en Load state machine {0}: {1}
	 */
	public static ResKey2 FACT_LOAD_STATE__LSM_STATE;

	/**
	 * @en No answer
	 */
	public static ResKey FACT_NO_ANSWER;

	/**
	 * @en Not readable ({0})
	 */
	public static ResKey1 FACT_NOT_READABLE__REASON;

	/**
	 * @en A relative segment, its place in the device is not known.
	 */
	public static ResKey COMPARE_RELATIVE_SEGMENT;

	/**
	 * @en Reading {0} failed: {1}
	 */
	public static ResKey2 COMPARE_READ_FAILED__ADDRESS_REASON;

	/**
	 * @en The device has the mask version {0}, the program needs {1}.
	 */
	public static ResKey2 EXEC_MASK__DESCRIPTOR_NEEDED;

	/**
	 * @en Object {0} property {1} is {2}, expected {3}.
	 */
	public static ResKey4 EXEC_PROPERTY__OBJECT_PROPERTY_VALUE_EXPECTED;

	/**
	 * @en Verification of {0} failed: the device holds {1}, written was {2}.
	 */
	public static ResKey3 EXEC_VERIFY__ADDRESS_READ_WRITTEN;

	/**
	 * @en Memory {0} holds {1}, expected {2}.
	 */
	public static ResKey3 EXEC_MEMORY__ADDRESS_READ_EXPECTED;

	/**
	 * @en Load state machine {0} reports the state "{1}" after the load control "{2}", expected "{3}".
	 */
	public static ResKey4 EXEC_LOAD_STATE__LSM_STATE_CONTROL_EXPECTED;

	/**
	 * @en Load state machine {0} reports no known state after the load control "{1}", expected "{2}".
	 */
	public static ResKey3 EXEC_NO_LOAD_STATE__LSM_CONTROL_EXPECTED;

	/**
	 * @en Device descriptor (mask version): {0}
	 */
	public static ResKey1 REPORT_DESCRIPTOR__VALUE;

	/**
	 * @en {0} bytes compared, all equal.
	 */
	public static ResKey1 REPORT_BLOCK_EQUAL__COUNT;

	/**
	 * @en {0} of {1} bytes differ: {2}
	 */
	public static ResKey3 REPORT_BLOCK_DIFFERENT__DIFFERENT_COUNT_RANGES;

	/**
	 * @en Not compared: a download does not write it (its segment lacks the flag 0x80).
	 */
	public static ResKey REPORT_NOT_COMPARED;

	/**
	 * @en {0}, {1}-{2}
	 */
	public static ResKey3 BLOCK_RANGE__BLOCK_FROM_TO;

	/**
	 * @en The operation "{0}" failed: {1}
	 */
	public static ResKey2 REPORT_OPERATION_FAILED__OPERATION_REASON;

	static {
		initConstants(I18NConstants.class);
	}
}
