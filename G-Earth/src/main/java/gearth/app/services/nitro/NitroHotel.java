package gearth.app.services.nitro;

import java.util.List;

public abstract class NitroHotel {

    private final String name;
    private final List<String> websocketUrls;
    private final List<NitroAsset> assetWhitelist;

    public NitroHotel(final String name, final List<String> websocketUrls, final List<NitroAsset> assetWhitelist) {
        this.name = name;
        this.websocketUrls = websocketUrls;
        this.assetWhitelist = assetWhitelist;
    }

    public String getName() {
        return name;
    }

    public boolean skipWebsocket(String websocketUrl) {
        return false;
    }

    public boolean hasWebsocket(final String websocketUrl) {
        for (final String url : websocketUrls) {
            if (url.endsWith("*")) {
                final String prefix = url.substring(0, url.length() - 1);
                if (websocketUrl.startsWith(prefix)) {
                    return true;
                }
            } else if (websocketUrl.equals(url)) {
                return true;
            }
        }

        return false;
    }

    public void checkAsset(final String host, final String uri, final byte[] data) {
        for (NitroAsset asset : assetWhitelist) {
            if (asset.matches(host, uri)) {
                loadAsset(host, uri, data);
                return;
            }
        }
    }

    public boolean isInitialFrame(String websocketUrl, final byte[] data) {
        return true;
    }

    /**
     * Retrieve a packet handler for this hotel.
     * @return Return a new instance of a packet handler, or null for the default packet handler.
     */
    public abstract NitroPacketModifier createPacketModifier(String websocketUrl);

    /**
     * Proxy loaded an asset for this hotel.
     *
     * @param host The host.
     * @param uri The uri path.
     * @param data The data of the asset.
     */
    protected abstract void loadAsset(final String host, final String uri, final byte[] data);
}
