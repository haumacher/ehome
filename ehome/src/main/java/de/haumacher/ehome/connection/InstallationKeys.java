/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.connection;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

import com.top_logic.basic.Logger;
import com.top_logic.basic.util.ResKey;
import com.top_logic.knowledge.service.PersistencyLayer;
import com.top_logic.knowledge.service.Transaction;
import com.top_logic.model.TLObject;
import com.top_logic.util.Resources;
import com.top_logic.util.error.TopLogicException;

import de.haumacher.ehome.connection.Signatures.Keys;

/**
 * The installation key of a project (homecontrol issue #35, <code>spec/api.md</code>, "Signed runtime
 * images"): one Ed25519 key pair per project, whose private key never leaves E-Home and signs every
 * runtime image deployed to or exported for the project's controllers.
 *
 * <p>
 * The key is generated at the enrollment of the project's first controller
 * ({@link #enrollmentKey(TLObject, boolean)}) or on demand ({@link #create(TLObject)}). It is stored
 * with the project: the private key's seed in <code>installationKey</code>, read by nobody but an
 * administrator (<code>ehome.security.config.xml</code>), the public key in
 * <code>installationPublicKey</code>. A controller learns the public key from its enrollment answer
 * (<code>public_key</code>) and reports the fingerprint of the key it holds at every connection
 * (<code>auth</code>, member <code>key</code>), stored as <code>Controller#deviceKey</code>.
 * </p>
 *
 * <p>
 * The rotation ({@link #renew(TLObject)}) generates a new pair (<code>newInstallationKey</code>,
 * <code>newInstallationPublicKey</code>) and keeps the old one: after <code>ready</code>, a controller
 * that does not hold the key the project wants it to hold ({@link #target(TLObject)}, the new key during
 * a rotation) is offered it (<code>{"type": "key", "public_key", "fingerprint"}</code>); the device
 * keeps it pending until its owner accepts it, and reports <code>key_accepted</code> (or names it in
 * the <code>auth</code> of a later connection). Each image is signed with the key its controller holds
 * ({@link #signer(TLObject, TLObject)}): the new key for a controller that accepted it, the old one
 * otherwise. Once no controller of the project holds the old key any more, the new key becomes the
 * installation key and the old one is forgotten ({@link #deviceKey(String, String)}): the rotation
 * never leaves a device without a key E-Home signs with.
 * </p>
 *
 * <p>
 * The same offer gives a key to a controller enrolled before the project had one, and repairs a
 * controller holding a foreign key: its owner accepts the key on the device.
 * </p>
 */
public final class InstallationKeys {

	/** The project's private installation key (the seed). */
	public static final String KEY = "installationKey";

	/** The project's public installation key. */
	public static final String PUBLIC_KEY = "installationPublicKey";

	/** The private new key of a rotation. */
	public static final String NEW_KEY = "newInstallationKey";

	/** The public new key of a rotation. */
	public static final String NEW_PUBLIC_KEY = "newInstallationPublicKey";

	/** The controller's attribute holding the fingerprint of the key the device holds. */
	public static final String DEVICE_KEY = "deviceKey";

	/** The device's error code refusing an offered key. */
	public static final String BAD_KEY = "ehome.bad_key";

	private InstallationKeys() {
		// Static helpers.
	}

	/**
	 * The installation key of the project, <code>null</code> if it has none.
	 */
	public static Keys key(TLObject project) {
		return keys(project, KEY, PUBLIC_KEY);
	}

	/**
	 * The new key of a rotation of the project, <code>null</code> if none is under way.
	 */
	public static Keys newKey(TLObject project) {
		return keys(project, NEW_KEY, NEW_PUBLIC_KEY);
	}

	/**
	 * The key the project's controllers should hold: the new key of a rotation, otherwise the
	 * installation key; <code>null</code> for a project without a key.
	 */
	public static Keys target(TLObject project) {
		Keys newKey = newKey(project);
		return newKey != null ? newKey : key(project);
	}

	private static Keys keys(TLObject project, String seed, String publicKey) {
		if (project == null || !project.tValid()) {
			return null;
		}
		Object seedValue = project.tValueByName(seed);
		Object publicValue = project.tValueByName(publicKey);
		if (seedValue == null || publicValue == null || !Signatures.isKey(seedValue.toString())
			|| !Signatures.isKey(publicValue.toString())) {
			return null;
		}
		return new Keys(seedValue.toString(), publicValue.toString());
	}

	/**
	 * The key an image for the given controller of the project is signed with: the new key of a
	 * rotation if the controller holds it, otherwise the installation key; <code>null</code> for a
	 * project without a key (the image goes unsigned).
	 *
	 * @param controller
	 *        The controller, <code>null</code> for an image exported as a file.
	 */
	public static Keys signer(TLObject project, TLObject controller) {
		Keys newKey = newKey(project);
		if (newKey != null && controller != null && controller.tValid()
			&& newKey.fingerprint().equals(controller.tValueByName(DEVICE_KEY))) {
			return newKey;
		}
		Keys key = key(project);
		return key != null ? key : newKey;
	}

