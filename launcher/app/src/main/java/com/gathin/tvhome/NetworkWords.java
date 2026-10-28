package com.gathin.tvhome;

/**
 * The Wi-Fi dropdown's wording, chosen without Android so it is tested on the JVM: which signal word goes with a
 * level, and which state line ("Connected · Internet OK · Strong signal") goes with a connection.
 */
final class NetworkWords {
    private NetworkWords() {}

    enum Signal { STRONG, FAIR, WEAK, NONE }

    enum State { OFFLINE, NO_INTERNET, ONLINE }

    /** WifiManager.calculateSignalLevel(rssi, 5) gives 0 to 4. */
    static Signal signal(int level) {
        if (level < 0) return Signal.NONE;
        if (level >= 3) return Signal.STRONG;
        if (level == 2) return Signal.FAIR;
        return Signal.WEAK;
    }

    static State state(boolean online, boolean validated) {
        if (!online) return State.OFFLINE;
        return validated ? State.ONLINE : State.NO_INTERNET;
    }

    /** The SSID WifiInfo reports, without its quotes; null for the placeholders firmware returns when it can't tell. */
    static String ssid(String raw) {
        if (raw == null) return null;
        String s = raw.replace("\"", "").trim();
        if (s.isEmpty() || s.contains("unknown") || s.equals("0x")) return null;
        return s;
    }
}
