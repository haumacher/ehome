/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.runtime;

import java.util.HashMap;
import java.util.Map;

import de.haumacher.ehome.blocks.HeatingCurve;

/**
 * The German texts of the heating curve's manifest (homecontrol <code>spec/blocks.md</code> 17.1),
 * by the id of the port, parameter or choice (see {@link RoomControllerTexts}), and the help texts of the ports and parameters in German and
 * English.
 */
public final class HeatingCurveTexts {

	/** The type id. */
	public static final String TYPE = HeatingCurve.TYPE;

	/** The German label of the type. */
	public static final String LABEL_DE = "Heizkurve";

	/** The German labels of the ports and parameters by id, the choices by <code>parameter.choice</code>. */
	public static final Map<String, String> DE = Map.ofEntries(
		Map.entry("outside", "Außentemperatur"),
		Map.entry("roomSetpoint", "Raumsollwert"),
		Map.entry("offset", "Vorlaufanhebung"),
		Map.entry("enable", "Heizung ein"),
		Map.entry("summerSwitch", "Sommer-/Winterschalter"),
		Map.entry("flowSetpoint", "Vorlauf-Sollwert"),
		Map.entry("summer", "Sommer"),
		Map.entry("outsideFiltered", "Gefilterte Außentemperatur"),
		Map.entry("state", "Zustand"),
		Map.entry("curve", "Kurvenform"),
		Map.entry("curve.slope", "Neigung und Niveau"),
		Map.entry("curve.design", "Auslegungspunkt"),
		Map.entry("curve.points", "Stützpunkte"),
		Map.entry("slope", "Neigung"),
		Map.entry("level", "Niveau"),
		Map.entry("roomTemperature", "Raumtemperatur der Kurve"),
		Map.entry("designOutside", "Auslegungs-Außentemperatur"),
		Map.entry("designFlow", "Auslegungs-Vorlauftemperatur"),
		Map.entry("pointCount", "Anzahl der Stützpunkte"),
		Map.entry("outside1", "Außentemperatur 1"),
		Map.entry("flow1", "Vorlauf 1"),
		Map.entry("outside2", "Außentemperatur 2"),
		Map.entry("flow2", "Vorlauf 2"),
		Map.entry("outside3", "Außentemperatur 3"),
		Map.entry("flow3", "Vorlauf 3"),
		Map.entry("outside4", "Außentemperatur 4"),
		Map.entry("flow4", "Vorlauf 4"),
		Map.entry("outside5", "Außentemperatur 5"),
		Map.entry("flow5", "Vorlauf 5"),
		Map.entry("outside6", "Außentemperatur 6"),
		Map.entry("flow6", "Vorlauf 6"),
		Map.entry("minFlow", "Kleinster Vorlauf"),
		Map.entry("maxFlow", "Größter Vorlauf"),
		Map.entry("minChange", "Kleinste gesendete Änderung"),
		Map.entry("outsideFilter", "Filterzeitkonstante der Außentemperatur"),
		Map.entry("summerOn", "Sommer oberhalb"),
		Map.entry("summerOff", "Winter unterhalb"),
		Map.entry("summerDays", "Tage bis zum Sommer"),
		Map.entry("frostOutside", "Frostschutz unterhalb"),
		Map.entry("offFlow", "Vorlauf ausgeschaltet"),
		Map.entry("sensorTimeout", "Sensor-Zeitüberschreitung"));

	/** The defaults of the points' outside temperatures and flows, as in the manifest. */
	private static final int[] POINT_OUTSIDE = { -15, -10, -5, 0, 10, 20 };

	private static final int[] POINT_FLOW = { 60, 55, 49, 43, 32, 20 };

	/**
	 * The German help texts of the ports and parameters by id (haumacher/ehome #101): what the member
	 * does, for the installer. The choices of an enumeration each in a paragraph of their own.
	 */
	public static final Map<String, String> HELP_DE = helpDe();

	/** The English help texts of the ports and parameters by id, as {@link #HELP_DE}. */
	public static final Map<String, String> HELP_EN = helpEn();