	/**
	 * The key given to a controller enrolled for the project now, inside the enrollment's
	 * transaction: the key the controllers should hold ({@link #target(TLObject)}); for the project's
	 * first controller, a project without a key gets one. <code>null</code> for a project that has no
	 * key and controllers enrolled before (they and the new one deploy unsigned until a key is created).
	 *
	 * @param first
	 *        Whether no controller is enrolled for the project yet.
	 */
	static Keys enrollmentKey(TLObject project, boolean first) {
		Keys target = target(project);
		if (target == null && first) {
			target = Signatures.generate();
			project.tUpdateByName(KEY, target.seed());
			project.tUpdateByName(PUBLIC_KEY, target.publicKey());
			Logger.info("Generated the installation key " + target.fingerprint() + " of project "
				+ project.tValueByName("name") + ".", InstallationKeys.class);
		}
		return target;
	}

	/**
	 * Why no key can be created for the project now ("Create key"), <code>null</code> if it can: it
	 * has one already.
	 */
	public static ResKey createVeto(TLObject project) {
		return key(project) != null || newKey(project) != null ? I18NConstants.KEY_EXISTS : null;
	}

	/**
	 * Why the project's key cannot be renewed now ("Renew key"), <code>null</code> if it can: the
	 * project has no key, or a controller accepted the new key of a rotation still under way (the
	 * rotation ends when the other controllers accept it too).
	 */
	public static ResKey renewVeto(TLObject project) {
		if (key(project) == null) {
			return I18NConstants.KEY_NONE_TO_RENEW;
		}
		Keys newKey = newKey(project);
		if (newKey != null) {
			List<String> holders = holders(project, newKey.fingerprint());
			if (!holders.isEmpty()) {
				return I18NConstants.KEY_ROTATION_ACCEPTED__FINGERPRINT_CONTROLLERS.fill(newKey.fingerprint(),
					String.join(", ", holders));
			}
		}
		return null;
	}

