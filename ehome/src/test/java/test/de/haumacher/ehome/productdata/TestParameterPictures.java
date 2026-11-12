/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.productdata;

import static test.de.haumacher.ehome.importer.TestEtsImport.instances;

import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

import junit.framework.Test;
import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;

import com.top_logic.basic.config.ConfigurationException;
import com.top_logic.basic.io.binary.BinaryData;
import com.top_logic.basic.util.ResKey;
import com.top_logic.knowledge.service.KnowledgeBase;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.model.TLClass;
import com.top_logic.model.TLObject;
import com.top_logic.model.search.expr.config.ExprFormat;
import com.top_logic.model.search.expr.query.QueryExecutor;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.Resources;
import com.top_logic.util.model.ModelService;
import com.top_logic.xio.importer.binding.ApplicationModelBinding;

import de.haumacher.ehome.importer.Importer;
import de.haumacher.ehome.knx.ParameterPictures;
import de.haumacher.ehome.knx.ParameterPictures.Display;
import de.haumacher.ehome.knx.ParameterValues;
import de.haumacher.ehome.model.operational.EhomeOperationalFactory;
import de.haumacher.ehome.model.operational.Option;
import de.haumacher.ehome.model.operational.ParameterView;
import de.haumacher.ehome.model.operational.PictureType;
import de.haumacher.ehome.model.operational.VoidType;
import de.haumacher.ehome.programming.DeviceConfiguration;
import de.haumacher.ehome.programming.DeviceSettings;
import de.haumacher.ehome.programming.DownloadImage;
import de.haumacher.ehome.programming.download.DownloadPlan;
import de.haumacher.ehome.programming.download.DownloadPlanner;

/**
 * Test case for the pictures of the parameter dialog (ETS: <code>TypePicture</code>,
 * haumacher/ehome #155) with the fixture <code>TestPictures.knxprod</code>
 * (<code>src/test/fixtures/make-fixtures.py</code>): a program placing three parameters of picture
 * types in a case of a choose, one showing a PNG, one a BMP (a format the browser is not asked to
 * show), one a file the product file does not hold.
 *
 * <p>
 * The types and their files are read back; the parameter editor of a device lists the pictures
 * while the case holds and not otherwise, each shown as its picture, as its file with a download,
 * or with the note that it is not there; a picture parameter has no value: the device has no
 * setting for it, refuses one, and its download is the same with and without the pictures.
 * </p>
 */
@SuppressWarnings("javadoc")
public class TestParameterPictures extends BasicTestCase {

	private static final String FIXTURE = "src/test/fixtures/TestPictures.knxprod";

	private static final String PROGRAM = "M-0008_A-FFB2-01-0000";

	private static final String VARIANT = "M-0008_H-TEST.20BILDER-1_P-TEST.20Bilder";

