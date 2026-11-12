/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.connection;

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
	 * @en Enrollment code {0}: valid for {2} minutes (until {1}), for one controller. Enter the address of this E-Home and the code on the Settings page of the controller; it then appears among the controllers of the project and connects by itself while its E-Home connection is switched on.
	 */
	public static ResKey3 ENROLLMENT_CODE__CODE_UNTIL_MINUTES;

	/**
	 * @en An enrollment code is open until {0}.
	 */
	public static ResKey1 CODE_OPEN__UNTIL;

	/**
	 * @en The last enrollment code expired at {0}.
	 */
	public static ResKey1 CODE_EXPIRED__AT;

	/**
	 * @en Online since {0}
	 */
	public static ResKey1 STATE_ONLINE__SINCE;

	/**
	 * @en Offline, last contact {0}
	 */
	public static ResKey1 STATE_OFFLINE__LAST_CONTACT;

	/**
	 * @en Not connected yet
	 */
	public static ResKey STATE_NEVER_CONNECTED;

	/**
	 * @en On the LAN (not enrolled)
	 */
	public static ResKey STATE_LAN;

	/**
	 * @en Forget the controller {0}? It is deleted from E-Home with its secret, its connection is closed and refused from now on. To connect it again, enroll it with a new code: it becomes a new controller.
	 */
	public static ResKey1 CONFIRM_FORGET__CONTROLLER;

	/**
	 * @en The controller {0} was forgotten.
	 */
	public static ResKey1 FORGOTTEN__CONTROLLER;

	/**
	 * @en The project has an installation key already; renew it instead.
	 */
	public static ResKey KEY_EXISTS;

	/**
	 * @en The project has no installation key to renew; create one.
	 */
	public static ResKey KEY_NONE_TO_RENEW;

	/**
	 * @en The new key {0} of the rotation under way is held by {1} already: renewing now would leave them with a key E-Home forgets. The rotation ends when the other controllers accept the new key too.
	 */
	public static ResKey2 KEY_ROTATION_ACCEPTED__FINGERPRINT_CONTROLLERS;

	/**
	 * @en No installation key: runtime images are deployed unsigned.
	 */
	public static ResKey KEY_STATE_NONE;

	/**
	 * @en Installation key {0}: every runtime image is signed with it. Compare the fingerprint with the one the Settings page of the controller shows.
	 */
	public static ResKey1 KEY_STATE__FINGERPRINT;

	/**
	 * @en New key offered, awaiting the owner''s acceptance on the device: fingerprint {0}. Until a controller accepts it, its images are signed with {1}.
	 */
	public static ResKey2 KEY_STATE_ROTATION__NEW_OLD;

	/**
	 * @en Installation key {0} created; the connected controllers are offered it now, the others at their next connection. Each owner accepts it on the Settings page of the device after comparing the fingerprint.
	 */
	public static ResKey1 KEY_CREATED__FINGERPRINT;

	/**
	 * @en New key {0} offered to the controllers: images are signed with the old key until the owner accepts the new one on the device after comparing the fingerprint.
	 */
	public static ResKey1 KEY_RENEWED__FINGERPRINT;

	/**
	 * @en The device holds no key: it accepts unsigned images.
	 */
	public static ResKey DEVICE_KEY_NONE;

	/**
	 * @en The device holds the key {0}.
	 */
	public static ResKey1 DEVICE_KEY__FINGERPRINT;

	/**
	 * @en Warning: the device holds the key {0}, but the project''s key is {1}. The device refuses the images of this project until its owner accepts the key offered to it.
	 */
	public static ResKey2 DEVICE_KEY_MISMATCH__DEVICE_PROJECT;

	/**
	 * @en Warning: the device holds the key {0}, but the project has no installation key. The device refuses the unsigned images of this project; create a key and have the owner accept it on the device.
	 */
	public static ResKey1 DEVICE_KEY_MISMATCH_NO_KEY__DEVICE;

	/**
	 * @en New key offered, awaiting the owner''s acceptance on the device: fingerprint {0}.
	 */
	public static ResKey1 DEVICE_KEY_PENDING__FINGERPRINT;

	/**
	 * @en The key {0} is offered to the device at each connection until its owner accepts it.
	 */
	public static ResKey1 DEVICE_KEY_OFFERED__FINGERPRINT;

	/**
	 * @en The device refused the offered key: {0}
	 */
	public static ResKey1 DEVICE_KEY_REFUSED__ERROR;

	/**
	 * @en Contact with controller {0} recorded
	 */
	public static ResKey1 COMMIT_CONTROLLER_CONTACT__CONTROLLER;

	/**
	 * @en Enrollment code created for {0}
	 */
	public static ResKey1 COMMIT_ENROLLMENT_CODE__PROJECT;

	/**
	 * @en Controller {0} enrolled in {1}
	 */
	public static ResKey2 COMMIT_ENROLLED__CONTROLLER_PROJECT;

	/**
	 * @en Installation key {1} created for {0}
	 */
	public static ResKey2 COMMIT_KEY_CREATED__PROJECT_FINGERPRINT;

	/**
	 * @en Installation key of {0} renewed: new key {1}
	 */
	public static ResKey2 COMMIT_KEY_RENEWED__PROJECT_FINGERPRINT;

	/**
	 * @en Key held by controller {0} recorded
	 */
	public static ResKey1 COMMIT_DEVICE_KEY__CONTROLLER;

	/**
	 * @en New installation key {1} of {0} in effect
	 */
	public static ResKey2 COMMIT_KEY_ROTATED__PROJECT_FINGERPRINT;

	/**
	 * @en Controller forgotten: {0}
	 */
	public static ResKey1 COMMIT_CONTROLLER_FORGOTTEN__CONTROLLER;

	/**
	 * @en No project is chosen.
	 */
	public static ResKey ERROR_NO_PROJECT;

	/**
	 * @en No controller is chosen.
	 */
	public static ResKey ERROR_NO_CONTROLLER_CHOSEN;

	/**
	 * @en Enter the host name or IP address of the controller.
	 */
	public static ResKey ERROR_CONTROLLER_HOST_MISSING;

	/**
	 * @en The port {0} is no port: enter a number from 1 to 65535.
	 */
	public static ResKey1 ERROR_CONTROLLER_PORT_INVALID__PORT;

	/**
	 * @en The controller {0} of the project has this address already.
	 */
	public static ResKey1 ERROR_CONTROLLER_ADDRESS_KNOWN__CONTROLLER;

	/**
	 * @en The controller {0} belongs to the project {1} already.
	 */
	public static ResKey2 ERROR_CONTROLLER_HAS_PROJECT__CONTROLLER_PROJECT;

	/**
	 * @en The controller {0} belongs to the project {1}, not to this one: a project uses only its own controllers.
	 */
	public static ResKey2 ERROR_FOREIGN_CONTROLLER__CONTROLLER_PROJECT;

	/**
	 * @en The controller {0} belongs to no project: an administrator assigns it to a project first.
	 */
	public static ResKey1 ERROR_CONTROLLER_WITHOUT_PROJECT__CONTROLLER;

	static {
		initConstants(I18NConstants.class);
	}
}
