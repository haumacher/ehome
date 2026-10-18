/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.virtual;

import com.top_logic.basic.util.ResKey;
import com.top_logic.basic.util.ResKey1;
import com.top_logic.basic.util.ResKey2;
import com.top_logic.basic.util.ResKey3;
import com.top_logic.layout.I18NConstantsBase;

/**
 * Internationalization constants of the KNXnet/IP server of the virtual installation
 * ({@link de.haumacher.ehome.virtual.server.KnxServer}).
 */
public class I18NConstants extends I18NConstantsBase {

	/**
	 * @en The UDP port {0} is in use: another KNXnet/IP server or a programming tool listens there.
	 */
	public static ResKey1 ERROR_PORT_IN_USE__ENDPOINT;

	/**
	 * @en The virtual interface on {0} could not be started: {1}
	 */
	public static ResKey2 ERROR_START__ENDPOINT_DETAIL;

	/**
	 * @en {0} is no IPv4 address, KNXnet/IP needs one.
	 */
	public static ResKey1 ERROR_NOT_IPV4__ADDRESS;

	/**
	 * @en {0} is no address of this computer.
	 */
	public static ResKey1 ERROR_NOT_LOCAL__ADDRESS;

	/**
	 * @en The virtual interface can only listen on the first IPv4 address of the network interface {1}, which is {2}, not {0}.
	 */
	public static ResKey3 ERROR_NOT_FIRST_ADDRESS__ADDRESS_INTERFACE_FIRST;

	/**
	 * @en Interrupted while starting or stopping the virtual interface.
	 */
	public static ResKey ERROR_INTERRUPTED;

	static {
		initConstants(I18NConstants.class);
	}
}