	public void testPictures() throws Exception {
		KnowledgeBase kb = PersistencyLayer.getKnowledgeBase();
		importProduct(FIXTURE);

		// The types and their files.
		PictureType diagram = (PictureType) byExtId("ehome.operational:PictureType", PROGRAM + "_PT-Schaltbild");
		PictureType curve = (PictureType) byExtId("ehome.operational:PictureType", PROGRAM + "_PT-Kurve");
		PictureType absent = (PictureType) byExtId("ehome.operational:PictureType", PROGRAM + "_PT-Ohne");
		assertEquals("TEST_Schaltbild.png", diagram.getPicture().tValueByName("name"));
		assertEquals("TEST_Kurve.bmp", curve.getPicture().tValueByName("name"));
		assertEquals("TEST_Ohne.png", absent.getPicture().tValueByName("name"));
		assertNull(absent.getPicture().tValueByName("content"));

		// How each is shown.
		assertEquals(Display.IMAGE, ParameterPictures.display(diagram));
		BinaryData image = ParameterPictures.image(diagram);
		assertNotNull(image);
		assertEquals("image/png", image.getContentType());
		assertNull(ParameterPictures.file(diagram));
		assertNull(ParameterPictures.note(diagram));

		assertEquals(Display.FILE, ParameterPictures.display(curve));
		assertNull(ParameterPictures.image(curve));
		assertEquals("TEST_Kurve.bmp", ParameterPictures.file(curve).getName());
		assertNull(ParameterPictures.note(curve));

		assertEquals(Display.MISSING, ParameterPictures.display(absent));
		assertNull(ParameterPictures.image(absent));
		assertNull(ParameterPictures.file(absent));
		assertEquals("The picture \"TEST_Ohne.png\" is not there: the product data did not hold its file. "
			+ "Importing the product file again completes it.", english(ParameterPictures.note(absent)));

		// A type that names no file at all (a program imported before the files were kept).
		PictureType unnamed;
		try (Transaction tx = kb.beginTransaction()) {
			unnamed = EhomeOperationalFactory.getInstance().createPictureType();
			tx.commit();
		}
		try {
			assertEquals(Display.MISSING, ParameterPictures.display(unnamed));
			assertEquals("The picture is not there: the product data did not name its file. "
				+ "Importing the product file again completes it.", english(ParameterPictures.note(unnamed)));
		} finally {
			try (Transaction tx = kb.beginTransaction()) {
				unnamed.tDelete();
				tx.commit();
			}
		}

		// Not a picture: the functions answer nothing.
		assertNull(ParameterPictures.pictureType(byExtId("ehome.operational:Parameter", PROGRAM + "_P-1")));

		// The parameter editor of a device.
		TLObject variant = byExtId("ehome.catalog:Variant", VARIANT);
		TLObject assignment = ((Collection<?>) ((TLObject) variant.tValueByName("product")).tValueByName("programs"))
			.stream()
			.map(TLObject.class::cast)
			.filter(a -> PROGRAM.equals(((TLObject) a.tValueByName("program")).tValueByName("extId")))
			.findFirst().orElseThrow();
		TLObject project;
		TLObject device;
		try (Transaction tx = kb.beginTransaction()) {
			device = create("ehome.physical:Device");
			device.tUpdateByName("address", 77);
			device.tUpdateByName("type", variant);
			device.tUpdateByName("program", assignment);
			TLObject line = create("ehome.physical:Line");
			line.tUpdateByName("address", 1);
			line.tUpdateByName("devices", List.of(device));
			TLObject area = create("ehome.physical:Area");
			area.tUpdateByName("address", 1);
			area.tUpdateByName("lines", List.of(line));
			project = create("ehome.project:Project");
			project.tUpdateByName("name", "TEST Bilder");
			project.tUpdateByName("areas", List.of(area));
			tx.commit();
		}
		try {
			// The default case (Schalten): no picture.
			ParameterView operation = view(device, PROGRAM + "_P-1");
			assertEquals(List.of(), pictures(new DeviceConfiguration(device)));
			DownloadImage hidden = DownloadImage.compute(device);
			assertEquals(hidden.getProblems().toString(), List.of(), hidden.getProblems());
			int hiddenBytes = DownloadPlanner.plan(device, DownloadPlan.Kind.FULL).bytesWritten();

			// Treppenlicht: the three pictures, at their place on the block.
			try (Transaction tx = kb.beginTransaction()) {
				DeviceSettings.setValue(device, operation, entry(operation, 2));
				tx.commit();
			}
			DeviceConfiguration configuration = new DeviceConfiguration(device);
			List<ParameterView> pictures = pictures(configuration);
			assertEquals(List.of(PROGRAM + "_P-9", PROGRAM + "_P-10", PROGRAM + "_P-11"),
				pictures.stream().map(v -> v.getParameter().getExtId()).toList());
			Object page = configuration.getShownItems().get(pictures.get(0));
			List<Option> items = configuration.getItems(page);
			assertEquals(pictures, items.subList(items.size() - 3, items.size()));
			assertEquals(List.of(Display.IMAGE, Display.FILE, Display.MISSING),
				pictures.stream().map(v -> ParameterPictures.display(ParameterPictures.pictureType(v))).toList());
			assertEquals("Schaltbild Treppenlicht", pictures.get(0).getTitle());

			// The functions of the views, by a placed parameter.
			assertEquals(ParameterPictures.image(diagram).getName(),
				((BinaryData) script("v -> parameterPictureImage($v)").execute(pictures.get(0))).getName());
			assertNull(script("v -> parameterPictureImage($v)").execute(pictures.get(1)));
			assertEquals("TEST_Kurve.bmp",
				((BinaryData) script("v -> parameterPictureFile($v)").execute(pictures.get(1))).getName());
			assertEquals(ParameterPictures.note(absent), script("v -> parameterPictureNote($v)").execute(pictures.get(2)));
			assertNull(script("v -> parameterPictureNote($v)").execute(operation));

			// No value: no setting, none accepted, nothing for a calculation.
			for (ParameterView picture : pictures) {
				assertNull(DeviceSettings.setting(device, picture));
				assertNotNull(DeviceSettings.editVeto(device, picture));
				assertFalse(DeviceConfiguration.isEditable(picture));
				assertTrue(ParameterValues.valueType(picture) instanceof VoidType);
				assertNull(ParameterValues.valueAttribute(ParameterValues.valueType(picture)));
				try (Transaction tx = kb.beginTransaction()) {
					assertNotNull(DeviceSettings.applyValue(device, picture, "1"));
					tx.commit();
				}
				assertNull(DeviceSettings.setting(device, picture));
			}
			assertEquals(1, ((Collection<?>) device.tValueByName("settings")).size());

			// The download: the same bytes as without the pictures.
			DownloadImage shown = DownloadImage.compute(device);
			assertEquals(shown.getProblems().toString(), List.of(), shown.getProblems());
			DownloadPlan plan = DownloadPlanner.plan(device, DownloadPlan.Kind.FULL);
			assertTrue(plan.getProblems().toString(), plan.isExecutable());
			assertEquals(hiddenBytes, plan.bytesWritten());
			assertEquals(2, shown.byteAt(0x150).intValue());
			for (int address = 0x100; address < 0x200; address++) {
				if (address != 0x150) {
					assertEquals("Byte at " + Integer.toHexString(address), hidden.byteAt(address), shown.byteAt(address));
				}
			}
		} finally {
			try (Transaction tx = kb.beginTransaction()) {
				project.tDelete();
				tx.commit();
			}
		}
	}

