/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package test.de.haumacher.ehome.programming;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * The memory ETS records as loaded into a device: the attribute <code>LoadedImage</code> of a
 * <code>DeviceInstance</code> in a project file.
 *
 * <p>
 * As far as decoded from <code>samples/EHomeDemo.knxproj</code>: base64 of raw deflate data; a
 * header of 4 bytes (01 00 01 00) and the number of records (4 bytes, little endian); per record
 * the kind (4 bytes, 2 for memory), 4 zero bytes, the address and the length (4 bytes each, little
 * endian), one byte (03), the data and a mask of the length each. ETS records the segments of the
 * application (load state machine 3) whose load control has <code>SegFlags</code> 0x80, not the
 * tables.
 * </p>
 */
public class EtsLoadedImage {

	/**
	 * The records of the loaded image of a device in a project file: memory address to data.
	 *
	 * @param knxproj
	 *        The project archive.
	 * @param projectFile
	 *        The project file in the archive, e.g. <code>P-0353/0.xml</code>.
	 * @param deviceId
	 *        The ID of the device instance.
	 * @return The records, empty if the device has no loaded image.
	 */
	public static Map<Integer, byte[]> records(String knxproj, String projectFile, String deviceId) throws IOException {
		String xml;
		try (ZipFile zip = new ZipFile(knxproj)) {
			ZipEntry entry = zip.getEntry(projectFile);
			try (InputStream in = zip.getInputStream(entry)) {
				xml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
			}
		}
		Matcher matcher = Pattern.compile("<DeviceInstance Id=\"" + Pattern.quote(deviceId) + "\"[^>]*?LoadedImage=\"([^\"]+)\"")
			.matcher(xml);
		Map<Integer, byte[]> result = new LinkedHashMap<>();
		if (!matcher.find()) {
			return result;
		}
		ByteBuffer image = ByteBuffer.wrap(inflate(Base64.getDecoder().decode(matcher.group(1)))).order(ByteOrder.LITTLE_ENDIAN);
		image.getInt();
		int count = image.getInt();
		for (int n = 0; n < count; n++) {
			image.getInt();
			image.getInt();
			int address = image.getInt();
			int length = image.getInt();
			image.get();
			byte[] data = new byte[length];
			image.get(data);
			byte[] mask = new byte[length];
			image.get(mask);
			result.put(address, data);
		}
		return result;
	}

	private static byte[] inflate(byte[] compressed) throws IOException {
		Inflater inflater = new Inflater(true);
		inflater.setInput(compressed);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		byte[] buffer = new byte[4096];
		try {
			while (!inflater.finished()) {
				int length = inflater.inflate(buffer);
				if (length == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
					break;
				}
				out.write(buffer, 0, length);
			}
		} catch (DataFormatException ex) {
			throw new IOException(ex);
		} finally {
			inflater.end();
		}
		return out.toByteArray();
	}

}
