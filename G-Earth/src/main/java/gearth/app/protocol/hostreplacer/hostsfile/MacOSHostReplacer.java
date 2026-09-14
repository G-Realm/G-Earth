package gearth.app.protocol.hostreplacer.hostsfile;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.regex.Pattern;

final class MacOSHostReplacer implements HostReplacer {

    private static final Logger LOG = LoggerFactory.getLogger(MacOSHostReplacer.class);
    private static final Pattern HOSTNAME = Pattern.compile("[A-Za-z0-9._-]+");

    private final HostHelper helper;

    MacOSHostReplacer() {
        this(new MacOSPrivilegedHostHelper());
    }

    MacOSHostReplacer(HostHelper helper) {
        this.helper = helper;
    }

    @Override
    public synchronized boolean addRedirect(String[] lines) {
        if (!validRedirects(lines)) {
            LOG.error("Refusing invalid macOS hosts-file redirects");
            return false;
        }
        return helper.apply(lines);
    }

    @Override
    public synchronized boolean removeRedirect(String[] lines) {
        return helper.remove();
    }

    private static boolean validRedirects(String[] lines) {
        if (lines == null || lines.length == 0 || lines.length > 64) {
            return false;
        }
        for (String line : lines) {
            if (line == null || line.indexOf('\n') >= 0 || line.indexOf('\r') >= 0) {
                return false;
            }
            final String[] fields = line.trim().split("\\s+");
            if (fields.length != 2 || !isLoopbackAddress(fields[0]) || !HOSTNAME.matcher(fields[1]).matches()) {
                return false;
            }
        }
        return true;
    }

    private static boolean isLoopbackAddress(String address) {
        final String[] octets = address.split("\\.", -1);
        if (octets.length != 4 || !octets[0].equals("127")) {
            return false;
        }
        for (String octet : octets) {
            try {
                if (octet.isEmpty() || !octet.chars().allMatch(Character::isDigit)
                        || Integer.parseInt(octet) > 255) {
                    return false;
                }
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return true;
    }

    interface HostHelper {
        boolean apply(String[] lines);

        boolean remove();
    }
}
