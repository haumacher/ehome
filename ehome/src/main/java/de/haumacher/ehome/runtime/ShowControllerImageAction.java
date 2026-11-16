/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.runtime;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import com.top_logic.basic.CalledByReflection;
import com.top_logic.basic.config.InstantiationContext;
import com.top_logic.basic.config.PolymorphicConfiguration;
import com.top_logic.basic.config.annotation.TagName;
import com.top_logic.basic.config.annotation.defaults.ClassDefault;
import com.top_logic.basic.io.binary.BinaryDataFactory;
import com.top_logic.layout.react.ReactContext;
import com.top_logic.layout.view.command.ViewAction;
import com.top_logic.layout.view.command.WithResultActions;
import com.top_logic.model.TLObject;
import com.top_logic.util.Resources;

import de.haumacher.ehome.runtime.ControllerClient.Answer;
import de.haumacher.ehome.runtime.binary.ImageDecoder;
import de.haumacher.ehome.runtime.binary.ImageDecoder.DecodeException;

/**
 * {@link ViewAction} showing the image the controller of the input project
 * (<code>ehome.project:Project</code>) runs (<code>GET /api/image</code>, haumacher/ehome #176): the
 * binary image read back into its source form ({@link ImageDecoder}).
 *
 * <p>
 * The result is a map for a dialog: <code>summary</code> (what the controller runs: project, revision,
 * format, size, CRC, whether it is signed; or why there is nothing to show), <code>file</code> (the
 * source form as JSON file, <code>null</code> when there is none) and <code>binary</code> (the image as
 * the controller holds it, <code>.bin</code>, <code>null</code> when there is none).
 * </p>
 */
public class ShowControllerImageAction implements ViewAction {

	/**
	 * Configuration of {@link ShowControllerImageAction}.
	 */
	@TagName("show-controller-image")
	public interface Config extends PolymorphicConfiguration<ShowControllerImageAction>, WithResultActions {

		@Override
		@ClassDefault(ShowControllerImageAction.class)
		Class<? extends ShowControllerImageAction> getImplementationClass();
	}

	/**
	 * Creates a {@link ShowControllerImageAction} from configuration.
	 */
	@CalledByReflection
	public ShowControllerImageAction(InstantiationContext context, Config config) {
		// No configuration.
	}

	@Override
	public Object execute(ReactContext context, Object input) {
		return show((TLObject) input);
	}

	/**
	 * The image the controller of the given project runs, see the class comment.
	 */
	public static Map<String, Object> show(TLObject project) {
		Resources resources = Resources.getInstance();
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("file", null);
		result.put("binary", null);
		TLObject controller = Deployer.controller(project);
		if (controller == null) {
			result.put("summary", resources.getString(I18NConstants.ERROR_NO_CONTROLLER));
			return result;
		}
		ControllerClient client = Deployer.client(controller);
		Answer answer;
		try {
			answer = client.getImage();
		} catch (IOException ex) {
			result.put("summary", resources.getString(
				I18NConstants.COMPARE_UNREACHABLE__ADDRESS_ERROR.fill(client.address(), Deployer.reason(ex))));
			return result;
		}
		if (answer.status() == 404) {
			result.put("summary", resources.getString(I18NConstants.COMPARE_NO_IMAGE));
			return result;
		}
		if (!answer.ok()) {
			String error = answer.string("error");
			result.put("summary", resources.getString(I18NConstants.COMPARE_FAILED__STATUS_ERROR
				.fill(Integer.valueOf(answer.status()), error != null ? error : answer.text().strip())));
			return result;
		}
		byte[] bytes = answer.bytes();
		String base = RuntimeImage.binaryFileName(project);
		base = "controller-" + base.substring(0, base.length() - ".bin".length());
		result.put("binary", BinaryDataFactory.createBinaryData(bytes, RuntimeImage.BINARY_CONTENT_TYPE, base + ".bin"));
		try {
			ImageDecoder.Header header = ImageDecoder.header(bytes);
			Map<String, Object> view = ImageDecoder.decode(bytes);
			Map<?, ?> image = (Map<?, ?>) view.get("image");
			result.put("file", BinaryDataFactory.createBinaryData(Json.write(view).getBytes(StandardCharsets.UTF_8),
				RuntimeImage.CONTENT_TYPE, base + ".json"));
			result.put("summary", resources.getString(I18NConstants.CONTROLLER_IMAGE__PROJECT_REVISION_FORMAT_BYTES_CRC.fill(
				Objects.toString(image.get("projectId"), ""), image.get("revision"), header.format(),
				Integer.valueOf(bytes.length), header.crcText())) + " "
				+ resources.getString(header.signed() ? I18NConstants.CONTROLLER_IMAGE_SIGNED
					: I18NConstants.CONTROLLER_IMAGE_UNSIGNED));
		} catch (DecodeException ex) {
			result.put("summary", resources.getString(I18NConstants.CONTROLLER_IMAGE_UNREADABLE__REASON.fill(ex.getMessage())));
		}
		return result;
	}

}
