/*
 * Copyright (c) 2026 Bernhard Haumacher
 */
package de.haumacher.ehome.runtime;

import java.util.Map;

import de.haumacher.ehome.blocks.HeatingDemand;

/**
 * The German texts of the heating demand's manifest (homecontrol <code>spec/blocks.md</code> 16.1),
 * by the id of the port, parameter or choice (see {@link RoomControllerTexts}), and the help texts of the ports and parameters in German and
 * English.
 */
public final class HeatingDemandTexts {

	/** The type id. */
	public static final String TYPE = HeatingDemand.TYPE;

	/** The German label of the type. */
	public static final String LABEL_DE = "Wärmebedarf";

	/** The German labels of the ports and parameters by id, the choices by <code>parameter.choice</code>. */
	public static final Map<String, String> DE = Map.ofEntries(
		Map.entry("valves", "Ventilstellungen"),
		Map.entry("demands", "Wärmeanforderungen"),
		Map.entry("enable", "Heizung ein"),
		Map.entry("summer", "Sommer"),
		Map.entry("request", "Anforderung Wärmeerzeuger"),
		Map.entry("level", "Bedarfsgrad"),
		Map.entry("pump", "Umwälzpumpe"),
		Map.entry("flowSetpointOffset", "Vorlaufanhebung"),
		Map.entry("state", "Zustand"),
		Map.entry("staleInputs", "Eingänge ohne gültigen Wert"),
		Map.entry("mode", "Bedarfsermittlung"),
		Map.entry("mode.max", "Höchstes Ventil"),
		Map.entry("mode.topMean", "Mittel der höchsten"),
		Map.entry("topCount", "Anzahl gemittelter Ventile"),
		Map.entry("onThreshold", "Einschaltschwelle"),
		Map.entry("offThreshold", "Ausschaltschwelle"),
		Map.entry("minOn", "Mindestlaufzeit"),
		Map.entry("minOff", "Mindestpause"),
		Map.entry("pumpRunOn", "Pumpennachlauf"),
		Map.entry("targetLevel", "Bedarfsgrad für die Anhebung"),
		Map.entry("offsetGain", "Anhebungsrate"),
		Map.entry("maxOffset", "Größte Anhebung"),
		Map.entry("staleInput", "Eingang ohne gültigen Wert"),
		Map.entry("staleInput.ignore", "Ignorieren"),
		Map.entry("staleInput.full", "Als voll geöffnet werten"));

