package gearth.app.protocol.connection.proxy.nitro.anubis;

import gearth.app.protocol.connection.proxy.nitro.NitroConstants;
import gearth.app.protocol.connection.proxy.nitro.websocket.NitroWebsocketCallback;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.websocketx.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;

public class AnubisBridgeServer {

    private static final Logger log = LoggerFactory.getLogger(AnubisBridgeServer.class);
    public static final int PORT = 2095;

    private final NitroWebsocketCallback callback;
    private final AtomicBoolean connected = new AtomicBoolean(false);

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;
    private Channel clientChannel;
    private Channel bridgeChannel;

    public AnubisBridgeServer(NitroWebsocketCallback callback) {
        this.callback = callback;
    }

    public boolean start() {
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup(2);

        try {
            ServerBootstrap b = new ServerBootstrap();
            b.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ch.pipeline().addLast(new HttpServerCodec());
                            ch.pipeline().addLast(new HttpObjectAggregator(NitroConstants.WEBSOCKET_BUFFER_SIZE));
                            ch.pipeline().addLast(new WebSocketFrameAggregator(NitroConstants.WEBSOCKET_BUFFER_SIZE));
                            ch.pipeline().addLast(new BridgeWsHandler());
                        }
                    });

            serverChannel = b.bind(PORT).sync().channel();
            log.info("AnubisBridge started on port {}", PORT);
            return true;
        } catch (Exception e) {
            log.error("Failed to start AnubisBridge", e);
            stop();
            return false;
        }
    }

    public void stop() {
        connected.set(false);
        if (clientChannel != null) { clientChannel.close(); clientChannel = null; }
        if (bridgeChannel != null) { bridgeChannel.close(); bridgeChannel = null; }
        if (serverChannel != null) { serverChannel.close(); serverChannel = null; }
        if (workerGroup != null) { workerGroup.shutdownGracefully(); workerGroup = null; }
        if (bossGroup != null) { bossGroup.shutdownGracefully(); bossGroup = null; }
    }

    private void tryNotifyConnected() {
        if (clientChannel != null && clientChannel.isActive()
                && bridgeChannel != null && bridgeChannel.isActive()
                && connected.compareAndSet(false, true)) {
            callback.onConnected("wss://websocket.habbo.network:6969", clientChannel, bridgeChannel);
            callback.onHandshakeComplete();
        }
    }

    private class BridgeWsHandler extends SimpleChannelInboundHandler<Object> {

        private WebSocketServerHandshaker handshaker;
        private String path;

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, Object msg) {
            if (msg instanceof FullHttpRequest req) {
                handleHttpRequest(ctx, req);
            } else if (msg instanceof WebSocketFrame frame) {
                handleWebSocketFrame(ctx, frame);
            }
        }

        private void handleHttpRequest(ChannelHandlerContext ctx, FullHttpRequest req) {
            path = req.uri().split("\\?")[0];

            if (!"/client".equals(path) && !"/bridge".equals(path)) {
                ctx.close();
                return;
            }

            WebSocketServerHandshakerFactory factory = new WebSocketServerHandshakerFactory(
                    "ws://127.0.0.1:" + PORT + path, null, true, NitroConstants.WEBSOCKET_BUFFER_SIZE);
            handshaker = factory.newHandshaker(req);

            if (handshaker == null) {
                WebSocketServerHandshakerFactory.sendUnsupportedVersionResponse(ctx.channel());
                return;
            }

            handshaker.handshake(ctx.channel(), req);

            if ("/client".equals(path)) {
                clientChannel = ctx.channel();
            } else {
                bridgeChannel = ctx.channel();
            }
            tryNotifyConnected();
        }

        private void handleWebSocketFrame(ChannelHandlerContext ctx, WebSocketFrame frame) {
            if (frame instanceof CloseWebSocketFrame) {
                if (handshaker != null) handshaker.close(ctx.channel(), (CloseWebSocketFrame) frame.retain());
                if (connected.get()) callback.onClose();
                return;
            }
            if (frame instanceof PingWebSocketFrame) {
                ctx.writeAndFlush(new PongWebSocketFrame(frame.content().retain()));
                return;
            }
            if (!(frame instanceof BinaryWebSocketFrame)) return;

            byte[] data = ByteBufUtil.getBytes(frame.content());
            if ("/client".equals(path)) callback.onClientMessage(data);
            else if ("/bridge".equals(path)) callback.onServerMessage(data);
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            if (connected.get()) callback.onClose();
            super.channelInactive(ctx);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            ctx.close();
        }
    }
}
