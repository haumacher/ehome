/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.knx;

import java.util.Set;

import junit.framework.Test;

import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;

import com.top_logic.knowledge.service.KnowledgeBase;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.layout.provider.MetaLabelProvider;
import com.top_logic.model.TLClass;
import com.top_logic.model.TLObject;
import com.top_logic.model.util.TLModelUtil;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.model.physical.Area;
import de.haumacher.ehome.model.physical.Line;
import de.haumacher.ehome.model.project.Project;

/**
 * An area or a line without a name (ETS 6 writes none, haumacher/ehome #168) is labeled by its
 * address; a name, where there is one, is its label; the address is never stored as the name.
 */
@SuppressWarnings("javadoc")
public class TestTopologyLabel extends BasicTestCase {

	public void testLabels() {
		KnowledgeBase kb = PersistencyLayer.getKnowledgeBase();
		TLObject project;
		Area area;
		Line unnamed;
		Line named;
		try (Transaction tx = kb.beginTransaction()) {
			project = create("ehome.project:Project");
			((Project) project).setName("TestTopologyLabel");
			area = (Area) create("ehome.physical:Area");
			area.setAddress(Integer.valueOf(1));
			unnamed = (Line) create("ehome.physical:Line");
			unnamed.setAddress(Integer.valueOf(1));
			named = (Line) create("ehome.physical:Line");
			named.setAddress(Integer.valueOf(2));
			named.setName("Erdgeschoss");
			area.setLines(Set.of(unnamed, named));
			((Project) project).addArea(area);
			tx.commit();
		}
		try {
			assertEquals("1", MetaLabelProvider.INSTANCE.getLabel(area));
			assertEquals("1.1", MetaLabelProvider.INSTANCE.getLabel(unnamed));
			assertEquals("Erdgeschoss", MetaLabelProvider.INSTANCE.getLabel(named));
			assertNull("The address is a label only.", unnamed.getName());
		} finally {
			try (Transaction tx = kb.beginTransaction()) {
				project.tDelete();
				tx.commit();
			}
		}
	}

	private static TLObject create(String typeName) {
		return ModelService.getInstance().getFactory().createObject((TLClass) TLModelUtil.findType(typeName));
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestTopologyLabel.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
