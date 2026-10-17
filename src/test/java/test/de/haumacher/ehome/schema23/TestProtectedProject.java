/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.schema23;

import static test.de.haumacher.ehome.importer.TestEtsImport.instances;

import java.util.List;
import java.util.Map;

import junit.framework.Test;
import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;

import com.top_logic.basic.io.binary.BinaryData;
import com.top_logic.basic.io.binary.BinaryDataFactory;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.model.TLObject;
import com.top_logic.util.error.TopLogicException;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.etsImport.ImportEtsProjectAction;
import de.haumacher.ehome.importer.I18NConstants;
import de.haumacher.ehome.importer.PasswordRequiredException;
import de.haumacher.ehome.importer.ProtectedProject;

import test.de.haumacher.ehome.importer.SharedData;
import test.de.haumacher.ehome.schema23.Ets6Files.Protection;

/**
 * Test of the import of project files whose project part is an archive of its own
 * (<code>P-xxxx.zip</code>, ETS 5/6), protected by a password or not, built from the artificial
 * fixture <code>TestEts6.knxproj</code>.
 */
@SuppressWarnings("javadoc")
public class TestProtectedProject extends BasicTestCase {

	private static final String PASSWORD = "Test-Passwort äö";

	/**
	 * The derivation of the archive password of ETS 6, compared with values computed by Python's
	 * <code>hashlib.pbkdf2_hmac('sha256', password.encode('utf-16-le'), b'21.project.ets.knx.org', 65536, 32)</code>.
	 */
	public void testDerivedPassword() {
		assertEquals("2+IIP7ErCPPKxFjJXc59GFx2+w/1VTLHjJ2duc04CYQ=", ProtectedProject.derivedPassword("test"));
		assertEquals("lLBwnGWZsLxSgmJjRQ8kLjmtcT7N6JXNwrYqp84HCWk=", ProtectedProject.derivedPassword("Geheim!äö"));
	}

	/**
	 * A project protected by the password of ETS 6: refused without a password and with a wrong one,
	 * imported with the right one. The import action of the projects view asks for the password
	 * instead of failing, and imports the file with the password given.
	 */
	public void testProtected() throws Exception {
		List<TLObject> before = instances("ehome.project:Project");
		try {
			byte[] content = Ets6Files.withProjectArchive(Protection.AES_DERIVED, PASSWORD);

			// Without a password: refused, naming the project, nothing imported.
			try {
				Ets6Files.importFile(content, null);
				fail("A protected project must not be imported without its password.");
			} catch (PasswordRequiredException ex) {
				assertEquals(I18NConstants.ERROR_PASSWORD_REQUIRED__ENTRY.fill("P-01A0.zip"), ex.getErrorKey());
			}
			assertEquals(0, projects(before));

			// With a wrong password: refused with the reason.
			try {
				Ets6Files.importFile(content, "falsch");
				fail("A wrong password must be refused.");
			} catch (TopLogicException ex) {
				assertEquals(I18NConstants.ERROR_WRONG_PASSWORD__ENTRY.fill("P-01A0.zip"), ex.getErrorKey());
			}
			assertEquals(0, projects(before));

			BinaryData file = BinaryDataFactory.createBinaryData(content, "application/zip", "Protected.knxproj");
			ImportEtsProjectAction action = new ImportEtsProjectAction(null, null);
			Map<?, ?> request = (Map<?, ?>) action.execute(null, file);
			assertEquals(Boolean.TRUE, request.get(ImportEtsProjectAction.PASSWORD_REQUIRED));
			assertSame(file, request.get(ImportEtsProjectAction.FILE_DATA));
			assertTrue(String.valueOf(request.get(ImportEtsProjectAction.MESSAGE)).contains("P-01A0.zip"));
			assertEquals(0, projects(before));
			try {
				action.execute(null, Map.of(ImportEtsProjectAction.FILE_DATA, file, ImportEtsProjectAction.PASSWORD, "falsch"));
				fail("A wrong password must be refused.");
			} catch (TopLogicException ex) {
				assertEquals(I18NConstants.ERROR_WRONG_PASSWORD__ENTRY.fill("P-01A0.zip"), ex.getErrorKey());
			}
			assertEquals(0, projects(before));

			// With the right one: imported, although the archive comes before the manufacturer data.
			Map<?, ?> report = (Map<?, ?>) action.execute(null,
				Map.of(ImportEtsProjectAction.FILE_DATA, file, ImportEtsProjectAction.PASSWORD, PASSWORD));
			assertEquals(List.of(), report.get(ImportEtsProjectAction.MESSAGES) instanceof List<?> messages
				? messages.stream().filter(m -> ImportEtsProjectAction.SEVERITY_ERROR.equals(((Map<?, ?>) m).get(
					ImportEtsProjectAction.SEVERITY))).toList()
				: null);
			TLObject project = (TLObject) report.get(ImportEtsProjectAction.PROJECT);
			assertNotNull(project);
			assertEquals("TestEts6", project.tValueByName("name"));
			assertEquals(List.of(project), SharedData.newProjects(before));
			assertEquals(1, SharedData.within("ehome.physical:Device", List.of(project)).size());
			assertEquals(10, SharedData.within("ehome.logical:GroupAddress", List.of(project)).size());
			assertEquals(11, SharedData.within("ehome.physical:Connection", List.of(project)).size());
		} finally {
			SharedData.deleteProjects(SharedData.newProjects(before));
		}
	}

	/**
	 * A project of ETS 4/5 protected by the traditional ZIP encryption with the password itself, and a
	 * project archive without a password.
	 */
	public void testOtherArchives() throws Exception {
		List<TLObject> before = instances("ehome.project:Project");
		try {
			assertEquals(List.of(), Ets6Files.errors(
				Ets6Files.importFile(Ets6Files.withProjectArchive(Protection.ZIP_CRYPTO, PASSWORD), PASSWORD).getMessages()));
			assertEquals(1, projects(before));
			assertEquals(List.of(), Ets6Files.errors(
				Ets6Files.importFile(Ets6Files.withProjectArchive(Protection.PLAIN, null), null).getMessages()));
			assertEquals(2, projects(before));
			assertEquals(2, SharedData.within("ehome.physical:Device", SharedData.newProjects(before)).size());
		} finally {
			SharedData.deleteProjects(SharedData.newProjects(before));
		}
	}

	/**
	 * The number of projects imported since the given ones existed.
	 */
	private static int projects(List<TLObject> before) {
		return SharedData.newProjects(before).size();
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestProtectedProject.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