	private static Map<String, String> helpDe() {
		Map<String, String> result = new HashMap<>(Map.ofEntries(
			Map.entry("outside", "Die Außentemperatur, nach der die Kurve den Vorlauf bestimmt, z. B. von der Wetterstation; muss gebunden werden. "
				+ "Bis zu 4 Fühler, zusammengefasst als Mittelwert oder kleinster Wert; gelesen einmal pro Minute und geglättet mit „Filterzeitkonstante der Außentemperatur“. "
				+ "Kommt länger als „Sensor-Zeitüberschreitung“ kein Wert, meldet die Heizkurve Störung und rechnet mit dem letzten geglätteten Wert weiter."),
			Map.entry("roomSetpoint", "Der Raumsollwert, für den der Vorlauf berechnet wird; er verschiebt die Kurve: "
				+ "22 °C statt 20 °C „Raumtemperatur der Kurve“ heben den Vorlauf bei „Neigung“ 1,2 und 0 °C außen von 44 °C auf 48,4 °C. "
				+ "Ungebunden oder ohne Wert gilt „Raumtemperatur der Kurve“."),
			Map.entry("offset", "Eine Anhebung des Vorlaufs in Kelvin, die zur Kurve dazukommt (vor der Begrenzung auf „Kleinster Vorlauf“ und „Größter Vorlauf“), "
				+ "z. B. vom Ausgang „Vorlaufanhebung“ eines Wärmebedarfs: Bekommen die Räume zu wenig Wärme, hebt er den Vorlauf an. Ungebunden: keine Anhebung."),
			Map.entry("enable", "Schaltet die Heizung ein und aus. Aus (0): Der Vorlauf-Sollwert ist „Vorlauf ausgeschaltet“, "
				+ "bei Frostgefahr (siehe „Frostschutz unterhalb“) „Kleinster Vorlauf“. Ein (1) oder ungebunden: normaler Betrieb."),
			Map.entry("summerSwitch", "Ein Schalter, der Sommer (1) oder Winter (0) vorgibt, z. B. aus einer Visualisierung; "
				+ "solange er einen Wert hat, gilt er statt der Erkennung über „Sommer oberhalb“, „Winter unterhalb“ und „Tage bis zum Sommer“. "
				+ "Ungebunden: Die Heizkurve erkennt den Sommer selbst."),
			Map.entry("flowSetpoint", "Der Vorlauf-Sollwert für den Wärmeerzeuger oder den Mischerkreis, z. B. 44 °C bei 0 °C außen, "
				+ "begrenzt auf „Kleinster Vorlauf“ bis „Größter Vorlauf“; im Sommer und bei ausgeschalteter Heizung „Vorlauf ausgeschaltet“. "
				+ "Gesendet ab „Kleinste gesendete Änderung“ und beim Erreichen einer Grenze. Hält das virtuelle Gerät an, geht er auf 40 °C."),
			Map.entry("summer", "Ein im Sommer: für die Eingänge „Sommer“ der Raumregler und des Wärmebedarfs, die dann nicht heizen. "
				+ "Hält das virtuelle Gerät an, schaltet er aus."),
			Map.entry("outsideFiltered", "Die Außentemperatur nach der Glättung durch „Filterzeitkonstante der Außentemperatur“; "
				+ "mit ihr rechnen die Kurve und die Sommer-/Wintererkennung. Zur Anzeige oder Fehlersuche."),
			Map.entry("state", "Was die Heizkurve gerade tut: 0 Heizen, 1 Sommer, 2 Aus („Heizung ein“ aus), 3 Frostschutz (aus oder Sommer bei Frost), "
				+ "4 Störung (die Außentemperatur fehlt oder ist zu alt)."),
			Map.entry("curve", "Wie die Kurve angegeben wird. Bei jeder Form verschieben „Niveau“ und ein „Raumsollwert“ sie parallel.\n\n"
				+ "Neigung und Niveau: Vorlauf = Raumsollwert + „Niveau“ + „Neigung“ × (Raumsollwert − Außentemperatur); "
				+ "bei Neigung 1,2 und 20 °C: -10 °C außen 56 °C, 0 °C 44 °C, 10 °C 32 °C. "
				+ "Die übliche Angabe der Kesselregler; es wirken „Neigung“ und „Niveau“.\n\n"
				+ "Auslegungspunkt: Die Gerade durch den Punkt, an dem Raum- und Vorlauftemperatur gleich sind, und den Auslegungspunkt: "
				+ "bei „Auslegungs-Außentemperatur“ -12 °C „Auslegungs-Vorlauftemperatur“ 55 °C, bei 0 °C damit 41,9 °C. "
				+ "Passt, wenn die Heizlastberechnung oder der Heizungsbauer diese Werte nennt; es wirken die beiden Auslegungswerte und „Raumtemperatur der Kurve“, „Neigung“ nicht.\n\n"
				+ "Stützpunkte: 2 bis 6 Paare aus Außentemperatur und Vorlauf, dazwischen geradlinig verbunden, außerhalb des ersten und letzten Punkts waagerecht "
				+ "(Vorgabe: -15 °C 60 °C bis 20 °C 20 °C, bei 5 °C damit 37,5 °C). Für eine geknickte Kurve, z. B. eine Wärmepumpe; "
				+ "es wirken „Anzahl der Stützpunkte“ und die Punkte, „Neigung“ nicht."),
			Map.entry("slope", "Wie stark der Vorlauf mit sinkender Außentemperatur steigt: um so viele Kelvin je Kelvin kälter "
				+ "(1,2: 10 K kälter außen, 12 K mehr Vorlauf). Fußbodenheizungen liegen um 0,3–0,6, Heizkörper um 1,0–1,6. "
				+ "Nur bei „Kurvenform“ „Neigung und Niveau“. Die Bewohner dürfen sie ändern."),
			Map.entry("level", "Verschiebt die Kurve um so viele Kelvin nach oben oder unten, bei jeder Außentemperatur gleich, z. B. +2 K, wenn es überall etwas zu kühl ist. "
				+ "Wirkt bei jeder „Kurvenform“. Die Bewohner dürfen es ändern."),
			Map.entry("roomTemperature", "Die Raumtemperatur, für die die Kurve gilt, z. B. 20 °C. Ohne „Raumsollwert“ wird für sie gerechnet; "
				+ "ein Raumsollwert darüber oder darunter verschiebt die Kurve um die Differenz. "
				+ "Bei „Auslegungspunkt“ der Fußpunkt der Geraden (Vorlauf gleich Raumtemperatur); muss dann über der „Auslegungs-Außentemperatur“ "
				+ "und unter der „Auslegungs-Vorlauftemperatur“ liegen."),
			Map.entry("designOutside", "Die tiefste Außentemperatur, für die die Heizung ausgelegt ist (Normaußentemperatur des Orts), z. B. -12 °C. "
				+ "Nur bei „Kurvenform“ „Auslegungspunkt“; muss unter „Raumtemperatur der Kurve“ liegen."),
			Map.entry("designFlow", "Der Vorlauf, den die Heizung bei der „Auslegungs-Außentemperatur“ braucht, z. B. 55 °C für Heizkörper oder 35 °C für eine Fußbodenheizung. "
				+ "Nur bei „Kurvenform“ „Auslegungspunkt“; muss über „Raumtemperatur der Kurve“ liegen."),
			Map.entry("pointCount", "Wie viele Stützpunkte gelten, 2 bis 6; die Punkte darüber hinaus werden nicht beachtet. "
				+ "Nur bei „Kurvenform“ „Stützpunkte“."),
			Map.entry("minFlow", "Der Vorlauf-Sollwert geht nie darunter, z. B. 25 °C; bei Frostschutz ist er der Vorlauf. Muss unter „Größter Vorlauf“ liegen."),
			Map.entry("maxFlow", "Der Vorlauf-Sollwert geht nie darüber, z. B. 60 °C für Heizkörper oder 40 °C zum Schutz einer Fußbodenheizung, auch nicht mit „Vorlaufanhebung“."),
			Map.entry("minChange", "Wie weit sich der Vorlauf-Sollwert ändern muss, bevor er erneut gesendet wird, z. B. 0,5 K; "
				+ "„Kleinster Vorlauf“, „Größter Vorlauf“ und „Vorlauf ausgeschaltet“ werden immer gesendet. 0: jede Änderung."),
			Map.entry("outsideFilter", "Glättet die Außentemperatur, wie die Masse des Gebäudes es tut: Nach dieser Zeit hat die geglättete Temperatur "
				+ "knapp zwei Drittel eines Sprungs mitgemacht, z. B. 12 h für ein massives Haus, weniger für ein leichtes. "
				+ "Wirkt auf die Kurve und die Sommer-/Wintererkennung, nicht auf den Frostschutz. 0: keine Glättung."),
			Map.entry("summerOn", "Liegt die geglättete Außentemperatur „Tage bis zum Sommer“ lang über diesem Wert, z. B. 18 °C, schaltet die Heizkurve auf Sommer. "
				+ "Nur ohne Wert am „Sommer-/Winterschalter“."),
			Map.entry("summerOff", "Fällt die geglättete Außentemperatur unter diesen Wert, z. B. 15 °C, ist sofort wieder Winter. "
				+ "Muss unter „Sommer oberhalb“ liegen; nur ohne Wert am „Sommer-/Winterschalter“."),
			Map.entry("summerDays", "Wie viele Tage die geglättete Außentemperatur über „Sommer oberhalb“ liegen muss, bevor Sommer ist, z. B. 3; "
				+ "ein paar warme Tage im April schalten dann noch nicht ab. 0: sofort. Nur ohne Wert am „Sommer-/Winterschalter“."),
			Map.entry("frostOutside", "Ist die Heizung aus oder Sommer und sinkt die Außentemperatur (die gemessene, nicht die geglättete) unter diesen Wert, z. B. 3 °C, "
				+ "fordert die Heizkurve „Kleinster Vorlauf“ an, damit die Leitungen nicht einfrieren; "
				+ "das endet, wenn es 1 K wärmer ist (bei 3 °C: ab 4 °C)."),
			Map.entry("offFlow", "Der Vorlauf-Sollwert, solange die Heizung aus oder Sommer ist und kein Frost droht: 0 °C heißt keine Anforderung; "
				+ "manche Wärmeerzeuger brauchen einen Wert wie 20 °C."),
			Map.entry("sensorTimeout", "Wie lange die Heizkurve ohne neue Außentemperatur auskommt, z. B. 3 h: Kam länger keine, meldet sie Störung, "
				+ "hält die geglättete Temperatur fest und rechnet mit ihr weiter.")));
		for (int i = 0; i < POINT_OUTSIDE.length; i++) {
			int n = i + 1;
			result.put("outside" + n, "Die Außentemperatur des " + n + ". Stützpunkts (Vorgabe " + POINT_OUTSIDE[i] + " °C); zu ihr gehört „Vorlauf "
				+ n + "“. Die Außentemperaturen müssen von Punkt zu Punkt steigen. "
				+ "Nur bei „Kurvenform“ „Stützpunkte“ und wenn „Anzahl der Stützpunkte“ mindestens " + n + " ist.");
			result.put("flow" + n, "Der Vorlauf am " + n + ". Stützpunkt (Vorgabe " + POINT_FLOW[i] + " °C), also bei „Außentemperatur " + n
				+ "“; zwischen zwei Punkten wird geradlinig verbunden. "
				+ "Nur bei „Kurvenform“ „Stützpunkte“ und wenn „Anzahl der Stützpunkte“ mindestens " + n + " ist.");
		}
		return Map.copyOf(result);
	}