	/**
	 * Creates the installation key of a project that has none, in a transaction of its own, and offers
	 * it to the project's connected controllers.
	 *
	 * @throws TopLogicException
	 *         If the project has a key ({@link #createVeto(TLObject)}).
	 */
	public static Keys create(TLObject project) {
		ResKey veto = createVeto(project);
		if (veto != null) {
			throw new TopLogicException(veto);
		}
		Keys keys = Signatures.generate();
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			project.tUpdateByName(KEY, keys.seed());
			project.tUpdateByName(PUBLIC_KEY, keys.publicKey());
			tx.commit();
		}
		Logger.info("Created the installation key " + keys.fingerprint() + " of project " + project.tValueByName("name")
			+ ".", InstallationKeys.class);
		offerAll(project);
		return keys;
	}

	/**
	 * Starts a rotation: a new key pair replaces an earlier unaccepted new key and is offered to the
	 * project's connected controllers (the others get it at their next connection), in a transaction
	 * of its own. The installation key stays the signing key until the controllers accept the new
	 * one.
	 *
	 * @throws TopLogicException
	 *         If the key cannot be renewed now ({@link #renewVeto(TLObject)}).
	 */
	public static Keys renew(TLObject project) {
		ResKey veto = renewVeto(project);
		if (veto != null) {
			throw new TopLogicException(veto);
		}
		Keys keys = Signatures.generate();
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			project.tUpdateByName(NEW_KEY, keys.seed());
			project.tUpdateByName(NEW_PUBLIC_KEY, keys.publicKey());
			tx.commit();
		}
		Logger.info("Offering the new installation key " + keys.fingerprint() + " of project "
			+ project.tValueByName("name") + ".", InstallationKeys.class);
		offerAll(project);
		return keys;
	}

	/**
	 * Offers the key the project's controllers should hold to each connected controller of the
	 * project that does not hold it.
	 */
	public static void offerAll(TLObject project) {
		Keys target = target(project);
		if (target == null) {
			return;
		}
		for (TLObject controller : enrolled(project)) {
			ControllerConnection connection = ControllerConnections.get(controller);
			if (connection != null && !target.fingerprint().equals(connection.deviceKey())) {
				try {
					connection.offerKey(target.publicKey(), target.fingerprint());
				} catch (IOException ex) {
					Logger.warn("Offering the key to controller " + connection.controllerId() + " failed: "
						+ ex.getMessage(), InstallationKeys.class);
				}
			}
		}
	}

	/**
	 * The device of the controller with the given id holds the key with the given fingerprint
	 * (<code>null</code> for none), as its authentication or its <code>key_accepted</code> said: stored
	 * with the controller; when it is the new key of a rotation and no controller of the project holds
	 * the old key any more, the new key becomes the installation key and the old one is forgotten.
	 * Runs on the thread of {@link ControllerConnections#execute(Runnable)}.
	 */
	static void deviceKey(String controllerId, String fingerprint) {
		TLObject controller = ControllerConnections.find(controllerId);
		if (controller == null) {
			return;
		}
		TLObject project = controller.tContainer();
		try (Transaction tx = PersistencyLayer.getKnowledgeBase().beginTransaction()) {
			if (!Objects.equals(fingerprint, controller.tValueByName(DEVICE_KEY))) {
				controller.tUpdateByName(DEVICE_KEY, fingerprint);
			}
			Keys newKey = newKey(project);
			Keys key = key(project);
			if (newKey != null && newKey.fingerprint().equals(fingerprint)
				&& (key == null || holders(project, key.fingerprint()).isEmpty())) {
				project.tUpdateByName(KEY, newKey.seed());
				project.tUpdateByName(PUBLIC_KEY, newKey.publicKey());
				project.tUpdateByName(NEW_KEY, null);
				project.tUpdateByName(NEW_PUBLIC_KEY, null);
				Logger.info("The controllers of project " + project.tValueByName("name")
					+ " accepted the new installation key " + newKey.fingerprint() + "; the old key "
					+ (key == null ? "-" : key.fingerprint()) + " is forgotten.", InstallationKeys.class);
			}
			tx.commit();
		}
	}

	/**
	 * The names of the enrolled controllers of the project that hold the key with the given
	 * fingerprint.
	 */
	private static List<String> holders(TLObject project, String fingerprint) {
		List<String> result = new ArrayList<>();
		for (TLObject controller : enrolled(project)) {
			if (fingerprint.equals(controller.tValueByName(DEVICE_KEY))) {
				result.add(String.valueOf(controller.tValueByName("name")));
			}
		}
		return result;
	}

	private static List<TLObject> enrolled(TLObject project) {
		List<TLObject> result = new ArrayList<>();
		if (project != null && project.tValid() && project.tValueByName("controllers") instanceof Collection<?> all) {
			for (Object entry : all) {
				if (entry instanceof TLObject controller && controller.tValid()) {
					result.add(controller);
				}
			}
		}
		return result;
	}

	/**
	 * The state of the project's installation key, for a person: none, its fingerprint, and a
	 * rotation under way.
	 */
	public static String state(TLObject project) {
		Keys key = key(project);
		Keys newKey = newKey(project);
		if (key == null) {
			return text(I18NConstants.KEY_STATE_NONE);
		}
		String result = text(I18NConstants.KEY_STATE__FINGERPRINT.fill(key.fingerprint()));
		if (newKey != null) {
			result += " " + text(I18NConstants.KEY_STATE_ROTATION__NEW_OLD.fill(newKey.fingerprint(), key.fingerprint()));
		}
		return result;
	}

	/**
	 * The key the controller's device holds and how it relates to the project's, for a person;
	 * <code>null</code> for a controller on the LAN, which never reports its key.
	 */
	public static String controllerState(TLObject controller) {
		if (ControllerConnections.controllerId(controller) == null) {
			return null;
		}
		TLObject project = controller.tContainer();
		Keys key = key(project);
		Keys newKey = newKey(project);
		Keys target = target(project);
		Object held = controller.tValueByName(DEVICE_KEY);
		List<String> result = new ArrayList<>();
		if (held == null) {
			result.add(text(I18NConstants.DEVICE_KEY_NONE));
		} else {
			result.add(text(I18NConstants.DEVICE_KEY__FINGERPRINT.fill(held)));
			boolean known = (key != null && key.fingerprint().equals(held))
				|| (newKey != null && newKey.fingerprint().equals(held));
			if (!known) {
				result.add(text(target == null ? I18NConstants.DEVICE_KEY_MISMATCH_NO_KEY__DEVICE.fill(held)
					: I18NConstants.DEVICE_KEY_MISMATCH__DEVICE_PROJECT.fill(held, target.fingerprint())));
			}
		}
		if (target != null && !target.fingerprint().equals(held)) {
			ControllerConnection connection = ControllerConnections.get(controller);
			String pending = connection == null ? null : connection.keyPending();
			if (connection != null && connection.keyError() != null) {
				result.add(text(I18NConstants.DEVICE_KEY_REFUSED__ERROR.fill(connection.keyError())));
			} else if (pending != null && pending.equals(target.fingerprint())) {
				result.add(text(I18NConstants.DEVICE_KEY_PENDING__FINGERPRINT.fill(pending)));
			} else {
				result.add(text(I18NConstants.DEVICE_KEY_OFFERED__FINGERPRINT.fill(target.fingerprint())));
			}
		}
		return String.join(" ", result);
	}

	private static String text(ResKey key) {
		return Resources.getInstance().getString(key);
	}

}
