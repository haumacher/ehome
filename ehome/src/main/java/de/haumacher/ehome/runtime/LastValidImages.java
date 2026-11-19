/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.runtime;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Objects;

import com.top_logic.basic.Logger;
import com.top_logic.basic.io.binary.BinaryData;
import com.top_logic.basic.io.binary.BinaryDataFactory;
import com.top_logic.model.TLObject;

import de.haumacher.ehome.model.project.Project;
import de.haumacher.ehome.model.runtime.Controller;
import de.haumacher.ehome.model.runtime.Deployment;
import de.haumacher.ehome.runtime.ControllerClient.Answer;
import de.haumacher.ehome.runtime.binary.ImageDecoder;
import de.haumacher.ehome.runtime.binary.ImageDecoder.DecodeException;

/**
 * The last valid deployment of a controller (haumacher/ehome #176, homecontrol
 * <code>spec/runtime-image.md</code> I3, I9): the binary image the controller ran, byte for byte with
 * its signature, which E-Home sends again when a newer image does not load and the controller runs
 * none.
 *
 * <p>
 * It is stored with the newest accepted deployment of the controller
 * (<code>ehome.runtime:Deployment#image</code>, {@link #store(TLObject, TLObject, byte[])}): the older
 * deployments of that controller lose theirs, so at most one image per controller is kept, and it
 * survives a restart of E-Home. Before every deployment the image the controller runs is asked for as
 * well ({@link #fetch(TLObject, ControllerClient)}), so that a deployment that fails sends back what ran
 * before, also when another tool deployed it.
 * </p>
 */
public final class LastValidImages {

	/** The attribute of a deployment holding its binary image. */
	public static final String IMAGE = "image";

	/** The file name of a stored image. */
	private static final String FILE_NAME = "image.bin";

	/**
	 * The last valid image of a controller.
	 *
	 * @param bytes
	 *        The binary image as the controller ran it.
	 * @param revision
	 *        Its <code>image.revision</code>, <code>null</code> if it cannot be read.
	 * @param own
	 *        Whether it is an image of the controller's project (its <code>image.projectId</code> is
	 *        the project's id).
	 */
	public record Entry(byte[] bytes, Long revision, boolean own) {
		// A value.
	}

	private LastValidImages() {
		// Static only.
	}

	/**
	 * The image of the newest accepted deployment of the controller that keeps one, <code>null</code>
	 * if there is none.
	 */
	public static Entry get(TLObject controller) {
		TLObject project = controller == null ? null : controller.tContainer();
		if (!(project instanceof Project owner)) {
			return null;
		}
		for (Deployment deployment : owner.getDeployments()) {
			if (deployment.getController() == controller && deployment.getAccepted()
				&& deployment.getImage() instanceof BinaryData data) {
				try (InputStream in = data.getStream()) {
					return entry(controller, in.readAllBytes());
				} catch (IOException ex) {
					Logger.error("The stored image of a deployment cannot be read.", ex, LastValidImages.class);
					return null;
				}
			}
		}
		return null;
	}

	/**
	 * Stores the image with the given accepted deployment of the controller and removes it from every
	 * other deployment of that controller; called in the transaction recording the deployment.
	 */
	public static void store(TLObject controller, TLObject deployment, byte[] image) {
		TLObject project = controller.tContainer();
		if (project instanceof Project owner) {
			for (Deployment other : owner.getDeployments()) {
				if (other != deployment && other.getController() == controller && other.getImage() != null) {
					other.setImage(null);
				}
			}
		}
		((Deployment) deployment).setImage(
			BinaryDataFactory.createBinaryData(image, RuntimeImage.BINARY_CONTENT_TYPE, FILE_NAME));
	}

	/**
	 * Asks the controller for the image it runs (<code>GET /api/image</code>): the last valid image if
	 * the next one fails; <code>null</code> if it does not answer with one.
	 */
	public static Entry fetch(TLObject controller, ControllerClient client) {
		try {
			Answer answer = client.getImage();
			if (answer.status() == 200 && answer.bytes().length > 0) {
				ImageDecoder.header(answer.bytes());
				return entry(controller, answer.bytes());
			}
		} catch (IOException | DecodeException ex) {
			Logger.info("The image controller " + ((Controller) controller).getName() + " runs could not be read: "
				+ ex.getMessage(), LastValidImages.class);
		}
		return null;
	}

	private static Entry entry(TLObject controller, byte[] image) {
		Long revision = null;
		boolean own = false;
		try {
			Map<String, Object> view = ImageDecoder.decode(image);
			if (view.get("image") instanceof Map<?, ?> header) {
				revision = header.get("revision") instanceof Number number ? Long.valueOf(number.longValue()) : null;
				TLObject project = controller.tContainer();
				own = project instanceof Project owner && Objects.equals(header.get("projectId"), owner.getExtId());

			}
		} catch (DecodeException ex) {
			// Kept as it is: the controller ran it.
		}
		return new Entry(image.clone(), revision, own);
	}

}
