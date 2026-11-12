/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.monitor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import junit.framework.Test;
import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;
import test.de.haumacher.ehome.runtime.FakeController;

import com.top_logic.basic.util.ResKey;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.model.TLClass;
import com.top_logic.model.TLObject;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.Resources;
import com.top_logic.util.error.TopLogicException;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.monitor.ControllerTarget;
import de.haumacher.ehome.monitor.GroupMonitor;
import de.haumacher.ehome.monitor.I18NConstants;
import de.haumacher.ehome.monitor.ProjectIndex;

/**
 * The controller the group monitor attaches to (haumacher/ehome #56, {@link ControllerTarget}): the
 * project's controller, another controller of the project, or one entered by host, port and token,
 * which is not stored; a wrong token is refused and leaves the monitor where it was. A controller of
 * another project is refused (haumacher/ehome #151). The status line names the controller.
 *
 * <p>
 * Runs on an empty project of its own against a {@link FakeController}: no import.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestControllerChoice extends BasicTestCase {

	private static final String TOKEN = "hct_0123456789abcdef0123456789abcdef";

	private static final String WRONG_TOKEN = "hct_ffffffffffffffffffffffffffffffff";

	public void testChoice() throws Exception {
		TLObject project = createProject("TestControllerChoice");
		TLObject other = createProject("TestControllerChoice (other)");
		FakeController fake = new FakeController(TOKEN, 262144);
		TLObject registered = createController(project, "Fake registered", fake.getPort(), TOKEN);
		TLObject withoutToken = createController(project, "Fake without token", fake.getPort(), null);
		TLObject foreign = createController(other, "Fake foreign", fake.getPort(), TOKEN);
		GroupMonitor monitor = new GroupMonitor(ProjectIndex.of(project));
		String address = "127.0.0.1:" + fake.getPort();
		try {
			// Nothing chosen, and the project has no controller.
			assertError(I18NConstants.ERROR_NO_CONTROLLER_CHOSEN, () -> ControllerTarget.of(project, null, null, null, null));

			// A controller of another project is refused, also with a token entered for it.
			assertError(de.haumacher.ehome.connection.I18NConstants.ERROR_FOREIGN_CONTROLLER__CONTROLLER_PROJECT.fill("", ""),
				() -> ControllerTarget.of(project, foreign, null, null, null));
			assertError(de.haumacher.ehome.connection.I18NConstants.ERROR_FOREIGN_CONTROLLER__CONTROLLER_PROJECT.fill("", ""),
				() -> ControllerTarget.of(project, foreign, null, null, TOKEN));

			// A controller of the project that is not its deployment target.
			ControllerTarget target = ControllerTarget.of(project, registered, null, null, null);
			assertFalse(target.entered());
			target.attach(monitor);
			assertTrue(monitor.isConnected());
			assertEquals("Fake registered (" + address + ")", target.description());
			assertEquals(status(target.description()), monitor.snapshot().get("status"));
			assertNull(project.tValueByName("controller"));

			// A controller entered ad hoc, with the right token.
			ControllerTarget adHoc = ControllerTarget.of(project, registered, " 127.0.0.1 ", String.valueOf(fake.getPort()),
				TOKEN);
			assertTrue(adHoc.entered());
			assertEquals(address, adHoc.description());
			adHoc.attach(monitor);
			assertTrue(monitor.isConnectedTo(address));
			assertEquals(status(address), monitor.snapshot().get("status"));

			// A wrong token is refused; the monitor stays attached where it was.
			ControllerTarget wrong =
				ControllerTarget.of(project, null, "127.0.0.1", Integer.valueOf(fake.getPort()), WRONG_TOKEN);
			try {
				wrong.attach(monitor);
				fail("A wrong token was accepted.");
			} catch (TopLogicException ex) {
				assertEquals(I18NConstants.ERROR_CONTROLLER_CONNECT_FAILED__CONTROLLER_DETAIL.fill(address, "x").plain(),
					ex.getErrorKey().plain());
				assertTrue(text(ex.getErrorKey()), text(ex.getErrorKey()).contains("auth.token_invalid"));
			}
			assertTrue(monitor.isConnectedTo(address));

			// An entered host needs a token; a port must be one.
			assertError(I18NConstants.ERROR_NO_AD_HOC_TOKEN__CONTROLLER.fill(""),
				() -> ControllerTarget.of(project, null, "127.0.0.1", null, " "));
			assertError(I18NConstants.ERROR_INVALID_CONTROLLER_PORT__PORT.fill(""),
				() -> ControllerTarget.of(project, null, "127.0.0.1", "http", TOKEN));
			assertError(I18NConstants.ERROR_INVALID_CONTROLLER_PORT__PORT.fill(""),
				() -> ControllerTarget.of(project, null, "127.0.0.1", "70000", TOKEN));
			assertEquals(80, ControllerTarget.of(project, null, "controller.local", null, TOKEN).client().port());

			// A registered controller without token: refused, unless one is entered, which is not stored.
			assertError(I18NConstants.ERROR_NO_CONTROLLER_TOKEN__CONTROLLER.fill(""),
				() -> ControllerTarget.of(project, withoutToken, null, null, null));
			ControllerTarget entered = ControllerTarget.of(project, withoutToken, "", null, TOKEN);
			assertTrue(entered.entered());
			entered.attach(monitor);
			assertTrue(monitor.isConnectedTo("Fake without token (" + address + ")"));
			assertNull(withoutToken.tValueByName("token"));

			// Without a choice, the project's controller.
			try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
				project.tUpdateByName("controller", registered);
				tx.commit();
			}
			ControllerTarget own = ControllerTarget.of(project, null, null, null, null);
			assertEquals("Fake registered", own.name());
			own.attach(monitor);
			assertTrue(monitor.isConnectedTo(own.description()));
		} finally {
			monitor.close();
			fake.stop();
			delete(project);
			delete(other);
		}
	}

	private static String status(String controller) {
		return text(I18NConstants.STATUS_CONTROLLER_CONNECTED__CONTROLLER_COUNT.fill(controller, Long.valueOf(0)));
	}

	private static void assertError(ResKey expected, Runnable action) {
		try {
			action.run();
			fail("Expected the error " + expected.plain() + ".");
		} catch (TopLogicException ex) {
			assertEquals(expected.plain(), ex.getErrorKey().plain());
		}
	}

	private static String text(ResKey key) {
		return Resources.getInstance().getString(key);
	}

	/**
	 * Creates an empty project (<code>ehome.project:Project</code>) with the given name.
	 */
	static TLObject createProject(String name) {
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			TLObject project = ModelService.getInstance().getFactory()
				.createObject((TLClass) TLModelUtil.findType("ehome.project:Project"));
			project.tUpdateByName("name", name);
			tx.commit();
			return project;
		}
	}

	private static TLObject createController(TLObject project, String name, int port, String token) {
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			TLObject controller = ModelService.getInstance().getFactory()
				.createObject((TLClass) TLModelUtil.findType("ehome.runtime:Controller"));
			controller.tUpdateByName("name", name);
			controller.tUpdateByName("host", "127.0.0.1");
			controller.tUpdateByName("port", Integer.valueOf(port));
			controller.tUpdateByName("token", token);
			List<Object> controllers = new ArrayList<>((Collection<?>) project.tValueByName("controllers"));
			controllers.add(controller);
			project.tUpdateByName("controllers", controllers);
			tx.commit();
			return controller;
		}
	}

	/**
	 * Deletes the given object, if it still exists.
	 */
	static void delete(TLObject object) {
		if (object == null || !object.tValid()) {
			return;
		}
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			object.tDelete();
			tx.commit();
		}
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestControllerChoice.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
