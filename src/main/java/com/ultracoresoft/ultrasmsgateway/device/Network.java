package com.ultracoresoft.ultrasmsgateway.device;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The mobile networks of the DR Congo, recognised on a recipient's number (its prefix after +243) and on a phone
 * (the operator name its SIM reports). A number kept when changing operator keeps its old prefix: routing then
 * picks the wrong network, and the message still goes out.
 */
public enum Network {
    VODACOM, ORANGE, AIRTEL, AFRICELL;

    private static final Map<String, Network> PREFIXES = Map.ofEntries(
            Map.entry("81", VODACOM), Map.entry("82", VODACOM), Map.entry("83", VODACOM),
            Map.entry("84", ORANGE), Map.entry("85", ORANGE), Map.entry("89", ORANGE),
            Map.entry("97", AIRTEL), Map.entry("98", AIRTEL), Map.entry("99", AIRTEL),
            Map.entry("90", AFRICELL));

    /** The network of a number in E.164 form (+243 81…); empty for other countries and unknown prefixes. */
    public static Optional<Network> ofRecipient(String number) {
        if (number == null || !number.startsWith("+243") || number.length() < 6) {
            return Optional.empty();
        }
        return Optional.ofNullable(PREFIXES.get(number.substring(4, 6)));
    }

    /** The network of a phone, from the operator name of its SIM ("Vodacom CD", "Airtel"…). */
    public static Optional<Network> ofOperator(String operatorName) {
        if (operatorName == null) {
            return Optional.empty();
        }
        String name = operatorName.toLowerCase(Locale.ROOT);
        for (Network network : values()) {
            if (name.contains(network.name().toLowerCase(Locale.ROOT))) {
                return Optional.of(network);
            }
        }
        return Optional.empty();
    }

    /** The prefixes of the network, for the routing page (81, 82, 83). */
    public java.util.List<String> prefixes() {
        return PREFIXES.entrySet().stream().filter(e -> e.getValue() == this).map(Map.Entry::getKey).sorted().toList();
    }

    /** As people write it: Vodacom, Orange, Airtel, Africell. */
    public String label() {
        return name().charAt(0) + name().substring(1).toLowerCase(Locale.ROOT);
    }
}
