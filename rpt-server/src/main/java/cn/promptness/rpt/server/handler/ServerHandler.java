package cn.promptness.rpt.server.handler;

import cn.promptness.rpt.base.config.ClientConfig;
import cn.promptness.rpt.base.config.Config;
import cn.promptness.rpt.base.config.RemoteConfig;
import cn.promptness.rpt.base.handler.ByteIdleCheckHandler;
import cn.promptness.rpt.base.protocol.Message;
import cn.promptness.rpt.base.protocol.MessageType;
import cn.promptness.rpt.base.protocol.ProxyType;
import cn.promptness.rpt.base.utils.StringUtils;
import cn.promptness.rpt.server.cache.ServerChannelCache;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.bytes.ByteArrayDecoder;
import io.netty.handler.codec.bytes.ByteArrayEncoder;
import io.netty.handler.stream.ChunkedWriteHandler;
import io.netty.handler.traffic.GlobalTrafficShapingHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 处理服务器接收到的客户端连接
 */
public class ServerHandler extends SimpleChannelInboundHandler<Message> {

    private static final Logger logger = LoggerFactory.getLogger(ServerHandler.class);
    /**
     * remoteChannelId --> remoteChannel
     */
    private final Map<String, Channel> remoteChannelMap = new ConcurrentHashMap<>();
    private final EventLoopGroup remoteBossGroup = new NioEventLoopGroup();
    private final EventLoopGroup remoteWorkerGroup = new NioEventLoopGroup();
    private final List<String> domainList = new CopyOnWriteArrayList<>();

    /**
     * 全局服务器读限速器
     */
    private final GlobalTrafficShapingHandler globalTrafficShapingHandler;
    private String clientKey;

    public ServerHandler(GlobalTrafficShapingHandler globalTrafficShapingHandler) {
        this.globalTrafficShapingHandler = globalTrafficShapingHandler;
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        ctx.channel().close();
    }

    /**
     * 连接中断
     */
    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        logger.info("服务端-客户端连接中断,{}", clientKey == null ? "未知连接" : clientKey);
        for (Channel channel : remoteChannelMap.values()) {
            channel.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
        }
        remoteChannelMap.clear();
        // 取消监听的端口 否则第二次连接时无法再次绑定端口
        remoteBossGroup.shutdownGracefully();
        remoteWorkerGroup.shutdownGracefully();

