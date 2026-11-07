/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.rules;

import com.top_logic.basic.util.ResKey;

/**
 * The owner of a datapoint as a message of a loader names it: the loaders take the owner as the
 * controller names it (<code>the KNX adapter</code>, <code>a schedule entry</code>), the message for
 * the user names it in the user's language, as the subject of its sentence.
 */
public final class OwnerLabel {

	private OwnerLabel() {
		// Static only.
	}

	/**
	 * The owner for the message of a loader.
	 *
	 * @param owner
	 *        The owner as the controller names it.
	 * @return Its label; the owner itself for an owner the loaders do not know.
	 */
	public static ResKey of(String owner) {
		switch (owner) {
			case "the KNX adapter":
				return I18NConstants.OWNER_KNX_ADAPTER;
			case "the Zigbee adapter":
				return I18NConstants.OWNER_ZIGBEE_ADAPTER;
			case "the time master":
			case "the KNX time master":
				return I18NConstants.OWNER_TIME_MASTER;
			case "the rule engine":
				return I18NConstants.OWNER_RULE_ENGINE;
			case "the rule engine (a rule parameter)":
				return I18NConstants.OWNER_RULE_PARAMETER;
			case "the scheduler":
				return I18NConstants.OWNER_SCHEDULER;
			case "a schedule entry":
				return I18NConstants.OWNER_SCHEDULE_ENTRY;
			case "another schedule entry":
				return I18NConstants.OWNER_OTHER_SCHEDULE_ENTRY;
			case "the block runtime":
				return I18NConstants.OWNER_BLOCKS;
			case "the block runtime (a block's datapoint)":
				return I18NConstants.OWNER_BLOCK_DATAPOINT;
			default:
				return ResKey.text(owner);
		}
	}

}
