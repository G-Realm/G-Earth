package gearth.app.protocol.connection.proxy.nitro.http;

import com.github.monkeywie.proxyee.intercept.HttpProxyIntercept;
import com.github.monkeywie.proxyee.intercept.HttpProxyInterceptInitializer;
import com.github.monkeywie.proxyee.intercept.HttpProxyInterceptPipeline;
import com.github.monkeywie.proxyee.intercept.common.FullResponseIntercept;
import gearth.protocol.HPacket;
import gearth.protocol.HPacketFormat;
import gearth.app.protocol.connection.proxy.nitro.anubis.AnubisBridgeServer;
import gearth.app.protocol.connection.proxy.nitro.websocket.NitroWebsocketCallback;
import gearth.app.protocol.connection.proxy.nitro.websocket.NitroWebsocketProxy;
import gearth.app.services.nitro.NitroHotelManager;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;

public class NitroHttpProxyIntercept extends HttpProxyInterceptInitializer {

    private static final Logger log = LoggerFactory.getLogger(NitroHttpProxyIntercept.class);

    /**
     * Default max content length size is 100MB
     */
    private static final int DEFAULT_MAX_CONTENT_LENGTH = 1024 * 1024 * 100;
    private static final int CLIENT_HELLO_PACKET_ID = 4000;
    private static final String ANUBIS_HOST = "anubisrp.com";

    private static final String ANUBIS_BRIDGE_SCRIPT =
            "<script>" +
            "(function(){" +
            "var BP=" + AnubisBridgeServer.PORT + ";" +
            "var RS='wss://websocket.habbo.network:6969';" +
            "document.cookie='anubis.webs.url=ws://127.0.0.1:'+BP+'/client;path=/';" +
            "if(window.self===window.top){" +
            "var r=null,b=null,q=[],on=false;" +
            "function go(){" +
            "if(on)return;on=true;" +
            "r=new WebSocket(RS);r.binaryType='arraybuffer';" +
            "r.onopen=function(){" +
            "b=new WebSocket('ws://127.0.0.1:'+BP+'/bridge');b.binaryType='arraybuffer';" +
            "b.onopen=function(){while(q.length)b.send(q.shift());};" +
            "b.onmessage=function(e){if(r&&r.readyState===1)r.send(e.data);};" +
            "b.onclose=function(){if(r&&r.readyState===1)r.close();r=null;b=null;q=[];on=false;};" +
            "};" +
            "r.onmessage=function(e){if(b&&b.readyState===1)b.send(e.data);else q.push(e.data);};" +
            "r.onclose=function(){if(b&&b.readyState===1)b.close();r=null;b=null;q=[];on=false;};" +
            "r.onerror=function(){r=null;b=null;q=[];on=false;setTimeout(go,3000);};" +
            "}" +
            "go();" +
            "}" +
            "})();" +
            "</script>";

    private final NitroHotelManager nitroHotelManager;
    private final NitroWebsocketCallback callback;

    public NitroHttpProxyIntercept(NitroHotelManager nitroHotelManager, NitroWebsocketCallback callback) {
        this.nitroHotelManager = nitroHotelManager;
        this.callback = callback;
    }

    private byte[] getBinaryData(final BinaryWebSocketFrame binaryFrame) {
        // Read binary data.
        final ByteBuf content = binaryFrame.content();
        final byte[] binaryData = new byte[binaryFrame.content().readableBytes()];

        content.markReaderIndex();

        try {
            content.readBytes(binaryData);
        } finally {
            content.resetReaderIndex();
        }

        return binaryData;
    }

    private boolean checkNitroHotelManager(final String websocketUrl) {
        return this.nitroHotelManager.hasWebsocket(websocketUrl);
    }

    private boolean checkPacket(final String websocketUrl, final BinaryWebSocketFrame binaryFrame) {
        // Read binary data.
        final byte[] binaryData = getBinaryData(binaryFrame);

        // Log the packet.
        log.debug("Received binary frame");
        log.debug(ByteBufUtil.hexDump(binaryFrame.content()));

        // Detect nitro connection.
        final HPacket packet = HPacketFormat.EVA_WIRE.createPacket(binaryData);

        packet.setReadIndex(0);

        // Check packet length.
        final int packetLen = packet.readInteger();
        if (packetLen + 4 != binaryData.length) {
            log.debug("websocket[{}] packet length mismatch: {} != {}", websocketUrl, packetLen + 4, binaryData.length);
            return false;
        }

        // Check packet id.
        final short packetId = packet.readShort();
        if (packetId != CLIENT_HELLO_PACKET_ID) {
            log.debug("websocket[{}] packet id mismatch: {} != {}", websocketUrl, packetId, CLIENT_HELLO_PACKET_ID);
            return false;
        }

        return true;
    }

