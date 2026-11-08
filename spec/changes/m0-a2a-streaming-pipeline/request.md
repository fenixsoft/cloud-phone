你提到的日后都有可能添加。但是我们先从安卓到安卓做起，你分别启动两台安卓的模拟器，作为源端和客户端，走通全部流程。通过了之后，我会把客户端安装到自己手机上测试，这部我手动来。这个过程中，你要分别模拟网络走局域网、公网P2P、公网FRP中转的。以及模拟不同类型的负载（游戏、APP）、不同分辨率的传输，确保能够打到可用的程度。另外，你所在的服务器就是我的家庭服务器，与我其他设备处于同一个网络环境，如果服务器能够P2P打洞成功，那说明我的家庭网络就是可以支持P2P的。最后，我已经在FRP服务中开好了两个端口（UDP和TCP各一个），见如下配置。你在/root目录下创建一个cloud-phone的项目（源和客户端你自己独立创建二级目录作），在该项目中初始化好git、fenix框架以及开发相关用到的其他依赖。在该项目中完成立项。FRP配置：[[proxies]]
type = "tcp"
#每个连接一个名称，不能修改
name="t3CcW1J2DMkW"
localIP = "127.0.0.1"
#请填写需要远程访问的本地端口
localPort = 22333
#每个连接一个远程端口，访问地址 serverAddr:remotePort
remotePort = 22333
#启动报错时尝试删除下面两行
transport.useEncryption = true
transport.useCompression = true

[[proxies]]
type = "udp"
#每个连接一个名称，不能修改
name="9dNPmvI3sR2C"
localIP = "127.0.0.1"
#请填写需要远程访问的本地端口
localPort = 22334
#每个连接一个远程端口，访问地址 serverAddr:remotePort
remotePort = 22334
#启动报错时尝试删除下面两行
transport.useEncryption = true
transport.useCompression = true
