/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.connection;

import com.top_logic.basic.util.ResKey;
import com.top_logic.basic.util.ResKey1;
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

	static {
		initConstants(I18NConstants.class);
	}
}
