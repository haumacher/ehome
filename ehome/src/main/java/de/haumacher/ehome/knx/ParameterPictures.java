/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.knx;

import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.top_logic.basic.config.ConfigurationException;
import com.top_logic.basic.config.InstantiationContext;
import com.top_logic.basic.io.binary.BinaryData;
import com.top_logic.basic.util.ResKey;
import com.top_logic.model.TLObject;
import com.top_logic.model.TLType;
import com.top_logic.model.search.expr.EvalContext;
import com.top_logic.model.search.expr.GenericMethod;
import com.top_logic.model.search.expr.SearchExpression;
import com.top_logic.model.search.expr.config.dom.Expr;
import com.top_logic.model.search.expr.config.operations.AbstractSimpleMethodBuilder;

import de.haumacher.ehome.model.operational.Parameter;
import de.haumacher.ehome.model.operational.ParameterView;
import de.haumacher.ehome.model.operational.PictureType;

/**
 * The pictures of the parameter dialog (ETS: <code>TypePicture</code>, haumacher/ehome #155): how
 * the parameter editor of a device and the catalog show a parameter of a {@link PictureType}.
 *
 * <p>
 * A picture parameter has no value: nothing is stored on a device, nothing is downloaded, no
 * calculation or validation sees it (it is a {@link de.haumacher.ehome.model.operational.VoidType}).
 * Where it is shown follows from the dynamic structure like for every parameter; this class only
 * decides how: the picture itself ({@link Display#IMAGE}, a format every browser shows), its file
 * with a download ({@link Display#FILE}, any other format), or the note that the file is not there
 * ({@link Display#MISSING}: the type names no file, or the file has no content, because the program
 * was imported before the files of the manufacturer were kept, or the product file did not hold
 * the file; importing the product file again completes it).
 * </p>
 *
 * <p>
 * The TL-Script functions of this class are registered in <code>ehomeConf.config.xml</code> under
 * the names of the {@link Function} constants. Each takes a placed parameter, a parameter or a
 * parameter type; for anything else than a picture they answer <code>null</code>. A view calling
 * them observes <code>ehome.catalog:Baggage</code>, whose content an import may complete.
 * </p>
 */
public class ParameterPictures extends GenericMethod {

	/**
	 * The extensions of the files shown as pictures: the raster formats every browser shows. An SVG
	 * is a document that can carry scripts; served inline from E-Home's origin a manufacturer's file
	 * would run in the user's session if its address were opened directly, so it is offered as a
	 * download like any other format.
	 */
	public static final Set<String> SHOWN = Set.of("png", "jpg", "jpeg", "gif", "webp");

	/**
	 * How a picture of the parameter dialog is shown.
	 */
	public enum Display {
		/** The picture itself. */
		IMAGE,
		/** The name of its file with a download: a format a browser does not show. */
		FILE,
		/** The note that the picture is not there. */
		MISSING;
	}

	/**
	 * The functions, named as they are registered.
	 */
	public enum Function {
		/**
		 * <code>parameterPictureImage(part)</code>: the picture to show, see
		 * {@link ParameterPictures#image(PictureType)}.
		 */
		parameterPictureImage,

		/**
		 * <code>parameterPictureFile(part)</code>: the file to download instead of a picture, see
		 * {@link ParameterPictures#file(PictureType)}.
		 */
		parameterPictureFile,

		/**
		 * <code>parameterPictureNote(part)</code>: the note that the picture is not there, see
		 * {@link ParameterPictures#note(PictureType)}.
		 */
		parameterPictureNote;
	}

	private final Function _function;

	/**
	 * Creates a {@link ParameterPictures}.
	 */
	protected ParameterPictures(String name, Function function, SearchExpression[] arguments) {
		super(name, arguments);
		_function = function;
	}

	/**
	 * The picture type of the given placed parameter, parameter or parameter type,
	 * <code>null</code> for anything else.
	 */
	public static PictureType pictureType(Object part) {
		Object type = part;
		if (type instanceof ParameterView view) {
			type = view.getParameter();
		}
		if (type instanceof Parameter parameter) {
			type = parameter.getType();
		}
		return type instanceof PictureType picture ? picture : null;
	}

	/**
	 * How the given picture is shown.
	 */
	public static Display display(PictureType type) {
		TLObject file = type.getPicture();
		if (file == null || content(file) == null) {
			return Display.MISSING;
		}
		BinaryData content = content(file);
		String contentType = content.getContentType();
		if (SHOWN.contains(extension(fileName(file))) && contentType != null && contentType.startsWith("image/")) {
			return Display.IMAGE;
		}
		return Display.FILE;
	}

	/**
	 * The picture to show for the given picture type, <code>null</code> if it is not shown as a
	 * picture (see {@link #display(PictureType)}).
	 */
	public static BinaryData image(PictureType type) {
		return display(type) == Display.IMAGE ? content(type.getPicture()) : null;
	}

	/**
	 * The file of the given picture type to download, if the browser does not show it as a picture;
	 * <code>null</code> otherwise.
	 */
	public static BinaryData file(PictureType type) {
		return display(type) == Display.FILE ? content(type.getPicture()) : null;
	}

	/**
	 * The note that the picture of the given type is not there, <code>null</code> if it is.
	 */
	public static ResKey note(PictureType type) {
		if (display(type) != Display.MISSING) {
			return null;
		}
		TLObject file = type.getPicture();
		return file == null ? I18NConstants.PICTURE_UNKNOWN
			: I18NConstants.PICTURE_MISSING__NAME.fill(fileName(file));
	}

	private static BinaryData content(TLObject file) {
		return (BinaryData) file.tValueByName("content");
	}

	private static String fileName(TLObject file) {
		Object name = file.tValueByName("name");
		return name == null ? "" : name.toString();
	}

	private static String extension(String name) {
		int dot = name.lastIndexOf('.');
		return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
	}

	@Override
	public GenericMethod copy(SearchExpression[] arguments) {
		return new ParameterPictures(getName(), _function, arguments);
	}

	@Override
	public Object getId() {
		return _function;
	}

	@Override
	public TLType getType(List<TLType> argumentTypes) {
		return null;
	}

	@Override
	public boolean isSideEffectFree() {
		return true;
	}

	@Override
	public boolean canEvaluateAtCompileTime(Object[] arguments) {
		return false;
	}

	@Override
	protected Object eval(Object[] arguments, EvalContext definitions) {
		PictureType type = pictureType(arguments[0]);
		if (type == null) {
			return null;
		}
		switch (_function) {
			case parameterPictureImage:
				return image(type);
			case parameterPictureFile:
				return file(type);
			case parameterPictureNote:
				return note(type);
		}
		return null;
	}

	/**
	 * Builder of a {@link ParameterPictures} function: the {@link Function} named as the function
	 * is registered.
	 */
	public static final class Builder extends AbstractSimpleMethodBuilder<ParameterPictures> {

		private final Function _function;

		/**
		 * Creates a {@link Builder}.
		 */
		public Builder(InstantiationContext context, Config<?> config) {
			super(context, config);
			_function = Function.valueOf(config.getName());
		}

		@Override
		public ParameterPictures build(Expr expr, SearchExpression[] args) throws ConfigurationException {
			checkSingleArg(expr, args);
			return new ParameterPictures(getConfig().getName(), _function, args);
		}

		@Override
		public Object getId() {
			return _function;
		}
	}

}
