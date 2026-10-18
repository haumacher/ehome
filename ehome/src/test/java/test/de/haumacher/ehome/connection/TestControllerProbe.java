/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.connection;

import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import junit.framework.Test;

import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;
import test.de.haumacher.ehome.importer.SharedData;
import test.de.haumacher.ehome.runtime.FakeController;
import test.de.haumacher.ehome.runtime.FakeControllerLink;

import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.model.TLObject;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.connection.ControllerConnection;
import de.haumacher.ehome.connection.ControllerConnections;
import de.haumacher.ehome.connection.Controllers;
import de.haumacher.ehome.connection.Enrollment;

/**
 * Probe of the controller connection (haumacher/ehome #54) composed with the enrollment codes: a
 * project has one open code at a time, so a new code kills the old one; and a controller that
 * connects a second time replaces its first socket, which is closed, while the registry points at
 * the new one.
 *
 * <p>
 * Costs about a second: one enrollment and two sockets against the in-process endpoint.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestControllerProbe extends BasicTestCase {

	private static final String TOKEN = "hct_0123456789abcdef0123456789abcdef";

	private ControllerEndpoint _endpoint;

	private FakeController _fake;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		_endpoint = new ControllerEndpoint();
		_fake = new FakeController(TOKEN, 262144);
	}

	@Override
	protected void tearDown() throws Exception {
		_fake.stop();
		_endpoint.stop();
		super.tearDown();
	}

	public void testNewCodeKillsTheOldAndASecondSocketReplacesTheFirst() throws Exception {
		TLObject project = ControllerEndpoint.project("Probe");
		try {
			String first = Enrollment.createCode(project, null, Instant.now());
			String second = Enrollment.createCode(project, null, Instant.now());
			assertFalse(first.equals(second));
			assertEquals(second, project.tValueByName("enrollmentCode"));

			Map<String, Object> stale = new LinkedHashMap<>();
			stale.put("code", first);
			stale.put("name", "homecontrol-probe");
			Enrollment.Answer refused = Enrollment.enroll(stale, _endpoint.base(), Instant.now());
			assertEquals(refused.body().toString(), 404, refused.status());
			assertEquals("ehome.code_unknown", refused.body().get("code"));

			Map<String, Object> request = new LinkedHashMap<>();
			request.put("code", second);
			request.put("name", "homecontrol-probe");
			Enrollment.Answer enrolled = Enrollment.enroll(request, _endpoint.base(), Instant.now());
			assertEquals(enrolled.body().toString(), 200, enrolled.status());
			String id = (String) enrolled.body().get("controller");
			String secret = (String) enrolled.body().get("secret");
			URI socket = URI.create((String) enrolled.body().get("socket"));
			assertNull("The code is consumed.", project.tValueByName("enrollmentCode"));

			// The same controller connects twice: the first socket is closed, the registry holds the second.
			FakeControllerLink a = new FakeControllerLink(_fake, TOKEN, id, secret);
			assertEquals("ready", a.connect(socket).get("type"));
			ControllerConnection firstConnection = ControllerConnections.get(id);
			assertNotNull(firstConnection);
			FakeControllerLink b = new FakeControllerLink(_fake, TOKEN, id, secret);
			assertEquals("ready", b.connect(socket).get("type"));
			int closeCode = a.awaitClose(); // 1008 with E-Home's refusal, or the container's 1000 when it beat it
			assertTrue("close code " + closeCode, closeCode == 1008 || closeCode == 1000);
			ControllerConnection secondConnection = ControllerConnections.get(id);
			assertNotNull(secondConnection);
			assertNotSame(firstConnection, secondConnection);
			// E-Home closes the replaced socket with the reason `ehome.replaced` (no `refused` message: the
			// newer socket is the controller's own), so the old client sees only the close.
			assertFalse(a.getReceived().stream().anyMatch(m -> "ready".equals(m.get("type")) && a.getReceived().indexOf(m) > 0));

			// Forgetting closes the live socket; only then may the project go (the connection's writer
			// thread updates the controller's last contact until the socket is closed).
			Controllers.forget((TLObject) project.tValueByName("controller"));
			assertEquals(1008, b.awaitClose());
			assertNull(ControllerConnections.get(id));
		} finally {
			deleteProject(project);
		}
	}

	private static void deleteProject(TLObject project) throws InterruptedException {
		ControllerConnections.awaitKnowledgeBase(); // the writer records the closes before the project goes
		SharedData.deleteProjects(List.of(project)); // which refetches and retries a late commit itself
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestControllerProbe.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