        for (String domain : domainList) {
            Channel remove = ServerChannelCache.getServerDomainChannelMap().remove(domain);
            if (remove != null) {
                remove.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
            }
        }
        domainList.clear();
    }

    @Override
    protected void channelRead0(ChannelHandlerContext context, Message message) throws Exception {
        MessageType type = message.getType();
        switch (type) {
            case TYPE_REGISTER:
                register(context, message);
                break;
            case TYPE_DATA:
                transfer(message);
                break;
            case TYPE_CONNECTED:
                connected(message);
                break;
            case TYPE_DISCONNECTED:
                disconnected(message);
                break;
            case TYPE_KEEPALIVE:
            default:
        }
    }

    private void connected(Message message) {
        ClientConfig clientConfig = message.getClientConfig();
        RemoteConfig remoteConfig = clientConfig.getConfig().get(0);
        ProxyType proxyType = remoteConfig.getProxyType();
        if (proxyType == null) {
            return;
        }
        String channelId = message.getClientConfig().getChannelId();

        switch (proxyType) {
            case TCP:
                Channel remoteChannel = remoteChannelMap.get(channelId);
                if (remoteChannel != null) {
                    remoteChannel.config().setAutoRead(true);
                }
                break;
            case HTTP:
                Channel httpChannel = ServerChannelCache.getServerHttpChannelMap().get(channelId);
                if (httpChannel != null) {
                    httpChannel.pipeline().fireUserEventTriggered(ProxyType.HTTP);
                }
                break;
            default:
        }
    }

    private void disconnected(Message message) {
        ClientConfig clientConfig = message.getClientConfig();
        RemoteConfig remoteConfig = message.getClientConfig().getConfig().get(0);
        ProxyType proxyType = remoteConfig.getProxyType();
        if (proxyType == null) {
            return;
        }
        switch (proxyType) {
            case TCP:
                Channel remoteChannel = remoteChannelMap.remove(clientConfig.getChannelId());
                if (remoteChannel == null) {
                    return;
                }
                // 数据发送完成后再关闭连 解决http1.0数据传输问题
                remoteChannel.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
                break;
            case HTTP:
                Channel httpChannel = ServerChannelCache.getServerHttpChannelMap().remove(clientConfig.getChannelId());
                if (httpChannel == null) {
                    return;
                }
                httpChannel.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
                break;
            default:
        }
    }


    private void transfer(Message message) {
        RemoteConfig remoteConfig = message.getClientConfig().getConfig().get(0);
        ProxyType proxyType = remoteConfig.getProxyType();
        if (proxyType == null) {
            return;
        }
        switch (proxyType) {
            case TCP:
                transferTcp(message);
                break;
            case HTTP:
                String channelId = message.getClientConfig().getChannelId();
                Channel channel = ServerChannelCache.getServerHttpChannelMap().get(channelId);
                if (channel != null) {
                    channel.writeAndFlush(message.getData());
                }
                break;
            default:
        }
    }

    private void transferTcp(Message message) {
        ClientConfig clientConfig = message.getClientConfig();
        Channel channel = remoteChannelMap.get(clientConfig.getChannelId());
        if (channel == null) {
            return;
        }
        channel.writeAndFlush(message.getData());
    }

    private void register(ChannelHandlerContext context, Message message) {
        ClientConfig clientConfig = message.getClientConfig();
        clientKey = clientConfig.getClientKey();
        if (!Config.getServerConfig().getClientKey().contains(clientKey)) {
            logger.info("授权连接失败,clientKey:{}", clientKey);
            Message res = new Message();
            res.setType(MessageType.TYPE_AUTH);
            res.setClientConfig(clientConfig.setConnection(false));
            context.writeAndFlush(res);
            return;
        }
        List<String> remoteResult = new ArrayList<>();
        for (RemoteConfig remoteConfig : clientConfig.getConfig()) {
            ProxyType proxyType = remoteConfig.getProxyType();
            if (proxyType == null) {
                Message res = new Message();
                res.setType(MessageType.TYPE_AUTH);
                res.setClientConfig(clientConfig.setConnection(false));
                context.writeAndFlush(res);
                return;
            }
            switch (proxyType) {
                case TCP:
                    registerTcp(context, remoteResult, remoteConfig);
                    break;
                case HTTP:
                    registerHttp(context, remoteResult, remoteConfig);
                    break;
                default:
            }
        }
        Message res = new Message();
        res.setType(MessageType.TYPE_AUTH);
        res.setClientConfig(clientConfig.setConnection(true).setRemoteResult(remoteResult));
        context.writeAndFlush(res);
    }


    private void registerHttp(ChannelHandlerContext context, List<String> remoteResult, RemoteConfig remoteConfig) {
        if (!StringUtils.hasText(remoteConfig.getDomain())) {
            remoteResult.add(String.format("需要绑定域名[%s]不合法", remoteConfig.getDomain()));
            return;
        }
        logger.info("服务端开始绑定域名[{}]", remoteConfig.getDomain());
        ServerChannelCache.getServerDomainChannelMap().compute(remoteConfig.getDomain(), (domain, channel) -> {
            if (channel != null) {
                remoteResult.add(String.format("服务端绑定域名重复%s", domain));
                return channel;
            }
            domainList.add(domain);
            remoteResult.add(String.format("服务端绑定域名成功%s", domain));
            return context.channel();
        });
    }

    private void registerTcp(ChannelHandlerContext context, List<String> remoteResult, RemoteConfig remoteConfig) {
        if (remoteConfig.getRemotePort() == 0 || remoteConfig.getRemotePort() == Config.getServerConfig().getServerPort() || remoteConfig.getRemotePort() == Config.getServerConfig().getHttpPort()) {
            remoteResult.add(String.format("需要绑定的端口[%s]不合法", remoteConfig.getRemotePort()));
            return;
        }
        ServerBootstrap remoteBootstrap = new ServerBootstrap();
        remoteBootstrap.group(remoteBossGroup, remoteWorkerGroup).channel(NioServerSocketChannel.class).childOption(ChannelOption.SO_KEEPALIVE, true)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    public void initChannel(SocketChannel channel) throws Exception {
                        channel.pipeline().addLast(globalTrafficShapingHandler);
                        channel.pipeline().addLast(new ByteArrayDecoder());
                        channel.pipeline().addLast(new ByteArrayEncoder());
                        channel.pipeline().addLast(new ChunkedWriteHandler());
                        channel.pipeline().addLast(new ByteIdleCheckHandler(30, 20, 0));
                        channel.pipeline().addLast(new RemoteHandler(context.channel(), remoteConfig));
                        remoteChannelMap.put(channel.id().asLongText(), channel);
                    }
                });
        try {
            logger.info("服务端开始建立本地端口绑定[{}]", remoteConfig.getRemotePort());
            remoteBootstrap.bind(Config.getServerConfig().getServerIp(), remoteConfig.getRemotePort()).get();
            remoteResult.add(String.format("服务端绑定端口[%s]成功", remoteConfig.getRemotePort()));
        } catch (Exception exception) {
            logger.info("服务端失败建立本地端口绑定[{}], {}", remoteConfig.getRemotePort(), exception.getCause().getMessage());
            remoteResult.add(String.format("服务端绑定端口[%s]失败,原因:%s", remoteConfig.getRemotePort(), exception.getCause().getMessage()));
        }
    }
}
