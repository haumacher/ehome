/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.productdata;

import static test.de.haumacher.ehome.importer.TestEtsImport.importSample;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;

import junit.framework.Test;

import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;

import com.top_logic.basic.BufferingProtocol;
import com.top_logic.basic.config.TypedConfiguration;
import com.top_logic.basic.i18n.log.BufferingI18NLog;
import com.top_logic.basic.sql.ConnectionPool;
import com.top_logic.basic.sql.ConnectionPoolRegistry;
import com.top_logic.basic.sql.PooledConnection;
import com.top_logic.basic.util.ResourcesModule;
import com.top_logic.knowledge.service.KnowledgeBase;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.knowledge.service.migration.SQLMigrationProcessor;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.model.operational.Channel;
import de.haumacher.ehome.model.operational.EhomeOperationalFactory;
import de.haumacher.ehome.programming.DeviceTexts;

/**
 * Test case for the number of a channel in its own notation (haumacher/ehome #162): the KNX schema
 * types <code>Channel@Number</code> as a text (<code>String50_t</code>), and Theben's program
 * <code>M-0048_A-F140-13-8536</code> numbers its logic channels "18d". With the artificial product file
 * <code>TestChannelNumber.knxprod</code> (made by <code>src/test/fixtures/make-fixtures.py</code>), and the
 * data migration <code>Issue_162_Channel_number_text</code>, which turns the integers stored before into
 * their text.
 */
@SuppressWarnings("javadoc")
public class TestChannelNumber extends BasicTestCase {

	private static final String PROGRAM = "M-0008_A-FFA1-01-0000";

	private static final String FILE = "src/test/fixtures/TestChannelNumber.knxprod";

	private static final String SCRIPT = "webinf://kbase/migration/ehome/Issue_162_Channel_number_text_scripts/numberText";

	/**
	 * The import reads the number as the file writes it, without a message; the label of the channel
	 * in the tree and in the editor of a device starts with it.
	 */
	public void testImport() throws Exception {
		List<BufferingI18NLog.Entry> messages = importSample(FILE);
		for (BufferingI18NLog.Entry entry : messages) {
			assertFalse(String.valueOf(entry.getMessage()), String.valueOf(entry.getMessage()).contains(PROGRAM));
		}
		Channel channel = (Channel) TestValueForms.byExtId("ehome.operational:Channel", PROGRAM + "_CH-18d");
		assertEquals("18d", channel.getNumber());
		assertEquals("18d: Kanal A", channel.tValueByName("displayName"));
		assertEquals("18d: Kanal A", DeviceTexts.label(null, channel));
	}

	/**
	 * A channel an import before haumacher/ehome #162 left without its number (it was reported, the
	 * number not being an integer) gets it from the next import of the program's file.
	 */
	public void testKnownChannelCompleted() throws Exception {
		importSample(FILE);
		Channel channel = (Channel) TestValueForms.byExtId("ehome.operational:Channel", PROGRAM + "_CH-18d");
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			channel.setNumber(null);
			tx.commit();
		}
		List<BufferingI18NLog.Entry> messages = importSample(FILE);
		assertEquals("18d", channel.getNumber());
		assertTrue(messages.stream().anyMatch(m -> ResourcesModule.getInstance().getBundle(Locale.ENGLISH)
			.getString(m.getMessage()).equals("The channel " + PROGRAM + "_CH-18d, imported earlier without its number, "
				+ "gets its number \"18d\".")));
	}

	/**
	 * The migration turns the integer a channel stored before into its decimal text, in the flex data
	 * of the channels only.
	 */
	public void testMigration() throws Exception {
		KnowledgeBase kb = PersistencyLayer.getKnowledgeBase();
		Channel channel;
		try (Transaction tx = kb.beginTransaction()) {
			channel = EhomeOperationalFactory.getInstance().createChannel();
			channel.setName("TEST Migration");
			channel.setNumber("987654");
			tx.commit();
		}
		ConnectionPool pool = ConnectionPoolRegistry.getDefaultConnectionPool();
		PooledConnection connection = pool.borrowWriteConnection();
		try {
			int others = count(connection, "TYPE <> 'EHConfigFragment' AND ATTR = 'number' AND DATA_TYPE IN (10, 11)");
			// The channel as stored before haumacher/ehome #162: an integer.
			assertEquals(1, update(connection, "UPDATE FLEX_DATA SET LONG_DATA = 987654, VARCHAR_DATA = NULL, DATA_TYPE = 11 "
				+ "WHERE TYPE = 'EHConfigFragment' AND ATTR = 'number' AND VARCHAR_DATA = '987654'"));
			assertEquals(1, count(connection, "TYPE = 'EHConfigFragment' AND ATTR = 'number' AND LONG_DATA = 987654"));

			SQLMigrationProcessor.Config config = TypedConfiguration.newConfigItem(SQLMigrationProcessor.Config.class);
			config.setFileNamePrefix(SCRIPT);
			BufferingProtocol log = new BufferingProtocol();
			new SQLMigrationProcessor(null, config).doMigration(null, log, connection);
			connection.commit();
			assertFalse(log.getErrors().toString(), log.hasErrors());

			assertEquals(1, count(connection,
				"TYPE = 'EHConfigFragment' AND ATTR = 'number' AND DATA_TYPE = 30 AND VARCHAR_DATA = '987654' AND LONG_DATA IS NULL"));
			assertEquals(0, count(connection, "TYPE = 'EHConfigFragment' AND ATTR = 'number' AND DATA_TYPE IN (10, 11)"));
			assertEquals(others, count(connection, "TYPE <> 'EHConfigFragment' AND ATTR = 'number' AND DATA_TYPE IN (10, 11)"));
		} finally {
			pool.releaseWriteConnection(connection);
		}
		assertEquals("987654", channel.getNumber());
		try (Transaction tx = kb.beginTransaction()) {
			channel.tDelete();
			tx.commit();
		}
	}

	private static int count(PooledConnection connection, String condition) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM FLEX_DATA WHERE " + condition);
				ResultSet result = statement.executeQuery()) {
			result.next();
			return result.getInt(1);
		}
	}

	private static int update(PooledConnection connection, String sql) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement(sql)) {
			int result = statement.executeUpdate();
			connection.commit();
			return result;
		}
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestChannelNumber.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
