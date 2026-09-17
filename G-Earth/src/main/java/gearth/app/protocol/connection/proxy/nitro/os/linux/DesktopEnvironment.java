package gearth.app.protocol.connection.proxy.nitro.os.linux;

import java.util.Locale;

public enum DesktopEnvironment {
    GNOME,
    KDE,
    UNKNOWN;

    public static DesktopEnvironment detectDesktopEnvironment() {
        String xdg = System.getenv("XDG_CURRENT_DESKTOP");
        String desktopSession = System.getenv("DESKTOP_SESSION");

        String current = (xdg != null ? xdg : (desktopSession != null ? desktopSession : "")).toUpperCase(Locale.ROOT);

        if (current.contains("GNOME") || current.contains("UNITY") || current.contains("CINNAMON") || current.contains("MATE") || current.contains("BUDGIE")) {
            return DesktopEnvironment.GNOME;
        } else if (current.contains("KDE") || current.contains("PLASMA")) {
            return DesktopEnvironment.KDE;
        }
        return DesktopEnvironment.UNKNOWN;
    }
}
