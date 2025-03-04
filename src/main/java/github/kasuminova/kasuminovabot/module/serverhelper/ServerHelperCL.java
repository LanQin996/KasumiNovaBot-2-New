package github.kasuminova.kasuminovabot.module.serverhelper;

import github.kasuminova.kasuminovabot.KasumiNovaBot2;
import github.kasuminova.kasuminovabot.command.GroupCommand;
import github.kasuminova.kasuminovabot.module.serverhelper.command.*;
import github.kasuminova.kasuminovabot.module.serverhelper.command.console.ReconnectCLCmd;
import github.kasuminova.kasuminovabot.module.serverhelper.command.console.ReloadCLCmd;
import github.kasuminova.kasuminovabot.module.serverhelper.config.ServerHelperCLConfig;
import github.kasuminova.kasuminovabot.module.serverhelper.event.GroupMessageProcessor;
import github.kasuminova.kasuminovabot.module.serverhelper.hypernet.StoredResearchData;
import github.kasuminova.kasuminovabot.module.serverhelper.network.ClientInitializer;
import github.kasuminova.kasuminovabot.util.FileUtil;
import github.kasuminova.kasuminovabot.util.MiraiCodes;
import github.kasuminova.kasuminovabot.util.MiscUtil;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import net.mamoe.mirai.console.command.CommandManager;
import net.mamoe.mirai.message.data.MessageChain;
import net.mamoe.mirai.message.data.MessageChainBuilder;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@SuppressWarnings("unused")
public class ServerHelperCL {
    public static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(Math.max(Runtime.getRuntime().availableProcessors() / 4, 2));
    public static final List<ServerHelperCL> CL_LIST = new ArrayList<>();
    private final ServerHelperCLConfig.CLConfig config;

    private final ServerMessageSyncThread chatMessageSyncTask = new ServerMessageSyncThread(this);
    private final ConnectionDaemonThread connectionDaemonThread = new ConnectionDaemonThread(this);

    private final GroupMessageProcessor groupMessageListener = new GroupMessageProcessor(this, KasumiNovaBot2.INSTANCE.genericEventListener);

    private final Map<String, GroupCommand> groupPrivateCmds = new HashMap<>();

    private StoredResearchData storedResearchData = new StoredResearchData();

    public ChannelFuture future = null;
    private EventLoopGroup work;
    private ChannelHandlerContext ctx = null;
    private volatile long lastHeartbeat = System.currentTimeMillis();

    public ServerHelperCL(ServerHelperCLConfig.CLConfig config) {
        this.config = config;

        WhiteListAddCmd whiteListAddCommand = new WhiteListAddCmd(this);
        WhiteListForceAddCmd whiteListForceAddCommand = new WhiteListForceAddCmd(this);
        WhiteListGetCmd whiteListGetCommand = new WhiteListGetCmd(this);
        WhiteListRemoveCmd whiteListRemoveCommand = new WhiteListRemoveCmd(this);
        WhiteListUpdateCmd whiteListUpdateCommand = new WhiteListUpdateCmd(this);
        WhiteListScanCmd whiteListScanCmd = new WhiteListScanCmd(this);
        ServerStatusCmd serverStatusCmd = new ServerStatusCmd(this);
        GetPlayersCmd getPlayersCmd = new GetPlayersCmd(this);
        ReconnectCmd reconnectCmd = new ReconnectCmd(this);
        ServerCommandExecCmd serverCommandExecCmd = new ServerCommandExecCmd(this);
        GlobalCommandExecCmd globalCommandExecCmd = new GlobalCommandExecCmd(this);
        PlayerCommandExecCmd playerCommandExecCmd = new PlayerCommandExecCmd(this);
        KickMeCmd kickMeCmd = new KickMeCmd(this);
//        RandomResearchDataCmd randomResearchDataCmd = new RandomResearchDataCmd(this);

        groupPrivateCmds.put(whiteListAddCommand.commandName, whiteListAddCommand);
        groupPrivateCmds.put(whiteListForceAddCommand.commandName, whiteListForceAddCommand);
        groupPrivateCmds.put(whiteListGetCommand.commandName, whiteListGetCommand);
        groupPrivateCmds.put(whiteListRemoveCommand.commandName, whiteListRemoveCommand);
        groupPrivateCmds.put(whiteListUpdateCommand.commandName, whiteListUpdateCommand);
        groupPrivateCmds.put(whiteListScanCmd.commandName, whiteListScanCmd);
        groupPrivateCmds.put(serverStatusCmd.commandName, serverStatusCmd);
        groupPrivateCmds.put(getPlayersCmd.commandName, getPlayersCmd);
        groupPrivateCmds.put(reconnectCmd.commandName, reconnectCmd);
        groupPrivateCmds.put(serverCommandExecCmd.commandName, serverCommandExecCmd);
        groupPrivateCmds.put(globalCommandExecCmd.commandName, globalCommandExecCmd);
        groupPrivateCmds.put(playerCommandExecCmd.commandName, playerCommandExecCmd);
        groupPrivateCmds.put(kickMeCmd.commandName, kickMeCmd);
//        groupPrivateCmds.put(randomResearchDataCmd.commandName, randomResearchDataCmd);
    }

