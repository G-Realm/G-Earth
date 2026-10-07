package gearth.app.services.nitro.hotels;

import gearth.app.services.nitro.NitroHotel;
import gearth.app.services.nitro.NitroPacketModifier;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class ConfiguredNitroHotel extends NitroHotel {

    private static final Logger LOG = LoggerFactory.getLogger(ConfiguredNitroHotel.class);
    private static final String CONFIG_FILENAME = "nitro.json";

    private ConfiguredNitroHotel(String name, List<String> websocketUrls) {
        super(name, websocketUrls, List.of());
    }

    public static List<ConfiguredNitroHotel> load() {
        final Path configPath;

        try {
            final String overrideDataDir = System.getProperty("gearth.data.dir");
            final Path appRoot = overrideDataDir == null ? Path.of("") : Path.of(overrideDataDir);
            configPath = appRoot.resolve(CONFIG_FILENAME).toAbsolutePath().normalize();
        } catch (Exception e) {
            LOG.error("Failed to locate {}", CONFIG_FILENAME, e);
            return List.of();
        }

        if (!Files.isRegularFile(configPath)) {
            return List.of();
        }

        try {
            final JSONArray configurations = new JSONArray(
                    Files.readString(configPath, StandardCharsets.UTF_8));
            final List<ConfiguredNitroHotel> hotels = new ArrayList<>(configurations.length());

            for (int i = 0; i < configurations.length(); i++) {
                hotels.add(fromJson(configurations.getJSONObject(i), i));
            }

            LOG.info("Loaded {} Nitro hotel configuration(s) from {}", hotels.size(), configPath);
            return List.copyOf(hotels);
        } catch (Exception e) {
            LOG.error("Failed to load {}", configPath, e);
            return List.of();
        }
    }

    private static ConfiguredNitroHotel fromJson(JSONObject config, int index) {
        final JSONArray configuredUrls = config.getJSONArray("websocketUrls");
        final List<String> websocketUrls = new ArrayList<>(configuredUrls.length());

        for (int i = 0; i < configuredUrls.length(); i++) {
            final String websocketUrl = configuredUrls.getString(i).trim();
            if (websocketUrl.isEmpty()) {
                throw new IllegalArgumentException("websocketUrls cannot contain empty values");
            }
            websocketUrls.add(websocketUrl);
        }

        if (websocketUrls.isEmpty()) {
            throw new IllegalArgumentException("websocketUrls must contain at least one URL");
        }

        final String defaultName = CONFIG_FILENAME + "[" + index + "]";
        final String configuredName = config.optString("name", defaultName).trim();
        final String name = configuredName.isEmpty() ? defaultName : configuredName;
        final List<String> configuredWebsocketUrls = List.copyOf(websocketUrls);
        LOG.info("Loaded dynamic Nitro hotel '{}' with websocket URL(s): {}",
                name, configuredWebsocketUrls);
        return new ConfiguredNitroHotel(name, configuredWebsocketUrls);
    }

    @Override
    public NitroPacketModifier createPacketModifier(String websocketUrl) {
        return null;
    }

    @Override
    protected void loadAsset(String host, String uri, byte[] data) {
    }
}
