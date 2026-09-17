package gearth.app.protocol.connection.proxy.nitro.os.linux;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Arrays;

public class NitroGnomeLinux extends NitroLinux {
    private static final Logger log = LoggerFactory.getLogger(NitroGnomeLinux.class);

    @Override
    public boolean registerSystemProxy(String host, int port) {
        try {
            runCommand("gsettings", "set", "org.gnome.system.proxy", "mode", "'manual'");
            runCommand("gsettings", "set", "org.gnome.system.proxy.http", "host", "'" + host + "'");
            runCommand("gsettings", "set", "org.gnome.system.proxy.http", "port", String.valueOf(port));
            runCommand("gsettings", "set", "org.gnome.system.proxy.https", "host", "'" + host + "'");
            runCommand("gsettings", "set", "org.gnome.system.proxy.https", "port", String.valueOf(port));

            String ignoreFormatted = "[" + String.join(", ", Arrays.stream(PROXY_IGNORE_LIST)
                    .map(h -> "'" + h + "'")
                    .toArray(String[]::new)) + "]";
            runCommand("gsettings", "set", "org.gnome.system.proxy", "ignore-hosts", ignoreFormatted);
            return true;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.error("Failed to register GNOME proxy", e);
            return false;
        }
    }

    @Override
    public boolean unregisterSystemProxy() {
        try {
            CommandResult result = runCommand("gsettings", "set", "org.gnome.system.proxy", "mode", "'none'");
            return result.exitCode() == 0;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.error("Failed to unregister GNOME proxy", e);
            return false;
        }
    }
}