    public static void loadConsoleCommand() {
        CommandManager.INSTANCE.registerCommand(new ReconnectCLCmd(), true);
        CommandManager.INSTANCE.registerCommand(new ReloadCLCmd(), true);
    }

    public static void loadAll() {
        KasumiNovaBot2.INSTANCE.reloadPluginConfig(ServerHelperCLConfig.INSTANCE);

        for (ServerHelperCLConfig.CLConfig client : ServerHelperCLConfig.INSTANCE.getClients()) {
            ServerHelperCL cl = new ServerHelperCL(client);
            CL_LIST.add(cl);
            cl.load();
        }
    }

    public static void unloadAll() {
        for (ServerHelperCL cl : CL_LIST) {
            cl.unLoad();
        }
        CL_LIST.clear();
    }

    public ServerMessageSyncThread getChatMessageSyncTask() {
        return chatMessageSyncTask;
    }

    public ServerHelperCLConfig.CLConfig getConfig() {
        return config;
    }

    public void load() {
        try {
            connect();
        } catch (Exception e) {
            KasumiNovaBot2.INSTANCE.logger.error("连接至插件服务器失败！", e);
        }

        KasumiNovaBot2.INSTANCE.registerPrivateCommand(String.valueOf(config.getGroupID()), groupPrivateCmds);

        chatMessageSyncTask.load();
        chatMessageSyncTask.start();
        groupMessageListener.load();

        loadHyperNetIntegration();
    }

    public void unLoad() {
        disconnect();

        KasumiNovaBot2.INSTANCE.unregisterPrivateCommand(String.valueOf(config.getGroupID()));
        chatMessageSyncTask.interrupt();
        groupMessageListener.unLoad();
    }

    public void connect() throws Exception {
        if (work != null && future != null) disconnect();
        if (work != null) {
            work.shutdownGracefully();
        }
        work = new NioEventLoopGroup();

        Bootstrap bootstrap = new Bootstrap();
        bootstrap.group(work)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .handler(new ClientInitializer(this));

        KasumiNovaBot2.INSTANCE.logger.info(
                String.format("正在连接到插件服务器 %s:%s ...", config.getIp(), config.getPort()));
        future = bootstrap.connect(config.getIp(), config.getPort()).sync();
        connectionDaemonThread.start();
    }

    public void disconnect() {
        try {
            connectionDaemonThread.interrupt();

            work.shutdownGracefully();

            if (future != null) {
                future.channel().closeFuture();
                future = null;
            }
            work = null;
        } catch (Exception e) {
            KasumiNovaBot2.INSTANCE.logger.warning(e);
        }
    }

    public void loadHyperNetIntegration() {
        String fileName = config.getResearchDataFileName();

        try {
            String jsonStr = FileUtil.readStringFromFile(KasumiNovaBot2.INSTANCE.resolveDataFile(fileName));
            storedResearchData = StoredResearchData.Companion.parseFromJSONString(jsonStr);
            KasumiNovaBot2.INSTANCE.logger.info("加载 HyperNet 研究数据文件成功！");
        } catch (Exception e) {
            KasumiNovaBot2.INSTANCE.logger.warning("加载 HyperNet 研究数据文件失败！", e);
        }
    }

    public void updateHeartbeatTime() {
        lastHeartbeat = System.currentTimeMillis();
    }

    public long getLastHeartbeat() {
        return lastHeartbeat;
    }

    public StoredResearchData getStoredResearchData() {
        return storedResearchData;
    }

    public void setStoredResearchData(final StoredResearchData storedResearchData) {
        this.storedResearchData = storedResearchData;
    }

    public ChannelHandlerContext getCtx() {
        return ctx;
    }

    public void setCtx(ChannelHandlerContext ctx) {
        this.ctx = ctx;
    }

    public <M extends Serializable> void sendMessageToServer(M message, boolean notifyIfFailure) {
        if (ctx == null) {
            if (notifyIfFailure) {
                sendMessage(new MessageChainBuilder()
                        .append("与内部服务器断开连接，此指令暂时无法使用。").append(MiraiCodes.WRAP)
                        .append("如有任何疑问，请联系机器人管理员。")
                        .build());
            }
            return;
        }
        EXECUTOR.execute(() -> ctx.writeAndFlush(message));
    }

    public void sendMessage(MessageChain message) {
        MiscUtil.sendMessageToGroup(message, config.getBotId(), config.getGroupID());
    }
}
