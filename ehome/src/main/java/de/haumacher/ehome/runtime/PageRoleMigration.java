/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.runtime;

import com.top_logic.basic.Logger;
import com.top_logic.element.meta.MetaElementUtil;
import com.top_logic.knowledge.service.migration.StartupAction;
import com.top_logic.model.TLClass;
import com.top_logic.model.TLObject;
import com.top_logic.model.util.TLModelUtil;

/**
 * The migration <code>Issue_184_Page_minimum_role</code> (haumacher/ehome #184): every control page
 * of a database from before, which has no minimum role, gets the role <code>operator</code>, the
 * default of a new page; nothing changes in its image until a page is set to <code>anonymous</code>.
 *
 * <p>
 * Runs once, in the started application after the migration, in a transaction of the migration
 * service.
 * </p>
 */
public class PageRoleMigration implements StartupAction {

	@Override
	public void perform() {
		int count = 0;
		TLClass type = (TLClass) TLModelUtil.findType("ehome.runtime:Page");
		TLObject operator = RuleImport.classifier("PageRole", Pages.OPERATOR);
		for (TLObject page : MetaElementUtil.getAllDirectInstancesOf(type, TLObject.class)) {
			if (page.tValueByName(Pages.MINIMUM_ROLE) == null) {
				page.tUpdateByName(Pages.MINIMUM_ROLE, operator);
				count++;
			}
		}
		Logger.info("Control pages given the minimum role operator (haumacher/ehome #184): " + count + ".",
			PageRoleMigration.class);
	}

}
