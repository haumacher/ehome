/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.runtime;

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
	 * @en The group address {0} has no address and is not part of the runtime image.
	 */
	public static ResKey1 NOTE_NO_ADDRESS__NAME;

	/**
	 * @en The group address {0} has no datapoint type (neither its own nor one of a connected object) and is not part of the runtime image.
	 */
	public static ResKey1 NOTE_NO_DATAPOINT_TYPE__ADDRESS;

	/**
	 * @en The group address {0} has the datapoint type {1}, which the controller does not support; it is not part of the runtime image.
	 */
	public static ResKey2 NOTE_UNSUPPORTED_DATAPOINT_TYPE__ADDRESS_TYPE;

	/**
	 * @en The project has no controller to deploy to: choose one in the deployment tab.
	 */
	public static ResKey ERROR_NO_CONTROLLER;

	/**
	 * @en The controller {0} has no token: set the token created on its console first.
	 */
	public static ResKey1 ERROR_NO_TOKEN__CONTROLLER;

	/**
	 * @en The runtime image could not be created: {0}
	 */
	public static ResKey1 ERROR_EXPORT_FAILED__DETAIL;

	/**
	 * @en The runtime image is valid: {0} datapoints, {1} locations, {2} bytes.
	 */
	public static ResKey3 EXPORT_VALID__DATAPOINTS_LOCATIONS_BYTES;

	/**
	 * @en The runtime image is not valid ({0} refusals); the controller would refuse it.
	 */
	public static ResKey1 EXPORT_INVALID__COUNT;

	/**
	 * @en The controller loaded revision {0}: {1} datapoints, {2} locations, {3} bytes.
	 */
	public static ResKey4 DEPLOY_ACCEPTED__REVISION_DATAPOINTS_LOCATIONS_BYTES;

	/**
	 * @en The controller runs the image but could not store it ({0}); after a restart it loads the previous one.
	 */
	public static ResKey1 DEPLOY_NOT_STORED__ERROR;

	/**
	 * @en E-Home did not send the image: it has {0} refusals.
	 */
	public static ResKey1 DEPLOY_REFUSED_LOCALLY__COUNT;

	/**
	 * @en The controller at {0} could not be reached: {1}
	 */
	public static ResKey2 DEPLOY_UNREACHABLE__ADDRESS_ERROR;

	/**
	 * @en The controller refused the image (HTTP {0}): {1}
	 */
	public static ResKey2 DEPLOY_REFUSED__STATUS_ERROR;

	/**
	 * @en The controller has no image loaded.
	 */
	public static ResKey STATUS_NO_IMAGE;

	/**
	 * @en The controller has no image loaded; the last one it was given was refused ({0}).
	 */
	public static ResKey1 STATUS_NO_IMAGE_REFUSED__CODE;

	/**
	 * @en The controller runs project {0}, revision {1} ({2}), loaded by {3}.
	 */
	public static ResKey4 STATUS_LOADED__PROJECT_REVISION_NAME_SOURCE;

	/**
	 * @en Its KNX connection is down (degraded).
	 */
	public static ResKey STATUS_DEGRADED;

	/**
	 * @en The controller at {0} could not be reached: {1}
	 */
	public static ResKey2 STATUS_UNREACHABLE__ADDRESS_ERROR;

	/**
	 * @en The controller does not tell its status (HTTP {0}): {1}
	 */
	public static ResKey2 STATUS_FAILED__STATUS_ERROR;

	/**
	 * @en In sync: the controller runs revision {0} of this project, the revision of its last deployment.
	 */
	public static ResKey1 DRIFT_IN_SYNC__REVISION;

	/**
	 * @en Drift: the controller runs another project ({0}).
	 */
	public static ResKey1 DRIFT_OTHER_PROJECT__PROJECT;

	/**
	 * @en Drift: the controller runs revision {0} of this project, its last deployment was revision {1}.
	 */
	public static ResKey2 DRIFT_OTHER_REVISION__LOADED_DEPLOYED;

	/**
	 * @en Drift: the controller runs revision {0} of this project, which has not been deployed from here.
	 */
	public static ResKey1 DRIFT_NOT_DEPLOYED__LOADED;

	/**
	 * @en Drift: the controller runs no image, the last deployment of this project was revision {0}.
	 */
	public static ResKey1 DRIFT_NO_IMAGE__DEPLOYED;

	/**
	 * @en The project has not been deployed yet.
	 */
	public static ResKey DRIFT_NEVER_DEPLOYED;

	/**
	 * @en Set
	 */
	public static ResKey TOKEN_SET;

	/**
	 * @en Not set
	 */
	public static ResKey TOKEN_NOT_SET;

	/**
	 * @en The location (latitude and longitude) is incomplete and not part of the runtime image.
	 */
	public static ResKey NOTE_LOCATION_INCOMPLETE;

	/**
	 * @en The group address {0} of the time master has the datapoint type {1}; the controller sends {2} on it.
	 */
	public static ResKey3 NOTE_TIME_MASTER_TYPE__ADDRESS_TYPE_EXPECTED;

	/**
	 * @en Writable and read on start are derived from the connected objects.
	 */
	public static ResKey FLAGS_DERIVED;

	/**
	 * @en Writable and read on start are set for the address.
	 */
	public static ResKey FLAGS_SET;

	/**
	 * @en Writable is set for the address, read on start is derived from the connected objects.
	 */
	public static ResKey FLAGS_WRITABLE_SET;

	/**
	 * @en Read on start is set for the address, writable is derived from the connected objects.
	 */
	public static ResKey FLAGS_READ_ON_START_SET;

	/**
	 * @en A history needs both the retention and the spacing.
	 */
	public static ResKey HISTORY_INCOMPLETE;

	/**
	 * @en A history is kept only of numbers and truth values, not of values of the type {0}.
	 */
	public static ResKey1 HISTORY_WRONG_TYPE__TYPE;

	/**
	 * @en The retention of a history is at least its spacing.
	 */
	public static ResKey HISTORY_RETENTION_BELOW_SPACING;

	/**
	 * @en The history would hold {0} samples; the controller keeps at most {1} per datapoint.
	 */
	public static ResKey2 HISTORY_TOO_MANY_SAMPLES__SAMPLES_MAX;

	/**
	 * @en An identifier has 1 to 32 letters, digits and the characters _ . -
	 */
	public static ResKey INVALID_IDENTIFIER;

	/**
	 * @en This is no POSIX TZ string (e.g. CET-1CEST,M3.5.0,M10.5.0/3): {0}
	 */
	public static ResKey1 INVALID_TIME_ZONE__DETAIL;

	/**
	 * @en This is no host name: {0}
	 */
	public static ResKey1 INVALID_HOST_NAME__DETAIL;

	/**
	 * @en {0} functions were proposed and added.
	 */
	public static ResKey1 PROPOSAL_ADDED__COUNT;

	/**
	 * @en The project has a function for every channel the proposal recognizes; nothing was added.
	 */
	public static ResKey PROPOSAL_NOTHING_NEW;

	/**
	 * @en New function
	 */
	public static ResKey NEW_FUNCTION_NAME;

	static {
		initConstants(I18NConstants.class);
	}
}
