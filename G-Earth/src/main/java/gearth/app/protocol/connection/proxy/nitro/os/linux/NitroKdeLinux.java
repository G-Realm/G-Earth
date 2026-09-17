package gearth.app.protocol.connection.proxy.nitro.os.linux;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

public class NitroKdeLinux extends NitroLinux {
    private static final Logger log = LoggerFactory.getLogger(NitroKdeLinux.class);

    @Override
    public boolean registerSystemProxy(String host, int port) {
        try {
            String kwrite = commandExists("kwriteconfig6") ? "kwriteconfig6" : "kwriteconfig5";

            // Mode 1 = Manual Proxy in KDE
            runCommand(kwrite, "--file", "kioslaverc", "--group", "Proxy Settings", "--key", "ProxyType", "1");
            runCommand(kwrite, "--file", "kioslaverc", "--group", "Proxy Settings", "--key", "httpProxy", String.format("http://%s:%d", host, port));
            runCommand(kwrite, "--file", "kioslaverc", "--group", "Proxy Settings", "--key", "httpsProxy", String.format("http://%s:%d", host, port));
            runCommand(kwrite, "--file", "kioslaverc", "--group", "Proxy Settings", "--key", "NoProxyFor", String.join(",", PROXY_IGNORE_LIST));

            // Notify KDE apps to reload configuration
            runCommand("dbus-send", "--type=signal", "/KIO/Scheduler", "org.kde.KIO.Scheduler.reparseSlaveConfiguration", "string:''");
            return true;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.error("Failed to register KDE proxy", e);
            return false;
        }
    }

    @Override
    public boolean unregisterSystemProxy() {
        try {
            String kwrite = commandExists("kwriteconfig6") ? "kwriteconfig6" : "kwriteconfig5";

            // Mode 0 = No Proxy in KDE
            runCommand(kwrite, "--file", "kioslaverc", "--group", "Proxy Settings", "--key", "ProxyType", "0");

            // Notify KDE apps to reload configuration
            runCommand("dbus-send", "--type=signal", "/KIO/Scheduler", "org.kde.KIO.Scheduler.reparseSlaveConfiguration", "string:''");
            return true;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.error("Failed to unregister KDE proxy", e);
            return false;
        }
    }
}
