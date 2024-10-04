package gearth.protocol.memory.habboclient;

import gearth.misc.OSValidator;
import gearth.protocol.HConnection;
import gearth.protocol.memory.habboclient.linux.LinuxHabboClient;
import gearth.protocol.memory.habboclient.external.LinuxMemoryClient;
import gearth.protocol.memory.habboclient.external.MemoryClient;

/**
 * Created by Jonas on 13/06/18.
 */
public class HabboClientFactory {

    public static HabboClient get(HConnection connection) {
        if (OSValidator.isUnix()) {
            return new LinuxMemoryClient(connection);
            // return new LinuxHabboClient(connection);
        }

        return new MemoryClient(connection);
    }

}