	/**
	 * The German help texts of the ports and parameters by id (haumacher/ehome #101): what the member
	 * does, for the installer. The choices of an enumeration each in a paragraph of their own.
	 */
	public static final Map<String, String> HELP_DE = Map.ofEntries(
		Map.entry("valves", "Die Ventilstellungen der Räume (0–100 %), bis zu 32, z. B. die Ausgänge „Ventil“ der Raumregler. "
			+ "Aus ihnen ergibt sich nach „Bedarfsermittlung“ der „Bedarfsgrad“; jeder empfangene Wert löst eine Berechnung aus. "
			+ "Ein Ventil ohne gültigen Wert zählt nach „Eingang ohne gültigen Wert“."),
		Map.entry("demands", "Ein/Aus-Anforderungen der Räume, bis zu 32, z. B. die Ausgänge „Wärmeanforderung“ der Raumregler oder Thermostate mit Schaltausgang. "
			+ "Eine Anforderung zählt für den „Bedarfsgrad“ wie ein Ventil mit 100 %, keine wie eines mit 0 %."),
		Map.entry("enable", "Schaltet die Anforderung an den Wärmeerzeuger frei. "
			+ "Aus (0): Der Wärmeerzeuger wird nicht angefordert (Zustand Gesperrt), sobald die „Mindestlaufzeit“ abgelaufen ist. "
			+ "Ein (1) oder ungebunden: normaler Betrieb."),
		Map.entry("summer", "Sommerbetrieb, z. B. vom Ausgang „Sommer“ einer Heizkurve. "
			+ "Ein (1): Der Wärmeerzeuger wird nicht angefordert (Zustand Sommer), sobald die „Mindestlaufzeit“ abgelaufen ist."),
		Map.entry("request", "Die Anforderung an den Wärmeerzeuger (Kessel, Wärmepumpe): schaltet ein, wenn der „Bedarfsgrad“ die „Einschaltschwelle“ erreicht, "
			+ "und aus, wenn er auf die „Ausschaltschwelle“ fällt (20 % und 5 %: ein bei 20 %, aus bei 5 %), "
			+ "dabei bleibt sie mindestens „Mindestlaufzeit“ ein und „Mindestpause“ aus. "
			+ "Hält das virtuelle Gerät an, schaltet sie ein, damit das Haus nicht auskühlt."),
		Map.entry("level", "Wie viel Wärme die Räume brauchen, 0–100 %: nach „Bedarfsermittlung“ das höchste Ventil oder das Mittel der höchsten "
			+ "(Ventile 80, 60, 20 und 10 %: 80 % bzw. bei 3 gemittelten 53 %). "
			+ "Z. B. für die Leistungsvorgabe einer modulierenden Wärmepumpe oder zur Anzeige. Hält das virtuelle Gerät an, geht er auf 100 %."),
		Map.entry("pump", "Die Umwälzpumpe des Heizkreises: ein, solange der Wärmeerzeuger angefordert ist, und danach noch „Pumpennachlauf“ lang. "
			+ "Hält das virtuelle Gerät an, schaltet sie ein."),
		Map.entry("flowSetpointOffset", "Eine Anhebung des Vorlaufs von 0 K bis „Größte Anhebung“, für den Eingang „Vorlaufanhebung“ einer Heizkurve: "
			+ "Sie wächst, solange der „Bedarfsgrad“ über „Bedarfsgrad für die Anhebung“ liegt (die Räume bekommen knapp genug Wärme), "
			+ "und sinkt, solange er darunter liegt oder nicht angefordert wird; wie schnell, sagt „Anhebungsrate“. "
			+ "Gesendet ab 0,1 K Änderung. Hält das virtuelle Gerät an, geht sie auf 0 K."),
		Map.entry("state", "Was der Wärmebedarf gerade tut: 0 Aus, 1 Ein, 2 Ein gehalten (die „Mindestlaufzeit“ läuft noch), "
			+ "3 Aus gehalten (die „Mindestpause“ läuft noch), 4 Gesperrt („Heizung ein“ aus), 5 Sommer."),
		Map.entry("staleInputs", "Wie viele der gebundenen Datenpunkte von „Ventilstellungen“ und „Wärmeanforderungen“ gerade keinen gültigen Wert haben, "
			+ "z. B. weil ein Raumregler nicht sendet. Zur Fehlersuche."),
		Map.entry("mode", "Wie aus den Ventilen der „Bedarfsgrad“ wird.\n\n"
			+ "Höchstes Ventil: Der Bedarfsgrad ist die höchste Ventilstellung, eine „Wärmeanforderung“ zählt als 100 % "
			+ "(Ventile 80, 60, 20 und 10 %: 80 %). Schon ein einziger kalter Raum fordert Wärme an; "
			+ "passt, wenn jeder Raum warm werden muss. „Anzahl gemittelter Ventile“ wirkt nicht.\n\n"
			+ "Mittel der höchsten: Der Mittelwert der „Anzahl gemittelter Ventile“ höchsten Stellungen (bei 3: (80 + 60 + 20) / 3 = 53 %). "
			+ "Ein einzelner Raum mit weit offenem Ventil, etwa ein Bad mit hohem Sollwert, lässt den Wärmeerzeuger dann nicht allein laufen. "
			+ "Gibt es weniger Werte, wird über die vorhandenen gemittelt."),
		Map.entry("topCount", "Über wie viele der höchsten Ventilstellungen gemittelt wird, z. B. 3; Wärmeanforderungen zählen als 100 % mit. "
			+ "Nur bei „Bedarfsermittlung“ „Mittel der höchsten“."),
		Map.entry("onThreshold", "Ab diesem „Bedarfsgrad“ wird der Wärmeerzeuger angefordert, z. B. 20 %. "
			+ "Zwischen „Ausschaltschwelle“ und dieser Schwelle bleibt die Anforderung, wie sie ist. Muss über der „Ausschaltschwelle“ liegen."),
		Map.entry("offThreshold", "Fällt der „Bedarfsgrad“ auf diesen Wert, endet die Anforderung, z. B. 5 %. Muss unter der „Einschaltschwelle“ liegen."),
		Map.entry("minOn", "Wie lange die Anforderung nach dem Einschalten mindestens bleibt, z. B. 5 min, gegen kurzes Takten des Brenners oder Verdichters; "
			+ "gilt auch, wenn „Heizung ein“ ausschaltet oder „Sommer“ kommt. 0: keine Mindestlaufzeit."),
		Map.entry("minOff", "Wie lange die Anforderung nach dem Ausschalten mindestens aus bleibt, z. B. 5 min. 0: keine Mindestpause."),
		Map.entry("pumpRunOn", "Wie lange die „Umwälzpumpe“ nach dem Ende der Anforderung weiterläuft, z. B. 5 min, um die Restwärme des Wärmeerzeugers zu verteilen. "
			+ "0: Die Pumpe schaltet mit der Anforderung aus."),
		Map.entry("targetLevel", "Der „Bedarfsgrad“, den die „Vorlaufanhebung“ anstrebt, z. B. 70 %: Steht das höchste Ventil länger bei 90 %, "
			+ "reicht der Vorlauf kaum aus, und die Anhebung wächst; liegt es darunter, sinkt sie wieder."),
		Map.entry("offsetGain", "Wie schnell sich die „Vorlaufanhebung“ ändert, in Kelvin je Prozent Abweichung und Stunde: "
			+ "Bei 0,1 hebt ein Bedarfsgrad 20 % über „Bedarfsgrad für die Anhebung“ den Vorlauf um 2 K pro Stunde an. "
			+ "0: Die Anhebung ändert sich nicht (von 0 K aus: keine Anhebung)."),
		Map.entry("maxOffset", "Die größte „Vorlaufanhebung“, z. B. 5 K. 0: keine Anhebung."),
		Map.entry("staleInput", "Was ein Ventil oder eine Anforderung ohne gültigen Wert (z. B. ein Raumregler, der nicht mehr sendet) für den „Bedarfsgrad“ zählt.\n\n"
			+ "Ignorieren: Der Eingang zählt nicht mit; fallen alle aus, ist der Bedarfsgrad 0 % und der Wärmeerzeuger wird nicht mehr angefordert. "
			+ "Passt, wenn ausgefallene Räume den Wärmeerzeuger nicht laufen lassen sollen.\n\n"
			+ "Als voll geöffnet werten: Der Eingang zählt wie ein Ventil mit 100 %; bei „Höchstes Ventil“ hält schon ein ausgefallener Raum "
			+ "die Anforderung aufrecht. Sicher gegen Auskühlen und Frost, kostet aber Energie, bis der Fehler behoben ist."));

