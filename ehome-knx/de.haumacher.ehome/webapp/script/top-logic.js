;
// Compiled from 'script/browser-utilities.js'
/* -------------------------------------------------------------------------- *
 * Extends Array
 * -------------------------------------------------------------------------- */
 
function arrayCopy(source) {
	var copy = new Array();
	
	for (var i = 0; i < source.length; i++) {
		copy[i] = source[i];
 	}

	return copy;
}

function arrayRemove(target, element) {
	for (var i = target.length - 1; i >= 0; i--) {
		if (target[i] === element) {
			target.splice(i, 1);
		}
 	}
}

function arrayContains(array, element) {
	for (var i = 0; i < array.length; i++) {
		if (array[i] == element) {
			return true;
		}
 	}

	return false;
}

function arrayIndexOf(array, element, startIndex) {
	for (var i = (startIndex == undefined) ? 0 : startIndex; i < array.length; i++) {
		if (array[i] == element) {
			return i;
		}
 	}

	return -1;
}

/* -------------------------------------------------------------------------- *
 * Functional Utilities
 * -------------------------------------------------------------------------- */
	 
/**
 * Return a function that applies the given method on the given target object.
 */
function thisClosure(target, method) {
	return function() {
		method.apply(target, arguments);
	};
}

/**
 * Iterates over the given array and applies the given function to each array element. 
 * Returns the first non-null result of a function application. The iteration is aborted
 * after the first function application returns a non-null result. If none of the function
 * applications returns a non-null value, the given default value is returned.
 */
function foreach(array, method, defaultValue) {
	for (var i = 0; i < array.length; i++) {
		var result = method(array[i]);
			
		if (result === undefined) continue;
		
		return result;
	}
		
	return defaultValue;
}

/**
 * Returns an array of results of applications of the given function to the elements 
 * of the given array.
 */
function map(array, method) {
	var result = [];
	for (var i = 0; i < array.length; i++) {
		result.push(method(array[i]));
	}
	return result;	
}

/**
 * Return all elements of the given array, for which the given method 
 * returns true.
 */
function filter(array, method) {
	var result = [];
	for (var i = 0; i < array.length; i++) {
		var element = array[i];

		if (method(element)) {
			result.push(element);
		}
	}
	return result;	
}

/**
 * Returns the first element of the array for witch the test returns true
 * (more precise: a value coerced to true in an if-condition),
 * or undefined if there is no such element.
 */
function findFirst(array, test) {
	for (var i = 0; i < array.length; i++) {
		if (test(array[i])) {
			return array[i];
		}
	}
	return undefined;
}

/**
 * Creates a list starting with the given start value.
 * The second element is the result of applying the successor function on the start element.
 * The third element is the result of applying the successor function on the second element.
 * The element (n+1) is the result of applying the successor function on the n-th element.
 * The list ends when the successor function returns undefined or null.
 * If the start value is undefined or null, the list will be empty.
 * This means, you cannot create a list containing undefined or null with this function.
 * The successor Function has one parameter: The element for which it should return the successor.
 */
function createList(start, successorFunction) {
	var resultList = [];
	var currentObject = start;
	while (currentObject != null) {
		resultList.push(currentObject);
		currentObject = successorFunction(currentObject);
	}
	return resultList;
}

/**
 * Creates a list starting with the given start value.
 * The second element is the property stored under the given property name in the start element.
 * The third element is the property stored under the given property name in the second element.
 * The element (n+1) is the property stored under the given property name in the n-th element.
 * The list ends when the property is undefined or null.
 * If the start value is undefined or null, the list will be empty.
 * This means, you cannot create a list containing undefined or null with this function.
 * Example usage:
 * createListFromProperty(element, 'parent')
 */
function createListFromProperty(start, propertyName) {
	return createList(start, function(object) { return object[propertyName]; });
}


/* -------------------------------------------------------------------------- *
 * Element creation functions
 * -------------------------------------------------------------------------- */

/** <<object>>
 * This is the global onload method handler.
 */
OnLoadHandler = {
	_onLoadHandler: new Array(),

	addHandler: function(handler) {
		this._onLoadHandler.push(handler);
	},

	onLoad: function() {
		for (var i = 0; i < this._onLoadHandler.length; i++) {
			this._onLoadHandler[i]();
		}
	}
}

/* -------------------------------------------------------------------------- *
 * Element creation functions
 * -------------------------------------------------------------------------- */
	
/** <<object>>
 * This boolean indicates if the dnd div is present.
 */
var dndDivCreated = false;
	
function isDndDivCreated() {
	return dndDivCreated;
}

function createDndDiv(text, x, y, width, height) {
	var parent = document.body;
	
	if (document.createElement) {
		var elem = document.createElement("div");
		elem.id = "dndOptionDiv";
		elem.style.position = "absolute";
		if (width != undefined) {
			elem.style.width = width + "px";
		}
		if (height != undefined) {
			elem.style.height = height + "px";
		}
		elem.style.background = "#DDDDDD";
		elem.style.border = "solid 1px"
		elem.style.padding = "2px"
		elem.innerHTML = text;
		setDivPos(elem, x, y);
		parent.appendChild(elem);
	} else {
		parent.insertAdjacentHTML("BeforeEnd", "<div id='dndOptionDiv' style='position: absolute; left: " + x + "px; top: " + y + "px; width: 50px; height: 50px; border-style: solid; border-width: 1px; background-color: #DDDDDD;'>" + text + "</div>");
	}
	
	dndDivCreated = true;
}
	
function moveDndDiv(x, y) {
	var div = document.getElementById("dndOptionDiv");
	setDivPos(div, x, y);
}

function setDivPos(div, x, y) {
	div.style.left = x + "px";
	div.style.top = 5 + y + "px";
}
	
function removeDndDiv() {
	var div = document.getElementById("dndOptionDiv");
	div.parentNode.removeChild(div);
	dndDivCreated = false;
}

/* -------------------------------------------------------------------------- *
 * Drag and Drop related functions
 * -------------------------------------------------------------------------- */
	
/** <<function>>
 * Initialises the drag and drop handler needed for dnd support.
 *
 * @param dragSourceArray an array of objects that will be treated as drag
 *        sources
 * @param dropTargetArray an array of objects that will be treated as drop
 *        targets
 * @param dragStartHandler the dragStartHandler from the user
 * @param dropHandler the dropHandler from the user
 */
function initDnD(dragSourceArray, dropTargetArray, dragStartHandler, dropHandler) {
	/* Register the drag sources. */
	for (var i = 0; i < dragSourceArray.length; i++) {
		addDragSource(dragSourceArray[i], dragStartHandler);
	}
	
	/* Register the drop targets. */
	for (var i = 0; i < dropTargetArray.length; i++) {
		var dropObject = dropTargetArray[i];
	
		if (dropObject.addEventListener) {
			dropObject.addEventListener("mouseup", dropHandler, false);
		} else {
			dropObject.attachEvent("onmouseup", dropHandler);
		}
	}
}

function addDragSource(element, dragStartHandler) {
	if (element.addEventListener) {
		element.addEventListener("mousedown", dragStartHandler, false);
	} else {
		element.attachEvent("onmousedown", dragStartHandler);
	}
}

var _dragHandler = null;
var _selectHandler = null;

/** <<function>>
 * Registers the MouseMove handler. This method is called on starting of a
 * drag operation.
 *
 * @param dragHandler the dragHandler from the user
 */
function registerMove(dragHandler) {
	_dragHandler = dragHandler;
	
	/* Enable the dragmove handler. */
	if (document.addEventListener) {
		document.addEventListener("mousemove", _dragHandler, false);
	} else {
		document.attachEvent("onmousemove", _dragHandler);
	}
	
	/* Disable the document wide text selection. */
	if (document.onselectstart) {
		_selectHandler = document.onselectstart;
		document.onselectstart = function() {return false;};
	} else {
		_selectHandler = document.onmousedown;
		document.onmousedown = function() {return false;};
	}
}
	
/** <<function>>
 * Removes the MouseMove handler. This method is called on stopping of a
 * drag operation.
 */
function deregisterMove() {
	/* Remove the dragmove handler. */
	if (document.removeEventListener) {
		document.removeEventListener("mousemove", _dragHandler, false);
	} else {
		document.detachEvent("onmousemove", _dragHandler);
	}
	
	/* Reenable the document wide text selection. */
	if (document.onselectstart) {
		document.onselectstart = _selectHandler;
	} else {
		document.onmousedown = _selectHandler;
	}
	
	_selectHandler = null;
}

/* -------------------------------------------------------------------------- *
 * Debug functions
 * -------------------------------------------------------------------------- */

function debugObject(object, stringArray) {
	var message = "Found object [" + object + "]\n\n";
	
	for (name in object) {
		if (!isExcludeProperty(name, object) && containsString(name, stringArray)) {
			var value = object[name];
			message += name + ":" + value;
			
			if (value != null) {
				if (notEmpty(value.id)) {
					message += " - id:" + value.id;
				} else if (notEmpty(value.label)) {
					message += " - label:" + value.label;
				} else if (notEmpty(value.text)) {
					message += " - text:" + value.text;
				} else if (!isExcludeValue(value)) {
					var innerMessage = "\n----------\n";
					
					for (innerName in value) {
						try {
							if (!isExcludeInnerValue(value[innerName])) {
								innerMessage += innerName + ":" + value[innerName] + "-+-";
							}
						} catch (err) {}
					}
					
					message += " - inner properties:" + innerMessage + "\n----------";
				}
			}
			
			message += "\n";
		}
	}
	
	alert(message);
}

function isExcludeProperty(propertyName, property) {
	var type = typeof(property[propertyName]);
	return startsWith(type, "function") ||
	       startsWith(propertyName, "on") ||
				 containsIgnoreCase(propertyName, "all") ||
				 containsIgnoreCase(propertyName, "child") ||
				 containsIgnoreCase(propertyName, "inner") ||
				 containsIgnoreCase(propertyName, "form") ||
	       containsIgnoreCase(propertyName, "document") ||
	       containsIgnoreCase(propertyName, "outer") ||
	       containsIgnoreCase(propertyName, "parent") ||
	       containsIgnoreCase(propertyName, "namespaces") ||
	       containsIgnoreCase(propertyName, "sibling") ||
	       containsIgnoreCase(propertyName, "style");
}

function isExcludeValue(propertyValue) {
	var type = typeof(propertyValue);
	return !startsWith(type, "object");
}

function isExcludeInnerValue(propertyValue) {
	var type = typeof(propertyValue);
	return startsWith(type, "function");
}

/* -------------------------------------------------------------------------- *
 * String manipulation methods
 * -------------------------------------------------------------------------- */
	
function containsString(string, stringArray) {
	if (stringArray != null) {
		for (var i = 0; i < stringArray.length; i++) {
			var s = stringArray[i];
			
			if (containsIgnoreCase(string, s)) {
				return true;
			}
		}
	
		return false;
	} else {
		return true;
	}
}

function notEmpty(string) {
	return (string != null) && (string != "");
}

function containsIgnoreCase(left, right) {
	var leftLower = left.toLowerCase();
	var rightLower = right.toLowerCase();
	
	return leftLower.indexOf(rightLower) > -1;
}

function startsWith(left, right) {
	return left.indexOf(right) == 0;
}

/* -------------------------------------------------------------------------- *
 * Utility Objects
 * -------------------------------------------------------------------------- */

/** <<constructor>>
 * Constructs a new Point object.
 */
function Point(x, y) {
	this.x = x;
	this.y = y;
}

/** <<prototype>>
 * The Point prototype.
 */
Point.prototype = {
	
	verticalDistance: function(point) {
		return Math.abs(this.y - point.y);
	}
	
}
;
// Compiled from 'script/soap.js'
/**
 * Webservice implementation in JavaScript. 
 *
 * This class allows to send SOAP messages and receive corresponding 
 * replies. Replies are directed to a custom handler function. 
 * 
 * Author:  <a href=mailto:bhu@top-logic.com>Bernhard Haumacher</a>
 */
function WebService(serviceUrl) {
	this.serviceUrl = serviceUrl;
	
	this.NEWLINE = '\r\n';

	this.onError = function(status) {
		alert(status);
	};
	
	this.onCompletion = function() {};

	/**
	 * Browser independent creation of a XMLHTTP object.
	 */
	this.createXMLHTTP = function() {
		try {
			// Native object supported by most browsers (including upcomming MS IE 7.0.
			return new XMLHttpRequest();
		} catch (e) {
			try {
				return new ActiveXObject("Microsoft.XMLHTTP");
			} catch (e) {
				return new ActiveXObject("Msxml2.XMLHTTP");
			}
		}
	};

	/**
	 * Sends a request asynchron to the server
	 * 
	 * @param {String}
	 *            request the XML request send to the server
	 * @param {Object}
	 *            onCompletion handler which is called when the response of the
	 *            server arrives. is calles with the response XML. may be null.
	 * @param {Object}
	 *            onError handler which is called when an error occurs. may be
	 *            null.
	 * @param {Boolean}
	 *            ignoreResponse whether the response of the server is irrelevant
	 * @param {Boolean}
	 *            sequential whether to wait for the answer to be returned from 
	 *            the server before continuing execution
	 */
	this.sendRequest = function(request, onCompletion, onError, ignoreResponse, sequential, retry) {
		var http = this.createXMLHTTP();

		http.open("POST", this.CONTEXT_PATH + this.serviceUrl, /*async*/ !sequential);
		http.setRequestHeader("SOAPAction", "\"\"");
		http.setRequestHeader("Content-Type", "text/xml; charset=utf-8");
		if (retry != null) {
			http.setRequestHeader("X-Retry", "" + retry);
		}
		
		if (!ignoreResponse) {
    		if (onCompletion == null) {
				onCompletion = this.onCompletion;
			}

			if (onError == null) {
				onError = this.onError;
			}

			http.onreadystatechange = function() {
				if (http.readyState == 4) { // means all data are received
					var status = http.status;
					if (status == 200) { // means everything OK
						// see #4213, #6742, and AJAXServlet
						var responseServer = http.getResponseHeader("X-Response-Server");
						if (responseServer == null || responseServer.indexOf("tl-ajax") < 0) {
							BAL.clientLog("Invalid AJAX response: " + http.responseText);
							onError(services.i18n.SERVER_CLIENT_COMMUNICATION_CRASHED, this.ERROR_NO_AJAX_RESPONSE);
							return;
						}

						// DEBUG: Display response message.
						//
						// alert("AJAX-RESPONSE:\n"+ http.responseText);

						var response = http.responseXML;

						if (response == null || response.documentElement == null) {
							// Signals parse error in IE.
							var message = "Illegal AJAX response: "	+ http.responseText;
							services.log.error(message);
							onError(services.i18n.INVALID_XML_RESPONSE, this.ERROR_CANNOT_PARSE_RESPONSE);
							return;
						}

						if (response.documentElement.namespaceURI == "http://www.mozilla.org/newlayout/xml/parsererror.xml") {
							// Signals parse error in FF.
							var message = "Illegal AJAX response ("	+ response.documentElement.textContent + "): " + http.responseText;
							services.log.error(message);
							onError(services.i18n.INVALID_XML_RESPONSE, this.ERROR_CANNOT_PARSE_RESPONSE);
							return;
						}

						onCompletion(response);
					} else {
						onError(services.i18n.NETWORK_ERROR + " (HTTP/" + status + ")", status);
					}

					/**
					 * see #32: must delete the onreadystatechange function of
					 * the http-request object to give the IE a chance to remove
					 * it
					 */
					delete http['onreadystatechange'];
				}
			};
		}
		http.send(request);
	};
};

WebService.prototype = {
	ERROR_NO_AJAX_RESPONSE: -1,
	ERROR_CANNOT_PARSE_RESPONSE: -2
};

/**
 * Global variable, where all installed services are registered (by 
 * scripts that are executed after this script). 
 */
var services = {
	// Anchor for internationalized texts displayed directly from 
	// static JavaScript functions.
	i18n : {
		INCONSISTENT_SERVER_REPIES: "Inconsistent server replies, page is reloaded. Please check your inputs."
	}
};
;
// Compiled from 'script/queuing.js'
/**
 * This queue limits parallel AJAX requests to a defined amount. The number of parallel requests
 * can be specified at creation time of this queue. Requests, which can't be send immediately will
 * be enqueued. They will be executed when they have been moved to the first waiting position and
 * the next free slot becomes available. Thereby the order of execution follows FIFO rule.
 */
 queuing = {
 	// Namespace declaration
 };
 
 queuing.RequestQueue = function(allowedAmountOfParallelRequests) {
 	/* 
 	 * Due to error in ECMAScript specification, which sets the incorrect this-pointer
 	 * in inner functions
 	 */
 	var self = this;
 	
 	this.allowedAmountOfParallelRequests = allowedAmountOfParallelRequests;
 	this.activeRequestsCount = 0;
 	this.waitingQueue = new Array();
 	this._active = true;
 	
 	this.stop = function() {
 		this._active = false;
 	};
 	
 	this.resume = function() {
 		this._active = true;
 		this._processNext();
 	};
 	
 	this.setAllowedParallelRequests = function(size) {
 		this.allowedAmountOfParallelRequests = size;
 	};
 	
 	this._processNext = function() {
 		while (self.isFreeRequestSlotAvailable() && !self.isWaitingQueueEmpty()) {
			var queueObject = self.dequeueRequest();
			self.executeNextRequest(queueObject.requestFunction, queueObject.optionalResponseFunction);
 		}
 	};
 	
 	this.isFreeRequestSlotAvailable = function() {
 		return self.activeRequestsCount < self.allowedAmountOfParallelRequests;
 	};
 	
 	this.isWaitingQueueEmpty = function() {
 		return self.waitingQueue.length == 0;
 	};
 	
 	this.allocateRequestSlot = function() {
 		self.activeRequestsCount++;
 	};
 	
 	this.freeRequestSlot = function() {
 		self.activeRequestsCount--;
 	};
 	
 	this._enqueueRequest = function(requestFunction, optionalResponseFunction) {
 		var queueObject = new Object();
 		queueObject.requestFunction = requestFunction;
 		queueObject.optionalResponseFunction = optionalResponseFunction;
		self.waitingQueue.push(queueObject);
 	};
 	
 	this.dequeueRequest = function() {
 		return self.waitingQueue.shift();
 	};
 	
 	this.executeNextRequest = function(requestFunction, optionalResponseFunction) {
 		self.allocateRequestSlot();
 		requestFunction.call();
 		self.registerStandardResponseAndIncludeOptionalResponseFunction(optionalResponseFunction);
 	};
 	
 	this.registerStandardResponseAndIncludeOptionalResponseFunction = function(optionalResponseFunction) {
 		services.ajax.runLater(function() {
 			self.freeRequestSlot();
 			
 			if(self.existOptionalResponseFunction(optionalResponseFunction)) {
	 			optionalResponseFunction.call();
 			}
 			
 			if(self._active) {
 				self._processNext();
 			}
 		});
 	};
 	
 	this.existOptionalResponseFunction = function(optionalResponseFunction) {
 		return (optionalResponseFunction != undefined) && (optionalResponseFunction != null);
 	};
 }
 
 /**
  * 
  * @param {Function} requestFunction - function, which triggers the ajax request
  * @param {Function} optionalResponseFunction - optional function, which shall be executed,
  * 											 after the server sends the response to the
  * 											 request, triggered by the function, given as
  * 											 first argument 											 this function 
  */
 queuing.RequestQueue.prototype.executeRequest = function(requestFunction, optionalResponseFunction) {
 	if (this._active && this.isFreeRequestSlotAvailable()) {
 		this.executeNextRequest(requestFunction, optionalResponseFunction);
 	} else {
 		this._enqueueRequest(requestFunction, optionalResponseFunction); 		
 	}
 };
// Compiled from 'script/linkedMap.js'
/**
 * LinkedMap is a stub implementation of a map which returns the values in the
 * order in which they are inserted.
 */
services.LinkedMap = function() {
	this.key = 0;
	this.clear();
};

services.LinkedMap.prototype.clear = function() {
	this.storage = new Object();
	this.order = new Array();
};

services.LinkedMap.prototype.newKey = function() {
	return this.key++;
};

/**
 * 
 * @param key - any number or string
 * @param value - any object
 */
services.LinkedMap.prototype.put = function(key, value) {
	if (! this.storage.hasOwnProperty(key)) {
		this.order.push(key);
	}
	this.storage[key] = value;
};

services.LinkedMap.prototype.values = function() {
	if (this.order.length == 0) {
		return null;
	}
	var values = new Array();
	for (var index = 0; index < this.order.length; index++) {
		values[index] = this.storage[this.order[index]];
	}
	return values;
};

services.LinkedMap.prototype.keys = function() {
	if (this.order.length == 0) {
		return null;
	}
	var keys = new Array();
	for (var index = 0; index < this.order.length; index++) {
		keys.push(this.order[index]);
	}
	return keys;
};

services.LinkedMap.prototype.size = function() {
	return this.order.length;
};

services.LinkedMap.prototype.contains = function(key) {
	return this.storage[key] != null;
};
;
// Compiled from 'script/simpleajax.js'
/**
 * Webservice for executing AJAX commands.
 * 
 * Author: <a href=mailto:bhu@top-logic.com>Bernhard Haumacher</a>
 * 
 * @see test/xml/simpleajax/simpleajax.xsd
 */
services.AJAXServiceClass = function() {

	this.AJAX_NS = "http://top-logic.com/base/service/ajax";
	this.XHTML_NS = "http://www.w3.org/1999/xhtml";
	
	this.ACTIONS_ELEMENT       = new BAL.QName(this.AJAX_NS, "actions");
	this.ACTION_ELEMENT        = new BAL.QName(this.AJAX_NS, "action");
	this.ID_ELEMENT            = new BAL.QName(this.AJAX_NS, "id");
	this.STOP_ID_ELEMENT       = new BAL.QName(this.AJAX_NS, "stop-id");
	this.FRAGMENT_ELEMENT      = new BAL.QName(this.AJAX_NS, "fragment");
	this.CODE_ELEMENT          = new BAL.QName(this.AJAX_NS, "code");
	this.PROPERTY_NAME_ELEMENT = new BAL.QName(this.AJAX_NS, "property-name");
	this.OLD_VALUE_ELEMENT     = new BAL.QName(this.AJAX_NS, "old-value");
	this.NEW_VALUE_ELEMENT     = new BAL.QName(this.AJAX_NS, "new-value");
	this.PROPERTY_ELEMENT      = new BAL.QName(this.AJAX_NS, "property");
	this.FUNCTION_ELEMENT      = new BAL.QName(this.AJAX_NS, "function");
	this.ARGUMENTS_ELEMENT     = new BAL.QName(this.AJAX_NS, "arguments");
	this.SCRIPT_ELEMENT        = new BAL.QName(this.XHTML_NS, "script");

	this.IS_PROPERTY_ELEMENT = this.PROPERTY_ELEMENT.getPredicate();
	
	this.REQUEST_QUEUE = new queuing.RequestQueue(10);
	// is activated in on load
	this.REQUEST_QUEUE.stop();
	
	this.lazyRequests = new services.LinkedMap();
	
	
	this.parseAction = function(action) {
		var result = {};
		for (var child = action.firstChild; child != null; child = child.nextSibling) {
			if (this.ID_ELEMENT.matches(child)) {
				result.id = BAL.DOM.getTextContent(child);
			}
			else if (this.STOP_ID_ELEMENT.matches(child)) {
				result.stopId = BAL.DOM.getTextContent(child);
			}
			else if (this.FRAGMENT_ELEMENT.matches(child)) {
				result.fragment = BAL.DOM.getTextContent(child);
			}
			else if (this.PROPERTY_NAME_ELEMENT.matches(child)) {
				result.property = BAL.DOM.getTextContent(child);
			}
			else if (this.OLD_VALUE_ELEMENT.matches(child)) {
				result.oldValue = child;
			}
			else if (this.NEW_VALUE_ELEMENT.matches(child)) {
				result.newValue = child;
			}
			else if (this.FUNCTION_ELEMENT.matches(child)) {
				result.functionDef = child;
			}
			else if (this.ARGUMENTS_ELEMENT.matches(child)) {
				result.argumentsDef = child;
			}
		}
		return result;
	}
	
	/**
	 * Extract the identifier of the target element from an action element of
	 * the response message.
	 */
	this.getID = function(action) {
		try {
			return BAL.DOM.getTextContent(BAL.DOM.elementsByQName(action, this.ID_ELEMENT).next());
		} catch (ex) {
			throw new Error("No element '" + this.ID_ELEMENT + "' in action '" + this.getActionType(action) + "'.");
		}
	};

	this.getPropertyName = function(action) {
		try {
			return BAL.DOM.getTextContent(BAL.DOM.elementsByQName(action, this.PROPERTY_NAME_ELEMENT).next());
		} catch (ex) {
			throw new Error("No element '" + this.PROPERTY_NAME_ELEMENT + "' in action '" + this.getActionType(action) + "'.");
		}
	};

	this.getOldValue = function(action) {
		try {
			return BAL.DOM.elementsByQName(action, this.OLD_VALUE_ELEMENT).next();
		} catch (ex) {
			throw new Error("No element '" + this.OLD_VALUE_ELEMENT + "' in action '" + this.getActionType(action) + "'.");
		}
	};

	this.getNewValue = function(action) {
		try {
			return BAL.DOM.elementsByQName(action, this.NEW_VALUE_ELEMENT).next();
		} catch (ex) {
			throw new Error("No element '" + this.NEW_VALUE_ELEMENT + "' in action '" + this.getActionType(action) + "'.");
		}
	};
	
	/**
	 * Extract the identifier of the node, at which the range of a
	 * RangeReplacement should stop.
	 */
	this.getStopID = function(action) {
		try {
			return BAL.DOM.getTextContent(BAL.DOM.elementsByQName(action, this.STOP_ID_ELEMENT).next());
		} catch (ex) {
			throw new Error("No element '" + this.STOP_ID_ELEMENT + "' in action '" + this.getActionType(action) + "'.");
		}
	};

	this.getFunction = function(action) {
		try {
			return BAL.DOM.elementsByQName(action, this.FUNCTION_ELEMENT).next();
		} catch (ex) {
			throw new Error("No element '" + this.FUNCTION_ELEMENT + "' in action '" + this.getActionType(action) + "'.");
		}
	};

	this.getArguments = function(action) {
		try {
			return BAL.DOM.elementsByQName(action, this.ARGUMENTS_ELEMENT).next();
		} catch (ex) {
			throw new Error("No element '" + this.ARGUMENTS_ELEMENT + "' in action '" + this.getActionType(action) + "'.");
		}
	};

	/**
	 * Create a range pointing to the contents that should be displayed inside
	 * the target element.
	 */
	this.getFragment = function(action) {
		try {
			var result = BAL.DOM.createRange(action.ownerDocument);
			result.selectNodeContents(BAL.DOM.elementsByQName(action, this.FRAGMENT_ELEMENT).next());
			return result;
		} catch (ex) {
			throw new Error("No element '" + this.FRAGMENT_ELEMENT + "' in action '" + this.getActionType(action) + "'.");
		}
	};
	
	this.showWaitPane = function() {
		this.topWindow.services.ajax._showWaitPane();
		this.statusBefore = window.status;
		window.status = services.i18n.PLEASE_WAIT_PAGE_BEING_LOADED;
	};
	
	this.hideWaitPane = function() {
		this.topWindow.services.ajax._hideWaitPane();
		window.status = this.statusBefore;
	};
	
	this.showNetworkError = function() {
		this.topWindow.services.ajax._showNetworkError();
	};
	
	this._showNetworkError = function() {
		var waitPane = document.getElementById("waitPane");
		var dialogPane = document.createElement("div");
		waitPane.parentNode.appendChild(dialogPane);
		dialogPane.outerHTML = 
			'<div id="sxNetwork" class="dlgBackground">' + 
			  '<div class="sxError">' + 
			    '<div class="sxMessage">' + 
			      services.i18n.NETWORK_ERROR_TRYING_RECONNECT + 
			    '</div>' + 
			    '<div class="sxFailure">' + 
			    services.i18n.NETWORK_ERROR_RECONNECT_FAILED + 
			    '</div>' + 
			    '<div class="sxButtons">' + 
			      '<span class="sxButton">' +
			      services.i18n.NETWORK_ERROR_RETRY + 
			      '</span>' + 
			    '</div>' + 
			  '</div>' + 
			'</div>';
	};
	
	this.showSessionTimeout = function(logoutURL) {
		this.topWindow.services.ajax._showSessionTimeout(logoutURL);
	};
	
	this._showSessionTimeout = function(logoutURL) {
		var waitPane = document.getElementById("waitPane");
		var dialogPane = document.createElement("div");
		waitPane.parentNode.appendChild(dialogPane);
		dialogPane.outerHTML = 
			'<div id="sxNetwork" class="dlgBackground sxRetry">' + 
				'<div class="sxError">' + 
					'<div class="sxFailure">' + 
						services.i18n.SETTION_TIMEOUT_FAILURE + 
					'</div>' + 
					'<div class="sxButtons">' + 
						'<span class="sxButton">' +
							services.i18n.SETTION_TIMEOUT_LOGIN + 
						'</span>' + 
					'</div>' + 
				'</div>' + 
			'</div>';
		
		var dialog = document.getElementById("sxNetwork");
		BAL.addEventListener(dialog, "click", function() {
			window.location = logoutURL;
		});
	};
	
	this.setNetworkErrorRetry = function(onClick) {
		this.topWindow.services.ajax._setNetworkErrorRetry(onClick);
	};
	
	this._setNetworkErrorRetry = function(onClick) {
		var dialogPane = document.getElementById("sxNetwork");
		BAL.DOM.addClass(dialogPane, "sxRetry");
		BAL.addEventListener(dialogPane, "click", onClick);
	};
	
	this.hideNetworkError = function() {
		this.topWindow.services.ajax._hideNetworkError();
	};
	
	this._hideNetworkError = function() {
		var dialogPane = document.getElementById("sxNetwork");
		if (dialogPane != null) {
			dialogPane.parentNode.removeChild(dialogPane);
		}
	};
	
	this._showWaitPane = function() {
		var waitPane = document.getElementById("waitPane");
		BAL.DOM.addClass(waitPane, "waiting");
		waitPane.style.cursor = "wait";
	};

	this._hideWaitPane = function() {
		var waitPane = document.getElementById("waitPane");
		BAL.DOM.removeClass(waitPane, "waiting");
		waitPane.style.cursor = "default";
	};

	this.onError = function(message) {
		alert(message);
		services.ajax.mainLayout.location.reload();
		return;
	};

	this.onCompletion = function(result) {
		var self = this;
		this.executeOnCompletion(function() {self.processResult(result)});
	};
	
	this.executeOnCompletion = function(fun) {
		var iFrames = document.getElementsByTagName("IFRAME");
        if (iFrames == null) {
            throw new Error("getElementsByTagName() for \"IFRAME\" returned null");
        }
        var loading = false;
        for (var i = 0; i < iFrames.length; i++) {
        	if (this.isLoading(document, iFrames[i])) {
        		loading = true;
        		break;
        	}
        }
		if (!loading) {
			fun();
		} else {
			var outer = this;
			window.setTimeout(function(){
				outer.executeOnCompletion(fun);
			},	100);
		}
	};
	
	/**
	 * Associates the given AJAX response to its pending request and calls
	 * processLater().
	 * 
	 * This method is called, whenever a response to an AJAX request is
	 * received.
	 */		
	this.processResult = function(result) {
		var actions;
		try {
			actions = BAL.DOM.elementsByQName(result, this.ACTIONS_ELEMENT).next();
		} catch (ex) {
			throw new Error("No element '" + this.ACTIONS_ELEMENT + "' completion action.");
		}
	
		var rxSequenceNrString = BAL.DOM.getAttribute(actions, 'rx');
		if (rxSequenceNrString != null) {
			var rxSequenceNr = Number(rxSequenceNrString);

			var ajax = this.mainLayout.services.ajax;
			if ((ajax.pendingRequests.length == 0) || (rxSequenceNr < ajax.pendingRequests[0].txSequence)) {
				// This is a late response.
				
				// Silently ignore. It has been reported that this happens in unstable networks. 
				// Since the client already received this request, there is no reason to confuse 
				// the user with an error message.
				return;
			}
			
			// Update the time of the request to the time of the arrival of its
			// answer (now). This ensures that requests delayed by the server do
			// not immediately cancel subsequent requests.
			//
			// Search the corresponding request to the current reply.
			var currentRequest = null;
			var currentRequestIndex = 0;
			for (; currentRequestIndex < ajax.pendingRequests.length; currentRequestIndex++) {
				var request = ajax.pendingRequests[currentRequestIndex];
				if (rxSequenceNr == request.txSequence) {
					currentRequest = request;
					break;
				}
			}
			
			if (currentRequest == null) {
				alert("Reply for unknown request, dropped.");
				return;
			}
			
			// Remember request as successfully received.
			ajax.acknowledgedRequests.push(rxSequenceNr);
			
			// Annotate all requests up to the current request with the current time as starting
			// point of the reorder timeout. 
			//
			// The reorder timeout for a requests starts, when a response for an request with a
			// higher sequence number arrives. At that time, it is guaranteed that the server has
			// processed those requests. The only reason why the answers has not yet been receives
			// is network latency.
			var now = new Date().getTime();
			for (var n = 0; n <= currentRequestIndex; n++) {
				var request = ajax.pendingRequests[n];
				if (request.time == undefined) {
					request.time = now;
				}
			}
			
			// Enqueue the reply.
			var outer = this;
			ajax.pendingReplies[rxSequenceNr] = function() {
				outer.processActions(actions);
			};
			
			// Process pending replies.
			this.processLater();
		} else {
			this.processActions(actions);
		}
	};
	
	/**
	 * Create a new timer after calling processPendingReplies().
	 */
	this.processLater = function() {
		this.mainLayout.services.ajax.timer = null;
		
		var now = (new Date()).getTime();
		this.processPendingReplies(now);
		
		// Find the next sequence number, for which a reply is already enqueued.
		// Only, if
		// such already answered request is in the queue, a new timer must be
		// started.
		var firstPendingReplyIndex = -1;
		for (var n = 0; n < this.mainLayout.services.ajax.pendingRequests.length; n++) {
			var sequence = this.mainLayout.services.ajax.pendingRequests[n].txSequence;
			var reply = this.mainLayout.services.ajax.pendingReplies[sequence];
			if (reply != null) {
				firstPendingReplyIndex = n;
				break;
			}
		}
		
		if (firstPendingReplyIndex >= 0) {
			var waitTime = this.mainLayout.services.ajax.pendingRequests[firstPendingReplyIndex].time + this.TIMEOUT - now;
			this.mainLayout.services.ajax.timer = setTimeout(this.processLaterClosure, waitTime);
		}
	};

	/**
	 * Processes all pending replies. Drops all requests that have already timed
	 * out.
	 * 
	 * Cancel no replies, for which no concurrent replies have been received so
	 * far. This approach drops no events, if the user does never create more
	 * than one request at a time (no matter how long the server response time
	 * is).
	 */
	this.processPendingReplies = function(now) {
		// Process all pending replies in the order defined by the pending
		// requests.
		// Stop, if a request with a sequence number is found in the queue that
		// has
		// no reply yet, and the corresponding request has not yet timed out.
		var lastProcessedRequestIndex = -1;
		for (var n = 0; n < this.mainLayout.services.ajax.pendingRequests.length; n++) {
			var request = this.mainLayout.services.ajax.pendingRequests[n];
			var sequence = request.txSequence;
			var reply = this.mainLayout.services.ajax.pendingReplies[sequence];
			if ((reply == null) && ((now - this.mainLayout.services.ajax.pendingRequests[n].time) < this.TIMEOUT)) {
				// No reply, but not yet timed out. Cancel processing.
				break;
			}
		
			if (reply != null) {
				reply.call();
				
				// Process functions attached to this request. See runLater().
				if (request.actions != null) {
					for (var m = 0; m < request.actions.length; m++) {
						try {
							request.actions[m].call();
						}
						catch(ex) {
							// action is not up to date
						}
					}
					request.actions = null;
				}
				
				lastProcessedRequestIndex = n;
				delete this.mainLayout.services.ajax.pendingReplies[sequence];
			}
		}

		// Remove all processed or timed out requests up to and including
		// the the last processed request.
		if (lastProcessedRequestIndex >= 0) {
			this.mainLayout.services.ajax.pendingRequests.splice(0, lastProcessedRequestIndex + 1);
		}
	};

	this.getRequestSourceDocument = function(actions) {
		var requestSourceWindow;
		try {
			var requestSourceExpression = BAL.DOM.getAttribute(actions, 'requestsourcereference');
			if(requestSourceExpression == null || requestSourceExpression == "") {
				requestSourceWindow = null;
			} else {
				requestSourceWindow = eval(requestSourceExpression);
			}
		} catch(ex) {
			requestSourceWindow == null;
		}
		
		return requestSourceWindow != null ? requestSourceWindow.document : null;
	};

	/**
	 * Execute all received client actions.
	 */	
	this.processActions = function(actions) {
		var requestSourceDocumentBeforeUpdates = this.getRequestSourceDocument(actions);
		var topLevelDocument;
		if(requestSourceDocumentBeforeUpdates != null) {
			topLevelDocument = BAL.getTopLevelDocumentOf(requestSourceDocumentBeforeUpdates);
			BAL.getCurrentWindow(requestSourceDocumentBeforeUpdates).services.focusRestore.saveFocusState();
		}
        
        // windowReference -> array of objects for layout
        var layoutNodeByWindow = new Object();
        // windowReference -> array of scripts to execute within that window
        var scriptsByWindow = new Object();
		var errors = null;
		for (var it = BAL.DOM.elementsByQName(actions, this.ACTION_ELEMENT); it.hasNext(); ) {
			var action = it.next();

			var contextExpression = BAL.DOM.getAttribute(action, 'context');
			if (contextExpression == null) {
				contextExpression = "window";
			}

			try {
				// Find the context in which to execute the action.
				var context = eval(contextExpression);
									
				if (context == null) {
					services.log.error(
						"Undeliverable AJAX event.", 
						"The component reference is undefined: '" + contextExpression + "'. Action: " + this.getActionType(action));
					continue;
				}
			
				if (! ("services" in context)) {
					// The event is directed to a page that does not
					// support dispatching AJAX events.
					services.log.error(
						"Undeliverable AJAX event.", 
						"The component referenced in '" + contextExpression + "' does not support dispatching AJAX events.");
					continue;
				}
			
				// Redirect this action to the context for which it was
				// generated.
				context.services.ajax.processAction(action, contextExpression, layoutNodeByWindow, scriptsByWindow);
			} catch (ex) {
				errors = this.addError(errors, ex);
			}
		}
		
		errors = this._doLayout(layoutNodeByWindow, errors);
		
		for (var windowReference in scriptsByWindow) {
			var scripts = scriptsByWindow[windowReference];
			var contextWindow = eval(windowReference);
			for (var i = 0 ; i < scripts.length; i++) {
				var script = scripts[i];
				try {
					contextWindow.eval(script);
				} catch (ex) {
					errors = this.addError(errors, "Script execution of '" + script + "' failed: " + ex);
				}
			}
		}
		
		try {
			var requestSourceDocumentAfterUpdates = this.getRequestSourceDocument(actions);
			if(requestSourceDocumentAfterUpdates != null) {
				BAL.getCurrentWindow(requestSourceDocumentAfterUpdates).services.focusRestore.restoreFocusState();
			} else if(topLevelDocument != null){
				BAL.getCurrentWindow(topLevelDocument).services.focusRestore.restoreFocusState();
			}
		} catch(ex) {
			// Do nothing.
			// Tried to restore focus within an iframe,
			// whose content has not been retrieved until yet.
		}
		
		services.ajax.mainLayout.services.ajax.afterRendering(errors);
		
		if (errors != null) {
			services.log.error("Problems during AJAX event execution.", errors);
			services.ajax.mainLayout.services.ajax.cleanupQueuedFunctions();
		}
	};
	
	/**
	 * Layouts the formerly cached nodes.
	 * 
	 * @param {Object}
	 *            Mapping of window references to arrays containing the nodes in
	 *            that window which must be layouted.
	 * @param {String}
	 *            errors the errors to append potential errors to
	 * 
	 * @return {String} the new error messages
	 */
	this._doLayout = function(layoutNodeByWindow, errors) {
		for (var windowReference in layoutNodeByWindow) {
			var nodeSet = layoutNodeByWindow[windowReference];
			var contextWindow = eval(windowReference);
			var nodesArray;
			if (nodeSet.size() == 1) {
				nodesArray = nodeSet.toArray();
				errors = contextWindow.services.ajax._reinitLayout(nodesArray[0], errors);
			} else {
				var nodesToLayout = this.getNodesWithoutMarkedParentToLayout(nodeSet);
				nodesArray = nodesToLayout.toArray();
				for (var i = 0 ; i < nodesArray.length; i++) {
					var nodeForLayout = nodesArray[i];
					errors = contextWindow.services.ajax._reinitLayout(nodeForLayout, errors);
				}
			}
		}
		return errors;
	};
	
	this.getNodesWithoutMarkedParentToLayout = function(nodeSet) {
		var nodesToLayout = new services.NodeSet();
		var nodeArray = nodeSet.toArray();
		for(var i = 0; i < nodeArray.length; i++) {
			var node = nodeArray[i];
			if(! (this.isChildFromNodeInSet(node, nodeSet) ||
				  this.isChildFromNodeInSet(node, nodesToLayout))) {
				nodesToLayout.add(node);
			}
		}
		
		return nodesToLayout;
	};
	
	this.isChildFromNodeInSet = function(node, nodeSet) {
		if(! nodeSet.isEmpty()) {
			var checkNode = node.parentNode;
			if(checkNode != null) {
				var rootNode = BAL.getCurrentDocument(node);
				while(checkNode != rootNode) {
					if(nodeSet.contains(checkNode)) {
						return true;
					}
					checkNode = checkNode.parentNode;
				}
			}
		}
		
		return false;
	};

	/**
	 * Adds the message of the given error to the previous errors
	 * 
	 * @param {String}
	 *            the errors to append exception to
	 * @param {Error}
	 *            the new error
	 * 
	 * @return {String} the extended error massage
	 */
	this.addError = function(errors, ex) {
		if (errors == null) {
			errors = "";
		} else {
			errors += ", ";
		}
		errors += "exception: ";
		errors += ex.message ? ex.message : ex.message;
		return errors;
	};
	
	this.getActionType = function(action) {
		return BAL.DOM.getAttribute(action, 'xsi:type');
	};
	
	/**
	 * @param {String}
	 *            windowReference a reference from the main layout to the
	 *            current window;
	 * @param {Object}
	 *            layoutNodeByWindow mapping from window references to array of
	 *            nodes in that window to be layed out later. If the given
	 *            action forces a re-layout of some node, that node can be added
	 *            to the corresponding array.
	 * @param {Object}
	 *            scriptsByWindow mapping from window references to array of
	 *            scripts to be evaluated later. If the given action forces
	 *            executing some scripts, they can be added to the corresponding
	 *            array.
	 */
	this.processAction = function(action, windowReference, layoutNodeByWindow, scriptsByWindow) {
		
		var type = this.getActionType(action);
		if (type == 'JSSnipplet') {
			var codeElement;
			try {
				codeElement = BAL.DOM.elementsByQName(action, this.CODE_ELEMENT).next();
			} catch (ex) {
				throw new Error("No element '" + this.CODE_ELEMENT + "' action '" + type + "'.");
			}
			var scripts = scriptsByWindow[windowReference];
			if (scripts == null) {
				scripts = new Array();
				scriptsByWindow[windowReference] = scripts;
			}
			scripts.push(BAL.DOM.getTextContent(codeElement));
		}
		else if (type == 'ContentReplacement') {
            var actionObject = this.parseAction(action);
			var id = actionObject.id;
			
			// Lookup the target element in the current document.
			var targetNode = window.document.getElementById(id);
			if (targetNode == null) {
				throw new Error("There is no element with id '" + id + "'");
			}
			
			for (var child = targetNode.firstChild; child != null; child = child.nextSibling) {
	            BAL.DOM.triggerEachNode(child, "DOMNodeRemovedFromDocument");
			}
			var fragment = actionObject.fragment;
			BAL.setInnerHTML(targetNode, fragment);
			this._addScripts(windowReference, scriptsByWindow, targetNode);
			this._markNodeForLayout(windowReference, layoutNodeByWindow, targetNode);
		}
		else if (type == 'ElementReplacement') {
            var actionObject = this.parseAction(action);
			var id = actionObject.id;
			var targetNode = window.document.getElementById(id);
			if (targetNode == null) {
				throw new Error("There is no element with id '" + id + "'");
			}
			
            BAL.DOM.triggerEachNode(targetNode, "DOMNodeRemovedFromDocument");
			var targetNodeParent = targetNode.parentNode;
			var fragment = actionObject.fragment;
			BAL.setOuterHTML(targetNode, fragment);
			
			this._addScripts(windowReference, scriptsByWindow, targetNodeParent);
			this._markNodeForLayout(windowReference, layoutNodeByWindow, targetNodeParent);
		}
		else if (type == 'PropertyUpdate') {
			var id = this.getID(action);
			var target = window.document.getElementById(id);
			if (target == null) {
				throw new Error("There is no element with id '" + id + "'");
			}
			for (var it = BAL.DOM.elementsByQName(action, this.PROPERTY_ELEMENT); it.hasNext(); ) {
				var property = it.next();
				
				var name = property.getAttribute("name");
				var value = property.getAttribute("value");
				
				// Maximal simple parsing of values, only String and Boolean
				// types is supported. Of cause, this might prevent setting a
				// "title" property to "false" (as string). But is is required
				// to set e.g. the "disabled" property at all. The heavy-weight
				// (and correct solution would be to switch to XMLValueDecoder
				// and encode things accordingly on the server side). Another
				// valid solution would be to parse values dependent on the
				// "type" of the property to set.
				var jsValue;
				if (value == "true") {
					jsValue = true;
				} else if (value == "false") {
					jsValue = false;
				} else {
					jsValue = value;
				}
				
				// alert(
				// "Updating property '" + name + "'" +
				// " of element '" + target.tagName + "'." + target.id + "" +
				// " to '" + value + "'.");
				if (property.getAttribute("namespace") == null && name.indexOf("data-") != 0) {
					target[name] = jsValue;
					if (name == "value") {
						// Also update the default value property to be able to decide, whether a value 
						// has actually changed. This is necessary at least when changing the active grid
						// row with RETURN in Opera and the the server sends a value normalization 
						// (e.g. "9" -> "9,000") upon the value change event. Without updating the default
						// value, the onchange handlers of input controls are not able to detect that no
						// change is made. Since `onchange` is triggered twice during keycode handling, 
						// The second invocation of `onchange` must be prevented, even if an intermediate
						// update is performed by the server.
						target["defaultValue"] = jsValue;
					}
				} else {
					BAL.DOM.setNonStandardAttribute(target, name, jsValue);
				}
			}
		}
		else if (type == 'CssClassUpdate') {
			var id = this.getID(action);
			var target = window.document.getElementById(id);
			if (target == null) {
				throw new Error("There is no element with id '" + id + "'");
			}
			for (var it = BAL.DOM.elementsByQName(action, this.PROPERTY_ELEMENT); it.hasNext(); ) {
				var property = it.next();
				var classAttributeName = "class";
				var classAttributeValue = BAL.DOM.getNonStandardAttribute(property, classAttributeName);
				if(classAttributeValue != null) {
					BAL.DOM.setNonStandardAttribute(target, classAttributeName, classAttributeValue);
				} else {
					if(BAL.DOM.getNonStandardAttribute(target, classAttributeName) != null) {
						BAL.DOM.removeNonStandardAttribute(target, classAttributeName);
					}
				}
			}
		}
		else if (type == 'RangeReplacement') {
            var actionObject = this.parseAction(action);
			var startId = actionObject.id;
			var stopId = actionObject.stopId;
			
			var startNode = window.document.getElementById(startId);
			if (startNode == null) {
				throw new Error("There is no element with startId '" + startId + "'");
			}
			var stopNode = window.document.getElementById(stopId);
			if (stopNode == null) {
				throw new Error("There is no element with stopId '" + stopId + "'");
			}
			if (startNode.parentNode != stopNode.parentNode) {
				throw new Error("Start and stop node are not sibblings (" + startId + ", " + stopId + ").'");
			}
			
			var parent = startNode.parentNode;
			var fragment = actionObject.fragment;
			BAL.insertAdjacentHTML(startNode, "beforebegin", fragment);
			var node = startNode; 
			while (true) {
				var nextNode = node.nextSibling;
				var lastNode = node == stopNode;

				BAL.DOM.triggerEachNode(node, "DOMNodeRemovedFromDocument");

				parent.removeChild(node);
				if (lastNode) {
					break;
				}
				if (nextNode == null) {
					throw new Error("Stop node not a following-sibling of start node (" + startId + ", " + stopId + ").'");
				}
				node = nextNode;
			}
			
			this._addScripts(windowReference, scriptsByWindow, parent);
			this._markNodeForLayout(windowReference, layoutNodeByWindow, parent);
		}
		else if (type == 'FragmentInsertion') {
            var actionObject = this.parseAction(action);
			var id = actionObject.id;
			var position = BAL.DOM.getAttribute(action, 'position');

			var targetNode = window.document.getElementById(id);
			if (targetNode == null) {
				throw new Error("There is no element with id '" + id + "'");
			}

			var fragment = actionObject.fragment;
			BAL.insertAdjacentHTML(targetNode, position, fragment);
			
			var layoutParent;
			if (position == 'beforebegin') {
				layoutParent = targetNode.parentNode;
			} else if (position == 'afterend') {
				layoutParent = targetNode.parentNode;
			} else if (position == 'afterbegin') {
				layoutParent = targetNode;
			} else if (position == 'beforeend') {
				layoutParent = targetNode;
			} else {
				throw new Error("Unknown position specifier: " + position);
			}
			
			this._addScripts(windowReference, scriptsByWindow, layoutParent);
			this._markNodeForLayout(windowReference, layoutNodeByWindow, layoutParent);
		}
		else if (type == 'AJAXPropertyEvent') {
			var id = this.getID(action);
			var decoder = new XMLValueDecoder();

			var event = {
				source: id,
				propertyName: this.getPropertyName(action),
				oldValue: decoder.decode(BAL.DOM.getFirstElementChild(this.getOldValue(action))),
				newValue: decoder.decode(BAL.DOM.getFirstElementChild(this.getNewValue(action)))
			};

			services.form.propertEventQueue.handlePropertyEvent(event);
		}
		else if (type == 'FunctionCall') {
			var decoder = new XMLValueDecoder();
			var target = this.getID(action);
			var fun = this.getFunction(action);
			var ref = fun.getAttribute('ref');
			var name = fun.getAttribute('name');
			var arguments = decoder.decode(BAL.DOM.getFirstElementChild(this.getArguments(action)));

			var targetNode = document.getElementById(target);
			if (targetNode == null) {
				throw new Error("Referenced element '" + target + "' does not exist. action='" + type + "', ref='" + ref + "'");
			}
			
			var obj = eval(ref);
			var functionReference = obj[name];
			if (typeof(functionReference) != 'function') {
				throw new Error("Not a function. reference='" + ref + "', name='" + name + "'");
			}
			arguments.unshift(targetNode);
			functionReference.apply(obj, arguments);
		}
		else {
			alert("Received unknown action: type=" + type + ", contents=" + action);
			throw new Error('Unknown action: ' + type);
		}
	};
	
	/**
	 * Marks the given node to be layouted later.
	 * 
	 * @param {String}
	 *            windowReference a reference of the window inwhich the given
	 *            node lives
	 * @param {Object}
	 *            layoutNodeByWindow the storage object given to
	 *            processActions()
	 * @param {Node}
	 *            the nod to mark to be layouted later.
	 */
	this._markNodeForLayout = function(windowReference, layoutNodeByWindow, node) {
		// Node is not layed out so no need for reinit Layout
		if (node.layout == null) {
			return;
		}
		var layoutNodes = layoutNodeByWindow[windowReference];
		if (layoutNodes == null) {
			layoutNodes = new services.NodeSet();
			layoutNodeByWindow[windowReference] = layoutNodes;
		}
		layoutNodes.add(node);
	};
	
	/**
	 * Reinitializes the layout of the given element.
	 * <p>
	 * If the given element was laid out using layout.js (i.e. the element has a
	 * js-object 'layout' the complete element layout will be rebuild (it may
	 * happen that some new content did not already laid out) and laid out.
	 * </p>
	 */
	this._reinitLayout = function(element, errors) {
		try {
			services.layout.renderLayout(element);
		} catch (ex) {
			errors = this.addError(errors, ex);
		}
		return errors;

	};
	
	/**
	 * Selects inline scripts in the given action and adds them to the given
	 * scripts.
	 */
	this._addScripts = function(windowReference, scriptsByWindow, fragmentRoot) {
        var scriptTags = fragmentRoot.getElementsByTagName("script");
        for (var n = 0; n < scriptTags.length; n++) { 
	        var scripts = scriptsByWindow[windowReference];
	        if (scripts == null) {
	        	scripts = new Array();
	        	scriptsByWindow[windowReference] = scripts;
	        }
	        
	        var scriptTag = scriptTags[n];
            scripts.push(BAL.DOM.getTextContent(scriptTag));
        }
        
        while (scriptTags.length > 0) {
        	var scriptTag = scriptTags[scriptTags.length - 1];
        	
            // Note: Removing the script tag modifies the life list "scriptTags".
            scriptTag.parentNode.removeChild(scriptTag);
        }
	};
	
	this.isMaster = function() {
		return this.mainLayout == window;
	};
	
	/**
	 * Creates a new (session-unique) sequence number for a request.
	 * 
	 * This function is also called from command handlers immediately before
	 * loading their invocation URL.
	 */
	this.createSequenceNumber = function() {
		return this.mainLayout.services.ajax.txSequence++;
	};
	
	this.execute = function(command, args, useWaitPane, serverResponseCallback, onError, contextInformation, sequential) {
		var requestFunction = this._getRequestFunction(command, args, useWaitPane, onError, contextInformation, sequential);
		this.mainLayout.services.ajax.REQUEST_QUEUE.executeRequest(requestFunction, serverResponseCallback);
		return false;
	};
	
	this._getRequestFunction = function(command, args, useWaitPane, onError, contextInformation, sequential) {
		var self = this;
		var requestFunction = function() {
			var componentId = self.COMPONENT_ID;
			var submitNumber = services.ajax.SUBMIT_NUMBER;
			if (contextInformation != undefined) {
				componentId = contextInformation.componentId;
				submitNumber = contextInformation.submitNumber;
			}
			
			var sequence = self.createSequenceNumber();
			
			// Invoke the command. Source and target components are identical.
			// Could be
			// extended to invoke commands on arbitrary components.
			return self.mainLayout.services.ajax.invoke(self, componentId, componentId, sequence, submitNumber, command, args, useWaitPane, onError, /* ignoreResponse */ false, sequential);
		};
		return requestFunction;
	};
	
	this.executeOrUpdateLazy = function(requestID, command, args, contextInformation) {
		/*
		 * read the submit number at the moment at which the function is called.
		 * The variant to read the submit number directly before sending the
		 * event is not used as then a problem can occur, e.g. when the
		 * corresponding component is repainted the data (for example control
		 * IDs) may be out dated.
		 */ 
		var componentId = this.COMPONENT_ID;
		var submitNumber = this.SUBMIT_NUMBER;
		if (contextInformation != undefined) {
			componentId = contextInformation.componentId;
			submitNumber = contextInformation.submitNumber;
		}
		
		var commandArguments = this.addSystemCommandProperty(args);
		var requ = {
			command : command,
			args : commandArguments,
			componentId : componentId,
			submitNumber : submitNumber
		};
		this.mainLayout.services.ajax.lazyRequests.put(requestID, requ);
		return false;
	};
	
	this.addSystemCommandProperty = function(originalCommandArguments) {
		var modifiedCommandArguments = originalCommandArguments;
		modifiedCommandArguments[services.ajax.systemCommandId] = true;
		return modifiedCommandArguments;
	};
	
	this.createLazyRequestID = function() {
		return this.mainLayout.services.ajax.lazyRequests.newKey();
	};
	
	this.invokeRead = function(command, args, onError, sequential) {
		var executeSequentially = sequential != null ? sequential : false;
		return this.mainLayout.services.ajax.invoke(this, this.COMPONENT_ID, this.COMPONENT_ID, -1, services.ajax.SUBMIT_NUMBER, command, args, /* useWaitPane */ false, onError, /* ignoreResponse */ false, executeSequentially);
	};

	this.invokeStatic = function(command, args, onError, sequential) {
		var executeSequentially = sequential != null ? sequential : false;
		return this.mainLayout.services.ajax.invoke(this, this.COMPONENT_ID, null, -1, services.ajax.SUBMIT_NUMBER, command, args, /* useWaitPane */ false, onError, /* ignoreResponse */ true, executeSequentially);
	};
	
	/**
	 * adds a new object to the list or pending requests, which represents the
	 * given sequence number.
	 * 
	 * <b> Must not be called if the cooresponding request does not need the
	 * answer of the server.</b>
	 */
	this.enqueueRequest = function(txSequence) {
		// Insert the new request in the queue of pending requests. In all
		// regular
		// cases (expect test cases that immitate delayed requests), the new
		// request
		// must be simply appended to the pending request queue to keep the
		// requests
		// in ascending order of their sequence numbers). Just to simplify test
		// cases,
		// functionality is added here that allows to insert a programmatically
		// delayed
		// request (where the sequence number is created some time before
		// actually
		// invoking this method).
	
		var insertIndex = this.mainLayout.services.ajax.pendingRequests.length;
		while ((insertIndex > 0) && (this.mainLayout.services.ajax.pendingRequests[insertIndex - 1].txSequence > txSequence)) {
			// This cannot happen during normal operation (see above).
			insertIndex--;
		}
		
		var currentRequest = {
			txSequence: txSequence, 
			time: undefined
		};
		
		// Insert into the request queue at index insertIndex.
		this.mainLayout.services.ajax.pendingRequests.splice(insertIndex, 0, currentRequest);
		
		return currentRequest;
	};
	
	/**
	 * Attaches the given function to the last AJAX request in the queue. The
	 * given function will be called after the response for the last AJAX
	 * request is received. If there is no pending request, the function is
	 * executed immediately.
	 * 
	 * Returns true, if the function was enqueued, false if the function has
	 * been executed immediately.
	 */
	this.runLater = function(fun) {
		var pendingRequestCount = this.mainLayout.services.ajax.pendingRequests.length;
		if (pendingRequestCount > 0) {
			var lastRequest = this.mainLayout.services.ajax.pendingRequests[pendingRequestCount - 1];
			if (lastRequest.actions == null) {
				lastRequest.actions = new Array();
			}

			// Attach the function to be run later to the last pending request.
			lastRequest.actions[lastRequest.actions.length] = fun;
			return true;
		} else {
			// There is no pending request. Immediately call the given function.
			fun.call();
			return false;
		}
	};
	
	this._getCommandString = function(source, target, submitNumber, command, args) {
		var encoder = new XMLValueEncoder();
		var commandString = '<execute xmlns="http://top-logic.com/base/services/ajax">' +
					'<component xmlns="http://top-logic.com/base/services/cs" ' + 
						'env:role="http://top-logic.com/component"' + 
						' source="' + source + '"' + 
							((target != null) ?	(' target="' + target + '"') : '') +
					'/>' + 
					'<submit xmlns="http://top-logic.com/base/services/cs" ' + 
						'env:role="http://top-logic.com/submit"' + 
						' value="' + submitNumber + '"'+ 
					'/>' +
				   	'<command>' + command + '</command>' + 
				   	'<arguments>' + 
				    	(function() {
			    			var result = '';
			    			for (var arg in args) {
			    				var value = args[arg];
			    				if (value === undefined) continue;
			    				
			    				var encodedValue = encoder.encode(value);
			    				
			    				// alert(encodedValue);
			    				
			    				result += 
			    					'<argument>' + 
			    						'<name>' + arg + '</name>' + 
			    						'<value>' + 
			    							encodedValue + 
			    						'</value>' + 
					    			'</argument>';
					    	}
					    	return result;
					    })() +
			    		
			    	'</arguments>' +
		    	'</execute>\n'
		;

		// alert("Command:\n" + commandString);
		return commandString;
	};

	this.encodeHTML = function (s) {
	    return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;').replace(/'/g, '&#39;');
	};
	
	this.invoke = function(source, sourceID, targetID, txSequence, submitNumber, command, args, useWaitPane, onError, ignoreResponse, sequential) {
		var request = this._createRequest(sourceID, targetID, txSequence, submitNumber, command, args);
		if (!ignoreResponse && txSequence >= 0) {
			this.enqueueRequest(txSequence);
		}
		
		if (txSequence >= 0) {
			var self = this;
			var customOnError = onError;
			if (customOnError == null) {
				customOnError = this.onError;
			}
			var maxRetry = 6;
			var retry = 1;
			var timeout = 500;
			var totalDelay = 0;
			var onCompletion = null;
			onError = function(message, code) {
				if (code >= 0 && (code < 200 || code >= 500)) {
					var useWaitPaneForRetry = true;
					
					if (retry > maxRetry) {
						if (onCompletion == null) {
							source.topWindow.services.ajax.showNetworkError();
							onCompletion = function() {
								source.topWindow.services.ajax.hideNetworkError();
							}
						}
						source.topWindow.services.ajax.setNetworkErrorRetry(function() {
							source.topWindow.services.ajax.hideNetworkError();
							maxRetry += 6;
							totalDelay = 0;
							timeout = 500;
							onCompletion = null;
							self._sendRequest(source, request, useWaitPaneForRetry, onCompletion, onError, ignoreResponse, sequential, retryInfo);
						});
					} else {
						var retryInfo = retry;
						totalDelay += timeout;
						if (totalDelay > 2000 && onCompletion == null) {
							// Enhanced visual feedback.
							source.topWindow.services.ajax.showNetworkError();
							onCompletion = function() {
								source.topWindow.services.ajax.hideNetworkError();
							}
						}
						
						// Use wait pane to lock the UI during retry.
						window.setTimeout(function() {
							self._sendRequest(source, request, useWaitPaneForRetry, onCompletion, onError, ignoreResponse, sequential, retryInfo);
						}, timeout);
						var increasedTimeout = timeout * 2;
						if (increasedTimeout < 5000) {
							timeout = increasedTimeout;
						}
						retry++;
					}
				} else {
					if (customOnError != null) {
						customOnError(message, code);
					}
				}
			};
		}
		
		this._sendRequest(source, request, useWaitPane, null, onError, ignoreResponse, sequential);
	};
	
	/* Same as in com.top_logic.layout.internal.WindowHandler.writeEncodeFunction(TagWriter) */
	this.encodeWindowName = function(s) {
		var regexp = /[-%'\.]/g;
		return encodeURIComponent(s).replace(regexp, '_');
	};
	
	this._createRequest = function(source, target, txSequence, submitNumber, command, args) {
		var request = 
			'<?xml version="1.0" encoding="UTF-8"?>' + this.NEWLINE +
			'<env:Envelope xmlns:env="http://www.w3.org/2003/05/soap-envelope">' + 
				'<env:Header>';
					
		if (txSequence >= 0) {
			request +=
					'<sequence xmlns="http://top-logic.com/base/services/cs" ' + 
						'env:role="http://top-logic.com/sequence"' + 
						' tx="' + txSequence + '"'+ 
					'/>';
			
			var acknowledgedRequests = this.mainLayout.services.ajax.acknowledgedRequests;
			var cnt = acknowledgedRequests.length;
			for (var n = 0; n < cnt; n++) {
				var ack = acknowledgedRequests[n];
				request +=
					'<ack xmlns="http://top-logic.com/base/services/cs" ' + 
						'env:role="http://top-logic.com/ack"' + 
						' tx="' + ack + '"'+ 
					'/>';
			}
			acknowledgedRequests.splice(0, cnt);
		}
		request +=
					'<source xmlns="http://top-logic.com/base/services/cs" window="' + this.encodeHTML(this.encodeWindowName(window.name)) + '">' + source + '</source>' + 
				'</env:Header>' + 
				
				'<env:Body>';
		if (txSequence >= 0) {
			/*
			 * If the event is sent without sequence number, then a problem may
			 * occur when the lazy events need to be send in correct order. E.g.
			 * updatScrollposition is sent with log message. At the "same"
			 * moment the tab is changed which is executed earlier. The
			 * updateScrollposition fails.
			 */
			var requests = this.lazyRequests.values();
			if (requests != null) {
				for (var index = 0 ; index < requests.length; index++) {
					var lazyRequest = requests[index];
					var lazyComponentId = lazyRequest.componentId;
					var lazySubmitNumber = lazyRequest.submitNumber;
					var lazyCommand = lazyRequest.command;
					var lazyArgs = lazyRequest.args;
					request += this._getCommandString(lazyComponentId, lazyComponentId, lazySubmitNumber, lazyCommand, lazyArgs); 
				}
				this.lazyRequests.clear();
			}
		}
		request += this._getCommandString(source, target, submitNumber, command, args); 
		request += '</env:Body>' +
		    '</env:Envelope>';

		    // DEBUG: Display request message.
            //
            // alert("AJAX-REQUEST:\n" + request);
		return request;
	};
	
	this._sendRequest = function(source, request, useWaitPane, onCompletion, onError, ignoreResponse, sequential, retry) {
		try {
			if (onCompletion == null) {
				onCompletion = this.onCompletionClosure;
			} else {
				onCompletion = this.concatFunction(onCompletion, this.onCompletionClosure);
			}
			if (useWaitPane == null || useWaitPane == true){
				source.topWindow.services.ajax.showWaitPane();
				onCompletion = this.concatFunction(
					function() {
						source.topWindow.services.ajax.hideWaitPane();
					}, 
					onCompletion
				);
			}
			this.sendRequest(request, onCompletion, onError, ignoreResponse, sequential, retry);
		} catch(ex) {
			alert(ex);
		}
	};
	
	this.concatFunction = function(f1, f2) {
		return function() {
			f1.apply(self, arguments);
			f2.apply(self, arguments);
		};
	};
	
	/**
	 * This method is called by a script in the top window if a new IFrame is
	 * written. It shows a DIV during loading time using a 1000 ms timeout. When
	 * the loading process has finished, the DIV will be removed using
	 * #hideLoading(windowElement).
	 * 
	 * @param frameID
	 *            the ID of the frame currently loading. The shown div must have
	 *            id frameID + "loading".
	 * @see IFrameLayout
	 * @see hideLoading(windowElement)
	 */
	this.showLoading = function(frameID) {
		try {
			var frame = document.getElementById(frameID);
			if (frame== null) {
				throw new Error("No element with id '" + frameID + "'");
			}
			var outer = this;
			var showArea = function() {
				frame.loadingTimeout = null;
				
				var loadingArea = outer.getLoadingArea(frame);
				if (loadingArea != null) {
					// Actually show the loading div by "removing" the none display style.
					loadingArea.style.display = "";
				}
			};
			frame.loadingTimeout = window.setTimeout(showArea, 1000);
		} catch (e) {
			throw new Error("Unable to show loading area for frame '" + frameID + "': error " + e.message);
		}
	};

	/**
	 * Check whether the given Iframe is currently loading, i.e. if this method
	 * returns true the content window must not be touched
	 */
	this.isLoading = function(parentDocument, iframe) {
		if (iframe.id == null) {
			return false;
		}
		if (iframe.loadingTimeout != null) {
			return true;
		}
		var loadingArea = this.getLoadingArea(iframe);
		if (loadingArea == null) {
			return false;
		}
		
		return true;
	};
	
	/**
	 * This method is called when an iframe has been loaded. The given
	 * <code>windowElement</code> is the window corresponding to the iframe.
	 * The method removes the corresponding waiting DIV (if exists).
	 * 
	 * @param windowElement
	 *            the window of the iframe whose waiting div shall be removed
	 * @see showLoading(frameID)
	 */
	this.hideLoading = function(windowElement) {
		try {
            var iFrameHolder = BAL.searchIFrame(document, windowElement);
            if (iFrameHolder.index == -1) {
            	return
            }
            var resultIFrame = iFrameHolder.iFrames[iFrameHolder.index];
			if (resultIFrame.loadingTimeout != null) {
				window.clearTimeout(resultIFrame.loadingTimeout);
				resultIFrame.loadingTimeout = null;
			}
			var loadingArea = this.getLoadingArea(resultIFrame);
			if (loadingArea != null) {
				loadingArea.parentNode.removeChild(loadingArea);
				BAL.DOM.removeNonStandardAttribute(resultIFrame, "data-loadingid")
			}
		} catch(e) {
			throw new Error("Unable to hide waiting for window '" + windowElement.name + "': error " + e.message);
		}
	};
	
	this.getLoadingArea = function(frame) {
		var loadingId = BAL.DOM.getNonStandardAttribute(frame, "data-loadingid");
		if (loadingId == null) {
			return null;
		}
		return document.getElementById(loadingId);
	};
	
	/**
	 * Redirects the main layout to the given page.
	 * 
	 * @param page
	 *            the page to redirect. page must be a path relative to the
	 *            context path of the application.
	 */
	this.logout = function(page) {
		services.ajax.mainLayout.document.body.onunload = function() {};
		services.ajax.mainLayout.document.location = WebService.prototype.CONTEXT_PATH + page;
	};	
	
	this.executeAfterRendering = function(winObj, funcDef) {
		if (services.ajax.mainLayout.queuedFunctions == null) {
			services.ajax.mainLayout.queuedFunctions = new Array();
		}
		services.ajax.mainLayout.queuedFunctions.push(winObj);
		services.ajax.mainLayout.queuedFunctions.push(funcDef);
	};
	
	this.afterRendering = function(errors) {
		if (services.ajax.mainLayout.queuedFunctions != null) {
			for (var i = 0; i < services.ajax.mainLayout.queuedFunctions.length; i = i + 2) {
				var windowRef = services.ajax.mainLayout.queuedFunctions[i];
				var script = services.ajax.mainLayout.queuedFunctions[i + 1];
				try {
					script.call(windowRef);
				} catch (ex) {
					errors = this.addError(errors, "Script execution of '" + script + "' failed: " + ex);
				}
			}
		}
		services.ajax.mainLayout.services.ajax.cleanupQueuedFunctions();
	};
	
	this.cleanupQueuedFunctions = function() {
		queuedFunctions = null;
	};
	
	
	this._nextID = new Date().getTime();
	/*
	 * generates an id for that document based on the current time: the id is
	 * 'd' + new Date().getTime().
	 */
	this.nextID = function() {
		return 'd' + this._nextID++;
	};
	
};
	
services.AJAXServiceConstructor = function() {

	/**
	 * Temporary storage for the windw's status bar contents.
	 * 
	 * The status bar contents is replaced in the showWaitPane() function and
	 * reset in the hideWaitPane() function.
	 */
	this.statusBefore = "";

	/**
	 * The sequence number that is assigend to the next AJAX request that is
	 * sent.
	 * 
	 * This variable only exists in the MainLayout window. All application
	 * frames create new sequence numbers from this single source. This variable
	 * is initialized from a script in the MainLayout's component header.
	 * 
	 * txSequence = 0;
	 */

	/**
	 * Already received replies by sequence number.
	 * 
	 * Only used in the mainLayout instance.
	 */
	this.pendingReplies = new Object();

	/**
	 * An array of all requests, that are not yet answered by the server or
	 * whose replies have not been executed, because there are earlier requests
	 * that have not yet been answered.
	 * 
	 * Only used in the mainLayout instance.
	 */	
	this.pendingRequests = new Array();
	
	/**
	 * Array of request numbers of requests whose responses have been successfully received.
	 */
	this.acknowledgedRequests = new Array();
	
	/**
	 * Only used in the mainLayout instance.
	 */
	this.timer = null;

	var outer = this;
	this.processLaterClosure = function() {
		outer.processLater();
	};
	
	this.onCompletionClosure = function(result) {
		outer.onCompletion(result);
	};
};

services.AJAXServiceClass.prototype = new WebService("/servlet/AJAXServlet");
services.AJAXServiceConstructor.prototype = new services.AJAXServiceClass();
services.ajax = new services.AJAXServiceConstructor();


function XMLValueDecoder() {
	this.readObjects = new Array();
}

function XMLValueDecoderClass() {
	this.XML_VALUE_NS = "http://top-logic.com/ns/xml-value";
	
	this.IS_STRING_ELEMENT = new BAL.QName(this.XML_VALUE_NS, "string").getPredicate();
	this.IS_BOOLEAN_ELEMENT = new BAL.QName(this.XML_VALUE_NS, "boolean").getPredicate();
	this.IS_INT_ELEMENT = new BAL.QName(this.XML_VALUE_NS, "int").getPredicate();
	this.IS_FLOAT_ELEMENT = new BAL.QName(this.XML_VALUE_NS, "float").getPredicate();
	this.IS_OBJECT_ELEMENT = new BAL.QName(this.XML_VALUE_NS, "object").getPredicate();
	this.IS_ARRAY_ELEMENT = new BAL.QName(this.XML_VALUE_NS, "array").getPredicate();
	this.IS_NULL_ELEMENT = new BAL.QName(this.XML_VALUE_NS, "null").getPredicate();
	this.IS_REF_ELEMENT = new BAL.QName(this.XML_VALUE_NS, "ref").getPredicate();
	
	this.decode = function(node) {
		if (this.IS_STRING_ELEMENT(node)) {
			return BAL.DOM.getTextContent(node);
		}
		else if (this.IS_BOOLEAN_ELEMENT(node)) {
			return BAL.DOM.getTextContent(node) == "true";
		}
		else if (this.IS_NULL_ELEMENT(node)) {
			return null;
		}
		else if (this.IS_ARRAY_ELEMENT(node)) {
			var result = new Array();
			
			for (var child = BAL.DOM.getFirstChild(node); child != null; child = BAL.DOM.getNextSibling(child)) {
				if (! BAL.DOM.isElementNode(child)) continue;
				result.push(this.decode(child));
			}
			
			return result;			
		}
		else if (this.IS_OBJECT_ELEMENT(node)) {
			var result = new Object();
				
			for (var propertyNode = BAL.DOM.getFirstElementChild(node); propertyNode != null; propertyNode = BAL.DOM.getNextElementSibling(propertyNode)) {
				var nameNode = BAL.DOM.getFirstElementChild(propertyNode);
				var valueNode = BAL.DOM.getNextElementSibling(nameNode);

				result[BAL.DOM.getTextContent(nameNode)] = this.decode(valueNode);
			}
				
			return result;			
		}
			
		throw new Error("TODO: Not yet supported. Implement other choices. ");
	};
};

XMLValueDecoder.prototype = new XMLValueDecoderClass();



function XMLValueEncoder() {
}

function XMLValueEncoderClass() {
	this.encodeText = function(s) {
		s = s.replace(/\&/g, '&amp;');
		s = s.replace(/\</g, '&lt;');
		s = s.replace(/\>/g, '&gt;');

		// Filter out characters that cannot be encoded in XML.
		s = s.replace(/[^\u0009,\u000A,\u000D,\u0020-\uFFFF]/g, '');
		return s;
	};

	this.encode = function(value) {
		if (value == null) {
			return '<null xmlns="http://top-logic.com/ns/xml-value"/>';
		}
		else if ((typeof value) == "string") {
			return '<string xmlns="http://top-logic.com/ns/xml-value">' + this.encodeText(value) + '</string>';
		}
		else if ((typeof value) == "boolean") {
			return '<boolean xmlns="http://top-logic.com/ns/xml-value">' + String(value) + '</boolean>';
		}
		else if ((typeof value) == "number") {
			// TODO KHA/KBU value > 0x80000000 && value < 0x7fffffff // Check
			// for Integer range
			if (Math.floor(value) == value) // Check for numbers like 3.1425927
											// ...
			{
				return '<int xmlns="http://top-logic.com/ns/xml-value">' + String(value) + '</int>';
			} else {
				return '<float xmlns="http://top-logic.com/ns/xml-value">' + String(value) + '</float>';
			}
		}
		else if ((typeof value) == "object") {
			// The following line does not work for unknown reasons:
			// 
			// if (Array.isPrototypeOf(value)) {
			// if (value instanceof Array) {
			// if (value instanceof Array.prototype) {
			//
			// Use a fragile workaround instead:
			if ("length" in value) {
				var result = '<array xmlns="http://top-logic.com/ns/xml-value">';
				for (var n = 0; n < value.length; n++) {
					result += this.encode(value[n]);
				}
				result += '</array>';
				return result;
			}
			else {
				var result = '<object xmlns="http://top-logic.com/ns/xml-value">';
				for (var name in value) {
					result += '<property>';
					result += '<name>' + name + '</name>';
					result += this.encode(value[name]);
					result += '</property>';
				}
				result += '</object>';
				return result;
			}
		}
		// TODO: Missing branches for Number, missing reference handling.
	};
}

XMLValueEncoder.prototype = new XMLValueEncoderClass();


/**
 * Service for logging client-side events.
 */
services.log = {
	ERROR: "ERROR",
	WARN: "WARN",
	INFO: "INFO",
	
	COMPONENT_NAME: "unknown",

	error: function(message, ex) {
		this.transmit(this.ERROR, message, ex);
	},

	warn: function(message, ex) {
		this.transmit(this.WARN, message, ex);
	},
	
	info: function(message, ex) {
		this.transmit(this.INFO, message, ex);
	},


	transmit: function(level, message, ex) {
		var handler = window.onerror;
		
		// Disable the error handler while transmitting the event, because an
		// error during transmission is likely to produce an infinite loop.
		window.onerror = function(ex) { 
			alert("Error during AJAX transmission: " + ex); 
		};
		try {
			BAL.getMainLayoutWindow().services.ajax.invokeStatic('log', {
				level: level, 
				message: message,
				exception: ex,
				source: String(document.location), 
				component: this.COMPONENT_NAME
			});
		} finally {
			window.onerror = handler;
		}
	},
	
	/**
	 * Install a global error handler that reports all uncaught client-side
	 * exceptions back to the server.
	 */
	installGlobalHandler: function() {
		window.onerror = function(ex) {
			services.log.error("Uncaught JavaScript exception", ex);
		};
	}
};

services.FocusedElementStackClass = function() {
	
	this.focusedElementPathProperty = "focusedElementPath";
	
	this.setFocusedElementOfVisibleLayer = function(rootDocument, focusedElementPathOnLayer) {
		var visibleLayer = KEYBOARD_NAVIGATION.getTopmostVisibleLayerAnchor(BAL.getTopLevelDocument());
		
		if(this.layerHasNoFocusContainer(visibleLayer)) {
			this.createFocusContainer(visibleLayer);
		}
		
		var stackEntry = new Object();
		stackEntry.rootDocument = rootDocument;
		stackEntry.elementPath = focusedElementPathOnLayer;
		visibleLayer[this.focusedElementPathProperty] = stackEntry;
	};
	
	this.layerHasNoFocusContainer = function(layer) {
		return layer[this.focusedElementPathProperty] == null;
	};
	
	this.createFocusContainer = function (layer) {
		var dummyEntry = new Object();
		dummyEntry.rootDocument = null;
		dummyEntry.elementPath = null;
		layer[this.focusedElementPathProperty] = dummyEntry;
	};
	
	this.resetFocusContainer = function () {
		var visibleLayer = KEYBOARD_NAVIGATION.getTopmostVisibleLayerAnchor(BAL.getTopLevelDocument());
		this.createFocusContainer(visibleLayer);
	};
	
	this.hasNewlyOpenedLayer = function() {
		var visibleLayer = KEYBOARD_NAVIGATION.getTopmostVisibleLayerAnchor(BAL.getTopLevelDocument());
		return this.layerHasNoFocusContainer(visibleLayer);
	};
	
	this.getFocusedElementOfVisibleLayer = function() {
		return this.getElementPath();
	};
	
	this.getElementPath = function() {
		var visibleLayer = KEYBOARD_NAVIGATION.getTopmostVisibleLayerAnchor(BAL.getTopLevelDocument());
		var focusedElementPath = visibleLayer[this.focusedElementPathProperty];
		
		// For each retrieval a read reset must be done to the path object. Otherwise
		// multiple read attempt will fail.
		if(focusedElementPath.elementPath != null) {
			focusedElementPath.elementPath.resetPathRead();
		}
		return focusedElementPath;
	};
};

services.FocusRestoreClass = function() {
	
	this.currentActiveElement = null;
	this.currentTopLevelDocument = null;
	
	this.saveFocusState = function() {
		var documentOfAction = document;
        if(documentOfAction != null) {
        	this.currentActiveElement = BAL.DOM.getCurrentActiveSimpleElement(documentOfAction);
        	this.currentTopLevelDocument = BAL.getTopLevelDocumentOf(documentOfAction);
        	var focusStack = this.getFocusStack();
        	if (this.currentActiveElement != null) {
        		var currentActiveElementPath = BAL.DOM.getPath(BAL.getTopLevelDocumentOf(documentOfAction), this.currentActiveElement);
        		focusStack.setFocusedElementOfVisibleLayer(this.currentTopLevelDocument, currentActiveElementPath);
        	} else if(!focusStack.hasNewlyOpenedLayer()){
        		focusStack.setFocusedElementOfVisibleLayer(this.currentTopLevelDocument, null);
        	}
        }
        
	};
	
	this.getFocusStack = function() {
		return BAL.getTopLevelWindowOf(this.currentTopLevelDocument).services.focusedElementStack;
	};
	
	this.restoreFocusState = function() {
		var documentOfAction = document;
		if(this.currentTopLevelDocument != null) {
			var focusStack = this.getFocusStack();
			if(focusStack.hasNewlyOpenedLayer()) {
				this.setFocusToFirstFocusableElement(this.currentTopLevelDocument);
			} else {
				var currentActiveElement = focusStack.getFocusedElementOfVisibleLayer();
				if (currentActiveElement.elementPath != null) {
					this.restoreFocusOfElement(documentOfAction, currentActiveElement.elementPath);
				}
			}
		}
		
		this.currentActiveElement = null;
		this.currentTopLevelDocument = null;
	};
	
	this.restoreFocusOfElement = function(documentOfAction, elementPath) {
		var bestMatchingNode = BAL.DOM.getNodeFromPath(this.currentTopLevelDocument, elementPath);
		if(KEYBOARD_NAVIGATION.isBelowVisibleTabroot(bestMatchingNode.domReference)) {
			if(bestMatchingNode.pathMatchedFully && BAL.DOM.canBeActive(bestMatchingNode.domReference)) {
				if(documentOfAction == null || BAL.DOM.getCurrentActiveSimpleElement(documentOfAction) != bestMatchingNode.domReference) {
					BAL.focus(bestMatchingNode.domReference);
				}
			} 
		}
		else {
			this.setFocusToFirstFocusableElement(this.currentTopLevelDocument);
		}
	};
	
	this.setFocusToFirstFocusableElement = function(documentOfAction) {
		try {
			if(this.currentActiveElement != null) {
				this.currentActiveElement.blur();
			}
		} catch(ex) {
			// Exception may occur, if the active element is not part of the DOM
			// anymore.
			// In this case it already has been "blured".
		}
		KEYBOARD_NAVIGATION.focusFirstFocusableElementInVisibleLayer(documentOfAction);
		
		var focusStack = this.getFocusStack();
		var visibleLayer = KEYBOARD_NAVIGATION.getTopmostVisibleLayerAnchor(BAL.getTopLevelDocument());
		
		if(focusStack.layerHasNoFocusContainer(visibleLayer)) {
			focusStack.createFocusContainer(visibleLayer);
		}
	};
};

services.focusedElementStack = new services.FocusedElementStackClass();
services.focusRestore = new services.FocusRestoreClass();

services.NodeSet = function() {
	this.map = new services.LinkedMap();
	
	this.clear = function() {
		this.map.clear();
	};
	
	this.add = function(node) {
		this.map.put(node.id, node);
	};
	
	this.toArray = function() {
		return this.map.values();
	};
	
	this.size = function() {
		return this.map.size();
	};
	
	this.contains = function(node) {
		return this.map.contains(node.id);
	};
	
	this.isEmpty = function() {
		return this.map.size() == 0;
	};
};
;
// Compiled from 'script/clientsniffer.js'
//<!--
// Ultimate client-side JavaScript client sniff. Version 3.03
// (C) Netscape Communications 1999-2001.  Permission granted to reuse and distribute.
// Revised 17 May 99 to add is_nav5up and is_ie5up (see below).
// Revised 20 Dec 00 to add is_gecko and change is_nav5up to is_nav6up
//                      also added support for IE5.5 Opera4&5 HotJava3 AOLTV
// Revised 22 Feb 01 to correct Javascript Detection for IE 5.x, Opera 4, 
//                      correct Opera 5 detection
//                      add support for winME and win2k
//                      synch with browser-type-oo.js
// Revised 26 Mar 01 to correct Opera detection
// Revised 02 Oct 01 to add IE6 detection

// Everything you always wanted to know about your JavaScript client
// but were afraid to ask. Creates "is_" variables indicating:
// (1) browser vendor:
//     is_nav, is_ie, is_opera, is_hotjava, is_webtv, is_TVNavigator, is_AOLTV
// (2) browser version number:
//     is_major (integer indicating major version number: 2, 3, 4 ...)
//     is_minor (float   indicating full  version number: 2.02, 3.01, 4.04 ...)
// (3) browser vendor AND major version number
//     is_nav2, is_nav3, is_nav4, is_nav4up, is_nav6, is_nav6up, is_gecko, is_ie3,
//     is_ie4, is_ie4up, is_ie5, is_ie5up, is_ie5_5, is_ie5_5up, is_ie6, is_ie6up, is_hotjava3, is_hotjava3up,
//     is_opera2, is_opera3, is_opera4, is_opera5, is_opera5up
// (4) JavaScript version number:
//     is_js (float indicating full JavaScript version number: 1, 1.1, 1.2 ...)
// (5) OS platform and version:
//     is_win, is_win16, is_win32, is_win31, is_win95, is_winnt, is_win98, is_winme, is_win2k
//     is_os2
//     is_mac, is_mac68k, is_macppc
//     is_unix
//     is_sun, is_sun4, is_sun5, is_suni86
//     is_irix, is_irix5, is_irix6
//     is_hpux, is_hpux9, is_hpux10
//     is_aix, is_aix1, is_aix2, is_aix3, is_aix4
//     is_linux, is_sco, is_unixware, is_mpras, is_reliant
//     is_dec, is_sinix, is_freebsd, is_bsd
//     is_vms
//
// See http://www.it97.de/JavaScript/JS_tutorial/bstat/navobj.html and
// http://www.it97.de/JavaScript/JS_tutorial/bstat/Browseraol.html
// for detailed lists of userAgent strings.
//
// Note: you don't want your Nav4 or IE4 code to "turn off" or
// stop working when new versions of browsers are released, so
// in conditional code forks, use is_ie5up ("IE 5.0 or greater") 
// is_opera5up ("Opera 5.0 or greater") instead of is_ie5 or is_opera5
// to check version in code which you want to work on future
// versions.

    // convert all characters to lowercase to simplify testing
    var agt=navigator.userAgent.toLowerCase();

    // *** BROWSER VERSION ***
    // Note: On IE5, these return 4, so use is_ie5up to detect IE5.
    var is_major = parseInt(navigator.appVersion);
    var is_minor = parseFloat(navigator.appVersion);

    // Note: Opera and WebTV spoof Navigator.  We do strict client detection.
    // If you want to allow spoofing, take out the tests for opera and webtv.
    var is_nav  = ((agt.indexOf('mozilla')!=-1) && (agt.indexOf('spoofer')==-1)
                && (agt.indexOf('compatible') == -1) && (agt.indexOf('opera')==-1)
                && (agt.indexOf('webtv')==-1) && (agt.indexOf('hotjava')==-1));
    var is_nav2 = (is_nav && (is_major == 2));
    var is_nav3 = (is_nav && (is_major == 3));
    var is_nav4 = (is_nav && (is_major == 4));
    var is_nav4up = (is_nav && (is_major >= 4));
    var is_navonly      = (is_nav && ((agt.indexOf(";nav") != -1) ||
                          (agt.indexOf("; nav") != -1)) );
    var is_nav6 = (is_nav && (is_major == 5));
    var is_nav6up = (is_nav && (is_major >= 5));
    var is_gecko = (agt.indexOf('gecko') != -1);


    var is_ie     = ((agt.indexOf("msie") != -1) && (agt.indexOf("opera") == -1));
    var is_ie3    = (is_ie && (is_major < 4));
    var is_ie4    = (is_ie && (is_major == 4) && (agt.indexOf("msie 4")!=-1) );
    var is_ie4up  = (is_ie && (is_major >= 4));
    var is_ie5    = (is_ie && (is_major == 4) && (agt.indexOf("msie 5.0")!=-1) );
    var is_ie5_5  = (is_ie && (is_major == 4) && (agt.indexOf("msie 5.5") !=-1));
    var is_ie5up  = (is_ie && !is_ie3 && !is_ie4);
    var is_ie5_5up =(is_ie && !is_ie3 && !is_ie4 && !is_ie5);
    var is_ie6    = (is_ie && (is_major == 4) && (agt.indexOf("msie 6.")!=-1) );
    var is_ie6up  = (is_ie && !is_ie3 && !is_ie4 && !is_ie5 && !is_ie5_5);
    var is_ie7    = (is_ie && (is_major == 4) && (agt.indexOf("msie 7.")!=-1));

    // KNOWN BUG: On AOL4, returns false if IE3 is embedded browser
    // or if this is the first browser window opened.  Thus the
    // variables is_aol, is_aol3, and is_aol4 aren't 100% reliable.
    var is_aol   = (agt.indexOf("aol") != -1);
    var is_aol3  = (is_aol && is_ie3);
    var is_aol4  = (is_aol && is_ie4);
    var is_aol5  = (agt.indexOf("aol 5") != -1);
    var is_aol6  = (agt.indexOf("aol 6") != -1);

    var is_opera = (agt.indexOf("opera") != -1);
    var is_opera2 = (agt.indexOf("opera 2") != -1 || agt.indexOf("opera/2") != -1);
    var is_opera3 = (agt.indexOf("opera 3") != -1 || agt.indexOf("opera/3") != -1);
    var is_opera4 = (agt.indexOf("opera 4") != -1 || agt.indexOf("opera/4") != -1);
    var is_opera5 = (agt.indexOf("opera 5") != -1 || agt.indexOf("opera/5") != -1);
    var is_opera5up = (is_opera && !is_opera2 && !is_opera3 && !is_opera4);

    var is_webtv = (agt.indexOf("webtv") != -1); 

    var is_TVNavigator = ((agt.indexOf("navio") != -1) || (agt.indexOf("navio_aoltv") != -1)); 
    var is_AOLTV = is_TVNavigator;

    var is_hotjava = (agt.indexOf("hotjava") != -1);
    var is_hotjava3 = (is_hotjava && (is_major == 3));
    var is_hotjava3up = (is_hotjava && (is_major >= 3));

    // *** JAVASCRIPT VERSION CHECK ***
    var is_js;
    if (is_nav2 || is_ie3) is_js = 1.0;
    else if (is_nav3) is_js = 1.1;
    else if (is_opera5up) is_js = 1.3;
    else if (is_opera) is_js = 1.1;
    else if ((is_nav4 && (is_minor <= 4.05)) || is_ie4) is_js = 1.2;
    else if ((is_nav4 && (is_minor > 4.05)) || is_ie5) is_js = 1.3;
    else if (is_hotjava3up) is_js = 1.4;
    else if (is_nav6 || is_gecko) is_js = 1.5;
    // NOTE: In the future, update this code when newer versions of JS
    // are released. For now, we try to provide some upward compatibility
    // so that future versions of Nav and IE will show they are at
    // *least* JS 1.x capable. Always check for JS version compatibility
    // with > or >=.
    else if (is_nav6up) is_js = 1.5;
    // NOTE: ie5up on mac is 1.4
    else if (is_ie5up) is_js = 1.3

    // HACK: no idea for other browsers; always check for JS version with > or >=
    else is_js = 0.0;

    // *** PLATFORM ***
    var is_win   = ( (agt.indexOf("win")!=-1) || (agt.indexOf("16bit")!=-1) );
    // NOTE: On Opera 3.0, the userAgent string includes "Windows 95/NT4" on all
    //        Win32, so you can't distinguish between Win95 and WinNT.
    var is_win95 = ((agt.indexOf("win95")!=-1) || (agt.indexOf("windows 95")!=-1));

    // is this a 16 bit compiled version?
    var is_win16 = ((agt.indexOf("win16")!=-1) || 
               (agt.indexOf("16bit")!=-1) || (agt.indexOf("windows 3.1")!=-1) || 
               (agt.indexOf("windows 16-bit")!=-1) );  

    var is_win31 = ((agt.indexOf("windows 3.1")!=-1) || (agt.indexOf("win16")!=-1) ||
                    (agt.indexOf("windows 16-bit")!=-1));

    var is_winme = ((agt.indexOf("win 9x 4.90")!=-1));
    var is_win2k = ((agt.indexOf("windows nt 5.0")!=-1));

    // NOTE: Reliable detection of Win98 may not be possible. It appears that:
    //       - On Nav 4.x and before you'll get plain "Windows" in userAgent.
    //       - On Mercury client, the 32-bit version will return "Win98", but
    //         the 16-bit version running on Win98 will still return "Win95".
    var is_win98 = ((agt.indexOf("win98")!=-1) || (agt.indexOf("windows 98")!=-1));
    var is_winnt = ((agt.indexOf("winnt")!=-1) || (agt.indexOf("windows nt")!=-1));
    var is_win32 = (is_win95 || is_winnt || is_win98 || 
                    ((is_major >= 4) && (navigator.platform == "Win32")) ||
                    (agt.indexOf("win32")!=-1) || (agt.indexOf("32bit")!=-1));

    var is_os2   = ((agt.indexOf("os/2")!=-1) || 
                    (navigator.appVersion.indexOf("OS/2")!=-1) ||   
                    (agt.indexOf("ibm-webexplorer")!=-1));

    var is_mac    = (agt.indexOf("mac")!=-1);
    // hack ie5 js version for mac
    if (is_mac && is_ie5up) is_js = 1.4;
    var is_mac68k = (is_mac && ((agt.indexOf("68k")!=-1) || 
                               (agt.indexOf("68000")!=-1)));
    var is_macppc = (is_mac && ((agt.indexOf("ppc")!=-1) || 
                                (agt.indexOf("powerpc")!=-1)));

    var is_sun   = (agt.indexOf("sunos")!=-1);
    var is_sun4  = (agt.indexOf("sunos 4")!=-1);
    var is_sun5  = (agt.indexOf("sunos 5")!=-1);
    var is_suni86= (is_sun && (agt.indexOf("i86")!=-1));
    var is_irix  = (agt.indexOf("irix") !=-1);    // SGI
    var is_irix5 = (agt.indexOf("irix 5") !=-1);
    var is_irix6 = ((agt.indexOf("irix 6") !=-1) || (agt.indexOf("irix6") !=-1));
    var is_hpux  = (agt.indexOf("hp-ux")!=-1);
    var is_hpux9 = (is_hpux && (agt.indexOf("09.")!=-1));
    var is_hpux10= (is_hpux && (agt.indexOf("10.")!=-1));
    var is_aix   = (agt.indexOf("aix") !=-1);      // IBM
    var is_aix1  = (agt.indexOf("aix 1") !=-1);    
    var is_aix2  = (agt.indexOf("aix 2") !=-1);    
    var is_aix3  = (agt.indexOf("aix 3") !=-1);    
    var is_aix4  = (agt.indexOf("aix 4") !=-1);    
    var is_linux = (agt.indexOf("inux")!=-1);
    var is_sco   = (agt.indexOf("sco")!=-1) || (agt.indexOf("unix_sv")!=-1);
    var is_unixware = (agt.indexOf("unix_system_v")!=-1); 
    var is_mpras    = (agt.indexOf("ncr")!=-1); 
    var is_reliant  = (agt.indexOf("reliantunix")!=-1);
    var is_dec   = ((agt.indexOf("dec")!=-1) || (agt.indexOf("osf1")!=-1) || 
           (agt.indexOf("dec_alpha")!=-1) || (agt.indexOf("alphaserver")!=-1) || 
           (agt.indexOf("ultrix")!=-1) || (agt.indexOf("alphastation")!=-1)); 
    var is_sinix = (agt.indexOf("sinix")!=-1);
    var is_freebsd = (agt.indexOf("freebsd")!=-1);
    var is_bsd = (agt.indexOf("bsd")!=-1);
    var is_unix  = ((agt.indexOf("x11")!=-1) || is_sun || is_irix || is_hpux || 
                 is_sco ||is_unixware || is_mpras || is_reliant || 
                 is_dec || is_sinix || is_aix || is_linux || is_bsd || is_freebsd);

    var is_vms   = ((agt.indexOf("vax")!=-1) || (agt.indexOf("openvms")!=-1));

//--> end hide JavaScript
;
// Compiled from 'script/util.js'
/**
 *
 * Various JavaScript utility functions.
 *
 * Copyright (c) 2001-2008 Business Operation Systems GmbH. All Rights Reserved.
 */

// Set the focus into the first editable component of the first form.
//
// This method is only a caller for the selectFirst() with a specific
// timeout for 100 msec.
function focusFirst () {
    window.setTimeout(selectFirst, 100);
} 


// Set the focus into the first editable component of the first form.
// COPIED TO formCheck.js
function selectFirst() {
	
	// alert("scrollTop: " + scrollTop);
	
	// Use last forms in case, a dialog is rendered over an inline rendered form. 
	// In that case, the first input in the last form (the dialog) must be selected.
    var theForm = document.forms[document.forms.length - 1];
    if (theForm && theForm.elements) {
    	var theElement = null;
	    
    	var selectionSuccessful = false;
    	for (var thePos = 0; !selectionSuccessful && (thePos < theForm.elements.length); thePos++) {
            theElement = theForm.elements[thePos];
            selectionSuccessful = selectElement(theElement, false);
        }
    }
}

/**
 * Sets the focus to an element, in case it is focusable.
 * @param theElement - the element, which shall receive focus
 * @param changeScrollPosition - true, if the scroll position shall be changed in order to show
 * 								 the focusable element, false otherwise
 * @returns true, if selection was successful, false otherwise
 */
function selectElement(theElement, changeScrollPosition) {
	if (!theElement.disabled
		&& ((theElement.tagName.toLowerCase() == "textarea")
				|| (('type' in theElement)
				&& (theElement.type == "text")))
    ) {
    	// Check, whether the element and all of its parents are visible.
		var anchestor = theElement;
		if(window.KEYBOARD_NAVIGATION != null) {
			var topmostVisibleTabroot = KEYBOARD_NAVIGATION.getTopmostVisibleLayerAnchor(BAL.getTopLevelDocumentOf(theElement));
			
			if(!KEYBOARD_NAVIGATION.tabCycleRootFilter(topmostVisibleTabroot)) {
				return false;
			}
			
	    	while (KEYBOARD_NAVIGATION.hasParent(anchestor) && anchestor != topmostVisibleTabroot) {
	    		if (!BAL.isVisible(anchestor)) {
	    			return false;
	    		}
				anchestor = KEYBOARD_NAVIGATION.getParentElement(anchestor);
	    	}
	    	
	    	if(anchestor != topmostVisibleTabroot) {
	    		return false;
	    	}
		} else {
			while (anchestor != null && anchestor.tagName.toLowerCase() != "html") {
				if (!BAL.isVisible(anchestor)) {
					return false;
				}
				anchestor = anchestor.parentNode;
			}
		}
		
    	if(!changeScrollPosition && window.TL != null) {
			// Check, whether the element is on the currently visible part of the form.
    		var offsetParent = _getOffsetLayoutParent(theElement);
    		var position = BAL.getElementPositionRelativeTo(theElement, offsetParent);
    		var elementHeight = BAL.getElementHeight(theElement);
    		var viewportHeight;
    		var scrollTop;
    		if(offsetParent != BAL.getBodyElement(document)) {
				viewportHeight = BAL.getElementHeight(offsetParent);
				scrollTop = BAL.getScrollTopElement(offsetParent);
    		} else{
    			viewportHeight = BAL.getViewportHeight();
    			scrollTop = BAL.getScrollTop(window);
    		}
    		if (position.y < scrollTop || position.y + elementHeight > scrollTop + viewportHeight) {
    			return false;
    		}
    	}

    	try {
            BAL.focus(theElement);
            return true;
    	} catch (ex) {
    		// Still not selectable, return false.
    		return false;
    	}
    }
    return false;
}

function _getOffsetLayoutParent(element) {
	var offsetParent = element.offsetParent;
	if(offsetParent == BAL.getBodyElement(document) || _isLayoutElement(offsetParent)) {
		return offsetParent;
	} else {
		return _getOffsetLayoutParent(offsetParent);
	}
	
}

function _isLayoutElement(element) {
	return TL.getTLAttribute(element, "layout") != null; 
}

function blurFocus(element) {
	if(element != undefined && element != null) {
		element.blur();
	}
	resetFocusContainer();
}

function resetFocusContainer() {
	BAL.getTopLevelWindow().services.focusedElementStack.resetFocusContainer();
}

// opens the Clipboard of the current user.
function clipboard(aFunction, anID, aContext, aFrame) {
	var theURL = aContext + "/jsp/display/clipboard/DefaultView.jsp";

	if (aFunction != null) {
		if (anID != null) {
			theURL +='?oid=' + escape (anID) + '&mode=' + aFunction;
		}
		else {
			theURL +='?' + aFunction;
		}
	}

	if (aFunction == "add") {
		var theFrame = findFrame(aFrame);

		if (theFrame == null) {
			theFrame = findFrame(aFrame, opener);
		}

		theURL += "&directClose=1";

//		alert("Execute: " + theURL);
		theFrame.location.href = theURL;

    alert (_i18n_clipboard_add);
 	}
	else {
		openSimpleDialog('ClipBoard', theURL, '640', '480');
	}
}

// Creates a window without status, scroll/toolbars
function openDialog (anID, anURL, aWidth, aHeight) {
    return openScrollableDialog(anID, anURL, aWidth, aHeight, "no");
}

function openScrollableDialog(anID, anURL, aWidth, aHeight, withScroll) {
  	var theWindow = services.ajax.topWindow;
  	var theXPos   = theWindow.screenLeft;
  	var theYPos   = theWindow.screenTop;
  	var theWidth  = BAL.getViewportWidthOfWindowObject(theWindow);
  	var theHeight = BAL.getViewportHeightOfWindowObject(theWindow);

    var screenX   = theXPos + (theWidth - aWidth) / 2;
    var screenY   = theYPos  + (theHeight - aHeight) / 2;

    if (withScroll == null) {
        withScroll = "yes";
    }

    return window.open (anURL,
                             anID, 
                             "status=no" +
                             ",resizable=yes" + 
                             ",scrollbars=" + withScroll +
                             ",toolbar=no" +
                             ",width=" + aWidth +
                             ",height=" + aHeight +
                             ",top=" + screenY +
                             ",left=" + screenX);

}

function openSimpleDialog (anID, anURL, aWidth, aHeight) {
    openDialog (anID, anURL, aWidth, aHeight);
}

// Opens a fairly defaultish browser window. Use zero values for aWidth and aHeight
// for default values. (I. e. width and height of current window.)
function openBrowserWindow(anId, anURL, aWidth, aHeight) {
	if (!aWidth && !aHeight || aWidth == 0 && aHeight == 0) {
		aWidth = getFrameWidth(services.ajax.topWindow);
		aHeight = getFrameHeight(services.ajax.topWindow);
	}
    
	window.open(anURL, anId, "toolbar=yes,location=yes,directories=yes,status=yes,menubar=yes,scrollbars=yes,copyhistory=yes,resizable=yes,screenX=0,screenY=0,width="
        + aWidth + ",height="
        + aHeight + ",top,left");
}

// Creates a window without status, scroll/toolbars
function openBareWindow(id, url, width, height) {
	if (!width && !height || width == 0 && height == 0) {
		width = getFrameWidth(services.ajax.topWindow);
		height = getFrameHeight(services.ajax.topWindow);
	}
	
    return window.open(url,id, "status=no,resizable=yes,scrollbars=no,toolbar=no,width="+width+",height="+height);
}

// Creates a window without status, scroll/toolbars
function openQueryWindow(id, url, query, width, height) {
    queryurl = "&query=" + escape (query);
    window.open(url+queryurl,id, "status=no,resizable=yes,scrollbars=no,toolbar=no,width="+width+",height="+height);
}

// Creates a window without status, toolbars but with scroll.
function openBareScrollingWindow(id, url, width, height) {
	if (!width && !height || width == 0 && height == 0) {
		width = getFrameWidth(services.ajax.topWindow);
		height = getFrameHeight(services.ajax.topWindow);
	}
	
    window.open(url,id,"status=no,resizable=yes,scrollbars=yes,toolbar=no,width="+width+",height="+height);
}

// Creates a window without status, toolbars but with scroll and menu.
function openBareScrollingWindowWithMenubar(id, url, width, height) {
    window.open(url,id,"status=no,resizable=yes,scrollbars=yes,toolbar=no,menubar=yes,width="+width+",height="+height);
}

// Creates a resizable pop-up window with scrollbars.
function openBareResizableWindow(id, url, width, height) {
    window.open(url,id,"status=no,resizable=yes,scrollbars=yes,toolbar=no,locationbar=no,width="+width+",height="+height);
}

// Creates a window that has (nearly) the size of the screen.
function openMaxWindow(id, url) {
    var screenwidth = screen.availWidth - 10;
    var screenheight = screen.availHeight - 30;
    window.open(url,id,"status=no,resizable=yes,scrollbars=yes,toolbar=no,locationbar=no,screenX=0,screenY=0,width="+screenwidth+",height="+screenheight+",top,left");
}

// An executeCommandWindow is a window that can be used to (guess!) execute 'commands'
// by providing an url to forward to. The window itself contains a forward statement
// which makes sure that the exCmdW is closed after the command has finished.
//
// @param    anURL    an URL being used as destination of the forward statement

function openExecuteCommand(anURL) {
	alert("oECW: anURL is "+escape(anURL));
    window.open(anURL,"tl_execute_command","status=no,resizable=yes,scrollbars=no,toolbar=no,width=100,height=100");
	alert("done");
}


// Sets the title of the window (or, rather, the document) to aTitle
function setWindowTitle(aTitle) {
	document.title = aTitle;
}


// Changes the state of all checkboxes with the name provided
// in aName in form aForm to the value aValue. See function changeCheckboxState()
// for details.
function toggleCheckBoxes(aForm, aName, aValue){
	var len = aForm.elements.length;

	var i;
	for (i = 0; i < len; i++) {
		var theElement = aForm.elements[i];
		if (aName == null) {
				changeCheckboxState(theElement, aValue);
		} else if (theElement.name == aName) {
				changeCheckboxState(theElement, aValue);
		}
	}
}

// Changes the value of a given checkbox:
//
//     aValue == 0   =>   checkbox is unchecked
//     aValue == 1   =>   checkbox is checked
//     aValue == 0   =>   checkbox is inverted
//     
function changeCheckboxState(aCheckbox, aValue) {
	if (!aCheckbox.disabled) {
		if (aValue == 0) {
			aCheckbox.checked = false;
		} else if (aValue == 1) {
			aCheckbox.checked = true;
		} else if (aValue == -1) {
			aCheckbox.checked = !aCheckbox.checked;
		}
	}
}


// Returns an Array of all elements whose names match the
// specified RegExp pattern in the specified form.
function getElementsByPattern(aPattern, aForm) {
	var butArray = new Array();
	var len = aForm.elements.length;
	var j = 0;
	for (var i = 0; i < len; i++) {
		var theElement = aForm.elements[i];
		var elName = theElement.name;
		if ( elName.search(aPattern) > -1) {
			butArray[j++] = theElement;
		}
	}

	return butArray;
}


// Parses parameters out of a string.
function getParameter(string, parm, delim) {
    // returns value of parm from string
    if (string.length == 0) return '';
    
    var sPos = string.indexOf(parm + "=");
    if (sPos == -1) return '';
    sPos = sPos + parm.length + 1;
    
    var ePos = string.indexOf(delim, sPos);
    if (ePos == -1) ePos = string.length;
    
    return unescape(string.substring(sPos, ePos));
}

// Get a parameter value from query string
function getPageParameter(parameterName, defaultValue) {
    var s = self.location.search;
    if ((s == null) || (s.length < 1)) {
        return defaultValue;
    }
    return getParameter(s, parameterName, '&');
}


// Retrieves the value of the selected option of a select element
// defined by the id provided.
function getSelectedOptionValue (anID) {
	theSelector = document.getElementById(anID); 
	if (theSelector == null) return null;
	theElement = theSelector.options;
	theValue = theElement[theElement.selectedIndex].value;
	return theValue;
}


// Retrieves the value of the element
// defined by the id provided.
function getValueByID (anID) {
	alert(anID);
	theElement = document.getElementById(anID);
	if (theElement == null) return null;
	theValue = theElement.value;
	return theValue;
}


// Returns the value of the element with id anID
function getElementValueByID(anID) {
	var element = document.getElementById(anID);
	if (element) {
		return element.value;
	} else {
		return null;
	}
}

// Returns the selected value of the (option) element with id anID.
function getSelectedOptionValueByID(anID) {
	var element = document.getElementById(anID);
	if (element) {
		var index = element.selectedIndex;
		return element[index].value;
	}
}

// Returns true if the element with id anID is checked.
function elementChecked(anID) {
	var element = document.getElementById(anID);
	if (element) {
		return element.checked;
	}
}




// Find the frame with the given name and return this.
//
// param    aFrameName     The name of the frame to be found.
// param    aStartFrame    THe frame to start from.
// return   The searched frame or null, if no frame with that name found.
function findFrame(aFrameName, aStartFrame) {
	if (aStartFrame == null) {
		aStartFrame = this;
}

	var theCurrentFrames = aStartFrame.frames;

	if (theCurrentFrames == null) {
		return null;
	}

	for (var i = 0; i < theCurrentFrames.length; i++) {
		if (theCurrentFrames[i].name == aFrameName) {
			return theCurrentFrames[i];
		}
	}

	var theParentFrame = aStartFrame.parent;

	if ((theParentFrame != null) && (theParentFrame != aStartFrame)) {
		return findFrame(aFrameName, theParentFrame);
	} 
	else {
		return null;
	}
}

function getMasterWindow() {
	return getMasterWindowFor(services.ajax.topWindow);
}


// called by getMasterWindow()
function getMasterWindowFor(aWindow) {

	var theWindow = aWindow.services.ajax.topWindow;
	//alert("theWindow is " + theWindow + ", name is " + theWindow.name);

	if ( (typeof theWindow != 'undefined') && (theWindow != null) ) {
		//alert("theWindow is " + theWindow);
		if (theWindow.name == "masterwindow") {
			return theWindow;
		} else {
			if ( (typeof theWindow.opener != 'undefined') && theWindow.opener != null) {
				theDeeperWindow = theWindow.opener;
				//alert("deeperWindow is " + theDeeperWindow);
				return getMasterWindowFor(theDeeperWindow);
			}
		}
	} else {
		//alert("reached " + theWindow + " and returning null");
		return null;
	}
	return null;
}



// Count the chars in an html-input field and display remaining chars that could be entered.
//
// If maxlimit is reached, no more chars could be entered in field.
// The countfield displays the countstatus
//
// Example see: com.top_logic.solutions.tts.util.TtsUtil printTextareaWithCounter()
//
// @param field	name of input field to count the chars
// @param countfield name of the field to display how many chars could be still entered 
// @param maxlimit maximum allowed chars in field

function countChars(field, countfield, maxlimit) {
    if (field.value.length > maxlimit) // if too long...trim it!
        field.value = field.value.substring(0, maxlimit);
    // otherwise, update 'characters left' counter
    else 
        countfield.value = maxlimit - field.value.length;
    }


// Returns the width (in px) of the given frame.
function getFrameWidth(aFrame) {
	var frameWidth;
	// NS4
	if (self.innerWidth)
		frameWidth = aFrame.innerWidth;
	// NS6 et al
	else if (document.documentElement && document.documentElement.clientWidth)
		frameWidth = aFrame.document.documentElement.clientWidth;
	// all others
	else if (document.body)
		frameWidth = aFrame.document.body.clientWidth;

	return frameWidth;
}



// Returns the height (in px) of the given frame.
function getFrameHeight(aFrame) {
	var frameHeight;
	// NS4
	if (self.innerHeight)
		frameHeight = aFrame.innerHeight;
	// NS6 et al
	else if (document.documentElement && document.documentElement.clientWidth)
		frameHeight = aFrame.document.documentElement.clientHeight;
	// all others
	else if (document.body)
		frameHeight = aFrame.document.body.clientHeight;

	return frameHeight;
}


// Sets the focus into the first editable element of the first form
// of the current document.
//
// This method actually just invokes _selectFirstFormElement() after a
// 100ms timeout. We assume that the page is loaded when 100ms have passed.
// Oh well ...
function selectFirstFormElement () {
    window.setTimeout("_selectFirstFormElement()",100);
} 

// Sets the focus into the first editable component of the first form
// of the current window.
function _selectFirstFormElement () {
    var theElement = null;
    var thePos     = 0;
    var theForm    = document.forms[0];

	if (theForm == null) return;

    while (theElement == null 
            && thePos < theForm.elements.length) {
        theElement = theForm.elements [thePos];

        if (theElement.type == "hidden" || theElement.disabled) {
            theElement = null;
            thePos++;
        }
    }

    if (theElement != null) {
        theElement.focus ();

        if (theElement.type != "button" 
                && theElement.type != "submit"
                && theElement.type != "reset") {
            theElement.select ();
        }
    }
}

/**
 * 
 * @param elementSize
 * @param paddingAndBorderSizes, container, which holds properties "paddingBefore", "paddingAfter", "borderBefore", "borderAfter" as numbers.
 *
 * @returns {Number}
 */
function calculateEffectiveSize(elementSize, paddingAndBorderSizes) {
	var calculated = elementSize -
		   paddingAndBorderSizes.paddingBefore -
		   paddingAndBorderSizes.paddingAfter -
		   paddingAndBorderSizes.borderBefore -
		   paddingAndBorderSizes.borderAfter;
	return Math.max(calculated, 0);
	
}

function createHideWaitpaneOnClickFunction(callbackFunction) {
	var clickFunction = function(event) {
		var waitpane = services.ajax.topWindow.document.getElementById("waitPane");
		BAL.removeEventListener(waitpane, "mousedown", clickFunction, true);
		services.ajax.hideWaitPane();
		callbackFunction.call(this, event);
	};
	return clickFunction;
}

function showClickResponsiveWaitpane(clickFunction) {
	var waitpane = services.ajax.topWindow.document.getElementById("waitPane");
	BAL.addEventListener(waitpane, "mousedown", clickFunction, true);
	services.ajax.showWaitPane();
}

function hideClickResponsiveWaitpane(clickFunction) {
	clickFunction.call();
}

function showInfoArea() {
	var infoServiceItems = [];
	for(var i = 0; i < arguments.length; i++) {
		infoServiceItems.push(arguments[i]);
	}
	BAL.getTopLevelWindow()._showInfoArea(infoServiceItems);
}

function _showInfoArea(infoServiceItems) {
	if(_hasPendingInfoItems()) {
		_appendInfoItems(infoServiceItems);
	} else {
		var topLevelDocument = BAL.getTopLevelDocument();
		var infoServiceContainerId = "infoService";
		var infoServiceContainer = topLevelDocument.getElementById(infoServiceContainerId);
		if (infoServiceContainer != null) {
			_stopInfoServiceFadeOut(infoServiceContainer);
		} else {
			infoServiceContainer = topLevelDocument.createElement("div");
			infoServiceContainer.id = infoServiceContainerId;
			BAL.DOM.addClass(infoServiceContainer, infoServiceContainerId);
			var pinningFunction = function(event) {
				_stopInfoServiceFadeOut(infoServiceContainer);
				BAL.removeEventListener(infoServiceContainer, "mouseenter", pinningFunction);
				BAL.DOM.addClass(infoServiceContainer, "pinnedInfoService");
				var infoItems = BAL.DOM.getChildElements(infoServiceContainer);
				infoItems.forEach(function(infoItem) {
					BAL.DOM.addClass(infoItem, "pinnedInfoServiceItem");
				});
			};
			BAL.addEventListener(infoServiceContainer, "mouseenter", pinningFunction);
			BAL.getBodyElement(topLevelDocument).appendChild(infoServiceContainer);
		}
		_appendInfoItems(infoServiceItems);
		_showInfoServiceBox(infoServiceContainer);
	}
}

function _stopInfoServiceFadeOut(infoServiceContainer) {
	BAL.getTopLevelWindow().clearTimeout(infoServiceContainer.hideInfoServiceContainerTimerId);
	if(infoServiceContainer.removeInfoServiceListener != null) {
		BAL.DOM.removeClass(infoServiceContainer, "hideInfoService");
		BAL.removeAnimationEndListener(infoServiceContainer, infoServiceContainer.removeInfoServiceListener);
	}
}

function closeInfoItem(itemId) {
	var topLevelDocument = BAL.getTopLevelDocument();
	var infoItem = topLevelDocument.getElementById(itemId);
	BAL.addAnimationEndListener(infoItem, function() {
		var infoServiceContainer = topLevelDocument.getElementById("infoService");
		infoServiceContainer.removeChild(infoItem);
		if(BAL.DOM.getChildElementCount(infoServiceContainer) == 0) {
			BAL.getBodyElement(topLevelDocument).removeChild(infoServiceContainer);
		}
	});
	var hideItemCssClass = "hideInfoServiceItem";
	infoItem.style.zIndex = 0;
	_modifyItemAnimation(hideItemCssClass, "100%", infoItem);
	BAL.DOM.addClass(infoItem, hideItemCssClass);
}

function toggleDetailMessage(messageId, buttonId) {
	var topLevelDocument = BAL.getTopLevelDocument();
	var detailMessage = topLevelDocument.getElementById(messageId);
	var expansionButton = topLevelDocument.getElementById(buttonId);
	if(BAL.DOM.containsClass(detailMessage, "infoServiceItemDetailMessageHidden")
		|| BAL.DOM.containsClass(detailMessage, "hideInfoServiceItemDetailMessage")) {
		_showDetailMessage(detailMessage, expansionButton);
	} else {
		_hideDetailMessage(detailMessage, expansionButton);
	}
}


function _showDetailMessage(detailMessage, expansionButton){
	BAL.DOM.removeClass(detailMessage, "infoServiceItemDetailMessageHidden");
	BAL.DOM.removeClass(detailMessage, "hideInfoServiceItemDetailMessage");
	BAL.DOM.removeClass(expansionButton, "regularInfoServiceItemDetailExpandButton");

	_modifyDetailMessageAnimation("showInfoServiceItemDetailMessage", "100%", BAL.DOM.getFirstElementChild(detailMessage));
	BAL.DOM.addClass(detailMessage, "showInfoServiceItemDetailMessage");
	BAL.DOM.addClass(expansionButton, "rotatedInfoServiceItemDetailExpandButton");
}


function _hideDetailMessage(detailMessage, expansionButton){
	BAL.DOM.removeClass(detailMessage, "showInfoServiceItemDetailMessage");
	BAL.DOM.removeClass(expansionButton, "rotatedInfoServiceItemDetailExpandButton");

	_modifyDetailMessageAnimation("hideInfoServiceItemDetailMessage", "0%", BAL.DOM.getFirstElementChild(detailMessage));
	BAL.DOM.addClass(detailMessage, "hideInfoServiceItemDetailMessage");
	BAL.DOM.addClass(expansionButton, "regularInfoServiceItemDetailExpandButton");
}

function _hasPendingInfoItems() {
	var infoServiceContainer = BAL.getTopLevelDocument().getElementById("infoService");
	return infoServiceContainer != null && _getPendingInfoItems().length > 0;
}

function _appendInfoItems(infoItems) {
	for(var i = 0; i < infoItems.length; i++) {
		_getPendingInfoItems().push(infoItems[i]);
	}
}

function _getPendingInfoItems() {
	var infoServiceContainer = BAL.getTopLevelDocument().getElementById("infoService");
	if(infoServiceContainer.pendingInfoItems == null) {
		infoServiceContainer.pendingInfoItems = [];
	}
	return infoServiceContainer.pendingInfoItems;
}

function _showInfoServiceBox(infoServiceContainer) {
	var topLevelDocument = BAL.getTopLevelDocument();
	BAL.insertAdjacentHTML(infoServiceContainer, "beforeend", _getPendingInfoItems()[0]);
	var infoItem = BAL.DOM.getLastElementChild(infoServiceContainer);
	infoItem.style.zIndex = 100000 - BAL.DOM.getChildElementCount(infoServiceContainer);
	if(BAL.DOM.containsClass(infoServiceContainer, "pinnedInfoService")) {
		BAL.DOM.addClass(infoItem, "showPinnedInfoServiceItem");
		_modifyItemAnimation("showPinnedInfoServiceItem", "0%", infoItem);
	} else {
		BAL.DOM.addClass(infoItem, "showInfoServiceItem");
		_modifyItemAnimation("showInfoServiceItem", "0%", infoItem);
	}
	var showNextItemListener = function(event) {
		BAL.removeAnimationEndListener(infoItem, showNextItemListener);
		_getPendingInfoItems().shift();
		if(_hasPendingInfoItems()) {
			_showInfoServiceBox(infoServiceContainer);
		} else {
			BAL.setScrollTopElementAnimated(infoServiceContainer, BAL.getElementY(infoItem), 500);
			if(!BAL.DOM.containsClass(infoServiceContainer, "pinnedInfoService")) {
				infoServiceContainer.hideInfoServiceContainerTimerId = BAL.getTopLevelWindow().setTimeout(function() {
					BAL.DOM.addClass(infoServiceContainer, "hideInfoService");
					infoServiceContainer.removeInfoServiceListener = function(event) {
						if(BAL.DOM.containsClass(infoServiceContainer, "hideInfoService")) {
							BAL.getBodyElement(topLevelDocument).removeChild(infoServiceContainer);
						}
					};
					BAL.addAnimationEndListener(infoServiceContainer, infoServiceContainer.removeInfoServiceListener);
				}, services.infoServiceFadeoutTimerSeconds * 1000);
			}
		}
	};
	BAL.addAnimationEndListener(infoItem, showNextItemListener);
}

function _modifyItemAnimation(keyframesName, step, infoItem) {
	var keyframes = BAL.findKeyframesRule(keyframesName);
	if (keyframes != null) {
		BAL.changeRule(keyframes, step, step + " {opacity: 0; margin-top: -" + BAL.getElementHeight(infoItem) + "px;}");
	}
}

function _modifyDetailMessageAnimation(keyframesName, step, detailMessage) {
	var keyframes = BAL.findKeyframesRule(keyframesName);
	if (keyframes != null) {
		BAL.changeRule(keyframes, step, step + " {height: " + BAL.getFullElementHeight(detailMessage) + "px;}");
	}
}
;
// Compiled from 'script/popUpSupport.js'
/**
 *
 *  Handles Popup tag JS
 *
 * Copyright (c) 2006-2008 Business Operation Systems GmbH. All Rights Reserved.
 */ 
 
 
var userSelectWindow = null;

// Make sure, that no unreferenced popup stays open after its 
// parent has been closed or reloaded.
window.onunload = function() {
	if (userSelectWindow != null) {
		userSelectWindow.close();
	}
}

function popUp(jsp, width, height){
	if (userSelectWindow != null) {
		userSelectWindow.close();
	}

	if (width == undefined) {
		width = "700";
	}
	if (height == undefined) {
		height = "455";
	}
	userSelectWindow = openBareWindow("userSelectorWindow",jsp, width, height);
}

function popUpSmall(jsp){
	popUp(jsp, "650");
}

function clearPopUp(aFieldName, anEnabledImg, aDisabledImg) {
	var theField = document.getElementById(aFieldName + "_label");
	
	if (theField != null) {
		theField.value = "";
	}

	var theImg = document.getElementById(aFieldName + "_clear");
	
	if (theImg != null) {
		theImg.src   = aDisabledImg;
		theImg.style.cursor ="default";
	}
}


function handleClearButton(aField, anEnabledImg, aDisabledImg) {
	if (aField != null) {
		var theName = aField.name;
		var theValue= aField.value;
		var isDisabled = true;
		
		if (theValue != null && theValue.length>0) {
			isDisabled = false;
		}

		var theImg = document.getElementById(theName.replace(/_label/, "_clear"));
		
		if (theImg != null) {
			if (isDisabled) {
				theImg.src   = aDisabledImg;
				theImg.style.cursor ="default";
			}
			else {
				theImg.src   = anEnabledImg;
				theImg.style.cursor ="pointer";				
			}
		}
	}
}


function closeUserSelector() {
	if (userSelectWindow != null) {
		userSelectWindow.close();
	}
}

function handleChange(element) {
	alert("Triggerd element "+element);
}

/**
 * <<event handler>>
 * 
 * If assigned to the document's onkeypress callback, the current window 
 * is closed, if the user presses the ESC key. 
 */
function closeWindowOnEscape(event) {
	var keyCode = BAL.getKeyCode(BAL.getEvent(event));
					
	if (keyCode == 27) {
		window.close();
		return false;
	} else {
		return true;
	}
}
;
// Compiled from 'script/table.js'
/**
 * Table Object Modell
 * 
 * Includes functions for tables and identifies the position of an element in a browser.
 *
 * Author:  <a href=mailto:bhu@top-logic.com>Bernhard Haumacher</a>
 * FIXME : ColDetection center mid rework and border with color
 * TODO : Persistenz for users
 * 
 * Copyright (c) 2010 Business Operation Systems GmbH. All Rights Reserved.
 */
TABLE = {
	
	doLog: false,

	UNDEFINED_DISPLAY_VERSION: -1,
	MINIMUM_COLUMN_WIDTH: 12,
	BROWSER_MAXIMUM_WIDTH: 900000,
	MINIMUM_FLEX_SPACE: 150, // 150 is randomely choosen for the space for flexible part
	COLUMN_MOVE_INIT_DELTA: 3, //px - amount of pixels mouse must have been moved, before column movement is recognized
	DEFAULT_TABLE_SEPARATOR_WIDTH: 5, //px
	tableSliceManagers: null,
	
	setColumnWidth: function(element, columnWidth) {
		BAL.setEffectiveWidth(element, columnWidth);
	},

	setColumnCellWidth: function(td, columnWidth) {
		this.setColumnWidth(td, columnWidth);
		TL.setTLAttribute(td, "column-width", columnWidth);
	},
	
	/**
	 * function called during moving the mouse when column drag & drop
	 * shall be activated, i.e. if the user wants to change the order of columns this
	 * function is triggered when he moves the mouse. If the moved mouse position is
	 * greater than a specified limit column movement will be initiated.
	 * 
	 * @param {Object}
	 *            event the event occured when moving the mouse
	 * @param {TABLE.MouseEventData}
	 *            mouseEvtData data holder object which holds information about
	 *            the mouse position when activating drag & drop
	 * @return {Boolean} always false
	 */
	mouseMoveInit: function(event, mouseEvtData) {
		var mousePosition = BAL.mouseCoordinates(event);
		var moveDelta = Math.abs(mouseEvtData.mousepos.x - mousePosition.x);
		if(moveDelta >= TABLE.COLUMN_MOVE_INIT_DELTA) {
			BAL.removeEventListener(document, "mousemove",	mouseEvtData.adjustmentInitFunction);
			BAL.addEventListener(document, "mousemove",	mouseEvtData.adjustmentFunction);
		}
		return false;
	},
	
	/**
	 * function called during moving the mouse when column drag & drop is
	 * activated, i.e. if the user wants to change the order of columns this
	 * function is triggered when he moves the mouse.
	 * 
	 * @param {Object}
	 *            event the event occured when moving the mouse
	 * @param {TABLE.MouseEventData}
	 *            mouseEvtData data holder object which holds information about
	 *            the mouse position when activating drag & drop
	 * @return {Boolean} always false
	 */
	mouseMove: function(event, mouseEvtData) {
		mouseEvtData.dragOrSort = true;
		if (mouseEvtData.isFirstMove) {
			TABLE.createNewDivElement(mouseEvtData);
			mouseEvtData.isFirstMove = false;
		}
		event = event || window.event;

		var mousePosition = BAL.mouseCoordinates(event);

		BAL.setElementX(mouseEvtData.dragObject, mousePosition.x - mouseEvtData.relativeMouse.x);
		BAL.setElementY(mouseEvtData.dragObject, mousePosition.y - mouseEvtData.relativeMouse.y);
		TABLE.colDetection(mouseEvtData.dragObject, mouseEvtData.limits);
		TABLE.checkForInsert(mouseEvtData, mousePosition);

		if (mouseEvtData.scrollLeftAreaUpperBoundX) {
			if (   (mousePosition.x >= mouseEvtData.scrollLeftAreaLowerBoundX)
                && (mousePosition.x <= mouseEvtData.scrollLeftAreaUpperBoundX)) {

				TABLE.scrollLeftFunction(mouseEvtData);
				
			} else if ((mousePosition.x >= mouseEvtData.scrollRightAreaLowerBoundX)
					&& (mousePosition.x <= mouseEvtData.scrollRightAreaUpperBoundX)) {

				TABLE.scrollRightFunction(mouseEvtData);
				
			} else {
        		if (mouseEvtData.timerID != 0) {
        			window.clearInterval(mouseEvtData.timerID);
        			mouseEvtData.timerID = 0;
        		}
			}
		}

		return false;
	},
	
	/**
	 * Starts a delyed function which scrolls the scroll pane to the left all
	 * 10ms.
	 * 
	 * @param {TABLE.MouseEventData}
	 *            mouseEvtData data holder object which holds information about
	 *            the mouse position when activating drag & drop
	 * @return {Boolean} always false
	 */
	scrollLeftFunction: function(mouseEvtData) {
		if (mouseEvtData.timerID == 0) {
			var scrollLeft = function() {
				mouseEvtData.horizontalScrollBar.scrollLeft -= mouseEvtData.scrollStepWidth;
				return false;
			};

			// Delay start, because of the user possibly wants to drag a flex
			// column to fix header or vice versa.
			var delayFunction = function() {
				mouseEvtData.timerID = window.setInterval(scrollLeft, 10);
			};
			mouseEvtData.timerID = window.setTimeout(delayFunction, 500);
			return false;
		}
	},

    /**
	 * Starts a function which scrolls the scroll pane to the right all 10ms.
	 * 
	 * @param {TABLE.MouseEventData}
	 *            mouseEvtData data holder object which holds information about
	 *            the mouse position when activating drag & drop
	 * @return {Boolean} always false
	 */
	scrollRightFunction: function(mouseEvtData) {
		if (mouseEvtData.timerID == 0) {
			var scrollRight = function() {
				mouseEvtData.horizontalScrollBar.scrollLeft += mouseEvtData.scrollStepWidth;
				return false;
			};
			
			mouseEvtData.timerID = window.setInterval(scrollRight, 10);

			return false;
		}
	},

	/**
	 * Change color of the elements by changing the class.
	 * 
	 * @param {TABLE.MouseEventData}
	 *            mouseEvtData holder object for the event data during start of
	 *            drag and drop
	 * @param {TABLE.Coordinates}
	 *            mousePosition current position of the mouse to retrieve object
	 *            to change color from
	 * @return {Boolean} always false
	 */
	checkForInsert: function(mouseEvtData, mousePosition) {
		var tableheader = mouseEvtData.headerList;
		var lookupFirstMatchingColumn = true;
		
		for (var i = 0; i < tableheader.length; i++) {
			var currentTableHeader = tableheader[i];
			var elementPos = TABLE.getElementPosition(currentTableHeader);
			var left = elementPos.topX;
			var right = elementPos.belowX;
			if (left < mousePosition.x && mousePosition.x < right && lookupFirstMatchingColumn) {
				var middle = (right + left) / 2 ;
				if (mousePosition.x <= middle) {
					TABLE.markLeftBorderForInsert(mouseEvtData, tableheader, i);
				} else {
					TABLE.markRightBorderForInsert(mouseEvtData, tableheader, i);
					if (i + 1 < tableheader.length) {
						//Must increase index as otherwise next loop run would remove added classes
        				i++;
					}
				}
				lookupFirstMatchingColumn = false;
			} else {
				BAL.DOM.removeClass(currentTableHeader, "insertLeft");
				BAL.DOM.removeClass(currentTableHeader, "insertRight");
			}
		}
		
		if(tableheader.length > 0) {
			var mostLeftPosition = TABLE.getElementPosition(tableheader[0]).topX;
			var mostRightPosition = TABLE.getElementPosition(tableheader[tableheader.length - 1]).belowX;
			if(mousePosition.x < mostLeftPosition) {
				TABLE.markLeftBorderForInsert(mouseEvtData, tableheader, 0);
			} else if(mousePosition.x > mostRightPosition) {
				TABLE.markRightBorderForInsert(mouseEvtData, tableheader, tableheader.length - 1);
			}
		}
		
		return false;
	},
	
	markLeftBorderForInsert: function(mouseEvtData, tableHeader, elementIndex) {
		var currentTableHeader = tableHeader[elementIndex];
		mouseEvtData.changeElement = currentTableHeader.columnIndex;
		BAL.DOM.addClass(currentTableHeader, "insertLeft");
		BAL.DOM.removeClass(currentTableHeader, "insertRight");
		if (!currentTableHeader.isFirstFlexNode && elementIndex > 0) {
			BAL.DOM.addClass(tableHeader[elementIndex - 1], "insertRight");
		}
		if(currentTableHeader.isFirstFlexNode) {
			if(mouseEvtData.nodePosition == "lastFixNode") {
				mouseEvtData.isFixFlexColumnExchange = !mouseEvtData.isFixFlexColumnExchange;
			}
			mouseEvtData.nodePosition = "firstFlexNode";
		} else {
			mouseEvtData.nodePosition = "commonNode";
		}
	},
	
	markRightBorderForInsert: function(mouseEvtData, tableHeader, elementIndex) {
		var currentTableHeader = tableHeader[elementIndex];
		mouseEvtData.changeElement = currentTableHeader.columnIndex + 1;
		BAL.DOM.addClass(currentTableHeader, "insertRight");
		BAL.DOM.removeClass(currentTableHeader, "insertLeft");
		if (elementIndex + 1 < tableHeader.length) {
			// update also the next cell.
			var nextTableHeader = tableHeader[elementIndex + 1];
			if(!nextTableHeader.isFirstFlexNode) {
				BAL.DOM.addClass(nextTableHeader, "insertLeft");
			}
			BAL.DOM.removeClass(nextTableHeader, "insertRight");
			if(nextTableHeader.isFirstFlexNode) {
				if(mouseEvtData.nodePosition == "firstFlexNode") {
					mouseEvtData.isFixFlexColumnExchange = !mouseEvtData.isFixFlexColumnExchange;
				}
				mouseEvtData.nodePosition = "lastFixNode";
			} else {
				mouseEvtData.nodePosition = "commonNode";
			}
		}
	},
	
	/**
	 * Retrieves the element position relative to a specified ancestor node.
	 * 
	 * @param {Node}
	 *            child the child to determine relative position from
	 * @param {Node}
	 *            ancestor an ancestor of the given child
	 * @return {Object} contains the X and Y coordinate of the child
	 *         relative to the parent
	 */
	relativeElementPosition: function(child, ancestor) {

		var relativeCurrentNodePosition = BAL.getAbsoluteElementPosition(child);
		var parentNodePosition = BAL.getAbsoluteElementPosition(ancestor);
		relativeCurrentNodePosition.x -= parentNodePosition.x;
		relativeCurrentNodePosition.y -= parentNodePosition.y;

		return relativeCurrentNodePosition;
	},

	/**
	 * Returns an JSON Object which contains position informations.
	 * 
	 * @param {Node}
	 *            element an DOM element from which you want to know the
	 *            absolute Position, not the Viewport position.
	 * @return {TABLE.ElementPosition} containing information about the
	 *         coordinates (the four corners), width, and height.
	 */
	getElementPosition: function(element) {

		var width = BAL.getElementWidth(element);
		var height = BAL.getElementHeight(element);

		var absolutePos = BAL.getAbsoluteElementPosition(element);

		return new TABLE.ElementPosition(absolutePos.x, absolutePos.y, width, height);
	},

	/**
	 * Removes the drag & drop insert marker set by {@link TABLE.checkForInsert}
	 * 
	 * @param {TABLE.MouseEventData}
	 *            mouseEvtData data used to set the insert marker
	 */
	removeInsertMarker: function(mouseEvtData) {
		var tableheader = mouseEvtData.headerList;
		for (var i = 0; i < tableheader.length; i++) {
			BAL.DOM.removeClass(tableheader[i], "insertRight");
			BAL.DOM.removeClass(tableheader[i], "insertLeft");
		}

	},

	mouseUp: function(event, mouseEvtData) {

		mouseEvtData.dragObject.parentNode.removeChild(mouseEvtData.dragObject);
		BAL.DOM.triggerEachNode(mouseEvtData.dragObject, "DOMNodeRemovedFromDocument");
		TABLE.removeInsertMarker(mouseEvtData);
		document.onclick = null;
		BAL.removeEventListener(document, "mousemove", mouseEvtData.adjustmentFunction);
		BAL.removeEventListener(document, "mousemove", mouseEvtData.adjustmentInitFunction);
		if ((mouseEvtData.origMouseMove != undefined) && (mouseEvtData.origMouseMove != null)) {
			BAL.addEventListener(document, "mousemove", mouseEvtData.origMouseMove);
		}
		BAL.removeEventListener(document, "mouseup", mouseEvtData.applianceFunction);
		if (mouseEvtData.dragOrSort && mouseEvtData.replace) {
			// Remove adjustment panes and column border div
			if (mouseEvtData.scrollLeftAreaUpperBoundX) {
				if (mouseEvtData.timerID != 0) {
					window.clearInterval(mouseEvtData.timerID);
				}
			}

			delete mouseEvtData.timerID;
			delete mouseEvtData.horizontalScrollBar;
			delete mouseEvtData.scrollStepWidth;
			delete mouseEvtData.scrollLeftAreaLowerBoundX;
			delete mouseEvtData.scrollLeftAreaUpperBoundX;
			delete mouseEvtData.scrollRightAreaLowerBoundX;
			delete mouseEvtData.scrollRightAreaUpperBoundX;

			var firstMovedColumn = mouseEvtData.firstChangedColumn;
			var lastMovedColumn = mouseEvtData.lastChangedColumn;
			var fixFlexMovement = mouseEvtData.isFixFlexColumnExchange;
			if (fixFlexMovement || firstMovedColumn != mouseEvtData.changeElement) {
				dispatchControlCommand({
							controlCommand: "tableReplace",
							controlID: mouseEvtData.ctrlID,
							firstMovedColumn: firstMovedColumn,
							lastMovedColumn: lastMovedColumn,
							insertIndex: mouseEvtData.changeElement,
							fixFlexMovement: fixFlexMovement
						});
			}

		}
		if (!mouseEvtData.dragOrSort && mouseEvtData.sort) {
			return dispatchControlCommand({
						controlCommand: "tableSort",
						controlID: mouseEvtData.ctrlID,
						column: mouseEvtData.firstChangedColumn
					});
		}

		delete mouseEvtData.firstChangedColumn;
		delete mouseEvtData.lastChangedColumn;
		delete mouseEvtData.dragOrSort;
		mouseEvtData.headerList.splice(0, mouseEvtData.headerList.length);
		delete mouseEvtData.headerList;
		delete mouseEvtData.oldclass;
		delete mouseEvtData.dragObject;
		delete mouseEvtData.limits;
		delete mouseEvtData.mousePos;
		delete mouseEvtData.changeElement;
		delete mouseEvtData.dragElement;
		delete mouseEvtData.ctrlID;
		delete mouseEvtData.isFirstMove;

		return false;

	},
	
	getHeaderParentRow: function(currentRow) {
		var parentRowCandidate = BAL.DOM.getPreviousElementSibling(currentRow);
		if(parentRowCandidate != null && TABLE.isHeaderRow(parentRowCandidate)) {
			return parentRowCandidate;
		} else {
			return null;
		}
	},
	
	isHeaderRow: function(headerRowCandidate) {
		var headerRowAttribute = BAL.DOM.getNonStandardAttribute(headerRowCandidate, "data-header-row");
		return headerRowAttribute != null && headerRowAttribute == "true";
	},
	
	getTopLeftHeaderCell: function(ctrlId) {
		var headerAnchor = null;
		if(TABLE.isFrozenTable(ctrlId)) {
			var fixHeader = document.getElementById(ctrlId + "_headerFix");
			var flexHeader = document.getElementById(ctrlId + "_headerFlex");
			if (fixHeader != null) {
				headerAnchor = fixHeader;
			} else {
				headerAnchor = flexHeader;
			}
		} else {
			headerAnchor = document.getElementById(ctrlId);
		}
		var firstHeaderRow = headerAnchor.getElementsByTagName("tr")[0];
		while(! TABLE.isHeaderRow(firstHeaderRow)) {
			firstHeaderRow = BAL.DOM.getNextElementSibling(firstHeaderRow);
		}
		return firstHeaderRow.getElementsByTagName("th")[0];
	},
	
	/**
	 * Creates an array containing all "th" elements on the same level as the
	 * given element.
	 * 
	 * @param {String}
	 * 			  id of the corresponding table control
	 * @param {Node}
	 *            dragElement DOM element to get all "th" siblings from
	 * @param {Integer}
	 * 			  column index of the dragElement
	 * @return {Array} list of all siblings of the given element which are "th"
	 */
	columnHeaderList: function(ctrlId, dragElement, dragElementIndex) {
		var headerList = new Array();
		var currentCell = dragElement;
		var currentRow = currentCell.parentNode;
		
		// Get column offset
		var columnOffset = dragElementIndex;
		var counterNode = BAL.DOM.getPreviousElementSibling(currentCell);
		while (counterNode != null) {
			var colGroupAttribute = BAL.DOM.getAttribute(counterNode, "colspan");
			if(colGroupAttribute == null) {
				columnOffset--;
			} else {
				columnOffset -= parseInt(colGroupAttribute);
			}
			counterNode = BAL.DOM.getPreviousElementSibling(counterNode);
		}
		
		var parentRow = TABLE.getHeaderParentRow(currentRow);
		
		if(parentRow == null) {
			
			// Get complete list of cell objects, if top row
			var currentCellObject = TABLE.getTopLeftHeaderCell(ctrlId);
			var fixHeader = document.getElementById(ctrlId + "_headerFix");
			var flexHeader = document.getElementById(ctrlId + "_headerFlex");
			var topColumnIndex = 0;
			while (currentCellObject != null) {
				currentCellObject.columnIndex = topColumnIndex;
				headerList.push(currentCellObject);					
				var colGroupAttribute = BAL.DOM.getAttribute(currentCellObject, "colspan");
				if(colGroupAttribute == null) {
					topColumnIndex++;
				} else {
					topColumnIndex += parseInt(colGroupAttribute);
				}
				currentCellObject = TABLE.determineNextTableSeperatorNode(currentCellObject, fixHeader, flexHeader);
			}
		} else {
			// Determine minimum and maximum index of current group
			var parentIndex = columnOffset;
			var parentCounterNode = BAL.DOM.getFirstElementChild(parentRow);
			var minGroupIndex = -1;
			var maxGroupIndex = -1;
			while (parentIndex <= dragElementIndex) {
				var colGroupAttribute = BAL.DOM.getAttribute(parentCounterNode, "colspan");
				if(colGroupAttribute == null) {
					parentIndex++;
					
					if(parentIndex > dragElementIndex) {
						minGroupIndex = maxGroupIndex = parentIndex - 1;
						break;
					}
				} else {
					var groupSpan = parseInt(colGroupAttribute);
					parentIndex += groupSpan;
					if(parentIndex > dragElementIndex) {
						minGroupIndex = parentIndex - groupSpan;
						maxGroupIndex = parentIndex - 1;
						break;
					}
				}
				parentCounterNode = BAL.DOM.getNextElementSibling(parentCounterNode);
			}
			
			if(minGroupIndex == maxGroupIndex) {
				// Element is only member of group, try to get parent group of parent
				return TABLE.columnHeaderList(ctrlId, parentCounterNode, dragElementIndex);
			}
			
			// Determine column group nodes
			var groupIndex = columnOffset;
			var currentCellObject = BAL.DOM.getFirstElementChild(currentRow);
			while (groupIndex <= maxGroupIndex) {
				if(groupIndex >= minGroupIndex) {
					currentCellObject.columnIndex = groupIndex;
					headerList.push(currentCellObject);					
				}
				var colGroupAttribute = BAL.DOM.getAttribute(currentCellObject, "colspan");
				if(colGroupAttribute == null) {
					groupIndex++;
				} else {
					groupIndex += parseInt(colGroupAttribute);
				}
				currentCellObject = BAL.DOM.getNextElementSibling(currentCellObject);
			}
		}
		
		return headerList;
	},

	/**
	 * Adopts the X and Y coordinates of the given DOM element that it fits in
	 * the given limits (as good as it can be done).
	 * 
	 * @param {Node}
	 *            element the element which must fit into the given limits
	 * @param {TABLE.ElementPosition}
	 *            limits the limits to ensure that the element fits into
	 */
	colDetection: function(domElement, limits) {
		var domPosition = TABLE.getElementPosition(domElement);

		// test x position out of range
		if (domPosition.topX < limits.topX) {
			BAL.setElementX(domElement, limits.topX);
		} else {
			if (domPosition.belowX > limits.belowX) {
				BAL.setElementX(domElement, limits.belowX - domPosition.width);
			}
		}

		// test y position out of range
		if (domPosition.topY < limits.topY) {
			BAL.setElementY(domElement, limits.topY);
		} else {
			if (domPosition.belowY > limits.belowY) {
				BAL.setElementY(domElement, limits.belowY - domPosition.height);
			}
		}
	},

	/**
	 * Finds the leas ancestor of the given element with the given tagname (may
	 * be the element self or null if there is none)
	 * 
	 * @param {Node}
	 *            elem the element to get ancestor from
	 * @param {String}
	 *            tagName the tag name of the desired ancestor. must not be null
	 * @return {Node} the "least" ancestor of the given element or null if there
	 *         is none.
	 */
	findAncestorWithTagName: function(elem, tagName) {
		var lowerTagName = tagName.toLowerCase();
		while (elem != null && BAL.DOM.getTagName(elem) != lowerTagName) {
			elem = elem.parentNode;
		}
		return elem;
	},

	/**
	 * Creates the element moved during the drag & drop operation
	 * 
	 * @param {TABLE.MouseEventData}
	 *            mouseEvtData
	 */
	createNewDivElement: function(mouseEvtData) {
		// mouseEvtData.limits = this.getElementPosition(this.dragElement.parentNode);
		mouseEvtData.oldclass = mouseEvtData.dragElement.className;
		// document.body.appendChild(this.dragObject);
		// this.dragObject = document.createElement("div");
		// alert(this.dragObject);

		// Give the right CSS class
		BAL.DOM.addClass(mouseEvtData.dragObject, "movingheader");
		BAL.addEventListener(mouseEvtData.dragObject, 'mouseover', function(event) {
			BAL.eventStopPropagation(event);
		}, true);

		// Get the correct Name
		mouseEvtData.dragObject.innerHTML = mouseEvtData.dragElement.innerHTML;

		// FF
		mouseEvtData.dragObject.style.opacity = '.50';

		// IE
		mouseEvtData.dragObject.style.filter = 'alpha(opacity=40)';

		var pos = TABLE.getElementPosition(mouseEvtData.dragElement);

		// alert("Width: " + pos.width + " Height " + pos.height + " topX: " + pos.topX + " topY:" + pos.topY);
		var x = mouseEvtData.mousepos.x - pos.topX;
		var y = mouseEvtData.mousepos.y - pos.topY;

		mouseEvtData.relativeMouse = new BAL.Coordinates(x, y);

		BAL.setElementX(mouseEvtData.dragObject, pos.topX);
		BAL.setElementY(mouseEvtData.dragObject, pos.topY);
		BAL.setElementWidth(mouseEvtData.dragObject, pos.width);
		BAL.setElementHeight(mouseEvtData.dragObject, pos.height);
	},

	/**
	 * Determines whether the table rendered by the control with the given ID is
	 * frozen.
	 * 
	 * @param {String}
	 *            ctrlID
	 * @return {Boolean} whether the table rendered by the control with the
	 *         given ID is frozen.
	 */
	isFrozenTable: function(ctrlID) {
		if (document.getElementById(ctrlID + "_headerFix") != null 
		      || document.getElementById(ctrlID + "_headerFlex") != null) {
			return true;
		} else {
			return false;
		}
	},

	/**
	 * Initial function which is called when trying to move or to sort a column.
	 * 
	 * @param {Object}
	 *            ev the occured event
	 * @param {Node}
	 *            element the element on which the event occured
	 * @param {String}
	 *            ctrlId the id of the control which rendered the table
	 * @param {Number}
	 *            column the number of the column to move. It is not the
	 *            technical column number (e.g. in fixed case if more than one
	 *            table is writter for an application table), but the
	 *            application column number
	 * @param {Boolean}
	 *            replace whether the column can be moved
	 * @param {Boolean}
	 *            sort whether the column is sortable
	 * @return {Boolean}
	 */
	makeDragable: function(initialEvent, element, ctrlId, column, replace, sort) {
		if (BAL.isLeftMouseButton(initialEvent.button)) {

			// Create transport object
			var mouseEvtData = new TABLE.MouseEventData();
			mouseEvtData.dragElement = TABLE.findAncestorWithTagName(element, "th");
			mouseEvtData.replace = replace;
			mouseEvtData.sort = sort;
			mouseEvtData.firstChangedColumn = column;
			mouseEvtData.nodePosition = "commonNode";
			var colSpanAttribute = BAL.DOM.getAttribute(mouseEvtData.dragElement, "colspan");
			if(colSpanAttribute != null) {
				mouseEvtData.lastChangedColumn = column + parseInt(colSpanAttribute) - 1;
			} else {
				mouseEvtData.lastChangedColumn = column;
			}
			var ev = BAL.getEvent(initialEvent);

			mouseEvtData.mousepos = BAL.mouseCoordinates(ev);
			mouseEvtData.adjustmentFunction = function(event) {
				return TABLE.mouseMove(BAL.getEvent(event), mouseEvtData);
			};
			mouseEvtData.adjustmentInitFunction = function(event) {
				return TABLE.mouseMoveInit(BAL.getEvent(event), mouseEvtData);
			};
			if (mouseEvtData.replace) {
				mouseEvtData.origMouseMove = document.onmousemove;
				BAL.removeEventListener(document, "mousemove", mouseEvtData.origMouseMove);
				BAL.addEventListener(document, "mousemove",	mouseEvtData.adjustmentInitFunction);
			}
			mouseEvtData.applianceFunction = function(event) {
				return TABLE.mouseUp(BAL.getEvent(event), mouseEvtData);
			};
			BAL.addEventListener(document, "mouseup", mouseEvtData.applianceFunction);

			mouseEvtData.ctrlID = ctrlId;
			
			// Get a list of cell objects
			mouseEvtData.headerList = TABLE.columnHeaderList(ctrlId, mouseEvtData.dragElement, column);

			if (!TABLE.isFrozenTable(ctrlId)) {
				// Get limits in which the cell is dragable to.
				mouseEvtData.limits = TABLE.getElementPosition(mouseEvtData.dragElement.parentNode);
			} else {
				var fixHeader = document.getElementById(ctrlId + "_headerFix");
				var flexHeader = document.getElementById(ctrlId + "_headerFlex");
				
				var nonNullHeaderPos, otherHeaderPos;
				var flexHeaderPos, fixHeaderPos;
				if (fixHeader != null) {
					fixHeaderPos = nonNullHeaderPos = TABLE.getElementPosition(fixHeader);
					if (flexHeader != null) {
						flexHeaderPos = otherHeaderPos = TABLE.getElementPosition(flexHeader);
					} else {
						flexHeaderPos = otherHeaderPos = new TABLE.ElementPosition(0, 0, 0, 0);
					}
				} else {
					flexHeaderPos = nonNullHeaderPos = TABLE.getElementPosition(flexHeader);
					fixHeaderPos = otherHeaderPos = new TABLE.ElementPosition(0, 0, 0, 0);
				}

				// Get limits in which the cell is dragable to.
				mouseEvtData.limits = nonNullHeaderPos;
				mouseEvtData.limits.belowX += otherHeaderPos.belowX	+ TABLE.getTableSeparatorWidth(ctrlId);

				// Define scrolling left sensitive area
				mouseEvtData.scrollLeftAreaLowerBoundX = flexHeaderPos.topX;
				mouseEvtData.scrollLeftAreaUpperBoundX = mouseEvtData.scrollLeftAreaLowerBoundX
						+ BAL.getVerticalScrollBarWidth();

				// Define scrolling right sensitive area
				mouseEvtData.scrollRightAreaLowerBoundX = flexHeaderPos.belowX;
				mouseEvtData.scrollRightAreaUpperBoundX = mouseEvtData.scrollRightAreaLowerBoundX
						+ BAL.getVerticalScrollBarWidth();
				mouseEvtData.timerID = 0;
				mouseEvtData.scrollStepWidth = 5 /* px */;

				// Retrieve pane for horizontal scrolling
				mouseEvtData.horizontalScrollBar = document.getElementById(ctrlId + "_horizontalScroll");
			}

			mouseEvtData.dragObject = document.createElement("div");
			mouseEvtData.dragObject.id = "tempdiv";
			document.body.appendChild(mouseEvtData.dragObject);

			return true;
		} else {
			return true;
		}
	},

	openFilterDialog: function(filterButton, ctrlID, columnIndex) {
		return services.ajax.execute("dispatchControlCommand", {
					controlCommand: "openTableFilterDialog",
					controlID: ctrlID,
					column: columnIndex,
					filterButtonID: filterButton
				});
	},

	resetElement: function(element) {
		// see Ticket #644:
		element.onmousedown = null;
		element.className = "";
	},

	initColumnWidthAdjustment: function(initialEvent, ctrlID, column, columnID) {

		var ev = BAL.getEvent(initialEvent);
		var colWidthAdj = new TABLE.ColumnWidthAdjustment(ev, ctrlID);

		// Retrieve title data
		var title = document.getElementById(ctrlID + "_title");
		var titleHeight = BAL.getElementHeight(title);

		// Retrieve pane for horizontal scrolling
		colWidthAdj.horizontalScrollBar = document.getElementById(ctrlID	+ "_horizontalScroll").getElementsByTagName("div")[0];

		// Retrieve mouse position
		colWidthAdj.outermostDiv = TABLE.getFrozenTableDiv(ctrlID);
		// Create two independet objects, making double call
		var rawStartMousePos = BAL.relativeMouseCoordinates(ev, colWidthAdj.outermostDiv);
		colWidthAdj.startMousePos = BAL.relativeMouseCoordinates(ev, colWidthAdj.outermostDiv);

		// Retrieve column data
		colWidthAdj.sliceManager = TABLE.tableSliceManagers[ctrlID];
		var columnData = columnID.split("_");
		colWidthAdj.columnID = parseInt(columnData[0]);
		colWidthAdj.tablePart = columnData[1];
		if (colWidthAdj.tablePart == "fixed") {
			var viewportContentID = ctrlID + "_bodyFix" + TABLE.Viewport.CONTAINER_ID_SUFFIX;
			colWidthAdj.viewport = colWidthAdj.sliceManager.getViewport(viewportContentID);
		} else {
			var viewportContentID = ctrlID + "_bodyFlex" + TABLE.Viewport.CONTAINER_ID_SUFFIX;
			colWidthAdj.viewport = colWidthAdj.sliceManager.getViewport(viewportContentID);

			// Adjust horizontal mouse position to real mouse position in flex
			// table
			colWidthAdj.startMousePos.x += colWidthAdj.tableStructure.headerFlex.scrollLeft
					- colWidthAdj.tableStructure.headerWidth
					- TABLE.getTableSeparatorWidth(ctrlID);
		}
		colWidthAdj.headerColumn = column;

		// Determine minimum position (according to minimum width of 12px per
		// column)
		colWidthAdj.minMouseX = colWidthAdj.startMousePos.x
				- colWidthAdj.viewport.getColumnWidth(colWidthAdj.columnID)
				+ TABLE.MINIMUM_COLUMN_WIDTH;

		// Create background pane
		var mainLayout = services.ajax.topWindow.document.getElementsByTagName("div")[0];
		colWidthAdj.backgroundPane = document.createElement("div");
		mainLayout.appendChild(colWidthAdj.backgroundPane);
		BAL.DOM.addClass(colWidthAdj.backgroundPane, "tblColumnAdjustmentPane");

		// Create column border div
		colWidthAdj.columnBorderElement = document.createElement("div");
		colWidthAdj.outermostDiv.appendChild(colWidthAdj.columnBorderElement);
		BAL.DOM.addClass(colWidthAdj.columnBorderElement, "tblColumnBorderLine");
		BAL.setElementHeight(colWidthAdj.columnBorderElement,
				colWidthAdj.sliceManager.getVisibleViewportHeight()	+ colWidthAdj.tableStructure.headerHeight);
		BAL.setElementX(colWidthAdj.columnBorderElement, rawStartMousePos.x);
		BAL.setElementY(colWidthAdj.columnBorderElement, titleHeight);

		// Register mouse move and column width appliance handlers
		colWidthAdj.adjustmentFunction = function(event) {

			// Retrieve mouse position
			var scrollEvent = BAL.getEvent(event);
			var currentMousePos = BAL.relativeMouseCoordinates(scrollEvent, colWidthAdj.outermostDiv);

			if (colWidthAdj.tablePart != "fixed") {
				currentMousePos.x += colWidthAdj.tableStructure.headerFlex.scrollLeft
						- colWidthAdj.tableStructure.headerWidth
						- TABLE.getTableSeparatorWidth(ctrlID);
			}

			return TABLE.handleColumnWidthAdjustment(currentMousePos, colWidthAdj, "adjust");
		};

		colWidthAdj.applianceFunction = function(event) {
			return TABLE.applyColumnWidthAdjustment(event, colWidthAdj);
		};
		BAL.addEventListener(document, "mousemove",	colWidthAdj.adjustmentFunction);
		BAL.addEventListener(document, "mouseup", colWidthAdj.applianceFunction);

		// Enable scrolling logic for column width adjustment in flex table part
		if (colWidthAdj.tablePart != "fixed") {

			// Create scrolling left pane
			var tableOffset = BAL.getAbsoluteElementPosition(colWidthAdj.outermostDiv);
			colWidthAdj.scrollingLeftPane = document.createElement("div");
			colWidthAdj.backgroundPane.appendChild(colWidthAdj.scrollingLeftPane);
			BAL.DOM.addClass(colWidthAdj.scrollingLeftPane,	"tblColumnAdjustmentPane");
			BAL.setElementWidth(colWidthAdj.scrollingLeftPane,
					colWidthAdj.tableStructure.headerWidth + tableOffset.x
							+ TABLE.MINIMUM_COLUMN_WIDTH);
			BAL.setElementX(colWidthAdj.scrollingLeftPane, 0);
			BAL.setElementY(colWidthAdj.scrollingLeftPane, 0);

			// Create scrolling right pane
			var insertPosition = BAL.getElementWidth(colWidthAdj.outermostDiv)
					- BAL.getVerticalScrollBarWidth() + tableOffset.x;
			colWidthAdj.scrollingRightPane = document.createElement("div");
			colWidthAdj.backgroundPane.appendChild(colWidthAdj.scrollingRightPane);
			BAL.DOM.addClass(colWidthAdj.scrollingRightPane, "tblColumnAdjustmentPane");
			BAL.setElementWidth(colWidthAdj.scrollingRightPane, BAL.getVerticalScrollBarWidth());
			BAL.setElementX(colWidthAdj.scrollingRightPane, insertPosition);
			BAL.setElementY(colWidthAdj.scrollingRightPane, 0);

			// Determine raw (screen) minimum and maximum mouse positions (relative to table div)
			colWidthAdj.rawMinimumMousePositionX = BAL.getElementX(colWidthAdj.tableStructure.headerFlex);
			colWidthAdj.rawMaximumMousePositionX = insertPosition - tableOffset.x;

			// Create horizontal scrolling range divs
			// Header div
			colWidthAdj.horizontalHeaderRangeDiv = document.createElement("div");
			colWidthAdj.tableStructure.headerFlex.appendChild(colWidthAdj.horizontalHeaderRangeDiv);
			BAL.setElementHeight(colWidthAdj.horizontalHeaderRangeDiv, 1);
			BAL.setElementWidth(colWidthAdj.horizontalHeaderRangeDiv, TABLE.BROWSER_MAXIMUM_WIDTH);
			BAL.setElementX(colWidthAdj.horizontalHeaderRangeDiv, 0);
			BAL.setElementY(colWidthAdj.horizontalHeaderRangeDiv, 0);
			// Body div
			colWidthAdj.horizontalBodyRangeDiv = document.createElement("div");
			colWidthAdj.tableStructure.bodyFlex.appendChild(colWidthAdj.horizontalBodyRangeDiv);
			BAL.setElementHeight(colWidthAdj.horizontalBodyRangeDiv, 1);
			BAL.setElementWidth(colWidthAdj.horizontalBodyRangeDiv, TABLE.BROWSER_MAXIMUM_WIDTH);
			BAL.setElementX(colWidthAdj.horizontalBodyRangeDiv, 0);
			BAL.setElementY(colWidthAdj.horizontalBodyRangeDiv, 0);
			// ScrollPane div
			colWidthAdj.horizontalScrollPaneRangeDiv = document.createElement("div");
			colWidthAdj.horizontalScrollBar.parentNode.appendChild(colWidthAdj.horizontalScrollPaneRangeDiv);
			BAL.setElementHeight(colWidthAdj.horizontalScrollPaneRangeDiv, 1);
			BAL.setElementWidth(colWidthAdj.horizontalScrollPaneRangeDiv, TABLE.BROWSER_MAXIMUM_WIDTH);
			BAL.setElementX(colWidthAdj.horizontalScrollPaneRangeDiv, 0);
			BAL.setElementY(colWidthAdj.horizontalScrollPaneRangeDiv, 0);

			colWidthAdj.timerID = 0;
			var _scrollLeftFunction = function(event) {

				if (colWidthAdj.timerID == 0) {
					BAL.removeEventListener(document, "mousemove", colWidthAdj.adjustmentFunction);

					var currentMousePos = new Object();

					var scrollLeft = function() {
						currentMousePos.x = colWidthAdj.rawMinimumMousePositionX
								+ colWidthAdj.tableStructure.headerFlex.scrollLeft
								- colWidthAdj.tableStructure.headerWidth
								- TABLE.getTableSeparatorWidth(ctrlID);
						currentMousePos.y = 0;

						return TABLE.handleColumnWidthAdjustment(currentMousePos, colWidthAdj, "scrollLeft");
					};
					colWidthAdj.timerID = window.setInterval(scrollLeft, 10);

					return false;
				}
			};
			var _scrollRightFunction = function(event) {

				if (colWidthAdj.timerID == 0) {
					BAL.removeEventListener(document, "mousemove", colWidthAdj.adjustmentFunction);

					var currentMousePos = new Object();

					var scrollRight = function() {
						currentMousePos.x = colWidthAdj.rawMaximumMousePositionX
								+ colWidthAdj.tableStructure.headerFlex.scrollLeft
								- colWidthAdj.tableStructure.headerWidth
								- TABLE.getTableSeparatorWidth(ctrlID);
						currentMousePos.y = 0;

						return TABLE.handleColumnWidthAdjustment(currentMousePos, colWidthAdj, "scrollRight");
					};
					colWidthAdj.timerID = window.setInterval(scrollRight, 10);

					return false;
				}
			};
			var _deregisterScrollFunction = function(event) {
				window.clearInterval(colWidthAdj.timerID);
				colWidthAdj.timerID = 0;
				BAL.addEventListener(document, "mousemove",	colWidthAdj.adjustmentFunction);

				return false;
			};

			BAL.addEventListener(colWidthAdj.scrollingLeftPane, "mouseover", _scrollLeftFunction);
			BAL.addEventListener(colWidthAdj.scrollingLeftPane, "mouseout", _deregisterScrollFunction);
			BAL.addEventListener(colWidthAdj.scrollingRightPane, "mouseover",_scrollRightFunction);
			BAL.addEventListener(colWidthAdj.scrollingRightPane, "mouseout", _deregisterScrollFunction);
		} else {
			colWidthAdj.rawMaximumMousePositionX = colWidthAdj.startMousePos.x
					+ BAL.getElementWidth(colWidthAdj.outermostDiv) 
					- colWidthAdj.tableStructure.headerWidth 
					- BAL.getVerticalScrollBarWidth();
			if (colWidthAdj.tableStructure.hasFlexPart) {
				colWidthAdj.rawMaximumMousePositionX -= TABLE.MINIMUM_FLEX_SPACE;
			}
		}
		
		BAL.eventStopPropagation(ev);
		return false;
	},

	handleColumnWidthAdjustment: function(currentMousePosition, colWidthAdj, actionType) {

		// Adjust column border line position
		if (actionType == "adjust") {
			var horizontalScreenPosition = 0;
			if (colWidthAdj.tablePart != "fixed") {
				currentMousePosition.x = Math.max(currentMousePosition.x, colWidthAdj.minMouseX);
				horizontalScreenPosition = currentMousePosition.x
						- colWidthAdj.tableStructure.headerFlex.scrollLeft
						+ colWidthAdj.tableStructure.headerWidth
						+ colWidthAdj.tableStructure.tableSeparatorWidth;
			} else {
				currentMousePosition.x = Math.min(currentMousePosition.x, colWidthAdj.rawMaximumMousePositionX);
				horizontalScreenPosition = Math.max(currentMousePosition.x, colWidthAdj.minMouseX);
			}

			BAL.setElementX(colWidthAdj.columnBorderElement, horizontalScreenPosition);
		}

		// Scroll flex table to right or left
		else if (colWidthAdj.tablePart != "fixed") {

			var scrollStepWidth = 5/* px */;

			// Scroll right
			if (actionType == "scrollRight") {
				colWidthAdj.horizontalScrollBar.parentNode.scrollLeft += scrollStepWidth;
			}

			// Scroll left
			else if (currentMousePosition.x > colWidthAdj.minMouseX) {
				colWidthAdj.horizontalScrollBar.parentNode.scrollLeft -= scrollStepWidth;
			}
		}

		return false;
	},

	/**
	 * @param {Object}
	 *            event the event object occured when calling this function
	 * @param {TABLE.ColumnWidthAdjustment}
	 *            colWidthAdj the data holder to provide necessary informations
	 *            to adjust column width
	 * @return {Boolean} always false
	 */
	applyColumnWidthAdjustment: function(event, colWidthAdj) {

		// Remove adjustment panes and column border div
		colWidthAdj.columnBorderElement.parentNode.removeChild(colWidthAdj.columnBorderElement);
		if (colWidthAdj.scrollingLeftPane) {
			if (colWidthAdj.timerID != 0) {
				window.clearInterval(colWidthAdj.timerID);
			}
			colWidthAdj.backgroundPane.removeChild(colWidthAdj.scrollingLeftPane);
			colWidthAdj.backgroundPane.removeChild(colWidthAdj.scrollingRightPane);
		}
		colWidthAdj.backgroundPane.parentNode.removeChild(colWidthAdj.backgroundPane);

		var tableSeparatorWidth = colWidthAdj.tableStructure.tableSeparatorWidth;
		// Retrieve mouse position
		var ev = BAL.getEvent(event);
		var currentMousePosition = BAL.relativeMouseCoordinates(ev, colWidthAdj.outermostDiv);
		if (colWidthAdj.tablePart != "fixed") {
			currentMousePosition.x = Math.min(currentMousePosition.x, colWidthAdj.rawMaximumMousePositionX);
			currentMousePosition.x = Math.max(currentMousePosition.x, colWidthAdj.rawMinimumMousePositionX);
			currentMousePosition.x += colWidthAdj.tableStructure.bodyFlex.scrollLeft
					- colWidthAdj.tableStructure.headerWidth
					- tableSeparatorWidth;
		} else {
			currentMousePosition.x = Math.min(currentMousePosition.x, colWidthAdj.rawMaximumMousePositionX);
		}

		// Determine new column width
		var deltaWidth = Math.max(currentMousePosition.x, colWidthAdj.minMouseX) - colWidthAdj.startMousePos.x;
		var oldColumnWidth = colWidthAdj.viewport.getColumnWidth(colWidthAdj.columnID);
		var newColumnWidth = oldColumnWidth + deltaWidth;

		// Apply new column width, if group header can be fully displayed
		var columnHeaderId;
		var headerColumn;
		var pxDeltaThDiv;
		var columnNumber = colWidthAdj.headerColumn[0];
		var numberHeaderRows = colWidthAdj.headerColumn[1];
		var tableID = colWidthAdj.sliceManager.getTableID();
		if(numberHeaderRows > 0) {
			columnHeaderId = tableID + "_header_" + 0 + "_" + columnNumber;
			headerColumn = document.getElementById(columnHeaderId);
			pxDeltaThDiv = BAL.getEffectiveWidth(headerColumn.parentNode) - BAL.getEffectiveWidth(headerColumn);
		}
		
		TABLE.setHeaderColumnWidth(tableID, numberHeaderRows, columnNumber, newColumnWidth);
		
		if(numberHeaderRows > 0) {
			var headerColumnDivWidth = BAL.getEffectiveWidth(headerColumn);
			var headerColumnThWidth = BAL.getEffectiveWidth(headerColumn.parentNode);
			if(headerColumnDivWidth + pxDeltaThDiv < headerColumnThWidth) {
				newColumnWidth = oldColumnWidth;
				TABLE.setHeaderColumnWidth(tableID, numberHeaderRows, columnNumber, newColumnWidth);
			}
		}
		
		colWidthAdj.sliceManager.updateColumnWidth(
                colWidthAdj.viewport.getContainerID(),
				colWidthAdj.columnID, 
				newColumnWidth,
				/* update server column width */true);

		var availableWidth = TABLE.getAvailableWidth(colWidthAdj.sliceManager.getTableID());

		// Adjust table structure, if fixed column was adjusted
		if (colWidthAdj.tablePart == "fixed") {

			var newFixWidth = colWidthAdj.tableStructure.updateFixWidth();
			var newFlexWidth = colWidthAdj.tableStructure.updateFlexWidth(availableWidth);

			// Adjust table seperator
			BAL.setElementX(colWidthAdj.tableStructure.tableSeperator, newFixWidth);

			// Adjust horizontal scrolling function
			var horizontalScrollOuter = colWidthAdj.horizontalScrollBar.parentNode;
			if(horizontalScrollOuter.horizontalScrollManager != null) {
				horizontalScrollOuter.horizontalScrollManager.setFlexTableWidth(newFlexWidth);
			}
		}

		// If flex column was adjusted, remove previously installed scroll range
		// panes
		else {
			colWidthAdj.tableStructure.headerFlex.removeChild(colWidthAdj.horizontalHeaderRangeDiv);
			colWidthAdj.tableStructure.bodyFlex.removeChild(colWidthAdj.horizontalBodyRangeDiv);
			colWidthAdj.horizontalScrollPaneRangeDiv.parentNode.removeChild(colWidthAdj.horizontalScrollPaneRangeDiv);
		}
		
		if (colWidthAdj.tableStructure.hasFlexPart) {
    		// Adjust scroll bar width
    		var flexTableWidth = BAL.getElementWidth(colWidthAdj.tableStructure.headerFlex.getElementsByTagName("table")[0]);
    		var fixWidth = colWidthAdj.tableStructure.headerWidth;
    		var newScrollBarWidth = (flexTableWidth * availableWidth) / (availableWidth - fixWidth - tableSeparatorWidth);
    		BAL.setElementWidth(colWidthAdj.horizontalScrollBar, newScrollBarWidth);
    
    		// Adjust scroll position
    		var scrollPosition = colWidthAdj.tableStructure.headerFlex.scrollLeft 
    		      * (availableWidth / (availableWidth - fixWidth - tableSeparatorWidth));
    		colWidthAdj.horizontalScrollBar.parentNode.scrollLeft = scrollPosition;
		}

		// Remove event listeners
		BAL.removeEventListener(document, "mousemove", colWidthAdj.adjustmentFunction);
		BAL.removeEventListener(document, "mouseup", colWidthAdj.applianceFunction);

		// Delete all properties
		delete colWidthAdj;

		return false;
	},
	
	setHeaderColumnWidth: function(tableID, numberHeaderRows, columnNumber, newColumnWidth) {
		var columnDefinitionId = tableID + "_basicColumnDefinition_" + columnNumber;
		var columnDefinition = document.getElementById(columnDefinitionId);
		TABLE.setColumnWidth(columnDefinition, newColumnWidth);
		for (var i = 0; i < numberHeaderRows; i++) {
			var columnHeaderId = tableID + "_header_" + i + "_" + columnNumber;
			var div = document.getElementById(columnHeaderId);
			TABLE.setColumnWidth(div, newColumnWidth);
			// Quirks, to force IE6 and IE7 to set the new column width directly at th-tag,
			// due to these IE versions do not perform column adjustment according to the
			// width of child elements
			TABLE.setColumnCellWidth(div.parentNode, newColumnWidth);
		}
	},

	checkMinimumSize: function(sliceManager, numberHeaderRows, ctrlID) {
		if (numberHeaderRows < 1) {
			return false;
		}
		var viewportContentID = ctrlID + "_bodyFix" + TABLE.Viewport.CONTAINER_ID_SUFFIX;
		var fixViewport = sliceManager.getViewport(viewportContentID);
		var numberFixedColumns;
		if (fixViewport != null) {
			numberFixedColumns = fixViewport.getColumnCount();
		} else {
			numberFixedColumns = 0;
		}
		
		var viewportContentID = ctrlID + "_bodyFlex" + TABLE.Viewport.CONTAINER_ID_SUFFIX;
		var flexViewport = sliceManager.getViewport(viewportContentID);
		var numberFlexColumns;
		if (flexViewport != null) {
			numberFlexColumns = flexViewport.getColumnCount();
		} else {
			numberFlexColumns = 0;
		}
		
		var columnCount = numberFixedColumns + numberFlexColumns;
		
		var adaptedColumnWidth = false;
		for (var columnNumber = 0; columnNumber < columnCount; columnNumber++) {
			var	columnHeaderId = sliceManager.getTableID() + "_header_" + 0 + "_" + columnNumber;
			var headerColumnDiv = document.getElementById(columnHeaderId);
			var headerColumnDivWidth = BAL.getEffectiveWidth(headerColumnDiv);
			var headerColumnThWidth = BAL.getEffectiveWidth(headerColumnDiv.parentNode);
			if (headerColumnDivWidth < headerColumnThWidth) {
				adaptedColumnWidth = true;
				var tableID = sliceManager.getTableID();
				TABLE.setHeaderColumnWidth(tableID, numberHeaderRows, columnNumber, headerColumnThWidth)
				if (columnNumber < numberFixedColumns) {
					sliceManager.updateColumnWidth(
		                fixViewport.getContainerID(),
						columnNumber, 
						headerColumnThWidth,
						/* update server column width */true);
				} else {
					sliceManager.updateColumnWidth(
		                flexViewport.getContainerID(),
						columnNumber - numberFixedColumns, 
						headerColumnThWidth,
						/* update server column width */true);
					
				}
			}
		}
		return adaptedColumnWidth;
	},
	
	/**
	 * Determines the right sibling of the given node. This method searches in
	 * both the fix header and the flex header (if not null): If the current
	 * node is the last one in the fixHeader, the first of the flexHeader is
	 * returned.
	 * 
	 * @param {Node}
	 *            currentNode must not be null
	 * @param {Element}
	 *            fixHeader may be null
	 * @param {Element}
	 *            flexHeader may be null
	 * @return {Node} my be null if the given node does not have a right
	 *         sibling.
	 */
	determineNextTableSeperatorNode: function(currentNode, fixHeader, flexHeader) {

		// Determine next node at current dom level
		var nextNode = currentNode;
		while (true) {
			nextNode = nextNode.nextSibling;
			if (nextNode != null) {
				if (BAL.DOM.getTagName(nextNode) == "th") {
					break;
				}
			} else {
				break;
			}
		}

		/*
		 * If there was no next node at current dom level, determine if the
		 * current node belongs to fix header table. If there is no fix header
		 * the element is already in the flex part and loop above must find
		 * sibling; if no flex header exists no node can be found there
		 */
		if (nextNode == null && flexHeader != null && fixHeader != null) {
			var isFixNode = false;
			var tempNode = currentNode;
			while (tempNode != null) {
				tempNode = tempNode.parentNode;
				if (tempNode == fixHeader) {
					isFixNode = true;
					break;
				} else if (tempNode == flexHeader) {
					break;
				}
			}

			if (isFixNode) {
				// Determine row in fix table
				var fixTableRow = currentNode.parentNode;
				var rowNumber = 0;
				while(fixTableRow != null) {
					fixTableRow = BAL.DOM.getPreviousElementSibling(fixTableRow);
					if(fixTableRow != null) {
						rowNumber++;
					}
				}
				
				var flexHeaderTable = flexHeader.getElementsByTagName("table")[0];
				var flexTableHeader = flexHeaderTable.getElementsByTagName("thead")[0];
				var flexTableRows = BAL.DOM.getChildElements(flexTableHeader);
				var flexTableRow = flexTableRows[rowNumber];
				var flexTableColumns = BAL.DOM.getChildElements(flexTableRow);
				nextNode = flexTableColumns[0];
				nextNode.isFirstFlexNode = true;
			}
		}

		return nextNode;
	},

    /**
	 * Determines the left sibling of the given node. This method searches in
	 * both the fix header and the flex header (if not null): If the current
	 * node is the first one in the flexHeader, the last one of the fixHeader is
	 * returned.
	 * 
	 * @param {Node}
	 *            currentNode must not be null
	 * @param {Element}
	 *            fixHeader may be null
	 * @param {Element}
	 *            flexHeader may be null
	 * @return {Node} my be null if the given node does not have a left sibling.
	 */
	determinePreviousTableSeperatorNode: function(currentNode, fixHeader, flexHeader) {


		// Determine previous node at current dom level
		var previousNode = currentNode;
		while (true) {
			previousNode = previousNode.previousSibling;
			if (previousNode != null) {
				if (BAL.DOM.getTagName(previousNode) == "th") {
					break;
				}
			} else {
				break;
			}
		}

		// If there was no previous node at current dom level, determine if the
		// current node belongs to flex header table
		if (previousNode == null && flexHeader != null && fixHeader != null) {
			var isFlexNode = false;
			var tempNode = currentNode;
			while (tempNode != null) {
				tempNode = tempNode.parentNode;
				if (tempNode == flexHeader) {
					isFlexNode = true;
					break;
				} else if (tempNode == fixHeader) {
					break;
				}
			}

			if (isFlexNode) {
				// Determine row in flex table
				var flexTableRow = currentNode.parentNode;
				var rowNumber = 0;
				while(flexTableRow != null) {
					flexTableRow = BAL.DOM.getPreviousElementSibling(flexTableRow);
					if(flexTableRow != null) {
						rowNumber++;
					}
				}
				
				var fixHeaderTable = fixHeader.getElementsByTagName("table")[0];
				var fixTableHeader = fixHeaderTable.getElementsByTagName("thead")[0];
				var fixTableRows = BAL.DOM.getChildElements(fixTableHeader);
				var fixTableRow = fixTableRows[rowNumber];
				var fixTableColumns = BAL.DOM.getChildElements(fixTableRow);

				for (var i = fixTableColumns.length - 1; i > -1; i--) {
					if (BAL.DOM.getTagName(fixTableColumns[i]) == "th") {
						previousNode = fixTableColumns[i];
						break;
					}
				}
				currentNode.isFirstFlexNode = true;
			}
		}

		return previousNode;
	},

	/**
	 * Initializes the adjustment of the number of fixed columns, i.e. installs
	 * event handlers which allows the user to select the number of fixed
	 * columns via D&D of the separator.
	 * 
	 * @param {String}
	 *            ctrlID the id of the control which rendered the table
	 * @param {Node}
	 *            fixHeader the fixed header element. May be null in case no
	 *            columns are frozen.
	 * @param {Node}
	 *            flexHeader the flexible header of the table. May be null in
	 *            case all columns are frozen.
	 * @param {Number}
	 *            fixColumnCount the current number of fixed columnns
	 * @param {Number}
	 *            adjustmentLineWidth the width of the transparent line moved by
	 *            the user
	 * @param {Number}
	 *            adjustmentLineHeight the height of the transparent line moved
	 *            by the user
	 * @return {Boolean} always false
	 */
	initFrozenColumnAmountAdjustment: function(ctrlID, fixHeader, flexHeader,
	       fixColumnCount, adjustmentLineWidth, adjustmentLineHeight) {

		var outermostDiv = TABLE.getFrozenTableDiv(ctrlID);

		var currentNode, nextNode, previousNode;
		var currentNodePosition, nextNodePosition, previousNodePosition;
		if (flexHeader != null) {
			// Determine current horizontal position data
			var flexHeaderTable = flexHeader.getElementsByTagName("table")[0];
			var flexTableHeader = flexHeaderTable.getElementsByTagName("thead")[0];
			var flexTableRows = BAL.DOM.getChildElements(flexTableHeader);
			var flexTableRow = flexTableRows[flexTableRows.length - 1];
			var flexTableColumns = BAL.DOM.getChildElements(flexTableRow);
			currentNode = flexTableColumns[0];
			currentNodePosition = TABLE.relativeElementPosition(currentNode, outermostDiv).x;

			nextNode = TABLE.determineNextTableSeperatorNode(currentNode, fixHeader, flexHeader);
			if(nextNode != null) {
				nextNodePosition = TABLE.relativeElementPosition(nextNode, outermostDiv).x;
			} else {
				nextNodePosition = currentNodePosition + BAL.getElementWidth(currentNode);
			}
    		
    		if (fixHeader != null) {
    			previousNode = TABLE.determinePreviousTableSeperatorNode(currentNode, fixHeader, flexHeader);
    			previousNodePosition = TABLE.relativeElementPosition(previousNode, outermostDiv).x;
    		} else {
    			// no fixed columns, separator is at the front of the table
    			previousNode = null;
    			previousNodePosition = null;
    		}
			
		} else {
            var fixHeaderTable = fixHeader.getElementsByTagName("table")[0];
            var fixTableHeader = fixHeaderTable.getElementsByTagName("thead")[0];
            var fixTableRows =  BAL.DOM.getChildElements(fixTableHeader);
			var fixTableRow = fixTableRows[fixTableRows.length - 1];
            var fixTableColumns = BAL.DOM.getChildElements(fixTableRow);
            previousNode = fixTableColumns[fixTableColumns.length - 1];
            previousNodePosition = TABLE.relativeElementPosition(previousNode, outermostDiv).x;

			// currently no flex part, all columns fixed, separator is at the end of the table
			currentNode = nextNode = null;
			currentNodePosition = nextNodePosition = previousNodePosition + BAL.getElementWidth(previousNode);
		}


		var currentFixedColumns = fixColumnCount;

		// Create background pane
		var mainLayout = services.ajax.topWindow.document.getElementsByTagName("div")[0];
		var backgroundPane = document.createElement("div");
		mainLayout.appendChild(backgroundPane);
		BAL.DOM.addClass(backgroundPane, "tblColumnAdjustmentPane");

		// Create table seperator adjustment div
		var tableSeperator = document.createElement("div");
		outermostDiv.appendChild(tableSeperator);
		BAL.DOM.addClass(tableSeperator, "tblSeperatorLine");
		BAL.setElementWidth(tableSeperator, adjustmentLineWidth);
		BAL.setElementHeight(tableSeperator, adjustmentLineHeight);
		var horizontalPosition = fixHeader == null ? 0 : BAL.getElementWidth(fixHeader);
		var verticalPosition;
		if(fixHeader != null) {
			verticalPosition = BAL.getElementY(fixHeader);
		} else {
			verticalPosition = BAL.getElementY(flexHeader);
		}
		BAL.setElementX(tableSeperator, horizontalPosition);
		BAL.setElementY(tableSeperator, verticalPosition);

		// Create function for adjustment of table seperator position
		var adjustTableSeperatorPosition = function(event) {

			// Retrieve mouse position
			var ev = BAL.getEvent(event);
			var currentMousePosition = BAL.relativeMouseCoordinates(ev, outermostDiv).x;

			// Determine horizontal position change of the table seperator
			if (currentMousePosition > currentNodePosition) {
				
				var maxTableSeparatorPosition = BAL.getElementX(TABLE.getVerticalScrollbarOuter(ctrlID)) - TABLE.MINIMUM_FLEX_SPACE;
				
				// In case the current mouse position is near to the next node position
				if (nextNode != currentNode 
				        && (nextNodePosition - currentMousePosition) < (currentMousePosition - currentNodePosition)
						&& (nextNodePosition <= maxTableSeparatorPosition)) {

					previousNode = currentNode;
					previousNodePosition = currentNodePosition;
					currentNode = nextNode;
					currentNodePosition = nextNodePosition;
					if (currentNode != null) {
    					nextNode = TABLE.determineNextTableSeperatorNode(currentNode, fixHeader, flexHeader);
        				if(nextNode != null) {
        					nextNodePosition = TABLE.relativeElementPosition(nextNode, outermostDiv).x;
        				} else {
        					nextNodePosition = currentNodePosition + BAL.getElementWidth(currentNode);
        				}
					}
						

					BAL.setElementX(tableSeperator, currentNodePosition - TABLE.DEFAULT_TABLE_SEPARATOR_WIDTH);

					if (previousNode != null && previousNode.colSpan) {
						currentFixedColumns += previousNode.colSpan;
					} else {
						currentFixedColumns++;
					}
				}
			} else {

				// In case the current mouse position is near to the previous
				// node position
				if (previousNode != null 
				        && (currentMousePosition - previousNodePosition) < (currentNodePosition - currentMousePosition)
						&& previousNodePosition >= 0) {

					nextNode = currentNode;
					nextNodePosition = currentNodePosition;
					currentNode = previousNode;
					currentNodePosition = previousNodePosition;
					previousNode = TABLE.determinePreviousTableSeperatorNode(currentNode, fixHeader, flexHeader);
					if (previousNode != null) {
    					previousNodePosition = TABLE.relativeElementPosition(previousNode, outermostDiv).x;
					} else {
						previousNodePosition = null;
					}

					BAL.setElementX(tableSeperator, currentNodePosition - TABLE.DEFAULT_TABLE_SEPARATOR_WIDTH);

					if (nextNode != null && nextNode.colSpan) {
						currentFixedColumns -= nextNode.colSpan;
					} else {
						currentFixedColumns--;
					}
				}
			}
			BAL.removeDocumentSelection();
			return false;
		};

		// Create function for appliance of table seperator position
		var applyTableSeperatorPosition = function(event) {

			// Cleanup layout
			backgroundPane.parentNode.removeChild(backgroundPane);
			outermostDiv.removeChild(tableSeperator);
			BAL.removeDocumentSelection();

			// Remove event listeners
			BAL.removeEventListener(document, "mousemove", adjustTableSeperatorPosition);
			BAL.removeEventListener(document, "mouseup", applyTableSeperatorPosition);

			// Notify server
			if (currentFixedColumns != fixColumnCount) {
				services.ajax.execute("dispatchControlCommand", {
							controlCommand: "updateFixedColumnAmount",
							controlID: ctrlID,
							fixedColumnAmount: currentFixedColumns
						}, /* useWaitPane */true);
			}
			
			return false;
		};

		// Register table seperator adjustment and appliance functions
		BAL.addEventListener(document, "mousemove", adjustTableSeperatorPosition);
		BAL.addEventListener(document, "mouseup", applyTableSeperatorPosition);

		return false;
	},

	initFrozing: function(ctrlID, displayVersion, firstPageRow, lastPageRow, firstSliceRow,
			lastSliceRow, numberHeaderRows, fixedColumnCountAdjustable, tableSeparatorTooltip,
			clientDisplayData) {

		// Create table slice manager registry on demand
		if (TABLE.tableSliceManagers == null) {
			TABLE.tableSliceManagers = new Object();
		}

		var sliceManager = TABLE.tableSliceManagers[ctrlID];
		var tableBodyData = null;

		// Initial frozing
		if (sliceManager == null) {
			tableBodyData = TABLE.frozenTableAdjustment(ctrlID,	displayVersion,
														fixedColumnCountAdjustable, true,
														tableSeparatorTooltip);
			if (!tableBodyData.forcedFixedColumnCountAdjustment) {
				sliceManager = new TABLE.SliceManager(ctrlID, displayVersion,
						tableBodyData.bodyHeight, firstPageRow, lastPageRow,
						tableBodyData.viewports, firstSliceRow, lastSliceRow);

				TABLE.tableSliceManagers[ctrlID] = sliceManager;
				
				services.layout.addLayoutFunction(function(){
					TABLE.adjustScrollBars(ctrlID);
					var adapted = TABLE.checkMinimumSize(sliceManager, numberHeaderRows, ctrlID);
					if (adapted) {
						tableBodyData = TABLE.frozenTableAdjustment(ctrlID,	displayVersion,
														fixedColumnCountAdjustable, false,
														tableSeparatorTooltip);
					}
				});
				
				services.layout.addPostLayoutFunction(function(){
					TABLE.initPreviousScrollPosition(sliceManager, clientDisplayData);
					TABLE.activateScrollPositionPropagationInViewports(tableBodyData.viewports);
					TABLE.scrollToRequestedPosition(ctrlID, clientDisplayData);
					
				});
			}
		}
		// Resizing
		else {
			tableBodyData = TABLE.frozenTableAdjustment(ctrlID, TABLE.UNDEFINED_DISPLAY_VERSION,
														fixedColumnCountAdjustable, false,
														tableSeparatorTooltip);
			if (!tableBodyData.forcedFixedColumnCountAdjustment) {
				services.layout.addLayoutFunction(function() {
					// Adjusting visible viewport height, and updates all slices (especially
					// slice insert positions and sizes)
					sliceManager.setVisibleViewportHeight(tableBodyData.bodyHeight);
				});
			}
		}
	},
	
	initPreviousScrollPosition: function(sliceManager, clientDisplayData) {
		var bodyScrollPositions = TABLE.ScrollPositionProvider.getServerScrollPositions(sliceManager, clientDisplayData);
		var tableID = sliceManager.getTableID();
		
		// Set initial scroll positions to table (including synchronization between table parts)
		if(TABLE.hasVerticalScollbarOuter(tableID)) {
			var verticalScrollOuter = TABLE.getVerticalScrollbarOuter(tableID);
			if(verticalScrollOuter.scrolledVertical != undefined &&
					verticalScrollOuter.scrolledVertical != null) {
				verticalScrollOuter.verticalScrollManager.initScrollPosition(bodyScrollPositions.vertical);
			}
		}
		
		if(TABLE.hasHorizontalScollbarOuter(tableID)) {
			var horizontalScrollOuter = TABLE.getHorizontalScrollbarOuter(tableID);
			if(horizontalScrollOuter.horizontalScrollManager != undefined &&
					horizontalScrollOuter.horizontalScrollManager != null) {
				horizontalScrollOuter.horizontalScrollManager.initScrollPosition(bodyScrollPositions.horizontal);
			}
		}
	},
	
	scrollToRequestedPosition: function(ctrlID, clientDisplayData) {
		var sliceManager = TABLE.tableSliceManagers[ctrlID];
		
		if(sliceManager != null) {
			var scrollbarScrollPositions = TABLE.ScrollPositionProvider.getComputedScrollPositions(sliceManager, clientDisplayData);
			if(TABLE.hasHorizontalScollbarOuter(ctrlID)) {
				var horizontalScrollOuter = TABLE.getHorizontalScrollbarOuter(ctrlID);
				if(horizontalScrollOuter.horizontalScrollManager != undefined &&
				   horizontalScrollOuter.horizontalScrollManager != null) {
					BAL.setScrollLeftElement(horizontalScrollOuter, scrollbarScrollPositions.horizontal);
					horizontalScrollOuter.horizontalScrollManager.scrollbarScrolled.call();
				}
			}
			
			if(TABLE.hasVerticalScollbarOuter(ctrlID)) {
				var verticalScrollOuter = TABLE.getVerticalScrollbarOuter(ctrlID);
				if(verticalScrollOuter.scrolledVertical != undefined &&
				   verticalScrollOuter.scrolledVertical != null) {
					BAL.setScrollTopElement(verticalScrollOuter, scrollbarScrollPositions.vertical);
					verticalScrollOuter.scrolledVertical.call();
				}
			}
			
			// Because the requested scroll position may not differ from the previous scroll
			// position, the check for additional slice loading must be triggered manually.
			sliceManager.positionUpdate(scrollbarScrollPositions.vertical);
		} else {
			services.log.error("Could not scroll to requested position, " +
					"due to slice manager for control with id '" + ctrlID + "' was not found!");
		}
	},
	
	activateScrollPositionPropagationInViewports: function(viewports) {
		viewports[viewports.length - 1].setUpdateServerState(true);
	},

	insertTableSlice: function(ctrlID, viewportID, sliceID, sliceRowCount) {
		var sliceManager = TABLE.tableSliceManagers[ctrlID];
		if ((sliceManager != undefined) && (sliceManager != null)) {
			sliceManager.insertSlice(viewportID, sliceID, sliceRowCount);
		} else {
			throw new Error("Client is not in sync with server. Reload page!");
		}
	},

	updateViewportHeight: function(ctrlID, sliceIDPart, pageRowCount) {
		var sliceManager = TABLE.tableSliceManagers[ctrlID];
		if ((sliceManager != undefined) && (sliceManager != null)) {
			sliceManager.setPageRange(0, pageRowCount - 1);
			
			// Update fix body container
			var fixViewportID = ctrlID + "_bodyFix" + TABLE.Viewport.CONTAINER_ID_SUFFIX;
			var fixSliceID = fixViewportID + "_slice-" + sliceIDPart;
			sliceManager.updateViewportHeight(fixViewportID, fixSliceID, pageRowCount);

			// Update flex body container
			var flexViewportID = ctrlID + "_bodyFlex" + TABLE.Viewport.CONTAINER_ID_SUFFIX;
			var flexSliceID = flexViewportID + "_slice-" + sliceIDPart;
			sliceManager.updateViewportHeight(flexViewportID, flexSliceID, pageRowCount);
		} else {
			throw new Error("Client is not in sync with server. Reload page!");
		}
	},

	frozenTableAdjustment: function(ctrlID, displayVersion, fixedColumnCountAdjustable,	isInitialLayout, tableSeparatorTooltip) {
		var horizontalFlexPartInsertPosition;
		var outermostDiv = TABLE.getFrozenTableDiv(ctrlID);
		var outermostDivWidth = services.layout.parentLayoutInformation.effectiveWidth;
		var outermostDivHeight = services.layout.parentLayoutInformation.effectiveHeight;
		var effectiveOutermostDivWidth = outermostDivWidth;
		var effectiveOutermostDivHeight = outermostDivHeight;
		
//		********************* Setter ********************
//		*************************************************
		var outermostDivLayoutFunction = services.layout.createPositionAndSizeApplianceFunction(outermostDiv, 0, 0, effectiveOutermostDivWidth, effectiveOutermostDivHeight);
		services.layout.addLayoutFunction(outermostDivLayoutFunction);
//		***************************************************
//		***************************************************

		var availableWidth = Math.max(0, effectiveOutermostDivWidth - BAL.getVerticalScrollBarWidth());
		var availableHeight = Math.max(0, effectiveOutermostDivHeight - BAL.getHorizontalScrollBarHeight());
		var tableSeparatorWidth = TABLE.DEFAULT_TABLE_SEPARATOR_WIDTH; /* px */;
		
//		********************* Setter ********************
//		*************************************************
		TABLE.setTableSeparatorWidth(ctrlID, tableSeparatorWidth);
//		***************************************************
//		***************************************************

		// Title row

		var title = document.getElementById(ctrlID + "_title");
		var titleHeight;
		if (title != null) {
			titleHeight = Math.min(parseInt(TL.getTLAttribute(title, "title-height")), availableHeight);
			
//		********************* Setter ********************
//		*************************************************
			var titleLayoutFunction = services.layout.createPositionAndSizeApplianceFunction(title, 0, 0, availableWidth, titleHeight);
			services.layout.addLayoutFunction(titleLayoutFunction);
//		***************************************************
//		***************************************************
			
		} else {
			titleHeight = 0;
		}

		// Fix Header part of the table

		var tableBodyData = new Object();
		var fixHeader = document.getElementById(ctrlID + "_headerFix");
		var hasFixPart = fixHeader != null;
		var fixBodyID = TABLE.getFixBodyID(ctrlID);
		var fixBody = document.getElementById(fixBodyID);
		if (hasFixPart && fixBody == null) {
			throw new Error("Table for Control '" + ctrlID+ "' has fixed header part but no fixed body part!");
		}
		if (!hasFixPart && fixBody != null) {
			throw new Error("Table for Control '" + ctrlID + "' has fixed body part but no fixed header part!");
		}
		var fixWidth;
		var headerHeight;
		var borderWidth;
		var fixColumnWidths = new Array();
		if (hasFixPart) {
			borderWidth = parseInt(TL.getTLAttribute(fixHeader, "border-width")); 
			var fixHeaderTable = fixHeader.getElementsByTagName("table")[0];
			var fixTableHeader = fixHeaderTable.getElementsByTagName("thead")[0];
			var fixTableRows = BAL.DOM.getChildElements(fixTableHeader);
			var fixTableRow = fixTableRows[fixTableRows.length - 1];
			var fixTableColumns = BAL.DOM.getChildElements(fixTableRow);
			
			// Check if fix table does fit screen width
			var fixTableWidth = 0;
			for (var j = 0; j < fixTableColumns.length; j++) {
				var currentColumnWidth = parseInt(TL.getTLAttribute(fixTableColumns[j], "column-width"));
				if (currentColumnWidth > 0) { // TODO: WHY no check at flex table part?
					fixColumnWidths.push(currentColumnWidth);
					fixTableWidth += currentColumnWidth;
				}
			}
			fixTableWidth += fixTableColumns.length * borderWidth + borderWidth;

			var maximumWidth = availableWidth - TABLE.MINIMUM_FLEX_SPACE;
			if (fixTableWidth <= maximumWidth) {
				fixWidth = fixTableWidth;
			}

			// Force adjustment of fixed columns to fit maximum fix table width
			else if (fixedColumnCountAdjustable) {
				// Determine fixed column count
				var newFixedColumns = fixTableColumns.length;
				for (; newFixedColumns > 0; newFixedColumns--) {
					if (fixTableWidth <= maximumWidth) {
						break;
					}
					fixTableWidth -= fixColumnWidths[newFixedColumns - 1] + borderWidth;
				}
				
				var reloadTimerID = BAL.DOM.getNonStandardAttribute(outermostDiv, "reloadTimerID");
				if(reloadTimerID != undefined && reloadTimerID != null) {
					BAL.getTopLevelWindow().clearTimeout(reloadTimerID);
				}
				
				// Notify server to reload page
				var repaintFunction = function() {
					BAL.DOM.removeNonStandardAttribute(outermostDiv, "reloadTimerID");
					services.ajax.execute("dispatchControlCommand", {
						controlCommand: "updateFixedColumnAmount",
						controlID: ctrlID,
						fixedColumnAmount: newFixedColumns
					}, /* useWaitPane */true);
				};
				
				reloadTimerID = BAL.getTopLevelWindow().setTimeout(repaintFunction, 100);
//		********************* Setter ********************
//		*************************************************
				services.layout.addLayoutFunction(function() {
					BAL.DOM.setNonStandardAttribute(outermostDiv, "reloadTimerID", reloadTimerID);
				});
//		***************************************************
//		***************************************************

				// Stop frozing function, due to page reload request
				tableBodyData.forcedFixedColumnCountAdjustment = true;

				return tableBodyData;
			} else {
				fixWidth = maximumWidth;
			}
			
			headerHeight = 0;
			for (var j = 0; j < fixTableRows.length; j++) {
				headerHeight += parseInt(TL.getTLAttribute(fixTableRows[j], "header-row-height"));
			}
//		********************* Setter ********************
//		*************************************************
			var fixHeaderLayoutFunction = services.layout.createPositionAndSizeApplianceFunction(fixHeader, 0, titleHeight, fixWidth, headerHeight);
			services.layout.addLayoutFunction(fixHeaderLayoutFunction);
			services.layout.addLayoutFunction(function() {
				fixHeader.style.overflow = "hidden";
			});
			
//		***************************************************
//		***************************************************

		} else {
			fixWidth = 0;
			headerHeight = 0;
		}

		// Flex Header part of the table

		var flexHeader = document.getElementById(ctrlID + "_headerFlex");
		var hasFlexPart = flexHeader != null;
		var flexHeaderTable = null;
		var flexWidth = 0;
		var flexColumnWidths = new Array();
		var horizontalScrollManagerInstance = new TABLE.HorizontalScrollManager(availableWidth, flexWidth);
		var verticalScrollManagerInstance = new TABLE.VerticalScrollManager(ctrlID);
		if (hasFlexPart) {
			flexHeaderTable = flexHeader.getElementsByTagName("table")[0];
			var flexTableHeader = flexHeaderTable.getElementsByTagName("thead")[0];
			var flexTableRows = BAL.DOM.getChildElements(flexTableHeader);
			var flexTableRow = flexTableRows[flexTableRows.length - 1];
			var flexTableColumns = BAL.DOM.getChildElements(flexTableRow);
			if(!hasFixPart) {
				borderWidth = parseInt(TL.getTLAttribute(flexHeader, "border-width"));
			} 
			if (headerHeight == 0) {
				for (var j = 0; j < flexTableRows.length; j++) {
					headerHeight += parseInt(TL.getTLAttribute(flexTableRows[j], "header-row-height"));
				}
			}
			horizontalFlexPartInsertPosition = fixWidth + tableSeparatorWidth;
			if(!hasFixPart) {
				horizontalFlexPartInsertPosition++; // Add table separator overlap from fixed part
			}
			
			flexWidth = Math.max(availableWidth - fixWidth - tableSeparatorWidth, 0);
//			********************* Setter ********************
//			*************************************************
			var flexHeaderLayoutFunction = services.layout.createPositionAndSizeApplianceFunction(flexHeader, horizontalFlexPartInsertPosition, titleHeight, flexWidth, headerHeight);
			services.layout.addLayoutFunction(flexHeaderLayoutFunction);
			services.layout.addLayoutFunction(function() {
				flexHeader.style.overflow = "hidden";
			});
//			***************************************************
//			***************************************************

			for (var j = 0; j < flexTableColumns.length; j++) {
				flexColumnWidths.push(parseInt(TL.getTLAttribute(flexTableColumns[j], "column-width")));
			}
			
			horizontalScrollManagerInstance.setFlexTableWidth(flexWidth);
			if(isInitialLayout) {
				horizontalScrollManagerInstance.setFlexHeader(flexHeader);
			}
		}
		// Footer part of the table

		var footer = document.getElementById(ctrlID + "_footer");
		var footerHeight;
		if (footer != null) {
			footerHeight = Math.min(parseInt(TL.getTLAttribute(footer, "footer-height")), availableHeight);
//			********************* Setter ********************
//			*************************************************
			var footerLayoutFunction = services.layout.createPositionAndSizeApplianceFunction(footer, 0, availableHeight - footerHeight, availableWidth, footerHeight);
			services.layout.addLayoutFunction(footerLayoutFunction);
//			***************************************************
//			***************************************************
		} else {
			footerHeight = 0;
		}

		// Fix Body part of the table

		var viewports = new Array();
		var verticalFixAndFlexBodyPosition = headerHeight + titleHeight;
		
		// Ensure, that viewport has technically at least height of 1 px
		var fixAndFlexBodyHeight = Math.max(1, availableHeight - footerHeight - headerHeight - titleHeight);
		if (hasFixPart) {
//			********************* Setter ********************
//			*************************************************
			var fixBodyLayoutFunction = services.layout.createPositionAndSizeApplianceFunction(fixBody, 0, verticalFixAndFlexBodyPosition, fixWidth, fixAndFlexBodyHeight);
			services.layout.addLayoutFunction(fixBodyLayoutFunction);
			services.layout.addLayoutFunction(function() {
				fixBody.style.overflow = "hidden";
			});
//			***************************************************
//			***************************************************
			var fixBodyViewportID = TABLE.getViewportIDForTablePartWithID(fixBodyID);
			var fixBodyViewport = null;
			if (isInitialLayout) {
				if(displayVersion == TABLE.UNDEFINED_DISPLAY_VERSION) {
					throw new Error("Initial table state at client side cannot be created with undefined display version!");
				}
				fixBodyViewport = new TABLE.Viewport(ctrlID, displayVersion, fixBodyViewportID,
						fixWidth, 
						fixColumnWidths, 
						0);
				verticalScrollManagerInstance.setFixBody(fixBody);
				verticalScrollManagerInstance.setFixBodyViewport(fixBodyViewport);
			} else {
				var presentSliceManager = TABLE.tableSliceManagers[ctrlID];
				fixBodyViewport = presentSliceManager.getViewport(fixBodyViewportID);
				if (fixBodyViewport != null) {
					fixBodyViewport.setColumnWidths(fixColumnWidths);
					// TODO: why no adjustment of width as in flex part
				} else {
					throw new Error("Client is not in sync with server. Table is not rendered initial, but there is no sliceManager. Reload page!");
				}
			}
			viewports.push(fixBodyViewport);
		}

		// Flex Body part of the table

		var flexBodyID = TABLE.getFlexBodyID(ctrlID);
		var flexBody = document.getElementById(flexBodyID);
		if (hasFlexPart && flexBody == null) {
			throw new Error("Table for Control '" + ctrlID + "' has flex header part but no flex body part!");
		}
		if (!hasFlexPart && flexBody != null) {
			throw new Error("Table for Control '" + ctrlID + "' has flex body part but no flex header part!");
		}

		var flexTableWidth = 0;
		if (hasFlexPart) {
			for (var j = 0; j < flexColumnWidths.length; j++) {
				flexTableWidth += flexColumnWidths[j];
			}
			flexTableWidth += flexColumnWidths.length * borderWidth + borderWidth;
//			********************* Setter ********************
//			*************************************************
			var flexBodyLayoutFunction = services.layout.createPositionAndSizeApplianceFunction(flexBody, horizontalFlexPartInsertPosition, verticalFixAndFlexBodyPosition, flexWidth, fixAndFlexBodyHeight);
			services.layout.addLayoutFunction(flexBodyLayoutFunction);
			services.layout.addLayoutFunction(function() {
				flexBody.style.overflow = "hidden";
			});
//			***************************************************
//			***************************************************
			var flexBodyViewportID = TABLE.getViewportIDForTablePartWithID(flexBodyID);
			var flexBodyViewport = null;
			if (isInitialLayout) {
				if(displayVersion == TABLE.UNDEFINED_DISPLAY_VERSION) {
					throw new Error("Initial table state at client side cannot be created with undefined display version!");
				}
				flexBodyViewport = new TABLE.Viewport(ctrlID, displayVersion, flexBodyViewportID,
						flexTableWidth, 
						flexColumnWidths,
						fixColumnWidths.length);
				horizontalScrollManagerInstance.setFlexBody(flexBody);
				horizontalScrollManagerInstance.setFlexBodyViewport(flexBodyViewport);
				verticalScrollManagerInstance.setFlexBody(flexBody);
				verticalScrollManagerInstance.setFlexBodyViewport(flexBodyViewport);
			} else {
				var presentSliceManager = TABLE.tableSliceManagers[ctrlID];
				flexBodyViewport = presentSliceManager.getViewport(flexBodyViewportID);
				if (flexBodyViewport == null) {
					throw new Error("Client is not in sync with server. Table is not rendered initial, but there is no sliceManager. Reload page!");
				}

				// Adjust viewport width
				flexBodyViewport.initWidth(flexTableWidth);
				
				// Adjust column widths
				flexBodyViewport.setColumnWidths(flexColumnWidths);
			}
			viewports.push(flexBodyViewport);
		}
		
		// Horizontal scroll bar

		var horizontalScrollOuter = document.getElementById(ctrlID + "_horizontalScroll");
		var horizontalScrollInner;
		if (horizontalScrollOuter != null) {
			horizontalScrollInner = horizontalScrollOuter.getElementsByTagName("div")[0];
		}
		
		var horizontalScrollInnerWidth = 0;
		if (hasFlexPart) {
			// needed: innerScrollbarDivSize/outerScrollbarDivSize == neededFlexSize/availableFlexSize
			horizontalScrollInnerWidth = (flexTableWidth * availableWidth) / flexWidth;
		}
		
		if (horizontalScrollOuter == null) {
//			********************* Setter ********************
//			*************************************************
			services.layout.addLayoutFunction(function() {
				
				horizontalScrollOuter = document.createElement("div");
				horizontalScrollOuter.id = ctrlID + "_horizontalScroll";
				horizontalScrollOuter.style.position = "absolute";
				horizontalScrollOuter.style.overflowX = "scroll";
				horizontalScrollOuter.style.overflowY = "hidden";
				
				horizontalScrollInner = document.createElement("div");
				horizontalScrollInner.style.visibility = "hidden";
				
				if(hasFlexPart && isInitialLayout) {
					horizontalScrollOuter.horizontalScrollManager = horizontalScrollManagerInstance;
					horizontalScrollManagerInstance.setScrollbar(horizontalScrollOuter);
				}
				
				BAL.setEffectiveWidth(horizontalScrollInner, horizontalScrollInnerWidth);
				BAL.setEffectiveHeight(horizontalScrollInner, Math.min(BAL.getHorizontalScrollBarHeight(), effectiveOutermostDivHeight));
				BAL.setEffectiveWidth(horizontalScrollOuter, availableWidth);
				BAL.setEffectiveHeight(horizontalScrollOuter, Math.min(BAL.getHorizontalScrollBarHeight(), effectiveOutermostDivHeight));
				BAL.setElementY(horizontalScrollOuter, availableHeight);
				
				outermostDiv.appendChild(horizontalScrollOuter);
				horizontalScrollOuter.appendChild(horizontalScrollInner);
			});
//			***************************************************
//			***************************************************
		} else {
//			********************* Setter ********************
//			*************************************************
			services.layout.addLayoutFunction(function() {
				BAL.setEffectiveWidth(horizontalScrollInner, horizontalScrollInnerWidth);
				BAL.setEffectiveHeight(horizontalScrollInner, Math.min(BAL.getHorizontalScrollBarHeight(), effectiveOutermostDivHeight));
				BAL.setEffectiveWidth(horizontalScrollOuter, availableWidth);
				BAL.setEffectiveHeight(horizontalScrollOuter, Math.min(BAL.getHorizontalScrollBarHeight(), effectiveOutermostDivHeight));
				BAL.setElementY(horizontalScrollOuter, availableHeight);
			});
//			***************************************************
//			***************************************************
		}
		
		if (hasFlexPart && !isInitialLayout) {
//			********************* Setter ********************
//			*************************************************
			// Adjust scrolling bar dimensions and scrolling position
			services.layout.addLayoutFunction(function() {
				horizontalScrollOuter.horizontalScrollManager.setAvailableWidth(availableWidth);
				horizontalScrollOuter.horizontalScrollManager.setFlexTableWidth(flexWidth);
				horizontalScrollOuter.horizontalScrollManager.scrollbarScrolled();
			});
//			***************************************************
//			***************************************************
		}
		

		// Vertical scroll bar

		var constantSummand = headerHeight + footerHeight + titleHeight;
		var verticalScrollOuter = document.getElementById(ctrlID + "_verticalScroll");
		if (verticalScrollOuter == null) {
//		********************* Setter ********************
//		*************************************************
			services.layout.addLayoutFunction(function() {
				
				verticalScrollOuter = document.createElement("div");
				verticalScrollOuter.id = ctrlID + "_verticalScroll";
				verticalScrollOuter.style.position = "absolute";
				verticalScrollOuter.style.overflowY = "scroll";
				verticalScrollOuter.style.overflowX = "hidden";
				if((hasFixPart || hasFlexPart) && isInitialLayout) {
					verticalScrollManagerInstance.setScrollbar(verticalScrollOuter);
					verticalScrollOuter.scrolledVertical = verticalScrollManagerInstance.scrollbarScrolled;
					verticalScrollOuter.verticalScrollManager = verticalScrollManagerInstance;
				}
				var mouseWheel = function(event) {
					var scrollingDelta = BAL.getEventMouseScrollDelta(event);
					verticalScrollOuter.scrollTop += scrollingDelta;
					verticalScrollManagerInstance.scrollbarScrolled();
				};
				
				if (hasFixPart) {
					BAL.addMouseScrollListener(fixBody, mouseWheel);
				}
				if (hasFlexPart) {
					BAL.addMouseScrollListener(flexBody, mouseWheel);
				}
				var verticalScrollInner = document.createElement("div");
				verticalScrollInner.style.visibility = "hidden";
				BAL.setEffectiveWidth(verticalScrollInner, Math.min(BAL.getVerticalScrollBarWidth(), effectiveOutermostDivWidth));
				
				
				// store informations about height of inner scroll for adjusting
				// when adding new rows to the table
				verticalScrollInner.constantSummand = constantSummand;
				if (hasFixPart) {
					verticalScrollInner.bodyTable = fixBody.getElementsByTagName("table")[0];
				} else {
					verticalScrollInner.bodyTable = flexBody.getElementsByTagName("table")[0];
				}
				BAL.setEffectiveHeight(verticalScrollOuter, availableHeight);
				BAL.setEffectiveWidth(verticalScrollOuter, Math.min(BAL.getVerticalScrollBarWidth(), effectiveOutermostDivWidth));
				BAL.setElementX(verticalScrollOuter, availableWidth);
				
				outermostDiv.appendChild(verticalScrollOuter);
				verticalScrollOuter.appendChild(verticalScrollInner);
			});
//		***************************************************
//		***************************************************
		} else {
//		********************* Setter ********************
//		*************************************************
			services.layout.addLayoutFunction(function() {
				
				BAL.setEffectiveHeight(verticalScrollOuter, availableHeight);
				BAL.setEffectiveWidth(verticalScrollOuter, Math.min(BAL.getVerticalScrollBarWidth(), effectiveOutermostDivWidth));
				BAL.setElementX(verticalScrollOuter, availableWidth);
			});
//		***************************************************
//		***************************************************
		}

		// Create table separator

		var tableSeparator = document.getElementById(ctrlID + "_tableSeperator");
		var tableSeparatorHeight = Math.max(availableHeight - titleHeight - footerHeight, 0);
		if (tableSeparator == null) {
//				********************* Setter ********************
//				*************************************************
			services.layout.addLayoutFunction(function() {
				
				tableSeparator = document.createElement("div");
				if (tableSeparatorTooltip != null) {
					BAL.DOM.setNonStandardAttribute(tableSeparator, "data-tooltip", tableSeparatorTooltip);
				}
				tableSeparator.id = ctrlID + "_tableSeperator";
				tableSeparator.style.position = "absolute";
				BAL.DOM.addClass(tableSeparator, "tblSeperator");
				if(fixedColumnCountAdjustable) {
					var TableSeperatorAdjustment = function(tableSeparatorHeight) {
						
						var self = this;
						this._tableSeparatorHeight = tableSeparatorHeight;
						
						this.initAdjustment = function() {
							
							if (hasFlexPart) {
								// Reset horizontal scrolling
								BAL.setScrollLeftElement(horizontalScrollOuter, 0);
							}
							
							// Delay frozen column amount adjustment, to ensure that the
							// adjustment function retrieves correct DOM values (e.g. positions)
							// after reset of the horizontal scrolling. Thereby the delay length
							// is not important, just the control flow has to be interrupted.
							var delayedFunction = function() {
								// Init frozen column adjustment
								return TABLE.initFrozenColumnAmountAdjustment(ctrlID,
										fixHeader, 
										flexHeader,
										fixColumnWidths.length,
										tableSeparatorWidth,
										self._tableSeparatorHeight);
							};
							
							BAL.getTopLevelWindow().setTimeout(delayedFunction, 10);
							return false;
						};
						
						this.setTableSeparatorHeight = function(tableSeperatorHeight) {
							this._tableSeparatorHeight = tableSeperatorHeight;
						};
					};
					
					tableSeparator.adjustmentFunction = new TableSeperatorAdjustment(tableSeparatorHeight);
					BAL.addEventListener(tableSeparator, "mousedown", tableSeparator.adjustmentFunction.initAdjustment);
				} else {
					tableSeparator.style.cursor = "default";
				}
				
				// Increase width of table seperator by 2px to build overlap with
				// table borders
				BAL.setEffectiveWidth(tableSeparator, Math.min(tableSeparatorWidth + 2, availableWidth));
				BAL.setEffectiveHeight(tableSeparator, tableSeparatorHeight);
				BAL.setElementX(tableSeparator, Math.max(0, fixWidth - 1));
				BAL.setElementY(tableSeparator, titleHeight);
				
				outermostDiv.appendChild(tableSeparator);
			});
//				***************************************************
//				***************************************************
		} else {
//				********************* Setter ********************
//				*************************************************
			services.layout.addLayoutFunction(function() {
				if(fixedColumnCountAdjustable) {
					tableSeparator.adjustmentFunction.setTableSeparatorHeight(tableSeparatorHeight);
				}
				
				// Increase width of table seperator by 2px to build overlap with
				// table borders
				BAL.setEffectiveWidth(tableSeparator, Math.min(tableSeparatorWidth + 2, availableWidth));
				BAL.setEffectiveHeight(tableSeparator, tableSeparatorHeight);
				BAL.setElementX(tableSeparator, Math.max(0, fixWidth - 1));
				BAL.setElementY(tableSeparator, titleHeight);
			});
//				***************************************************
//				***************************************************
		}

		// Update current scrolling positions in viewport containers
		if(!isInitialLayout) {
			if (hasFixPart) {
				fixBodyViewport.setVerticalScrollingPosition(BAL.getScrollTopElement(verticalScrollOuter));
			}
			if (hasFlexPart) {
				flexBodyViewport.setHorizontalScrollingPosition(BAL.getScrollLeftElement(horizontalScrollOuter));
				flexBodyViewport.setVerticalScrollingPosition(BAL.getScrollTopElement(verticalScrollOuter));
			}
		}

		// Deregister table slice manager, when the table becomes removed
		if (isInitialLayout) {
//			********************* Setter ********************
//			*************************************************
			services.layout.addLayoutFunction(function() {
				
				var deregisterSliceManager = function() {
					BAL.removeEventListener(outermostDiv, 'DOMNodeRemovedFromDocument', deregisterSliceManager);
					delete TABLE.tableSliceManagers[ctrlID];
				};
				BAL.addEventListener(outermostDiv, 'DOMNodeRemovedFromDocument', deregisterSliceManager);
			});
//				***************************************************
//				***************************************************
		}

		if (hasFlexPart || hasFixPart) {
			tableBodyData.bodyHeight = fixAndFlexBodyHeight;
		} else {
			tableBodyData.bodyHeight = 1;
		}
		tableBodyData.viewports = viewports;
		tableBodyData.forcedFixedColumnCountAdjustment = false;
		
		return tableBodyData;
	},
	
	getEffectiveElementSize: function(element, elementWidth, elementHeight) {
		var horizontalPaddingAndBorderSizes = BAL.getHorizontalPaddingAndBorderSizes(element);
		var verticalPaddingAndBorderSizes = BAL.getVerticalPaddingAndBorderSizes(element);
		var effectiveWidth = calculateEffectiveSize(elementWidth, horizontalPaddingAndBorderSizes);
		var effectiveHeight = calculateEffectiveSize(elementHeight, verticalPaddingAndBorderSizes);
		
		return {effectiveWidth: effectiveWidth, effectiveHeight: effectiveHeight};
	},
	
	getAvailableWidth: function(ctrlID) {
		var outermostDiv = TABLE.getFrozenTableDiv(ctrlID);
		return Math.max(0, BAL.getElementWidth(outermostDiv) - BAL.getVerticalScrollBarWidth());
	},
	
	getFixBodyID: function(ctrlID) {
		return ctrlID + "_bodyFix";
	},
	
	getFlexBodyID: function(ctrlID) {
		return ctrlID + "_bodyFlex";
	},
	
	getViewportIDForTablePartWithID: function(tablePartID) {
		return tablePartID + TABLE.Viewport.CONTAINER_ID_SUFFIX;
	},
	
	getViewportFromSliceManager: function(ctrlID, tablePartID) {
		var sliceManager = TABLE.tableSliceManagers[ctrlID];
		var viewportID = TABLE.getViewportIDForTablePartWithID(tablePartID);
		return sliceManager.getViewport(viewportID);
	},

	adjustScrollBars: function(ctrlID) {
		if(TABLE.hasVerticalScollbarOuter(ctrlID)) {
			var verticalScrollOuter = TABLE.getVerticalScrollbarOuter(ctrlID);
			var verticalScrollInner = verticalScrollOuter.children[0];
			var bodyTable = verticalScrollInner.bodyTable;
			if (bodyTable == null) {
				// no table so nothing to adjust
				return;
			};
			var sliceManager = TABLE.tableSliceManagers[ctrlID];
			var constantSummand = verticalScrollInner.constantSummand;
			var verticalScrollInnerHeight = constantSummand + sliceManager.getViewportContentHeight();
			BAL.setEffectiveHeight(verticalScrollInner, verticalScrollInnerHeight);
		}
	},
	
	getVerticalScrollbarOuter: function(ctrlID) {
		return document.getElementById(ctrlID + "_verticalScroll");
	},
	
	hasVerticalScollbarOuter: function(ctrlID) {
		return TABLE.getVerticalScrollbarOuter(ctrlID) != null;
	},

	getHorizontalScrollbarOuter: function(ctrlID) {
		return document.getElementById(ctrlID + "_horizontalScroll");
	},
	
	hasHorizontalScollbarOuter: function(ctrlID) {
		return TABLE.getHorizontalScrollbarOuter(ctrlID) != null;
	},
	
	getTableSeparatorWidth: function(ctrlID) {
		return TABLE.getFrozenTableDiv(ctrlID).tableSeparatorWidth;
	},

	setTableSeparatorWidth: function(ctrlID, width) {
		TABLE.getFrozenTableDiv(ctrlID).tableSeparatorWidth = width;
	},

	getFrozenTableDiv: function(ctrlID) {
		return document.getElementById(ctrlID + "_table");
	}
};

TABLE.VerticalScrollManager = function(tableControlID) {
	
	var self = this;
	var _timerID = null;
	var _ctrlID = tableControlID;
	var _scrollbar = null;
	var _fixBody = null;
	var _fixBodyViewport = null;
	var _flexBody = null;
	var _flexBodyViewport = null;
	
	this.initScrollPosition = function(initialBodyScrollPosition) {
		if (_scrollbar != null) {
			BAL.setScrollTopElement(_scrollbar, initialBodyScrollPosition);
		}
		if (_fixBodyViewport != null) {
			BAL.setScrollTopElement(_fixBody, initialBodyScrollPosition);
			_fixBodyViewport.setVerticalScrollingPosition(initialBodyScrollPosition);
		}
		if (_flexBodyViewport != null) {
			BAL.setScrollTopElement(_flexBody, initialBodyScrollPosition);
			_flexBodyViewport.setVerticalScrollingPosition(initialBodyScrollPosition);
		}
	};
	
	this.scrollbarScrolled = function() {
		if(self.scrollbarAnimationId != null) {
			BAL.cancelAnimationFrame(self.scrollbarAnimationId);
		}
		self.scrollbarAnimationId = BAL.requestAnimationFrame(function() {
			var currentScrollPosition = BAL.getScrollTopElement(_scrollbar);
			if(hasScrollPositionChanged(currentScrollPosition)) {
				if (_fixBodyViewport != null) {
					BAL.setScrollTopElement(_fixBody, currentScrollPosition);
					_fixBodyViewport.setVerticalScrollingPosition(currentScrollPosition);
				}
				if (_flexBodyViewport != null) {
					BAL.setScrollTopElement(_flexBody, currentScrollPosition);
					_flexBodyViewport.setVerticalScrollingPosition(currentScrollPosition);
				}
				performSliceManagerUpdate(currentScrollPosition);
				self.scrollbarAnimationId = null;
			}
		});
	};
	
	this.fixBodyScrolled = function() {
		if(self.fixBodyAnimationId != null) {
			BAL.cancelAnimationFrame(self.fixBodyAnimationId);
		}
		self.fixBodyAnimationId = BAL.requestAnimationFrame(function() {
			tableScrolled(_fixBody, _fixBodyViewport, _flexBody, _flexBodyViewport);
			self.fixBodyAnimationId = null;
		});
	};
	
	function tableScrolled(tableTriggerPart, tableTriggerPartViewport, tableAppliancePart, tableAppliancePartViewport) {
		var currentScrollPosition = BAL.getScrollTopElement(tableTriggerPart);
		if(hasScrollPositionChanged(currentScrollPosition)) {
			BAL.setScrollTopElement(_scrollbar, currentScrollPosition);
			if(tableAppliancePartViewport != null) {
				BAL.setScrollTopElement(tableAppliancePart, currentScrollPosition);
				tableTriggerPartViewport.setVerticalScrollingPosition(currentScrollPosition);
				tableAppliancePartViewport.setVerticalScrollingPosition(currentScrollPosition);
			}
			performSliceManagerUpdate(currentScrollPosition);
		}
	}
	
	this.flexBodyScrolled = function() {
		if(self.flexBodyAnimationId != null) {
			BAL.cancelAnimationFrame(self.flexBodyAnimationId);
		}
		self.flexBodyAnimationId = BAL.requestAnimationFrame(function() {
			tableScrolled(_flexBody, _flexBodyViewport, _fixBody, _fixBodyViewport);
			self.flexBodyAnimationId = null;
		});
	};
	
	function hasScrollPositionChanged(currentScrollPosition) {
		var referenceViewport = getReferenceViewport();
		return referenceViewport != null && 
		currentScrollPosition != referenceViewport.getVerticalScrollingPosition();
	}
	
	function getReferenceViewport() {
		return _fixBodyViewport != null ? _fixBodyViewport : _flexBodyViewport;
	}
	
	function performSliceManagerUpdate(currentScrollPosition) {
		var sliceManager = TABLE.tableSliceManagers[_ctrlID];
		
		// Informs slice manager about position update
		if (_timerID != null) {
			BAL.getTopLevelWindow().clearTimeout(_timerID);
		}
		_timerID = BAL.getTopLevelWindow().setTimeout(function() {
			sliceManager.positionUpdate(currentScrollPosition);
		}, 100);
	}
	
	this.setScrollbar = function(scrollbar) {
		removeScrollListener(_scrollbar, this.scrollbarScrolled);
		_scrollbar = scrollbar;
		addScrollListener(_scrollbar, this.scrollbarScrolled);
	};
	
	function removeScrollListener(element, listenerFunction) {
		if(element != null) {
			BAL.removeEventListener(element, "scroll", listenerFunction);
		}
	}
	
	function addScrollListener(element, listenerFunction) {
		if(element != null) {
			BAL.addEventListener(element, "scroll", listenerFunction);
		}
	}
	
	this.setFixBody = function(fixBody) {
		removeScrollListener(_fixBody, this.fixBodyScrolled);
		_fixBody = fixBody;
		addScrollListener(_fixBody, this.fixBodyScrolled);
	};
	
	this.setFixBodyViewport = function(fixBodyViewport) {
		_fixBodyViewport = fixBodyViewport;
	};
	
	this.setFlexBody = function(flexBody) {
		removeScrollListener(_flexBody, this.flexBodyScrolled);
		_flexBody = flexBody;
		addScrollListener(_flexBody, this.flexBodyScrolled);
	};
	
	this.setFlexBodyViewport = function(flexBodyViewport) {
		_flexBodyViewport = flexBodyViewport;
	};
};

TABLE.HorizontalScrollManager = function(availableWidth, flexWidth) {

	var self = this;
	
	this._availableWidth = availableWidth;
	this._flexWidth = flexWidth;
	this._scrollbar = null;
	this._flexHeader = null;
	this._flexBody = null;
	this._flexBodyViewport = null;
	
	this.initScrollPosition = function(initialBodyScrollPosition) {
		if(self._scrollbar != null && self._flexHeader != null && self._flexBody != null) {
			var scrollbarScrollPosition = Math.round(initialBodyScrollPosition * (self._availableWidth / self._flexWidth));
			BAL.setScrollLeftElement(self._scrollbar, scrollbarScrollPosition);
			BAL.setScrollLeftElement(self._flexHeader, initialBodyScrollPosition);
			BAL.setScrollLeftElement(self._flexBody, initialBodyScrollPosition);
			self._flexBodyViewport.setHorizontalScrollingPosition(initialBodyScrollPosition);
		}
	};

	this.scrollbarScrolled = function() {
		if(self.scrollbarAnimationId != null) {
			BAL.cancelAnimationFrame(self.scrollbarAnimationId);
		}
		self.scrollbarAnimationId = BAL.requestAnimationFrame(function() {
			var currentHorizontalScrollPosition = BAL.getScrollLeftElement(self._scrollbar);
			var tableScrollPosition = Math.round(currentHorizontalScrollPosition / (self._availableWidth / self._flexWidth));
			if(hasScrollPositionChanged(tableScrollPosition)) {
				// needed: availableFlexScroll/outerScrollbarDivScroll == availableFlexSize/outerScrollbarDivSize
				BAL.setScrollLeftElement(self._flexHeader, tableScrollPosition);
				BAL.setScrollLeftElement(self._flexBody, tableScrollPosition);
				self._flexBodyViewport.setHorizontalScrollingPosition(tableScrollPosition);
				self.scrollbarAnimationId = null;
			}
		});
	};
	
	this.headerScrolled = function() {
		if(self.headerAnimationId != null) {
			BAL.cancelAnimationFrame(self.headerAnimationId);
		}
		self.headerAnimationId = BAL.requestAnimationFrame(function() {
			tableScrolled(self._flexHeader, self._flexBody);
			self.headerAnimationId = null;
		});
	};
	
	function tableScrolled(scrollTriggerTablePart, scrollApplianceTablePart) {
		var currentHorizontalScrollPosition = BAL.getScrollLeftElement(scrollTriggerTablePart);
		var scrollbarScrollPosition = Math.round(currentHorizontalScrollPosition * (self._availableWidth / self._flexWidth));
		if(hasScrollPositionChanged(currentHorizontalScrollPosition)) {
			// needed: availableFlexScroll/outerScrollbarDivScroll == availableFlexSize/outerScrollbarDivSize
			BAL.setScrollLeftElement(self._scrollbar, scrollbarScrollPosition);
			BAL.setScrollLeftElement(scrollApplianceTablePart, currentHorizontalScrollPosition);
			self._flexBodyViewport.setHorizontalScrollingPosition(currentHorizontalScrollPosition);
		}
	}
	
	function hasScrollPositionChanged(currentScrollPosition) {
		return self._flexBodyViewport != null && currentScrollPosition != self._flexBodyViewport.getHorizontalScrollingPosition();
	}
	
	this.bodyScrolled = function() {
		if(self.bodyAnimationId != null) {
			BAL.cancelAnimationFrame(self.bodyAnimationId);
		}
		self.bodyAnimationId = BAL.requestAnimationFrame(function() {
			tableScrolled(self._flexBody, self._flexHeader);
			self.bodyAnimationId = null;
		});
	};
	
	this.setFlexTableWidth = function(flexWidth) {
		this._flexWidth = flexWidth;
	};

	this.setAvailableWidth = function(availableWidth) {
		this._availableWidth = availableWidth;
	};
	
	this.setScrollbar = function(scrollbar) {
		removeScrollListener(this._scrollbar, this.scrollbarScrolled);
		this._scrollbar = scrollbar;
		addScrollListener(this._scrollbar, this.scrollbarScrolled);
	};
	
	function removeScrollListener(element, listenerFunction) {
		if(element != null) {
			BAL.removeEventListener(element, "scroll", listenerFunction);
		}
	}
	
	function addScrollListener(element, listenerFunction) {
		if(element != null) {
			BAL.addEventListener(element, "scroll", listenerFunction);
		}
	}
	
	this.setFlexHeader = function(flexHeader) {
		removeScrollListener(this._flexHeader, this.headerScrolled);
		this._flexHeader = flexHeader;
		addScrollListener(this._flexHeader, this.headerScrolled);
	};
	
	this.setFlexBody = function(flexBody) {
		removeScrollListener(this._flexBody, this.bodyScrolled);
		this._flexBody = flexBody;
		addScrollListener(this._flexBody, this.bodyScrolled);
	};
	
	this.setFlexBodyViewport = function(flexBodyViewport) {
		this._flexBodyViewport = flexBodyViewport;
	};
};

/**
 * A container class for slice informations
 */
TABLE.Slice = function(sliceID, insertPosition, insertStrategy, firstRow,
	lastRow, rowHeight) {

	/*
	 * Initialization of member variables
	 */
	this._sliceID = sliceID;
	this._insertPosition = insertPosition;
	this._insertStrategy = insertStrategy;
	this._firstRow = firstRow;
	this._lastRow = lastRow;
	
	this._displayed = false;
	this._rowHeight = rowHeight;
};

/**
 * The slice id (the div/@id attribute of the slice element).
 */
TABLE.Slice.prototype.getID = function() {
	return this._sliceID;
};

/**
 * Vertical position in pixels fo the slice.
 */
TABLE.Slice.prototype.getInsertPosition = function() {
	return this._insertPosition;
};

TABLE.Slice.prototype.noIntersection = function(start, stop) {
	return this.getUpperBound() >= stop || this.getLowerBound() <= start;
};

/**
 * Retrieves the slice bound with higher pixel offset value
 * 
 */
TABLE.Slice.prototype.getLowerBound = function() {
	if (this.getInsertStrategy() == TABLE.BELOW_STRATEGY) {
		return this.getInsertPosition() + this.getHeight();
	} else {
		return this.getInsertPosition();
	}
};

/**
 * Retrieves the slice bound with lower pixel offset value
 */
TABLE.Slice.prototype.getUpperBound = function() {
	if (this.getInsertStrategy() == TABLE.BELOW_STRATEGY) {
		return this.getInsertPosition();
	} else {
		return this.getInsertPosition() - this.getHeight();
	}
};

/**
 * Where to insert a requested slice relative to the {_insertPosition}.
 * 
 * <p>
 * Possible values are {TABLE.ABOVE_STRATEGY} and {TABLE.BELOW_STRATEGY}.
 * </p>
 */
TABLE.Slice.prototype.getInsertStrategy = function() {
	return this._insertStrategy;
};

/**
 * DOM element of the slice.
 */
TABLE.Slice.prototype.getDOMElement = function() {
	return document.getElementById(this._sliceID);
};

/**
 * Getter for the slice height
 */
TABLE.Slice.prototype.getHeight = function() {
	return this._rowHeight * this.getRowCount();
};

/**
 * Sever-side row number of the first row in this slice
 */
TABLE.Slice.prototype.getFirstRow = function() {
	return this._firstRow;
};

TABLE.Slice.prototype.setFirstRow = function(firstRow) {
	this._firstRow = firstRow;
};

/**
 * Sever-side row number of the last row in this slice
 */
TABLE.Slice.prototype.getLastRow = function() {
	return this._lastRow;
};

TABLE.Slice.prototype.setLastRow = function(lastRow) {
	this._lastRow = lastRow;
};

/**
 * Numer of rows in this slice.
 */
TABLE.Slice.prototype.getRowCount = function() {
	return this._lastRow - this._firstRow + 1;
};

TABLE.Slice.prototype.setWidth = function(viewportWidth) {
	BAL.setEffectiveWidth(this.getDOMElement(), viewportWidth);
};

TABLE.Slice.prototype.remove = function() {
	var sliceElement = this.getDOMElement();
	sliceElement.parentNode.removeChild(sliceElement);
};

/**
 * A container structure for viewport informations.
 * 
 * <p>
 * Ein Viewport ist ein scorllbarer Inhaltsbereich in der Tablelle. 
 * I.d.R. gibt es davon zwei, einen im fixierten Tabellenteil der nur vertikal 
 * gescrollt werden kann und einen im flexiblen Teil, der sowohl horizontal als 
 * auch vertikal gescrollt werden kann.
 * </p>
 * 
 * <p>
 * Ein Viewport-Element enth???lt eine zusammenh???ngende Menge von Slices. Ein Slice 
 * ist repr???sentiert durch Strukturen {TABLE.Slice} im {TABLE.SliceManager}. 
 * </p>
 * 
 * @param containerID {String} 
 *			  div/@id of element holding the slice divs.
 * @param columnWidths {Array}
 *            the widths of the columns in the view port
 * @param columnIDOffset {Number}
 *            the number of columns to add to compute the servcer
 *            side id of a column, e.g. if the Viewport is a flex one then the
 *            number of fixed columns must be added to get the "logical" column
 *            number.
 */
TABLE.Viewport = function(tableID, tableDisplayVersion, containerID, viewportWidth, columnWidths, columnIDOffset) {

	/*
	 * Initialization of member variables
	 */
	this._tableID = tableID;
	this._tableDisplayVersion = tableDisplayVersion;
	this._containerID = containerID;
	this.initWidth(viewportWidth);
	this._horizontalScrollingPosition = 0;
	this._verticalScrollingPosition = 0;
	this.setColumnWidths(columnWidths);
	this._columnIDOffset = columnIDOffset;
	this._shallUpdateServerState = false;
	this._lazyRequestID = services.ajax.createLazyRequestID();
	this._slices = new Array();
};

TABLE.Viewport.CONTAINER_ID_SUFFIX = "_viewport";

TABLE.Viewport.prototype.appendSlice = function(slice) {
	this._slices.push(slice);
};

TABLE.Viewport.prototype.prependSlice = function(slice) {
	this._slices.splice(0,0, slice);
};

TABLE.Viewport.prototype.setUpdateServerState = function(shallUpdateServerState) {
	this._shallUpdateServerState = shallUpdateServerState;
};

/**
 * Getter for the viewport container id
 */
TABLE.Viewport.prototype.getContainerID = function() {
	return this._containerID;
};

/**
 * Getter for the viewport width (sum width of all columns)
 */
TABLE.Viewport.prototype.getWidth = function() {
	return this._width;
};

/**
 * Called at frozen table creation / lazifying setting
 */
TABLE.Viewport.prototype.initWidth = function(viewportWidth) {
	this._width = viewportWidth;
	var effectiveWidth = viewportWidth;
	var viewport = document.getElementById(this.getContainerID());
	var layoutFunction = function(viewport, effectiveWidth) {
		return function() {
			BAL.setEffectiveWidth(viewport, effectiveWidth);
			viewport = null;
			effectiveWidth = null;
		};
	}(viewport, effectiveWidth);
	services.layout.addLayoutFunction(layoutFunction);
};

/**
 * Setter for the viewport width (sum width of all columns)
 */
TABLE.Viewport.prototype.setWidth = function(viewportWidth) {
	this._width = viewportWidth;
	var viewport = document.getElementById(this.getContainerID());
	BAL.setElementWidth(viewport, viewportWidth);
};

/**
 * @returns viewport width, which can be seen on screen
 */
TABLE.Viewport.prototype.getVisibleWidth = function() {
	return BAL.getElementWidth(this.getContentElement().parentNode);
};

/**
 * Called at frozen table creation / lazifying setting
 */
TABLE.Viewport.prototype.initHeight = function(viewportHeight) {
	var effectiveHeight = viewportHeight;
	var viewport = document.getElementById(this.getContainerID());
	var layoutFunction = function(viewport, effectiveHeight) {
		return function() {
			BAL.setEffectiveHeight(viewport, effectiveHeight);
			viewport = null;
			effectiveHeight = null;
		};
	}(viewport, effectiveHeight);
	services.layout.addLayoutFunction(layoutFunction);
};

/**
 * Setter for the viewport height
 */
TABLE.Viewport.prototype.setHeight = function(viewportHeight) {
	var viewport = document.getElementById(this.getContainerID());
	BAL.setElementHeight(viewport, viewportHeight);
};

/**
 * Getter for the offset of the column ids
 */
TABLE.Viewport.prototype.getColumnIDOffset = function() {
	return this._columnIDOffset;
};

/**
 * Getter for column count
 */
TABLE.Viewport.prototype.getColumnCount = function() {
	return this._columnWidths.length;
};

/**
 * Getter for a column width
 */
TABLE.Viewport.prototype.getColumnWidth = function(column) {
	if (typeof(this._columnWidths[column]) != "undefined") {
		return this._columnWidths[column];
	} else {
		throw new Error("Could not retrieve column width due to invalid column index!");
	}
};

/**
 * Setter for a column width
 */
TABLE.Viewport.prototype.setColumnWidth = function(column, columnWidth) {
	if (typeof(this._columnWidths[column]) != "undefined") {
		this._columnWidths[column] = columnWidth;
	} else {
		throw new Error("Could not set column width due to invalid column index!");
	}
};

/**
 * Setter for the column widths
 */
TABLE.Viewport.prototype.setColumnWidths = function(/* Array */columnWidths) {
	this._columnWidths = columnWidths;
};

/**
 * Getter for the viewport html reference
 */
TABLE.Viewport.prototype.getContentElement = function() {
	return document.getElementById(this._containerID);
};

/**
 * Getter for the viewport horizontal scrolling position
 */
TABLE.Viewport.prototype.getHorizontalScrollingPosition = function() {
	return this._horizontalScrollingPosition;
};

/**
 * Setter for the viewport horizontal scrolling position
 */
TABLE.Viewport.prototype.setHorizontalScrollingPosition = function(
		horizontalScrollingPosition) {
	if (this._horizontalScrollingPosition != horizontalScrollingPosition) {
		this._horizontalScrollingPosition = horizontalScrollingPosition;
		if(this._shallUpdateServerState) {
			this.propagateScrollPositionToServer();
		}
	}
};

/**
 * Getter for the viewport vertical scrolling position
 */
TABLE.Viewport.prototype.getVerticalScrollingPosition = function() {
	return this._verticalScrollingPosition;
};

/**
 * Setter for the viewport vertical scrolling position
 */
TABLE.Viewport.prototype.setVerticalScrollingPosition = function(
		verticalScrollingPosition) {
	if (this._verticalScrollingPosition != verticalScrollingPosition) {
		this._verticalScrollingPosition = verticalScrollingPosition;
		if(this._shallUpdateServerState) {
			this.propagateScrollPositionToServer();
		}
	}
};

TABLE.Viewport.prototype.propagateScrollPositionToServer = function() {
	var position = TABLE.VisiblePositionCalculator.getPosition(this);
	services.ajax.executeOrUpdateLazy(this._lazyRequestID, "dispatchControlCommand", {
		controlCommand: "updateScrollPosition",
		controlID: this._tableID,
		displayVersion: this._tableDisplayVersion,
		columnAnchor: position.horizontal.columnAnchor,
		columnAnchorOffset: position.horizontal.columnAnchorOffset,
		rowAnchor: position.vertical.rowAnchor,
		rowAnchorOffset: position.vertical.rowAnchorOffset
	});
};

TABLE.Viewport.prototype.getTableID = function() {
	return this._tableID;
};

/**
 * A class to manage table slices
 * 
 * @param {Array}
 *            viewports a list of managed viewports {TABLE.Viewport}
 */
TABLE.SliceManager = function(tableControlID, tableDisplayVersion, viewportHeight, firstRow, 
        lastRow, viewports, firstSliceRow, lastSliceRow) {

	/*
	 * Initialization of member variables
	 */
	this._tableControlID = tableControlID;
	this._tableDisplayVersion = tableDisplayVersion;
	this._visibleViewportHeight = viewportHeight;
	this._pageStart = firstRow;
	this._pageEnd = lastRow;
	this._viewports = viewports;
	this._viewportsById = new Object();
	
	for (var n = 0; n < viewports.length; n++) {
		var viewport = viewports[n];
		this._viewportsById[viewport.getContainerID()] = viewport;
	}
	
	this._rowHeight = 0; // adjusted in init function
	
	this._viewportContentHeight = 0; // adjusted in init function
	
	/* Note: The initial slice gets its ID by the server. */
	this._nextId = this.INITIAL_SLICE_ID + 1;

	this._init(firstSliceRow, lastSliceRow);
};

TABLE.SliceManager.prototype.getTableID = function() {
	return this._tableControlID;
};

TABLE.SliceManager.prototype.getFirstPageRow = function() {
	return this._pageStart;
};

TABLE.SliceManager.prototype.getLastPageRow = function() {
	return this._pageEnd;
};

/** Vertical start position of the current page. */
TABLE.SliceManager.prototype.getPageStartPosition = function() {
	return 0;
};

/** Vertical end position of the current page. */
TABLE.SliceManager.prototype.getPageEndPosition = function() {
	return this.getPageRowCount() * this.getRowHeight();
};

/** Vertical start position of the first slice of the requested range. */
TABLE.SliceManager.prototype.getRangeStartPosition = function() {
	var sliceCount = this._viewports[0]._slices.length;
	if (sliceCount > 0) {
		return this._viewports[0]._slices[0].getUpperBound();
	} else {
		return 0;
	}
};

/** Vertical end position of the last slice of the requested range. */
TABLE.SliceManager.prototype.getRangeStopPosition = function() {
	var sliceCount = this._viewports[0]._slices.length;
	if (sliceCount > 0) {
		return this._viewports[0]._slices[sliceCount - 1].getLowerBound();
	} else {
		return -1;
	}
};

TABLE.SliceManager.prototype.getRangeStartRow = function() {
	var sliceCount = this._viewports[0]._slices.length;
	if (sliceCount > 0) {
		return this._viewports[0]._slices[0].getFirstRow();
	} else {
		return 0;
	}
};

TABLE.SliceManager.prototype.getRangeLastRow = function() {
	var sliceCount = this._viewports[0]._slices.length;
	if (sliceCount > 0) {
		return this._viewports[0]._slices[sliceCount - 1].getLastRow();
	} else {
		return -1;
	}
};


TABLE.ABOVE_STRATEGY = "above";
TABLE.BELOW_STRATEGY = "below";

/*
 * Declaration of privileged methods
 */

/**
 * Getter for the height of the viewport container
 */
TABLE.SliceManager.prototype.getViewportContentHeight = function() {
	return this._viewportContentHeight;
};

TABLE.SliceManager.prototype.newId = function() {
	return this._nextId++;
};

/**
 * Getter for a viewport container
 */
TABLE.SliceManager.prototype.getViewport = function(containerID) {
	return this._viewportsById[containerID];
};

/**
 * Setter for the visible viewport height
 */
TABLE.SliceManager.prototype.setVisibleViewportHeight = function(viewportHeight) {
	// Viewport height must be at least 1 pixel
	this._visibleViewportHeight = Math.max(viewportHeight, 1);

	// Check, if slice updates are necessary
	for (var id in this._viewports) {
        var verticalScrollPosition = this._viewports[id].getVerticalScrollingPosition();
        break;
	}
	if (typeof(verticalScrollPosition) == undefined) {
		throw new Error("No viewPorts found");
	}
	
	this.positionUpdate(verticalScrollPosition);
};

/**
 * Getter for the visible viewport height
 */
TABLE.SliceManager.prototype.getVisibleViewportHeight = function() {
	return this._visibleViewportHeight;
};

/**
 * Update the viewport container height
 */
TABLE.SliceManager.prototype.updateViewportHeight = function(
		viewportID, sliceID, pageRowCount) {
	// Adjusting viewport container height
    var viewport = this.getViewport(viewportID);
	if (viewport == null) {
		return;
	}
	
	if(this.hasSlices()) {
		viewport._slices[0].setFirstRow(0);
		viewport._slices[0].setLastRow(pageRowCount - 1);
	}
	this._adjustViewportContentHeight(viewport, sliceID, pageRowCount, false);
	TABLE.adjustScrollBars(this._tableControlID);
};

TABLE.SliceManager.prototype.getFixedSlices = function() {
	return this._viewports[0]._slices;
};

/**
 * Update the width of a table column
 */
TABLE.SliceManager.prototype.updateColumnWidth = function(viewportID,
		columnID, columnWidth, updateServerColumn) {

	// Update viewport container data
	var viewport = this.getViewport(viewportID);
	var deltaWidth = columnWidth - viewport.getColumnWidth(columnID);
	viewport.setColumnWidth(columnID, columnWidth);
	viewport.setWidth(viewport.getWidth() + deltaWidth);

	// Update table columns
	var slices = viewport._slices;
	for (var i = 0; i < slices.length; i++) {
		var currentSlice = slices[i];
		if (!currentSlice._displayed) {
			continue;
		}
		
		// Adjust slice width
		var sliceFragment = currentSlice.getDOMElement();
		var sliceTableFragment = sliceFragment.getElementsByTagName("table")[0];
		currentSlice.setWidth(viewport.getWidth());
		
		// Adjust column width
		if (sliceTableFragment != undefined) {
			var tableBody = sliceTableFragment.getElementsByTagName("tbody")[0];
			var tableRows = tableBody.getElementsByTagName("tr");
			var columnDIV = null;
			for (var rowIndex = 0; (rowIndex < tableRows.length); rowIndex++) {
				var tableColumns = tableRows[rowIndex].getElementsByTagName("td");
				columnDIV = tableColumns[columnID].getElementsByTagName("div")[0];
				TABLE.setColumnWidth(columnDIV, viewport.getColumnWidth(columnID));
				
				// Quirks, to force IE6 and IE7 to set the new column width
				// directly at td-tag, due to these IE versions do not
				// perform column adjustment according to the width of
				// child elements
				TABLE.setColumnWidth(tableColumns[columnID], viewport.getColumnWidth(columnID));
			}
		}
	}
	
	// Update server-side column width
	if (updateServerColumn) {
		var serverColumnID = viewport.getColumnIDOffset() + columnID;
		
		services.ajax.execute("dispatchControlCommand", {
			controlCommand: "updateColumnWidth",
			controlID: this._tableControlID,
			columnID: serverColumnID,
			newColumnWidth: columnWidth
		}, /* useWaitPane */false);
	}
};

/**
 * Inserts a slice into a viewport container
 */
TABLE.SliceManager.prototype.insertSlice = function(viewportID, sliceID, sliceRowCount) {
	if(TABLE.doLog) {
		this._startProfiling("insertSlice");
	}
	
	var viewport = this.getViewport(viewportID);
	
	// Retrieve slice from request array
	var slice = null;
	var slices = viewport._slices;
	for (var i = 0; i < slices.length; i++) {
		if (slices[i].getID() == sliceID) {
			slice = slices[i];
			break;
		}
	}

	var sliceElement = document.getElementById(sliceID);
	if (slice == null) {
		// This slice is already requested to be deleted. 
		// Drop it from DOM.
		sliceElement.parentNode.removeChild(sliceElement);
		
		if(TABLE.doLog) {
			this._reportState("Received slice '" + sliceID + "' that is already marked as deleted.");
		}
		return;
	}


	if(TABLE.doLog) {
		this._startProfiling("slicePositionSynchronization");
	}

	slice._displayed = true;
	
	var sliceHeight = slice.getHeight();
	
	if(TABLE.doLog) {
		this._endProfiling("slicePositionSynchronization");
		this._startProfiling("slicePositioning");
	}

	if (slice.getInsertStrategy() == TABLE.BELOW_STRATEGY) {

		// Insert new slice after last visible slice
		BAL.setElementY(sliceElement, slice.getInsertPosition());
		BAL.setElementX(sliceElement, 0);
	} else {

		// Insert new slice before first visible slice
		BAL.setElementY(sliceElement, slice.getInsertPosition() - sliceHeight);
		BAL.setElementX(sliceElement, 0);
	}
	
	if(TABLE.doLog) {
		this._endProfiling("slicePositioning");
	}
	
	var viewport = this.getViewport(viewportID);
	// Set slice width in every viewport
	if (viewport != null) {
		
		// Workaround for IE erasing the scroll information of empty divs:
		// Set horizontal scrolling for flex viewport again.
		var contentElement = viewport.getContentElement().parentNode;
		contentElement.scrollLeft = viewport.getHorizontalScrollingPosition();
	} else {
		throw new Error("Received table slice was not found in request queue!");
	}
	
	if(TABLE.doLog) {
		this._endProfiling("insertSlice");
		this._reportState("Insert slice!", ["insertSlice", "slicePositionSynchronization",
		                                    "slicePositioning"]);
	}
};

/**
 * Informs the slice manager about scroll position updates, to request new
 * slices from server on demand, or to drop unused slices.
 */
TABLE.SliceManager.prototype.positionUpdate = function(scrollPosition) {
	if(TABLE.doLog) {
		this._startProfiling("positionUpdate");
	}
	
	// Check if table is empty
	if(this.getPageEndPosition() > 0) {
		
		var viewportStart = scrollPosition;
		var viewportStop = scrollPosition + this._visibleViewportHeight;
		
		var keepRangeStart = Math.max(this.getPageStartPosition(), viewportStart - this.getKeepLimit());
		var keepRangeStop = Math.min(this.getPageEndPosition(), viewportStop + this.getKeepLimit());
		
		/* Determine slices, which can be dismissed */
		var dismissedSlices = new Array();
		var dismissedBoundaries = new Array();
		for (var n = 0; n < this._viewports.length; n++) {
			var viewport = this._viewports[n];
			
			var slices = viewport._slices;
			for (var i = slices.length - 1; i >= 0; i--) {
				var slice = slices[i];
				
				if (slice.noIntersection(keepRangeStart, keepRangeStop)) {
					// Drop slice.
					slices.splice(i, 1);
					dismissedSlices.push(slice);
					
					if (n == 0) {
						dismissedBoundaries.push(slice.getLastRow());
					}
				}
			}
		}
		
		// Update current range after dropping outdated slices.
		var rangeStart = this.getRangeStartPosition();
		var rangeStop = this.getRangeStopPosition();
		
		var requestRangeStart = Math.max(this.getPageStartPosition(), viewportStart - this.getRequestOffset());
		var requestRangeStop = Math.min(this.getPageEndPosition(), viewportStop + this.getRequestOffset());
		
		if(TABLE.doLog) {
			this._startProfiling("_requestSlice");
		}
		var requestSent = false;
		if (this.hasSlices()) {
			var triggerRangeStart = Math.max(this.getPageStartPosition(), viewportStart - this.getTriggerLimit());
			var triggerRangeStop = Math.min(this.getPageEndPosition(), viewportStop + this.getTriggerLimit());
			
			if (rangeStart > triggerRangeStart) {
				// Request before current range.
				requestSent |= this._requestSlice(requestRangeStart, rangeStart, TABLE.ABOVE_STRATEGY, dismissedSlices, dismissedBoundaries);
			}
			if (rangeStop < triggerRangeStop) {
				// Request after current range.
				requestSent |= this._requestSlice(rangeStop, requestRangeStop, TABLE.BELOW_STRATEGY, dismissedSlices, dismissedBoundaries);
			}
		} else {
			// Request new range.
			requestSent |= this._requestSlice(requestRangeStart, requestRangeStop, TABLE.BELOW_STRATEGY, dismissedSlices, dismissedBoundaries);
		}
		
		if(TABLE.doLog) {
			this._endProfiling("_requestSlice");
		}
		
		if ((dismissedSlices.length > 0) && !requestSent) {
			var tableControlID = this._tableControlID;
			var tableDisplayVersion = this._tableDisplayVersion;
			var rangeStartRow = this.getRangeStartRow();
			var rangeLastRow = this.getRangeLastRow();
			
			this.blurCellFocus();
			
			// Propagate slice dismissal to server, even if there is no request for further slices
			var callback = function() {
				TABLE.dismissSlices(dismissedSlices);
			};
			var commandArguments = {
					controlCommand: "dismissSlice",
					controlID: tableControlID,
					displayVersion: tableDisplayVersion,
					dismissedBoundaries: dismissedBoundaries,
					displayFirstRow: rangeStartRow,
					displayLastRow: rangeLastRow
				};
			services.ajax.execute("dispatchControlCommand",
					services.ajax.addSystemCommandProperty(commandArguments),
					/* useWaitPane */false, callback);
		}
	} 
	
	
	if(TABLE.doLog) {
		this._endProfiling("positionUpdate");
		if(this.getRowHeight() > 0) {
			this._reportState("Slice request operation", ["positionUpdate", "_requestSlice"]);
		} else {
			this._reportState("Slice request operation", ["positionUpdate"]);
		}
	}
};

TABLE.SliceManager.prototype.blurCellFocus = function() {
	var activeElement = BAL.DOM.getCurrentActiveSimpleElement(BAL.getTopLevelDocument());
	if(activeElement != null) {
		var tableControlId = this._tableControlID;
		var fixBodyNode = document.getElementById(TABLE.getFixBodyID(tableControlId));
		var flexBodyNode = document.getElementById(TABLE.getFlexBodyID(tableControlId));
		var currentNode = activeElement;
		var isFocusInCell = false;
		while(currentNode != null) {
			if(currentNode == fixBodyNode || currentNode == flexBodyNode) {
				isFocusInCell = true;
				break;
			}
			currentNode = currentNode.parentNode;
		}
		if(isFocusInCell) {
			blurFocus(activeElement);
		}
	}
};

TABLE.dismissSlices = function(slicesToDismiss) {
	for (var n = 0; n < slicesToDismiss.length; n++) {
		var slice = slicesToDismiss[n];
		if (slice._displayed) {
			// Drop slice from DOM.
			slice.remove();
		} else {
			// Slice was not yet delivered by the server and therefore cannot be hidden. 
			// This slice will be disabled directly after it arrives.
		}
	}
};

/*
 * Declaration of private methods
 */

TABLE.SliceManager.prototype.INITIAL_SLICE_ID = 0;
TABLE.SliceManager.prototype.MAX_KEEP_PIXELS = 700;
TABLE.SliceManager.prototype.MIN_TRIGGER_PIXELS = 400;
TABLE.SliceManager.prototype.MAX_REQUEST_PIXELS = 700;

/**
 * Keep limit is two times the visible viewport height, but at most
 * TABLE.SliceManager.prototype.MAX_KEEP_PIXELS 
 */
TABLE.SliceManager.prototype.getKeepLimit = function() {
	return Math.min(this._visibleViewportHeight * 2, this.MAX_KEEP_PIXELS);
};

/**
 * Request trigger limit is at half of visible viewport height, but at least
 * TABLE.SliceManager.prototype.MIN_TRIGGER_PIXELS
 */
TABLE.SliceManager.prototype.getTriggerLimit = function() {
	return Math.max(this._visibleViewportHeight / 2, this.MIN_TRIGGER_PIXELS);
};

/**
 * Request offset is two times the visible viewport height, but at most
 * TABLE.SliceManager.prototype.MAX_REQUEST_PIXELS 
 */
TABLE.SliceManager.prototype.getRequestOffset = function() {
	return Math.min(this._visibleViewportHeight * 2, this.MAX_REQUEST_PIXELS);
};

TABLE.SliceManager.prototype.hasSlices = function() {
	return this._viewports[0]._slices.length > 0;
};

/**
 * Inits the slice manager at object creation
 */
TABLE.SliceManager.prototype._init = function(firstSliceRow, lastSliceRow) {
	for (var id in this._viewports) {
		var viewport = this._viewports[id];
		
		var sliceID = viewport.getContainerID() + "_slice-" + this.INITIAL_SLICE_ID;
		var sliceDomElement = document.getElementById(sliceID);
		var sliceRowCount = firstSliceRow > -1 ? (lastSliceRow - firstSliceRow + 1) : 0;
		this._adjustViewportContentHeight(viewport,	sliceID, sliceRowCount, true);
		var insertPosition = this._calculateInsertPosition(TABLE.BELOW_STRATEGY, firstSliceRow, lastSliceRow);
		var newSlice = new TABLE.Slice(sliceID, 
		          insertPosition, 
		          TABLE.BELOW_STRATEGY, 
		          firstSliceRow, 
		          lastSliceRow, 
		          this.getRowHeight());
		viewport.appendSlice(newSlice);
		newSlice._displayed = true;
		
//		********************* Setter ********************
//		*************************************************
		var layoutFunction = function(sliceDOMElement, position, currentViewport) {
			return function() {
				BAL.setElementY(sliceDOMElement, position);
				newSlice.setWidth(currentViewport.getWidth());
				sliceDOMElement = null;
				position = null;
				currentViewport = null;
			};
		}(sliceDomElement, insertPosition, viewport);
		services.layout.addLayoutFunction(layoutFunction);
//		***************************************************
//		***************************************************
	}
};

/**
 * Computes and adjusts the average row height
 */
TABLE.SliceManager.prototype._adjustRowHeight = function(sliceID, sliceRowCount) {
	var slice = document.getElementById(sliceID);
	var sliceRowHeight;
	if(sliceRowCount > 0) {
		var sliceTable = slice.getElementsByTagName("table")[0];
		var sliceTableBody = sliceTable.getElementsByTagName("tbody")[0];
		var sliceTableRows = BAL.DOM.getChildElements(sliceTableBody);
		var sliceTableRow = sliceTableRows[sliceTableRows.length - 1];
		sliceRowHeight = parseInt(TL.getTLAttribute(sliceTableRow, "row-height"));
	} else {
		sliceRowHeight = 0;
	}
	this._rowHeight = sliceRowHeight;
};

/**
 * @returns {Number} height of a single row within viewport
 */
TABLE.SliceManager.prototype.getRowHeight = function() {
	return this._rowHeight;
};

/**
 * Computes and adjusts the height of the viewport container
 * 
 * @param {TABLE.Viewport} viewPort the view port to adjust 
 */
TABLE.SliceManager.prototype._adjustViewportContentHeight = function(viewPort, sliceID, sliceRowCount, isInitPhase) {
	if(isInitPhase) {
		this._adjustRowHeight(sliceID, sliceRowCount);
	}
	this._viewportContentHeight = this.getRowHeight() * this.getPageRowCount();
	if(!isInitPhase) {
		viewPort.setHeight(this._viewportContentHeight);
	} else {
		viewPort.initHeight(this._viewportContentHeight);
	}
};

TABLE.SliceManager.prototype.setPageRange = function(firstPageRow, lastPageRow) {
	this._pageStart = firstPageRow;
	this._pageEnd = lastPageRow;
};

TABLE.SliceManager.prototype.getPageRowCount = function() {
	return this._pageEnd - this._pageStart + 1;
};

TABLE.SliceManager.prototype.getRowStartingBefore = function(position) {
	return this._pageStart + Math.floor(position / this.getRowHeight());
};

TABLE.SliceManager.prototype.getRowEndingAfter = function(position) {
	return Math.min(this._pageEnd, this._pageStart + Math.ceil(position / this.getRowHeight()));
};

/**
 * Requests a new slice from server
 */
TABLE.SliceManager.prototype._requestSlice = function(startPosition, stopPosition, 
		insertStrategy, /* Array */ dismissedSlices, /* Array */dismissedBoundaries) {
	
	var firstRow = this.getRangeStartRow();
	var lastRow = this.getRangeLastRow();

	var sliceFirstRow = this.getRowStartingBefore(startPosition);
	var sliceLastRow = this.getRowEndingAfter(stopPosition);
	
	var insertBelow = insertStrategy == TABLE.BELOW_STRATEGY;
	
	// Make sure, no overlapping rows are requested after rounding from pixels to rows.
	if (lastRow >= firstRow) {
		// Currently at least one row is displayed.
		if (insertBelow) {
			sliceFirstRow = Math.max(lastRow + 1, sliceFirstRow);
		} else {
			sliceLastRow = Math.min(firstRow - 1, sliceLastRow);
		}
	}

	if (sliceFirstRow > sliceLastRow) {
		return false;
	}
	
	var insertPosition = this._calculateInsertPosition(insertStrategy, sliceFirstRow, sliceLastRow);
	
	var sliceIDPart = this.newId();
	for (var id in this._viewports) {
		var viewport = this._viewports[id];
		var sliceID = viewport.getContainerID() + "_slice-" + sliceIDPart;
		var newSlice = new TABLE.Slice(sliceID, 
			insertPosition, 
			insertStrategy, 
			sliceFirstRow, 
			sliceLastRow, 
			this.getRowHeight());
		
		if (insertBelow) {
			viewport.appendSlice(newSlice);
		} else {
			viewport.prependSlice(newSlice);
		}
	}
	
	var tableControlID = this._tableControlID;
	var tableDisplayVersion = this._tableDisplayVersion;
	var rangeStartRow = this.getRangeStartRow();
	var rangeLastRow = this.getRangeLastRow();
	var callback = function() {
		TABLE.dismissSlices(dismissedSlices);
	};
	
	this.blurCellFocus();
	
	var commandArguments = {
			controlCommand: "requestSlice",
			controlID: tableControlID,
			displayVersion: tableDisplayVersion,
			sliceIDPart: sliceIDPart,
			sliceFirstRow: sliceFirstRow,
			sliceLastRow: sliceLastRow,
			dismissedBoundaries: dismissedBoundaries,
			displayFirstRow: rangeStartRow,
			displayLastRow: rangeLastRow
		};
	services.ajax.execute("dispatchControlCommand",
			services.ajax.addSystemCommandProperty(commandArguments),
			/* useWaitPane */false, callback);
			
	return true;
};

TABLE.SliceManager.prototype._calculateInsertPosition = function(insertStrategy, sliceFirstRow, sliceLastRow) {
	if(insertStrategy == TABLE.BELOW_STRATEGY) {
		return this._getRowOnCurrentPage(sliceFirstRow) * this.getRowHeight();
	} else {
		return (this._getRowOnCurrentPage(sliceLastRow) + 1) * this.getRowHeight();
	}
};

TABLE.SliceManager.prototype._getRowOnCurrentPage = function(tableRow) {
	return tableRow - this._pageStart;
};

TABLE.SliceManager.prototype._timeMeasures = new Object();

TABLE.SliceManager.prototype._startProfiling = function(id) {
	this._timeMeasures[id] = new Object();
	this._timeMeasures[id].startTime = new Date();
};

TABLE.SliceManager.prototype._endProfiling = function(id) {
	this._timeMeasures[id].stopTime = new Date();
};

TABLE.SliceManager.prototype._getProfilingTime = function(id) {
	return this._timeMeasures[id].stopTime.getTime() - this._timeMeasures[id].startTime.getTime();
};

/**
 * Method to report the current state to the logs 
 * @param reportMessage - an additional report message
 */
TABLE.SliceManager.prototype._reportState = function(reportMessage, /*Array*/ profilingIDs) {
//	this._tableControlID = ctrlID;
//	this._pageStart = firstRow;
//	this._pageEnd = lastRow;
//	this._visibleViewportHeight = viewportHeight;
//	this._viewports = viewports;
//	this._viewportContentHeight = 0; // adjusted in init function
//	this._init(firstSliceRow, lastSliceRow);
	var report = reportMessage + "\n";
	if((profilingIDs != undefined) && (profilingIDs != null)) {
		for(var i = 0; i < profilingIDs.length; i++) {
			report += "Execution time of profiled code section '" + profilingIDs[i] + "': " +
					  this._getProfilingTime(profilingIDs[i]) + "ms\n";
		}
	}
	report += "First visible row: " + this.getRangeStartRow() + "\n";
	report += "Last visible row: " + this.getRangeLastRow() + "\n\n";
	
	// Slices info section begin
	report += "------\n\n";
	
	// Add infos about open slices (only fix part
	report += "Slices:\n";
	var slices = this.getFixedSlices();
	for(var i = 0; i < slices.length; i++) {
		var slice = slices[i];
		report += this._getSliceInfo(slice);
	}
	
	// Slices info section end
	report += "------\n\n";
	
	report += "First page row: " + this._pageStart + "\n";
	report += "Last page row: " + this._pageEnd + "\n";
	report += "Page row count: " + this.getPageRowCount() + "\n";
	
	// Send report to server
	services.log.info(report);
};

/**
 * Method to report information about a slice 
 * @param slice {TABLE.Slice} - the slice, which info shall be added to the report
 * 
 * @returns the info of the slice
 */
TABLE.SliceManager.prototype._getSliceInfo = function(slice) {
	var spacer = "    ";
	var report = spacer + "*********\n";
	report += spacer + "ID: " + slice.getID() + "\n";
	report += spacer + "displayed: " + slice._displayed + "\n";
	report += spacer + "first row: " + slice.getFirstRow() + "\n";
	report += spacer + "last row: " + slice.getLastRow() + "\n";
	report += spacer + "insert strategy: " + slice.getInsertStrategy() + "\n";
	report += spacer + "*********\n\n";
	
	return report;
};

TABLE.MouseEventData = function() {
	this.oldclass = null;
	this.changeElement = null;
	this.isFirstMove = true;
	this.dragOrSort = false;
	this.origMouseMove = null;
	this.isFixFlexColumnExchange = false;
};

TABLE.ColumnWidthAdjustment = function(event, ctrlID) {
	this.tableStructure = new TABLE.TableStructure(ctrlID);
};

TABLE.TableStructure = function(ctrlID) {
	this.headerFix = document.getElementById(ctrlID + "_headerFix");
	this.bodyFix = document.getElementById(ctrlID + "_bodyFix");
	this.hasFixPart = this.headerFix != null;
	this.headerFlex = document.getElementById(ctrlID + "_headerFlex");
	this.bodyFlex = document.getElementById(ctrlID + "_bodyFlex");
	this.hasFlexPart = this.headerFlex != null;
	this.tableSeperator = document.getElementById(ctrlID + "_tableSeperator");
	this.tableSeparatorWidth = TABLE.getTableSeparatorWidth(ctrlID);
	if (this.hasFixPart) {
		var fixHeaderTable = this.headerFix.getElementsByTagName("table")[0];
		this.headerHeight = BAL.getElementHeight(fixHeaderTable);
		this.headerWidth = BAL.getElementWidth(fixHeaderTable);
	} else {
		var flexHeaderTable = this.headerFlex.getElementsByTagName("table")[0];
		this.headerHeight = BAL.getElementHeight(flexHeaderTable);
		this.headerWidth = 0;
	}
};

TABLE.TableStructure.prototype.updateFlexWidth = function(availableWidth) {
	if (!this.hasFlexPart) {
		return;
	}
	var width = availableWidth - this.headerWidth - this.tableSeparatorWidth;
	BAL.setElementWidth(this.bodyFlex, width);
	BAL.setElementWidth(this.headerFlex, width);
	return width;
};

TABLE.TableStructure.prototype.updateFixWidth = function() {
	if (!this.hasFixPart) {
		return;
	}
	var width = BAL.getElementWidth(this.headerFix.getElementsByTagName("table")[0]);
	if (this.hasFlexPart) {
		BAL.setElementX(this.bodyFlex, width + this.tableSeparatorWidth);
		BAL.setElementX(this.headerFlex, width + this.tableSeparatorWidth);
	}
	BAL.setElementWidth(this.bodyFix, width);
	BAL.setElementWidth(this.headerFix, width);
	this.headerWidth = width;
	return width;
};

TABLE.ElementPosition = function(x, y, width, height) {
	this.topX = x;
	this.topY = y;
	this.belowX = x + width;
	this.belowY = y + height;
	this.width = width;
	this.height = height;
};

/******************************************************************************************
 *                                                                                        *
 *                                                                                        *
 *                      ScrollPositionProvider                                            *
 *                                                                                        *
 *                                                                                        *
 *                                                                                        *
 ******************************************************************************************/

TABLE.ScrollPositionProvider = {
		/**
		 * Return scroll positions of table body
		 */
		getServerScrollPositions:function(sliceManager, clientDisplayData) {
			if(TABLE.ScrollPositionProvider.isClientDisplayDataExistent(clientDisplayData)) {
				var storedVerticalPosition = TABLE.ScrollPositionProvider.getStoredVerticalScrollPosition(sliceManager, clientDisplayData);
				var storedHorizontalPosition = TABLE.ScrollPositionProvider.getStoredHorizontalScrollPosition(sliceManager, clientDisplayData);
				return TABLE.ScrollPositionProvider.getFormattedPositions(storedHorizontalPosition, storedVerticalPosition);
			} else {
				return TABLE.ScrollPositionProvider.getTopLeftPosition(sliceManager);
			}
		},
		
		/**
		 * Return scroll positions of scrollbar
		 */
		getComputedScrollPositions:function(sliceManager, clientDisplayData) {
			if(TABLE.ScrollPositionProvider.isClientDisplayDataExistent(clientDisplayData)) {
				return TABLE.ScrollPositionProvider.getCalculatedScrollPositions(sliceManager, clientDisplayData);
			} else {
				return TABLE.ScrollPositionProvider.getTopLeftPosition(sliceManager);
			}
		},
		
		isClientDisplayDataExistent:function(clientDisplayData) {
			return clientDisplayData != undefined || clientDisplayData != null;
		},
		
		getCalculatedScrollPositions:function(sliceManager, clientDisplayData) {
			var verticalScrollPosition = TABLE.ScrollPositionProvider.getVerticalScrollPosition(sliceManager, clientDisplayData);
			var horizontalScrollPosition = TABLE.ScrollPositionProvider.getHorizontalScrollPosition(sliceManager, clientDisplayData);
			return TABLE.ScrollPositionProvider.getFormattedPositions(horizontalScrollPosition, verticalScrollPosition);
		},
		
		getVerticalScrollPosition:function(sliceManager, clientDisplayData) {
			if(TABLE.ScrollPositionProvider.isVerticalRangeDefined(sliceManager, clientDisplayData)) {
				return TABLE.ScrollPositionProvider.getVerticalScrollPositionForDefinedRange(sliceManager, clientDisplayData);
			} else {
				return TABLE.ScrollPositionProvider.getVerticalScrollPositionForUndefinedRange(sliceManager, clientDisplayData);
			}
		},
		
		isVerticalRangeDefined:function(sliceManager, clientDisplayData) {
			var pageRowRange = TABLE.ScrollPositionProvider.getPageRowRange(sliceManager, clientDisplayData);
			return pageRowRange.firstIndex > -1;
		},
		
		getPageRowRange:function(sliceManager, clientDisplayData) {
			var rowRange = clientDisplayData.visiblePane.rowRange;
			var pageRangeFirstRow = rowRange.firstIndex - sliceManager.getFirstPageRow();
			var pageRangeLastRow = rowRange.lastIndex - sliceManager.getFirstPageRow();
			var pageForcedVisibleRowInRange = rowRange.forcedVisibleIndexInRange - sliceManager.getFirstPageRow();
			return {firstIndex:pageRangeFirstRow, lastIndex:pageRangeLastRow,
					forcedVisibleIndexInRange:pageForcedVisibleRowInRange};
		},
		
		getVerticalScrollPositionForUndefinedRange:function(sliceManager, clientDisplayData) {
			if(TABLE.ScrollPositionProvider.isStoredVerticalScrollPositionDefined(sliceManager, clientDisplayData)) {
				return TABLE.ScrollPositionProvider.getStoredVerticalScrollPosition(sliceManager, clientDisplayData);
			} else {
				return sliceManager.getRangeStartPosition();
			}
		},
		
		isStoredVerticalScrollPositionDefined:function(sliceManager, clientDisplayData) {
			var pageRowAnchor = TABLE.ScrollPositionProvider.getPageRowAnchor(sliceManager, clientDisplayData); 
			return pageRowAnchor.index > -1;
		},
		
		getPageRowAnchor:function(sliceManager, clientDisplayData) {
			var pageRowAnchorIndex = clientDisplayData.viewportState.rowAnchor.index - sliceManager.getFirstPageRow();
			return {index:pageRowAnchorIndex, indexPixelOffset:clientDisplayData.viewportState.rowAnchor.indexPixelOffset };
		},
		
		getStoredVerticalScrollPosition:function(sliceManager, clientDisplayData) {
			var pageRowAnchor = TABLE.ScrollPositionProvider.getPageRowAnchor(sliceManager, clientDisplayData);
			var visibleRowPosition = pageRowAnchor.index * sliceManager.getRowHeight();
			var visibleRowOffset = pageRowAnchor.indexPixelOffset;
			return TABLE.ScrollPositionProvider.getStoredScrollPosition(visibleRowPosition, visibleRowOffset);
		},
		
		getStoredScrollPosition:function(indexScrollPosition, indexPixelOffset) {
			return indexScrollPosition + indexPixelOffset;
		},
		
		getVerticalScrollPositionForDefinedRange:function(sliceManager, clientDisplayData) {
			if(TABLE.ScrollPositionProvider.isPaneSmallerThanViewportHeight(sliceManager, clientDisplayData)) {
				return TABLE.ScrollPositionProvider.getVerticalScrollPositionForFullyDisplayablePane(sliceManager, clientDisplayData);
			} else {
				return TABLE.ScrollPositionProvider.getVerticalScrollPositionForForcedVisibleRow(sliceManager, clientDisplayData);
			}
		},
		
		isPaneSmallerThanViewportHeight:function(sliceManager, clientDisplayData) {
			return sliceManager.getVisibleViewportHeight() > TABLE.ScrollPositionProvider.getVerticalPaneHeight(sliceManager, clientDisplayData);
		},
		
		getVerticalPaneHeight:function(sliceManager, clientDisplayData) {
			var pageRowRange = TABLE.ScrollPositionProvider.getPageRowRange(sliceManager, clientDisplayData);
			return (pageRowRange.lastIndex - pageRowRange.firstIndex + 1) * sliceManager.getRowHeight();
		},
		
		getVerticalScrollPositionForFullyDisplayablePane:function(sliceManager, clientDisplayData) {
			var pageRowRange = TABLE.ScrollPositionProvider.getPageRowRange(sliceManager, clientDisplayData);
			var paneAnchor = pageRowRange.firstIndex;
			var paneHeight = TABLE.ScrollPositionProvider.getVerticalPaneHeight(sliceManager, clientDisplayData);
			return TABLE.ScrollPositionProvider.getPaneAndPreviousScrollingBasedVerticalPosition(sliceManager, clientDisplayData, paneAnchor, paneHeight);
		},
		
		getPaneAndPreviousScrollingBasedVerticalPosition:function(sliceManager, clientDisplayData, visibleRangeAnchor, visibleRangeHeight) {
			var rangeOffsetFromTop = visibleRangeAnchor * sliceManager.getRowHeight();
			var visibleViewportHeight = sliceManager.getVisibleViewportHeight();
			if(TABLE.ScrollPositionProvider.isStoredVerticalScrollPositionDefined(sliceManager, clientDisplayData)) {
				var verticalScrollOffset = TABLE.ScrollPositionProvider.getStoredVerticalScrollPosition(sliceManager, clientDisplayData);
				return TABLE.ScrollPositionProvider.getPaneAndPreviousScrollingBasedPosition(visibleRangeHeight, rangeOffsetFromTop, visibleViewportHeight, verticalScrollOffset);
			} else {
				return TABLE.ScrollPositionProvider.getCenteredScrollPosition(visibleRangeHeight, rangeOffsetFromTop, visibleViewportHeight);
			}
		},
		
		getPaneAndPreviousScrollingBasedPosition:function(visibleRangeSize, rangeOffsetFromTableBegin, visibleViewportSize, viewportScrollOffset) {
			if(TABLE.ScrollPositionProvider.hasPaneIntersectionWithPreviousScrollPosition(visibleRangeSize, rangeOffsetFromTableBegin, visibleViewportSize, viewportScrollOffset)) {
				return TABLE.ScrollPositionProvider.getDeltaBasedScrollPosition(visibleRangeSize, rangeOffsetFromTableBegin, visibleViewportSize, viewportScrollOffset);
			} else {
				return TABLE.ScrollPositionProvider.getCenteredScrollPosition(visibleRangeSize, rangeOffsetFromTableBegin, visibleViewportSize);
			}
		},
		
		hasPaneIntersectionWithPreviousScrollPosition:function(visibleRangeSize, rangeOffsetFromTableBegin, visibleViewportSize, viewportScrollOffset) {
			var boundaries = TABLE.ScrollPositionProvider.getRangeAndViewportBoundaries(visibleRangeSize, rangeOffsetFromTableBegin, visibleViewportSize, viewportScrollOffset);
			return boundaries.rangeBegin < boundaries.visibleViewportEnd
					&& boundaries.rangeEnd > boundaries.visibleViewportBegin;
		},
		
		getRangeAndViewportBoundaries:function(visibleRangeSize, rangeOffsetFromTableBegin, visibleViewportSize, viewportScrollOffset) {
			var rangeBegin = rangeOffsetFromTableBegin;
			var rangeEnd = rangeBegin + visibleRangeSize;
			var visibleViewportBegin = viewportScrollOffset;
			var visibleViewportEnd = visibleViewportBegin + visibleViewportSize;
			return {rangeBegin:rangeBegin, rangeEnd:rangeEnd,
					visibleViewportBegin:visibleViewportBegin,
					visibleViewportEnd:visibleViewportEnd};
			
		},
		
		getDeltaBasedScrollPosition:function(visibleRangeSize, rangeOffsetFromTableBegin, visibleViewportSize, viewportScrollOffset) {
			var boundaries = TABLE.ScrollPositionProvider.getRangeAndViewportBoundaries(visibleRangeSize, rangeOffsetFromTableBegin, visibleViewportSize, viewportScrollOffset);
			var endDelta;
			if(boundaries.rangeBegin < boundaries.visibleViewportBegin) {
				endDelta = Math.min(0, boundaries.rangeBegin - boundaries.visibleViewportBegin);
			} else {
				endDelta = Math.max(0, boundaries.rangeEnd - boundaries.visibleViewportEnd);
			}
			return viewportScrollOffset + endDelta;
		},
		
		getCenteredScrollPosition:function(visibleRangeSize, rangeOffsetFromTableBegin, visibleViewportSize) {
			var rangeOffsetFromViewportBegin = (visibleViewportSize - visibleRangeSize) / 2;
			return rangeOffsetFromTableBegin - rangeOffsetFromViewportBegin;
		},
		
		getVerticalScrollPositionForForcedVisibleRow:function(sliceManager, clientDisplayData) {
			var pageRowRange = TABLE.ScrollPositionProvider.getPageRowRange(sliceManager, clientDisplayData);
			var rangeTopBound = pageRowRange.firstIndex * sliceManager.getRowHeight();
			var rangeBottomBound = (pageRowRange.lastIndex + 1) * sliceManager.getRowHeight() - sliceManager.getVisibleViewportHeight();
			var rangeAnchor = pageRowRange.forcedVisibleIndexInRange;
			var forcedVisibleRowHeight = sliceManager.getRowHeight();
			var verticalScrollPosition = TABLE.ScrollPositionProvider.getPaneAndPreviousScrollingBasedVerticalPosition(sliceManager, clientDisplayData, rangeAnchor, forcedVisibleRowHeight);
			return TABLE.ScrollPositionProvider.getScrollPositionInRange(rangeTopBound, rangeBottomBound, verticalScrollPosition);
		},
		
		getScrollPositionInRange:function(rangeLowerBound, rangeUpperBound, centeredScrollPosition){
			return Math.min(Math.max(centeredScrollPosition, rangeLowerBound), rangeUpperBound);
		},
		
		getHorizontalScrollPosition:function(sliceManager, clientDisplayData) {
			var flexViewportScrollPosition;
			if(TABLE.ScrollPositionProvider.existsFlexViewport(sliceManager)) {
				if(TABLE.ScrollPositionProvider.shallScrollHorizontalPaneBased(sliceManager, clientDisplayData)) {
					flexViewportScrollPosition = TABLE.ScrollPositionProvider.getPaneBasedFlexPartHorizontalScrollPosition(sliceManager, clientDisplayData);
				} else {
					flexViewportScrollPosition = TABLE.ScrollPositionProvider.getNonPaneBasedFlexPartHorizontalScrollPosition(sliceManager, clientDisplayData);
				}
				return TABLE.ScrollPositionProvider.convertToScrollbarScrollPosition(sliceManager, flexViewportScrollPosition);
			} else {
				return 0;
			}
		},
		
		shallScrollHorizontalPaneBased:function(sliceManager, clientDisplayData) {
			return TABLE.ScrollPositionProvider.isHorizontalRangeDefined(clientDisplayData) && TABLE.ScrollPositionProvider.isRangeFullyInFlexPart(sliceManager, clientDisplayData);
		},
		
		isStoredHorizontalScrollPositionDefined:function(clientDisplayData) {
			return clientDisplayData.viewportState.columnAnchor.index > -1;
		},
		
		getNonPaneBasedFlexPartHorizontalScrollPosition:function(sliceManager, clientDisplayData) {
			if(TABLE.ScrollPositionProvider.isStoredHorizontalScrollPositionDefined(clientDisplayData)) {
				return TABLE.ScrollPositionProvider.getStoredHorizontalScrollPosition(sliceManager, clientDisplayData);
			} else {
				return 0;
			}
		},
		
		getStoredHorizontalScrollPosition:function(sliceManager, clientDisplayData) {
			var flexColumnAnchor = TABLE.ScrollPositionProvider.getColumnAnchorInFlexPart(sliceManager, clientDisplayData);
			var visibleColumnPosition = TABLE.ScrollPositionProvider.getOffsetFromLeft(sliceManager, 0, flexColumnAnchor.index);
			var visibleColumnOffset = flexColumnAnchor.indexPixelOffset;
			return TABLE.ScrollPositionProvider.getStoredScrollPosition(visibleColumnPosition, visibleColumnOffset);
		},
		
		convertToScrollbarScrollPosition:function(sliceManager, flexViewportScrollPosition) {
			var flexViewport = TABLE.ScrollPositionProvider.getFlexViewport(sliceManager);
			var flexWidth = flexViewport.getVisibleWidth();
			var availableWidth = TABLE.getAvailableWidth(sliceManager.getTableID());
			return flexViewportScrollPosition * (availableWidth / flexWidth);
		},
		
		isHorizontalRangeDefined:function(clientDisplayData) {
			return clientDisplayData.visiblePane.columnRange.firstIndex > -1;
		},
		
		getPaneBasedFlexPartHorizontalScrollPosition:function(sliceManager, clientDisplayData) {
			if(TABLE.ScrollPositionProvider.isPaneSmallerThanViewportWidth(sliceManager, clientDisplayData)) {
				return TABLE.ScrollPositionProvider.getHorizontalScrollPositionForFullyDisplayablePane(sliceManager, clientDisplayData);
			} else {
				return TABLE.ScrollPositionProvider.getHorizontalScrollPositionForForcedVisibleColumn(sliceManager, clientDisplayData);
			}
		},
		
		isPaneSmallerThanViewportWidth:function(sliceManager, clientDisplayData) {
			var flexViewport = TABLE.ScrollPositionProvider.getFlexViewport(sliceManager);
			return flexViewport.getVisibleWidth() > TABLE.ScrollPositionProvider.getFlexPaneWidth(sliceManager, clientDisplayData);
		},
		
		getHorizontalScrollPositionForFullyDisplayablePane:function(sliceManager, clientDisplayData) {
			var paneOffsetFromLeft = TABLE.ScrollPositionProvider.getPaneOffsetFromFlexLeft(sliceManager, clientDisplayData);
			var paneWidth = TABLE.ScrollPositionProvider.getFlexPaneWidth(sliceManager, clientDisplayData);
			return TABLE.ScrollPositionProvider.getPaneAndPreviousScrollingBasedHorizontalPosition(sliceManager, clientDisplayData, paneOffsetFromLeft, paneWidth);
		},
		
		getPaneAndPreviousScrollingBasedHorizontalPosition:function(sliceManager, clientDisplayData, visibleRangeOffset, visibleRangeWidth) {
			var flexViewport = TABLE.ScrollPositionProvider.getFlexViewport(sliceManager);
			var visibleViewportWidth = flexViewport.getVisibleWidth();
			if(TABLE.ScrollPositionProvider.isStoredHorizontalScrollPositionDefined(clientDisplayData)) {
				var horizontalScrollOffset = TABLE.ScrollPositionProvider.getStoredHorizontalScrollPosition(sliceManager, clientDisplayData);
				return TABLE.ScrollPositionProvider.getPaneAndPreviousScrollingBasedPosition(visibleRangeWidth, visibleRangeOffset, visibleViewportWidth, horizontalScrollOffset);
			} else {
				return TABLE.ScrollPositionProvider.getCenteredScrollPosition(visibleRangeWidth, visibleRangeOffset, visibleViewportWidth);
			}
		},
		
		getHorizontalScrollPositionForForcedVisibleColumn:function(sliceManager, clientDisplayData) {
			var flexViewport = TABLE.ScrollPositionProvider.getFlexViewport(sliceManager);
			var leftBound = TABLE.ScrollPositionProvider.getPaneOffsetFromFlexLeft(sliceManager, clientDisplayData);
			var rightBound = TABLE.ScrollPositionProvider.getPaneEndOffsetFromFlexLeft(sliceManager, clientDisplayData) - flexViewport.getVisibleWidth();
			var offsetFromLeft = TABLE.ScrollPositionProvider.getForcedVisibleColumnOffsetFromFlexLeft(sliceManager, clientDisplayData);
			var width = TABLE.ScrollPositionProvider.getForcedVisibleColumnWidth(sliceManager, clientDisplayData);
			var horizontalScrollPosition = TABLE.ScrollPositionProvider.getPaneAndPreviousScrollingBasedHorizontalPosition(sliceManager, clientDisplayData, offsetFromLeft, width);
			return TABLE.ScrollPositionProvider.getScrollPositionInRange(leftBound, rightBound, horizontalScrollPosition);
		},
		
		getPaneOffsetFromFlexLeft:function(sliceManager, clientDisplayData) {
			var flexRange = TABLE.ScrollPositionProvider.getRangeInFlexPart(sliceManager, clientDisplayData);
			return TABLE.ScrollPositionProvider.getOffsetFromLeft(sliceManager, 0, flexRange.firstIndex);
		},
		
		getOffsetFromLeft:function(sliceManager, startIndex, stopIndex) {
			var flexViewport = TABLE.ScrollPositionProvider.getFlexViewport(sliceManager);
			var offset = 0;
			if(flexViewport != null) {
				for(var i = startIndex; i < stopIndex && i < flexViewport.getColumnCount(); i++) {
					offset += flexViewport.getColumnWidth(i);
				}
			}
			
			return offset;
		},
		
		getForcedVisibleColumnOffsetFromFlexLeft:function(sliceManager, clientDisplayData) {
			var flexRange = TABLE.ScrollPositionProvider.getRangeInFlexPart(sliceManager, clientDisplayData);
			return TABLE.ScrollPositionProvider.getOffsetFromLeft(sliceManager, 0, flexRange.forcedVisibleIndexInRange);
		},
		
		getPaneEndOffsetFromFlexLeft:function(sliceManager, clientDisplayData) {
			var flexRange = TABLE.ScrollPositionProvider.getRangeInFlexPart(sliceManager, clientDisplayData);
			return TABLE.ScrollPositionProvider.getOffsetFromLeft(sliceManager, 0, flexRange.lastIndex + 1);
		},
		
		getRangeInFlexPart:function(sliceManager, clientDisplayData) {
			var columnRange = clientDisplayData.visiblePane.columnRange;
			var fixViewportColumnCount = TABLE.ScrollPositionProvider.getFixViewportColumnCount(sliceManager);
			var firstFlexIndex = columnRange.firstIndex - fixViewportColumnCount;
			var lastFlexIndex = columnRange.lastIndex - fixViewportColumnCount;
			var forcedVisibleFlexIndexInRange = columnRange.forcedVisibleIndexInRange - fixViewportColumnCount;
			
			return { firstIndex:firstFlexIndex, lastIndex:lastFlexIndex,
				forcedVisibleIndexInRange:forcedVisibleFlexIndexInRange };
		},
		
		getColumnAnchorInFlexPart:function(sliceManager, clientDisplayData) {
			var columnAnchor = clientDisplayData.viewportState.columnAnchor;
			var fixViewportColumnCount = TABLE.ScrollPositionProvider.getFixViewportColumnCount(sliceManager);
			var flexColumnIndex = columnAnchor.index - fixViewportColumnCount;
			
			return { index:flexColumnIndex, indexPixelOffset:columnAnchor.indexPixelOffset };
		},
		
		getFixViewportColumnCount:function(sliceManager) {
			if(TABLE.ScrollPositionProvider.existsFixViewport(sliceManager)) {
				return TABLE.ScrollPositionProvider.getFixViewport(sliceManager).getColumnCount();
			} else {
				return 0;
			}
		},
		
		existsFixViewport:function(sliceManager) {
			var fixViewport = TABLE.ScrollPositionProvider.getFixViewport(sliceManager);
			return fixViewport != undefined && fixViewport != null;
		},
		
		existsFlexViewport:function(sliceManager) {
			var flexViewport = TABLE.ScrollPositionProvider.getFlexViewport(sliceManager);
			return flexViewport != undefined && flexViewport != null;
		},
		
		getFlexPaneWidth:function(sliceManager, clientDisplayData) {
			var flexRange = TABLE.ScrollPositionProvider.getRangeInFlexPart(sliceManager, clientDisplayData);
			return TABLE.ScrollPositionProvider.getOffsetFromLeft(sliceManager, flexRange.firstIndex, flexRange.lastIndex + 1);
		},
		
		getForcedVisibleColumnWidth:function(sliceManager, clientDisplayData) {
			var flexViewport = TABLE.ScrollPositionProvider.getFlexViewport(sliceManager);
			var flexRange = TABLE.ScrollPositionProvider.getRangeInFlexPart(sliceManager, clientDisplayData);
			return flexViewport.getColumnWidth(flexRange.forcedVisibleIndexInRange);
		},
		
		getFlexViewport:function(sliceManager) {
			var flexViewportID = TABLE.getViewportIDForTablePartWithID(TABLE.getFlexBodyID(sliceManager.getTableID()));
			return sliceManager.getViewport(flexViewportID);
		},
		
		getFixViewport:function(sliceManager) {
			var fixViewportID = TABLE.getViewportIDForTablePartWithID(TABLE.getFixBodyID(sliceManager.getTableID()));
			return sliceManager.getViewport(fixViewportID);
		},
		
		isRangeFullyInFlexPart:function(sliceManager, clientDisplayData) {
			var columnRange = clientDisplayData.visiblePane.columnRange;
			var fixViewport = TABLE.ScrollPositionProvider.getFixViewport(sliceManager);
			return !TABLE.ScrollPositionProvider.existsFixViewport(sliceManager)
			       || columnRange.firstIndex >= fixViewport.getColumnCount();
		},
		
		getTopLeftPosition:function(sliceManager) {
			var horizontalScrollPosition = 0;
			var verticalScrollPosition = sliceManager.getRangeStartPosition();
			return TABLE.ScrollPositionProvider.getFormattedPositions(horizontalScrollPosition, verticalScrollPosition);
		},
		
		getFormattedPositions:function(horizontalScrollPosition, verticalScrollPosition) {
			return { horizontal:horizontalScrollPosition, vertical:verticalScrollPosition };
		}
};

/**************************************************************************************
 * 																					  *
 * 						VisibleAnchorCalculator										  *
 * 																					  *
 * 																					  *
 **************************************************************************************/
TABLE.VisiblePositionCalculator = {
	getPosition:function(viewport) {
		return {horizontal: TABLE.VisiblePositionCalculator.getHorizontalPosition(viewport),
				vertical: TABLE.VisiblePositionCalculator.getVerticalPosition(viewport)};
	},
	
	getVerticalPosition:function(viewport) {
		return {rowAnchor:TABLE.VisiblePositionCalculator.getRowAnchor(viewport),
			    rowAnchorOffset:TABLE.VisiblePositionCalculator.getRowAnchorOffset(viewport)};
	},
	
	getRowAnchor:function(viewport) {
		var sliceManager = TABLE.VisiblePositionCalculator.getSliceManager(viewport);
		if(sliceManager.getRowHeight() > 0) {
			return Math.floor(viewport.getVerticalScrollingPosition() / sliceManager.getRowHeight()) + sliceManager.getFirstPageRow();
		} else {
			return sliceManager.getFirstPageRow();
		}
	},
	
	getSliceManager:function(viewport) {
		return TABLE.tableSliceManagers[viewport.getTableID()];
	},
	
	getRowAnchorOffset:function(viewport) {
		var sliceManager = TABLE.VisiblePositionCalculator.getSliceManager(viewport);
		if(sliceManager.getRowHeight() > 0) {
			return Math.round(viewport.getVerticalScrollingPosition() % sliceManager.getRowHeight());
		} else {
			return 0;
		}
	},
	
	getHorizontalPosition:function(viewport) {
		var columnAnchorData = TABLE.VisiblePositionCalculator.getColumnAnchorAndOffsetFromLeft(viewport);
		var columnAnchorOffset = TABLE.VisiblePositionCalculator.getColumnAnchorOffset(viewport, columnAnchorData.offsetFromLeft);
		return {columnAnchor:columnAnchorData.columnAnchor,
				columnAnchorOffset:columnAnchorOffset};
	},
	
	getColumnAnchorAndOffsetFromLeft:function(viewport) {
		var columnAnchor = 0;
		var offsetFromLeft = 0;
		for(; columnAnchor < viewport.getColumnCount(); columnAnchor++) {
			if(offsetFromLeft + viewport.getColumnWidth(columnAnchor) < viewport.getHorizontalScrollingPosition()) {
				offsetFromLeft += viewport.getColumnWidth(columnAnchor);
			} else {
				break;
			}
		}
		
		return {columnAnchor:columnAnchor + viewport.getColumnIDOffset(),
			    offsetFromLeft:offsetFromLeft};
	},
	
	getColumnAnchorOffset:function(viewport, offsetFromLeft) {
		return Math.round(viewport.getHorizontalScrollingPosition() - offsetFromLeft);
	}
};;
// Compiled from 'script/placeDialog.js'
/**
 *  Places the 'content' relative to the position of 'element' and shows it.
 *  The position of the 'content', above, below, right, or left from the element
 *  depends on the size of the viewport and the size of the 'content'.
 * 
 *  Copyright (c) 2008 Business Operation Systems GmbH. All Rights Reserved.
 */ 

PlaceDialog = {

    removeProperty: "removeFunction",

    placeDialog: function(argObj){
        var element = argObj.element;
        var content = argObj.content;
        var additionalMoveRight = argObj.additionalMoveRight;
		if (additionalMoveRight == undefined) {
            additionalMoveRight = 0;
		}
        var additionalMoveDown = argObj.additionalMoveDown;
		if (additionalMoveDown == undefined) {
            additionalMoveDown = 0;
		}
		var preferBelow = argObj.preferBelow;
		if (preferBelow == undefined) {
            preferBelow = true;
		}

        //Determine the position of the element and place the content
		var elementPos = null;
		var elementWidth = 0;
		var elementHeight = 0;
        if (element != null) {
        	elementPos = this._getElementPositionRelativeToOutermostWindow(element);
        	elementWidth = BAL.getElementWidth(element);
        	elementHeight = BAL.getElementHeight(element);
        }
        this._placeElement(content, elementPos, elementWidth, elementHeight, preferBelow, additionalMoveRight, additionalMoveDown);

		content.style.display = 'block';
    },
       
    /**
     *  this method returns the position of the upper left corner of the element
     *  computed in the outermost window.
     */
    _getElementPositionRelativeToOutermostWindow: function(element) {
        
        var currentWindow = window;

        var absPosRelToWin = BAL.getAbsoluteElementPosition(element);
        
        while (currentWindow != services.ajax.topWindow) {
            absPosRelToWin.x -= BAL.getScrollLeft(currentWindow);
            absPosRelToWin.y -= BAL.getScrollTop(currentWindow);
            
            // The frame DOM element corresponding to currentWindow within the 
            // currentWindow's parent window.
            var theFrame = null;
             
            var found = false;
            var iFrames = currentWindow.parent.document.getElementsByTagName('IFRAME');
            var frames = currentWindow.parent.document.getElementsByTagName('FRAME');
            for (var i = 0; i < frames.length && !found; i++) {
                theFrame = frames[i];
                if (theFrame.contentWindow == currentWindow) {
                    found = true;
                }
            }
            for (var i = 0; i < iFrames.length && !found; i++) {
                theFrame = iFrames[i];
                if (theFrame.contentWindow == currentWindow) {
                    found = true;
                }
            }
            if (!found){
                throw new Error('Frame not found');
            }
            
            currentWindow = currentWindow.parent;
            
            var absoluteFramePosition = BAL.getAbsoluteElementPosition(theFrame);
            absPosRelToWin.x += absoluteFramePosition.x;
            absPosRelToWin.y += absoluteFramePosition.y;
        }
        return {
                left : absPosRelToWin.x,
                top : absPosRelToWin.y
        };
    },

    /**
     *  this method returns the position where the event occured
     *  computed in the outermost window.
     */
    _getEventPositionRelativeToOutermostWindow: function(event) {
        
        var currentWindow = window;

        var absPosRelToWin = {x: BAL.getEventX(event), y: BAL.getEventY(event)} ;
        
        while (currentWindow != services.ajax.topWindow) {
            absPosRelToWin.x -= BAL.getScrollLeft(currentWindow);
            absPosRelToWin.y -= BAL.getScrollTop(currentWindow);
            
            // The frame DOM element corresponding to currentWindow within the 
            // currentWindow's parent window.
            var theFrame = null;
             
            var found = false;
            var iFrames = currentWindow.parent.document.getElementsByTagName('IFRAME');
            var frames = currentWindow.parent.document.getElementsByTagName('FRAME');
            for (var i = 0; i < frames.length && !found; i++) {
                theFrame = frames[i];
                if (theFrame.contentWindow == currentWindow) {
                    found = true;
                }
            }
            for (var i = 0; i < iFrames.length && !found; i++) {
                theFrame = iFrames[i];
                if (theFrame.contentWindow == currentWindow) {
                    found = true;
                }
            }
            if (!found){
                throw new Error('Frame not found');
            }
            
            currentWindow = currentWindow.parent;
            
            var absoluteFramePosition = BAL.getAbsoluteElementPosition(theFrame);
            absPosRelToWin.x += absoluteFramePosition.x;
            absPosRelToWin.y += absoluteFramePosition.y;
        }
        return {
                left : absPosRelToWin.x,
                top : absPosRelToWin.y
        };
    },
    
    _placeElement: function(dialog, anchorPosition, anchorWidth, anchorHeight, preferBelow, additionalShiftHorizontal, additionalShiftVertical) {
        var leftPosition = -1, topPosition = -1;
    	dialog.style.position = 'absolute';

            // if possible place the dialog right, otherwise left 
        var dialogWidth = BAL.getElementWidth(dialog);
        var viewportWidth = BAL.getViewportWidthOfWindowObject(services.ajax.topWindow);
            // enable scrolling if dialogWidth is to large
        if (dialogWidth > viewportWidth) { 
            dialog.style.overflow = 'auto';
            BAL.setElementWidth(dialog, viewportWidth);
        }
        
        if (anchorPosition != null) {
        	if ((anchorPosition.left + dialogWidth + additionalShiftHorizontal) < viewportWidth){
        		leftPosition = anchorPosition.left + additionalShiftHorizontal;
        	} else{
        		leftPosition = anchorPosition.left + anchorWidth - dialogWidth - additionalShiftHorizontal;
        	}
        }

            // if possible place the dialog above, otherwise below 
        var dialogHeight = BAL.getElementHeight(dialog);
        var viewportHeight = BAL.getViewportHeightOfWindowObject(services.ajax.topWindow);
            // enable scrolling if dialogHeight is to large
        if (dialogHeight > viewportHeight) {
            dialog.style.overflow = 'auto';
            BAL.setElementHeight(dialog, viewportHeight);
        }
        
        if (anchorPosition != null) {
	        if (preferBelow) {
	            if ((anchorPosition.top + anchorHeight + dialogHeight + additionalShiftVertical) < viewportHeight) {
	                topPosition = anchorPosition.top + anchorHeight + additionalShiftVertical;
	            } else{
	                topPosition = anchorPosition.top - dialogHeight - additionalShiftVertical;
	            }
	        }
	        else {
	            if ((anchorPosition.top - dialogHeight - additionalShiftVertical) >= 0) {
	                topPosition = anchorPosition.top - dialogHeight - additionalShiftVertical;
	            } else {
	                var lowestPoint = anchorPosition.top + anchorHeight + dialogHeight + additionalShiftVertical;
	                if (lowestPoint < viewportHeight) {
	                    topPosition = anchorPosition.top + anchorHeight + additionalShiftVertical;
	                } else {
	                    topPosition = -1;
	                }
	            }
	        }
        }
        
        if(topPosition < 0) {
        	topPosition = viewportHeight / 2 - dialogHeight / 2;
        	
            if (anchorPosition != null) {
	        	if(anchorPosition.left + anchorWidth + additionalShiftHorizontal + dialogWidth > viewportWidth) {
	        		leftPosition = anchorPosition.left - additionalShiftHorizontal - dialogWidth;
	        	} else {
	        		leftPosition = anchorPosition.left + anchorWidth + additionalShiftHorizontal;
	        	}
            }
        }
        
        if(leftPosition < 0) {
        	leftPosition = viewportWidth / 2 - dialogWidth / 2;
        }
        
        dialog.style.left = leftPosition + 'px';
        dialog.style.top = topPosition + 'px';
    },
    
    tooltipId: "tooltip",
    
    makeTooltip: function(argObj){
    	var tooltipTimeoutId = null;
        var element = argObj.element;
        var content = argObj.content;
        var tooltipClasses = argObj.tooltipClasses;
        if (tooltipClasses == undefined) {
            tooltipClasses = new Array();
            tooltipClasses.push('tooltip');
        }
        var delay = argObj.delay;
        if (delay == undefined) {
            delay = 250;
        }
        var preferBelow = argObj.preferBelow;
        if (preferBelow == undefined) {
            preferBelow = false;
        }
        var additionalShiftHorizontal = argObj.additionalShiftHorizontal;
        if (additionalShiftHorizontal == undefined) {
            additionalShiftHorizontal = 10;
        }
        var additionalShiftVertical = argObj.additionalShiftVertical;
        if (additionalShiftVertical == undefined) {
            additionalShiftVertical = 4;
        }
        var event = BAL.getEvent(argObj.event);
        var eventPos = this._getEventPositionRelativeToOutermostWindow(event);
        
        var outermostWindow = services.ajax.topWindow;
        var placeDialogObject = this;

        var mouseMove = function(event) {
        	var balEvent = BAL.getEvent(event);
        	eventPos = placeDialogObject._getEventPositionRelativeToOutermostWindow(balEvent);
        };
        var cancelTooltip = function() {
        	outermostWindow.clearTimeout(tooltipTimeoutId);
        	removeTooltipTriggerListener();
        };
        var delayedFunction = function() {
        	removeTooltipTriggerListener();
            placeDialogObject._makeTooltip(element, content, tooltipClasses, preferBelow, additionalShiftHorizontal, additionalShiftVertical, eventPos);
        };
        var removeTooltipTriggerListener = function() {
        	BAL.removeEventListener(document, 'mousemove', mouseMove);
        	BAL.removeEventListener(element, 'mousedown', cancelTooltip);
        	BAL.removeEventListener(element, 'mouseout', cancelTooltip);
        	BAL.removeEventListener(element, 'DOMNodeRemovedFromDocument', cancelTooltip); 
        };
        
        BAL.addEventListener(document, 'mousemove', mouseMove);
        BAL.addEventListener(element, 'mouseout', cancelTooltip);
        BAL.addEventListener(element, 'mousedown', cancelTooltip);
        BAL.addEventListener(element, 'DOMNodeRemovedFromDocument', cancelTooltip); 
        tooltipTimeoutId = outermostWindow.setTimeout(delayedFunction, delay);
    },
    
    removeTooltip: function() {
        var outermostDocument = services.ajax.topWindow.document;
        var oldTooltip = outermostDocument.getElementById(PlaceDialog.tooltipId);
        if (oldTooltip != undefined) {
            oldTooltip.parentNode.removeChild(oldTooltip);
        }
        
    },
    
    _makeTooltip: function (element, content, tooltipClasses, preferBelow, additionalShiftHorizontal, additionalShiftVertical, eventPos) {
        PlaceDialog.removeTooltip();
        var outermostDocument = services.ajax.topWindow.document;
        var tooltipDiv = outermostDocument.createElement('div');
        tooltipDiv.id = PlaceDialog.tooltipId;
        tooltipDiv.style.padding = '3px';
        tooltipDiv.innerHTML = content;
        BAL.DOM.setClassesArray(tooltipDiv, tooltipClasses);

        var onmouseout = function() {
            BAL.removeEventListener(element, 'mouseout', onmouseout);
            BAL.removeEventListener(element, 'mousedown', onmouseout);
            BAL.removeEventListener(element, 'DOMNodeRemovedFromDocument', onmouseout);
            PlaceDialog.removeTooltip();
            
            // Don't suppress standard action (e.g. checkboxes would not react to clicks and
            // therefore cannot become enabled or disabled). If any element needs to suppress the
            // default action, it has to be done by a separate event listener.
            // Also don't return true explicitly, because there is a different handling of event
            // bubbling between return values true and undefined. Despite both
            // return values don't prevent event bubbling, the former one undermines the above
            // mentioned possibility of event cancellation.
        };
        BAL.DOM.getFirstElementChild(outermostDocument.body).appendChild(tooltipDiv);
        BAL.addEventListener(element, 'mouseout', onmouseout);
        BAL.addEventListener(element, 'mousedown', onmouseout);
        BAL.addEventListener(element, 'DOMNodeRemovedFromDocument', onmouseout);
        
        if (!eventPos) {
	        var elementPos = this._getElementPositionRelativeToOutermostWindow(element);
	        this._placeTooltip(tooltipDiv, elementPos, BAL.getElementWidth(element), BAL.getElementHeight(element), preferBelow, additionalShiftHorizontal, additionalShiftVertical);
        } 
        else {
	        this._placeTooltip(tooltipDiv, eventPos, 0, 0, preferBelow, additionalShiftHorizontal, additionalShiftVertical);
        }
        
    },
    
    _placeTooltip: function(tooltip, anchorPosition, anchorWidth, anchorHeight, preferBelow, additionalShiftHorizontal, additionalShiftVertical) {
        var leftPosition, topPosition = 0;
        
    	tooltip.style.position = 'absolute';
    	tooltip.style.overflow = 'auto';

            // if possible place the tooltip right, otherwise left 
        var tooltipWidth = BAL.getElementWidth(tooltip);
        var viewportWidth = BAL.getViewportWidthOfWindowObject(services.ajax.topWindow);
        if ((anchorPosition.left + anchorWidth + tooltipWidth + additionalShiftHorizontal) < viewportWidth){
            leftPosition = anchorPosition.left + anchorWidth + additionalShiftHorizontal;
        } else{
            leftPosition = anchorPosition.left - tooltipWidth - additionalShiftHorizontal;
        }

            // if possible place the tooltip above, otherwise below 
        var tooltipHeight = BAL.getElementHeight(tooltip);
        var viewportHeight = BAL.getViewportHeightOfWindowObject(services.ajax.topWindow);
        if (preferBelow) {
            if ((anchorPosition.top + anchorHeight + tooltipHeight + additionalShiftVertical) < viewportHeight) {
                topPosition = anchorPosition.top + anchorHeight + additionalShiftVertical;
            } else{
                topPosition = anchorPosition.top - tooltipHeight - additionalShiftVertical;
            }
        }
        else {
            if ((anchorPosition.top - tooltipHeight - additionalShiftVertical) >= 0) {
                topPosition = anchorPosition.top - tooltipHeight - additionalShiftVertical;
            } else {
                var lowestPoint = anchorPosition.top + anchorHeight + tooltipHeight + additionalShiftVertical;
                if (lowestPoint < viewportHeight) {
                    topPosition = anchorPosition.top + anchorHeight + additionalShiftVertical;
                } else {
                    topPosition = anchorPosition.top - tooltipHeight - additionalShiftVertical;
                }
            }
        }
        
        if(leftPosition < 0 || topPosition < 0) {
        	var additionalSpace = 5;
        	if(leftPosition < 0 && topPosition < 0) {
        		var tooltipLocation;
        		if((tooltipWidth < viewportWidth && tooltipHeight < viewportHeight) ||
        			(tooltipWidth > viewportWidth && tooltipHeight > viewportHeight)) {
        			var horizontalOverlap = Math.abs(viewportWidth - tooltipWidth);
        			var verticalOverlap = Math.abs(viewportHeight - tooltipHeight);
        			if(horizontalOverlap < verticalOverlap) {
        				tooltipLocation = this._expandHorizontally(tooltip, tooltipWidth, tooltipHeight, anchorPosition, anchorHeight, viewportWidth, viewportHeight, additionalShiftVertical, additionalSpace);
        			} else {
        				tooltipLocation = this._expandVertically(tooltip, tooltipWidth, tooltipHeight, anchorPosition, anchorWidth, viewportWidth, viewportHeight, additionalShiftHorizontal, additionalSpace);
        			}
    			} else if(tooltipWidth < viewportWidth) {
    				tooltipLocation = this._expandHorizontally(tooltip, tooltipWidth, tooltipHeight, anchorPosition, anchorHeight, viewportWidth, viewportHeight, additionalShiftVertical, additionalSpace);
    			} else {
    				tooltipLocation = this._expandVertically(tooltip, tooltipWidth, tooltipHeight, anchorPosition, anchorWidth, viewportWidth, viewportHeight, additionalShiftHorizontal, additionalSpace);
    			}
        		leftPosition = tooltipLocation.leftPosition;
        		topPosition = tooltipLocation.topPosition;
        	} else if(leftPosition < 0) {
        		if(tooltipWidth > viewportWidth - 2 * additionalSpace) {
        			var constant = 2; /* arbitrary constant to avoid endless recursion, due to BAL.setElementWidth() and BAL.getElementWidth() are not strictly synchronous*/
        			BAL.setElementWidth(tooltip, viewportWidth - 2 * additionalSpace - constant);
        			this._placeTooltip(tooltip, anchorPosition, anchorWidth, anchorHeight, preferBelow, additionalShiftHorizontal, additionalShiftVertical)
        			return;
        		}
        		leftPosition = additionalSpace;
        	} else {
        		if(tooltipHeight > viewportHeight - 2 * additionalSpace) {
        			var constant = 2; /* arbitrary constant to avoid endless recursion, due to BAL.setElementWidth() and BAL.getElementWidth() are not strictly synchronous*/
        			BAL.setElementHeight(tooltip, viewportHeight - 2 * additionalSpace - constant);
        			this._placeTooltip(tooltip, anchorPosition, anchorWidth, anchorHeight, preferBelow, additionalShiftHorizontal, additionalShiftVertical)
        			return;
        		}
        		topPosition = additionalSpace;
        	}
        }
        
        tooltip.style.left = leftPosition + 'px';
        tooltip.style.top = topPosition + 'px';
    },
    
    _expandHorizontally: function(tooltip, tooltipWidth, tooltipHeight, anchorPosition, anchorHeight, viewportWidth, viewportHeight, additionalShiftVertical, additionalSpace) {
    	var leftPosition = additionalSpace;
    	var topPosition;
		if(tooltipWidth > viewportWidth - 2 * additionalSpace) {
			BAL.setElementWidth(tooltip, viewportWidth - 2 * additionalSpace);
		}
		var topSpace = anchorPosition.top - additionalShiftVertical;
		var bottomSpace = viewportHeight - anchorPosition.top - anchorHeight - additionalShiftVertical;
		if(topSpace > bottomSpace) {
			topPosition = additionalSpace;
			BAL.setElementHeight(tooltip, topSpace - additionalSpace);
		} else {
			topPosition = anchorPosition.top + additionalShiftVertical;
			BAL.setElementHeight(tooltip, bottomSpace - additionalSpace);
		}
    	return {leftPosition: leftPosition, topPosition: topPosition};
    },
    
    _expandVertically: function(tooltip, tooltipWidth, tooltipHeight, anchorPosition, anchorWidth, viewportWidth, viewportHeight, additionalShiftHorizontal, additionalSpace) {
    	var topPosition = additionalSpace;
    	var leftPosition;
		if(tooltipHeight > viewportHeight - 2 * additionalSpace) {
			BAL.setElementHeight(tooltip, viewportHeight - 2 * additionalSpace);
		}
		var leftSpace = anchorPosition.left - additionalShiftHorizontal;
		var rightSpace = viewportWidth - anchorPosition.left - anchorWidth - additionalShiftHorizontal;
		if(leftSpace > rightSpace) {
			leftPosition = additionalSpace;
			BAL.setElementWidth(tooltip, leftSpace - additionalSpace);
		} else {
			leftPosition = anchorPosition.left + additionalShiftHorizontal;
			BAL.setElementWidth(tooltip, rightSpace - additionalSpace);
		}
    	return {leftPosition: leftPosition, topPosition: topPosition};
    },
    
    showMenu: function(elementID, contentID, argObj) {
        var element = document.getElementById(elementID);
        var cancelClosing = function() {
	        if (element.timeoutID != null) {
	            window.clearTimeout(element.timeoutID);
	            element.timeoutID = null;
	        }
	    };
        if (element.timeoutID != null) {
            cancelClosing();
            return;
        }
        if (argObj == null) {
            argObj = new Object();
        }
        argObj.element = element;
        var content = document.getElementById(contentID);
        var oldParent = content.parentNode;
        var nextElementSibling = BAL.DOM.getNextElementSibling(content);

        var remove = function() {
        	
        	// hide the content before remove it (see #6369)
        	content.style.display = 'none';
        	
            document.body.removeChild(content);
            if (nextElementSibling == null) {
                oldParent.appendChild(content);
            } else {
                oldParent.insertBefore(content, nextElementSibling);
            }
            element.timeoutID = null;
            BAL.removeEventListener(element, 'mouseout', delayedHideFunction);
            BAL.removeEventListener(element, 'click', delayedHideFunction);
            BAL.removeEventListener(element, 'DOMNodeRemovedFromDocument', delayedHideFunction);
            
            BAL.removeEventListener(content, 'mouseout', delayedHideFunction);
            BAL.removeEventListener(content, 'click', delayedHideFunction);
            BAL.removeEventListener(content, 'mouseover', delayedHideFunction);
        };
        oldParent.removeChild(content);
        document.body.appendChild(content);
        argObj.content = content;
        PlaceDialog.placeDialog(argObj);
        var delayedHideFunction = function() {
			if (element.timeoutID == null) {
				element.timeoutID = window.setTimeout(remove, 100);
			}
        };
        BAL.addEventListener(element, 'mouseout', delayedHideFunction);
        BAL.addEventListener(element, 'click', delayedHideFunction);
        BAL.addEventListener(element, 'DOMNodeRemovedFromDocument', delayedHideFunction);
        
        BAL.addEventListener(content, 'mouseout', delayedHideFunction);
        BAL.addEventListener(content, 'click', delayedHideFunction);
        BAL.addEventListener(content, 'mouseover', cancelClosing);
    }
    
};
;
// Compiled from 'script/jquery-3.2.1.min.js'
/*! jQuery v3.2.1 | (c) JS Foundation and other contributors | jquery.org/license */
!function(a,b){"use strict";"object"==typeof module&&"object"==typeof module.exports?module.exports=a.document?b(a,!0):function(a){if(!a.document)throw new Error("jQuery requires a window with a document");return b(a)}:b(a)}("undefined"!=typeof window?window:this,function(a,b){"use strict";var c=[],d=a.document,e=Object.getPrototypeOf,f=c.slice,g=c.concat,h=c.push,i=c.indexOf,j={},k=j.toString,l=j.hasOwnProperty,m=l.toString,n=m.call(Object),o={};function p(a,b){b=b||d;var c=b.createElement("script");c.text=a,b.head.appendChild(c).parentNode.removeChild(c)}var q="3.2.1",r=function(a,b){return new r.fn.init(a,b)},s=/^[\s\uFEFF\xA0]+|[\s\uFEFF\xA0]+$/g,t=/^-ms-/,u=/-([a-z])/g,v=function(a,b){return b.toUpperCase()};r.fn=r.prototype={jquery:q,constructor:r,length:0,toArray:function(){return f.call(this)},get:function(a){return null==a?f.call(this):a<0?this[a+this.length]:this[a]},pushStack:function(a){var b=r.merge(this.constructor(),a);return b.prevObject=this,b},each:function(a){return r.each(this,a)},map:function(a){return this.pushStack(r.map(this,function(b,c){return a.call(b,c,b)}))},slice:function(){return this.pushStack(f.apply(this,arguments))},first:function(){return this.eq(0)},last:function(){return this.eq(-1)},eq:function(a){var b=this.length,c=+a+(a<0?b:0);return this.pushStack(c>=0&&c<b?[this[c]]:[])},end:function(){return this.prevObject||this.constructor()},push:h,sort:c.sort,splice:c.splice},r.extend=r.fn.extend=function(){var a,b,c,d,e,f,g=arguments[0]||{},h=1,i=arguments.length,j=!1;for("boolean"==typeof g&&(j=g,g=arguments[h]||{},h++),"object"==typeof g||r.isFunction(g)||(g={}),h===i&&(g=this,h--);h<i;h++)if(null!=(a=arguments[h]))for(b in a)c=g[b],d=a[b],g!==d&&(j&&d&&(r.isPlainObject(d)||(e=Array.isArray(d)))?(e?(e=!1,f=c&&Array.isArray(c)?c:[]):f=c&&r.isPlainObject(c)?c:{},g[b]=r.extend(j,f,d)):void 0!==d&&(g[b]=d));return g},r.extend({expando:"jQuery"+(q+Math.random()).replace(/\D/g,""),isReady:!0,error:function(a){throw new Error(a)},noop:function(){},isFunction:function(a){return"function"===r.type(a)},isWindow:function(a){return null!=a&&a===a.window},isNumeric:function(a){var b=r.type(a);return("number"===b||"string"===b)&&!isNaN(a-parseFloat(a))},isPlainObject:function(a){var b,c;return!(!a||"[object Object]"!==k.call(a))&&(!(b=e(a))||(c=l.call(b,"constructor")&&b.constructor,"function"==typeof c&&m.call(c)===n))},isEmptyObject:function(a){var b;for(b in a)return!1;return!0},type:function(a){return null==a?a+"":"object"==typeof a||"function"==typeof a?j[k.call(a)]||"object":typeof a},globalEval:function(a){p(a)},camelCase:function(a){return a.replace(t,"ms-").replace(u,v)},each:function(a,b){var c,d=0;if(w(a)){for(c=a.length;d<c;d++)if(b.call(a[d],d,a[d])===!1)break}else for(d in a)if(b.call(a[d],d,a[d])===!1)break;return a},trim:function(a){return null==a?"":(a+"").replace(s,"")},makeArray:function(a,b){var c=b||[];return null!=a&&(w(Object(a))?r.merge(c,"string"==typeof a?[a]:a):h.call(c,a)),c},inArray:function(a,b,c){return null==b?-1:i.call(b,a,c)},merge:function(a,b){for(var c=+b.length,d=0,e=a.length;d<c;d++)a[e++]=b[d];return a.length=e,a},grep:function(a,b,c){for(var d,e=[],f=0,g=a.length,h=!c;f<g;f++)d=!b(a[f],f),d!==h&&e.push(a[f]);return e},map:function(a,b,c){var d,e,f=0,h=[];if(w(a))for(d=a.length;f<d;f++)e=b(a[f],f,c),null!=e&&h.push(e);else for(f in a)e=b(a[f],f,c),null!=e&&h.push(e);return g.apply([],h)},guid:1,proxy:function(a,b){var c,d,e;if("string"==typeof b&&(c=a[b],b=a,a=c),r.isFunction(a))return d=f.call(arguments,2),e=function(){return a.apply(b||this,d.concat(f.call(arguments)))},e.guid=a.guid=a.guid||r.guid++,e},now:Date.now,support:o}),"function"==typeof Symbol&&(r.fn[Symbol.iterator]=c[Symbol.iterator]),r.each("Boolean Number String Function Array Date RegExp Object Error Symbol".split(" "),function(a,b){j["[object "+b+"]"]=b.toLowerCase()});function w(a){var b=!!a&&"length"in a&&a.length,c=r.type(a);return"function"!==c&&!r.isWindow(a)&&("array"===c||0===b||"number"==typeof b&&b>0&&b-1 in a)}var x=function(a){var b,c,d,e,f,g,h,i,j,k,l,m,n,o,p,q,r,s,t,u="sizzle"+1*new Date,v=a.document,w=0,x=0,y=ha(),z=ha(),A=ha(),B=function(a,b){return a===b&&(l=!0),0},C={}.hasOwnProperty,D=[],E=D.pop,F=D.push,G=D.push,H=D.slice,I=function(a,b){for(var c=0,d=a.length;c<d;c++)if(a[c]===b)return c;return-1},J="checked|selected|async|autofocus|autoplay|controls|defer|disabled|hidden|ismap|loop|multiple|open|readonly|required|scoped",K="[\\x20\\t\\r\\n\\f]",L="(?:\\\\.|[\\w-]|[^\0-\\xa0])+",M="\\["+K+"*("+L+")(?:"+K+"*([*^$|!~]?=)"+K+"*(?:'((?:\\\\.|[^\\\\'])*)'|\"((?:\\\\.|[^\\\\\"])*)\"|("+L+"))|)"+K+"*\\]",N=":("+L+")(?:\\((('((?:\\\\.|[^\\\\'])*)'|\"((?:\\\\.|[^\\\\\"])*)\")|((?:\\\\.|[^\\\\()[\\]]|"+M+")*)|.*)\\)|)",O=new RegExp(K+"+","g"),P=new RegExp("^"+K+"+|((?:^|[^\\\\])(?:\\\\.)*)"+K+"+$","g"),Q=new RegExp("^"+K+"*,"+K+"*"),R=new RegExp("^"+K+"*([>+~]|"+K+")"+K+"*"),S=new RegExp("="+K+"*([^\\]'\"]*?)"+K+"*\\]","g"),T=new RegExp(N),U=new RegExp("^"+L+"$"),V={ID:new RegExp("^#("+L+")"),CLASS:new RegExp("^\\.("+L+")"),TAG:new RegExp("^("+L+"|[*])"),ATTR:new RegExp("^"+M),PSEUDO:new RegExp("^"+N),CHILD:new RegExp("^:(only|first|last|nth|nth-last)-(child|of-type)(?:\\("+K+"*(even|odd|(([+-]|)(\\d*)n|)"+K+"*(?:([+-]|)"+K+"*(\\d+)|))"+K+"*\\)|)","i"),bool:new RegExp("^(?:"+J+")$","i"),needsContext:new RegExp("^"+K+"*[>+~]|:(even|odd|eq|gt|lt|nth|first|last)(?:\\("+K+"*((?:-\\d)?\\d*)"+K+"*\\)|)(?=[^-]|$)","i")},W=/^(?:input|select|textarea|button)$/i,X=/^h\d$/i,Y=/^[^{]+\{\s*\[native \w/,Z=/^(?:#([\w-]+)|(\w+)|\.([\w-]+))$/,$=/[+~]/,_=new RegExp("\\\\([\\da-f]{1,6}"+K+"?|("+K+")|.)","ig"),aa=function(a,b,c){var d="0x"+b-65536;return d!==d||c?b:d<0?String.fromCharCode(d+65536):String.fromCharCode(d>>10|55296,1023&d|56320)},ba=/([\0-\x1f\x7f]|^-?\d)|^-$|[^\0-\x1f\x7f-\uFFFF\w-]/g,ca=function(a,b){return b?"\0"===a?"\ufffd":a.slice(0,-1)+"\\"+a.charCodeAt(a.length-1).toString(16)+" ":"\\"+a},da=function(){m()},ea=ta(function(a){return a.disabled===!0&&("form"in a||"label"in a)},{dir:"parentNode",next:"legend"});try{G.apply(D=H.call(v.childNodes),v.childNodes),D[v.childNodes.length].nodeType}catch(fa){G={apply:D.length?function(a,b){F.apply(a,H.call(b))}:function(a,b){var c=a.length,d=0;while(a[c++]=b[d++]);a.length=c-1}}}function ga(a,b,d,e){var f,h,j,k,l,o,r,s=b&&b.ownerDocument,w=b?b.nodeType:9;if(d=d||[],"string"!=typeof a||!a||1!==w&&9!==w&&11!==w)return d;if(!e&&((b?b.ownerDocument||b:v)!==n&&m(b),b=b||n,p)){if(11!==w&&(l=Z.exec(a)))if(f=l[1]){if(9===w){if(!(j=b.getElementById(f)))return d;if(j.id===f)return d.push(j),d}else if(s&&(j=s.getElementById(f))&&t(b,j)&&j.id===f)return d.push(j),d}else{if(l[2])return G.apply(d,b.getElementsByTagName(a)),d;if((f=l[3])&&c.getElementsByClassName&&b.getElementsByClassName)return G.apply(d,b.getElementsByClassName(f)),d}if(c.qsa&&!A[a+" "]&&(!q||!q.test(a))){if(1!==w)s=b,r=a;else if("object"!==b.nodeName.toLowerCase()){(k=b.getAttribute("id"))?k=k.replace(ba,ca):b.setAttribute("id",k=u),o=g(a),h=o.length;while(h--)o[h]="#"+k+" "+sa(o[h]);r=o.join(","),s=$.test(a)&&qa(b.parentNode)||b}if(r)try{return G.apply(d,s.querySelectorAll(r)),d}catch(x){}finally{k===u&&b.removeAttribute("id")}}}return i(a.replace(P,"$1"),b,d,e)}function ha(){var a=[];function b(c,e){return a.push(c+" ")>d.cacheLength&&delete b[a.shift()],b[c+" "]=e}return b}function ia(a){return a[u]=!0,a}function ja(a){var b=n.createElement("fieldset");try{return!!a(b)}catch(c){return!1}finally{b.parentNode&&b.parentNode.removeChild(b),b=null}}function ka(a,b){var c=a.split("|"),e=c.length;while(e--)d.attrHandle[c[e]]=b}function la(a,b){var c=b&&a,d=c&&1===a.nodeType&&1===b.nodeType&&a.sourceIndex-b.sourceIndex;if(d)return d;if(c)while(c=c.nextSibling)if(c===b)return-1;return a?1:-1}function ma(a){return function(b){var c=b.nodeName.toLowerCase();return"input"===c&&b.type===a}}function na(a){return function(b){var c=b.nodeName.toLowerCase();return("input"===c||"button"===c)&&b.type===a}}function oa(a){return function(b){return"form"in b?b.parentNode&&b.disabled===!1?"label"in b?"label"in b.parentNode?b.parentNode.disabled===a:b.disabled===a:b.isDisabled===a||b.isDisabled!==!a&&ea(b)===a:b.disabled===a:"label"in b&&b.disabled===a}}function pa(a){return ia(function(b){return b=+b,ia(function(c,d){var e,f=a([],c.length,b),g=f.length;while(g--)c[e=f[g]]&&(c[e]=!(d[e]=c[e]))})})}function qa(a){return a&&"undefined"!=typeof a.getElementsByTagName&&a}c=ga.support={},f=ga.isXML=function(a){var b=a&&(a.ownerDocument||a).documentElement;return!!b&&"HTML"!==b.nodeName},m=ga.setDocument=function(a){var b,e,g=a?a.ownerDocument||a:v;return g!==n&&9===g.nodeType&&g.documentElement?(n=g,o=n.documentElement,p=!f(n),v!==n&&(e=n.defaultView)&&e.top!==e&&(e.addEventListener?e.addEventListener("unload",da,!1):e.attachEvent&&e.attachEvent("onunload",da)),c.attributes=ja(function(a){return a.className="i",!a.getAttribute("className")}),c.getElementsByTagName=ja(function(a){return a.appendChild(n.createComment("")),!a.getElementsByTagName("*").length}),c.getElementsByClassName=Y.test(n.getElementsByClassName),c.getById=ja(function(a){return o.appendChild(a).id=u,!n.getElementsByName||!n.getElementsByName(u).length}),c.getById?(d.filter.ID=function(a){var b=a.replace(_,aa);return function(a){return a.getAttribute("id")===b}},d.find.ID=function(a,b){if("undefined"!=typeof b.getElementById&&p){var c=b.getElementById(a);return c?[c]:[]}}):(d.filter.ID=function(a){var b=a.replace(_,aa);return function(a){var c="undefined"!=typeof a.getAttributeNode&&a.getAttributeNode("id");return c&&c.value===b}},d.find.ID=function(a,b){if("undefined"!=typeof b.getElementById&&p){var c,d,e,f=b.getElementById(a);if(f){if(c=f.getAttributeNode("id"),c&&c.value===a)return[f];e=b.getElementsByName(a),d=0;while(f=e[d++])if(c=f.getAttributeNode("id"),c&&c.value===a)return[f]}return[]}}),d.find.TAG=c.getElementsByTagName?function(a,b){return"undefined"!=typeof b.getElementsByTagName?b.getElementsByTagName(a):c.qsa?b.querySelectorAll(a):void 0}:function(a,b){var c,d=[],e=0,f=b.getElementsByTagName(a);if("*"===a){while(c=f[e++])1===c.nodeType&&d.push(c);return d}return f},d.find.CLASS=c.getElementsByClassName&&function(a,b){if("undefined"!=typeof b.getElementsByClassName&&p)return b.getElementsByClassName(a)},r=[],q=[],(c.qsa=Y.test(n.querySelectorAll))&&(ja(function(a){o.appendChild(a).innerHTML="<a id='"+u+"'></a><select id='"+u+"-\r\\' msallowcapture=''><option selected=''></option></select>",a.querySelectorAll("[msallowcapture^='']").length&&q.push("[*^$]="+K+"*(?:''|\"\")"),a.querySelectorAll("[selected]").length||q.push("\\["+K+"*(?:value|"+J+")"),a.querySelectorAll("[id~="+u+"-]").length||q.push("~="),a.querySelectorAll(":checked").length||q.push(":checked"),a.querySelectorAll("a#"+u+"+*").length||q.push(".#.+[+~]")}),ja(function(a){a.innerHTML="<a href='' disabled='disabled'></a><select disabled='disabled'><option/></select>";var b=n.createElement("input");b.setAttribute("type","hidden"),a.appendChild(b).setAttribute("name","D"),a.querySelectorAll("[name=d]").length&&q.push("name"+K+"*[*^$|!~]?="),2!==a.querySelectorAll(":enabled").length&&q.push(":enabled",":disabled"),o.appendChild(a).disabled=!0,2!==a.querySelectorAll(":disabled").length&&q.push(":enabled",":disabled"),a.querySelectorAll("*,:x"),q.push(",.*:")})),(c.matchesSelector=Y.test(s=o.matches||o.webkitMatchesSelector||o.mozMatchesSelector||o.oMatchesSelector||o.msMatchesSelector))&&ja(function(a){c.disconnectedMatch=s.call(a,"*"),s.call(a,"[s!='']:x"),r.push("!=",N)}),q=q.length&&new RegExp(q.join("|")),r=r.length&&new RegExp(r.join("|")),b=Y.test(o.compareDocumentPosition),t=b||Y.test(o.contains)?function(a,b){var c=9===a.nodeType?a.documentElement:a,d=b&&b.parentNode;return a===d||!(!d||1!==d.nodeType||!(c.contains?c.contains(d):a.compareDocumentPosition&&16&a.compareDocumentPosition(d)))}:function(a,b){if(b)while(b=b.parentNode)if(b===a)return!0;return!1},B=b?function(a,b){if(a===b)return l=!0,0;var d=!a.compareDocumentPosition-!b.compareDocumentPosition;return d?d:(d=(a.ownerDocument||a)===(b.ownerDocument||b)?a.compareDocumentPosition(b):1,1&d||!c.sortDetached&&b.compareDocumentPosition(a)===d?a===n||a.ownerDocument===v&&t(v,a)?-1:b===n||b.ownerDocument===v&&t(v,b)?1:k?I(k,a)-I(k,b):0:4&d?-1:1)}:function(a,b){if(a===b)return l=!0,0;var c,d=0,e=a.parentNode,f=b.parentNode,g=[a],h=[b];if(!e||!f)return a===n?-1:b===n?1:e?-1:f?1:k?I(k,a)-I(k,b):0;if(e===f)return la(a,b);c=a;while(c=c.parentNode)g.unshift(c);c=b;while(c=c.parentNode)h.unshift(c);while(g[d]===h[d])d++;return d?la(g[d],h[d]):g[d]===v?-1:h[d]===v?1:0},n):n},ga.matches=function(a,b){return ga(a,null,null,b)},ga.matchesSelector=function(a,b){if((a.ownerDocument||a)!==n&&m(a),b=b.replace(S,"='$1']"),c.matchesSelector&&p&&!A[b+" "]&&(!r||!r.test(b))&&(!q||!q.test(b)))try{var d=s.call(a,b);if(d||c.disconnectedMatch||a.document&&11!==a.document.nodeType)return d}catch(e){}return ga(b,n,null,[a]).length>0},ga.contains=function(a,b){return(a.ownerDocument||a)!==n&&m(a),t(a,b)},ga.attr=function(a,b){(a.ownerDocument||a)!==n&&m(a);var e=d.attrHandle[b.toLowerCase()],f=e&&C.call(d.attrHandle,b.toLowerCase())?e(a,b,!p):void 0;return void 0!==f?f:c.attributes||!p?a.getAttribute(b):(f=a.getAttributeNode(b))&&f.specified?f.value:null},ga.escape=function(a){return(a+"").replace(ba,ca)},ga.error=function(a){throw new Error("Syntax error, unrecognized expression: "+a)},ga.uniqueSort=function(a){var b,d=[],e=0,f=0;if(l=!c.detectDuplicates,k=!c.sortStable&&a.slice(0),a.sort(B),l){while(b=a[f++])b===a[f]&&(e=d.push(f));while(e--)a.splice(d[e],1)}return k=null,a},e=ga.getText=function(a){var b,c="",d=0,f=a.nodeType;if(f){if(1===f||9===f||11===f){if("string"==typeof a.textContent)return a.textContent;for(a=a.firstChild;a;a=a.nextSibling)c+=e(a)}else if(3===f||4===f)return a.nodeValue}else while(b=a[d++])c+=e(b);return c},d=ga.selectors={cacheLength:50,createPseudo:ia,match:V,attrHandle:{},find:{},relative:{">":{dir:"parentNode",first:!0}," ":{dir:"parentNode"},"+":{dir:"previousSibling",first:!0},"~":{dir:"previousSibling"}},preFilter:{ATTR:function(a){return a[1]=a[1].replace(_,aa),a[3]=(a[3]||a[4]||a[5]||"").replace(_,aa),"~="===a[2]&&(a[3]=" "+a[3]+" "),a.slice(0,4)},CHILD:function(a){return a[1]=a[1].toLowerCase(),"nth"===a[1].slice(0,3)?(a[3]||ga.error(a[0]),a[4]=+(a[4]?a[5]+(a[6]||1):2*("even"===a[3]||"odd"===a[3])),a[5]=+(a[7]+a[8]||"odd"===a[3])):a[3]&&ga.error(a[0]),a},PSEUDO:function(a){var b,c=!a[6]&&a[2];return V.CHILD.test(a[0])?null:(a[3]?a[2]=a[4]||a[5]||"":c&&T.test(c)&&(b=g(c,!0))&&(b=c.indexOf(")",c.length-b)-c.length)&&(a[0]=a[0].slice(0,b),a[2]=c.slice(0,b)),a.slice(0,3))}},filter:{TAG:function(a){var b=a.replace(_,aa).toLowerCase();return"*"===a?function(){return!0}:function(a){return a.nodeName&&a.nodeName.toLowerCase()===b}},CLASS:function(a){var b=y[a+" "];return b||(b=new RegExp("(^|"+K+")"+a+"("+K+"|$)"))&&y(a,function(a){return b.test("string"==typeof a.className&&a.className||"undefined"!=typeof a.getAttribute&&a.getAttribute("class")||"")})},ATTR:function(a,b,c){return function(d){var e=ga.attr(d,a);return null==e?"!="===b:!b||(e+="","="===b?e===c:"!="===b?e!==c:"^="===b?c&&0===e.indexOf(c):"*="===b?c&&e.indexOf(c)>-1:"$="===b?c&&e.slice(-c.length)===c:"~="===b?(" "+e.replace(O," ")+" ").indexOf(c)>-1:"|="===b&&(e===c||e.slice(0,c.length+1)===c+"-"))}},CHILD:function(a,b,c,d,e){var f="nth"!==a.slice(0,3),g="last"!==a.slice(-4),h="of-type"===b;return 1===d&&0===e?function(a){return!!a.parentNode}:function(b,c,i){var j,k,l,m,n,o,p=f!==g?"nextSibling":"previousSibling",q=b.parentNode,r=h&&b.nodeName.toLowerCase(),s=!i&&!h,t=!1;if(q){if(f){while(p){m=b;while(m=m[p])if(h?m.nodeName.toLowerCase()===r:1===m.nodeType)return!1;o=p="only"===a&&!o&&"nextSibling"}return!0}if(o=[g?q.firstChild:q.lastChild],g&&s){m=q,l=m[u]||(m[u]={}),k=l[m.uniqueID]||(l[m.uniqueID]={}),j=k[a]||[],n=j[0]===w&&j[1],t=n&&j[2],m=n&&q.childNodes[n];while(m=++n&&m&&m[p]||(t=n=0)||o.pop())if(1===m.nodeType&&++t&&m===b){k[a]=[w,n,t];break}}else if(s&&(m=b,l=m[u]||(m[u]={}),k=l[m.uniqueID]||(l[m.uniqueID]={}),j=k[a]||[],n=j[0]===w&&j[1],t=n),t===!1)while(m=++n&&m&&m[p]||(t=n=0)||o.pop())if((h?m.nodeName.toLowerCase()===r:1===m.nodeType)&&++t&&(s&&(l=m[u]||(m[u]={}),k=l[m.uniqueID]||(l[m.uniqueID]={}),k[a]=[w,t]),m===b))break;return t-=e,t===d||t%d===0&&t/d>=0}}},PSEUDO:function(a,b){var c,e=d.pseudos[a]||d.setFilters[a.toLowerCase()]||ga.error("unsupported pseudo: "+a);return e[u]?e(b):e.length>1?(c=[a,a,"",b],d.setFilters.hasOwnProperty(a.toLowerCase())?ia(function(a,c){var d,f=e(a,b),g=f.length;while(g--)d=I(a,f[g]),a[d]=!(c[d]=f[g])}):function(a){return e(a,0,c)}):e}},pseudos:{not:ia(function(a){var b=[],c=[],d=h(a.replace(P,"$1"));return d[u]?ia(function(a,b,c,e){var f,g=d(a,null,e,[]),h=a.length;while(h--)(f=g[h])&&(a[h]=!(b[h]=f))}):function(a,e,f){return b[0]=a,d(b,null,f,c),b[0]=null,!c.pop()}}),has:ia(function(a){return function(b){return ga(a,b).length>0}}),contains:ia(function(a){return a=a.replace(_,aa),function(b){return(b.textContent||b.innerText||e(b)).indexOf(a)>-1}}),lang:ia(function(a){return U.test(a||"")||ga.error("unsupported lang: "+a),a=a.replace(_,aa).toLowerCase(),function(b){var c;do if(c=p?b.lang:b.getAttribute("xml:lang")||b.getAttribute("lang"))return c=c.toLowerCase(),c===a||0===c.indexOf(a+"-");while((b=b.parentNode)&&1===b.nodeType);return!1}}),target:function(b){var c=a.location&&a.location.hash;return c&&c.slice(1)===b.id},root:function(a){return a===o},focus:function(a){return a===n.activeElement&&(!n.hasFocus||n.hasFocus())&&!!(a.type||a.href||~a.tabIndex)},enabled:oa(!1),disabled:oa(!0),checked:function(a){var b=a.nodeName.toLowerCase();return"input"===b&&!!a.checked||"option"===b&&!!a.selected},selected:function(a){return a.parentNode&&a.parentNode.selectedIndex,a.selected===!0},empty:function(a){for(a=a.firstChild;a;a=a.nextSibling)if(a.nodeType<6)return!1;return!0},parent:function(a){return!d.pseudos.empty(a)},header:function(a){return X.test(a.nodeName)},input:function(a){return W.test(a.nodeName)},button:function(a){var b=a.nodeName.toLowerCase();return"input"===b&&"button"===a.type||"button"===b},text:function(a){var b;return"input"===a.nodeName.toLowerCase()&&"text"===a.type&&(null==(b=a.getAttribute("type"))||"text"===b.toLowerCase())},first:pa(function(){return[0]}),last:pa(function(a,b){return[b-1]}),eq:pa(function(a,b,c){return[c<0?c+b:c]}),even:pa(function(a,b){for(var c=0;c<b;c+=2)a.push(c);return a}),odd:pa(function(a,b){for(var c=1;c<b;c+=2)a.push(c);return a}),lt:pa(function(a,b,c){for(var d=c<0?c+b:c;--d>=0;)a.push(d);return a}),gt:pa(function(a,b,c){for(var d=c<0?c+b:c;++d<b;)a.push(d);return a})}},d.pseudos.nth=d.pseudos.eq;for(b in{radio:!0,checkbox:!0,file:!0,password:!0,image:!0})d.pseudos[b]=ma(b);for(b in{submit:!0,reset:!0})d.pseudos[b]=na(b);function ra(){}ra.prototype=d.filters=d.pseudos,d.setFilters=new ra,g=ga.tokenize=function(a,b){var c,e,f,g,h,i,j,k=z[a+" "];if(k)return b?0:k.slice(0);h=a,i=[],j=d.preFilter;while(h){c&&!(e=Q.exec(h))||(e&&(h=h.slice(e[0].length)||h),i.push(f=[])),c=!1,(e=R.exec(h))&&(c=e.shift(),f.push({value:c,type:e[0].replace(P," ")}),h=h.slice(c.length));for(g in d.filter)!(e=V[g].exec(h))||j[g]&&!(e=j[g](e))||(c=e.shift(),f.push({value:c,type:g,matches:e}),h=h.slice(c.length));if(!c)break}return b?h.length:h?ga.error(a):z(a,i).slice(0)};function sa(a){for(var b=0,c=a.length,d="";b<c;b++)d+=a[b].value;return d}function ta(a,b,c){var d=b.dir,e=b.next,f=e||d,g=c&&"parentNode"===f,h=x++;return b.first?function(b,c,e){while(b=b[d])if(1===b.nodeType||g)return a(b,c,e);return!1}:function(b,c,i){var j,k,l,m=[w,h];if(i){while(b=b[d])if((1===b.nodeType||g)&&a(b,c,i))return!0}else while(b=b[d])if(1===b.nodeType||g)if(l=b[u]||(b[u]={}),k=l[b.uniqueID]||(l[b.uniqueID]={}),e&&e===b.nodeName.toLowerCase())b=b[d]||b;else{if((j=k[f])&&j[0]===w&&j[1]===h)return m[2]=j[2];if(k[f]=m,m[2]=a(b,c,i))return!0}return!1}}function ua(a){return a.length>1?function(b,c,d){var e=a.length;while(e--)if(!a[e](b,c,d))return!1;return!0}:a[0]}function va(a,b,c){for(var d=0,e=b.length;d<e;d++)ga(a,b[d],c);return c}function wa(a,b,c,d,e){for(var f,g=[],h=0,i=a.length,j=null!=b;h<i;h++)(f=a[h])&&(c&&!c(f,d,e)||(g.push(f),j&&b.push(h)));return g}function xa(a,b,c,d,e,f){return d&&!d[u]&&(d=xa(d)),e&&!e[u]&&(e=xa(e,f)),ia(function(f,g,h,i){var j,k,l,m=[],n=[],o=g.length,p=f||va(b||"*",h.nodeType?[h]:h,[]),q=!a||!f&&b?p:wa(p,m,a,h,i),r=c?e||(f?a:o||d)?[]:g:q;if(c&&c(q,r,h,i),d){j=wa(r,n),d(j,[],h,i),k=j.length;while(k--)(l=j[k])&&(r[n[k]]=!(q[n[k]]=l))}if(f){if(e||a){if(e){j=[],k=r.length;while(k--)(l=r[k])&&j.push(q[k]=l);e(null,r=[],j,i)}k=r.length;while(k--)(l=r[k])&&(j=e?I(f,l):m[k])>-1&&(f[j]=!(g[j]=l))}}else r=wa(r===g?r.splice(o,r.length):r),e?e(null,g,r,i):G.apply(g,r)})}function ya(a){for(var b,c,e,f=a.length,g=d.relative[a[0].type],h=g||d.relative[" "],i=g?1:0,k=ta(function(a){return a===b},h,!0),l=ta(function(a){return I(b,a)>-1},h,!0),m=[function(a,c,d){var e=!g&&(d||c!==j)||((b=c).nodeType?k(a,c,d):l(a,c,d));return b=null,e}];i<f;i++)if(c=d.relative[a[i].type])m=[ta(ua(m),c)];else{if(c=d.filter[a[i].type].apply(null,a[i].matches),c[u]){for(e=++i;e<f;e++)if(d.relative[a[e].type])break;return xa(i>1&&ua(m),i>1&&sa(a.slice(0,i-1).concat({value:" "===a[i-2].type?"*":""})).replace(P,"$1"),c,i<e&&ya(a.slice(i,e)),e<f&&ya(a=a.slice(e)),e<f&&sa(a))}m.push(c)}return ua(m)}function za(a,b){var c=b.length>0,e=a.length>0,f=function(f,g,h,i,k){var l,o,q,r=0,s="0",t=f&&[],u=[],v=j,x=f||e&&d.find.TAG("*",k),y=w+=null==v?1:Math.random()||.1,z=x.length;for(k&&(j=g===n||g||k);s!==z&&null!=(l=x[s]);s++){if(e&&l){o=0,g||l.ownerDocument===n||(m(l),h=!p);while(q=a[o++])if(q(l,g||n,h)){i.push(l);break}k&&(w=y)}c&&((l=!q&&l)&&r--,f&&t.push(l))}if(r+=s,c&&s!==r){o=0;while(q=b[o++])q(t,u,g,h);if(f){if(r>0)while(s--)t[s]||u[s]||(u[s]=E.call(i));u=wa(u)}G.apply(i,u),k&&!f&&u.length>0&&r+b.length>1&&ga.uniqueSort(i)}return k&&(w=y,j=v),t};return c?ia(f):f}return h=ga.compile=function(a,b){var c,d=[],e=[],f=A[a+" "];if(!f){b||(b=g(a)),c=b.length;while(c--)f=ya(b[c]),f[u]?d.push(f):e.push(f);f=A(a,za(e,d)),f.selector=a}return f},i=ga.select=function(a,b,c,e){var f,i,j,k,l,m="function"==typeof a&&a,n=!e&&g(a=m.selector||a);if(c=c||[],1===n.length){if(i=n[0]=n[0].slice(0),i.length>2&&"ID"===(j=i[0]).type&&9===b.nodeType&&p&&d.relative[i[1].type]){if(b=(d.find.ID(j.matches[0].replace(_,aa),b)||[])[0],!b)return c;m&&(b=b.parentNode),a=a.slice(i.shift().value.length)}f=V.needsContext.test(a)?0:i.length;while(f--){if(j=i[f],d.relative[k=j.type])break;if((l=d.find[k])&&(e=l(j.matches[0].replace(_,aa),$.test(i[0].type)&&qa(b.parentNode)||b))){if(i.splice(f,1),a=e.length&&sa(i),!a)return G.apply(c,e),c;break}}}return(m||h(a,n))(e,b,!p,c,!b||$.test(a)&&qa(b.parentNode)||b),c},c.sortStable=u.split("").sort(B).join("")===u,c.detectDuplicates=!!l,m(),c.sortDetached=ja(function(a){return 1&a.compareDocumentPosition(n.createElement("fieldset"))}),ja(function(a){return a.innerHTML="<a href='#'></a>","#"===a.firstChild.getAttribute("href")})||ka("type|href|height|width",function(a,b,c){if(!c)return a.getAttribute(b,"type"===b.toLowerCase()?1:2)}),c.attributes&&ja(function(a){return a.innerHTML="<input/>",a.firstChild.setAttribute("value",""),""===a.firstChild.getAttribute("value")})||ka("value",function(a,b,c){if(!c&&"input"===a.nodeName.toLowerCase())return a.defaultValue}),ja(function(a){return null==a.getAttribute("disabled")})||ka(J,function(a,b,c){var d;if(!c)return a[b]===!0?b.toLowerCase():(d=a.getAttributeNode(b))&&d.specified?d.value:null}),ga}(a);r.find=x,r.expr=x.selectors,r.expr[":"]=r.expr.pseudos,r.uniqueSort=r.unique=x.uniqueSort,r.text=x.getText,r.isXMLDoc=x.isXML,r.contains=x.contains,r.escapeSelector=x.escape;var y=function(a,b,c){var d=[],e=void 0!==c;while((a=a[b])&&9!==a.nodeType)if(1===a.nodeType){if(e&&r(a).is(c))break;d.push(a)}return d},z=function(a,b){for(var c=[];a;a=a.nextSibling)1===a.nodeType&&a!==b&&c.push(a);return c},A=r.expr.match.needsContext;function B(a,b){return a.nodeName&&a.nodeName.toLowerCase()===b.toLowerCase()}var C=/^<([a-z][^\/\0>:\x20\t\r\n\f]*)[\x20\t\r\n\f]*\/?>(?:<\/\1>|)$/i,D=/^.[^:#\[\.,]*$/;function E(a,b,c){return r.isFunction(b)?r.grep(a,function(a,d){return!!b.call(a,d,a)!==c}):b.nodeType?r.grep(a,function(a){return a===b!==c}):"string"!=typeof b?r.grep(a,function(a){return i.call(b,a)>-1!==c}):D.test(b)?r.filter(b,a,c):(b=r.filter(b,a),r.grep(a,function(a){return i.call(b,a)>-1!==c&&1===a.nodeType}))}r.filter=function(a,b,c){var d=b[0];return c&&(a=":not("+a+")"),1===b.length&&1===d.nodeType?r.find.matchesSelector(d,a)?[d]:[]:r.find.matches(a,r.grep(b,function(a){return 1===a.nodeType}))},r.fn.extend({find:function(a){var b,c,d=this.length,e=this;if("string"!=typeof a)return this.pushStack(r(a).filter(function(){for(b=0;b<d;b++)if(r.contains(e[b],this))return!0}));for(c=this.pushStack([]),b=0;b<d;b++)r.find(a,e[b],c);return d>1?r.uniqueSort(c):c},filter:function(a){return this.pushStack(E(this,a||[],!1))},not:function(a){return this.pushStack(E(this,a||[],!0))},is:function(a){return!!E(this,"string"==typeof a&&A.test(a)?r(a):a||[],!1).length}});var F,G=/^(?:\s*(<[\w\W]+>)[^>]*|#([\w-]+))$/,H=r.fn.init=function(a,b,c){var e,f;if(!a)return this;if(c=c||F,"string"==typeof a){if(e="<"===a[0]&&">"===a[a.length-1]&&a.length>=3?[null,a,null]:G.exec(a),!e||!e[1]&&b)return!b||b.jquery?(b||c).find(a):this.constructor(b).find(a);if(e[1]){if(b=b instanceof r?b[0]:b,r.merge(this,r.parseHTML(e[1],b&&b.nodeType?b.ownerDocument||b:d,!0)),C.test(e[1])&&r.isPlainObject(b))for(e in b)r.isFunction(this[e])?this[e](b[e]):this.attr(e,b[e]);return this}return f=d.getElementById(e[2]),f&&(this[0]=f,this.length=1),this}return a.nodeType?(this[0]=a,this.length=1,this):r.isFunction(a)?void 0!==c.ready?c.ready(a):a(r):r.makeArray(a,this)};H.prototype=r.fn,F=r(d);var I=/^(?:parents|prev(?:Until|All))/,J={children:!0,contents:!0,next:!0,prev:!0};r.fn.extend({has:function(a){var b=r(a,this),c=b.length;return this.filter(function(){for(var a=0;a<c;a++)if(r.contains(this,b[a]))return!0})},closest:function(a,b){var c,d=0,e=this.length,f=[],g="string"!=typeof a&&r(a);if(!A.test(a))for(;d<e;d++)for(c=this[d];c&&c!==b;c=c.parentNode)if(c.nodeType<11&&(g?g.index(c)>-1:1===c.nodeType&&r.find.matchesSelector(c,a))){f.push(c);break}return this.pushStack(f.length>1?r.uniqueSort(f):f)},index:function(a){return a?"string"==typeof a?i.call(r(a),this[0]):i.call(this,a.jquery?a[0]:a):this[0]&&this[0].parentNode?this.first().prevAll().length:-1},add:function(a,b){return this.pushStack(r.uniqueSort(r.merge(this.get(),r(a,b))))},addBack:function(a){return this.add(null==a?this.prevObject:this.prevObject.filter(a))}});function K(a,b){while((a=a[b])&&1!==a.nodeType);return a}r.each({parent:function(a){var b=a.parentNode;return b&&11!==b.nodeType?b:null},parents:function(a){return y(a,"parentNode")},parentsUntil:function(a,b,c){return y(a,"parentNode",c)},next:function(a){return K(a,"nextSibling")},prev:function(a){return K(a,"previousSibling")},nextAll:function(a){return y(a,"nextSibling")},prevAll:function(a){return y(a,"previousSibling")},nextUntil:function(a,b,c){return y(a,"nextSibling",c)},prevUntil:function(a,b,c){return y(a,"previousSibling",c)},siblings:function(a){return z((a.parentNode||{}).firstChild,a)},children:function(a){return z(a.firstChild)},contents:function(a){return B(a,"iframe")?a.contentDocument:(B(a,"template")&&(a=a.content||a),r.merge([],a.childNodes))}},function(a,b){r.fn[a]=function(c,d){var e=r.map(this,b,c);return"Until"!==a.slice(-5)&&(d=c),d&&"string"==typeof d&&(e=r.filter(d,e)),this.length>1&&(J[a]||r.uniqueSort(e),I.test(a)&&e.reverse()),this.pushStack(e)}});var L=/[^\x20\t\r\n\f]+/g;function M(a){var b={};return r.each(a.match(L)||[],function(a,c){b[c]=!0}),b}r.Callbacks=function(a){a="string"==typeof a?M(a):r.extend({},a);var b,c,d,e,f=[],g=[],h=-1,i=function(){for(e=e||a.once,d=b=!0;g.length;h=-1){c=g.shift();while(++h<f.length)f[h].apply(c[0],c[1])===!1&&a.stopOnFalse&&(h=f.length,c=!1)}a.memory||(c=!1),b=!1,e&&(f=c?[]:"")},j={add:function(){return f&&(c&&!b&&(h=f.length-1,g.push(c)),function d(b){r.each(b,function(b,c){r.isFunction(c)?a.unique&&j.has(c)||f.push(c):c&&c.length&&"string"!==r.type(c)&&d(c)})}(arguments),c&&!b&&i()),this},remove:function(){return r.each(arguments,function(a,b){var c;while((c=r.inArray(b,f,c))>-1)f.splice(c,1),c<=h&&h--}),this},has:function(a){return a?r.inArray(a,f)>-1:f.length>0},empty:function(){return f&&(f=[]),this},disable:function(){return e=g=[],f=c="",this},disabled:function(){return!f},lock:function(){return e=g=[],c||b||(f=c=""),this},locked:function(){return!!e},fireWith:function(a,c){return e||(c=c||[],c=[a,c.slice?c.slice():c],g.push(c),b||i()),this},fire:function(){return j.fireWith(this,arguments),this},fired:function(){return!!d}};return j};function N(a){return a}function O(a){throw a}function P(a,b,c,d){var e;try{a&&r.isFunction(e=a.promise)?e.call(a).done(b).fail(c):a&&r.isFunction(e=a.then)?e.call(a,b,c):b.apply(void 0,[a].slice(d))}catch(a){c.apply(void 0,[a])}}r.extend({Deferred:function(b){var c=[["notify","progress",r.Callbacks("memory"),r.Callbacks("memory"),2],["resolve","done",r.Callbacks("once memory"),r.Callbacks("once memory"),0,"resolved"],["reject","fail",r.Callbacks("once memory"),r.Callbacks("once memory"),1,"rejected"]],d="pending",e={state:function(){return d},always:function(){return f.done(arguments).fail(arguments),this},"catch":function(a){return e.then(null,a)},pipe:function(){var a=arguments;return r.Deferred(function(b){r.each(c,function(c,d){var e=r.isFunction(a[d[4]])&&a[d[4]];f[d[1]](function(){var a=e&&e.apply(this,arguments);a&&r.isFunction(a.promise)?a.promise().progress(b.notify).done(b.resolve).fail(b.reject):b[d[0]+"With"](this,e?[a]:arguments)})}),a=null}).promise()},then:function(b,d,e){var f=0;function g(b,c,d,e){return function(){var h=this,i=arguments,j=function(){var a,j;if(!(b<f)){if(a=d.apply(h,i),a===c.promise())throw new TypeError("Thenable self-resolution");j=a&&("object"==typeof a||"function"==typeof a)&&a.then,r.isFunction(j)?e?j.call(a,g(f,c,N,e),g(f,c,O,e)):(f++,j.call(a,g(f,c,N,e),g(f,c,O,e),g(f,c,N,c.notifyWith))):(d!==N&&(h=void 0,i=[a]),(e||c.resolveWith)(h,i))}},k=e?j:function(){try{j()}catch(a){r.Deferred.exceptionHook&&r.Deferred.exceptionHook(a,k.stackTrace),b+1>=f&&(d!==O&&(h=void 0,i=[a]),c.rejectWith(h,i))}};b?k():(r.Deferred.getStackHook&&(k.stackTrace=r.Deferred.getStackHook()),a.setTimeout(k))}}return r.Deferred(function(a){c[0][3].add(g(0,a,r.isFunction(e)?e:N,a.notifyWith)),c[1][3].add(g(0,a,r.isFunction(b)?b:N)),c[2][3].add(g(0,a,r.isFunction(d)?d:O))}).promise()},promise:function(a){return null!=a?r.extend(a,e):e}},f={};return r.each(c,function(a,b){var g=b[2],h=b[5];e[b[1]]=g.add,h&&g.add(function(){d=h},c[3-a][2].disable,c[0][2].lock),g.add(b[3].fire),f[b[0]]=function(){return f[b[0]+"With"](this===f?void 0:this,arguments),this},f[b[0]+"With"]=g.fireWith}),e.promise(f),b&&b.call(f,f),f},when:function(a){var b=arguments.length,c=b,d=Array(c),e=f.call(arguments),g=r.Deferred(),h=function(a){return function(c){d[a]=this,e[a]=arguments.length>1?f.call(arguments):c,--b||g.resolveWith(d,e)}};if(b<=1&&(P(a,g.done(h(c)).resolve,g.reject,!b),"pending"===g.state()||r.isFunction(e[c]&&e[c].then)))return g.then();while(c--)P(e[c],h(c),g.reject);return g.promise()}});var Q=/^(Eval|Internal|Range|Reference|Syntax|Type|URI)Error$/;r.Deferred.exceptionHook=function(b,c){a.console&&a.console.warn&&b&&Q.test(b.name)&&a.console.warn("jQuery.Deferred exception: "+b.message,b.stack,c)},r.readyException=function(b){a.setTimeout(function(){throw b})};var R=r.Deferred();r.fn.ready=function(a){return R.then(a)["catch"](function(a){r.readyException(a)}),this},r.extend({isReady:!1,readyWait:1,ready:function(a){(a===!0?--r.readyWait:r.isReady)||(r.isReady=!0,a!==!0&&--r.readyWait>0||R.resolveWith(d,[r]))}}),r.ready.then=R.then;function S(){d.removeEventListener("DOMContentLoaded",S),
a.removeEventListener("load",S),r.ready()}"complete"===d.readyState||"loading"!==d.readyState&&!d.documentElement.doScroll?a.setTimeout(r.ready):(d.addEventListener("DOMContentLoaded",S),a.addEventListener("load",S));var T=function(a,b,c,d,e,f,g){var h=0,i=a.length,j=null==c;if("object"===r.type(c)){e=!0;for(h in c)T(a,b,h,c[h],!0,f,g)}else if(void 0!==d&&(e=!0,r.isFunction(d)||(g=!0),j&&(g?(b.call(a,d),b=null):(j=b,b=function(a,b,c){return j.call(r(a),c)})),b))for(;h<i;h++)b(a[h],c,g?d:d.call(a[h],h,b(a[h],c)));return e?a:j?b.call(a):i?b(a[0],c):f},U=function(a){return 1===a.nodeType||9===a.nodeType||!+a.nodeType};function V(){this.expando=r.expando+V.uid++}V.uid=1,V.prototype={cache:function(a){var b=a[this.expando];return b||(b={},U(a)&&(a.nodeType?a[this.expando]=b:Object.defineProperty(a,this.expando,{value:b,configurable:!0}))),b},set:function(a,b,c){var d,e=this.cache(a);if("string"==typeof b)e[r.camelCase(b)]=c;else for(d in b)e[r.camelCase(d)]=b[d];return e},get:function(a,b){return void 0===b?this.cache(a):a[this.expando]&&a[this.expando][r.camelCase(b)]},access:function(a,b,c){return void 0===b||b&&"string"==typeof b&&void 0===c?this.get(a,b):(this.set(a,b,c),void 0!==c?c:b)},remove:function(a,b){var c,d=a[this.expando];if(void 0!==d){if(void 0!==b){Array.isArray(b)?b=b.map(r.camelCase):(b=r.camelCase(b),b=b in d?[b]:b.match(L)||[]),c=b.length;while(c--)delete d[b[c]]}(void 0===b||r.isEmptyObject(d))&&(a.nodeType?a[this.expando]=void 0:delete a[this.expando])}},hasData:function(a){var b=a[this.expando];return void 0!==b&&!r.isEmptyObject(b)}};var W=new V,X=new V,Y=/^(?:\{[\w\W]*\}|\[[\w\W]*\])$/,Z=/[A-Z]/g;function $(a){return"true"===a||"false"!==a&&("null"===a?null:a===+a+""?+a:Y.test(a)?JSON.parse(a):a)}function _(a,b,c){var d;if(void 0===c&&1===a.nodeType)if(d="data-"+b.replace(Z,"-$&").toLowerCase(),c=a.getAttribute(d),"string"==typeof c){try{c=$(c)}catch(e){}X.set(a,b,c)}else c=void 0;return c}r.extend({hasData:function(a){return X.hasData(a)||W.hasData(a)},data:function(a,b,c){return X.access(a,b,c)},removeData:function(a,b){X.remove(a,b)},_data:function(a,b,c){return W.access(a,b,c)},_removeData:function(a,b){W.remove(a,b)}}),r.fn.extend({data:function(a,b){var c,d,e,f=this[0],g=f&&f.attributes;if(void 0===a){if(this.length&&(e=X.get(f),1===f.nodeType&&!W.get(f,"hasDataAttrs"))){c=g.length;while(c--)g[c]&&(d=g[c].name,0===d.indexOf("data-")&&(d=r.camelCase(d.slice(5)),_(f,d,e[d])));W.set(f,"hasDataAttrs",!0)}return e}return"object"==typeof a?this.each(function(){X.set(this,a)}):T(this,function(b){var c;if(f&&void 0===b){if(c=X.get(f,a),void 0!==c)return c;if(c=_(f,a),void 0!==c)return c}else this.each(function(){X.set(this,a,b)})},null,b,arguments.length>1,null,!0)},removeData:function(a){return this.each(function(){X.remove(this,a)})}}),r.extend({queue:function(a,b,c){var d;if(a)return b=(b||"fx")+"queue",d=W.get(a,b),c&&(!d||Array.isArray(c)?d=W.access(a,b,r.makeArray(c)):d.push(c)),d||[]},dequeue:function(a,b){b=b||"fx";var c=r.queue(a,b),d=c.length,e=c.shift(),f=r._queueHooks(a,b),g=function(){r.dequeue(a,b)};"inprogress"===e&&(e=c.shift(),d--),e&&("fx"===b&&c.unshift("inprogress"),delete f.stop,e.call(a,g,f)),!d&&f&&f.empty.fire()},_queueHooks:function(a,b){var c=b+"queueHooks";return W.get(a,c)||W.access(a,c,{empty:r.Callbacks("once memory").add(function(){W.remove(a,[b+"queue",c])})})}}),r.fn.extend({queue:function(a,b){var c=2;return"string"!=typeof a&&(b=a,a="fx",c--),arguments.length<c?r.queue(this[0],a):void 0===b?this:this.each(function(){var c=r.queue(this,a,b);r._queueHooks(this,a),"fx"===a&&"inprogress"!==c[0]&&r.dequeue(this,a)})},dequeue:function(a){return this.each(function(){r.dequeue(this,a)})},clearQueue:function(a){return this.queue(a||"fx",[])},promise:function(a,b){var c,d=1,e=r.Deferred(),f=this,g=this.length,h=function(){--d||e.resolveWith(f,[f])};"string"!=typeof a&&(b=a,a=void 0),a=a||"fx";while(g--)c=W.get(f[g],a+"queueHooks"),c&&c.empty&&(d++,c.empty.add(h));return h(),e.promise(b)}});var aa=/[+-]?(?:\d*\.|)\d+(?:[eE][+-]?\d+|)/.source,ba=new RegExp("^(?:([+-])=|)("+aa+")([a-z%]*)$","i"),ca=["Top","Right","Bottom","Left"],da=function(a,b){return a=b||a,"none"===a.style.display||""===a.style.display&&r.contains(a.ownerDocument,a)&&"none"===r.css(a,"display")},ea=function(a,b,c,d){var e,f,g={};for(f in b)g[f]=a.style[f],a.style[f]=b[f];e=c.apply(a,d||[]);for(f in b)a.style[f]=g[f];return e};function fa(a,b,c,d){var e,f=1,g=20,h=d?function(){return d.cur()}:function(){return r.css(a,b,"")},i=h(),j=c&&c[3]||(r.cssNumber[b]?"":"px"),k=(r.cssNumber[b]||"px"!==j&&+i)&&ba.exec(r.css(a,b));if(k&&k[3]!==j){j=j||k[3],c=c||[],k=+i||1;do f=f||".5",k/=f,r.style(a,b,k+j);while(f!==(f=h()/i)&&1!==f&&--g)}return c&&(k=+k||+i||0,e=c[1]?k+(c[1]+1)*c[2]:+c[2],d&&(d.unit=j,d.start=k,d.end=e)),e}var ga={};function ha(a){var b,c=a.ownerDocument,d=a.nodeName,e=ga[d];return e?e:(b=c.body.appendChild(c.createElement(d)),e=r.css(b,"display"),b.parentNode.removeChild(b),"none"===e&&(e="block"),ga[d]=e,e)}function ia(a,b){for(var c,d,e=[],f=0,g=a.length;f<g;f++)d=a[f],d.style&&(c=d.style.display,b?("none"===c&&(e[f]=W.get(d,"display")||null,e[f]||(d.style.display="")),""===d.style.display&&da(d)&&(e[f]=ha(d))):"none"!==c&&(e[f]="none",W.set(d,"display",c)));for(f=0;f<g;f++)null!=e[f]&&(a[f].style.display=e[f]);return a}r.fn.extend({show:function(){return ia(this,!0)},hide:function(){return ia(this)},toggle:function(a){return"boolean"==typeof a?a?this.show():this.hide():this.each(function(){da(this)?r(this).show():r(this).hide()})}});var ja=/^(?:checkbox|radio)$/i,ka=/<([a-z][^\/\0>\x20\t\r\n\f]+)/i,la=/^$|\/(?:java|ecma)script/i,ma={option:[1,"<select multiple='multiple'>","</select>"],thead:[1,"<table>","</table>"],col:[2,"<table><colgroup>","</colgroup></table>"],tr:[2,"<table><tbody>","</tbody></table>"],td:[3,"<table><tbody><tr>","</tr></tbody></table>"],_default:[0,"",""]};ma.optgroup=ma.option,ma.tbody=ma.tfoot=ma.colgroup=ma.caption=ma.thead,ma.th=ma.td;function na(a,b){var c;return c="undefined"!=typeof a.getElementsByTagName?a.getElementsByTagName(b||"*"):"undefined"!=typeof a.querySelectorAll?a.querySelectorAll(b||"*"):[],void 0===b||b&&B(a,b)?r.merge([a],c):c}function oa(a,b){for(var c=0,d=a.length;c<d;c++)W.set(a[c],"globalEval",!b||W.get(b[c],"globalEval"))}var pa=/<|&#?\w+;/;function qa(a,b,c,d,e){for(var f,g,h,i,j,k,l=b.createDocumentFragment(),m=[],n=0,o=a.length;n<o;n++)if(f=a[n],f||0===f)if("object"===r.type(f))r.merge(m,f.nodeType?[f]:f);else if(pa.test(f)){g=g||l.appendChild(b.createElement("div")),h=(ka.exec(f)||["",""])[1].toLowerCase(),i=ma[h]||ma._default,g.innerHTML=i[1]+r.htmlPrefilter(f)+i[2],k=i[0];while(k--)g=g.lastChild;r.merge(m,g.childNodes),g=l.firstChild,g.textContent=""}else m.push(b.createTextNode(f));l.textContent="",n=0;while(f=m[n++])if(d&&r.inArray(f,d)>-1)e&&e.push(f);else if(j=r.contains(f.ownerDocument,f),g=na(l.appendChild(f),"script"),j&&oa(g),c){k=0;while(f=g[k++])la.test(f.type||"")&&c.push(f)}return l}!function(){var a=d.createDocumentFragment(),b=a.appendChild(d.createElement("div")),c=d.createElement("input");c.setAttribute("type","radio"),c.setAttribute("checked","checked"),c.setAttribute("name","t"),b.appendChild(c),o.checkClone=b.cloneNode(!0).cloneNode(!0).lastChild.checked,b.innerHTML="<textarea>x</textarea>",o.noCloneChecked=!!b.cloneNode(!0).lastChild.defaultValue}();var ra=d.documentElement,sa=/^key/,ta=/^(?:mouse|pointer|contextmenu|drag|drop)|click/,ua=/^([^.]*)(?:\.(.+)|)/;function va(){return!0}function wa(){return!1}function xa(){try{return d.activeElement}catch(a){}}function ya(a,b,c,d,e,f){var g,h;if("object"==typeof b){"string"!=typeof c&&(d=d||c,c=void 0);for(h in b)ya(a,h,c,d,b[h],f);return a}if(null==d&&null==e?(e=c,d=c=void 0):null==e&&("string"==typeof c?(e=d,d=void 0):(e=d,d=c,c=void 0)),e===!1)e=wa;else if(!e)return a;return 1===f&&(g=e,e=function(a){return r().off(a),g.apply(this,arguments)},e.guid=g.guid||(g.guid=r.guid++)),a.each(function(){r.event.add(this,b,e,d,c)})}r.event={global:{},add:function(a,b,c,d,e){var f,g,h,i,j,k,l,m,n,o,p,q=W.get(a);if(q){c.handler&&(f=c,c=f.handler,e=f.selector),e&&r.find.matchesSelector(ra,e),c.guid||(c.guid=r.guid++),(i=q.events)||(i=q.events={}),(g=q.handle)||(g=q.handle=function(b){return"undefined"!=typeof r&&r.event.triggered!==b.type?r.event.dispatch.apply(a,arguments):void 0}),b=(b||"").match(L)||[""],j=b.length;while(j--)h=ua.exec(b[j])||[],n=p=h[1],o=(h[2]||"").split(".").sort(),n&&(l=r.event.special[n]||{},n=(e?l.delegateType:l.bindType)||n,l=r.event.special[n]||{},k=r.extend({type:n,origType:p,data:d,handler:c,guid:c.guid,selector:e,needsContext:e&&r.expr.match.needsContext.test(e),namespace:o.join(".")},f),(m=i[n])||(m=i[n]=[],m.delegateCount=0,l.setup&&l.setup.call(a,d,o,g)!==!1||a.addEventListener&&a.addEventListener(n,g)),l.add&&(l.add.call(a,k),k.handler.guid||(k.handler.guid=c.guid)),e?m.splice(m.delegateCount++,0,k):m.push(k),r.event.global[n]=!0)}},remove:function(a,b,c,d,e){var f,g,h,i,j,k,l,m,n,o,p,q=W.hasData(a)&&W.get(a);if(q&&(i=q.events)){b=(b||"").match(L)||[""],j=b.length;while(j--)if(h=ua.exec(b[j])||[],n=p=h[1],o=(h[2]||"").split(".").sort(),n){l=r.event.special[n]||{},n=(d?l.delegateType:l.bindType)||n,m=i[n]||[],h=h[2]&&new RegExp("(^|\\.)"+o.join("\\.(?:.*\\.|)")+"(\\.|$)"),g=f=m.length;while(f--)k=m[f],!e&&p!==k.origType||c&&c.guid!==k.guid||h&&!h.test(k.namespace)||d&&d!==k.selector&&("**"!==d||!k.selector)||(m.splice(f,1),k.selector&&m.delegateCount--,l.remove&&l.remove.call(a,k));g&&!m.length&&(l.teardown&&l.teardown.call(a,o,q.handle)!==!1||r.removeEvent(a,n,q.handle),delete i[n])}else for(n in i)r.event.remove(a,n+b[j],c,d,!0);r.isEmptyObject(i)&&W.remove(a,"handle events")}},dispatch:function(a){var b=r.event.fix(a),c,d,e,f,g,h,i=new Array(arguments.length),j=(W.get(this,"events")||{})[b.type]||[],k=r.event.special[b.type]||{};for(i[0]=b,c=1;c<arguments.length;c++)i[c]=arguments[c];if(b.delegateTarget=this,!k.preDispatch||k.preDispatch.call(this,b)!==!1){h=r.event.handlers.call(this,b,j),c=0;while((f=h[c++])&&!b.isPropagationStopped()){b.currentTarget=f.elem,d=0;while((g=f.handlers[d++])&&!b.isImmediatePropagationStopped())b.rnamespace&&!b.rnamespace.test(g.namespace)||(b.handleObj=g,b.data=g.data,e=((r.event.special[g.origType]||{}).handle||g.handler).apply(f.elem,i),void 0!==e&&(b.result=e)===!1&&(b.preventDefault(),b.stopPropagation()))}return k.postDispatch&&k.postDispatch.call(this,b),b.result}},handlers:function(a,b){var c,d,e,f,g,h=[],i=b.delegateCount,j=a.target;if(i&&j.nodeType&&!("click"===a.type&&a.button>=1))for(;j!==this;j=j.parentNode||this)if(1===j.nodeType&&("click"!==a.type||j.disabled!==!0)){for(f=[],g={},c=0;c<i;c++)d=b[c],e=d.selector+" ",void 0===g[e]&&(g[e]=d.needsContext?r(e,this).index(j)>-1:r.find(e,this,null,[j]).length),g[e]&&f.push(d);f.length&&h.push({elem:j,handlers:f})}return j=this,i<b.length&&h.push({elem:j,handlers:b.slice(i)}),h},addProp:function(a,b){Object.defineProperty(r.Event.prototype,a,{enumerable:!0,configurable:!0,get:r.isFunction(b)?function(){if(this.originalEvent)return b(this.originalEvent)}:function(){if(this.originalEvent)return this.originalEvent[a]},set:function(b){Object.defineProperty(this,a,{enumerable:!0,configurable:!0,writable:!0,value:b})}})},fix:function(a){return a[r.expando]?a:new r.Event(a)},special:{load:{noBubble:!0},focus:{trigger:function(){if(this!==xa()&&this.focus)return this.focus(),!1},delegateType:"focusin"},blur:{trigger:function(){if(this===xa()&&this.blur)return this.blur(),!1},delegateType:"focusout"},click:{trigger:function(){if("checkbox"===this.type&&this.click&&B(this,"input"))return this.click(),!1},_default:function(a){return B(a.target,"a")}},beforeunload:{postDispatch:function(a){void 0!==a.result&&a.originalEvent&&(a.originalEvent.returnValue=a.result)}}}},r.removeEvent=function(a,b,c){a.removeEventListener&&a.removeEventListener(b,c)},r.Event=function(a,b){return this instanceof r.Event?(a&&a.type?(this.originalEvent=a,this.type=a.type,this.isDefaultPrevented=a.defaultPrevented||void 0===a.defaultPrevented&&a.returnValue===!1?va:wa,this.target=a.target&&3===a.target.nodeType?a.target.parentNode:a.target,this.currentTarget=a.currentTarget,this.relatedTarget=a.relatedTarget):this.type=a,b&&r.extend(this,b),this.timeStamp=a&&a.timeStamp||r.now(),void(this[r.expando]=!0)):new r.Event(a,b)},r.Event.prototype={constructor:r.Event,isDefaultPrevented:wa,isPropagationStopped:wa,isImmediatePropagationStopped:wa,isSimulated:!1,preventDefault:function(){var a=this.originalEvent;this.isDefaultPrevented=va,a&&!this.isSimulated&&a.preventDefault()},stopPropagation:function(){var a=this.originalEvent;this.isPropagationStopped=va,a&&!this.isSimulated&&a.stopPropagation()},stopImmediatePropagation:function(){var a=this.originalEvent;this.isImmediatePropagationStopped=va,a&&!this.isSimulated&&a.stopImmediatePropagation(),this.stopPropagation()}},r.each({altKey:!0,bubbles:!0,cancelable:!0,changedTouches:!0,ctrlKey:!0,detail:!0,eventPhase:!0,metaKey:!0,pageX:!0,pageY:!0,shiftKey:!0,view:!0,"char":!0,charCode:!0,key:!0,keyCode:!0,button:!0,buttons:!0,clientX:!0,clientY:!0,offsetX:!0,offsetY:!0,pointerId:!0,pointerType:!0,screenX:!0,screenY:!0,targetTouches:!0,toElement:!0,touches:!0,which:function(a){var b=a.button;return null==a.which&&sa.test(a.type)?null!=a.charCode?a.charCode:a.keyCode:!a.which&&void 0!==b&&ta.test(a.type)?1&b?1:2&b?3:4&b?2:0:a.which}},r.event.addProp),r.each({mouseenter:"mouseover",mouseleave:"mouseout",pointerenter:"pointerover",pointerleave:"pointerout"},function(a,b){r.event.special[a]={delegateType:b,bindType:b,handle:function(a){var c,d=this,e=a.relatedTarget,f=a.handleObj;return e&&(e===d||r.contains(d,e))||(a.type=f.origType,c=f.handler.apply(this,arguments),a.type=b),c}}}),r.fn.extend({on:function(a,b,c,d){return ya(this,a,b,c,d)},one:function(a,b,c,d){return ya(this,a,b,c,d,1)},off:function(a,b,c){var d,e;if(a&&a.preventDefault&&a.handleObj)return d=a.handleObj,r(a.delegateTarget).off(d.namespace?d.origType+"."+d.namespace:d.origType,d.selector,d.handler),this;if("object"==typeof a){for(e in a)this.off(e,b,a[e]);return this}return b!==!1&&"function"!=typeof b||(c=b,b=void 0),c===!1&&(c=wa),this.each(function(){r.event.remove(this,a,c,b)})}});var za=/<(?!area|br|col|embed|hr|img|input|link|meta|param)(([a-z][^\/\0>\x20\t\r\n\f]*)[^>]*)\/>/gi,Aa=/<script|<style|<link/i,Ba=/checked\s*(?:[^=]|=\s*.checked.)/i,Ca=/^true\/(.*)/,Da=/^\s*<!(?:\[CDATA\[|--)|(?:\]\]|--)>\s*$/g;function Ea(a,b){return B(a,"table")&&B(11!==b.nodeType?b:b.firstChild,"tr")?r(">tbody",a)[0]||a:a}function Fa(a){return a.type=(null!==a.getAttribute("type"))+"/"+a.type,a}function Ga(a){var b=Ca.exec(a.type);return b?a.type=b[1]:a.removeAttribute("type"),a}function Ha(a,b){var c,d,e,f,g,h,i,j;if(1===b.nodeType){if(W.hasData(a)&&(f=W.access(a),g=W.set(b,f),j=f.events)){delete g.handle,g.events={};for(e in j)for(c=0,d=j[e].length;c<d;c++)r.event.add(b,e,j[e][c])}X.hasData(a)&&(h=X.access(a),i=r.extend({},h),X.set(b,i))}}function Ia(a,b){var c=b.nodeName.toLowerCase();"input"===c&&ja.test(a.type)?b.checked=a.checked:"input"!==c&&"textarea"!==c||(b.defaultValue=a.defaultValue)}function Ja(a,b,c,d){b=g.apply([],b);var e,f,h,i,j,k,l=0,m=a.length,n=m-1,q=b[0],s=r.isFunction(q);if(s||m>1&&"string"==typeof q&&!o.checkClone&&Ba.test(q))return a.each(function(e){var f=a.eq(e);s&&(b[0]=q.call(this,e,f.html())),Ja(f,b,c,d)});if(m&&(e=qa(b,a[0].ownerDocument,!1,a,d),f=e.firstChild,1===e.childNodes.length&&(e=f),f||d)){for(h=r.map(na(e,"script"),Fa),i=h.length;l<m;l++)j=e,l!==n&&(j=r.clone(j,!0,!0),i&&r.merge(h,na(j,"script"))),c.call(a[l],j,l);if(i)for(k=h[h.length-1].ownerDocument,r.map(h,Ga),l=0;l<i;l++)j=h[l],la.test(j.type||"")&&!W.access(j,"globalEval")&&r.contains(k,j)&&(j.src?r._evalUrl&&r._evalUrl(j.src):p(j.textContent.replace(Da,""),k))}return a}function Ka(a,b,c){for(var d,e=b?r.filter(b,a):a,f=0;null!=(d=e[f]);f++)c||1!==d.nodeType||r.cleanData(na(d)),d.parentNode&&(c&&r.contains(d.ownerDocument,d)&&oa(na(d,"script")),d.parentNode.removeChild(d));return a}r.extend({htmlPrefilter:function(a){return a.replace(za,"<$1></$2>")},clone:function(a,b,c){var d,e,f,g,h=a.cloneNode(!0),i=r.contains(a.ownerDocument,a);if(!(o.noCloneChecked||1!==a.nodeType&&11!==a.nodeType||r.isXMLDoc(a)))for(g=na(h),f=na(a),d=0,e=f.length;d<e;d++)Ia(f[d],g[d]);if(b)if(c)for(f=f||na(a),g=g||na(h),d=0,e=f.length;d<e;d++)Ha(f[d],g[d]);else Ha(a,h);return g=na(h,"script"),g.length>0&&oa(g,!i&&na(a,"script")),h},cleanData:function(a){for(var b,c,d,e=r.event.special,f=0;void 0!==(c=a[f]);f++)if(U(c)){if(b=c[W.expando]){if(b.events)for(d in b.events)e[d]?r.event.remove(c,d):r.removeEvent(c,d,b.handle);c[W.expando]=void 0}c[X.expando]&&(c[X.expando]=void 0)}}}),r.fn.extend({detach:function(a){return Ka(this,a,!0)},remove:function(a){return Ka(this,a)},text:function(a){return T(this,function(a){return void 0===a?r.text(this):this.empty().each(function(){1!==this.nodeType&&11!==this.nodeType&&9!==this.nodeType||(this.textContent=a)})},null,a,arguments.length)},append:function(){return Ja(this,arguments,function(a){if(1===this.nodeType||11===this.nodeType||9===this.nodeType){var b=Ea(this,a);b.appendChild(a)}})},prepend:function(){return Ja(this,arguments,function(a){if(1===this.nodeType||11===this.nodeType||9===this.nodeType){var b=Ea(this,a);b.insertBefore(a,b.firstChild)}})},before:function(){return Ja(this,arguments,function(a){this.parentNode&&this.parentNode.insertBefore(a,this)})},after:function(){return Ja(this,arguments,function(a){this.parentNode&&this.parentNode.insertBefore(a,this.nextSibling)})},empty:function(){for(var a,b=0;null!=(a=this[b]);b++)1===a.nodeType&&(r.cleanData(na(a,!1)),a.textContent="");return this},clone:function(a,b){return a=null!=a&&a,b=null==b?a:b,this.map(function(){return r.clone(this,a,b)})},html:function(a){return T(this,function(a){var b=this[0]||{},c=0,d=this.length;if(void 0===a&&1===b.nodeType)return b.innerHTML;if("string"==typeof a&&!Aa.test(a)&&!ma[(ka.exec(a)||["",""])[1].toLowerCase()]){a=r.htmlPrefilter(a);try{for(;c<d;c++)b=this[c]||{},1===b.nodeType&&(r.cleanData(na(b,!1)),b.innerHTML=a);b=0}catch(e){}}b&&this.empty().append(a)},null,a,arguments.length)},replaceWith:function(){var a=[];return Ja(this,arguments,function(b){var c=this.parentNode;r.inArray(this,a)<0&&(r.cleanData(na(this)),c&&c.replaceChild(b,this))},a)}}),r.each({appendTo:"append",prependTo:"prepend",insertBefore:"before",insertAfter:"after",replaceAll:"replaceWith"},function(a,b){r.fn[a]=function(a){for(var c,d=[],e=r(a),f=e.length-1,g=0;g<=f;g++)c=g===f?this:this.clone(!0),r(e[g])[b](c),h.apply(d,c.get());return this.pushStack(d)}});var La=/^margin/,Ma=new RegExp("^("+aa+")(?!px)[a-z%]+$","i"),Na=function(b){var c=b.ownerDocument.defaultView;return c&&c.opener||(c=a),c.getComputedStyle(b)};!function(){function b(){if(i){i.style.cssText="box-sizing:border-box;position:relative;display:block;margin:auto;border:1px;padding:1px;top:1%;width:50%",i.innerHTML="",ra.appendChild(h);var b=a.getComputedStyle(i);c="1%"!==b.top,g="2px"===b.marginLeft,e="4px"===b.width,i.style.marginRight="50%",f="4px"===b.marginRight,ra.removeChild(h),i=null}}var c,e,f,g,h=d.createElement("div"),i=d.createElement("div");i.style&&(i.style.backgroundClip="content-box",i.cloneNode(!0).style.backgroundClip="",o.clearCloneStyle="content-box"===i.style.backgroundClip,h.style.cssText="border:0;width:8px;height:0;top:0;left:-9999px;padding:0;margin-top:1px;position:absolute",h.appendChild(i),r.extend(o,{pixelPosition:function(){return b(),c},boxSizingReliable:function(){return b(),e},pixelMarginRight:function(){return b(),f},reliableMarginLeft:function(){return b(),g}}))}();function Oa(a,b,c){var d,e,f,g,h=a.style;return c=c||Na(a),c&&(g=c.getPropertyValue(b)||c[b],""!==g||r.contains(a.ownerDocument,a)||(g=r.style(a,b)),!o.pixelMarginRight()&&Ma.test(g)&&La.test(b)&&(d=h.width,e=h.minWidth,f=h.maxWidth,h.minWidth=h.maxWidth=h.width=g,g=c.width,h.width=d,h.minWidth=e,h.maxWidth=f)),void 0!==g?g+"":g}function Pa(a,b){return{get:function(){return a()?void delete this.get:(this.get=b).apply(this,arguments)}}}var Qa=/^(none|table(?!-c[ea]).+)/,Ra=/^--/,Sa={position:"absolute",visibility:"hidden",display:"block"},Ta={letterSpacing:"0",fontWeight:"400"},Ua=["Webkit","Moz","ms"],Va=d.createElement("div").style;function Wa(a){if(a in Va)return a;var b=a[0].toUpperCase()+a.slice(1),c=Ua.length;while(c--)if(a=Ua[c]+b,a in Va)return a}function Xa(a){var b=r.cssProps[a];return b||(b=r.cssProps[a]=Wa(a)||a),b}function Ya(a,b,c){var d=ba.exec(b);return d?Math.max(0,d[2]-(c||0))+(d[3]||"px"):b}function Za(a,b,c,d,e){var f,g=0;for(f=c===(d?"border":"content")?4:"width"===b?1:0;f<4;f+=2)"margin"===c&&(g+=r.css(a,c+ca[f],!0,e)),d?("content"===c&&(g-=r.css(a,"padding"+ca[f],!0,e)),"margin"!==c&&(g-=r.css(a,"border"+ca[f]+"Width",!0,e))):(g+=r.css(a,"padding"+ca[f],!0,e),"padding"!==c&&(g+=r.css(a,"border"+ca[f]+"Width",!0,e)));return g}function $a(a,b,c){var d,e=Na(a),f=Oa(a,b,e),g="border-box"===r.css(a,"boxSizing",!1,e);return Ma.test(f)?f:(d=g&&(o.boxSizingReliable()||f===a.style[b]),"auto"===f&&(f=a["offset"+b[0].toUpperCase()+b.slice(1)]),f=parseFloat(f)||0,f+Za(a,b,c||(g?"border":"content"),d,e)+"px")}r.extend({cssHooks:{opacity:{get:function(a,b){if(b){var c=Oa(a,"opacity");return""===c?"1":c}}}},cssNumber:{animationIterationCount:!0,columnCount:!0,fillOpacity:!0,flexGrow:!0,flexShrink:!0,fontWeight:!0,lineHeight:!0,opacity:!0,order:!0,orphans:!0,widows:!0,zIndex:!0,zoom:!0},cssProps:{"float":"cssFloat"},style:function(a,b,c,d){if(a&&3!==a.nodeType&&8!==a.nodeType&&a.style){var e,f,g,h=r.camelCase(b),i=Ra.test(b),j=a.style;return i||(b=Xa(h)),g=r.cssHooks[b]||r.cssHooks[h],void 0===c?g&&"get"in g&&void 0!==(e=g.get(a,!1,d))?e:j[b]:(f=typeof c,"string"===f&&(e=ba.exec(c))&&e[1]&&(c=fa(a,b,e),f="number"),null!=c&&c===c&&("number"===f&&(c+=e&&e[3]||(r.cssNumber[h]?"":"px")),o.clearCloneStyle||""!==c||0!==b.indexOf("background")||(j[b]="inherit"),g&&"set"in g&&void 0===(c=g.set(a,c,d))||(i?j.setProperty(b,c):j[b]=c)),void 0)}},css:function(a,b,c,d){var e,f,g,h=r.camelCase(b),i=Ra.test(b);return i||(b=Xa(h)),g=r.cssHooks[b]||r.cssHooks[h],g&&"get"in g&&(e=g.get(a,!0,c)),void 0===e&&(e=Oa(a,b,d)),"normal"===e&&b in Ta&&(e=Ta[b]),""===c||c?(f=parseFloat(e),c===!0||isFinite(f)?f||0:e):e}}),r.each(["height","width"],function(a,b){r.cssHooks[b]={get:function(a,c,d){if(c)return!Qa.test(r.css(a,"display"))||a.getClientRects().length&&a.getBoundingClientRect().width?$a(a,b,d):ea(a,Sa,function(){return $a(a,b,d)})},set:function(a,c,d){var e,f=d&&Na(a),g=d&&Za(a,b,d,"border-box"===r.css(a,"boxSizing",!1,f),f);return g&&(e=ba.exec(c))&&"px"!==(e[3]||"px")&&(a.style[b]=c,c=r.css(a,b)),Ya(a,c,g)}}}),r.cssHooks.marginLeft=Pa(o.reliableMarginLeft,function(a,b){if(b)return(parseFloat(Oa(a,"marginLeft"))||a.getBoundingClientRect().left-ea(a,{marginLeft:0},function(){return a.getBoundingClientRect().left}))+"px"}),r.each({margin:"",padding:"",border:"Width"},function(a,b){r.cssHooks[a+b]={expand:function(c){for(var d=0,e={},f="string"==typeof c?c.split(" "):[c];d<4;d++)e[a+ca[d]+b]=f[d]||f[d-2]||f[0];return e}},La.test(a)||(r.cssHooks[a+b].set=Ya)}),r.fn.extend({css:function(a,b){return T(this,function(a,b,c){var d,e,f={},g=0;if(Array.isArray(b)){for(d=Na(a),e=b.length;g<e;g++)f[b[g]]=r.css(a,b[g],!1,d);return f}return void 0!==c?r.style(a,b,c):r.css(a,b)},a,b,arguments.length>1)}});function _a(a,b,c,d,e){return new _a.prototype.init(a,b,c,d,e)}r.Tween=_a,_a.prototype={constructor:_a,init:function(a,b,c,d,e,f){this.elem=a,this.prop=c,this.easing=e||r.easing._default,this.options=b,this.start=this.now=this.cur(),this.end=d,this.unit=f||(r.cssNumber[c]?"":"px")},cur:function(){var a=_a.propHooks[this.prop];return a&&a.get?a.get(this):_a.propHooks._default.get(this)},run:function(a){var b,c=_a.propHooks[this.prop];return this.options.duration?this.pos=b=r.easing[this.easing](a,this.options.duration*a,0,1,this.options.duration):this.pos=b=a,this.now=(this.end-this.start)*b+this.start,this.options.step&&this.options.step.call(this.elem,this.now,this),c&&c.set?c.set(this):_a.propHooks._default.set(this),this}},_a.prototype.init.prototype=_a.prototype,_a.propHooks={_default:{get:function(a){var b;return 1!==a.elem.nodeType||null!=a.elem[a.prop]&&null==a.elem.style[a.prop]?a.elem[a.prop]:(b=r.css(a.elem,a.prop,""),b&&"auto"!==b?b:0)},set:function(a){r.fx.step[a.prop]?r.fx.step[a.prop](a):1!==a.elem.nodeType||null==a.elem.style[r.cssProps[a.prop]]&&!r.cssHooks[a.prop]?a.elem[a.prop]=a.now:r.style(a.elem,a.prop,a.now+a.unit)}}},_a.propHooks.scrollTop=_a.propHooks.scrollLeft={set:function(a){a.elem.nodeType&&a.elem.parentNode&&(a.elem[a.prop]=a.now)}},r.easing={linear:function(a){return a},swing:function(a){return.5-Math.cos(a*Math.PI)/2},_default:"swing"},r.fx=_a.prototype.init,r.fx.step={};var ab,bb,cb=/^(?:toggle|show|hide)$/,db=/queueHooks$/;function eb(){bb&&(d.hidden===!1&&a.requestAnimationFrame?a.requestAnimationFrame(eb):a.setTimeout(eb,r.fx.interval),r.fx.tick())}function fb(){return a.setTimeout(function(){ab=void 0}),ab=r.now()}function gb(a,b){var c,d=0,e={height:a};for(b=b?1:0;d<4;d+=2-b)c=ca[d],e["margin"+c]=e["padding"+c]=a;return b&&(e.opacity=e.width=a),e}function hb(a,b,c){for(var d,e=(kb.tweeners[b]||[]).concat(kb.tweeners["*"]),f=0,g=e.length;f<g;f++)if(d=e[f].call(c,b,a))return d}function ib(a,b,c){var d,e,f,g,h,i,j,k,l="width"in b||"height"in b,m=this,n={},o=a.style,p=a.nodeType&&da(a),q=W.get(a,"fxshow");c.queue||(g=r._queueHooks(a,"fx"),null==g.unqueued&&(g.unqueued=0,h=g.empty.fire,g.empty.fire=function(){g.unqueued||h()}),g.unqueued++,m.always(function(){m.always(function(){g.unqueued--,r.queue(a,"fx").length||g.empty.fire()})}));for(d in b)if(e=b[d],cb.test(e)){if(delete b[d],f=f||"toggle"===e,e===(p?"hide":"show")){if("show"!==e||!q||void 0===q[d])continue;p=!0}n[d]=q&&q[d]||r.style(a,d)}if(i=!r.isEmptyObject(b),i||!r.isEmptyObject(n)){l&&1===a.nodeType&&(c.overflow=[o.overflow,o.overflowX,o.overflowY],j=q&&q.display,null==j&&(j=W.get(a,"display")),k=r.css(a,"display"),"none"===k&&(j?k=j:(ia([a],!0),j=a.style.display||j,k=r.css(a,"display"),ia([a]))),("inline"===k||"inline-block"===k&&null!=j)&&"none"===r.css(a,"float")&&(i||(m.done(function(){o.display=j}),null==j&&(k=o.display,j="none"===k?"":k)),o.display="inline-block")),c.overflow&&(o.overflow="hidden",m.always(function(){o.overflow=c.overflow[0],o.overflowX=c.overflow[1],o.overflowY=c.overflow[2]})),i=!1;for(d in n)i||(q?"hidden"in q&&(p=q.hidden):q=W.access(a,"fxshow",{display:j}),f&&(q.hidden=!p),p&&ia([a],!0),m.done(function(){p||ia([a]),W.remove(a,"fxshow");for(d in n)r.style(a,d,n[d])})),i=hb(p?q[d]:0,d,m),d in q||(q[d]=i.start,p&&(i.end=i.start,i.start=0))}}function jb(a,b){var c,d,e,f,g;for(c in a)if(d=r.camelCase(c),e=b[d],f=a[c],Array.isArray(f)&&(e=f[1],f=a[c]=f[0]),c!==d&&(a[d]=f,delete a[c]),g=r.cssHooks[d],g&&"expand"in g){f=g.expand(f),delete a[d];for(c in f)c in a||(a[c]=f[c],b[c]=e)}else b[d]=e}function kb(a,b,c){var d,e,f=0,g=kb.prefilters.length,h=r.Deferred().always(function(){delete i.elem}),i=function(){if(e)return!1;for(var b=ab||fb(),c=Math.max(0,j.startTime+j.duration-b),d=c/j.duration||0,f=1-d,g=0,i=j.tweens.length;g<i;g++)j.tweens[g].run(f);return h.notifyWith(a,[j,f,c]),f<1&&i?c:(i||h.notifyWith(a,[j,1,0]),h.resolveWith(a,[j]),!1)},j=h.promise({elem:a,props:r.extend({},b),opts:r.extend(!0,{specialEasing:{},easing:r.easing._default},c),originalProperties:b,originalOptions:c,startTime:ab||fb(),duration:c.duration,tweens:[],createTween:function(b,c){var d=r.Tween(a,j.opts,b,c,j.opts.specialEasing[b]||j.opts.easing);return j.tweens.push(d),d},stop:function(b){var c=0,d=b?j.tweens.length:0;if(e)return this;for(e=!0;c<d;c++)j.tweens[c].run(1);return b?(h.notifyWith(a,[j,1,0]),h.resolveWith(a,[j,b])):h.rejectWith(a,[j,b]),this}}),k=j.props;for(jb(k,j.opts.specialEasing);f<g;f++)if(d=kb.prefilters[f].call(j,a,k,j.opts))return r.isFunction(d.stop)&&(r._queueHooks(j.elem,j.opts.queue).stop=r.proxy(d.stop,d)),d;return r.map(k,hb,j),r.isFunction(j.opts.start)&&j.opts.start.call(a,j),j.progress(j.opts.progress).done(j.opts.done,j.opts.complete).fail(j.opts.fail).always(j.opts.always),r.fx.timer(r.extend(i,{elem:a,anim:j,queue:j.opts.queue})),j}r.Animation=r.extend(kb,{tweeners:{"*":[function(a,b){var c=this.createTween(a,b);return fa(c.elem,a,ba.exec(b),c),c}]},tweener:function(a,b){r.isFunction(a)?(b=a,a=["*"]):a=a.match(L);for(var c,d=0,e=a.length;d<e;d++)c=a[d],kb.tweeners[c]=kb.tweeners[c]||[],kb.tweeners[c].unshift(b)},prefilters:[ib],prefilter:function(a,b){b?kb.prefilters.unshift(a):kb.prefilters.push(a)}}),r.speed=function(a,b,c){var d=a&&"object"==typeof a?r.extend({},a):{complete:c||!c&&b||r.isFunction(a)&&a,duration:a,easing:c&&b||b&&!r.isFunction(b)&&b};return r.fx.off?d.duration=0:"number"!=typeof d.duration&&(d.duration in r.fx.speeds?d.duration=r.fx.speeds[d.duration]:d.duration=r.fx.speeds._default),null!=d.queue&&d.queue!==!0||(d.queue="fx"),d.old=d.complete,d.complete=function(){r.isFunction(d.old)&&d.old.call(this),d.queue&&r.dequeue(this,d.queue)},d},r.fn.extend({fadeTo:function(a,b,c,d){return this.filter(da).css("opacity",0).show().end().animate({opacity:b},a,c,d)},animate:function(a,b,c,d){var e=r.isEmptyObject(a),f=r.speed(b,c,d),g=function(){var b=kb(this,r.extend({},a),f);(e||W.get(this,"finish"))&&b.stop(!0)};return g.finish=g,e||f.queue===!1?this.each(g):this.queue(f.queue,g)},stop:function(a,b,c){var d=function(a){var b=a.stop;delete a.stop,b(c)};return"string"!=typeof a&&(c=b,b=a,a=void 0),b&&a!==!1&&this.queue(a||"fx",[]),this.each(function(){var b=!0,e=null!=a&&a+"queueHooks",f=r.timers,g=W.get(this);if(e)g[e]&&g[e].stop&&d(g[e]);else for(e in g)g[e]&&g[e].stop&&db.test(e)&&d(g[e]);for(e=f.length;e--;)f[e].elem!==this||null!=a&&f[e].queue!==a||(f[e].anim.stop(c),b=!1,f.splice(e,1));!b&&c||r.dequeue(this,a)})},finish:function(a){return a!==!1&&(a=a||"fx"),this.each(function(){var b,c=W.get(this),d=c[a+"queue"],e=c[a+"queueHooks"],f=r.timers,g=d?d.length:0;for(c.finish=!0,r.queue(this,a,[]),e&&e.stop&&e.stop.call(this,!0),b=f.length;b--;)f[b].elem===this&&f[b].queue===a&&(f[b].anim.stop(!0),f.splice(b,1));for(b=0;b<g;b++)d[b]&&d[b].finish&&d[b].finish.call(this);delete c.finish})}}),r.each(["toggle","show","hide"],function(a,b){var c=r.fn[b];r.fn[b]=function(a,d,e){return null==a||"boolean"==typeof a?c.apply(this,arguments):this.animate(gb(b,!0),a,d,e)}}),r.each({slideDown:gb("show"),slideUp:gb("hide"),slideToggle:gb("toggle"),fadeIn:{opacity:"show"},fadeOut:{opacity:"hide"},fadeToggle:{opacity:"toggle"}},function(a,b){r.fn[a]=function(a,c,d){return this.animate(b,a,c,d)}}),r.timers=[],r.fx.tick=function(){var a,b=0,c=r.timers;for(ab=r.now();b<c.length;b++)a=c[b],a()||c[b]!==a||c.splice(b--,1);c.length||r.fx.stop(),ab=void 0},r.fx.timer=function(a){r.timers.push(a),r.fx.start()},r.fx.interval=13,r.fx.start=function(){bb||(bb=!0,eb())},r.fx.stop=function(){bb=null},r.fx.speeds={slow:600,fast:200,_default:400},r.fn.delay=function(b,c){return b=r.fx?r.fx.speeds[b]||b:b,c=c||"fx",this.queue(c,function(c,d){var e=a.setTimeout(c,b);d.stop=function(){a.clearTimeout(e)}})},function(){var a=d.createElement("input"),b=d.createElement("select"),c=b.appendChild(d.createElement("option"));a.type="checkbox",o.checkOn=""!==a.value,o.optSelected=c.selected,a=d.createElement("input"),a.value="t",a.type="radio",o.radioValue="t"===a.value}();var lb,mb=r.expr.attrHandle;r.fn.extend({attr:function(a,b){return T(this,r.attr,a,b,arguments.length>1)},removeAttr:function(a){return this.each(function(){r.removeAttr(this,a)})}}),r.extend({attr:function(a,b,c){var d,e,f=a.nodeType;if(3!==f&&8!==f&&2!==f)return"undefined"==typeof a.getAttribute?r.prop(a,b,c):(1===f&&r.isXMLDoc(a)||(e=r.attrHooks[b.toLowerCase()]||(r.expr.match.bool.test(b)?lb:void 0)),void 0!==c?null===c?void r.removeAttr(a,b):e&&"set"in e&&void 0!==(d=e.set(a,c,b))?d:(a.setAttribute(b,c+""),c):e&&"get"in e&&null!==(d=e.get(a,b))?d:(d=r.find.attr(a,b),
null==d?void 0:d))},attrHooks:{type:{set:function(a,b){if(!o.radioValue&&"radio"===b&&B(a,"input")){var c=a.value;return a.setAttribute("type",b),c&&(a.value=c),b}}}},removeAttr:function(a,b){var c,d=0,e=b&&b.match(L);if(e&&1===a.nodeType)while(c=e[d++])a.removeAttribute(c)}}),lb={set:function(a,b,c){return b===!1?r.removeAttr(a,c):a.setAttribute(c,c),c}},r.each(r.expr.match.bool.source.match(/\w+/g),function(a,b){var c=mb[b]||r.find.attr;mb[b]=function(a,b,d){var e,f,g=b.toLowerCase();return d||(f=mb[g],mb[g]=e,e=null!=c(a,b,d)?g:null,mb[g]=f),e}});var nb=/^(?:input|select|textarea|button)$/i,ob=/^(?:a|area)$/i;r.fn.extend({prop:function(a,b){return T(this,r.prop,a,b,arguments.length>1)},removeProp:function(a){return this.each(function(){delete this[r.propFix[a]||a]})}}),r.extend({prop:function(a,b,c){var d,e,f=a.nodeType;if(3!==f&&8!==f&&2!==f)return 1===f&&r.isXMLDoc(a)||(b=r.propFix[b]||b,e=r.propHooks[b]),void 0!==c?e&&"set"in e&&void 0!==(d=e.set(a,c,b))?d:a[b]=c:e&&"get"in e&&null!==(d=e.get(a,b))?d:a[b]},propHooks:{tabIndex:{get:function(a){var b=r.find.attr(a,"tabindex");return b?parseInt(b,10):nb.test(a.nodeName)||ob.test(a.nodeName)&&a.href?0:-1}}},propFix:{"for":"htmlFor","class":"className"}}),o.optSelected||(r.propHooks.selected={get:function(a){var b=a.parentNode;return b&&b.parentNode&&b.parentNode.selectedIndex,null},set:function(a){var b=a.parentNode;b&&(b.selectedIndex,b.parentNode&&b.parentNode.selectedIndex)}}),r.each(["tabIndex","readOnly","maxLength","cellSpacing","cellPadding","rowSpan","colSpan","useMap","frameBorder","contentEditable"],function(){r.propFix[this.toLowerCase()]=this});function pb(a){var b=a.match(L)||[];return b.join(" ")}function qb(a){return a.getAttribute&&a.getAttribute("class")||""}r.fn.extend({addClass:function(a){var b,c,d,e,f,g,h,i=0;if(r.isFunction(a))return this.each(function(b){r(this).addClass(a.call(this,b,qb(this)))});if("string"==typeof a&&a){b=a.match(L)||[];while(c=this[i++])if(e=qb(c),d=1===c.nodeType&&" "+pb(e)+" "){g=0;while(f=b[g++])d.indexOf(" "+f+" ")<0&&(d+=f+" ");h=pb(d),e!==h&&c.setAttribute("class",h)}}return this},removeClass:function(a){var b,c,d,e,f,g,h,i=0;if(r.isFunction(a))return this.each(function(b){r(this).removeClass(a.call(this,b,qb(this)))});if(!arguments.length)return this.attr("class","");if("string"==typeof a&&a){b=a.match(L)||[];while(c=this[i++])if(e=qb(c),d=1===c.nodeType&&" "+pb(e)+" "){g=0;while(f=b[g++])while(d.indexOf(" "+f+" ")>-1)d=d.replace(" "+f+" "," ");h=pb(d),e!==h&&c.setAttribute("class",h)}}return this},toggleClass:function(a,b){var c=typeof a;return"boolean"==typeof b&&"string"===c?b?this.addClass(a):this.removeClass(a):r.isFunction(a)?this.each(function(c){r(this).toggleClass(a.call(this,c,qb(this),b),b)}):this.each(function(){var b,d,e,f;if("string"===c){d=0,e=r(this),f=a.match(L)||[];while(b=f[d++])e.hasClass(b)?e.removeClass(b):e.addClass(b)}else void 0!==a&&"boolean"!==c||(b=qb(this),b&&W.set(this,"__className__",b),this.setAttribute&&this.setAttribute("class",b||a===!1?"":W.get(this,"__className__")||""))})},hasClass:function(a){var b,c,d=0;b=" "+a+" ";while(c=this[d++])if(1===c.nodeType&&(" "+pb(qb(c))+" ").indexOf(b)>-1)return!0;return!1}});var rb=/\r/g;r.fn.extend({val:function(a){var b,c,d,e=this[0];{if(arguments.length)return d=r.isFunction(a),this.each(function(c){var e;1===this.nodeType&&(e=d?a.call(this,c,r(this).val()):a,null==e?e="":"number"==typeof e?e+="":Array.isArray(e)&&(e=r.map(e,function(a){return null==a?"":a+""})),b=r.valHooks[this.type]||r.valHooks[this.nodeName.toLowerCase()],b&&"set"in b&&void 0!==b.set(this,e,"value")||(this.value=e))});if(e)return b=r.valHooks[e.type]||r.valHooks[e.nodeName.toLowerCase()],b&&"get"in b&&void 0!==(c=b.get(e,"value"))?c:(c=e.value,"string"==typeof c?c.replace(rb,""):null==c?"":c)}}}),r.extend({valHooks:{option:{get:function(a){var b=r.find.attr(a,"value");return null!=b?b:pb(r.text(a))}},select:{get:function(a){var b,c,d,e=a.options,f=a.selectedIndex,g="select-one"===a.type,h=g?null:[],i=g?f+1:e.length;for(d=f<0?i:g?f:0;d<i;d++)if(c=e[d],(c.selected||d===f)&&!c.disabled&&(!c.parentNode.disabled||!B(c.parentNode,"optgroup"))){if(b=r(c).val(),g)return b;h.push(b)}return h},set:function(a,b){var c,d,e=a.options,f=r.makeArray(b),g=e.length;while(g--)d=e[g],(d.selected=r.inArray(r.valHooks.option.get(d),f)>-1)&&(c=!0);return c||(a.selectedIndex=-1),f}}}}),r.each(["radio","checkbox"],function(){r.valHooks[this]={set:function(a,b){if(Array.isArray(b))return a.checked=r.inArray(r(a).val(),b)>-1}},o.checkOn||(r.valHooks[this].get=function(a){return null===a.getAttribute("value")?"on":a.value})});var sb=/^(?:focusinfocus|focusoutblur)$/;r.extend(r.event,{trigger:function(b,c,e,f){var g,h,i,j,k,m,n,o=[e||d],p=l.call(b,"type")?b.type:b,q=l.call(b,"namespace")?b.namespace.split("."):[];if(h=i=e=e||d,3!==e.nodeType&&8!==e.nodeType&&!sb.test(p+r.event.triggered)&&(p.indexOf(".")>-1&&(q=p.split("."),p=q.shift(),q.sort()),k=p.indexOf(":")<0&&"on"+p,b=b[r.expando]?b:new r.Event(p,"object"==typeof b&&b),b.isTrigger=f?2:3,b.namespace=q.join("."),b.rnamespace=b.namespace?new RegExp("(^|\\.)"+q.join("\\.(?:.*\\.|)")+"(\\.|$)"):null,b.result=void 0,b.target||(b.target=e),c=null==c?[b]:r.makeArray(c,[b]),n=r.event.special[p]||{},f||!n.trigger||n.trigger.apply(e,c)!==!1)){if(!f&&!n.noBubble&&!r.isWindow(e)){for(j=n.delegateType||p,sb.test(j+p)||(h=h.parentNode);h;h=h.parentNode)o.push(h),i=h;i===(e.ownerDocument||d)&&o.push(i.defaultView||i.parentWindow||a)}g=0;while((h=o[g++])&&!b.isPropagationStopped())b.type=g>1?j:n.bindType||p,m=(W.get(h,"events")||{})[b.type]&&W.get(h,"handle"),m&&m.apply(h,c),m=k&&h[k],m&&m.apply&&U(h)&&(b.result=m.apply(h,c),b.result===!1&&b.preventDefault());return b.type=p,f||b.isDefaultPrevented()||n._default&&n._default.apply(o.pop(),c)!==!1||!U(e)||k&&r.isFunction(e[p])&&!r.isWindow(e)&&(i=e[k],i&&(e[k]=null),r.event.triggered=p,e[p](),r.event.triggered=void 0,i&&(e[k]=i)),b.result}},simulate:function(a,b,c){var d=r.extend(new r.Event,c,{type:a,isSimulated:!0});r.event.trigger(d,null,b)}}),r.fn.extend({trigger:function(a,b){return this.each(function(){r.event.trigger(a,b,this)})},triggerHandler:function(a,b){var c=this[0];if(c)return r.event.trigger(a,b,c,!0)}}),r.each("blur focus focusin focusout resize scroll click dblclick mousedown mouseup mousemove mouseover mouseout mouseenter mouseleave change select submit keydown keypress keyup contextmenu".split(" "),function(a,b){r.fn[b]=function(a,c){return arguments.length>0?this.on(b,null,a,c):this.trigger(b)}}),r.fn.extend({hover:function(a,b){return this.mouseenter(a).mouseleave(b||a)}}),o.focusin="onfocusin"in a,o.focusin||r.each({focus:"focusin",blur:"focusout"},function(a,b){var c=function(a){r.event.simulate(b,a.target,r.event.fix(a))};r.event.special[b]={setup:function(){var d=this.ownerDocument||this,e=W.access(d,b);e||d.addEventListener(a,c,!0),W.access(d,b,(e||0)+1)},teardown:function(){var d=this.ownerDocument||this,e=W.access(d,b)-1;e?W.access(d,b,e):(d.removeEventListener(a,c,!0),W.remove(d,b))}}});var tb=a.location,ub=r.now(),vb=/\?/;r.parseXML=function(b){var c;if(!b||"string"!=typeof b)return null;try{c=(new a.DOMParser).parseFromString(b,"text/xml")}catch(d){c=void 0}return c&&!c.getElementsByTagName("parsererror").length||r.error("Invalid XML: "+b),c};var wb=/\[\]$/,xb=/\r?\n/g,yb=/^(?:submit|button|image|reset|file)$/i,zb=/^(?:input|select|textarea|keygen)/i;function Ab(a,b,c,d){var e;if(Array.isArray(b))r.each(b,function(b,e){c||wb.test(a)?d(a,e):Ab(a+"["+("object"==typeof e&&null!=e?b:"")+"]",e,c,d)});else if(c||"object"!==r.type(b))d(a,b);else for(e in b)Ab(a+"["+e+"]",b[e],c,d)}r.param=function(a,b){var c,d=[],e=function(a,b){var c=r.isFunction(b)?b():b;d[d.length]=encodeURIComponent(a)+"="+encodeURIComponent(null==c?"":c)};if(Array.isArray(a)||a.jquery&&!r.isPlainObject(a))r.each(a,function(){e(this.name,this.value)});else for(c in a)Ab(c,a[c],b,e);return d.join("&")},r.fn.extend({serialize:function(){return r.param(this.serializeArray())},serializeArray:function(){return this.map(function(){var a=r.prop(this,"elements");return a?r.makeArray(a):this}).filter(function(){var a=this.type;return this.name&&!r(this).is(":disabled")&&zb.test(this.nodeName)&&!yb.test(a)&&(this.checked||!ja.test(a))}).map(function(a,b){var c=r(this).val();return null==c?null:Array.isArray(c)?r.map(c,function(a){return{name:b.name,value:a.replace(xb,"\r\n")}}):{name:b.name,value:c.replace(xb,"\r\n")}}).get()}});var Bb=/%20/g,Cb=/#.*$/,Db=/([?&])_=[^&]*/,Eb=/^(.*?):[ \t]*([^\r\n]*)$/gm,Fb=/^(?:about|app|app-storage|.+-extension|file|res|widget):$/,Gb=/^(?:GET|HEAD)$/,Hb=/^\/\//,Ib={},Jb={},Kb="*/".concat("*"),Lb=d.createElement("a");Lb.href=tb.href;function Mb(a){return function(b,c){"string"!=typeof b&&(c=b,b="*");var d,e=0,f=b.toLowerCase().match(L)||[];if(r.isFunction(c))while(d=f[e++])"+"===d[0]?(d=d.slice(1)||"*",(a[d]=a[d]||[]).unshift(c)):(a[d]=a[d]||[]).push(c)}}function Nb(a,b,c,d){var e={},f=a===Jb;function g(h){var i;return e[h]=!0,r.each(a[h]||[],function(a,h){var j=h(b,c,d);return"string"!=typeof j||f||e[j]?f?!(i=j):void 0:(b.dataTypes.unshift(j),g(j),!1)}),i}return g(b.dataTypes[0])||!e["*"]&&g("*")}function Ob(a,b){var c,d,e=r.ajaxSettings.flatOptions||{};for(c in b)void 0!==b[c]&&((e[c]?a:d||(d={}))[c]=b[c]);return d&&r.extend(!0,a,d),a}function Pb(a,b,c){var d,e,f,g,h=a.contents,i=a.dataTypes;while("*"===i[0])i.shift(),void 0===d&&(d=a.mimeType||b.getResponseHeader("Content-Type"));if(d)for(e in h)if(h[e]&&h[e].test(d)){i.unshift(e);break}if(i[0]in c)f=i[0];else{for(e in c){if(!i[0]||a.converters[e+" "+i[0]]){f=e;break}g||(g=e)}f=f||g}if(f)return f!==i[0]&&i.unshift(f),c[f]}function Qb(a,b,c,d){var e,f,g,h,i,j={},k=a.dataTypes.slice();if(k[1])for(g in a.converters)j[g.toLowerCase()]=a.converters[g];f=k.shift();while(f)if(a.responseFields[f]&&(c[a.responseFields[f]]=b),!i&&d&&a.dataFilter&&(b=a.dataFilter(b,a.dataType)),i=f,f=k.shift())if("*"===f)f=i;else if("*"!==i&&i!==f){if(g=j[i+" "+f]||j["* "+f],!g)for(e in j)if(h=e.split(" "),h[1]===f&&(g=j[i+" "+h[0]]||j["* "+h[0]])){g===!0?g=j[e]:j[e]!==!0&&(f=h[0],k.unshift(h[1]));break}if(g!==!0)if(g&&a["throws"])b=g(b);else try{b=g(b)}catch(l){return{state:"parsererror",error:g?l:"No conversion from "+i+" to "+f}}}return{state:"success",data:b}}r.extend({active:0,lastModified:{},etag:{},ajaxSettings:{url:tb.href,type:"GET",isLocal:Fb.test(tb.protocol),global:!0,processData:!0,async:!0,contentType:"application/x-www-form-urlencoded; charset=UTF-8",accepts:{"*":Kb,text:"text/plain",html:"text/html",xml:"application/xml, text/xml",json:"application/json, text/javascript"},contents:{xml:/\bxml\b/,html:/\bhtml/,json:/\bjson\b/},responseFields:{xml:"responseXML",text:"responseText",json:"responseJSON"},converters:{"* text":String,"text html":!0,"text json":JSON.parse,"text xml":r.parseXML},flatOptions:{url:!0,context:!0}},ajaxSetup:function(a,b){return b?Ob(Ob(a,r.ajaxSettings),b):Ob(r.ajaxSettings,a)},ajaxPrefilter:Mb(Ib),ajaxTransport:Mb(Jb),ajax:function(b,c){"object"==typeof b&&(c=b,b=void 0),c=c||{};var e,f,g,h,i,j,k,l,m,n,o=r.ajaxSetup({},c),p=o.context||o,q=o.context&&(p.nodeType||p.jquery)?r(p):r.event,s=r.Deferred(),t=r.Callbacks("once memory"),u=o.statusCode||{},v={},w={},x="canceled",y={readyState:0,getResponseHeader:function(a){var b;if(k){if(!h){h={};while(b=Eb.exec(g))h[b[1].toLowerCase()]=b[2]}b=h[a.toLowerCase()]}return null==b?null:b},getAllResponseHeaders:function(){return k?g:null},setRequestHeader:function(a,b){return null==k&&(a=w[a.toLowerCase()]=w[a.toLowerCase()]||a,v[a]=b),this},overrideMimeType:function(a){return null==k&&(o.mimeType=a),this},statusCode:function(a){var b;if(a)if(k)y.always(a[y.status]);else for(b in a)u[b]=[u[b],a[b]];return this},abort:function(a){var b=a||x;return e&&e.abort(b),A(0,b),this}};if(s.promise(y),o.url=((b||o.url||tb.href)+"").replace(Hb,tb.protocol+"//"),o.type=c.method||c.type||o.method||o.type,o.dataTypes=(o.dataType||"*").toLowerCase().match(L)||[""],null==o.crossDomain){j=d.createElement("a");try{j.href=o.url,j.href=j.href,o.crossDomain=Lb.protocol+"//"+Lb.host!=j.protocol+"//"+j.host}catch(z){o.crossDomain=!0}}if(o.data&&o.processData&&"string"!=typeof o.data&&(o.data=r.param(o.data,o.traditional)),Nb(Ib,o,c,y),k)return y;l=r.event&&o.global,l&&0===r.active++&&r.event.trigger("ajaxStart"),o.type=o.type.toUpperCase(),o.hasContent=!Gb.test(o.type),f=o.url.replace(Cb,""),o.hasContent?o.data&&o.processData&&0===(o.contentType||"").indexOf("application/x-www-form-urlencoded")&&(o.data=o.data.replace(Bb,"+")):(n=o.url.slice(f.length),o.data&&(f+=(vb.test(f)?"&":"?")+o.data,delete o.data),o.cache===!1&&(f=f.replace(Db,"$1"),n=(vb.test(f)?"&":"?")+"_="+ub++ +n),o.url=f+n),o.ifModified&&(r.lastModified[f]&&y.setRequestHeader("If-Modified-Since",r.lastModified[f]),r.etag[f]&&y.setRequestHeader("If-None-Match",r.etag[f])),(o.data&&o.hasContent&&o.contentType!==!1||c.contentType)&&y.setRequestHeader("Content-Type",o.contentType),y.setRequestHeader("Accept",o.dataTypes[0]&&o.accepts[o.dataTypes[0]]?o.accepts[o.dataTypes[0]]+("*"!==o.dataTypes[0]?", "+Kb+"; q=0.01":""):o.accepts["*"]);for(m in o.headers)y.setRequestHeader(m,o.headers[m]);if(o.beforeSend&&(o.beforeSend.call(p,y,o)===!1||k))return y.abort();if(x="abort",t.add(o.complete),y.done(o.success),y.fail(o.error),e=Nb(Jb,o,c,y)){if(y.readyState=1,l&&q.trigger("ajaxSend",[y,o]),k)return y;o.async&&o.timeout>0&&(i=a.setTimeout(function(){y.abort("timeout")},o.timeout));try{k=!1,e.send(v,A)}catch(z){if(k)throw z;A(-1,z)}}else A(-1,"No Transport");function A(b,c,d,h){var j,m,n,v,w,x=c;k||(k=!0,i&&a.clearTimeout(i),e=void 0,g=h||"",y.readyState=b>0?4:0,j=b>=200&&b<300||304===b,d&&(v=Pb(o,y,d)),v=Qb(o,v,y,j),j?(o.ifModified&&(w=y.getResponseHeader("Last-Modified"),w&&(r.lastModified[f]=w),w=y.getResponseHeader("etag"),w&&(r.etag[f]=w)),204===b||"HEAD"===o.type?x="nocontent":304===b?x="notmodified":(x=v.state,m=v.data,n=v.error,j=!n)):(n=x,!b&&x||(x="error",b<0&&(b=0))),y.status=b,y.statusText=(c||x)+"",j?s.resolveWith(p,[m,x,y]):s.rejectWith(p,[y,x,n]),y.statusCode(u),u=void 0,l&&q.trigger(j?"ajaxSuccess":"ajaxError",[y,o,j?m:n]),t.fireWith(p,[y,x]),l&&(q.trigger("ajaxComplete",[y,o]),--r.active||r.event.trigger("ajaxStop")))}return y},getJSON:function(a,b,c){return r.get(a,b,c,"json")},getScript:function(a,b){return r.get(a,void 0,b,"script")}}),r.each(["get","post"],function(a,b){r[b]=function(a,c,d,e){return r.isFunction(c)&&(e=e||d,d=c,c=void 0),r.ajax(r.extend({url:a,type:b,dataType:e,data:c,success:d},r.isPlainObject(a)&&a))}}),r._evalUrl=function(a){return r.ajax({url:a,type:"GET",dataType:"script",cache:!0,async:!1,global:!1,"throws":!0})},r.fn.extend({wrapAll:function(a){var b;return this[0]&&(r.isFunction(a)&&(a=a.call(this[0])),b=r(a,this[0].ownerDocument).eq(0).clone(!0),this[0].parentNode&&b.insertBefore(this[0]),b.map(function(){var a=this;while(a.firstElementChild)a=a.firstElementChild;return a}).append(this)),this},wrapInner:function(a){return r.isFunction(a)?this.each(function(b){r(this).wrapInner(a.call(this,b))}):this.each(function(){var b=r(this),c=b.contents();c.length?c.wrapAll(a):b.append(a)})},wrap:function(a){var b=r.isFunction(a);return this.each(function(c){r(this).wrapAll(b?a.call(this,c):a)})},unwrap:function(a){return this.parent(a).not("body").each(function(){r(this).replaceWith(this.childNodes)}),this}}),r.expr.pseudos.hidden=function(a){return!r.expr.pseudos.visible(a)},r.expr.pseudos.visible=function(a){return!!(a.offsetWidth||a.offsetHeight||a.getClientRects().length)},r.ajaxSettings.xhr=function(){try{return new a.XMLHttpRequest}catch(b){}};var Rb={0:200,1223:204},Sb=r.ajaxSettings.xhr();o.cors=!!Sb&&"withCredentials"in Sb,o.ajax=Sb=!!Sb,r.ajaxTransport(function(b){var c,d;if(o.cors||Sb&&!b.crossDomain)return{send:function(e,f){var g,h=b.xhr();if(h.open(b.type,b.url,b.async,b.username,b.password),b.xhrFields)for(g in b.xhrFields)h[g]=b.xhrFields[g];b.mimeType&&h.overrideMimeType&&h.overrideMimeType(b.mimeType),b.crossDomain||e["X-Requested-With"]||(e["X-Requested-With"]="XMLHttpRequest");for(g in e)h.setRequestHeader(g,e[g]);c=function(a){return function(){c&&(c=d=h.onload=h.onerror=h.onabort=h.onreadystatechange=null,"abort"===a?h.abort():"error"===a?"number"!=typeof h.status?f(0,"error"):f(h.status,h.statusText):f(Rb[h.status]||h.status,h.statusText,"text"!==(h.responseType||"text")||"string"!=typeof h.responseText?{binary:h.response}:{text:h.responseText},h.getAllResponseHeaders()))}},h.onload=c(),d=h.onerror=c("error"),void 0!==h.onabort?h.onabort=d:h.onreadystatechange=function(){4===h.readyState&&a.setTimeout(function(){c&&d()})},c=c("abort");try{h.send(b.hasContent&&b.data||null)}catch(i){if(c)throw i}},abort:function(){c&&c()}}}),r.ajaxPrefilter(function(a){a.crossDomain&&(a.contents.script=!1)}),r.ajaxSetup({accepts:{script:"text/javascript, application/javascript, application/ecmascript, application/x-ecmascript"},contents:{script:/\b(?:java|ecma)script\b/},converters:{"text script":function(a){return r.globalEval(a),a}}}),r.ajaxPrefilter("script",function(a){void 0===a.cache&&(a.cache=!1),a.crossDomain&&(a.type="GET")}),r.ajaxTransport("script",function(a){if(a.crossDomain){var b,c;return{send:function(e,f){b=r("<script>").prop({charset:a.scriptCharset,src:a.url}).on("load error",c=function(a){b.remove(),c=null,a&&f("error"===a.type?404:200,a.type)}),d.head.appendChild(b[0])},abort:function(){c&&c()}}}});var Tb=[],Ub=/(=)\?(?=&|$)|\?\?/;r.ajaxSetup({jsonp:"callback",jsonpCallback:function(){var a=Tb.pop()||r.expando+"_"+ub++;return this[a]=!0,a}}),r.ajaxPrefilter("json jsonp",function(b,c,d){var e,f,g,h=b.jsonp!==!1&&(Ub.test(b.url)?"url":"string"==typeof b.data&&0===(b.contentType||"").indexOf("application/x-www-form-urlencoded")&&Ub.test(b.data)&&"data");if(h||"jsonp"===b.dataTypes[0])return e=b.jsonpCallback=r.isFunction(b.jsonpCallback)?b.jsonpCallback():b.jsonpCallback,h?b[h]=b[h].replace(Ub,"$1"+e):b.jsonp!==!1&&(b.url+=(vb.test(b.url)?"&":"?")+b.jsonp+"="+e),b.converters["script json"]=function(){return g||r.error(e+" was not called"),g[0]},b.dataTypes[0]="json",f=a[e],a[e]=function(){g=arguments},d.always(function(){void 0===f?r(a).removeProp(e):a[e]=f,b[e]&&(b.jsonpCallback=c.jsonpCallback,Tb.push(e)),g&&r.isFunction(f)&&f(g[0]),g=f=void 0}),"script"}),o.createHTMLDocument=function(){var a=d.implementation.createHTMLDocument("").body;return a.innerHTML="<form></form><form></form>",2===a.childNodes.length}(),r.parseHTML=function(a,b,c){if("string"!=typeof a)return[];"boolean"==typeof b&&(c=b,b=!1);var e,f,g;return b||(o.createHTMLDocument?(b=d.implementation.createHTMLDocument(""),e=b.createElement("base"),e.href=d.location.href,b.head.appendChild(e)):b=d),f=C.exec(a),g=!c&&[],f?[b.createElement(f[1])]:(f=qa([a],b,g),g&&g.length&&r(g).remove(),r.merge([],f.childNodes))},r.fn.load=function(a,b,c){var d,e,f,g=this,h=a.indexOf(" ");return h>-1&&(d=pb(a.slice(h)),a=a.slice(0,h)),r.isFunction(b)?(c=b,b=void 0):b&&"object"==typeof b&&(e="POST"),g.length>0&&r.ajax({url:a,type:e||"GET",dataType:"html",data:b}).done(function(a){f=arguments,g.html(d?r("<div>").append(r.parseHTML(a)).find(d):a)}).always(c&&function(a,b){g.each(function(){c.apply(this,f||[a.responseText,b,a])})}),this},r.each(["ajaxStart","ajaxStop","ajaxComplete","ajaxError","ajaxSuccess","ajaxSend"],function(a,b){r.fn[b]=function(a){return this.on(b,a)}}),r.expr.pseudos.animated=function(a){return r.grep(r.timers,function(b){return a===b.elem}).length},r.offset={setOffset:function(a,b,c){var d,e,f,g,h,i,j,k=r.css(a,"position"),l=r(a),m={};"static"===k&&(a.style.position="relative"),h=l.offset(),f=r.css(a,"top"),i=r.css(a,"left"),j=("absolute"===k||"fixed"===k)&&(f+i).indexOf("auto")>-1,j?(d=l.position(),g=d.top,e=d.left):(g=parseFloat(f)||0,e=parseFloat(i)||0),r.isFunction(b)&&(b=b.call(a,c,r.extend({},h))),null!=b.top&&(m.top=b.top-h.top+g),null!=b.left&&(m.left=b.left-h.left+e),"using"in b?b.using.call(a,m):l.css(m)}},r.fn.extend({offset:function(a){if(arguments.length)return void 0===a?this:this.each(function(b){r.offset.setOffset(this,a,b)});var b,c,d,e,f=this[0];if(f)return f.getClientRects().length?(d=f.getBoundingClientRect(),b=f.ownerDocument,c=b.documentElement,e=b.defaultView,{top:d.top+e.pageYOffset-c.clientTop,left:d.left+e.pageXOffset-c.clientLeft}):{top:0,left:0}},position:function(){if(this[0]){var a,b,c=this[0],d={top:0,left:0};return"fixed"===r.css(c,"position")?b=c.getBoundingClientRect():(a=this.offsetParent(),b=this.offset(),B(a[0],"html")||(d=a.offset()),d={top:d.top+r.css(a[0],"borderTopWidth",!0),left:d.left+r.css(a[0],"borderLeftWidth",!0)}),{top:b.top-d.top-r.css(c,"marginTop",!0),left:b.left-d.left-r.css(c,"marginLeft",!0)}}},offsetParent:function(){return this.map(function(){var a=this.offsetParent;while(a&&"static"===r.css(a,"position"))a=a.offsetParent;return a||ra})}}),r.each({scrollLeft:"pageXOffset",scrollTop:"pageYOffset"},function(a,b){var c="pageYOffset"===b;r.fn[a]=function(d){return T(this,function(a,d,e){var f;return r.isWindow(a)?f=a:9===a.nodeType&&(f=a.defaultView),void 0===e?f?f[b]:a[d]:void(f?f.scrollTo(c?f.pageXOffset:e,c?e:f.pageYOffset):a[d]=e)},a,d,arguments.length)}}),r.each(["top","left"],function(a,b){r.cssHooks[b]=Pa(o.pixelPosition,function(a,c){if(c)return c=Oa(a,b),Ma.test(c)?r(a).position()[b]+"px":c})}),r.each({Height:"height",Width:"width"},function(a,b){r.each({padding:"inner"+a,content:b,"":"outer"+a},function(c,d){r.fn[d]=function(e,f){var g=arguments.length&&(c||"boolean"!=typeof e),h=c||(e===!0||f===!0?"margin":"border");return T(this,function(b,c,e){var f;return r.isWindow(b)?0===d.indexOf("outer")?b["inner"+a]:b.document.documentElement["client"+a]:9===b.nodeType?(f=b.documentElement,Math.max(b.body["scroll"+a],f["scroll"+a],b.body["offset"+a],f["offset"+a],f["client"+a])):void 0===e?r.css(b,c,h):r.style(b,c,e,h)},b,g?e:void 0,g)}})}),r.fn.extend({bind:function(a,b,c){return this.on(a,null,b,c)},unbind:function(a,b){return this.off(a,null,b)},delegate:function(a,b,c,d){return this.on(b,a,c,d)},undelegate:function(a,b,c){return 1===arguments.length?this.off(a,"**"):this.off(b,a||"**",c)}}),r.holdReady=function(a){a?r.readyWait++:r.ready(!0)},r.isArray=Array.isArray,r.parseJSON=JSON.parse,r.nodeName=B,"function"==typeof define&&define.amd&&define("jquery",[],function(){return r});var Vb=a.jQuery,Wb=a.$;return r.noConflict=function(b){return a.$===r&&(a.$=Wb),b&&a.jQuery===r&&(a.jQuery=Vb),r},b||(a.jQuery=a.$=r),r});
;
// Compiled from 'script/tl.js'
TL = {

	/**
	 * Returns the type attribute of the given element.
	 * 
	 * The type is typically a JS-Object which contains the functions used for
	 * Drag&Drop.
	 */
	getType : function(element) {
		var type = this.getTLAttribute(element, 'type');
		if (type != null && type != "") {
			return eval(type);
		}
		return null;
	},
		
    /**
     * Returns the value of the attribute with name "'data-' + attrName" of the 
     * given element.
     */
    getTLAttribute : function(element, attrName) {
        return BAL.DOM.getNonStandardAttribute(element, 'data-' + attrName);
    },

    /**
     * Sets the value of the attribute with name "'data-' + attrName" of the 
     * given element to the given value.
     */
    setTLAttribute : function(element, attrName, value) {
        return BAL.DOM.setNonStandardAttribute(element, 'data-' + attrName, value);
    },
    
    /**
     * Removes the attribute with name "'data-' + attrName" of the 
     * given element.
     */
    removeTLAttribute : function(element, attrName) {
    	BAL.DOM.removeNonStandardAttribute(element, 'data-' + attrName);
    },

	_parseJson : function(json) {
		return eval("(" + json + ");");
	}
	
};


BAL.addEventListener(document, "mousedown", function(evt) {
	evt = BAL.getEvent(evt);
	var target = BAL.getEventTarget(evt);

	while (target != null) {
		if (target.parentNode == null) {
			// The document node does not support attributes.
			break;
		}
		
		{
			var attributeValue = BAL.DOM.getNonStandardAttribute(target, 'data-dnd');
			if (attributeValue != null && attributeValue.length > 0) {
				if (target.id == null || target.id == "") {
					services.log.error("Cannot initialize drag and drop for element whithout ID.");
					return true;
				}
				
				var targetIds = TL._parseJson(attributeValue);
				return services.DnD.handleMouseDown(target, targetIds, evt);	
			}
		}

		target = target.parentNode;
	}
});

BAL.addEventListener(document, "mouseover", function(evt) {
	evt = BAL.getEvent(evt);
	
	var tooltip = true;
	var target = BAL.getEventTarget(evt);
	while (target != null) {
		if (target.parentNode == null) {
			// The document node does not support attributes.
			break;
		}

		if (tooltip) {
			var attributeValue = BAL.DOM.getNonStandardAttribute(target, 'data-tooltip');
			if (attributeValue != null && attributeValue.length > 0) {
				PlaceDialog.makeTooltip({element: target, content: attributeValue, event: evt});
				
				// Only interpret innermost tooltip.
				tooltip = false;
			}
		}

		{
			var attributeValue = BAL.DOM.getNonStandardAttribute(target, 'data-hover');
			if (attributeValue != null && attributeValue.length > 0) {
                target.plainClassArray = BAL.DOM.getClassesArray(target);
                target.className = attributeValue;
			}
		}
		
		target = target.parentNode;
	}
});

BAL.addEventListener(document, "mouseout", function(evt) {
	evt = BAL.getEvent(evt);
	
	var target = BAL.getEventTarget(evt);
	while (target != null) {
		if (target.parentNode == null) {
			// The document node does not support attributes.
			break;
		}
		
		{
			var attributeValue = BAL.DOM.getNonStandardAttribute(target, 'data-hover');
			if (attributeValue != null && attributeValue.length > 0) {
                if (typeof(target.plainClassArray) != 'undefined') {
                    BAL.DOM.setClassesArray(target, target.plainClassArray);
                    BAL.DOM.removeNonStandardAttribute(target, "plainClassArray");
                }
			}
		}

		target = target.parentNode;
	}
});
;
// Compiled from 'script/dragAndDrop.js'
services.DnD = {

	DropTarget: function(targetElement, targetType) {
		this.element = targetElement;
		this.type = targetType;
		this.handle= null;
	},
	
	Draggable: {
		/**
		 * Called on a potential drag source element on which a 
		 * mousedown-event has happened.
		 * 
		 * @param element
		 *          The concrete DOM element on which the drag'n drop
		 *          process is started.
		 * @returns Information about the dragged object, or null 
		 *          if the given event should be ignored.
		 *          
		 * @see DnD.dragOperation
		 */
		createDrag : function(event, element) {
			return new services.DnD.Drag(element.outerHTML);
		},
		
		/**
		 * Called on each potential target element of a drag'n drop operation 
		 * to inform that a drag'n drop operation is about to start.
		 * 
		 * A potential drop target must be enabled to provide visual feedback
		 * about a pending drop within its area. A pending drop will be is 
		 * announced by calling a "marker function" installed on the potential 
		 * drop target.
		 * 
		 * @param element
		 *            The concrete DOM element which is a potential drag'n 
		 *            drop target.
		 * @returns A handle object that is informed about drag positions and 
		 *          the end of the drag'n drop operation.
		 */
		createDrop: function(event, element) {
			return new services.DnD.Drop();
		}
		
	},
	
	/**
	 * A drag operation.
	 */
	Drag: function(dragShapeHtml) {
		this.dragShapeHtml = dragShapeHtml;
	},
	
	/**
	 * A drop target prepared to receive a drop.
	 */
	Drop: function() {
		// No additional state by default.
	},
	
	/**
	 * Information about a drag'n drop operation consisting of:
	 * 
	 * dragShapeHtml
	 *    A HTML fragment string of the moved content to show as mouse marker.
	 *    
	 * serverDragInfo
	 *    The ids of the source elements which are moved before the drag has started.
	 */
	dragOperation: null,
	
	/**
	 * The HTML element representing the drag content following the mouse pointer during Drag&Drop.
	 */
	mouseMarker : null,

	handleMouseDown : function(element, targets, evt) {
			/*
			 * Must compute this for each execution. Setting this once will not
			 * work, since when some target has repaint the DOM elements for the
			 * id's are new
			 */
			var dropTargets = new Array();
			for ( var i = 0; i < targets.length; i++) {
				var targetElement = document.getElementById(targets[i]);
				if (targetElement == null) {
					services.log.error("No element found with id '" + targets[i] + "' for drag and drop");
					continue;
				}
				var targetType = this.getType(targetElement);
				dropTargets.push(new services.DnD.DropTarget(targetElement, targetType));
				targetElement = null;
				targetType = null;
			}
			if (dropTargets.length == 0) {
				return false;
			}

			/*
			 * change onselect to prevent selection
			 */
			services.DnD.onselectstart = document.onselectstart;
			document.onselectstart = function() {
				return false;
			};

			var executeDragNDrop = function() {
				var type = services.DnD.getType(element);
				var dragOperation = type.createDrag(evt, element);
				if (dragOperation == null) {
					document.onselectstart = services.DnD.onselectstart;
					delete services.DnD.onselectStart;
					return;
				}
				services.DnD.dragOperation = dragOperation;
				
				for ( var i = 0; i < dropTargets.length; i++) {
					dropTargets[i].setup(dragOperation, element, evt);
				}
	
				/*
				 * Installs the HTMLElement which will follow the mouse on move and
				 * corresponding functions
				 */
				services.DnD._addMouseMarker();
	
				var mouseUp = function(event) {
					BAL.removeEventListener(document, 'mouseover', mouseOver, false);
					BAL.removeEventListener(element, 'DOMNodeRemovedFromDocument', services.DnD._removeMouseMarker, true);
					services.DnD._removeMouseMarker();

					var srcElement = BAL.getEventTarget(BAL.getEvent(event));

					var serverDropInfo = null;
					var droppedElement = null;
					var accepted = false;
					for ( var i = 0; i < dropTargets.length; i++) {
						var dropTarget = dropTargets[i];
						
						// Determine whether the event happened in the current target.
						var parent = srcElement;
						while (parent != null && parent != dropTarget.element) {
							parent = parent.parentNode;
						}
						if (parent == null) {
							// Don't care. drop happened within a different element
							dropTarget.dispose();
							continue;
						}
						
						var clientDragInfo = dragOperation.getClientDragInfo(element, dropTarget.element);
						var dropInfo = dropTarget.offerDrop(clientDragInfo, srcElement);
						if (dropInfo != null) {
							droppedElement = dropTarget.element;
							accepted = true;
							serverDropInfo = dropInfo;
						}
						
						dropTarget.dispose();
					}
					dragOperation.dispose(accepted, droppedElement);
	
					services.DnD._reinstallOnSelectStart();
	
					if (accepted) {
						var serverDragInfo = dragOperation.getServerDragInfo(element, droppedElement)
						services.DnD.notifyServer(element.id, droppedElement.id, serverDragInfo, serverDropInfo);
					}
	
					services.DnD.dragOperation = null
					services.DnD.mouseMarker = null;
				}
				var addedMouseUp = BAL.onceEventListener(document, 'mouseup', mouseUp, this, false);
	
				var mouseOver = function(event) {
					if (BAL.getEventTarget(BAL.getEvent(event)) == document.body) {
						addedMouseUp.call();
					}
				};
				BAL.addEventListener(document, 'mouseover', mouseOver, false);
				BAL.addEventListener(element, 'DOMNodeRemovedFromDocument', services.DnD._removeMouseMarker, true);
			}
			
			var startDnDMouseOut = function() {
				BAL.removeEventListener(element, 'mouseout', startDnDMouseOut);
				BAL.removeEventListener(element, 'mouseup', cancelDnD);
				executeDragNDrop.call();
			}
			
			var cancelDnD = function() {
				BAL.removeEventListener(element, 'mouseout', startDnDMouseOut);
				BAL.removeEventListener(element, 'mouseup', cancelDnD);
				services.DnD._reinstallOnSelectStart();
			}
			BAL.addEventListener(element, 'mouseout', startDnDMouseOut);
			BAL.addEventListener(element, 'mouseup', cancelDnD);
			return false;
	},
	
	getType: function(targetElement) {
		var targetType = TL.getType(targetElement)
		if (targetType == null || targetType == "") {
			targetType = services.DnD.Draggable;
		}
		return targetType;
	},
	
	_reinstallOnSelectStart: function() {
		/*
		 * Reinstall onselectstart
		 */
		document.onselectstart = services.DnD.onselectstart;
		delete services.DnD.onselectStart;
	},

	onDragging: function(event) {
		event = BAL.getEvent(event);
		
		var dragShapeHtml = services.DnD.dragOperation.dragShapeHtml;
		
		var mouseMarker = services.DnD.mouseMarker;
		if (mouseMarker == null) {
			if (dragShapeHtml == null) {
				return;
			}
			
			mouseMarker = document.createElement('div');
			BAL.DOM.addClass(mouseMarker, 'DNDmouseMarker');
			mouseMarker.innerHTML = dragShapeHtml;
			document.body.appendChild(mouseMarker);
			BAL.addEventListener(mouseMarker, 'mousemove', services.DnD.clearDragTimeout, false);
			
			services.DnD.mouseMarker = mouseMarker;
		}
		BAL.setElementX(mouseMarker, BAL.getEventX(event) + 10);
		BAL.setElementY(mouseMarker, BAL.getEventY(event) - 25);
	},
	
	_addMouseMarker : function() {
		BAL.addEventListener(document, 'mousemove', services.DnD.onDragging, false);
	},

	clearDragTimeout : function() {
		if (services.DnD.timer) {
			window.clearTimeout(services.DnD.timer);
			delete services.DnD.timer;
		}
	},

	_removeMouseMarker : function() {
		BAL.removeEventListener(document, 'mousemove', services.DnD.onDragging, false);
		
		var mouseMarker = services.DnD.mouseMarker;
		if (mouseMarker != null) {
			BAL.removeEventListener(mouseMarker, 'mousemove', services.DnD.clearDragTimeout, false);
			mouseMarker.parentNode.removeChild(mouseMarker);
			
			services.DnD.mouseMarker = null;
		}
	},

	notifyServer : function(sourceID, dropID, serverDragInfo, serverDropInfo) {
		services.ajax.execute('dispatchControlCommand', {
			controlCommand : 'dragNdrop',
			controlID : sourceID,
			serverDragInfo : serverDragInfo,
			dropID : dropID,
			serverDropInfo : serverDropInfo

		}, /*useWaitPane*/ false);
	}

};

services.DnD.DropTarget.prototype = {
	setup: function(dragOperation, dragSourceElement, eventMouseDown) {
		var element = this.element;
		var dropHandle = this.type.createDrop(eventMouseDown, element);
		if (dropHandle == null) {
			return;
		}
		
		var clientDragInfo = dragOperation.getClientDragInfo(dragSourceElement, element);
		this.onMouseMove = function(event) {
			var eventSrc = BAL.getEventTarget(BAL.getEvent(event));
			services.DnD.clearDragTimeout();
			
			dropHandle.dragOver(eventSrc, clientDragInfo);
		}
		BAL.addEventListener(element, 'mousemove', this.onMouseMove, false);

		this.onMouseOut = function(event) {
			if (BAL.getEventTarget(BAL.getEvent(event)) != element) {
				return;
			}
			services.DnD.timer = window.setTimeout(function() {
				dropHandle.dragOut();
			}, 100);
		}
		BAL.addEventListener(element, 'mouseout', this.onMouseOut, false);
		
		this.handle = dropHandle;
	},
	
	offerDrop: function(clientDragInfo, srcElement) {
		if (this.handle == null) {
			return null;
		}

		return this.handle.offerDrop(clientDragInfo, srcElement, this.element);
	},

	dispose: function() {
		if (this.handle == null) {
			return;
		}
		
		var element = this.element;
		BAL.removeEventListener(element, 'mousemove', this.onMouseMove, false);
		BAL.removeEventListener(element, 'mouseout', this.onMouseOut, false);
		
		this.handle.dragOut();
	}
	
};

services.DnD.Drag.prototype = {
	/**
	 * Called after the drag operation has finished. 
	 * 
	 * Can be used to clean up state and styles modified while staring the drag operation.
	 */
	dispose: function(successful, dropTargetElement) {
		return;
	},

	/**
	 * Provides information of the dragged objects, that must be handled by drop targets at client side.
	 * Called, whether when mouse hovers a drop target during drag operation, or drop shall be
	 * performed on drop target.
	 * 
	 * @param dragSourceElement
	 * 			DOM element, that is the source of a drag'n'drop operation
	 * @param dropTargetElement
	 * 			DOM element, that is the target of a drag'n'drop operation
	 * 			
	 */
	getClientDragInfo : function(dragSourceElement, dropTargetElement) {
		return null;
	},
	
	/**
	 * Provides information of the dragged objects, that must be handled by drop targets.
	 * Called, when drop target has been successfully accepted the drop and drag'n'drop update
	 * is send to server.
	 * 
	 * @param dragSourceElement
	 * 			DOM element, that is the source of a drag'n'drop operation
	 * @param dropTargetElement
	 * 			DOM element, that is the target of a drag'n'drop operation
	 * 			
	 */
	getServerDragInfo : function(dragSourceElement, dropTargetElement) {
		return null;
	}
};

services.DnD.Drop.prototype = {
	/**
	 * Notifies the drop target that the mouse moves currently over it.
	 * 
	 * This callback can be used to highlight the drop target to give 
	 * visual feedback what would happen, if the drop would be completed 
	 * at the current location.
	 * 
	 * @param eventSrc The DOM element within the drop target that is 
	 *          hit by the mouse cursor.
	 * @param clientDragInfo
	 *        The drag information created by the drag source in getClientDragInfo().
	 */
	dragOver: function(eventSrc, clientDragInfo) {
		// Ignore.
	},
	
	/**
	 * Notifies the drop target that the mouse cursor has left its area.
	 * 
	 * @see dragOver()
	 */
	dragOut: function() {
		// Ignore.
	},
	
	/**
	 * Informs a drop target about a drop. 
	 * 
	 * Each drop target will be informed about a drop (no matter, where the 
	 * drop event occurred). This function must decide, whether the given 
	 * element accepts the drop. 
	 * 
	 * In this function, the drop targets must also reset state installed 
	 * during createDrop().
	 * 
	 * @param clientDragInfo
	 *        The drag information created by the drag source in getClientDragInfo().
	 * @param element
	 *            The concrete DOM instance which is a potential drop
	 *            target.
	 * @param event
	 *            The mouse-up event which occurs during drop. This is used to
	 *            determine whether the element should accepts the drop.
	 * @return Custom information to be sent to the server as arguments of 
	 *         the drag'n drop operation.
	 */
	offerDrop: function(clientDragInfo, srcElement, element) {
		return {};
	}
};
;
// Compiled from 'script/viewport.js'
/**
 * Access manager to invisible container elements at GUI.
 */

services.viewport = {
	
	scrollSpeed : 300, /* px/s */
	
	inactiveScrollButtonClass : "inactive",

	currentScrollOffset : new Object(),
	
	initScrollingDisplay : function(viewportReferences) {
		services.layout._applyLayout();
		var self = services.viewport;
		if (self.isViewportTooSmall(viewportReferences)) {
			var leftScrollerImage = self.getLeftScrollButtonImage(viewportReferences);
			var rightScrollerImage = self.getRightScrollButtonImage(viewportReferences);
			var leftScrollerWidth = BAL.getFullElementWidth(leftScrollerImage);
			var rightScrollerWidth = BAL.getFullElementWidth(rightScrollerImage);
			if (self.areScrollButtonsInvisible(viewportReferences)) {
				services.layout.addLayoutFunction(self
						.getFadeScrollButtonsFunction(viewportReferences,
								leftScrollerWidth, rightScrollerWidth));
			}
			services.layout.addPostLayoutFunction(self
					.getScrollOffsetAdjustmentFunction(viewportReferences));
		} else if (self.areScrollButtonsVisible(viewportReferences)) {
			services.layout.addLayoutFunction(self
					.getFadeScrollButtonsFunction(viewportReferences, 0, 0));
			services.layout.addPostLayoutFunction(self
					.getResetScrollPositionFunction(viewportReferences));
		}
	},
	
	initMenuDisplay: function(viewportReferences) {
		var self = services.viewport;
		var viewportWidth = self.getParentWidth(viewportReferences);
		var menuDialogOpenerImage = self.getMenuDialogOpenerButtonImage(viewportReferences);
		var menuDialogOpenerWidth = 0;
		var buttonsHidden = self.isViewportTooSmall(viewportReferences, viewportWidth);
		if (buttonsHidden) {
			menuDialogOpenerWidth = BAL.getElementWidth(menuDialogOpenerImage);
			if(self.isMenuDialogOpenerButtonInvisible(viewportReferences)) {
				services.layout.addLayoutFunction(self.getFadeMenuDialogOpenerFunction(viewportReferences, menuDialogOpenerWidth));
				services.layout.addLayoutFunction(function() {
					var buttonScrollContainer = self.getScrollContainer(viewportReferences);
				});
			}
		} else if (self.isMenuDialogOpenerButtonVisible(viewportReferences)) {
			services.layout.addLayoutFunction(self.getFadeMenuDialogOpenerFunction(viewportReferences, 0));
			services.layout.addLayoutFunction(function() {
				var buttonScrollContainer = self.getScrollContainer(viewportReferences);
			});
		}
		
		var scrollContainerWidth = viewportWidth
;
		services.layout.addLayoutFunction(function() {
			var buttonScrollContainer = self.getScrollContainer(viewportReferences);
			BAL.setElementWidth(buttonScrollContainer, scrollContainerWidth);
		});
		
		var elementContainerLeft = BAL.getElementX(self.getElementContainer(viewportReferences));
		var elementContainerWidth = self.getElementContainerWidth(viewportReferences);
		var spaceHolderContainer = self.getSpaceHolderContainer(viewportReferences);
		var scrollPosition = elementContainerLeft + elementContainerWidth + parseInt(BAL.getComputedStyle(spaceHolderContainer, "paddingRight")) - scrollContainerWidth;
		if (buttonsHidden) {
			var hiddenButtonCount = self.calculateHiddenButtonCount(viewportReferences, scrollPosition);
			var firstVisibleButton = self.getFirstVisibleElement(viewportReferences, hiddenButtonCount);
			var buttonX;
			if(firstVisibleButton != null) {
				buttonX = BAL.getElementX(firstVisibleButton);
			} else {
				buttonX = 0;
			}
			
			services.layout.addLayoutFunction(function() {
				self.updateHiddenButtonState(viewportReferences, hiddenButtonCount);
				
				var menuDialogOpener = self.getMenuDialogOpenerButton(viewportReferences);
				if(firstVisibleButton != null) {
					var buttonPositionToParent = buttonX - scrollPosition;
					var menuDialogOpenerPositionX = viewportWidth - buttonPositionToParent; /*5 is randomely chosen adjustment*/;
					menuDialogOpener.style.right = menuDialogOpenerPositionX + "px";
				} else {
					menuDialogOpener.style.right = buttonX + "px";
				}
			});
			
			services.layout.addPostLayoutFunction(function() {
				self.scrollTo(viewportReferences, scrollPosition);
			});
			
			
			services.viewport.updateServerState(hiddenButtonCount, viewportReferences);
		} else {
			var firstElement = self.getFirstElement(viewportReferences);
			services.layout.addPostLayoutFunction(function() {
				self.scrollTo(viewportReferences, scrollPosition);
				self.showAllAfter(viewportReferences, firstElement);
			});
			services.viewport.updateServerState(0, viewportReferences);
		}
		
		if(services.layout.isIE9) {
			// Quirks to force GUI update in IE9, #10215
			services.layout.addPostLayoutFunction(function() {
				var elementContainer = self.getElementContainer(viewportReferences);
				BAL.getElementWidth(elementContainer);
				BAL.getElementHeight(elementContainer);
			});
		}
	},
	
	getParentWidth: function(viewportReferences) {
		if (services.layout.parentLayoutInformation != null) {
			return services.layout.parentLayoutInformation.effectiveWidth;
		} else {
			var barControl = document.getElementById(viewportReferences.controlID);
			return BAL.getEffectiveWidth(barControl);
		}
	},
	
	adjustContainerScrollPosition: function(viewportReferences, newSelection) {
		var element = document.getElementById(viewportReferences.controlID + "-" + newSelection);
		var elementOffsetX = BAL.getElementX(element);
		var elementWidth = BAL.getFullElementWidth(element);
		var scrollContainer = services.viewport.getScrollContainer(viewportReferences);
		var scrollContainerWidth = BAL.getElementWidth(scrollContainer);
		var scrollContainerScrollOffset = BAL
				.getScrollLeftElement(scrollContainer);
		var singleStep = true;

		if (elementOffsetX < scrollContainerScrollOffset) {
			this.scrollLeft(viewportReferences, singleStep);
		} else {
			var elementRightBorder = elementOffsetX + elementWidth;
			var scrollContainerRightBorder = scrollContainerScrollOffset
					+ scrollContainerWidth;
			if (elementRightBorder > scrollContainerRightBorder) {
				this.scrollRight(viewportReferences, singleStep);
			} else {
				this.currentScrollOffset[viewportReferences.controlID] = scrollContainerScrollOffset;
			}
		}
	},

	isViewportTooSmall: function(viewportReferences, precomputedViewportWidth) {
		var self = services.viewport;
		var viewport = self.getViewport(viewportReferences);
		var viewportWidth = precomputedViewportWidth != null ? precomputedViewportWidth : BAL.getElementWidth(viewport);
		return viewportWidth < self.getElementContainerWidth(viewportReferences);
	},
	
	getElementContainerWidth: function(viewportReferences) {
		var self = services.viewport;
		var elementSizeContainer = self.getElementSizeContainer(viewportReferences);
		return BAL.getElementWidth(elementSizeContainer);
	},

	areScrollButtonsInvisible : function(viewportReferences) {
		var leftScroller = services.viewport.getLeftScrollButton(viewportReferences);
		return services.viewport.isMarkedAsInvisible(leftScroller);
	},
	
	isMarkedAsInvisible: function(element) {
		return element.isVisible == undefined
		|| element.isVisible == false;
	},

	areScrollButtonsVisible : function(viewportReferences) {
		var leftScroller = services.viewport
				.getLeftScrollButton(viewportReferences);
		return services.viewport.isMarkedAsVisible(leftScroller);
	},
	
	isMarkedAsVisible: function(element) {
		return element.isVisible == true;
	},
	
	markAsVisible: function(element) {
		services.viewport.setVisibilityPropertyTo(element, true);
	},
	
	markAsInvisible: function(element) {
		services.viewport.setVisibilityPropertyTo(element, false);
	},
	
	setVisibilityPropertyTo: function(element, isVisible) {
		element.isVisible = isVisible;
	},
	
	isMenuDialogOpenerButtonInvisible: function(viewportReferences) {
		var menuDialogOpenerButton = services.viewport.getMenuDialogOpenerButton(viewportReferences);
		return services.viewport.isMarkedAsInvisible(menuDialogOpenerButton);
	},
	
	isMenuDialogOpenerButtonVisible: function(viewportReferences) {
		var menuDialogOpenerButton = services.viewport.getMenuDialogOpenerButton(viewportReferences);
		return services.viewport.isMarkedAsVisible(menuDialogOpenerButton);
	},

	getScrollOffsetAdjustmentFunction : function(viewportReferences) {
		var self = services.viewport;

		if (self.isScrollContainerOverScrolled(viewportReferences)) {
			return self.getRemoveOverScrollingFunction(viewportReferences);
		} else if (self.isPreviousScrollPositionRequested(viewportReferences)) {
			return self.getRestorePreviousScrollPositionFunction(viewportReferences);
		} else {
			return self.getMakeSelectedElementVisibleScrollingFunction(viewportReferences);
		}
	},
	
	isPreviousScrollPositionRequested : function(viewportReferences) {
		var self = services.viewport;
		return self.hasNoSelectedElement(viewportReferences) ||
			   self.isSelectedElementVisibleAtScrollOffset(viewportReferences);
	},
	
	hasNoSelectedElement : function (viewportReferences) {
		var self = services.viewport;
		return self.getSelectedElement(viewportReferences) == null;
	},

	isSelectedElementVisibleAtScrollOffset : function(viewportReferences) {
		var self = services.viewport;
		var scrollContainer = self.getScrollContainer(viewportReferences);
		var selectedElement = self.getSelectedElement(viewportReferences);
		var leftElementBorder = BAL.getElementX(selectedElement);
		var rightElementBorder = leftElementBorder + BAL.getFullElementWidth(selectedElement);
		var leftVisibleBorder = self.getCurrentScrollOffset(viewportReferences);
		var rightVisibleBorder = leftVisibleBorder
				+ BAL.getElementWidth(scrollContainer);
		return leftVisibleBorder <= leftElementBorder
				&& rightElementBorder <= rightVisibleBorder;
	},

	getCurrentScrollOffset : function(viewportReferences) {
		var offset = this.currentScrollOffset[viewportReferences.controlID];
		if (offset != null) {
			return offset;
		} else {
			return 0;
		}
	},

	isScrollContainerOverScrolled : function(viewportReferences) {
		var self = services.viewport;
		var scrollContainer = self.getScrollContainer(viewportReferences);
		var elementContainerWidth = self.getElementContainerWidth(viewportReferences);
		var scrollContainerScrollOffset = BAL
				.getScrollLeftElement(scrollContainer);
		var scrollContainerWidth = BAL.getElementWidth(scrollContainer);
		return (elementContainerWidth - scrollContainerScrollOffset) < scrollContainerWidth;
	},

	getRemoveOverScrollingFunction : function(viewportReferences) {
		return function() {
			var self = services.viewport;
			var scrollContainer = self.getScrollContainer(viewportReferences);
			var elementContainerWidth = self.getElementContainerWidth(viewportReferences);
			var scrollContainerWidth = BAL.getElementWidth(scrollContainer);
			var scrollOffset = elementContainerWidth - scrollContainerWidth;
			self.currentScrollOffset[viewportReferences.controlID] = scrollOffset;
			BAL.setScrollLeftElement(scrollContainer, scrollOffset);
		};
	},

	getResetScrollPositionFunction : function(viewportReferences) {
		return function() {
			var scrollContainer = services.viewport
					.getScrollContainer(viewportReferences);
			BAL.setScrollLeftElement(scrollContainer, 0);
		};
	},

	getRestorePreviousScrollPositionFunction : function(viewportReferences) {
		var self = this;
		return function() {
			var scrollContainer = services.viewport
					.getScrollContainer(viewportReferences);
			var lastScrollOffset = services.viewport.getCurrentScrollOffset
					.call(self, viewportReferences);
			BAL.setScrollLeftElement(scrollContainer, lastScrollOffset);
		};
	},

	getMakeSelectedElementVisibleScrollingFunction : function(viewportReferences) {
		var self = this;
		return function() {
			var context = services.viewport;
			var scrollContainer = context.getScrollContainer(viewportReferences);
			var selectedElement = context.getSelectedElement(viewportReferences);
			var leftElementBorder = BAL.getElementX(selectedElement);
			var rightElementBorder = leftElementBorder
					+ BAL.getFullElementWidth(selectedElement);
			var leftVisibleBorder = context.getCurrentScrollOffset.call(
					self, viewportReferences);
			var scrollContainerWidth = BAL.getElementWidth(scrollContainer);
			var scrollOffset;
			if (leftElementBorder < leftVisibleBorder) {
				scrollOffset = leftElementBorder;
			} else {
				scrollOffset = rightElementBorder - scrollContainerWidth;
			}
			self.currentScrollOffset[viewportReferences.controlID] = scrollOffset;
			BAL.setScrollLeftElement(scrollContainer, scrollOffset);
		};
	},

	getFadeScrollButtonsFunction : function(viewportReferences, leftScrollerWidth,
			rightScrollerWidth) {
		return function() {
			var self = services.viewport;
			var leftScroller = self.getLeftScrollButton(viewportReferences);
			var rightScroller = self.getRightScrollButton(viewportReferences);
			BAL.setElementWidth(leftScroller, leftScrollerWidth);
			BAL.setElementWidth(rightScroller, rightScrollerWidth);
			if (leftScrollerWidth > 0) {
				self.markAsVisible(leftScroller);
			} else {
				self.markAsInvisible(leftScroller);
			}
		};
	},

	getFadeMenuDialogOpenerFunction : function(viewportReferences, menuDialogOpenerWidth) {
		return function() {
			var self = services.viewport;
			var menuDialogOpener = self.getMenuDialogOpenerButton(viewportReferences);
			BAL.setElementWidth(menuDialogOpener, menuDialogOpenerWidth);
			if (menuDialogOpenerWidth > 0) {
				self.markAsVisible(menuDialogOpener);
			} else {
				self.markAsInvisible(menuDialogOpener);
			}
		};
	},
	
	scrollTo : function(viewportReferences, buttonContainerPosition) {
		var self = services.viewport;
		var buttonScrollContainer = self.getScrollContainer(viewportReferences);
		BAL.setScrollLeftElement(buttonScrollContainer, buttonContainerPosition);
	},
	
	initScrollButtonChanger : function(viewportReferences) {
		var self = services.viewport;
		if(self._hasTabs(viewportReferences)) {
			var leftButton = self.getLeftScrollButtonImage(viewportReferences);
			var rightButton = self.getRightScrollButtonImage(viewportReferences);
			BAL.DOM.removeClass(leftButton, self.inactiveScrollButtonClass);
			BAL.DOM.removeClass(rightButton, self.inactiveScrollButtonClass);
			leftButton.showButton = true;
			rightButton.showButton = true;
			var scrollContainer = self.getScrollContainer(viewportReferences);
			BAL.addEventListener(scrollContainer, "scroll", function() {
				self._updateButtonVisibility(viewportReferences);
			});	
			self._updateButtonVisibility(viewportReferences);
		}
	},
	
	_hasTabs : function(viewportReferences) {
		var self = services.viewport;
		var firstElement = self.getFirstElement(viewportReferences);
		return firstElement != null;
	},
	
	_updateButtonVisibility : function(viewportReferences) {
		var self = services.viewport;
		var scrollContainer = self.getScrollContainer(viewportReferences);
		var scrollContainerWidth = BAL.getElementWidth(scrollContainer);
		var firstElement = self.getFirstElement(viewportReferences);
		var lastElement = self.getLastElement(viewportReferences);
		var firstElementPosition = BAL.getElementX(firstElement);
		var scrollContainerRightBorderScrollOffset = BAL.getElementX(lastElement) + BAL.getFullElementWidth(lastElement);
		var scrollContainerLeftBorderScrollOffset = Math.max(scrollContainerRightBorderScrollOffset
				- scrollContainerWidth, 1);
		var scrollPosition = self.getCurrentScrollOffset(viewportReferences);
		var leftButton = self.getLeftScrollButtonImage(viewportReferences);
		var rightButton = self.getRightScrollButtonImage(viewportReferences);
		if(scrollPosition <= firstElementPosition) {
			if(leftButton.showButton == true) {
				BAL.DOM.addClass(leftButton, self.inactiveScrollButtonClass);
				leftButton.showButton = false;
			}
		} else {
			if(leftButton.showButton == false) {
				BAL.DOM.removeClass(leftButton, self.inactiveScrollButtonClass);
				leftButton.showButton = true;
			}
		}
		if(scrollPosition >= scrollContainerLeftBorderScrollOffset) {
			if(rightButton.showButton == true) {
				BAL.DOM.addClass(rightButton, self.inactiveScrollButtonClass);
				rightButton.showButton = false;
			}
		} else {
			if(rightButton.showButton == false) {
				BAL.DOM.removeClass(rightButton, self.inactiveScrollButtonClass);
				rightButton.showButton = true;
			}
		} 
	},
	
	_scrollInit : function(viewportReferences, targetScrollPosition, scrollDirection, loopingAnimation) {
		var self = services.viewport;
		var scrollContainer = self.getScrollContainer(viewportReferences);
		if (scrollContainer.animations == null) {
			scrollContainer.animations = new Array();
		}
		scrollContainer.animations.push(loopingAnimation);
		var animationEnd = function() {
			var continueAnimation = scrollContainer.animations[0];
			scrollContainer.animations.shift();
			if(continueAnimation == true) {
				if(scrollDirection == "left") {
					self.scrollLeft(viewportReferences, false);
				} else {
					self.scrollRight(viewportReferences, false);
				}
			} 
		};
		var scrollDistance = Math.abs(targetScrollPosition - self.getCurrentScrollOffset(viewportReferences));
		var scrollTime = scrollDistance / self.scrollSpeed * 1000;
		self.currentScrollOffset[viewportReferences.controlID] = targetScrollPosition;
		BAL.setScrollLeftElementAnimated(scrollContainer, targetScrollPosition, scrollTime, animationEnd);
	},
	
	scrollFinish : function(viewportReferences) {
		var self = services.viewport;
		var scrollContainer = self.getScrollContainer(viewportReferences);
		var animations = scrollContainer.animations;
		if(animations != null) {
			for(var i = 0; i < animations.length; i++) {
				if(animations[i] == true) {
					animations[i] = false;
					break;
				}
			}
		}
		return false;
	},
	
	scrollLeft : function(viewportReferences, singleStep) {
		var self = services.viewport;
		var scrollContainerScrollOffset = self.getCurrentScrollOffset(viewportReferences);
		var element = self.getFirstElement(viewportReferences);
		var previousElement = null;
		while (element != null
				&& BAL.getElementX(element) < scrollContainerScrollOffset) {
			previousElement = element;
			element = self.getNextElement(element, viewportReferences);
		}

		element = previousElement;
		if (element != null) {
			var scrollOffset = BAL.getElementX(element);
			self._scrollInit(viewportReferences, scrollOffset, "left", !singleStep);
		}

		return false;
	},
	
	scrollRight : function(viewportReferences, singleStep) {
		var self = services.viewport;
		var scrollContainer = self.getScrollContainer(viewportReferences);
		var scrollContainerWidth = BAL.getElementWidth(scrollContainer);
		var scrollContainerLeftBorderScrollOffset = self.getCurrentScrollOffset(viewportReferences);
		var scrollContainerRightBorderScrollOffset = scrollContainerLeftBorderScrollOffset
				+ scrollContainerWidth;
		var element = self.getFirstElement(viewportReferences);

		while ((element != null)
				&& (BAL.getElementX(element) + BAL.getFullElementWidth(element)) <= scrollContainerRightBorderScrollOffset) {
			element = self.getNextElement(element, viewportReferences);
		}

		if ((element != null)
				&& (BAL.getElementX(element) == scrollContainerLeftBorderScrollOffset)) {
			element = self.getNextElement(element, viewportReferences);
		}

		if (element != null) {
			var elementWidth = BAL.getFullElementWidth(element);
			var elementPosition = BAL.getElementX(element);
			var scrollOffset;
			if (elementWidth < scrollContainerWidth) {
				scrollOffset = elementPosition + elementWidth
						- scrollContainerWidth;
			} else {
				scrollOffset = elementPosition;
			}
			self._scrollInit(viewportReferences, scrollOffset, "right", !singleStep);
		}
		return false;
	},
	
	calculateHiddenButtonCount: function(viewportReferences, scrollOffset) {
		var self = services.viewport;
		var hiddenButtonCount = 0;
		var firstElement = self.getFirstElement(viewportReferences);
		var currentElement = firstElement;
		while(currentElement != null && BAL.getElementX(currentElement) < scrollOffset) {
			hiddenButtonCount++;
			currentElement = self.getNextElement(currentElement, viewportReferences);
		}
		
		return hiddenButtonCount;
	},
	
	updateHiddenButtonState: function(viewportReferences, hiddenButtonCount) {
		var self = services.viewport;
		var currentElement = self.getFirstElement(viewportReferences);
		for(var i = 0; i < hiddenButtonCount; i++) {
			currentElement.style.visibility = "hidden";
			currentElement = self.getNextElement(currentElement, viewportReferences);
		}
		self.showAllAfter(viewportReferences, currentElement);
	},
	
	getFirstVisibleElement: function(viewportReferences, hiddenButtonCount) {
		var self = services.viewport;
		var currentElement = self.getFirstElement(viewportReferences);
		for(var i = 0; i < hiddenButtonCount; i++) {
			currentElement = self.getNextElement(currentElement, viewportReferences);
		}
		
		return currentElement;
	},
	
	updateServerState: function(hiddenButtonCount, viewportReferences) {
		var self = services.viewport;
		var barControl = document.getElementById(viewportReferences.controlID);
		if(self.hasNoRequestId(barControl)) {
			barControl.requestId = services.ajax.createLazyRequestID();
		}
		services.ajax.executeOrUpdateLazy(barControl.requestId, "dispatchControlCommand", {
			controlCommand : viewportReferences.visibleButtonCountCommand,
			controlID : viewportReferences.controlID,
			hiddenElements : hiddenButtonCount
		});
	},
	
	hasNoRequestId: function(barControl) {
		return barControl.requestId == null;
	},
	
	showAllAfter: function(viewportReferences, currentElement) {
		var self = services.viewport;
		while(currentElement != null) {
			currentElement.style.visibility = "visible";
			currentElement = self.getNextElement(currentElement, viewportReferences);
		}
	},

	getSelectedElement : function(viewportReferences) {
		var self = services.viewport;
		var firstElement = self.getFirstElement(viewportReferences);
		if (self.isSelectedElement(firstElement, viewportReferences)) {
			return firstElement;
		} else {
			return self.getFollowingElementInOrder(firstElement,
					BAL.DOM.getNextElementSibling, self.isSelectedElement,
					viewportReferences);
		}
	},

	getFirstElement : function(viewportReferences) {
		var self = services.viewport;
		var elementContainer = self.getElementContainer(viewportReferences);
		var firstElementCandidate = BAL.DOM.getFirstElementChild(elementContainer);
		if (firstElementCandidate == null) {
			return null;
		} else if (self.isVisibleElement(firstElementCandidate, viewportReferences)) {
			return firstElementCandidate;
		} else {
			return self.getNextElement(firstElementCandidate, viewportReferences);
		}
	},
	
	getLastElement : function(viewportReferences) {
		var self = services.viewport;
		var elementContainer = self.getElementContainer(viewportReferences);
		var lastElementCandidate = BAL.DOM.getLastElementChild(elementContainer);
		if (lastElementCandidate == null) {
			return null;
		} else if (self.isVisibleElement(lastElementCandidate, viewportReferences)) {
			return lastElementCandidate;
		} else {
			return self.getPreviousElement(lastElementCandidate, viewportReferences);
		}
	},

	getNextElement : function(currentElement, viewportReferences) {
		return services.viewport.getFollowingElementInOrder(currentElement,
				BAL.DOM.getNextElementSibling,
				services.viewport.isVisibleElement,
				viewportReferences);
	},

	getPreviousElement : function(currentElement, viewportReferences) {
		return services.viewport.getFollowingElementInOrder(currentElement,
				BAL.DOM.getPreviousElementSibling,
				services.viewport.isVisibleElement,
				viewportReferences);
	},

	getFollowingElementInOrder : function(currentElement, followingElementFunction,
			elementAcceptanceFilter, viewportReferences) {
		var followingElement = followingElementFunction.call(BAL.DOM, currentElement);
		while (followingElement != null && !elementAcceptanceFilter(followingElement, viewportReferences)) {
			followingElement = followingElementFunction.call(BAL.DOM, followingElement);
		}
		return followingElement;
	},

	isVisibleElement : function(element, viewportReferences) {
		var self = services.viewport;
		return BAL.DOM.containsClass(element,viewportReferences.visibleElement)
				|| self.isSelectedElement(element, viewportReferences);
	},

	isSelectedElement : function(element, viewportReferences) {
		return BAL.DOM.containsClass(element, viewportReferences.activeElement);
	},

	getLeftScrollButton : function(viewportReferences) {
		return document.getElementById(viewportReferences.scrollLeftButton);
	},

	getLeftScrollButtonImage : function(viewportReferences) {
		return document.getElementById(viewportReferences.scrollLeftButtonImage);
	},

	getRightScrollButton : function(viewportReferences) {
		return document.getElementById(viewportReferences.scrollRightButton);
	},

	getRightScrollButtonImage : function(viewportReferences) {
		return document.getElementById(viewportReferences.scrollRightButtonImage);
	},

	getMenuDialogOpenerButton : function(viewportReferences) {
		return document.getElementById(viewportReferences.buttonDialogOpener);
	},
	
	getMenuDialogOpenerButtonImage : function(viewportReferences) {
		return document.getElementById(viewportReferences.buttonDialogOpenerImage);
	},

	getViewport : function(viewportReferences) {
		return document.getElementById(viewportReferences.viewport);
	},

	getScrollContainer : function(viewportReferences) {
		return document.getElementById(viewportReferences.scrollContainer);
	},

	getElementContainer : function(viewportReferences) {
		return document.getElementById(viewportReferences.elementContainer);
	},

	getElementSizeContainer : function(viewportReferences) {
		var elementSizeContainer = document.getElementById(viewportReferences.elementSizeContainer);
		if (elementSizeContainer != null) {
			return elementSizeContainer;
		} else {
			return services.viewport.getElementContainer(viewportReferences);
		}
	},
	
	getSpaceHolderContainer: function(viewportReferences) {
		return document.getElementById(viewportReferences.buttonSpaceHolderContainer);
	}
};;
// Compiled from 'script/keyboardNavigation.js'
/**
 * Collection of keyboard navigation related functions and classes 
 */

/**
 * Static functions
 */
KEYBOARD_NAVIGATION = {
		
	indentCounter: 0,
	
	processingLog: "",
	
	doLog: false,
	
	logIndentStep: " ",
	
	log: function(message) {
		KEYBOARD_NAVIGATION.processingLog += message;
	},

	clearLog: function() {
		KEYBOARD_NAVIGATION.processingLog = "";
		KEYBOARD_NAVIGATION.indentCounter = 0;
	},
	
	submitLog: function() {
		services.log.info(KEYBOARD_NAVIGATION.processingLog);
	},
	
	/**
	 * Filter, to determine, whether an element is a complex widget, or not
	 * 
	 * @param element - the current node
	 */
	complexWidgetFilter: function(element) {
		if(!BAL.DOM.hasAttributes(element)) {
			return false;
		}
		
		var widgetMarker = BAL.DOM.getNonStandardAttribute(element, "data-complexwidget");
		var widgetMarkerFound = (widgetMarker != null) && (widgetMarker == "true");
		
		// In case of logging
		if(KEYBOARD_NAVIGATION.doLog) {
			
			// Use tabbable filter for tag logging
			BAL.DOM.simpleFocusableElementFilter(element, KEYBOARD_NAVIGATION.log);
			
			if(widgetMarkerFound) {
				
				// Write indents
				for(var i = 0; i < KEYBOARD_NAVIGATION.indentCounter; i++) {
					KEYBOARD_NAVIGATION.log(KEYBOARD_NAVIGATION.logIndentStep); 
				}
				
				KEYBOARD_NAVIGATION.log("--- Begin of complex widget ---\n");
			}
		}
		
		return widgetMarkerFound;
	},
	
	/**
	 * Filter, to determine, whether an element is a root of a tab-cycle
	 * 
	 * @param element - the current node
	 */
	tabCycleRootFilter: function(element) {
		if(!BAL.DOM.hasAttributes(element)) {
			return false;
		}
		
		var rootMarker = BAL.DOM.getNonStandardAttribute(element, "data-tabroot");
		var tabRootFound = (rootMarker != null) && (rootMarker == "true");
		
		if(KEYBOARD_NAVIGATION.doLog && tabRootFound) {
			// Write indents
			for(var i = 0; i < KEYBOARD_NAVIGATION.indentCounter; i++) {
				KEYBOARD_NAVIGATION.log(KEYBOARD_NAVIGATION.logIndentStep); 
			}
			
			KEYBOARD_NAVIGATION.log("*** Tab root found ***\n");
		}
		
		return tabRootFound;
	},
	
	/**
	 * Filter, to determine, whether an element is a external frame, or not
	 * 
	 * @param element - the current node
	 */
	externalFrameFilter: function(element) {
		if(!BAL.DOM.hasAttributes(element)) {
			return false;
		}
		
		var frameMarker = BAL.DOM.getNonStandardAttribute(element, "data-externalframe");
		var frameMarkerFound = (frameMarker != null) && (frameMarker == "true");
		
		// In case of logging
		if(KEYBOARD_NAVIGATION.doLog) {
			
			// Use tabbable filter for tag logging
			BAL.DOM.simpleFocusableElementFilter(element, KEYBOARD_NAVIGATION.log);
			
			if(frameMarkerFound) {
				
				// Write indents
				for(var i = 0; i < KEYBOARD_NAVIGATION.indentCounter; i++) {
					KEYBOARD_NAVIGATION.log(KEYBOARD_NAVIGATION.logIndentStep); 
				}
				
				KEYBOARD_NAVIGATION.log("--- External frame found ---\n");
			}
		}
		
		return frameMarkerFound;
	},
	
	/**
	 * Handler, if a key was pressed
	 * 
	 * @param event - the event, which occurred.
	 */
	keyDownHandler: function(event) {
		event = BAL.getEvent(event);
		var keycode = BAL.getKeyCode(event);
		
		// Tab (simple widget) switch
		if(keycode == 9) {
			var activeElement = BAL.DOM.getCurrentActiveSimpleElement();
			
			var rootElement = KEYBOARD_NAVIGATION.getRootElement(activeElement,
																 KEYBOARD_NAVIGATION.tabCycleRootFilter);
			
			// If an element below an root element is focused, focus following element
			if(rootElement != null) {
				
				// Forward tabbing
				if(!BAL.hasShiftModifier(event)) {
					KEYBOARD_NAVIGATION.TABMANAGER_INSTANCE.switchToNextSimpleWidget(activeElement);
				}
				
				// Backward tabbing
				else {
					KEYBOARD_NAVIGATION.TABMANAGER_INSTANCE.switchToPreviousSimpleWidget(activeElement);
				}
			}
			
			else {
				KEYBOARD_NAVIGATION.focusFirstFocusableElementInVisibleLayer();
			}
			
			return BAL.cancelEvent(event);
		}
		
		// Complex widget switch
		else if (((keycode == 33) || (keycode == 34))&& BAL.hasCtrlModifier(event)) {
			
			var firstTimeSnapshot = null;
			if(KEYBOARD_NAVIGATION.doLog) {
				firstTimeSnapshot = new Date();
			}
			
			var activeElement = BAL.DOM.getCurrentActiveSimpleElement();
			
			var rootElement = KEYBOARD_NAVIGATION.getRootElement(activeElement,
																 KEYBOARD_NAVIGATION.tabCycleRootFilter);
			
			// If an element below an root element is focused, focus following element
			if(rootElement != null) {
				
				// Forward tabbing
				if(keycode == 34) {
					KEYBOARD_NAVIGATION.TABMANAGER_INSTANCE.switchToNextComplexWidget(activeElement);
				}
				
				// Backward tabbing
				else {
					KEYBOARD_NAVIGATION.TABMANAGER_INSTANCE.switchToPreviousComplexWidget(activeElement);
				}
			}
			
			// If no element is focused, try to find first focusable element below layout
			// root nodes in following order:
			// PopupDialogs, dialogs, application window
			else {
				KEYBOARD_NAVIGATION.focusFirstFocusableElementInVisibleLayer();
			}
			
			if(KEYBOARD_NAVIGATION.doLog) {
				var secondTimeSnapshot = new Date();
				var executionTime = secondTimeSnapshot.getTime() - firstTimeSnapshot.getTime();
				KEYBOARD_NAVIGATION.log("Overall focus switch time: " + executionTime + "ms\n");
				KEYBOARD_NAVIGATION.submitLog();
				KEYBOARD_NAVIGATION.clearLog();
			}
			
			return BAL.cancelEvent(event);
		}
		
		return true;
	},
	
	/**
	 * Performs a focus, if no element currently owns a focus. Therefore this function
	 * tries to focus an element in the top-most visible window. First it will check for any
	 * open popup dialogs. If there are no open popup dialogs, it will try to find a focusable
	 * element in any open dialog. If there are also no open dialogs, it will focus the first
	 * focusable element in the application window.
	 */
	focusFirstFocusableElementInVisibleLayer: function (documentContext) {
		
		var topLevelDocument = KEYBOARD_NAVIGATION.getTopLevelDocument(documentContext);
		
		if(KEYBOARD_NAVIGATION.isAnyPopupDialogOpen(topLevelDocument)) {
			KEYBOARD_NAVIGATION.focusElementInTopmostPopupDialog(topLevelDocument);
		}
		
		else if(KEYBOARD_NAVIGATION.isAnyDialogOpen(topLevelDocument)) {
			KEYBOARD_NAVIGATION.focusElementInTopmostDialog(topLevelDocument);
		}
		
		else {
			KEYBOARD_NAVIGATION.focusFirstElementInApplicationWindow(topLevelDocument);
		}
	},
	
	getTopLevelDocument: function (documentContext) {
		
		if(documentContext == undefined || documentContext == null) {
			return BAL.getTopLevelDocument();
		} else {
			return BAL.getTopLevelDocumentOf(documentContext);
		}
	},
	
	isAnyPopupDialogOpen: function(topLevelDocument) {
		var popupDialogsAnchor = KEYBOARD_NAVIGATION.getPopupDialogsAnchor(topLevelDocument);
		return !KEYBOARD_NAVIGATION.hasNoOrTextNodeChildsOnly(popupDialogsAnchor);
	},
	
	getPopupDialogsAnchor: function(topLevelDocument) {
		return topLevelDocument.getElementById("pdlgPopupDialogs");
	},
	
	focusElementInTopmostPopupDialog: function(topLevelDocument) {
		var topmostPopupDialog = KEYBOARD_NAVIGATION.getTopmostPopupDialogContent(topLevelDocument);
		KEYBOARD_NAVIGATION.focusFirstFocusableElementBelow(topmostPopupDialog);
	},
	
	getTopmostPopupDialogContent: function(topLevelDocument) {
		var popupDialog = BAL.DOM.getLastElementChild(KEYBOARD_NAVIGATION.getPopupDialogsAnchor(topLevelDocument));
		var popupDialogId = BAL.DOM.getAttribute(popupDialog, "id");
		return topLevelDocument.getElementById(popupDialogId + "-content");
	},
	
	isAnyDialogOpen: function(topLevelDocument) {
		var dialogsAnchor = KEYBOARD_NAVIGATION.getDialogsAnchor(topLevelDocument);
		return !KEYBOARD_NAVIGATION.hasNoOrTextNodeChildsOnly(dialogsAnchor);
	},
	
	getDialogsAnchor: function(topLevelDocument) {
		return topLevelDocument.getElementById("dlgDialogs");
	},
	
	focusElementInTopmostDialog: function(topLevelDocument) {
		// The standard use case is to focus an element in the content of the
		// dialog and not an element in the title bar.
		var topmostDialog = KEYBOARD_NAVIGATION.getTopmostDialogContent(topLevelDocument);
		KEYBOARD_NAVIGATION.focusFirstFocusableElementBelow(topmostDialog);
	},
	
	getTopmostDialogContent: function(topLevelDocument) {
		var dialog = BAL.DOM.getLastElementChild(KEYBOARD_NAVIGATION.getDialogsAnchor(topLevelDocument));
		var dialogId = BAL.DOM.getAttribute(dialog, "id");
		return topLevelDocument.getElementById(dialogId + "-content");
	},
	
	getTopmostDialog: function(topLevelDocument) {
		var dialog = BAL.DOM.getLastElementChild(KEYBOARD_NAVIGATION.getDialogsAnchor(topLevelDocument));
		var dialogId = BAL.DOM.getAttribute(dialog, "id");
		return topLevelDocument.getElementById(dialogId + "-window");
	},
	
	focusFirstFocusableElementBelowFirstElementNode: function(anchor) {
		var rootNode = BAL.DOM.getFirstElementChild(anchor);
		KEYBOARD_NAVIGATION.focusFirstFocusableElementBelow(rootNode);
	},
	
	focusFirstFocusableElementBelow: function(anchor) {
		var oldActiveElement = BAL.DOM.getCurrentActiveSimpleElement();
		KEYBOARD_NAVIGATION.focusTextFieldBelow(anchor);
		
		var newActiveElement = BAL.DOM.getCurrentActiveSimpleElement();
		if(oldActiveElement == newActiveElement) {
			KEYBOARD_NAVIGATION.focusFocusableElementBelow(anchor);
		}
	},
	
	focusFocusableElementBelow: function(anchor) {
		KEYBOARD_NAVIGATION.focusElementBelow(anchor, BAL.DOM.simpleFocusableElementFilter);
	},
	
	focusElementBelow: function(anchor, elementFilter) {
		if(anchor != null) {
			var focusableCandidate = anchor.firstChild;
			
			var selectionSuccessful = false;
			if (elementFilter(focusableCandidate)) {
				selectionSuccessful = BAL.focus(focusableCandidate);
			}
			
			if(!selectionSuccessful) {
				var tabber = new KEYBOARD_NAVIGATION.TabManager(
						KEYBOARD_NAVIGATION.complexWidgetFilter,
						KEYBOARD_NAVIGATION.tabCycleRootFilter,
						elementFilter);
				tabber.switchToNextSimpleWidget(focusableCandidate);
			}
		}
	},
	
	focusTextFieldBelow: function(anchor) {
		var currentDocument = BAL.getCurrentDocument(anchor);
		KEYBOARD_NAVIGATION.TextFieldFocuser.focusFirstIn(currentDocument);
	},
	
	focusFirstElementInApplicationWindow: function(topLevelDocument) {
		var anchor = KEYBOARD_NAVIGATION.getApplicationWindowAnchor(topLevelDocument);
		if(!KEYBOARD_NAVIGATION.hasNoOrTextNodeChildsOnly(anchor)) {
			KEYBOARD_NAVIGATION.focusFirstFocusableElementBelowFirstElementNode(anchor);
		}
	},
	
	getApplicationWindowAnchor: function(topLevelDocument) {
		return topLevelDocument.getElementById("applicationContent");
	},
	
	/**
	 * Checks, whether the current element has no children, or text node childs only.
	 */
	hasNoOrTextNodeChildsOnly: function(element) {
		if(element == undefined || element == null) {
			return true;
		}
		
		if(element.firstChild == null) {
			return true;
		}
		
		for(var i = 0; i < element.childNodes.length; i++) {
			if(!BAL.DOM.isTextNode(element.childNodes[i])) {
				return false;
			}
		}
		      
		return true;
	},
	
	isBelowVisibleTabroot: function(node) {
		var topmostVisibleTabroot = KEYBOARD_NAVIGATION.getTopmostVisibleLayerAnchor(BAL.getTopLevelDocumentOf(node));
		
		if(!KEYBOARD_NAVIGATION.tabCycleRootFilter(topmostVisibleTabroot)) {
			return false;
		}
		
		var currentNode = node;
		while(KEYBOARD_NAVIGATION.hasParent(currentNode)) {
			currentNode = KEYBOARD_NAVIGATION.getParentElement(currentNode);
			if(currentNode === topmostVisibleTabroot) {
				return true;
			}
		}
		
		return false;
	},
	
	getTopmostVisibleLayerAnchor: function(topLevelDocument) {
		if(KEYBOARD_NAVIGATION.isAnyPopupDialogOpen(topLevelDocument)) {
			return KEYBOARD_NAVIGATION.getTopmostPopupDialogContent(topLevelDocument);
		}
		else if(KEYBOARD_NAVIGATION.isAnyDialogOpen(topLevelDocument)) {
			return KEYBOARD_NAVIGATION.getTopmostDialog(topLevelDocument);
		}
		else {
			return KEYBOARD_NAVIGATION.getApplicationWindowAnchor(topLevelDocument);
		}
	},
	
	hasParent: function(node) {
		return KEYBOARD_NAVIGATION.getParentElement(node) != null;
	},
	
	/**
	 * Retrieves the current element's defined root node.
	 * 
	 * @param currentElement - the element, whose root node shall be retrieved
	 * @param rootElementFilter - a filter, which determines, whether an element is a defined root, or not
	 * 
	 * @returns the current element's root node, or <code>null</code> if none exists.
	 */
	getRootElement: function(currentElement, rootElementFilter) {
		
		var parentNode = currentElement;
		
		var isNotBelowRootNode = !rootElementFilter(parentNode);
		
		while(isNotBelowRootNode && (parentNode != null)) {
			parentNode = KEYBOARD_NAVIGATION.getParentElement(parentNode);
			isNotBelowRootNode = !rootElementFilter(parentNode);
		}
		
		return parentNode;
	},
		
    /**
     * Retrieves the current element's parent in DOM, regardless, whether the
     * current element resides in an iframe, or not.
     * 
     * @param currentElement -
     *            the element, whose parent in the DOM shall be retrieved, must
     *            not be <code>null</code>
     * 
     * @returns the parent element, or <code>null</code> if not present
     */
    getParentElement : function(currentElement) {
        var parentNode = currentElement.parentNode;

        // Check whether the current Element is child of an iframe
        if (parentNode == null
                || parentNode == BAL.getCurrentDocument(currentElement)) {
            var currentWindow = BAL.getCurrentWindow(currentElement);
            if(currentWindow != BAL.getTopLevelWindow(currentElement)) {
	            var searchResult = BAL.searchIFrame(currentWindow.parent.document,
	                    currentWindow);
	            if (searchResult.index != -1) {
	                parentNode = searchResult.iFrames[searchResult.index];
	            }
            }
        }

        // In case of logging
        if (KEYBOARD_NAVIGATION.doLog && (parentNode != null)) {
            KEYBOARD_NAVIGATION.log("- ");
        }

        return parentNode;
    },

    /**
     * Retrieves the current element's first or last child in DOM, regardless,
     * whether the child element resides in an iframe, or not.
     * 
     * @param currentElement -
     *            the element, whose child in the DOM shall be retrieved, must
     *            not be <code>null</code>
     * @param retrieveFirstChild -
     *            true retrieves the first child, false the last child
     * 
     * @returns the child element, according to retrieveFirstChild, or
     *          <code>null</code> if not present
     */
    getChildElement : function(currentElement, retrieveFirstChild) {
        var childNode = retrieveFirstChild ? BAL.DOM
                .getFirstChild(currentElement) : BAL.DOM
                .getLastChild(currentElement);

        // Check for iframe as child
        if ((childNode == null) && (currentElement.tagName != undefined)
                && (currentElement.tagName != null)) {

            var tagName = currentElement.tagName.toLowerCase();
            if (tagName == "iframe"
                    && !services.ajax.isLoading(BAL
                                    .getCurrentDocument(currentElement),
                            currentElement)
                    && !KEYBOARD_NAVIGATION.externalFrameFilter(currentElement)) {
                var frameBody = BAL.getFrameWindow(currentElement).document.body;
                if (frameBody != null) {
                    if (retrieveFirstChild) {
                        childNode = BAL.DOM.getFirstChild(frameBody);
                    } else {
                        childNode = BAL.DOM.getLastChild(frameBody);
                    }
                }
            }
        }

        // In case of logging
        if (KEYBOARD_NAVIGATION.doLog && (childNode != null)) {
            KEYBOARD_NAVIGATION.log("+ ");
        }

        return childNode;
    }
};

/**
 * Creates a new tab manager, which provides tabbing between complex widgets. Thereby several
 * root elements can be specified, which define different tabbing cycles.
 * 
 * @param complexWidgetFilter - filter-function, to determine, whether an element is a container
 * 								of a complex widget, or not.
 * @param tabCycleRootFilter - filter-function, to determine, whether an element is a root for
 * 							   a subtree, wherein tab cycling takes place.
 * @param tabbableElementFilter - filter-function, to determine, whether to an element can be
 * 								 tabbed to, or not. 
 */
KEYBOARD_NAVIGATION.TabManager = function(complexWidgetFilter, tabCycleRootFilter, tabbableElementFilter){
		
	var self = this;
	self._complexWidgetFilter = complexWidgetFilter;
	self._tabCycleRootFilter = tabCycleRootFilter;
	self._tabbableElementFilter = tabbableElementFilter;
	
	/**
	 * Focuses the following focusable element in the following complex widget in search order. If there is no other focusable element,
	 * the current element will remain focus.
	 * 
	 * @param currentElement - the element, which is currently focused, must not be <code>null</code>
	 * @param searchForward - true, if forward search shall be performed, false otherwise
	 */
	self._switchToFollowingInOrder = function(currentElement, searchForward) {
		
		// Failsafe
		if(currentElement == null) {
			throw new Error("Cannot switch element focus, because current element is null!");
		}
		
		var firstTimeSnapshot = null;
		var overallComplexWidgetSearchTime = 0;
		var overallSimpleWidgetSearchTime = 0;
		
		// Remove focus from current element
		currentElement.blur();
		
		if(KEYBOARD_NAVIGATION.doLog) {
			// Create initial indention
			var parentElement = currentElement;
			var initialIndention = 0;
			do {
				parentElement = KEYBOARD_NAVIGATION.getParentElement(parentElement);
				initialIndention++;
			} while(parentElement != null);
			KEYBOARD_NAVIGATION.indentCounter = initialIndention + 1;
			
			KEYBOARD_NAVIGATION.log("=== Lookup for complex widget ===\n");
			
			firstTimeSnapshot = new Date();
		}
		
		// Lookup for next complex widget
		var nextComplexWidgetElement = _nextComplexWidgetElement(currentElement, searchForward);
		
		if(KEYBOARD_NAVIGATION.doLog) {
			overallComplexWidgetSearchTime = (new Date()).getTime() - firstTimeSnapshot.getTime();
			
			if(nextComplexWidgetElement == null) {
				KEYBOARD_NAVIGATION.log("=== NO COMPLEX WIDGET FOUND! ===\n");
			}
		}
		
		if(nextComplexWidgetElement != null) {
			
			// Cycle as long as not a complex widget with a selectable simple widget is found, or
			// a full DOM cycle was performed
			var focussingSuccessful = false;
			var cycleMarkerElement = nextComplexWidgetElement;
			do {
				var simpleWidgetFirstTimeSnapshot = null;
				if(!KEYBOARD_NAVIGATION.hasNoOrTextNodeChildsOnly(nextComplexWidgetElement)) {
					
					if(KEYBOARD_NAVIGATION.doLog) {
						KEYBOARD_NAVIGATION.log("=== Lookup for simple widget ===\n");
						simpleWidgetFirstTimeSnapshot = new Date();
					}
					
					var focusableCandidate = self._nextTabbableElement(nextComplexWidgetElement, true, nextComplexWidgetElement,
							self._complexWidgetFilter);
					
					if(focusableCandidate != null) {
						focussingSuccessful = self._focusFollowing(focusableCandidate, true, nextComplexWidgetElement,
								self._complexWidgetFilter);
					}	
					
					if(KEYBOARD_NAVIGATION.doLog) {
						overallSimpleWidgetSearchTime += (new Date()).getTime() - simpleWidgetFirstTimeSnapshot;
					}
				}
				
				if(!focussingSuccessful) {
					var complexWidgetFirstTimeSnapshot = null;
					if(KEYBOARD_NAVIGATION.doLog) {
						KEYBOARD_NAVIGATION.log("=== Lookup for complex widget ===\n");
						complexWidgetFirstTimeSnapshot = new Date();
					}
					
					nextComplexWidgetElement = _nextComplexWidgetElement(nextComplexWidgetElement, searchForward);
					
					if(KEYBOARD_NAVIGATION.doLog) {
						overallComplexWidgetSearchTime += (new Date()).getTime() - complexWidgetFirstTimeSnapshot;
					}
				}
			} while(!focussingSuccessful && (nextComplexWidgetElement != null) && (nextComplexWidgetElement != cycleMarkerElement))
		}
		
		
		// Focus current element, if no other focusable elements are available
		else {
			BAL.focus(currentElement);
		}
		
		if(KEYBOARD_NAVIGATION.doLog) {
			var secondTimeSnapshot = new Date();
			var executionTime = secondTimeSnapshot.getTime() - firstTimeSnapshot.getTime();
			KEYBOARD_NAVIGATION.log("Complex widget search time: " + overallComplexWidgetSearchTime + "ms\n");
			KEYBOARD_NAVIGATION.log("Simple widget search time: " + overallSimpleWidgetSearchTime + "ms\n");
			KEYBOARD_NAVIGATION.log("Tabbing time: " + executionTime + "ms\n");
			KEYBOARD_NAVIGATION.indentCounter = 0;
			
			// Log will be cleared at function caller
		}
	};
	
	/**
	 * Retrieves the current element's very tabbable neighbor in the DOM.
	 * Based on search forward flag, the lookup for the next element will be performed in depth
	 * first, or in reverse depth first order.
	 * 
	 * @param currentElement - the element, whose very tabbable neighbor in the DOM shall be retrieved,
	 * 						   must not be <code>null</code>
	 * @param searchForward - true, if forward search shall be performed, false otherwise
	 * @param rootElement - the element at which traversal shall be stopped, in case it
	 * 						  will be passed while finding the next element, may be <code>null</code>,
	 * 						  if there is no stop element
	 * @param stopElementFilter - filter to check, whether traversal shall be stopped at analyzed element,
	 * 						  or not, may be <code>null</code>, if there are no stop elements.
	 * 
	 * @returns the element's very tabbable neighbor, may be <code>null</code>.
	 */
    self._nextTabbableElement = function(currentElement, searchForward, rootElement, stopElementFilter) {
    	return _nextElement(currentElement, searchForward, rootElement,
    			self._tabbableElementFilter, stopElementFilter);
    };
    
    /**
	 * Focus the current element, or focusable followers of the current element,
	 * if the current element is not focusable.
	 * 
	 * @param currentElement - the element to focus
	 * @param searchForward - true, if forward search shall be performed, false otherwise
	 * @param rootElement - the element at which traversal shall be stopped, in case it
	 * 						  will be passed while finding the next element, may be <code>null</code>,
	 * 						  if there is no stop element
	 * @param stopElementFilter - filter to check, whether traversal shall be stopped at analyzed element,
	 * 						  or not, may be <code>null</code>, if there are no stop elements.
	 * 
	 * @returns true, if the current element or one of its followers could be focused
	 */
	self._focusFollowing = function(currentElement, searchForward, rootElement, stopElementFilter) {
		
		// Cycle as long as not a selectable simple widget is found, or
		// a full DOM cycle was performed
		var focussingSuccessful = false;
		var cycleMarkerElement = currentElement;
		var focusableCandidate = currentElement;
		do {
			if(KEYBOARD_NAVIGATION.doLog) {
				KEYBOARD_NAVIGATION.log("=== Select simple widget ===\n");
			}
			
			// Set focus to new element
			if(focusableCandidate != null) {
				focussingSuccessful = BAL.focus(focusableCandidate);
			}
			
			if(KEYBOARD_NAVIGATION.doLog && !focussingSuccessful) {
				KEYBOARD_NAVIGATION.log("=== SIMPLE WIDGET NOT FOCUSABLE! ===\n");
			}
			
			if(!focussingSuccessful) {
				focusableCandidate = self._nextTabbableElement(focusableCandidate, searchForward, rootElement,
															   stopElementFilter);
			} 
		} while((!focussingSuccessful) && (focusableCandidate != null) &&
				(focusableCandidate !== cycleMarkerElement));
		
		return focussingSuccessful;
	};
    
    /**
     * Retrieves the current element's complex widget neighbor in the DOM.
     * Based on search forward flag, the lookup for the next element will be performed in depth
	 * first, or in reverse depth first order.
	 * 
     * @param currentElement - the element, whose complex widget neighbor in the DOM shall be retrieved,
     * 						   must not be <code>null</code>
     * @param searchForward - true, if forward search shall be performed, false otherwise
     * 
     * @returns the element's complex widget neighbor, may be <code>null</code>.
     */
    function _nextComplexWidgetElement(currentElement, searchForward) {
    	
    	// In case of reverse search order, retrieve current element complex widget parent.
    	// This element will be ignored while traversing, because we want to retrieve the complex
    	// widget, which resides in DOM before this one.
    	// If the current element is a complex widget itself, there is no need to search for
    	// the parent complex widget, because this can only happen in case of incremental search.
    	// Thereby it is impossible to fall back to the currently selected element, if there
    	// are other selectable elements in DOM below the same tab root.
    	var complexWidgetParent = null;
    	if(!searchForward && !KEYBOARD_NAVIGATION.complexWidgetFilter(currentElement)) {
    		if(KEYBOARD_NAVIGATION.doLog) {
    			KEYBOARD_NAVIGATION.log("=== Complex widget parent search begin ===\n");
    		}
    		
    		var complexWidgetParentCandidate = currentElement;
    		while(!self._complexWidgetFilter(complexWidgetParentCandidate) &&
				  !self._tabCycleRootFilter(complexWidgetParentCandidate)){
    			complexWidgetParentCandidate = KEYBOARD_NAVIGATION.getParentElement(complexWidgetParentCandidate);
    			if(self._complexWidgetFilter(complexWidgetParentCandidate)) {
    				complexWidgetParent = complexWidgetParentCandidate;
    			}
			};
    		
    		if(KEYBOARD_NAVIGATION.doLog) {
    			KEYBOARD_NAVIGATION.log("=== Complex widget parent search end ===\n");
    		}
    	}
    	
    	// Retrieve following complex widget, while ignoring parent complex widget
    	var followingComplexWidget = _nextElement(currentElement, searchForward,
    										 	/*rootElement*/ null,
    										 	self._complexWidgetFilter, null);
    	
    	if((complexWidgetParent != null) && (followingComplexWidget == complexWidgetParent)) {
    		if(KEYBOARD_NAVIGATION.doLog) {
    			KEYBOARD_NAVIGATION.log("!!!! Ignore parent complex widget !!!!\n");
    		}

    		followingComplexWidget = _nextElement(complexWidgetParent, searchForward,
    											/*rootElement*/ null,
    											self._complexWidgetFilter, null);
    	}
    	
    	
    	return followingComplexWidget;
    }
    
    /**
	 * Retrieves the current element's neighbor in the DOM, which fits some filter criteria.
	 * Based on search forward flag, the lookup for the next element will be performed in depth
	 * first, or in reverse depth first order.
	 * 
	 * @param currentElement - the element, whose neighbor in the DOM shall be retrieved,
	 * 						   must not be <code>null</code>
	 * @param searchForward - true, if forward search shall be performed, false otherwise
	 * @param rootElement - the element at which traversal shall be stopped, in case it
	 * 						  will be passed while finding the next element, may be <code>null</code>,
	 * 						  if there is no stop element
	 * @param elementFilter - filter to check, whether a node fits the search criteria, or not
	 * @param stopElementFilter - filter to check, whether traversal shall be stopped at analyzed element,
	 * 						  or not, may be <code>null</code>, if there are no stop elements.
	 * 
	 * @returns the element's neighbor, according to the given element filter, may be <code>null</code>.
	 */
    function _nextElement(currentElement, searchForward, rootElement, elementFilter, stopElementFilter) {
		
    	var startElement = currentElement;
    	var touchingElement = currentElement;
    	var followingElement = null;
    	var elementNotFound = true;
    	var stopElementNotFound = true;
    	do {
			
    		
    		// If following child is present
    		followingElement = _getLowerHierarchyElement(touchingElement, searchForward);
    		if(followingElement == null) {
    			
    			// If following sibling is present
    			followingElement = _getSiblingElement(touchingElement, searchForward);
    			if(followingElement == null) {
    				
    				// If following upper hierarchy element (parent or parent's following sibling) is present
    				if(rootElement == null) {
    					followingElement = _getUpperHierarchyElement(touchingElement, null, searchForward);
    				}
    				else {
    					followingElement = _getUpperHierarchyElement(touchingElement, rootElement, searchForward);
    				}
    			}
    			
    			// Report error, if we detect tab root cycle entered from above
    			else if(self._tabCycleRootFilter(followingElement)) {
    				_reportError(followingElement);
    				return null;
    			}
    		}
    		
    		// Report error, if we detect tab root cycle entered from above
			else if(self._tabCycleRootFilter(followingElement)) {
				_reportError(followingElement);
				return null;
			}
    		
    		
    		// Check if element fits traversal stop criteria
    		stopElementNotFound = (stopElementFilter == null) || !stopElementFilter(followingElement);
    		
    		if(stopElementNotFound) {
    			// Check if element fits selection criteria
    			if(!KEYBOARD_NAVIGATION.doLog) {
    				elementNotFound = !elementFilter(followingElement);
    			} else {
    				elementNotFound = !elementFilter(followingElement, KEYBOARD_NAVIGATION.log);
    			}
    		}
    		
    		touchingElement = followingElement;
		} while(elementNotFound && stopElementNotFound && (followingElement != null) &&
				(startElement !== touchingElement));
    	
    	if(KEYBOARD_NAVIGATION.doLog) {
    		if(followingElement == null) {
    			KEYBOARD_NAVIGATION.log("!!!!! Following node is null !!!!!\n");
    		}
    		
    		if(startElement === touchingElement) {
    			KEYBOARD_NAVIGATION.log("!!!!! DOM round-trip !!!!!\n");
    		}
    	}
    	
    	if(!elementNotFound) {
    		return followingElement;
    	}
    	else {
    		return null;
    	}
    }
    
    function _reportError(followingElement) {
    	var nodeID = "";
		if((BAL.DOM.getAttribute(followingElement, "id") != undefined) &&
		(BAL.DOM.getAttribute(followingElement, "id") != null)) {
			nodeID = BAL.DOM.getAttribute(followingElement, "id");
		}
		services.log.warn("Invalid tab root cycle marker found in subtree at node '" +
							nodeID +"'. Stop finding focusable element.");
    }
    
	/**
	 * Retrieves the current element's upper element in DOM (according to sort order, this may
	 * be the element's parent or the very next parent's sibling).
	 * 
	 * @param currentElement - the element, whose ascending element in the DOM shall be retrieved,
	 * 						   must not be <code>null</code>
	 * @param rootElement - the parent node, where to stop traversing upwards (may be <code>null</code>,
	 * 						if there is no parent node to stop at)
	 * @param searchForward - true, if forward search shall be performed, false otherwise
	 *
	 * @returns the ascending element, or <code>null</code> if not present, or traversal passed the stop element
	 */
	function _getUpperHierarchyElement(currentElement, rootElement, searchForward) {
		var parentNode = KEYBOARD_NAVIGATION.getParentElement(currentElement);
		var rootElementNotFound = (currentElement != rootElement) && (parentNode != rootElement);
		
		if((parentNode == null) || !rootElementNotFound ||
			self._tabCycleRootFilter(parentNode)) {
			return null;
		}
		
		if(searchForward) {
			// Find upper hierarchy node with a sibling
			var parentNodeSibling = null;
			do {
				rootElementNotFound = (parentNode != rootElement);
				parentNodeSibling = _getSiblingElement(parentNode, searchForward);
				parentNode = KEYBOARD_NAVIGATION.getParentElement(parentNode);
			} while(rootElementNotFound && (parentNodeSibling == null) &&
					(parentNode != null) && !self._tabCycleRootFilter(parentNode));
			
			if(rootElementNotFound) {
				return parentNodeSibling;
			}
			
			else {
				KEYBOARD_NAVIGATION.log("*** Defined root element reached ***\n");
				return null;
			}
		}
		else {
			return parentNode;
		}
	}
	
	/**
	 * Retrieves the current element's lower element in DOM (according to sort order, this may
	 * be the element's first child or the very last child of the previous sibling).
	 * 
	 * @param currentElement - the element, whose descending element in the DOM shall be retrieved,
	 *                         must not be <code>null</code>
	 * @param searchForward - true, if forward search shall be performed, false otherwise
	 *
	 * @returns the descending element, or <code>null</code> if not present
	 */
	function _getLowerHierarchyElement(currentElement, searchForward) {
		if(searchForward) {
			return KEYBOARD_NAVIGATION.getChildElement(currentElement, /* retrieveFirstChild */ true);
		}
		else {
			var previousSibling = _getSiblingElement(currentElement, searchForward);
			if(previousSibling != null) {
				var childNode = KEYBOARD_NAVIGATION.getChildElement(previousSibling, /*retrieveFirstChild*/ false);
				if(childNode != null) {
					while(KEYBOARD_NAVIGATION.getChildElement(childNode, /*retrieveFirstChild*/ false) != null) {
						childNode = KEYBOARD_NAVIGATION.getChildElement(childNode, /*retrieveFirstChild*/ false);
					}
				}
				
				return childNode;
			}
			
			return null;
		}
	}
	
	/**
	 * Retrieves the current element's sibling in DOM (according to sort order, this may
	 * be the previous or the next sibling).
	 * 
	 * @param currentElement - the element, whose sibling in the DOM shall be retrieved,
	 *                         must not be <code>null</code>
	 * @param searchForward - true, if forward search shall be performed, false otherwise
	 * 
	 * @returns the sibling element, or <code>null</code> if not present
	 */
	function _getSiblingElement(currentElement, searchForward) {
		if (BAL.DOM.isElementNode(currentElement)) {
			var nextId = BAL.DOM.getNonStandardAttribute(currentElement, searchForward ? "data-tabnext" : "data-tabprev");
			if (nextId != null) {
				var followingElement = document.getElementById(nextId);
				if (followingElement != null) {
					return followingElement;
				}
			}
		}
		
		if(searchForward) {
			var sibling = BAL.DOM.getNextElementSibling(currentElement);
			// Check for tab cycle end 
			if(sibling == null) {
				var rootNodeCandidate = KEYBOARD_NAVIGATION.getParentElement(currentElement);
				if(self._tabCycleRootFilter(rootNodeCandidate)) {
					sibling = KEYBOARD_NAVIGATION.getChildElement(rootNodeCandidate, /*retrieveFirstChild*/ true);
				}
			}
			return sibling;
		}
		else {
			var sibling = BAL.DOM.getPreviousElementSibling(currentElement);
			// Check for tab cycle end
			if(sibling == null) {
				var rootNodeCandidate = KEYBOARD_NAVIGATION.getParentElement(currentElement);
				if(self._tabCycleRootFilter(rootNodeCandidate)) {
					sibling = KEYBOARD_NAVIGATION.getChildElement(rootNodeCandidate, /*retrieveFirstChild*/ false);
				}
			} 
			
			return sibling;
		}
	}
	
	self._lookupForTableCellParent = function(currentNode) {
		var tdCandidate = currentNode;
		while(_isNotTableCell(tdCandidate)) {
			tdCandidate = KEYBOARD_NAVIGATION.getParentElement(tdCandidate);
		}
		return tdCandidate;
	};
	
	function _isNotTableCell(currentNode) {
		return currentNode != null && !_isTableCell(currentNode);
	}
	
	function _isTableCell(currentNode) {
		return BAL.DOM.isElementNode(currentNode) && (BAL.DOM.getTagName(currentNode) === "td" || BAL.DOM.getTagName(currentNode) === "th") &&
				(BAL.DOM.containsClass(currentNode, "tblTD") ||	BAL.DOM.containsClass(currentNode, "tblTDselect") || BAL.DOM.containsClass(currentNode, "tblTH"));
	}
	
	self._scrollToTD = function(tdElement) {
		var trElement = KEYBOARD_NAVIGATION.getParentElement(tdElement);
		var scrollContainer = null;
		if(_isFrozenTable(trElement)) {
			if(_isFlexPart(trElement)) {
				var flexViewport = _getFlexViewport(trElement);
				scrollContainer = flexViewport.getContentElement().parentNode;
				_scrollHorizontal(scrollContainer, tdElement);
				_scrollVertical(scrollContainer, tdElement);
			} else {
				var fixViewport = _getFixViewport(trElement);
				scrollContainer = fixViewport.getContentElement().parentNode;
				_scrollVertical(scrollContainer, tdElement);
			}
		}
	};
	
	self._scrollToTH = function(thElement) {
		var trElement = KEYBOARD_NAVIGATION.getParentElement(thElement);
		if(_isFrozenTable(trElement)) {
			_scrollHorizontal(thElement.offsetParent.offsetParent, thElement);
		}
	};
	
	function _scrollHorizontal(scrollContainer, cell) {
		var horizontalScrollPosition = BAL.getScrollLeftElement(scrollContainer);
		var viewportWidth = BAL.getElementWidth(scrollContainer);
		var cellLeftOffset = BAL.getElementX(cell);
		var cellWidth = BAL.getElementWidth(cell);
		_scroll(scrollContainer, horizontalScrollPosition, viewportWidth, cellLeftOffset, cellWidth, _scrollLeftTo);
	}
	
	function _scrollVertical(scrollContainer, cell) {
		var verticalScrollPosition = BAL.getScrollTopElement(scrollContainer);
		var viewportHeight = BAL.getElementHeight(scrollContainer);
		var sliceDiv = cell.offsetParent.offsetParent;
		var cellTopOffset = BAL.getElementY(cell) + BAL.getElementY(sliceDiv);
		var cellHeight = BAL.getElementHeight(cell);
		_scroll(scrollContainer, verticalScrollPosition, viewportHeight, cellTopOffset, cellHeight, _scrollTopTo);
	}
	
	function _scroll(scrollContainer, currentScrollPosition, viewportSize, cellLowerOffset, cellSize, scrollFunction) {
		if(cellSize < viewportSize) {
			if(cellLowerOffset > currentScrollPosition) {
				var cellUpperOffset = cellLowerOffset + cellSize;
				var viewportUpperOffset = currentScrollPosition + viewportSize;
				if(cellUpperOffset > viewportUpperOffset) {
					var newScrollPosition = cellUpperOffset - viewportSize;
					scrollFunction.call(this, scrollContainer, newScrollPosition);
				}
			} else {
				scrollFunction.call(this, scrollContainer, cellLowerOffset);
			}
		} else {
			scrollFunction.call(this, scrollContainer, cellLowerOffset);
		}
	}
	
	function _scrollLeftTo(scrollContainer, scrollLeft) {
		BAL.setScrollLeftElement(scrollContainer, scrollLeft);
	}
	
	function _scrollTopTo(scrollContainer, scrollTop) {
		BAL.setScrollTopElement(scrollContainer, scrollTop);
	}
	
	function _isFrozenTable(trElement) {
		return BAL.DOM.containsClass(trElement, "tblFrozen");
	}
	
	function _isFlexPart(trElement) {
		return trElement.id.indexOf("fixed") == -1;
	}
	
	function _getFlexViewport(trElement) {
		var ctrlId = _getCtrlId(trElement);
		var sliceManager = TABLE.tableSliceManagers[ctrlId];
		return TABLE.ScrollPositionProvider.getFlexViewport(sliceManager);
	}
	
	function _getFixViewport(trElement) {
		var ctrlId = _getCtrlId(trElement);
		var sliceManager = TABLE.tableSliceManagers[ctrlId];
		return TABLE.ScrollPositionProvider.getFixViewport(sliceManager);
	}
	
	function _getCtrlId(trElement) {
		return trElement.id.split(".")[0];
	}
};

/**
 * Focuses a element within the next complex widget. If the DOM's end is reached, the search will continue
 * at the very first child below the specified root elements. If there is no other focusable element,
 * the current element will remain focus.
 * 
 * @param currentElement - the element, which is currently focused, must not be <code>null</code>
 */
KEYBOARD_NAVIGATION.TabManager.prototype.switchToNextComplexWidget = function(currentElement) {
	this._switchToFollowingInOrder(currentElement, /*searchForward*/ true);
};
		
	
/**
 * Focuses a element within the previous complex widget. If the DOM's begin is reached, the search will continue
 * at the very last child below the specified root elements. If there is no other focusable element,
 * the current element will remain focus.
 * 
 * @param currentElement - the element, which is currently focused, must not be <code>null</code>
 */
KEYBOARD_NAVIGATION.TabManager.prototype.switchToPreviousComplexWidget = function(currentElement) {
	this._switchToFollowingInOrder(currentElement, /*searchForward*/ false);
};

/**
 * Focuses the next focusable element in DOM. If the DOM's end is reached, the search will continue
 * at the very first child below the specified root elements. If there is no other focusable element,
 * the current element will remain focus.
 * 
 * @param currentElement - the element, which is currently focused, must not be <code>null</code>
 */
KEYBOARD_NAVIGATION.TabManager.prototype.switchToNextSimpleWidget = function(currentElement) {
	if(KEYBOARD_NAVIGATION.doLog) {
		// Create initial indention
		var parentElement = currentElement;
		var initialIndention = 0;
		do {
			parentElement = KEYBOARD_NAVIGATION.getParentElement(parentElement);
			initialIndention++;
		} while(parentElement != null);
		KEYBOARD_NAVIGATION.indentCounter = initialIndention + 1;
		
		KEYBOARD_NAVIGATION.log("=== Lookup for simple widget ===\n");
	}
	
	var tabbableWidget = this._nextTabbableElement(currentElement, true, null, null);
	if(tabbableWidget != null) {
		this._focusFollowing(tabbableWidget, true, null, null);
	}
	
	if(KEYBOARD_NAVIGATION.doLog) {
		KEYBOARD_NAVIGATION.submitLog();
		KEYBOARD_NAVIGATION.clearLog();
	}
};

/**
 * Focuses the previous focusable element in DOM. If the DOM's begin is reached, the search will continue
 * at the very last child below the specified root elements. If there is no other focusable element,
 * the current element will remain focus.
 * 
 * @param currentElement - the element, which is currently focused, must not be <code>null</code>
 */
KEYBOARD_NAVIGATION.TabManager.prototype.switchToPreviousSimpleWidget = function(currentElement) {
	if(KEYBOARD_NAVIGATION.doLog) {
		// Create initial indention
		var parentElement = currentElement;
		var initialIndention = 0;
		do {
			parentElement = KEYBOARD_NAVIGATION.getParentElement(parentElement);
			initialIndention++;
		} while(parentElement != null);
		KEYBOARD_NAVIGATION.indentCounter = initialIndention + 1;
		
		KEYBOARD_NAVIGATION.log("=== Lookup for simple widget ===\n");
	}
	
	var tabbableWidget = this._nextTabbableElement(currentElement, false, null, null);
	if(tabbableWidget != null) {
		this._focusFollowing(tabbableWidget, false, null, null);
	}
	
	if(KEYBOARD_NAVIGATION.doLog) {
		KEYBOARD_NAVIGATION.submitLog();
		KEYBOARD_NAVIGATION.clearLog();
	}
};

/**
 * @returns table cell element, wherein the focused element resides, or <code>null</code> if
 * there is no table cell in the parent hierarchy.
 * 
 * @param currentElement - the element, which is currently focused, must not be <code>null</code>
 */
KEYBOARD_NAVIGATION.TabManager.prototype.getTableCellParent = function(focusElement) {
	return this._lookupForTableCellParent(focusElement);
};

/**
 * @param tableCell - to which shall be scrolled to, must not be <code>null</code>
 * 
 * @see KEYBOARD_NAVIGATION.TabManager.prototype.getTableCellParent(focusElement)
 */
KEYBOARD_NAVIGATION.TabManager.prototype.scrollToTableCell = function(tableCell) {
	if(BAL.DOM.getTagName(tableCell) === "td") {
		this._scrollToTD(tableCell);
	} else {
		this._scrollToTH(tableCell);
	}
};

/**
 * Singleton instance of tab manager
 */
KEYBOARD_NAVIGATION.TABMANAGER_INSTANCE = new KEYBOARD_NAVIGATION.TabManager(
								KEYBOARD_NAVIGATION.complexWidgetFilter,
								KEYBOARD_NAVIGATION.tabCycleRootFilter,
								BAL.DOM.simpleFocusableElementFilter);

KEYBOARD_NAVIGATION.TextFieldFocuser = {
		
		focusFirstIn: function(currentDocument) {
			var relevantForms = KEYBOARD_NAVIGATION.TextFieldFocuser.getRelevantForms(currentDocument);
			KEYBOARD_NAVIGATION.TextFieldFocuser.focusFirstFocusableInForms(relevantForms);
		},
		
		getRelevantForms: function(currentDocument) {
			var relevantForms;
			
			var relevantSubDocuments = KEYBOARD_NAVIGATION.TextFieldFocuser.getRelevantSubDocuments(currentDocument);
			if(relevantSubDocuments.length > 0) {
				relevantForms = KEYBOARD_NAVIGATION.TextFieldFocuser.getFormsFromAllDocuments(relevantSubDocuments);
			} else {
				relevantForms = KEYBOARD_NAVIGATION.TextFieldFocuser.getRelevantFormsFromDocument(currentDocument);
			}
			
			return relevantForms;
		},

		getRelevantSubDocuments: function(currentDocument) {
			var allFrames = currentDocument.frames != null ? currentDocument.frames : new Array();
			var relevantSubDocuments = new Array();
			for(var i = allFrames.length - 1; i >= 0; i--) {
				var frameWindow = allFrames[i];
				if(KEYBOARD_NAVIGATION.isBelowVisibleTabroot(frameWindow.document)) {
					relevantSubDocuments.unshift(frameWindow.document);
				}
			}
			
			return relevantSubDocuments;
		},
		
		getFormsFromAllDocuments: function(documents) {
			var forms = new Array();
			for(var i = 0; i < documents.length; i++) {
				var documentForms = documents[i].forms;
				for(var j = 0; j < documentForms.length; j++) {
					forms.push(documentForms[j]);
				}
			}
			
			return forms;
		},
		
		getRelevantFormsFromDocument: function(currentDocument) {
			var forms = new Array();
			var documentForms = currentDocument.forms;
			for(var i = 0; i < documentForms.length; i++) {
				var form = documentForms[i];
				if(KEYBOARD_NAVIGATION.isBelowVisibleTabroot(form)) {
					forms.push(form);
				}
			}
			
			return forms;
		},
		
		focusFirstFocusableInForms: function(forms) {
			for(var i = 0; i < forms.length; i++) {
				var elements = forms[i].elements;
				for(var j = 0; j < elements.length; j++) {
					var successfulSelection = selectElement(elements[j], false);
					if(successfulSelection) {
						return;
					}
				}
			}
		}
};;
// Compiled from 'script/inspect.js'
/**
 * Methods useful for inspecting the GUI.
 * Above all: openGuiInspectorAfterTargetClicked()
 * 
 * Author:  <a href=mailto:jst@top-logic.com>Jan Stolzenburg</a>
 */

INSPECT = {
	
	HIDE_ERRORS: !true,
	
	controlIdFormat: /^c[0-9]+$/,
	
	/* 
	 * In some tables (for example table trees like the budget tree in Top-S) the rows have a normal id.
	 * For example 'c12' instead of 'c5.12'.
	 * The "fixed" at the end of the id appears in the fixed columns of "frozen tables".
	 */
	tableRowControlIdFormat: /^c([0-9]+)\.([0-9]+)(fixed)?$/,
	
	isInspectable: function(element) {
		return INSPECT.isWithinControl(element) || INSPECT.isTableCellWithSingleControl(element) || INSPECT.isTableCellContent(element);
	},
	
	/**
	 * Returns the element at given location, recursing down Frames and IFrames.
	 */
	elementFromPointRecursive: function(x, y) {
		
		var contentDocument = function(element) {
			return element.contentWindow.document;
		};
		
		var scrollLeft = function(element) {
			return contentDocument(element).documentElement.scrollLeft;
		};
		
		var scrollTop = function(element) {
			return contentDocument(element).documentElement.scrollTop;
		};
		
		var hasChildren = function(element) {
			return element && element.contentWindow && element.contentWindow.document;
		};
		
		var searchRecursivelyWithinElement = function(result, x, y) {
			var element = result[result.length - 1];
			
			var elementPosition = BAL.getAbsoluteElementPosition(element);
			var xPositionWithinVisiblePartOfElement = x - elementPosition.x;
			var yPositionWithinVisiblePartOfElement = y - elementPosition.y;
			var child = contentDocument(element).elementFromPoint(xPositionWithinVisiblePartOfElement, yPositionWithinVisiblePartOfElement);
			result.push(child);
			
			if (hasChildren(child)) {
				var xPositionWithinWholeElement = xPositionWithinVisiblePartOfElement + scrollLeft(element);
				var yPositionWithinWholeElement = yPositionWithinVisiblePartOfElement + scrollTop(element);
				searchRecursivelyWithinElement(result, xPositionWithinWholeElement, yPositionWithinWholeElement);
			}
		};
		
		var topLevelElement = services.ajax.topWindow.document.elementFromPoint(x, y);
		var result = [topLevelElement];
		if (hasChildren(topLevelElement)) {
			searchRecursivelyWithinElement(result, x, y);
		}
		return result;
	},
	
	isControl: function(element) {
		var hasControlMarker = function(element) {
			return INSPECT.hasCssClass(element, "is-control");
		};
		
		return element && hasControlMarker(element);
	},
	
	hasCssClass: function(element, cssClass) {
		return element.className && containsString(cssClass, element.className.split(" "));
	},
	
	isDivOrSpanTag: function(element) {
		var tagName = BAL.DOM.getTagName(element);
		return tagName == "span" || tagName == "div";
	},
	
	/**
	 * Returns the nearest parent that has a valid control id.
	 * Even if it is not marked as a control.
	 */
	findControlIdEvenIfHidden: function(element) {
		var controlIdHolder = INSPECT.firstAncestor(element, 
			function(object) { return INSPECT.isControl(object) && INSPECT.controlIdFormat.test(object.id); }
		);
		return controlIdHolder ? controlIdHolder.id : null;
	},
	
	/**
	 * Returns the nearest parent that is marked as a control and has a valid control id.
	 */
	findControlId: function(element) {
		var controlIdHolder = INSPECT.firstAncestor(element, 
			function(object) { return INSPECT.isControl(object); }
		);

		if ( ! controlIdHolder) {
			return null;
		}
		if ( ! INSPECT.controlIdFormat.test(controlIdHolder.id)) {
			return null;
		}
		return controlIdHolder.id;
	},
	
	isWithinControl: function(element) {
		return INSPECT.findControlId(element);
	},
	
	getWaitPane: function() {
		return services.ajax.topWindow.document.getElementById('waitPane');
	},
	
	showInspectWaitPane: function() {
		var waitpane = INSPECT.getWaitPane();
		services.ajax.showWaitPane();
		waitpane.originalCursorStyle = waitpane.style.cursor;
		waitpane.style.cursor = "help";
	},
	
	hideInspectWaitPane: function() {
		var waitpane = INSPECT.getWaitPane();
		services.ajax.hideWaitPane();
		waitpane.style.cursor = waitpane.originalCursorStyle;
		
		// Do not use "delete waitpane.originalCursorStyle", because IE7
		// throws an "Not supported" exception on that.
		waitpane.originalCursorStyle = null;
	},
	
	waitPaneUntilCalled: function(wrappee, thisOfWrappee) {
		INSPECT.showInspectWaitPane();
		return function() {
			INSPECT.hideInspectWaitPane();
			wrappee.apply(thisOfWrappee, arguments);
		};
	},
	
	openGuiInspectorForClickedControl: function(mouseEvent) {
		var path = INSPECT.elementFromPointRecursive(mouseEvent.clientX, mouseEvent.clientY);
		for (var n = path.length - 1; n >= 0; n--) {
			var element = path[n];
			if (INSPECT.isInspectable(element)) {
				INSPECT.openGuiInspector(element);
				return;
			}
		}
	},
	
	openGuiInspector: function(element) {
		var addCoreInformation = function(commandArguments) {
			commandArguments.controlCommand = "openGuiInspector";
			commandArguments.controlID = INSPECT.findControlIdEvenIfHidden(element);
		};
		
		var addTableCellInformation = function(commandArguments) {
			commandArguments.isTableCell = true;
			commandArguments.rowIndex = INSPECT.getIndexOfTableRow(element);
			commandArguments.columnIndex = INSPECT.getIndexOfTableColumn(element);
			commandArguments.isFixedColumn = INSPECT.isWithinFixedColumn(element);
			if (INSPECT.isTreeTableNodeContent(element)){
				commandArguments.isTreeNode = true;
			}
		};
		
		var addTreeNodeInformation = function(commandArguments) {
			commandArguments.controlID = INSPECT.findControlId(element);
			commandArguments.isTreeNode = true;
			commandArguments.treeNodeId = INSPECT.findSurroundingTreeNode(element).id
		};
		
		var createCommandArguments = function() {
			try {
				var commandArguments = {};
				addCoreInformation(commandArguments);
				if (INSPECT.isTableCellWithSingleControl(element)) {
					commandArguments.controlID = INSPECT.getSingleControlOfTableCell(element).id;
				} else if (INSPECT.isTableCellContent(element)) {
					addTableCellInformation(commandArguments);
				} else if (INSPECT.isTreeNodeContent(element)) {
					addTreeNodeInformation(commandArguments);
				}
				return commandArguments;
			} catch (error) {
				if (INSPECT.HIDE_ERRORS) {
					return null;
				} else {
					throw error;
				}
			}
		};
		
		var windowOfElement = BAL.getCurrentWindow(element);
		var commandArguments = createCommandArguments();
		if (commandArguments) {
			windowOfElement.services.ajax.execute("dispatchControlCommand", commandArguments);
		}
	},
	
	findSurroundingTableCell: function(element) {
		var anchestor = element;
		while (anchestor != null) {
			var parentTd = INSPECT.findParentTag(anchestor, 'td');
			if (parentTd == null) {
				return null;
			}
			if (INSPECT.hasCssClass(parentTd, "tblTD")) {
				return parentTd;
			}
			if (INSPECT.hasCssClass(parentTd, "tblTDselect")) {
				return parentTd;
			}
			anchestor = parentTd.parentNode;
		}
		return null;
	},
	
	findSurroundingTreeNode: function(element) {
		return INSPECT.firstAncestor(element, 
			function(object) { return BAL.DOM.containsClass(object, 'treeNode'); }
		);
	},
	
	findSurroundingTreeTableNode: function(element) {
		return INSPECT.firstAncestor(element, 
			function(object) {
				if (!BAL.DOM.hasAttributes(object)){
					return false;
				}
				return TL.getTLAttribute(object, 'treenode') == "true"; 
			}
		);
	},
	
	/**
	 * Is the clicked element within a table cell that contains exactly one descendant which is a control?
	 * (Ignoring if the control contains further controls or not.)
	 * 
	 * Intention: Detect if the user clicked on a table cell that contains a single control, but missed that control.
	 */
	isTableCellWithSingleControl: function(element) {
		if ( ! INSPECT.isTableCellContent(element)) {
			return false;
		}
		return INSPECT.getSingleControlOfTableCell(INSPECT.findSurroundingTableCell(element)) != null;
	},
	
	getSingleControlOfTableCell: function(element) {
		var getRelevantOnlyChild = function(node) {
			var relevantChildren = filter(node.childNodes, INSPECT.isDivOrSpanTag);
			if (relevantChildren.length == 1) {
				return relevantChildren[0];
			}
			return null;
		};
		
		var relevantOnlyChildDescendants = createList(element, getRelevantOnlyChild);
		return findFirst(relevantOnlyChildDescendants, INSPECT.isControl);
	},
	
	isTableCellContent: function(element) {
		var surroundingTableCell = INSPECT.findSurroundingTableCell(element);
		if (! surroundingTableCell) {
			return false;
		}
		return INSPECT.findControlIdEvenIfHidden(element) == INSPECT.findControlIdEvenIfHidden(surroundingTableCell);
	},
	
	isTreeNodeContent: function(element) {
		var surroundingTreeNode = INSPECT.findSurroundingTreeNode(element);
		if (!surroundingTreeNode) {
			return false;
		}
		return INSPECT.findControlIdEvenIfHidden(element) == INSPECT.findControlIdEvenIfHidden(surroundingTreeNode);
	},
	
	isTreeTableNodeContent: function(element) {
		var surroundingTreeNode = INSPECT.findSurroundingTreeTableNode(element);
		if (!surroundingTreeNode) {
			return false;
		}
		return INSPECT.findControlIdEvenIfHidden(element) == INSPECT.findControlIdEvenIfHidden(surroundingTreeNode);
	},
	
	/**
	 * Waits for the user to click an ui element and tries to open the gui inspector for it.
	 */
	openGuiInspectorAfterTargetClicked: function() {
		BAL.onceEventListener(
			INSPECT.getWaitPane(),
			'mousedown',
			INSPECT.waitPaneUntilCalled(INSPECT.openGuiInspectorForClickedControl, this),
			this,
			true
		);
	},
	
	getIndexOfTableRow: function(element) {
		var extractTableRowIndex = function(controlId) {
			var tableRowControlIdMatcher = INSPECT.tableRowControlIdFormat.exec(controlId);
			var tableRowIndexAsString = tableRowControlIdMatcher[2];
			return parseInt(tableRowIndexAsString, 10);
		};
		
		var controlId = INSPECT.findControlIdOfSurroundingRow(element);
		if (!controlId) {
			throw new Error("The table row containing the inspected element has no or no valid control id!");
		}
		return extractTableRowIndex(controlId);
	},
	
	getIndexOfTableColumn: function(element) {
		return INSPECT.findParentTag(element, 'td').cellIndex;
	},
	
	/**
	 * Is that element within fixed column of a "frozen table"?
	 */
	isWithinFixedColumn: function(element) {
		var controlIdOfSurroundingRow = INSPECT.findControlIdOfSurroundingRow(element);
		return controlIdOfSurroundingRow.indexOf('fixed') != -1;
	},
	
	findControlIdOfSurroundingRow: function(element) {
		var searchTableRowId = function() {
			var surroundingTrTag = INSPECT.findParentTag(element, 'tr');
			return surroundingTrTag.id;
		};
		
		var isValidTableRowControlId = function(id) {
			var hasTableRowControlIdFormat = function() {
				return INSPECT.tableRowControlIdFormat.test(id);
			};
			
			return id && hasTableRowControlIdFormat(id);
		};
		var tableRowId = searchTableRowId();
		return isValidTableRowControlId(tableRowId) ? tableRowId : null;
	},
	
	findParentTag: function(element, tagName) {
		return INSPECT.firstAncestor(element,
			function(object) { return BAL.DOM.getTagName(object) == tagName; }
		);
	},
	
	firstAncestor: function(element, test) {
		var anchestor = element;
		while (anchestor != null) {
			if (test(anchestor)) {
				return anchestor;
			}
			var parent = anchestor.parentNode;
			if (parent == null) {
				anchestor = null;
			} else {
				anchestor = parent;
			}
		}
		return null;
	}
	
};;
// Compiled from 'script/fotorama.js'
/*!
 * Fotorama 4.6.4 | http://fotorama.io/license/
 */
fotoramaVersion="4.6.4",function(a,b,c,d,e){"use strict";function f(a){var b="bez_"+d.makeArray(arguments).join("_").replace(".","p");if("function"!=typeof d.easing[b]){var c=function(a,b){var c=[null,null],d=[null,null],e=[null,null],f=function(f,g){return e[g]=3*a[g],d[g]=3*(b[g]-a[g])-e[g],c[g]=1-e[g]-d[g],f*(e[g]+f*(d[g]+f*c[g]))},g=function(a){return e[0]+a*(2*d[0]+3*c[0]*a)},h=function(a){for(var b,c=a,d=0;++d<14&&(b=f(c,0)-a,!(Math.abs(b)<.001));)c-=b/g(c);return c};return function(a){return f(h(a),1)}};d.easing[b]=function(b,d,e,f,g){return f*c([a[0],a[1]],[a[2],a[3]])(d/g)+e}}return b}function g(){}function h(a,b,c){return Math.max(isNaN(b)?-1/0:b,Math.min(isNaN(c)?1/0:c,a))}function i(a){return a.match(/ma/)&&a.match(/-?\d+(?!d)/g)[a.match(/3d/)?12:4]}function j(a){return Ic?+i(a.css("transform")):+a.css("left").replace("px","")}function k(a){var b={};return Ic?b.transform="translate3d("+a+"px,0,0)":b.left=a,b}function l(a){return{"transition-duration":a+"ms"}}function m(a,b){return isNaN(a)?b:a}function n(a,b){return m(+String(a).replace(b||"px",""))}function o(a){return/%$/.test(a)?n(a,"%"):e}function p(a,b){return m(o(a)/100*b,n(a))}function q(a){return(!isNaN(n(a))||!isNaN(n(a,"%")))&&a}function r(a,b,c,d){return(a-(d||0))*(b+(c||0))}function s(a,b,c,d){return-Math.round(a/(b+(c||0))-(d||0))}function t(a){var b=a.data();if(!b.tEnd){var c=a[0],d={WebkitTransition:"webkitTransitionEnd",MozTransition:"transitionend",OTransition:"oTransitionEnd otransitionend",msTransition:"MSTransitionEnd",transition:"transitionend"};T(c,d[uc.prefixed("transition")],function(a){b.tProp&&a.propertyName.match(b.tProp)&&b.onEndFn()}),b.tEnd=!0}}function u(a,b,c,d){var e,f=a.data();f&&(f.onEndFn=function(){e||(e=!0,clearTimeout(f.tT),c())},f.tProp=b,clearTimeout(f.tT),f.tT=setTimeout(function(){f.onEndFn()},1.5*d),t(a))}function v(a,b){if(a.length){var c=a.data();Ic?(a.css(l(0)),c.onEndFn=g,clearTimeout(c.tT)):a.stop();var d=w(b,function(){return j(a)});return a.css(k(d)),d}}function w(){for(var a,b=0,c=arguments.length;c>b&&(a=b?arguments[b]():arguments[b],"number"!=typeof a);b++);return a}function x(a,b){return Math.round(a+(b-a)/1.5)}function y(){return y.p=y.p||("https:"===c.protocol?"https://":"http://"),y.p}function z(a){var c=b.createElement("a");return c.href=a,c}function A(a,b){if("string"!=typeof a)return a;a=z(a);var c,d;if(a.host.match(/youtube\.com/)&&a.search){if(c=a.search.split("v=")[1]){var e=c.indexOf("&");-1!==e&&(c=c.substring(0,e)),d="youtube"}}else a.host.match(/youtube\.com|youtu\.be/)?(c=a.pathname.replace(/^\/(embed\/|v\/)?/,"").replace(/\/.*/,""),d="youtube"):a.host.match(/vimeo\.com/)&&(d="vimeo",c=a.pathname.replace(/^\/(video\/)?/,"").replace(/\/.*/,""));return c&&d||!b||(c=a.href,d="custom"),c?{id:c,type:d,s:a.search.replace(/^\?/,""),p:y()}:!1}function B(a,b,c){var e,f,g=a.video;return"youtube"===g.type?(f=y()+"img.youtube.com/vi/"+g.id+"/default.jpg",e=f.replace(/\/default.jpg$/,"/hqdefault.jpg"),a.thumbsReady=!0):"vimeo"===g.type?d.ajax({url:y()+"vimeo.com/api/v2/video/"+g.id+".json",dataType:"jsonp",success:function(d){a.thumbsReady=!0,C(b,{img:d[0].thumbnail_large,thumb:d[0].thumbnail_small},a.i,c)}}):a.thumbsReady=!0,{img:e,thumb:f}}function C(a,b,c,e){for(var f=0,g=a.length;g>f;f++){var h=a[f];if(h.i===c&&h.thumbsReady){var i={videoReady:!0};i[Xc]=i[Zc]=i[Yc]=!1,e.splice(f,1,d.extend({},h,i,b));break}}}function D(a){function b(a,b,e){var f=a.children("img").eq(0),g=a.attr("href"),h=a.attr("src"),i=f.attr("src"),j=b.video,k=e?A(g,j===!0):!1;k?g=!1:k=j,c(a,f,d.extend(b,{video:k,img:b.img||g||h||i,thumb:b.thumb||i||h||g}))}function c(a,b,c){var e=c.thumb&&c.img!==c.thumb,f=n(c.width||a.attr("width")),g=n(c.height||a.attr("height"));d.extend(c,{width:f,height:g,thumbratio:S(c.thumbratio||n(c.thumbwidth||b&&b.attr("width")||e||f)/n(c.thumbheight||b&&b.attr("height")||e||g))})}var e=[];return a.children().each(function(){var a=d(this),f=R(d.extend(a.data(),{id:a.attr("id")}));if(a.is("a, img"))b(a,f,!0);else{if(a.is(":empty"))return;c(a,null,d.extend(f,{html:this,_html:a.html()}))}e.push(f)}),e}function E(a){return 0===a.offsetWidth&&0===a.offsetHeight}function F(a){return!d.contains(b.documentElement,a)}function G(a,b,c,d){return G.i||(G.i=1,G.ii=[!0]),d=d||G.i,"undefined"==typeof G.ii[d]&&(G.ii[d]=!0),a()?b():G.ii[d]&&setTimeout(function(){G.ii[d]&&G(a,b,c,d)},c||100),G.i++}function H(a){c.replace(c.protocol+"//"+c.host+c.pathname.replace(/^\/?/,"/")+c.search+"#"+a)}function I(a,b,c,d){var e=a.data(),f=e.measures;if(f&&(!e.l||e.l.W!==f.width||e.l.H!==f.height||e.l.r!==f.ratio||e.l.w!==b.w||e.l.h!==b.h||e.l.m!==c||e.l.p!==d)){var g=f.width,i=f.height,j=b.w/b.h,k=f.ratio>=j,l="scaledown"===c,m="contain"===c,n="cover"===c,o=$(d);k&&(l||m)||!k&&n?(g=h(b.w,0,l?g:1/0),i=g/f.ratio):(k&&n||!k&&(l||m))&&(i=h(b.h,0,l?i:1/0),g=i*f.ratio),a.css({width:g,height:i,left:p(o.x,b.w-g),top:p(o.y,b.h-i)}),e.l={W:f.width,H:f.height,r:f.ratio,w:b.w,h:b.h,m:c,p:d}}return!0}function J(a,b){var c=a[0];c.styleSheet?c.styleSheet.cssText=b:a.html(b)}function K(a,b,c){return b===c?!1:b>=a?"left":a>=c?"right":"left right"}function L(a,b,c,d){if(!c)return!1;if(!isNaN(a))return a-(d?0:1);for(var e,f=0,g=b.length;g>f;f++){var h=b[f];if(h.id===a){e=f;break}}return e}function M(a,b,c){c=c||{},a.each(function(){var a,e=d(this),f=e.data();f.clickOn||(f.clickOn=!0,d.extend(cb(e,{onStart:function(b){a=b,(c.onStart||g).call(this,b)},onMove:c.onMove||g,onTouchEnd:c.onTouchEnd||g,onEnd:function(c){c.moved||b.call(this,a)}}),{noMove:!0}))})}function N(a,b){return'<div class="'+a+'">'+(b||"")+"</div>"}function O(a){for(var b=a.length;b;){var c=Math.floor(Math.random()*b--),d=a[b];a[b]=a[c],a[c]=d}return a}function P(a){return"[object Array]"==Object.prototype.toString.call(a)&&d.map(a,function(a){return d.extend({},a)})}function Q(a,b,c){a.scrollLeft(b||0).scrollTop(c||0)}function R(a){if(a){var b={};return d.each(a,function(a,c){b[a.toLowerCase()]=c}),b}}function S(a){if(a){var b=+a;return isNaN(b)?(b=a.split("/"),+b[0]/+b[1]||e):b}}function T(a,b,c,d){b&&(a.addEventListener?a.addEventListener(b,c,!!d):a.attachEvent("on"+b,c))}function U(a){return!!a.getAttribute("disabled")}function V(a){return{tabindex:-1*a+"",disabled:a}}function W(a,b){T(a,"keyup",function(c){U(a)||13==c.keyCode&&b.call(a,c)})}function X(a,b){T(a,"focus",a.onfocusin=function(c){b.call(a,c)},!0)}function Y(a,b){a.preventDefault?a.preventDefault():a.returnValue=!1,b&&a.stopPropagation&&a.stopPropagation()}function Z(a){return a?">":"<"}function $(a){return a=(a+"").split(/\s+/),{x:q(a[0])||bd,y:q(a[1])||bd}}function _(a,b){var c=a.data(),e=Math.round(b.pos),f=function(){c.sliding=!1,(b.onEnd||g)()};"undefined"!=typeof b.overPos&&b.overPos!==b.pos&&(e=b.overPos,f=function(){_(a,d.extend({},b,{overPos:b.pos,time:Math.max(Qc,b.time/2)}))});var h=d.extend(k(e),b.width&&{width:b.width});c.sliding=!0,Ic?(a.css(d.extend(l(b.time),h)),b.time>10?u(a,"transform",f,b.time):f()):a.stop().animate(h,b.time,_c,f)}function ab(a,b,c,e,f,h){var i="undefined"!=typeof h;if(i||(f.push(arguments),Array.prototype.push.call(arguments,f.length),!(f.length>1))){a=a||d(a),b=b||d(b);var j=a[0],k=b[0],l="crossfade"===e.method,m=function(){if(!m.done){m.done=!0;var a=(i||f.shift())&&f.shift();a&&ab.apply(this,a),(e.onEnd||g)(!!a)}},n=e.time/(h||1);c.removeClass(Rb+" "+Qb),a.stop().addClass(Rb),b.stop().addClass(Qb),l&&k&&a.fadeTo(0,0),a.fadeTo(l?n:0,1,l&&m),b.fadeTo(n,0,m),j&&l||k||m()}}function bb(a){var b=(a.touches||[])[0]||a;a._x=b.pageX,a._y=b.clientY,a._now=d.now()}function cb(a,c){function e(a){return m=d(a.target),u.checked=p=q=s=!1,k||u.flow||a.touches&&a.touches.length>1||a.which>1||ed&&ed.type!==a.type&&gd||(p=c.select&&m.is(c.select,t))?p:(o="touchstart"===a.type,q=m.is("a, a *",t),n=u.control,r=u.noMove||u.noSwipe||n?16:u.snap?0:4,bb(a),l=ed=a,fd=a.type.replace(/down|start/,"move").replace(/Down/,"Move"),(c.onStart||g).call(t,a,{control:n,$target:m}),k=u.flow=!0,void((!o||u.go)&&Y(a)))}function f(a){if(a.touches&&a.touches.length>1||Nc&&!a.isPrimary||fd!==a.type||!k)return k&&h(),void(c.onTouchEnd||g)();bb(a);var b=Math.abs(a._x-l._x),d=Math.abs(a._y-l._y),e=b-d,f=(u.go||u.x||e>=0)&&!u.noSwipe,i=0>e;o&&!u.checked?(k=f)&&Y(a):(Y(a),(c.onMove||g).call(t,a,{touch:o})),!s&&Math.sqrt(Math.pow(b,2)+Math.pow(d,2))>r&&(s=!0),u.checked=u.checked||f||i}function h(a){(c.onTouchEnd||g)();var b=k;u.control=k=!1,b&&(u.flow=!1),!b||q&&!u.checked||(a&&Y(a),gd=!0,clearTimeout(hd),hd=setTimeout(function(){gd=!1},1e3),(c.onEnd||g).call(t,{moved:s,$target:m,control:n,touch:o,startEvent:l,aborted:!a||"MSPointerCancel"===a.type}))}function i(){u.flow||setTimeout(function(){u.flow=!0},10)}function j(){u.flow&&setTimeout(function(){u.flow=!1},Pc)}var k,l,m,n,o,p,q,r,s,t=a[0],u={};return Nc?(T(t,"MSPointerDown",e),T(b,"MSPointerMove",f),T(b,"MSPointerCancel",h),T(b,"MSPointerUp",h)):(T(t,"touchstart",e),T(t,"touchmove",f),T(t,"touchend",h),T(b,"touchstart",i),T(b,"touchend",j),T(b,"touchcancel",j),Ec.on("scroll",j),a.on("mousedown",e),Fc.on("mousemove",f).on("mouseup",h)),a.on("click","a",function(a){u.checked&&Y(a)}),u}function db(a,b){function c(c,d){A=!0,j=l=c._x,q=c._now,p=[[q,j]],m=n=D.noMove||d?0:v(a,(b.getPos||g)()),(b.onStart||g).call(B,c)}function e(a,b){s=D.min,t=D.max,u=D.snap,w=a.altKey,A=z=!1,y=b.control,y||C.sliding||c(a)}function f(d,e){D.noSwipe||(A||c(d),l=d._x,p.push([d._now,l]),n=m-(j-l),o=K(n,s,t),s>=n?n=x(n,s):n>=t&&(n=x(n,t)),D.noMove||(a.css(k(n)),z||(z=!0,e.touch||Nc||a.addClass(ec)),(b.onMove||g).call(B,d,{pos:n,edge:o})))}function i(e){if(!D.noSwipe||!e.moved){A||c(e.startEvent,!0),e.touch||Nc||a.removeClass(ec),r=d.now();for(var f,i,j,k,o,q,v,x,y,z=r-Pc,C=null,E=Qc,F=b.friction,G=p.length-1;G>=0;G--){if(f=p[G][0],i=Math.abs(f-z),null===C||j>i)C=f,k=p[G][1];else if(C===z||i>j)break;j=i}v=h(n,s,t);var H=k-l,I=H>=0,J=r-C,K=J>Pc,L=!K&&n!==m&&v===n;u&&(v=h(Math[L?I?"floor":"ceil":"round"](n/u)*u,s,t),s=t=v),L&&(u||v===n)&&(y=-(H/J),E*=h(Math.abs(y),b.timeLow,b.timeHigh),o=Math.round(n+y*E/F),u||(v=o),(!I&&o>t||I&&s>o)&&(q=I?s:t,x=o-q,u||(v=q),x=h(v+.03*x,q-50,q+50),E=Math.abs((n-x)/(y/F)))),E*=w?10:1,(b.onEnd||g).call(B,d.extend(e,{moved:e.moved||K&&u,pos:n,newPos:v,overPos:x,time:E}))}}var j,l,m,n,o,p,q,r,s,t,u,w,y,z,A,B=a[0],C=a.data(),D={};return D=d.extend(cb(b.$wrap,d.extend({},b,{onStart:e,onMove:f,onEnd:i})),D)}function eb(a,b){var c,e,f,h=a[0],i={prevent:{}};return T(h,Oc,function(a){var h=a.wheelDeltaY||-1*a.deltaY||0,j=a.wheelDeltaX||-1*a.deltaX||0,k=Math.abs(j)&&!Math.abs(h),l=Z(0>j),m=e===l,n=d.now(),o=Pc>n-f;e=l,f=n,k&&i.ok&&(!i.prevent[l]||c)&&(Y(a,!0),c&&m&&o||(b.shift&&(c=!0,clearTimeout(i.t),i.t=setTimeout(function(){c=!1},Rc)),(b.onEnd||g)(a,b.shift?l:j)))}),i}function fb(){d.each(d.Fotorama.instances,function(a,b){b.index=a})}function gb(a){d.Fotorama.instances.push(a),fb()}function hb(a){d.Fotorama.instances.splice(a.index,1),fb()}var ib="fotorama",jb="fullscreen",kb=ib+"__wrap",lb=kb+"--css2",mb=kb+"--css3",nb=kb+"--video",ob=kb+"--fade",pb=kb+"--slide",qb=kb+"--no-controls",rb=kb+"--no-shadows",sb=kb+"--pan-y",tb=kb+"--rtl",ub=kb+"--only-active",vb=kb+"--no-captions",wb=kb+"--toggle-arrows",xb=ib+"__stage",yb=xb+"__frame",zb=yb+"--video",Ab=xb+"__shaft",Bb=ib+"__grab",Cb=ib+"__pointer",Db=ib+"__arr",Eb=Db+"--disabled",Fb=Db+"--prev",Gb=Db+"--next",Hb=ib+"__nav",Ib=Hb+"-wrap",Jb=Hb+"__shaft",Kb=Hb+"--dots",Lb=Hb+"--thumbs",Mb=Hb+"__frame",Nb=Mb+"--dot",Ob=Mb+"--thumb",Pb=ib+"__fade",Qb=Pb+"-front",Rb=Pb+"-rear",Sb=ib+"__shadow",Tb=Sb+"s",Ub=Tb+"--left",Vb=Tb+"--right",Wb=ib+"__active",Xb=ib+"__select",Yb=ib+"--hidden",Zb=ib+"--fullscreen",$b=ib+"__fullscreen-icon",_b=ib+"__error",ac=ib+"__loading",bc=ib+"__loaded",cc=bc+"--full",dc=bc+"--img",ec=ib+"__grabbing",fc=ib+"__img",gc=fc+"--full",hc=ib+"__dot",ic=ib+"__thumb",jc=ic+"-border",kc=ib+"__html",lc=ib+"__video",mc=lc+"-play",nc=lc+"-close",oc=ib+"__caption",pc=ib+"__caption__wrap",qc=ib+"__spinner",rc='" tabindex="0" role="button',sc=d&&d.fn.jquery.split(".");if(!sc||sc[0]<1||1==sc[0]&&sc[1]<8)throw"Fotorama requires jQuery 1.8 or later and will not run without it.";var tc={},uc=function(a,b,c){function d(a){r.cssText=a}function e(a,b){return typeof a===b}function f(a,b){return!!~(""+a).indexOf(b)}function g(a,b){for(var d in a){var e=a[d];if(!f(e,"-")&&r[e]!==c)return"pfx"==b?e:!0}return!1}function h(a,b,d){for(var f in a){var g=b[a[f]];if(g!==c)return d===!1?a[f]:e(g,"function")?g.bind(d||b):g}return!1}function i(a,b,c){var d=a.charAt(0).toUpperCase()+a.slice(1),f=(a+" "+u.join(d+" ")+d).split(" ");return e(b,"string")||e(b,"undefined")?g(f,b):(f=(a+" "+v.join(d+" ")+d).split(" "),h(f,b,c))}var j,k,l,m="2.6.2",n={},o=b.documentElement,p="modernizr",q=b.createElement(p),r=q.style,s=({}.toString," -webkit- -moz- -o- -ms- ".split(" ")),t="Webkit Moz O ms",u=t.split(" "),v=t.toLowerCase().split(" "),w={},x=[],y=x.slice,z=function(a,c,d,e){var f,g,h,i,j=b.createElement("div"),k=b.body,l=k||b.createElement("body");if(parseInt(d,10))for(;d--;)h=b.createElement("div"),h.id=e?e[d]:p+(d+1),j.appendChild(h);return f=["&#173;",'<style id="s',p,'">',a,"</style>"].join(""),j.id=p,(k?j:l).innerHTML+=f,l.appendChild(j),k||(l.style.background="",l.style.overflow="hidden",i=o.style.overflow,o.style.overflow="hidden",o.appendChild(l)),g=c(j,a),k?j.parentNode.removeChild(j):(l.parentNode.removeChild(l),o.style.overflow=i),!!g},A={}.hasOwnProperty;l=e(A,"undefined")||e(A.call,"undefined")?function(a,b){return b in a&&e(a.constructor.prototype[b],"undefined")}:function(a,b){return A.call(a,b)},Function.prototype.bind||(Function.prototype.bind=function(a){var b=this;if("function"!=typeof b)throw new TypeError;var c=y.call(arguments,1),d=function(){if(this instanceof d){var e=function(){};e.prototype=b.prototype;var f=new e,g=b.apply(f,c.concat(y.call(arguments)));return Object(g)===g?g:f}return b.apply(a,c.concat(y.call(arguments)))};return d}),w.csstransforms3d=function(){var a=!!i("perspective");return a};for(var B in w)l(w,B)&&(k=B.toLowerCase(),n[k]=w[B](),x.push((n[k]?"":"no-")+k));return n.addTest=function(a,b){if("object"==typeof a)for(var d in a)l(a,d)&&n.addTest(d,a[d]);else{if(a=a.toLowerCase(),n[a]!==c)return n;b="function"==typeof b?b():b,"undefined"!=typeof enableClasses&&enableClasses&&(o.className+=" "+(b?"":"no-")+a),n[a]=b}return n},d(""),q=j=null,n._version=m,n._prefixes=s,n._domPrefixes=v,n._cssomPrefixes=u,n.testProp=function(a){return g([a])},n.testAllProps=i,n.testStyles=z,n.prefixed=function(a,b,c){return b?i(a,b,c):i(a,"pfx")},n}(a,b),vc={ok:!1,is:function(){return!1},request:function(){},cancel:function(){},event:"",prefix:""},wc="webkit moz o ms khtml".split(" ");if("undefined"!=typeof b.cancelFullScreen)vc.ok=!0;else for(var xc=0,yc=wc.length;yc>xc;xc++)if(vc.prefix=wc[xc],"undefined"!=typeof b[vc.prefix+"CancelFullScreen"]){vc.ok=!0;break}vc.ok&&(vc.event=vc.prefix+"fullscreenchange",vc.is=function(){switch(this.prefix){case"":return b.fullScreen;case"webkit":return b.webkitIsFullScreen;default:return b[this.prefix+"FullScreen"]}},vc.request=function(a){return""===this.prefix?a.requestFullScreen():a[this.prefix+"RequestFullScreen"]()},vc.cancel=function(){return""===this.prefix?b.cancelFullScreen():b[this.prefix+"CancelFullScreen"]()});var zc,Ac={lines:12,length:5,width:2,radius:7,corners:1,rotate:15,color:"rgba(128, 128, 128, .75)",hwaccel:!0},Bc={top:"auto",left:"auto",className:""};!function(a,b){zc=b()}(this,function(){function a(a,c){var d,e=b.createElement(a||"div");for(d in c)e[d]=c[d];return e}function c(a){for(var b=1,c=arguments.length;c>b;b++)a.appendChild(arguments[b]);return a}function d(a,b,c,d){var e=["opacity",b,~~(100*a),c,d].join("-"),f=.01+c/d*100,g=Math.max(1-(1-a)/b*(100-f),a),h=m.substring(0,m.indexOf("Animation")).toLowerCase(),i=h&&"-"+h+"-"||"";return o[e]||(p.insertRule("@"+i+"keyframes "+e+"{0%{opacity:"+g+"}"+f+"%{opacity:"+a+"}"+(f+.01)+"%{opacity:1}"+(f+b)%100+"%{opacity:"+a+"}100%{opacity:"+g+"}}",p.cssRules.length),o[e]=1),e}function f(a,b){var c,d,f=a.style;for(b=b.charAt(0).toUpperCase()+b.slice(1),d=0;d<n.length;d++)if(c=n[d]+b,f[c]!==e)return c;return f[b]!==e?b:void 0}function g(a,b){for(var c in b)a.style[f(a,c)||c]=b[c];return a}function h(a){for(var b=1;b<arguments.length;b++){var c=arguments[b];for(var d in c)a[d]===e&&(a[d]=c[d])}return a}function i(a){for(var b={x:a.offsetLeft,y:a.offsetTop};a=a.offsetParent;)b.x+=a.offsetLeft,b.y+=a.offsetTop;return b}function j(a,b){return"string"==typeof a?a:a[b%a.length]}function k(a){return"undefined"==typeof this?new k(a):void(this.opts=h(a||{},k.defaults,q))}function l(){function b(b,c){return a("<"+b+' xmlns="urn:schemas-microsoft.com:vml" class="spin-vml">',c)}p.addRule(".spin-vml","behavior:url(#default#VML)"),k.prototype.lines=function(a,d){function e(){return g(b("group",{coordsize:k+" "+k,coordorigin:-i+" "+-i}),{width:k,height:k})}function f(a,f,h){c(m,c(g(e(),{rotation:360/d.lines*a+"deg",left:~~f}),c(g(b("roundrect",{arcsize:d.corners}),{width:i,height:d.width,left:d.radius,top:-d.width>>1,filter:h}),b("fill",{color:j(d.color,a),opacity:d.opacity}),b("stroke",{opacity:0}))))}var h,i=d.length+d.width,k=2*i,l=2*-(d.width+d.length)+"px",m=g(e(),{position:"absolute",top:l,left:l});if(d.shadow)for(h=1;h<=d.lines;h++)f(h,-2,"progid:DXImageTransform.Microsoft.Blur(pixelradius=2,makeshadow=1,shadowopacity=.3)");for(h=1;h<=d.lines;h++)f(h);return c(a,m)},k.prototype.opacity=function(a,b,c,d){var e=a.firstChild;d=d.shadow&&d.lines||0,e&&b+d<e.childNodes.length&&(e=e.childNodes[b+d],e=e&&e.firstChild,e=e&&e.firstChild,e&&(e.opacity=c))}}var m,n=["webkit","Moz","ms","O"],o={},p=function(){var d=a("style",{type:"text/css"});return c(b.getElementsByTagName("head")[0],d),d.sheet||d.styleSheet}(),q={lines:12,length:7,width:5,radius:10,rotate:0,corners:1,color:"#000",direction:1,speed:1,trail:100,opacity:.25,fps:20,zIndex:2e9,className:"spinner",top:"auto",left:"auto",position:"relative"};k.defaults={},h(k.prototype,{spin:function(b){this.stop();var c,d,e=this,f=e.opts,h=e.el=g(a(0,{className:f.className}),{position:f.position,width:0,zIndex:f.zIndex}),j=f.radius+f.length+f.width;if(b&&(b.insertBefore(h,b.firstChild||null),d=i(b),c=i(h),g(h,{left:("auto"==f.left?d.x-c.x+(b.offsetWidth>>1):parseInt(f.left,10)+j)+"px",top:("auto"==f.top?d.y-c.y+(b.offsetHeight>>1):parseInt(f.top,10)+j)+"px"})),h.setAttribute("role","progressbar"),e.lines(h,e.opts),!m){var k,l=0,n=(f.lines-1)*(1-f.direction)/2,o=f.fps,p=o/f.speed,q=(1-f.opacity)/(p*f.trail/100),r=p/f.lines;!function s(){l++;for(var a=0;a<f.lines;a++)k=Math.max(1-(l+(f.lines-a)*r)%p*q,f.opacity),e.opacity(h,a*f.direction+n,k,f);e.timeout=e.el&&setTimeout(s,~~(1e3/o))}()}return e},stop:function(){var a=this.el;return a&&(clearTimeout(this.timeout),a.parentNode&&a.parentNode.removeChild(a),this.el=e),this},lines:function(b,e){function f(b,c){return g(a(),{position:"absolute",width:e.length+e.width+"px",height:e.width+"px",background:b,boxShadow:c,transformOrigin:"left",transform:"rotate("+~~(360/e.lines*i+e.rotate)+"deg) translate("+e.radius+"px,0)",borderRadius:(e.corners*e.width>>1)+"px"})}for(var h,i=0,k=(e.lines-1)*(1-e.direction)/2;i<e.lines;i++)h=g(a(),{position:"absolute",top:1+~(e.width/2)+"px",transform:e.hwaccel?"translate3d(0,0,0)":"",opacity:e.opacity,animation:m&&d(e.opacity,e.trail,k+i*e.direction,e.lines)+" "+1/e.speed+"s linear infinite"}),e.shadow&&c(h,g(f("#000","0 0 4px #000"),{top:"2px"})),c(b,c(h,f(j(e.color,i),"0 0 1px rgba(0,0,0,.1)")));return b},opacity:function(a,b,c){b<a.childNodes.length&&(a.childNodes[b].style.opacity=c)}});var r=g(a("group"),{behavior:"url(#default#VML)"});return!f(r,"transform")&&r.adj?l():m=f(r,"animation"),k});var Cc,Dc,Ec=d(a),Fc=d(b),Gc="quirks"===c.hash.replace("#",""),Hc=uc.csstransforms3d,Ic=Hc&&!Gc,Jc=Hc||"CSS1Compat"===b.compatMode,Kc=vc.ok,Lc=navigator.userAgent.match(/Android|webOS|iPhone|iPad|iPod|BlackBerry|Windows Phone/i),Mc=!Ic||Lc,Nc=navigator.msPointerEnabled,Oc="onwheel"in b.createElement("div")?"wheel":b.onmousewheel!==e?"mousewheel":"DOMMouseScroll",Pc=250,Qc=300,Rc=1400,Sc=5e3,Tc=2,Uc=64,Vc=500,Wc=333,Xc="$stageFrame",Yc="$navDotFrame",Zc="$navThumbFrame",$c="auto",_c=f([.1,0,.25,1]),ad=99999,bd="50%",cd={width:null,minwidth:null,maxwidth:"100%",height:null,minheight:null,maxheight:null,ratio:null,margin:Tc,glimpse:0,fit:"contain",position:bd,thumbposition:bd,nav:"dots",navposition:"bottom",navwidth:null,thumbwidth:Uc,thumbheight:Uc,thumbmargin:Tc,thumbborderwidth:Tc,thumbfit:"cover",allowfullscreen:!1,transition:"slide",clicktransition:null,transitionduration:Qc,captions:!0,hash:!1,startindex:0,loop:!1,autoplay:!1,stopautoplayontouch:!0,keyboard:!1,arrows:!0,click:!0,swipe:!0,trackpad:!1,enableifsingleframe:!1,controlsonstart:!0,shuffle:!1,direction:"ltr",shadows:!0,spinner:null},dd={left:!0,right:!0,down:!1,up:!1,space:!1,home:!1,end:!1};G.stop=function(a){G.ii[a]=!1};var ed,fd,gd,hd;jQuery.Fotorama=function(a,e){function f(){d.each(yd,function(a,b){if(!b.i){b.i=me++;var c=A(b.video,!0);if(c){var d={};b.video=c,b.img||b.thumb?b.thumbsReady=!0:d=B(b,yd,ie),C(yd,{img:d.img,thumb:d.thumb},b.i,ie)}}})}function g(a){return Zd[a]||ie.fullScreen}function i(a){var b="keydown."+ib,c=ib+je,d="keydown."+c,f="resize."+c+" orientationchange."+c;a?(Fc.on(d,function(a){var b,c;Cd&&27===a.keyCode?(b=!0,md(Cd,!0,!0)):(ie.fullScreen||e.keyboard&&!ie.index)&&(27===a.keyCode?(b=!0,ie.cancelFullScreen()):a.shiftKey&&32===a.keyCode&&g("space")||37===a.keyCode&&g("left")||38===a.keyCode&&g("up")?c="<":32===a.keyCode&&g("space")||39===a.keyCode&&g("right")||40===a.keyCode&&g("down")?c=">":36===a.keyCode&&g("home")?c="<<":35===a.keyCode&&g("end")&&(c=">>")),(b||c)&&Y(a),c&&ie.show({index:c,slow:a.altKey,user:!0})}),ie.index||Fc.off(b).on(b,"textarea, input, select",function(a){!Dc.hasClass(jb)&&a.stopPropagation()}),Ec.on(f,ie.resize)):(Fc.off(d),Ec.off(f))}function j(b){b!==j.f&&(b?(a.html("").addClass(ib+" "+ke).append(qe).before(oe).before(pe),gb(ie)):(qe.detach(),oe.detach(),pe.detach(),a.html(ne.urtext).removeClass(ke),hb(ie)),i(b),j.f=b)}function m(){yd=ie.data=yd||P(e.data)||D(a),zd=ie.size=yd.length,!xd.ok&&e.shuffle&&O(yd),f(),Je=y(Je),zd&&j(!0)}function o(){var a=2>zd&&!e.enableifsingleframe||Cd;Me.noMove=a||Sd,Me.noSwipe=a||!e.swipe,!Wd&&se.toggleClass(Bb,!e.click&&!Me.noMove&&!Me.noSwipe),Nc&&qe.toggleClass(sb,!Me.noSwipe)}function t(a){a===!0&&(a=""),e.autoplay=Math.max(+a||Sc,1.5*Vd)}function u(){function a(a,c){b[a?"add":"remove"].push(c)}ie.options=e=R(e),Sd="crossfade"===e.transition||"dissolve"===e.transition,Md=e.loop&&(zd>2||Sd&&(!Wd||"slide"!==Wd)),Vd=+e.transitionduration||Qc,Yd="rtl"===e.direction,Zd=d.extend({},e.keyboard&&dd,e.keyboard);var b={add:[],remove:[]};zd>1||e.enableifsingleframe?(Nd=e.nav,Pd="top"===e.navposition,b.remove.push(Xb),we.toggle(!!e.arrows)):(Nd=!1,we.hide()),Rb(),Bd=new zc(d.extend(Ac,e.spinner,Bc,{direction:Yd?-1:1})),Gc(),Hc(),e.autoplay&&t(e.autoplay),Td=n(e.thumbwidth)||Uc,Ud=n(e.thumbheight)||Uc,Ne.ok=Pe.ok=e.trackpad&&!Mc,o(),ed(e,[Le]),Od="thumbs"===Nd,Od?(lc(zd,"navThumb"),Ad=Be,he=Zc,J(oe,d.Fotorama.jst.style({w:Td,h:Ud,b:e.thumbborderwidth,m:e.thumbmargin,s:je,q:!Jc})),ye.addClass(Lb).removeClass(Kb)):"dots"===Nd?(lc(zd,"navDot"),Ad=Ae,he=Yc,ye.addClass(Kb).removeClass(Lb)):(Nd=!1,ye.removeClass(Lb+" "+Kb)),Nd&&(Pd?xe.insertBefore(re):xe.insertAfter(re),wc.nav=!1,wc(Ad,ze,"nav")),Qd=e.allowfullscreen,Qd?(De.prependTo(re),Rd=Kc&&"native"===Qd):(De.detach(),Rd=!1),a(Sd,ob),a(!Sd,pb),a(!e.captions,vb),a(Yd,tb),a("always"!==e.arrows,wb),Xd=e.shadows&&!Mc,a(!Xd,rb),qe.addClass(b.add.join(" ")).removeClass(b.remove.join(" ")),Ke=d.extend({},e)}function x(a){return 0>a?(zd+a%zd)%zd:a>=zd?a%zd:a}function y(a){return h(a,0,zd-1)}function z(a){return Md?x(a):y(a)}function E(a){return a>0||Md?a-1:!1}function U(a){return zd-1>a||Md?a+1:!1}function $(){Me.min=Md?-1/0:-r(zd-1,Le.w,e.margin,Fd),Me.max=Md?1/0:-r(0,Le.w,e.margin,Fd),Me.snap=Le.w+e.margin}function bb(){Oe.min=Math.min(0,Le.nw-ze.width()),Oe.max=0,ze.toggleClass(Bb,!(Oe.noMove=Oe.min===Oe.max))}function cb(a,b,c){if("number"==typeof a){a=new Array(a);var e=!0}return d.each(a,function(a,d){if(e&&(d=a),"number"==typeof d){var f=yd[x(d)];if(f){var g="$"+b+"Frame",h=f[g];c.call(this,a,d,f,h,g,h&&h.data())}}})}function fb(a,b,c,d){(!$d||"*"===$d&&d===Ld)&&(a=q(e.width)||q(a)||Vc,b=q(e.height)||q(b)||Wc,ie.resize({width:a,ratio:e.ratio||c||a/b},0,d!==Ld&&"*"))}function Pb(a,b,c,f,g,h){cb(a,b,function(a,i,j,k,l,m){function n(a){var b=x(i);fd(a,{index:b,src:w,frame:yd[b]})}function o(){t.remove(),d.Fotorama.cache[w]="error",j.html&&"stage"===b||!y||y===w?(!w||j.html||r?"stage"===b&&(k.trigger("f:load").removeClass(ac+" "+_b).addClass(bc),n("load"),fb()):(k.trigger("f:error").removeClass(ac).addClass(_b),n("error")),m.state="error",!(zd>1&&yd[i]===j)||j.html||j.deleted||j.video||r||(j.deleted=!0,ie.splice(i,1))):(j[v]=w=y,Pb([i],b,c,f,g,!0))}function p(){d.Fotorama.measures[w]=u.measures=d.Fotorama.measures[w]||{width:s.width,height:s.height,ratio:s.width/s.height},fb(u.measures.width,u.measures.height,u.measures.ratio,i),t.off("load error").addClass(fc+(r?" "+gc:"")).prependTo(k),I(t,(d.isFunction(c)?c():c)||Le,f||j.fit||e.fit,g||j.position||e.position),d.Fotorama.cache[w]=m.state="loaded",setTimeout(function(){k.trigger("f:load").removeClass(ac+" "+_b).addClass(bc+" "+(r?cc:dc)),"stage"===b?n("load"):(j.thumbratio===$c||!j.thumbratio&&e.thumbratio===$c)&&(j.thumbratio=u.measures.ratio,vd())},0)}function q(){var a=10;G(function(){return!fe||!a--&&!Mc},function(){p()})}if(k){var r=ie.fullScreen&&j.full&&j.full!==j.img&&!m.$full&&"stage"===b;if(!m.$img||h||r){var s=new Image,t=d(s),u=t.data();m[r?"$full":"$img"]=t;var v="stage"===b?r?"full":"img":"thumb",w=j[v],y=r?null:j["stage"===b?"thumb":"img"];if("navThumb"===b&&(k=m.$wrap),!w)return void o();d.Fotorama.cache[w]?!function z(){"error"===d.Fotorama.cache[w]?o():"loaded"===d.Fotorama.cache[w]?setTimeout(q,0):setTimeout(z,100)}():(d.Fotorama.cache[w]="*",t.on("load",q).on("error",o)),m.state="",s.src=w}}})}function Qb(a){Ie.append(Bd.spin().el).appendTo(a)}function Rb(){Ie.detach(),Bd&&Bd.stop()}function Sb(){var a=Dd[Xc];a&&!a.data().state&&(Qb(a),a.on("f:load f:error",function(){a.off("f:load f:error"),Rb()}))}function ec(a){W(a,sd),X(a,function(){setTimeout(function(){Q(ye)},0),Rc({time:Vd,guessIndex:d(this).data().eq,minMax:Oe})})}function lc(a,b){cb(a,b,function(a,c,e,f,g,h){if(!f){f=e[g]=qe[g].clone(),h=f.data(),h.data=e;var i=f[0];"stage"===b?(e.html&&d('<div class="'+kc+'"></div>').append(e._html?d(e.html).removeAttr("id").html(e._html):e.html).appendTo(f),e.caption&&d(N(oc,N(pc,e.caption))).appendTo(f),e.video&&f.addClass(zb).append(Fe.clone()),X(i,function(){setTimeout(function(){Q(re)},0),pd({index:h.eq,user:!0})}),te=te.add(f)):"navDot"===b?(ec(i),Ae=Ae.add(f)):"navThumb"===b&&(ec(i),h.$wrap=f.children(":first"),Be=Be.add(f),e.video&&h.$wrap.append(Fe.clone()))}})}function sc(a,b,c,d){return a&&a.length&&I(a,b,c,d)}function tc(a){cb(a,"stage",function(a,b,c,f,g,h){if(f){var i=x(b),j=c.fit||e.fit,k=c.position||e.position;h.eq=i,Re[Xc][i]=f.css(d.extend({left:Sd?0:r(b,Le.w,e.margin,Fd)},Sd&&l(0))),F(f[0])&&(f.appendTo(se),md(c.$video)),sc(h.$img,Le,j,k),sc(h.$full,Le,j,k)}})}function uc(a,b){if("thumbs"===Nd&&!isNaN(a)){var c=-a,f=-a+Le.nw;Be.each(function(){var a=d(this),g=a.data(),h=g.eq,i=function(){return{h:Ud,w:g.w}},j=i(),k=yd[h]||{},l=k.thumbfit||e.thumbfit,m=k.thumbposition||e.thumbposition;j.w=g.w,g.l+g.w<c||g.l>f||sc(g.$img,j,l,m)||b&&Pb([h],"navThumb",i,l,m)})}}function wc(a,b,c){if(!wc[c]){var f="nav"===c&&Od,g=0;b.append(a.filter(function(){for(var a,b=d(this),c=b.data(),e=0,f=yd.length;f>e;e++)if(c.data===yd[e]){a=!0,c.eq=e;break}return a||b.remove()&&!1}).sort(function(a,b){return d(a).data().eq-d(b).data().eq}).each(function(){if(f){var a=d(this),b=a.data(),c=Math.round(Ud*b.data.thumbratio)||Td;b.l=g,b.w=c,a.css({width:c}),g+=c+e.thumbmargin}})),wc[c]=!0}}function xc(a){return a-Se>Le.w/3}function yc(a){return!(Md||Je+a&&Je-zd+a||Cd)}function Gc(){var a=yc(0),b=yc(1);ue.toggleClass(Eb,a).attr(V(a)),ve.toggleClass(Eb,b).attr(V(b))}function Hc(){Ne.ok&&(Ne.prevent={"<":yc(0),">":yc(1)})}function Lc(a){var b,c,d=a.data();return Od?(b=d.l,c=d.w):(b=a.position().left,c=a.width()),{c:b+c/2,min:-b+10*e.thumbmargin,max:-b+Le.w-c-10*e.thumbmargin}}function Oc(a){var b=Dd[he].data();_(Ce,{time:1.2*a,pos:b.l,width:b.w-2*e.thumbborderwidth})}function Rc(a){var b=yd[a.guessIndex][he];if(b){var c=Oe.min!==Oe.max,d=a.minMax||c&&Lc(Dd[he]),e=c&&(a.keep&&Rc.l?Rc.l:h((a.coo||Le.nw/2)-Lc(b).c,d.min,d.max)),f=c&&h(e,Oe.min,Oe.max),g=1.1*a.time;_(ze,{time:g,pos:f||0,onEnd:function(){uc(f,!0)}}),ld(ye,K(f,Oe.min,Oe.max)),Rc.l=e}}function Tc(){_c(he),Qe[he].push(Dd[he].addClass(Wb))}function _c(a){for(var b=Qe[a];b.length;)b.shift().removeClass(Wb)}function bd(a){var b=Re[a];d.each(Ed,function(a,c){delete b[x(c)]}),d.each(b,function(a,c){delete b[a],c.detach()})}function cd(a){Fd=Gd=Je;var b=Dd[Xc];b&&(_c(Xc),Qe[Xc].push(b.addClass(Wb)),a||ie.show.onEnd(!0),v(se,0,!0),bd(Xc),tc(Ed),$(),bb())}function ed(a,b){a&&d.each(b,function(b,c){c&&d.extend(c,{width:a.width||c.width,height:a.height,minwidth:a.minwidth,maxwidth:a.maxwidth,minheight:a.minheight,maxheight:a.maxheight,ratio:S(a.ratio)})})}function fd(b,c){a.trigger(ib+":"+b,[ie,c])}function gd(){clearTimeout(hd.t),fe=1,e.stopautoplayontouch?ie.stopAutoplay():ce=!0}function hd(){fe&&(e.stopautoplayontouch||(id(),jd()),hd.t=setTimeout(function(){fe=0},Qc+Pc))}function id(){ce=!(!Cd&&!de)}function jd(){if(clearTimeout(jd.t),G.stop(jd.w),!e.autoplay||ce)return void(ie.autoplay&&(ie.autoplay=!1,fd("stopautoplay")));ie.autoplay||(ie.autoplay=!0,fd("startautoplay"));var a=Je,b=Dd[Xc].data();jd.w=G(function(){return b.state||a!==Je},function(){jd.t=setTimeout(function(){if(!ce&&a===Je){var b=Kd,c=yd[b][Xc].data();jd.w=G(function(){return c.state||b!==Kd},function(){ce||b!==Kd||ie.show(Md?Z(!Yd):Kd)})}},e.autoplay)})}function kd(){ie.fullScreen&&(ie.fullScreen=!1,Kc&&vc.cancel(le),Dc.removeClass(jb),Cc.removeClass(jb),a.removeClass(Zb).insertAfter(pe),Le=d.extend({},ee),md(Cd,!0,!0),rd("x",!1),ie.resize(),Pb(Ed,"stage"),Q(Ec,ae,_d),fd("fullscreenexit"))}function ld(a,b){Xd&&(a.removeClass(Ub+" "+Vb),b&&!Cd&&a.addClass(b.replace(/^|\s/g," "+Tb+"--")))}function md(a,b,c){b&&(qe.removeClass(nb),Cd=!1,o()),a&&a!==Cd&&(a.remove(),fd("unloadvideo")),c&&(id(),jd())}function nd(a){qe.toggleClass(qb,a)}function od(a){if(!Me.flow){var b=a?a.pageX:od.x,c=b&&!yc(xc(b))&&e.click;od.p!==c&&re.toggleClass(Cb,c)&&(od.p=c,od.x=b)}}function pd(a){clearTimeout(pd.t),e.clicktransition&&e.clicktransition!==e.transition?setTimeout(function(){var b=e.transition;ie.setOptions({transition:e.clicktransition}),Wd=b,pd.t=setTimeout(function(){ie.show(a)},10)},0):ie.show(a)}function qd(a,b){var c=a.target,f=d(c);f.hasClass(mc)?ie.playVideo():c===Ee?ie.toggleFullScreen():Cd?c===He&&md(Cd,!0,!0):b?nd():e.click&&pd({index:a.shiftKey||Z(xc(a._x)),slow:a.altKey,user:!0})}function rd(a,b){Me[a]=Oe[a]=b}function sd(a){var b=d(this).data().eq;pd({index:b,slow:a.altKey,user:!0,coo:a._x-ye.offset().left})}function td(a){pd({index:we.index(this)?">":"<",slow:a.altKey,user:!0})}function ud(a){X(a,function(){setTimeout(function(){Q(re)},0),nd(!1)})}function vd(){if(m(),u(),!vd.i){vd.i=!0;var a=e.startindex;(a||e.hash&&c.hash)&&(Ld=L(a||c.hash.replace(/^#/,""),yd,0===ie.index||a,a)),Je=Fd=Gd=Hd=Ld=z(Ld)||0}if(zd){if(wd())return;Cd&&md(Cd,!0),Ed=[],bd(Xc),vd.ok=!0,ie.show({index:Je,time:0}),ie.resize()}else ie.destroy()}function wd(){return!wd.f===Yd?(wd.f=Yd,Je=zd-1-Je,ie.reverse(),!0):void 0}function xd(){xd.ok||(xd.ok=!0,fd("ready"))}Cc=d("html"),Dc=d("body");var yd,zd,Ad,Bd,Cd,Dd,Ed,Fd,Gd,Hd,Id,Jd,Kd,Ld,Md,Nd,Od,Pd,Qd,Rd,Sd,Td,Ud,Vd,Wd,Xd,Yd,Zd,$d,_d,ae,be,ce,de,ee,fe,ge,he,ie=this,je=d.now(),ke=ib+je,le=a[0],me=1,ne=a.data(),oe=d("<style></style>"),pe=d(N(Yb)),qe=d(N(kb)),re=d(N(xb)).appendTo(qe),se=(re[0],d(N(Ab)).appendTo(re)),te=d(),ue=d(N(Db+" "+Fb+rc)),ve=d(N(Db+" "+Gb+rc)),we=ue.add(ve).appendTo(re),xe=d(N(Ib)),ye=d(N(Hb)).appendTo(xe),ze=d(N(Jb)).appendTo(ye),Ae=d(),Be=d(),Ce=(se.data(),ze.data(),d(N(jc)).appendTo(ze)),De=d(N($b+rc)),Ee=De[0],Fe=d(N(mc)),Ge=d(N(nc)).appendTo(re),He=Ge[0],Ie=d(N(qc)),Je=!1,Ke={},Le={},Me={},Ne={},Oe={},Pe={},Qe={},Re={},Se=0,Te=[];
qe[Xc]=d(N(yb)),qe[Zc]=d(N(Mb+" "+Ob+rc,N(ic))),qe[Yc]=d(N(Mb+" "+Nb+rc,N(hc))),Qe[Xc]=[],Qe[Zc]=[],Qe[Yc]=[],Re[Xc]={},qe.addClass(Ic?mb:lb).toggleClass(qb,!e.controlsonstart),ne.fotorama=this,ie.startAutoplay=function(a){return ie.autoplay?this:(ce=de=!1,t(a||e.autoplay),jd(),this)},ie.stopAutoplay=function(){return ie.autoplay&&(ce=de=!0,jd()),this},ie.show=function(a){var b;"object"!=typeof a?(b=a,a={}):b=a.index,b=">"===b?Gd+1:"<"===b?Gd-1:"<<"===b?0:">>"===b?zd-1:b,b=isNaN(b)?L(b,yd,!0):b,b="undefined"==typeof b?Je||0:b,ie.activeIndex=Je=z(b),Id=E(Je),Jd=U(Je),Kd=x(Je+(Yd?-1:1)),Ed=[Je,Id,Jd],Gd=Md?b:Je;var c=Math.abs(Hd-Gd),d=w(a.time,function(){return Math.min(Vd*(1+(c-1)/12),2*Vd)}),f=a.overPos;a.slow&&(d*=10);var g=Dd;ie.activeFrame=Dd=yd[Je];var i=g===Dd&&!a.user;md(Cd,Dd.i!==yd[x(Fd)].i),lc(Ed,"stage"),tc(Mc?[Gd]:[Gd,E(Gd),U(Gd)]),rd("go",!0),i||fd("show",{user:a.user,time:d}),ce=!0;var j=ie.show.onEnd=function(b){if(!j.ok){if(j.ok=!0,b||cd(!0),i||fd("showend",{user:a.user}),!b&&Wd&&Wd!==e.transition)return ie.setOptions({transition:Wd}),void(Wd=!1);Sb(),Pb(Ed,"stage"),rd("go",!1),Hc(),od(),id(),jd()}};if(Sd){var k=Dd[Xc],l=Je!==Hd?yd[Hd][Xc]:null;ab(k,l,te,{time:d,method:e.transition,onEnd:j},Te)}else _(se,{pos:-r(Gd,Le.w,e.margin,Fd),overPos:f,time:d,onEnd:j});if(Gc(),Nd){Tc();var m=y(Je+h(Gd-Hd,-1,1));Rc({time:d,coo:m!==Je&&a.coo,guessIndex:"undefined"!=typeof a.coo?m:Je,keep:i}),Od&&Oc(d)}return be="undefined"!=typeof Hd&&Hd!==Je,Hd=Je,e.hash&&be&&!ie.eq&&H(Dd.id||Je+1),this},ie.requestFullScreen=function(){return Qd&&!ie.fullScreen&&(_d=Ec.scrollTop(),ae=Ec.scrollLeft(),Q(Ec),rd("x",!0),ee=d.extend({},Le),a.addClass(Zb).appendTo(Dc.addClass(jb)),Cc.addClass(jb),md(Cd,!0,!0),ie.fullScreen=!0,Rd&&vc.request(le),ie.resize(),Pb(Ed,"stage"),Sb(),fd("fullscreenenter")),this},ie.cancelFullScreen=function(){return Rd&&vc.is()?vc.cancel(b):kd(),this},ie.toggleFullScreen=function(){return ie[(ie.fullScreen?"cancel":"request")+"FullScreen"]()},T(b,vc.event,function(){!yd||vc.is()||Cd||kd()}),ie.resize=function(a){if(!yd)return this;var b=arguments[1]||0,c=arguments[2];ed(ie.fullScreen?{width:"100%",maxwidth:null,minwidth:null,height:"100%",maxheight:null,minheight:null}:R(a),[Le,c||ie.fullScreen||e]);var d=Le.width,f=Le.height,g=Le.ratio,i=Ec.height()-(Nd?ye.height():0);return q(d)&&(qe.addClass(ub).css({width:d,minWidth:Le.minwidth||0,maxWidth:Le.maxwidth||ad}),d=Le.W=Le.w=qe.width(),Le.nw=Nd&&p(e.navwidth,d)||d,e.glimpse&&(Le.w-=Math.round(2*(p(e.glimpse,d)||0))),se.css({width:Le.w,marginLeft:(Le.W-Le.w)/2}),f=p(f,i),f=f||g&&d/g,f&&(d=Math.round(d),f=Le.h=Math.round(h(f,p(Le.minheight,i),p(Le.maxheight,i))),re.stop().animate({width:d,height:f},b,function(){qe.removeClass(ub)}),cd(),Nd&&(ye.stop().animate({width:Le.nw},b),Rc({guessIndex:Je,time:b,keep:!0}),Od&&wc.nav&&Oc(b)),$d=c||!0,xd())),Se=re.offset().left,this},ie.setOptions=function(a){return d.extend(e,a),vd(),this},ie.shuffle=function(){return yd&&O(yd)&&vd(),this},ie.destroy=function(){return ie.cancelFullScreen(),ie.stopAutoplay(),yd=ie.data=null,j(),Ed=[],bd(Xc),vd.ok=!1,this},ie.playVideo=function(){var a=Dd,b=a.video,c=Je;return"object"==typeof b&&a.videoReady&&(Rd&&ie.fullScreen&&ie.cancelFullScreen(),G(function(){return!vc.is()||c!==Je},function(){c===Je&&(a.$video=a.$video||d(d.Fotorama.jst.video(b)),a.$video.appendTo(a[Xc]),qe.addClass(nb),Cd=a.$video,o(),we.blur(),De.blur(),fd("loadvideo"))})),this},ie.stopVideo=function(){return md(Cd,!0,!0),this},re.on("mousemove",od),Me=db(se,{onStart:gd,onMove:function(a,b){ld(re,b.edge)},onTouchEnd:hd,onEnd:function(a){ld(re);var b=(Nc&&!ge||a.touch)&&e.arrows&&"always"!==e.arrows;if(a.moved||b&&a.pos!==a.newPos&&!a.control){var c=s(a.newPos,Le.w,e.margin,Fd);ie.show({index:c,time:Sd?Vd:a.time,overPos:a.overPos,user:!0})}else a.aborted||a.control||qd(a.startEvent,b)},timeLow:1,timeHigh:1,friction:2,select:"."+Xb+", ."+Xb+" *",$wrap:re}),Oe=db(ze,{onStart:gd,onMove:function(a,b){ld(ye,b.edge)},onTouchEnd:hd,onEnd:function(a){function b(){Rc.l=a.newPos,id(),jd(),uc(a.newPos,!0)}if(a.moved)a.pos!==a.newPos?(ce=!0,_(ze,{time:a.time,pos:a.newPos,overPos:a.overPos,onEnd:b}),uc(a.newPos),Xd&&ld(ye,K(a.newPos,Oe.min,Oe.max))):b();else{var c=a.$target.closest("."+Mb,ze)[0];c&&sd.call(c,a.startEvent)}},timeLow:.5,timeHigh:2,friction:5,$wrap:ye}),Ne=eb(re,{shift:!0,onEnd:function(a,b){gd(),hd(),ie.show({index:b,slow:a.altKey})}}),Pe=eb(ye,{onEnd:function(a,b){gd(),hd();var c=v(ze)+.25*b;ze.css(k(h(c,Oe.min,Oe.max))),Xd&&ld(ye,K(c,Oe.min,Oe.max)),Pe.prevent={"<":c>=Oe.max,">":c<=Oe.min},clearTimeout(Pe.t),Pe.t=setTimeout(function(){Rc.l=c,uc(c,!0)},Pc),uc(c)}}),qe.hover(function(){setTimeout(function(){fe||nd(!(ge=!0))},0)},function(){ge&&nd(!(ge=!1))}),M(we,function(a){Y(a),td.call(this,a)},{onStart:function(){gd(),Me.control=!0},onTouchEnd:hd}),we.each(function(){W(this,function(a){td.call(this,a)}),ud(this)}),W(Ee,ie.toggleFullScreen),ud(Ee),d.each("load push pop shift unshift reverse sort splice".split(" "),function(a,b){ie[b]=function(){return yd=yd||[],"load"!==b?Array.prototype[b].apply(yd,arguments):arguments[0]&&"object"==typeof arguments[0]&&arguments[0].length&&(yd=P(arguments[0])),vd(),ie}}),vd()},d.fn.fotorama=function(b){return this.each(function(){var c=this,e=d(this),f=e.data(),g=f.fotorama;g?g.setOptions(b,!0):G(function(){return!E(c)},function(){f.urtext=e.html(),new d.Fotorama(e,d.extend({},cd,a.fotoramaDefaults,b,f))})})},d.Fotorama.instances=[],d.Fotorama.cache={},d.Fotorama.measures={},d=d||{},d.Fotorama=d.Fotorama||{},d.Fotorama.jst=d.Fotorama.jst||{},d.Fotorama.jst.style=function(a){{var b,c="";tc.escape}return c+=".fotorama"+(null==(b=a.s)?"":b)+" .fotorama__nav--thumbs .fotorama__nav__frame{\npadding:"+(null==(b=a.m)?"":b)+"px;\nheight:"+(null==(b=a.h)?"":b)+"px}\n.fotorama"+(null==(b=a.s)?"":b)+" .fotorama__thumb-border{\nheight:"+(null==(b=a.h-a.b*(a.q?0:2))?"":b)+"px;\nborder-width:"+(null==(b=a.b)?"":b)+"px;\nmargin-top:"+(null==(b=a.m)?"":b)+"px}"},d.Fotorama.jst.video=function(a){function b(){c+=d.call(arguments,"")}var c="",d=(tc.escape,Array.prototype.join);return c+='<div class="fotorama__video"><iframe src="',b(("youtube"==a.type?a.p+"youtube.com/embed/"+a.id+"?autoplay=1":"vimeo"==a.type?a.p+"player.vimeo.com/video/"+a.id+"?autoplay=1&badge=0":a.id)+(a.s&&"custom"!=a.type?"&"+a.s:"")),c+='" frameborder="0" allowfullscreen></iframe></div>\n'},d(function(){d("."+ib+':not([data-auto="false"])').fotorama()})}(window,document,location,"undefined"!=typeof jQuery&&jQuery);;
// Compiled from 'script/password_strength_plugin.js'
/* @projectDescription jQuery Password Strength Plugin - A jQuery plugin to provide accessibility functions
 * @author Tane Piper (digitalspaghetti@gmail.com)
 * @version 2.0
 * @website: http://digitalspaghetti.me.uk/digitalspaghetti
 * @license MIT License: http://www.opensource.org/licenses/mit-license.php
 * 
 * === Changelog ===
 * Version 2.1.1 (07/10/2013)
 * Changed UI
 * 
 * Version 2.1 (18/05/2008)
 * Added a jQuery method to add a new rule: jQuery('input[@type=password]').pstrength.addRule(name, method, score, active)
 * Added a jQuery method to change a rule score: jQuery('input[@type=password]').pstrength.changeScore('one_number', 50);
 * Added a jQuery method to change a rules active state: jQuery('input[@type=password]').pstrength.ruleActive('one_number', false);
 * Hide the 'password to short' span if the password is more than the min chars
 * 
 * Version 2.0 (17/05/2008)
 * Completly re-wrote the plugin from scratch.  Plugin now features lamda functions for validation and
 * custom validation rules 
 * Plugin now exists in new digitalspaghetti. namespace to stop any conflits with other plugins.
 * Updated documentation
 * 
 * Version 1.4 (12/02/2008)
 * Added some improvments to i18n stuff from Raffael Luthiger.
 * Version 1.3 (02/01/2008)
 * Changing coding style to more OO
 * Added default messages object for i18n
 * Changed password length score to Math.pow (thanks to Keith Mashinter for this suggestion)
 * Version 1.2 (03/09/2007)
 * Added more options for colors and common words
 * Added common words checked to see if words like 'password' or 'qwerty' are being entered
 * Added minimum characters required for password
 * Re-worked scoring system to give better results
 * Version 1.1 (20/08/2007)
 * Changed code to be more jQuery-like
 * Version 1.0 (20/07/2007)
 * Initial version.
 */

// Create our namespaced object
/*global window */
/*global jQuery */
/*global digitalspaghetti*/
window.digitalspaghetti = window.digitalspaghetti || {};
digitalspaghetti.password = {	
	'defaults' : {
		'passwordFieldId': "passwordField",
		'tlCriteriaMinCount': 0,
		'minChar': 0,
		'colors': ["#f00", "#f60", "#ffd400", "#3c0", "#3f0"],
		'scores': [20, 30, 43, 50],
		'verdicts':	['Weak', 'Normal', 'Medium', 'Strong', 'Very Strong'],
		'raisePower': 1.4,
		'debug': false
	},
	'ruleScores' : {
		'length': 0,
		'matchTLCriteriaCount': 0,
		'lowercase': 1,
		'uppercase': 3,
		'one_number': 3,
		'three_numbers': 5,
		'one_special_char': 3,
		'two_special_char': 5,
		'upper_lower_combo': 2,
		'letter_number_combo': 2,
		'letter_number_char_combo': 2
	},
	'rules' : {
		'matchTLCriteriaCount': true,
		'length': true,
		'lowercase': true,
		'uppercase': true,
		'one_number': true,
		'three_numbers': true,
		'one_special_char': true,
		'two_special_char': true,
		'upper_lower_combo': true,
		'letter_number_combo': true,
		'letter_number_char_combo': true
	},
	'validationRules': {
		'matchTLCriteriaCount': function(word, score) {
			var acceptingWordRules = 0;
			var validationRules = digitalspaghetti.password.validationRules;
			acceptingWordRules += validationRules.lowercase(word, 1) ? 1 : 0;
			acceptingWordRules += validationRules.uppercase(word, 1) ? 1 : 0;
			acceptingWordRules += validationRules.one_number(word, 1) ? 1 : 0;
			acceptingWordRules += validationRules.one_special_char(word, 1) ? 1 : 0;
			
			if(acceptingWordRules < digitalspaghetti.password.options.tlCriteriaMinCount) {
				return Number.NEGATIVE_INFINITY;
			} else {
				return score;
			}
			
		},
		'length': function (word, score) {
			var wordlen = word.length;
			if (wordlen < digitalspaghetti.password.options.minChar) {
				return Number.NEGATIVE_INFINITY;
			} else {
				return Math.pow(wordlen, digitalspaghetti.password.options.raisePower);
			}
		},		
		'lowercase': function (word, score) {
			return word.match(/[a-z]/) && score;
		},
		'uppercase': function (word, score) {
			return word.match(/[A-Z]/) && score;
		},
		'one_number': function (word, score) {
			return word.match(/\d+/) && score;
		},
		'three_numbers': function (word, score) {
			return word.match(/(.*[0-9].*[0-9].*[0-9])/) && score;
		},
		'one_special_char': function (word, score) {
			return word.match(/.[!,@,#,$,%,\^,&,*,?,_,~]/) && score;
		},
		'two_special_char': function (word, score) {
			return word.match(/(.*[!,@,#,$,%,\^,&,*,?,_,~].*[!,@,#,$,%,\^,&,*,?,_,~])/) && score;
		},
		'upper_lower_combo': function (word, score) {
			return word.match(/([a-z].*[A-Z])|([A-Z].*[a-z])/) && score;
		},
		'letter_number_combo': function (word, score) {
			return word.match(/([a-zA-Z])/) && word.match(/([0-9])/) && score;
		},
		'letter_number_char_combo' : function (word, score) {
			return word.match(/([a-zA-Z0-9].*[!,@,#,$,%,\^,&,*,?,_,~])|([!,@,#,$,%,\^,&,*,?,_,~].*[a-zA-Z0-9])/) && score;
		}
	},
	'attachWidget': function (element) {
		digitalspaghetti.password.strengthBar = digitalspaghetti.password.options.passwordFieldId  + "_password-strength-bar";
		digitalspaghetti.password.strengthText = digitalspaghetti.password.options.passwordFieldId  + "_password-strength-text";
		var offset = 1; //px
		var barFrameBorderWidth = 1; //px
		var barFrameWidth = jQuery(element).outerWidth(true) - 2 * barFrameBorderWidth - offset;
		var output = ['<div>'];
		output.push('<div class="password-strength-frame" style="width: ' + barFrameWidth + 'px; border-width: ' + barFrameBorderWidth + 'px;">');
		output.push('<div id="' + digitalspaghetti.password.strengthBar + '"></div>');
		output.push('</div>');
		output.push('<div id="' + digitalspaghetti.password.strengthText + '"></div>');
		output.push('</div>');
		output = output.join('');
		jQuery(element).after(output);
	},
	'debugOutput': function (element) {
		if (typeof console.log === 'function') {
			console.log(digitalspaghetti.password);	
		} else {
			alert(digitalspaghetti.password);
		}
	},
	'addRule': function (name, method, score, active) {
		digitalspaghetti.password.rules[name] = active;
		digitalspaghetti.password.ruleScores[name] = score;
		digitalspaghetti.password.validationRules[name] = method;
		return true;
	},
	'init': function (element, options) {
		digitalspaghetti.password.options = jQuery.extend({}, digitalspaghetti.password.defaults, options);
		digitalspaghetti.password.attachWidget(element);
		jQuery(element).keyup(function () {
			digitalspaghetti.password.calculateScore(jQuery(this).val());
		});
		if (digitalspaghetti.password.options.debug) {
			digitalspaghetti.password.debugOutput();
		}
		
		digitalspaghetti.password.calculateScore(jQuery(element).val());
	},
	'calculateScore': function (word) {
		digitalspaghetti.password.totalscore = 0;
		digitalspaghetti.password.width = 0;
		for (var key in digitalspaghetti.password.validationRules) if (digitalspaghetti.password.rules.hasOwnProperty(key)) {
			if (digitalspaghetti.password.rules[key] === true) {
				var score = digitalspaghetti.password.ruleScores[key];
				var result = digitalspaghetti.password.validationRules[key](word, score);
				if (result) {
					digitalspaghetti.password.totalscore += result;
				}
			}
			if (digitalspaghetti.password.totalscore <= digitalspaghetti.password.options.scores[0] / 2) {
				digitalspaghetti.password.strColor = digitalspaghetti.password.options.colors[0];
				digitalspaghetti.password.strText = digitalspaghetti.password.options.verdicts[0];
				digitalspaghetti.password.width =  "3";
			} else if (digitalspaghetti.password.totalscore < digitalspaghetti.password.options.scores[0]) {
				digitalspaghetti.password.strColor = digitalspaghetti.password.options.colors[1];
				digitalspaghetti.password.strText = digitalspaghetti.password.options.verdicts[1];
				digitalspaghetti.password.width =  "13";
			} else if (digitalspaghetti.password.totalscore <= digitalspaghetti.password.options.scores[1] / 2) {
				digitalspaghetti.password.strColor = digitalspaghetti.password.options.colors[1];
				digitalspaghetti.password.strText = digitalspaghetti.password.options.verdicts[1];
				digitalspaghetti.password.width =  "25";
			} else if (digitalspaghetti.password.totalscore <= digitalspaghetti.password.options.scores[1]) {
				digitalspaghetti.password.strColor = digitalspaghetti.password.options.colors[1];
				digitalspaghetti.password.strText = digitalspaghetti.password.options.verdicts[1];
				digitalspaghetti.password.width =  "37";
			} else if (digitalspaghetti.password.totalscore <= digitalspaghetti.password.options.scores[2] / 2) {
				digitalspaghetti.password.strColor = digitalspaghetti.password.options.colors[2];
				digitalspaghetti.password.strText = digitalspaghetti.password.options.verdicts[2];
				digitalspaghetti.password.width =  "50";
			} else if (digitalspaghetti.password.totalscore <= digitalspaghetti.password.options.scores[2]) {
				digitalspaghetti.password.strColor = digitalspaghetti.password.options.colors[2];
				digitalspaghetti.password.strText = digitalspaghetti.password.options.verdicts[2];
				digitalspaghetti.password.width =  "62";
			} else if (digitalspaghetti.password.totalscore <= digitalspaghetti.password.options.scores[3] / 2) {
				digitalspaghetti.password.strColor = digitalspaghetti.password.options.colors[3];
				digitalspaghetti.password.strText = digitalspaghetti.password.options.verdicts[3];
				digitalspaghetti.password.width =  "75";
			} else if (digitalspaghetti.password.totalscore <= digitalspaghetti.password.options.scores[3]) {
				digitalspaghetti.password.strColor = digitalspaghetti.password.options.colors[3];
				digitalspaghetti.password.strText = digitalspaghetti.password.options.verdicts[3];
				digitalspaghetti.password.width =  "87";
			} else {
				digitalspaghetti.password.strColor = digitalspaghetti.password.options.colors[4];
				digitalspaghetti.password.strText = digitalspaghetti.password.options.verdicts[4];
				digitalspaghetti.password.width =  "100";
			}
			
			jQuery("#" + digitalspaghetti.password.strengthBar).css({'display': 'block', 'background-color': digitalspaghetti.password.strColor, 'height' : '4px', 'width': digitalspaghetti.password.width + "%"});
			jQuery("#" + digitalspaghetti.password.strengthText).text(digitalspaghetti.password.strText);
		}
	}
};

jQuery.extend(jQuery.fn, {
	'pstrength': function (options) {
		var self = this;
		return this.each(function () {
			digitalspaghetti.password.init(self, options);
		});
	}
});
jQuery.extend(jQuery.fn.pstrength, {
	'addRule': function (name, method, score, active) {
		digitalspaghetti.password.addRule(name, method, score, active);
		return true;
	},
	'changeScore': function (rule, score) {
		digitalspaghetti.password.ruleScores[rule] = score;
		return true;
	},
	'ruleActive': function (rule, active) {
		digitalspaghetti.password.rules[rule] = active;
		return true;
	}
});