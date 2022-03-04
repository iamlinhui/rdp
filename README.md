### 基于Netty实现的TCP代理工具

运行于TCP协议之上的协议：

- HTTP协议：超文本传输协议，用于普通浏览
- HTTPS协议：安全超文本传输协议，身披SSL外衣的HTTP协议
- FTP协议：文件传输协议，用于文件传输
- POP3协议：邮局协议，收邮件使用
- SMTP协议：简单邮件传输协议，用来发送电子邮件
- Telnet协议：远程登陆协议，通过一个终端登陆到网络
- SSH协议：安全外壳协议，用于加密安全登陆，替代安全性差的Telnet协议
- RDP协议：远程桌面协议，是一个多通道的协议，让用户连上提供终端服务的电脑
- SOCKS协议: 防火墙安全会话转换协议

服务端配置`server.yml`

```yaml
#服务端绑定IP
serverIp: 0.0.0.0
#服务端与客户端通讯端口
serverPort: 6167
#授权给客户端的秘钥
clientKey:
  - b0cc39c7-1b78-4ff6-9486-020399f569e9
  - 4befea7e-a61c-4979-b012-47659bab6f21
```

客户端配置`client.yml`

```yaml
#服务端IP
serverIp: 127.0.0.1
#服务端与客户端通讯端口
serverPort: 6167
#授权给客户端的秘钥
clientKey: b0cc39c7-1b78-4ff6-9486-020399f569e9

# remotePort与localPort映射配置
config:
  # 服务暴露端口
  - remotePort: 4389
    # 客户端连接目标端口
    localPort: 3389
    # 客户端连接目标IP
    localIp: 127.0.0.1
    # 描述
    description: rdp
  - remotePort: 7379
    localPort: 6379
    localIp: 127.0.0.1
    description: redis
  - remotePort: 9080
    localPort: 8080
    localIp: 127.0.0.1
    description: tomcat
```

Java命令行添加外部文件到classpath，从而实现读取外部配置文件
```text
对于jar包启动，使用-Xbootclasspath/a:命令；对于class启动，使用-cp命令。

两种方法分别是：
1. java -Xbootclasspath/a:/etc/hadoop/conf:/etc/hive/conf -jar example.jar
2. java -cp /etc/hadoop/conf:/etc/hive/conf:./example.jar example.Main.class
注意事项：
（1）-Xbootclasspath/a:要在-jar之前
（2）-Xbootclasspath/a:和后面的参数之间不能有空格
（3）example.Main.class是jar包的主类，要把相应的jar包放到classpath参数中。
（4）文件路径之间使用分隔符（win下为分号，linux下为冒号）
```