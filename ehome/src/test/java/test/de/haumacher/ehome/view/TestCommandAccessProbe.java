/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.view;

import java.util.List;

import junit.framework.Test;
import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;

import com.top_logic.base.services.InitialRolesManager;
import com.top_logic.model.search.expr.config.SearchBuilder;
import com.top_logic.model.security.SecurityConfigurationService;
import com.top_logic.tool.boundsec.manager.AccessManager;
import com.top_logic.util.model.ModelService;

/**
 * Probe of the access checks of the views (TL #29856, haumacher/ehome #65): every command whose
 * actions change data or act on the KNX bus declares an <code>&lt;access&gt;</code> check, so that a
 * viewer (role <code>ehome.Viewer</code>) cannot run it, and every declared check is valid.
 *
 * <p>
 * The engine checks the access of its own data-changing actions only through the declaration of the
 * command; the Java actions of the app are not access checked at all (CLAUDE.md). Which commands
 * count is described at {@link ViewCommandScan}. A command that only reads (an export, the status of
 * a controller, a search on the network) declares a <code>Read</code> check, so that the declaration
 * states its intent and a later change to writing is visible.
 * </p>
 *
 * <p>
 * The tooling applies the same probe to its views (<code>test.de.haumacher.ehome.virtual.view</code>).
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestCommandAccessProbe extends BasicTestCase {

	public void testDataChangingCommandsAreChecked() throws Exception {
		List<String> unchecked = ViewCommandScan.uncheckedCommands(ViewCommandScan.VIEWS);
		assertEquals("Commands changing data without an access check:\n" + String.join("\n", unchecked), List.of(),
			unchecked);
	}

	public void testDeclaredChecksAreValid() throws Exception {
		List<String> invalid = ViewCommandScan.invalidChecks(ViewCommandScan.VIEWS);
		assertEquals("Access checks that do not build:\n" + String.join("\n", invalid), List.of(), invalid);
	}

	public static Test suite() {
		return suite(TestCommandAccessProbe.class);
	}

	/**
	 * The setup of a probe of the given class: the model, TL-Script and the access rights the
	 * checks are built against.
	 */
	public static Test suite(Class<? extends BasicTestCase> probe) {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(probe,
			ModelService.Module.INSTANCE, SearchBuilder.Module.INSTANCE, InitialRolesManager.Module.INSTANCE,
			AccessManager.Module.INSTANCE, SecurityConfigurationService.Module.INSTANCE));
	}

}