	private static Map<String, String> helpEn() {
		Map<String, String> result = new HashMap<>(Map.ofEntries(
			Map.entry("outside", "The outside temperature the curve sets the flow by, for example from the weather station; must be bound. "
				+ "Up to 4 sensors, aggregated as their mean or lowest value; read once a minute and smoothed by \"Outside filter time constant\". "
				+ "When none has come for longer than \"Sensor timeout\", the heating curve reports a fault and goes on with the last smoothed value."),
			Map.entry("roomSetpoint", "The room setpoint the flow is computed for; it shifts the curve: "
				+ "22 °C instead of a \"Room temperature of the curve\" of 20 °C raises the flow at \"Slope\" 1.2 and 0 °C outside from 44 °C to 48.4 °C. "
				+ "Unbound or without a value, \"Room temperature of the curve\" applies."),
			Map.entry("offset", "A raise of the flow in kelvin added to the curve (before the limits \"Lowest flow\" and \"Highest flow\"), "
				+ "for example from the output \"Flow setpoint offset\" of a heating demand: when the rooms get too little heat, it raises the flow. Unbound: no raise."),
			Map.entry("enable", "Switches the heating on and off. Off (0): the flow setpoint is \"Flow when off\", "
				+ "or \"Lowest flow\" when frost threatens (see \"Frost protection below\"). On (1) or unbound: normal operation."),
			Map.entry("summerSwitch", "A switch that sets summer (1) or winter (0), from a visualization, say; "
				+ "while it has a value it applies instead of the detection by \"Summer above\", \"Winter below\" and \"Days to summer\". "
				+ "Unbound: the heating curve detects the summer itself."),
			Map.entry("flowSetpoint", "The flow setpoint for the heat source or the mixing circuit, for example 44 °C at 0 °C outside, "
				+ "limited to \"Lowest flow\" through \"Highest flow\"; in summer and with the heating off, \"Flow when off\". "
				+ "Sent from \"Smallest change sent\" on and when it reaches a limit. When the virtual device stops, it goes to 40 °C."),
			Map.entry("summer", "On in summer: for the inputs \"Summer\" of the room controllers and the heating demand, which then do not heat. "
				+ "When the virtual device stops, it switches off."),
			Map.entry("outsideFiltered", "The outside temperature after the smoothing by \"Outside filter time constant\"; "
				+ "the curve and the summer/winter detection compute with it. For display or troubleshooting."),
			Map.entry("state", "What the heating curve is doing: 0 heating, 1 summer, 2 off (\"Heating on\" is off), 3 frost protection (off or summer during frost), "
				+ "4 fault (the outside temperature is missing or too old)."),
			Map.entry("curve", "How the curve is given. In every form, \"Level\" and a \"Room setpoint\" shift it in parallel.\n\n"
				+ "Slope and level: flow = room setpoint + \"Level\" + \"Slope\" × (room setpoint − outside temperature); "
				+ "with slope 1.2 and 20 °C: -10 °C outside gives 56 °C, 0 °C 44 °C, 10 °C 32 °C. "
				+ "The usual way boiler controllers state it; \"Slope\" and \"Level\" apply.\n\n"
				+ "Design point: the straight line through the point where room and flow temperature are equal and through the design point: "
				+ "at a \"Design outside temperature\" of -12 °C a \"Design flow temperature\" of 55 °C, which gives 41.9 °C at 0 °C. "
				+ "Right when the heat load calculation or the heating engineer names these values; the two design values and \"Room temperature of the curve\" apply, \"Slope\" does not.\n\n"
				+ "Points: 2 to 6 pairs of outside temperature and flow, joined by straight lines between them and flat beyond the first and the last "
				+ "(by default -15 °C 60 °C through 20 °C 20 °C, which gives 37.5 °C at 5 °C). For a curve with a bend, for a heat pump, say; "
				+ "\"Points\" (the number) and the points apply, \"Slope\" does not."),
			Map.entry("slope", "How steeply the flow rises as the outside gets colder: by this many kelvin per kelvin colder "
				+ "(1.2: 10 K colder outside, 12 K more flow). Floor heating lies around 0.3–0.6, radiators around 1.0–1.6. "
				+ "Only with \"Curve\" \"Slope and level\". The residents may change it."),
			Map.entry("level", "Shifts the curve up or down by this many kelvin, the same at every outside temperature, for example +2 K when it is a little too cool everywhere. "
				+ "Applies to every \"Curve\". The residents may change it."),
			Map.entry("roomTemperature", "The room temperature the curve is meant for, 20 °C, say. Without a \"Room setpoint\" the flow is computed for it; "
				+ "a room setpoint above or below shifts the curve by the difference. "
				+ "With \"Design point\" the foot of the line (flow equal to room temperature); it must then be above the \"Design outside temperature\" "
				+ "and below the \"Design flow temperature\"."),
			Map.entry("designOutside", "The lowest outside temperature the heating is designed for (the local design temperature), -12 °C, say. "
				+ "Only with \"Curve\" \"Design point\"; must be below \"Room temperature of the curve\"."),
			Map.entry("designFlow", "The flow the heating needs at the \"Design outside temperature\", for example 55 °C for radiators or 35 °C for floor heating. "
				+ "Only with \"Curve\" \"Design point\"; must be above \"Room temperature of the curve\"."),
			Map.entry("pointCount", "How many of the points apply, 2 to 6; the points beyond are ignored. "
				+ "Only with \"Curve\" \"Points\"."),
			Map.entry("minFlow", "The flow setpoint never goes below this, 25 °C, say; in frost protection it is the flow. Must be below \"Highest flow\"."),
			Map.entry("maxFlow", "The flow setpoint never goes above this, for example 60 °C for radiators or 40 °C to protect a floor heating, not even with a \"Flow offset\"."),
			Map.entry("minChange", "How far the flow setpoint has to change before it is sent again, 0.5 K, say; "
				+ "\"Lowest flow\", \"Highest flow\" and \"Flow when off\" are always sent. 0: every change."),
			Map.entry("outsideFilter", "Smooths the outside temperature the way the mass of the building does: after this time the smoothed temperature "
				+ "has followed almost two thirds of a step, for example 12 h for a massive house, less for a light one. "
				+ "Applies to the curve and the summer/winter detection, not to the frost protection. 0: no smoothing."),
			Map.entry("summerOn", "When the smoothed outside temperature stays above this value, 18 °C, say, for the \"Days to summer\", the heating curve switches to summer. "
				+ "Only while the \"Summer switch\" has no value."),
			Map.entry("summerOff", "When the smoothed outside temperature falls below this value, 15 °C, say, it is winter again at once. "
				+ "Must be below \"Summer above\"; only while the \"Summer switch\" has no value."),
			Map.entry("summerDays", "How many days the smoothed outside temperature has to stay above \"Summer above\" before it is summer, 3, say; "
				+ "a few warm days in April then do not switch the heating off yet. 0: at once. Only while the \"Summer switch\" has no value."),
			Map.entry("frostOutside", "When the heating is off or it is summer and the outside temperature (the measured one, not the smoothed) falls below this value, 3 °C, say, "
				+ "the heating curve asks for \"Lowest flow\", so the pipes do not freeze; "
				+ "this ends when it is 1 K warmer (with 3 °C: from 4 °C on)."),
			Map.entry("offFlow", "The flow setpoint while the heating is off or it is summer and no frost threatens: 0 °C means no request; "
				+ "some heat sources need a value such as 20 °C."),
			Map.entry("sensorTimeout", "How long the heating curve manages without a new outside temperature, 3 h, say: when none has come for longer, it reports a fault, "
				+ "holds the smoothed temperature and goes on computing with it.")));
		for (int i = 0; i < POINT_OUTSIDE.length; i++) {
			int n = i + 1;
			result.put("outside" + n, "The outside temperature of point " + n + " (" + POINT_OUTSIDE[i] + " °C by default); \"Flow " + n
				+ "\" belongs to it. The outside temperatures must rise from point to point. "
				+ "Only with \"Curve\" \"Points\" and when \"Points\" (the number) is at least " + n + ".");
			result.put("flow" + n, "The flow at point " + n + " (" + POINT_FLOW[i] + " °C by default), that is at \"Outside temperature " + n
				+ "\"; two points are joined by a straight line. "
				+ "Only with \"Curve\" \"Points\" and when \"Points\" (the number) is at least " + n + ".");
		}
		return Map.copyOf(result);
	}

	private HeatingCurveTexts() {
		// Constants.
	}

}