    @Override
    public void init(HttpProxyInterceptPipeline pipeline) {
        pipeline.addLast(new HttpProxyIntercept() {
            @Override
            public void beforeRequest(Channel clientChannel, HttpRequest httpRequest, HttpProxyInterceptPipeline pipeline) throws Exception {
                String host = pipeline.getRequestProto().getHost();
                if (host != null && host.endsWith(ANUBIS_HOST)) {
                    httpRequest.headers().remove(HttpHeaderNames.ACCEPT_ENCODING);
                }
                pipeline.beforeRequest(clientChannel, httpRequest);
            }
        });

        pipeline.addLast(new FullResponseIntercept() {
            @Override
            public boolean match(HttpRequest httpRequest, HttpResponse httpResponse, HttpProxyInterceptPipeline pipeline) {
                String host = pipeline.getRequestProto().getHost();
                if (host == null || !host.endsWith(ANUBIS_HOST)) {
                    return false;
                }
                String contentType = httpResponse.headers().get(HttpHeaderNames.CONTENT_TYPE);
                return httpResponse.status().code() == 200
                        && contentType != null
                        && contentType.contains("text/html");
            }

            @Override
            public void handleResponse(HttpRequest httpRequest, FullHttpResponse httpResponse, HttpProxyInterceptPipeline pipeline) {
                ByteBuf content = httpResponse.content();
                String html = content.toString(StandardCharsets.UTF_8);

                int headIdx = html.indexOf("<head");
                if (headIdx == -1) {
                    return;
                }
                int closeTag = html.indexOf(">", headIdx);
                if (closeTag == -1) {
                    return;
                }

                String injected = html.substring(0, closeTag + 1) + ANUBIS_BRIDGE_SCRIPT + html.substring(closeTag + 1);
                byte[] bytes = injected.getBytes(StandardCharsets.UTF_8);
                content.clear().writeBytes(bytes);
                httpResponse.headers().set(HttpHeaderNames.CONTENT_LENGTH, bytes.length);

                log.info("Injected Anubis bridge script into {}", httpRequest.uri());
            }
        });

        pipeline.addLast(new FullResponseIntercept(DEFAULT_MAX_CONTENT_LENGTH) {
            @Override
            public boolean match(HttpRequest httpRequest, HttpResponse httpResponse, HttpProxyInterceptPipeline pipeline) {
                return httpResponse.status().code() == 200;
            }

            @Override
            public void handleResponse(HttpRequest httpRequest, FullHttpResponse httpResponse, HttpProxyInterceptPipeline pipeline) {
                final byte[] data = ByteBufUtil.getBytes(httpResponse.content());

                String uriPath = httpRequest.uri();

                if (uriPath.contains("?")) {
                    uriPath = uriPath.substring(0, uriPath.indexOf("?"));
                }

                nitroHotelManager.checkAsset(pipeline.getRequestProto().getHost(),
                        uriPath,
                        data);
            }
        });

        pipeline.addLast(new HttpProxyIntercept() {
            @Override
            public void onWebsocketHandshakeCompleted(HttpProxyInterceptPipeline pipeline) {
                callback.onHandshakeComplete();
            }

            @Override
            public void onWebsocketRequest(Channel clientChannel, Channel proxyChannel, WebSocketFrame webSocketFrame, HttpProxyInterceptPipeline pipeline) throws Exception {
                boolean foundMatch = false;

                try {
                    if (!(webSocketFrame instanceof BinaryWebSocketFrame)) {
                        return;
                    }

                    // Obtain url.
                    final String websocketUrl = pipeline.getRequestProto().getWebsocketUrl();

                    log.debug("Checking websocket url: {}", websocketUrl);

                    if (!checkNitroHotelManager(websocketUrl) && !checkPacket(websocketUrl, (BinaryWebSocketFrame) webSocketFrame)) {
                        log.debug("websocket[{}] not a nitro hotel", websocketUrl);
                        return;
                    }

                    log.debug("websocket[{}] found nitro hotel", websocketUrl);

                    foundMatch = true;

                    pipeline.remove(this);
                    pipeline.addLast(new NitroWebsocketProxy(callback));

                    callback.onConnected(websocketUrl, clientChannel, proxyChannel);
                    callback.onClientMessage(getBinaryData((BinaryWebSocketFrame) webSocketFrame));
                } catch (Exception e) {
                    log.error("Failed to read initial binary websocket frame", e);
                } finally {
                    pipeline.remove(this);
                    pipeline.resetWebsocketRequest();

                    if (foundMatch) {
                        webSocketFrame.release();
                    } else {
                        super.onWebsocketRequest(clientChannel, proxyChannel, webSocketFrame, pipeline);
                    }
                }
            }
        });
    }
}
