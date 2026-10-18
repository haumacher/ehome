/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.programming;

import static test.de.haumacher.ehome.importer.TestEtsImport.importSample;
import static test.de.haumacher.ehome.importer.TestEtsImport.instances;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import junit.framework.Test;
import test.com.top_logic.basic.BasicTestCase;
import test.com.top_logic.basic.module.ServiceTestSetup;
import test.com.top_logic.knowledge.KBSetup;
import test.de.haumacher.ehome.importer.SharedData;

import com.top_logic.knowledge.wrap.person.PersonManager;
import com.top_logic.layout.provider.LabelProviderService;
import com.top_logic.model.TLObject;
import com.top_logic.util.model.ModelService;

import de.haumacher.ehome.programming.GroupAddressTypes;
import de.haumacher.ehome.runtime.RuntimeImage;

/**
 * Probe of the untyped group addresses (haumacher/ehome #46) against the runtime image, with projects
 * the delivery's test does not use: the addresses the view marks as untyped are exactly those the
 * image leaves out, every suggestion is accepted, and the tree's untyped parts hold all untyped
 * addresses.
 */
@SuppressWarnings("javadoc")
public class TestGroupAddressTypesProbe extends BasicTestCase {

	public void testProbe() throws Exception {
		for (String sample : List.of("src/test/fixtures/TestDetails.knxproj", "samples/EHomeDemo.knxproj")) {
			List<TLObject> before = instances("ehome.project:Project");
			importSample(sample);
			List<TLObject> projects = SharedData.newProjects(before);
			try {
				TLObject project = projects.get(0);
				List<TLObject> addresses = SharedData.within("ehome.logical:GroupAddress", projects);
				assertFalse(sample, addresses.isEmpty());
				RuntimeImage image = RuntimeImage.build(project, 1, Instant.EPOCH);
				Set<TLObject> exported = new HashSet<>(image.getDatapointSources());
				Set<TLObject> untyped = new HashSet<>();
				for (TLObject address : addresses) {
					boolean typed = GroupAddressTypes.hasType(address);
					assertEquals(sample + " " + address.tValueByName("name"), typed, exported.contains(address));
					if (!typed) {
						untyped.add(address);
					}
					for (TLObject type : GroupAddressTypes.suggestions(address)) {
						assertNull(sample + " " + address.tValueByName("name") + " " + type,
							GroupAddressTypes.veto(address, type));
					}
				}
				assertEquals(sample, untyped, new HashSet<>(leaves(project)));
			} finally {
				SharedData.deleteProjects(projects);
			}
		}
	}

	private static List<TLObject> leaves(TLObject node) {
		List<TLObject> result = new ArrayList<>();
		for (TLObject part : GroupAddressTypes.untypedParts(node)) {
			if (part.tType().getName().equals("GroupAddress")) {
				result.add(part);
			} else {
				result.addAll(leaves(part));
			}
		}
		return result;
	}

	public static Test suite() {
		return KBSetup.getSingleKBTest(ServiceTestSetup.createSetup(TestGroupAddressTypesProbe.class,
			ModelService.Module.INSTANCE, LabelProviderService.Module.INSTANCE, PersonManager.Module.INSTANCE));
	}

}
