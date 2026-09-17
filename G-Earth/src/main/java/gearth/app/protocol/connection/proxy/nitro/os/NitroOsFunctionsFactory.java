package gearth.app.protocol.connection.proxy.nitro.os;

import gearth.app.misc.OSValidator;
import gearth.app.protocol.connection.proxy.nitro.os.linux.DesktopEnvironment;
import gearth.app.protocol.connection.proxy.nitro.os.linux.NitroGnomeLinux;
import gearth.app.protocol.connection.proxy.nitro.os.linux.NitroKdeLinux;
import gearth.app.protocol.connection.proxy.nitro.os.macos.NitroMacOS;
import gearth.app.protocol.connection.proxy.nitro.os.windows.NitroWindows;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class NitroOsFunctionsFactory {
    private static final Logger log = LoggerFactory.getLogger(NitroOsFunctionsFactory.class);

    public static NitroOsFunctions create() {
        if (OSValidator.isWindows()) {
            return new NitroWindows();
        }

        if (OSValidator.isUnix()) {
            DesktopEnvironment de = DesktopEnvironment.detectDesktopEnvironment();
            log.info("Detected Desktop Environment: {}", de);
            return switch (de) {
                case GNOME -> new NitroGnomeLinux();
                case KDE -> new NitroKdeLinux();
                default -> throw new UnsupportedOperationException("Unix nitro is not implemented yet for your desktop environment");
            };
        }

        if (OSValidator.isMac()) {
            return new NitroMacOS();
        }

        throw new UnsupportedOperationException("unsupported operating system");
    }
}
