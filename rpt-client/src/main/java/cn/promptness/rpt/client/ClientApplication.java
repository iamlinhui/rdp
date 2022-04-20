package cn.promptness.rpt.client;

import cn.promptness.rpt.base.coder.MessageDecoder;
import cn.promptness.rpt.base.coder.MessageEncoder;
import cn.promptness.rpt.base.config.ClientConfig;
import cn.promptness.rpt.base.handler.IdleCheckHandler;
import cn.promptness.rpt.base.utils.Config;
import cn.promptness.rpt.base.utils.Pair;
import cn.promptness.rpt.base.utils.ScheduledThreadFactory;
import cn.promptness.rpt.client.handler.ClientHandler;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldPrepender;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslProvider;
import io.netty.handler.stream.ChunkedWriteHandler;
import io.netty.handler.traffic.GlobalTrafficShapingHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLException;
import java.io.InputStream;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class ClientApplication {

    private static final Logger logger = LoggerFactory.getLogger(ClientApplication.class);

    private static final ScheduledThreadPoolExecutor EXECUTOR = new ScheduledThreadPoolExecutor(1, ScheduledThreadFactory.create("client", false));
    private static final AtomicBoolean CONNECT = new AtomicBoolean(false);

    public static void main(String[] args) throws SSLException {
        start();
    }

    public static Pair<NioEventLoopGroup, ScheduledFuture<?>> start() throws SSLException {
        ClientConfig clientConfig = Config.getClientConfig();
        InputStream certChainFile = ClassLoader.getSystemResourceAsStream("client.crt");
        InputStream keyFile = ClassLoader.getSystemResourceAsStream("pkcs8_client.key");
        InputStream rootFile = ClassLoader.getSystemResourceAsStream("ca.crt");
        SslContext sslContext = SslContextBuilder.forClient().keyManager(certChainFile, keyFile).trustManager(rootFile).sslProvider(SslProvider.OPENSSL).build();

        NioEventLoopGroup clientWorkerGroup = new NioEventLoopGroup();
        GlobalTrafficShapingHandler globalTrafficShapingHandler = new GlobalTrafficShapingHandler(clientWorkerGroup, 0, clientConfig.getClientLimit());
        Bootstrap bootstrap = new Bootstrap();
        bootstrap.group(clientWorkerGroup).channel(NioSocketChannel.class).option(ChannelOption.SO_KEEPALIVE, true).handler(new ChannelInitializer<SocketChannel>() {
            @Override
            public void initChannel(SocketChannel ch) throws Exception {
                ch.pipeline().addLast(sslContext.newHandler(ch.alloc()));
                //固定帧长解码器
                ch.pipeline().addLast(new LengthFieldBasedFrameDecoder(Integer.MAX_VALUE, 0, 4, 0, 4));
                ch.pipeline().addLast(new LengthFieldPrepender(4));
                ch.pipeline().addLast(new ChunkedWriteHandler());
                //自定义协议解码器
                ch.pipeline().addLast(new MessageDecoder());
                //自定义协议编码器
                ch.pipeline().addLast(new MessageEncoder());
                ch.pipeline().addLast(new IdleCheckHandler(60, 30, 0));
                //服务器连接处理器
                ch.pipeline().addLast(new ClientHandler(globalTrafficShapingHandler, CONNECT));
            }
        });
        ScheduledFuture<?> scheduledFuture = EXECUTOR.scheduleAtFixedRate(() -> {
            if (CONNECT.get()) {
                return;
            }
            synchronized (CONNECT) {
                if (CONNECT.get()) {
                    return;
                }
                try {
                    logger.info("客户端开始连接服务端IP:{},服务端端口:{}", clientConfig.getServerIp(), clientConfig.getServerPort());
                    bootstrap.connect(clientConfig.getServerIp(), clientConfig.getServerPort()).sync();
                } catch (Exception exception) {
                    logger.info("客户端失败连接服务端IP:{},服务端端口:{},原因:{}", clientConfig.getServerIp(), clientConfig.getServerPort(), exception.getCause().getMessage());
                    Thread.currentThread().interrupt();
                }
            }
        }, 0, 1, TimeUnit.MINUTES);
        return new Pair<>(clientWorkerGroup, scheduledFuture);
    }
}
