package cn.promptness.rpt.server.handler;

import cn.promptness.rpt.base.executor.MessageDispatcher;
import cn.promptness.rpt.base.protocol.Message;
import cn.promptness.rpt.base.utils.Constants;
import cn.promptness.rpt.server.cache.ServerChannelCache;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.util.concurrent.AbstractEventExecutorGroup;
import io.netty.util.internal.EmptyArrays;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 处理服务器接收到的客户端连接
 */
public class ServerHandler extends SimpleChannelInboundHandler<Message> {

    private static final Logger logger = LoggerFactory.getLogger(ServerHandler.class);

    private static final MessageDispatcher MESSAGE_DISPATCHER = new MessageDispatcher();

    @Override
    public void channelWritabilityChanged(ChannelHandlerContext ctx) throws Exception {
        Channel localChannel = ctx.channel().attr(Constants.LOCAL).get();
        if (Objects.nonNull(localChannel)) {
            localChannel.config().setAutoRead(ctx.channel().isWritable());
        }
        super.channelWritabilityChanged(ctx);
    }

    @Override
    protected void channelRead0(ChannelHandlerContext context, Message message) throws Exception {
        MESSAGE_DISPATCHER.handle(context, message);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        ctx.close();
    }

    /**
     * 连接中断
     */
    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        String clientKey = ctx.channel().attr(Constants.Server.CLIENT_KEY).getAndSet(null);
        Class<Void> label = ctx.channel().attr(Constants.Server.LABEL).getAndSet(null);
        // 代理连接/未知连接
        if (Objects.isNull(clientKey)) {
            logger.info("服务端-客户端{}连接中断", label == null ? "未知" : "代理");
            Channel localChannel = ctx.channel().attr(Constants.LOCAL).get();
            if (Objects.nonNull(localChannel) && localChannel.isActive()) {
                localChannel.writeAndFlush(EmptyArrays.EMPTY_BYTES).addListener(ChannelFutureListener.CLOSE);
            }
            return;
        }
        logger.info("服务端-客户端连接中断,{}", clientKey);
        Optional.ofNullable(ctx.channel().attr(Constants.CHANNELS).getAndSet(null)).ifPresent(this::clear);
        Optional.ofNullable(ctx.channel().attr(Constants.Server.DOMAIN).getAndSet(null)).ifPresent(ServerChannelCache::remove);
        Optional.ofNullable(ctx.channel().attr(Constants.Server.REMOTE_BOSS_GROUP).getAndSet(null)).ifPresent(AbstractEventExecutorGroup::shutdownGracefully);
        Optional.ofNullable(ctx.channel().attr(Constants.Server.REMOTE_WORKER_GROUP).getAndSet(null)).ifPresent(AbstractEventExecutorGroup::shutdownGracefully);
    }

    private void clear(Map<String, Channel> channelMap) {
        for (Channel localChannel : channelMap.values()) {
            localChannel.close();
        }
    }

}