	private static List<ParameterView> pictures(DeviceConfiguration configuration) {
		List<ParameterView> result = new ArrayList<>();
		for (Option item : configuration.getShownItems().keySet()) {
			if (item instanceof ParameterView view && ParameterPictures.pictureType(view) != null) {
				result.add(view);
			}
		}
		return result;
	}

	private static ParameterView view(TLObject device, String parameter) {
		return new DeviceConfiguration(device).getShownItems().keySet().stream()
			.filter(ParameterView.class::isInstance)
			.map(ParameterView.class::cast)
			.filter(v -> parameter.equals(v.getParameter().getExtId()))
			.findFirst().orElseThrow();
	}

	private static TLObject entry(ParameterView view, int value) {
		TLObject type = view.getParameter().getType();
		return ((Collection<?>) type.tValueByName("values")).stream()
			.map(TLObject.class::cast)
			.filter(e -> Integer.valueOf(value).equals(e.tValueByName("value")))
			.findFirst().orElseThrow();
	}

	private static QueryExecutor script(String expr) throws ConfigurationException {
		return QueryExecutor.compile(ExprFormat.INSTANCE.getValue("expr", expr));
	}

	private static String english(ResKey key) {
		return Resources.getInstance(Locale.ENGLISH).getString(key);
	}

	private static void importProduct(String file) throws Exception {
		KnowledgeBase kb = PersistencyLayer.getKnowledgeBase();
		Importer importer = new Importer(new ApplicationModelBinding(kb, ModelService.getApplicationModel(), false));
		try (InputStream in = new FileInputStream(file)) {
			try (Transaction tx = kb.beginTransaction()) {
				importer.importProductData(in);
				tx.commit();
			}
		}
	}

	private static TLObject byExtId(String typeName, String extId) {
		List<TLObject> result = instances(typeName).stream()
			.filter(obj -> extId.equals(obj.tValueByName("extId")))
			.toList();
		assertEquals(extId, 1, result.size());
		return result.get(0);
	}

	private static TLObject create(String typeName) {
		return ModelService.getInstance().getFactory().createObject((TLClass) TLModelUtil.findType(typeName));
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestParameterPictures.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
