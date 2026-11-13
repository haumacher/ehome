/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.project;

import com.top_logic.basic.util.ResKey;
import com.top_logic.basic.util.ResKey1;
import com.top_logic.layout.I18NConstantsBase;

/**
 * Internationalization constants for this package.
 */
public class I18NConstants extends I18NConstantsBase {

	/**
	 * @en No account with this address; an administrator creates accounts.
	 */
	public static ResKey ERROR_NO_ACCOUNT;

	/**
	 * @en Several accounts have the address {0}; give the user name of the account instead.
	 */
	public static ResKey1 ERROR_AMBIGUOUS_ADDRESS__ADDRESS;

	/**
	 * @en Enter the mail address or the user name of the account, or choose an account.
	 */
	public static ResKey ERROR_NO_ACCOUNT_GIVEN;

	/**
	 * @en {0} is already a member of the project; change the role instead.
	 */
	public static ResKey1 ERROR_ALREADY_MEMBER__ACCOUNT;

	/**
	 * @en {0} is not a member of the project.
	 */
	public static ResKey1 ERROR_NOT_MEMBER__ACCOUNT;

	/**
	 * @en The project keeps at least one owner: make another member owner before {0} gives up the ownership.
	 */
	public static ResKey1 ERROR_LAST_OWNER__ACCOUNT;

	/**
	 * @en Choose the role in the project.
	 */
	public static ResKey ERROR_NO_ROLE;

	/**
	 * @en The account {0} cannot become a member of a project.
	 */
	public static ResKey1 ERROR_NOT_ADDABLE__ACCOUNT;

	/**
	 * @en Only a logged-in user leaves a project.
	 */
	public static ResKey ERROR_NO_USER;

	static {
		initConstants(I18NConstants.class);
	}
}