	/** The English help texts of the ports and parameters by id, as {@link #HELP_DE}. */
	public static final Map<String, String> HELP_EN = Map.ofEntries(
		Map.entry("valves", "The rooms' valve positions (0–100 %), up to 32, for example the outputs \"Valve\" of the room controllers. "
			+ "The parameter \"Demand level\" decides how they make the output \"Demand level\"; every value received triggers a computation. "
			+ "How a valve without a valid value counts is set by \"Input not good\"."),
		Map.entry("demands", "The rooms' on/off requests, up to 32, for example the outputs \"Heat demand\" of the room controllers or thermostats with a switching output. "
			+ "For the demand level, a request counts like a valve at 100 %, no request like one at 0 %."),
		Map.entry("enable", "Releases the request to the heat source. "
			+ "Off (0): the heat source is not requested (state disabled) once the \"Minimum on time\" has passed. "
			+ "On (1) or unbound: normal operation."),
		Map.entry("summer", "Summer operation, for example from the output \"Summer\" of a heating curve. "
			+ "On (1): the heat source is not requested (state summer) once the \"Minimum on time\" has passed."),
		Map.entry("request", "The request to the heat source (boiler, heat pump): switches on when the demand level reaches the \"Switch-on level\" "
			+ "and off when it falls to the \"Switch-off level\" (20 % and 5 %: on at 20 %, off at 5 %), "
			+ "staying on for at least the \"Minimum on time\" and off for at least the \"Minimum off time\". "
			+ "When the virtual device stops, it switches on, so the house does not cool down."),
		Map.entry("level", "How much heat the rooms need, 0–100 %: the highest valve or the mean of the highest, as the parameter \"Demand level\" chooses "
			+ "(valves at 80, 60, 20 and 10 %: 80 %, or 53 % averaging 3). "
			+ "For the power demand of a modulating heat pump, say, or for display. When the virtual device stops, it goes to 100 %."),
		Map.entry("pump", "The circulation pump of the heating circuit: on while the heat source is requested, and for the \"Pump run-on\" after that. "
			+ "When the virtual device stops, it switches on."),
		Map.entry("flowSetpointOffset", "A raise of the flow temperature from 0 K to \"Highest offset\", for the input \"Flow offset\" of a heating curve: "
			+ "it grows while the demand level is above the \"Target level\" (the rooms barely get enough heat) "
			+ "and shrinks while it is below or nothing is requested; \"Offset gain\" says how fast. "
			+ "Sent on a change of 0.1 K or more. When the virtual device stops, it goes to 0 K."),
		Map.entry("state", "What the heating demand is doing: 0 off, 1 on, 2 held on (the \"Minimum on time\" is still running), "
			+ "3 held off (the \"Minimum off time\" is still running), 4 disabled (\"Heating on\" is off), 5 summer."),
		Map.entry("staleInputs", "How many of the datapoints bound to \"Valve positions\" and \"Heat demands\" have no valid value right now, "
			+ "for example because a room controller does not send. For troubleshooting."),
		Map.entry("mode", "How the valves make the demand level.\n\n"
			+ "Highest valve: the demand level is the highest valve position, a heat demand counting as 100 % "
			+ "(valves at 80, 60, 20 and 10 %: 80 %). A single cold room is enough to request heat; "
			+ "right when every room has to get warm. \"Valves averaged\" does not apply.\n\n"
			+ "Mean of the highest: the mean of the \"Valves averaged\" highest positions (with 3: (80 + 60 + 20) / 3 = 53 %). "
			+ "A single room with a wide open valve, such as a bathroom with a high setpoint, then does not keep the heat source running on its own. "
			+ "With fewer values, the mean is taken over those there are."),
		Map.entry("topCount", "How many of the highest valve positions are averaged, 3, say; heat demands count as 100 %. "
			+ "Only with \"Demand level\" \"Mean of the highest\"."),
		Map.entry("onThreshold", "From this demand level on, the heat source is requested, 20 %, say. "
			+ "Between the \"Switch-off level\" and this one, the request stays as it is. Must be above the \"Switch-off level\"."),
		Map.entry("offThreshold", "When the demand level falls to this value, the request ends, 5 %, say. Must be below the \"Switch-on level\"."),
		Map.entry("minOn", "The shortest time the request stays on once switched on, 5 min, say, against short cycling of the burner or compressor; "
			+ "also applies when \"Heating on\" switches off or \"Summer\" comes. 0: no minimum."),
		Map.entry("minOff", "The shortest time the request stays off once switched off, 5 min, say. 0: no minimum."),
		Map.entry("pumpRunOn", "How long the \"Circulation pump\" keeps running after the request ends, 5 min, say, to carry away the heat left in the heat source. "
			+ "0: the pump stops with the request."),
		Map.entry("targetLevel", "The demand level the \"Flow setpoint offset\" aims at, 70 %, say: when the highest valve stays at 90 %, "
			+ "the flow temperature is barely enough and the offset grows; below the target it shrinks again."),
		Map.entry("offsetGain", "How fast the \"Flow setpoint offset\" changes, in kelvin per percent of deviation and hour: "
			+ "with 0.1, a demand level 20 % above the \"Target level\" raises the flow by 2 K per hour. "
			+ "0: the offset does not change (from 0 K: no offset)."),
		Map.entry("maxOffset", "The highest \"Flow setpoint offset\", 5 K, say. 0: no offset."),
		Map.entry("staleInput", "What a valve or request without a valid value (a room controller that no longer sends, say) counts for the demand level.\n\n"
			+ "Ignore: the input does not count; when all fail, the demand level is 0 % and the heat source is no longer requested. "
			+ "Right when failed rooms are not to keep the heat source running.\n\n"
			+ "Count as fully open: the input counts like a valve at 100 %; with \"Highest valve\", one failed room keeps the request on. "
			+ "Safe against cooling down and frost, but it costs energy until the fault is mended."));

	private HeatingDemandTexts() {
		// Constants.
	}

}
