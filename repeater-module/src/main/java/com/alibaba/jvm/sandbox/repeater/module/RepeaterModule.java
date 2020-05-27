package com.alibaba.jvm.sandbox.repeater.module;

import com.alibaba.jvm.sandbox.api.Information;
import com.alibaba.jvm.sandbox.api.Information.Mode;
import com.alibaba.jvm.sandbox.api.Module;
import com.alibaba.jvm.sandbox.api.ModuleException;
import com.alibaba.jvm.sandbox.api.ModuleLifecycle;
import com.alibaba.jvm.sandbox.api.annotation.Command;
import com.alibaba.jvm.sandbox.api.resource.*;
import com.alibaba.jvm.sandbox.repeater.module.advice.SpringInstantiateAdvice;
import com.alibaba.jvm.sandbox.repeater.module.classloader.PluginClassLoader;
import com.alibaba.jvm.sandbox.repeater.module.classloader.PluginClassRouting;
import com.alibaba.jvm.sandbox.repeater.module.impl.JarFileLifeCycleManager;
import com.alibaba.jvm.sandbox.repeater.module.util.LogbackUtils;
import com.alibaba.jvm.sandbox.repeater.plugin.Constants;
import com.alibaba.jvm.sandbox.repeater.plugin.api.Broadcaster;
import com.alibaba.jvm.sandbox.repeater.plugin.api.ConfigManager;
import com.alibaba.jvm.sandbox.repeater.plugin.api.InvocationListener;
import com.alibaba.jvm.sandbox.repeater.plugin.api.LifecycleManager;
import com.alibaba.jvm.sandbox.repeater.plugin.core.StandaloneSwitch;
import com.alibaba.jvm.sandbox.repeater.plugin.core.bridge.ClassloaderBridge;
import com.alibaba.jvm.sandbox.repeater.plugin.core.bridge.RepeaterBridge;
import com.alibaba.jvm.sandbox.repeater.plugin.core.eventbus.EventBusInner;
import com.alibaba.jvm.sandbox.repeater.plugin.core.eventbus.RepeatEvent;
import com.alibaba.jvm.sandbox.repeater.plugin.core.impl.api.DefaultInvocationListener;
import com.alibaba.jvm.sandbox.repeater.plugin.core.model.ApplicationModel;
import com.alibaba.jvm.sandbox.repeater.plugin.core.serialize.SerializeException;
import com.alibaba.jvm.sandbox.repeater.plugin.core.serialize.Serializer;
import com.alibaba.jvm.sandbox.repeater.plugin.core.serialize.SerializerProvider;
import com.alibaba.jvm.sandbox.repeater.plugin.core.spring.SpringContextInnerContainer;
import com.alibaba.jvm.sandbox.repeater.plugin.core.trace.TtlConcurrentAdvice;
import com.alibaba.jvm.sandbox.repeater.plugin.core.util.ExecutorInner;
import com.alibaba.jvm.sandbox.repeater.plugin.core.util.PathUtils;
import com.alibaba.jvm.sandbox.repeater.plugin.core.util.PropertyUtil;
import com.alibaba.jvm.sandbox.repeater.plugin.core.wrapper.SerializerWrapper;
import com.alibaba.jvm.sandbox.repeater.plugin.domain.RepeatMeta;
import com.alibaba.jvm.sandbox.repeater.plugin.domain.RepeaterConfig;
import com.alibaba.jvm.sandbox.repeater.plugin.domain.RepeaterResult;
import com.alibaba.jvm.sandbox.repeater.plugin.exception.PluginLifeCycleException;
import com.alibaba.jvm.sandbox.repeater.plugin.spi.InvokePlugin;
import com.alibaba.jvm.sandbox.repeater.plugin.spi.Repeater;
import com.alibaba.jvm.sandbox.repeater.plugin.spi.SubscribeSupporter;
import org.apache.commons.lang3.StringUtils;
import org.kohsuke.MetaInfServices;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Resource;
import java.io.PrintWriter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.alibaba.jvm.sandbox.repeater.plugin.Constants.REPEAT_SPRING_ADVICE_SWITCH;

/**
 * <p>
 *
 * @author zhaoyb1990
 */
@MetaInfServices(Module.class)
@Information(id = com.alibaba.jvm.sandbox.repeater.module.Constants.MODULE_ID, author = "zhaoyb1990", version = com.alibaba.jvm.sandbox.repeater.module.Constants.VERSION)
public class RepeaterModule implements Module, ModuleLifecycle {

    private final static Logger log = LoggerFactory.getLogger(RepeaterModule.class);

    @Resource
    private ModuleEventWatcher eventWatcher; //事件观察者

    @Resource
    private ModuleController moduleController; //模块控制接口, 控制模块的激活和冻结

    @Resource
    private ConfigInfo configInfo; //sandbox启动配置, 这里只用来判断启动模式

    @Resource
    private ModuleManager moduleManager;

    @Resource
    private LoadedClassDataSource loadedClassDataSource; //已加载类数据源, 可以获取到所有已加载类的集合

    private Broadcaster broadcaster; //消息广播服务, 用于采集流量之后的消息分发 (保存录制记录, 保存回放结果, 拉取录制记录)

    private InvocationListener invocationListener; //调用监听器

    private ConfigManager configManager; //配置管理器, 实现拉取配置

    private LifecycleManager lifecycleManager; //插件加载器

    private List<InvokePlugin> invokePlugins; //插件列表

    private HeartbeatHandler heartbeatHandler; //心跳处理器

    private AtomicBoolean initialized = new AtomicBoolean(false); //是否完成初始化的标志

    @Override
    public void onLoad() throws Throwable {
        //模块加载, 模块开始加载之前
        // 初始化日志框架
        LogbackUtils.init(PathUtils.getConfigPath() + "/repeater-logback.xml");
        //获取启动模式
        Mode mode = configInfo.getMode();
        log.info("module on loaded,id={},version={},mode={}", com.alibaba.jvm.sandbox.repeater.module.Constants.MODULE_ID, com.alibaba.jvm.sandbox.repeater.module.Constants.VERSION, mode);
        /* agent方式启动 */
        if (mode == Mode.AGENT && Boolean.valueOf(PropertyUtil.getPropertyOrDefault(REPEAT_SPRING_ADVICE_SWITCH, ""))) {
            log.info("agent launch mode,use Spring Instantiate Advice to register bean.");
            //SpringContext内部容器的是否agent模式设置为真
            SpringContextInnerContainer.setAgentLaunch(true);
            //Spring初始化拦截器, agent启动模式下拦截记录 beanName和bean
            SpringInstantiateAdvice.watcher(this.eventWatcher).watch();
            //激活模块
            moduleController.active();
        }
    }

    @Override
    public void onUnload() throws Throwable {
        //释放插件加载资源, 尽可能关闭 pluginClassLoader
        if (lifecycleManager != null) {
            lifecycleManager.release();
        }
        heartbeatHandler.stop();
    }

    @Override
    public void onActive() throws Throwable {
        //模块激活, 打印日志
        log.info("onActive");
    }

    @Override
    public void onFrozen() throws Throwable {
        //模块冻结
        log.info("onFrozen");
    }

    @Override
    public void loadCompleted() {
        ExecutorInner.execute(new Runnable() {
            @Override
            public void run() {
                configManager = StandaloneSwitch.instance().getConfigManager();
                broadcaster = StandaloneSwitch.instance().getBroadcaster();
                invocationListener = new DefaultInvocationListener(broadcaster);
                RepeaterResult<RepeaterConfig> pr = configManager.pullConfig();
                if (pr.isSuccess()) {
                    log.info("pull repeater config success,config={}", pr.getData());
                    ClassloaderBridge.init(loadedClassDataSource);
                    initialize(pr.getData());
                }
            }
        });
        heartbeatHandler = new HeartbeatHandler(configInfo, moduleManager);
        heartbeatHandler.start();
    }

    /**
     * 初始化插件
     *
     * @param config 配置文件
     */
    private synchronized void initialize(RepeaterConfig config) {
        if (initialized.compareAndSet(false, true)) {
            try {
                ApplicationModel.instance().setConfig(config);
                // 特殊路由表;
                PluginClassLoader.Routing[] routingArray = PluginClassRouting.wellKnownRouting(configInfo.getMode() == Mode.AGENT, 20L);
                String pluginsPath;
                if (StringUtils.isEmpty(config.getPluginsPath())) {
                    pluginsPath = PathUtils.getPluginPath();
                } else {
                    pluginsPath = config.getPluginsPath();
                }
                lifecycleManager = new JarFileLifeCycleManager(pluginsPath, routingArray);
                // 装载插件
                invokePlugins = lifecycleManager.loadInvokePlugins();
                for (InvokePlugin invokePlugin : invokePlugins) {
                    try {
                        if (invokePlugin.enable(config)) {
                            log.info("enable plugin {} success", invokePlugin.identity());
                            invokePlugin.watch(eventWatcher, invocationListener);
                            invokePlugin.onConfigChange(config);
                        }
                    } catch (PluginLifeCycleException e) {
                        log.info("watch plugin occurred error", e);
                    }
                }
                // 装载回放器
                List<Repeater> repeaters = lifecycleManager.loadRepeaters();
                for (Repeater repeater : repeaters) {
                    if (repeater.enable(config)) {
                        repeater.setBroadcast(broadcaster);  // 注入广播器?
                    }
                }
                RepeaterBridge.instance().build(repeaters); // 通过RepeaterBridge 桥接器创建repeater 回放器
                // 装载消息订阅器
                List<SubscribeSupporter> subscribes = lifecycleManager.loadSubscribes();
                for (SubscribeSupporter subscribe : subscribes) {
                    subscribe.register();
                }
                TtlConcurrentAdvice.watcher(eventWatcher).watch(config);
            } catch (Throwable throwable) {
                initialized.compareAndSet(true, false);
                log.error("error occurred when initialize module", throwable);
            }
        }
    }

    /*
     这个是repeat module接收回放请求的接口

     该接口接收http请求, 然后发出RepeatEvent, 由EventBusInner发出Event

     问题:
        command为啥是对外http接口?


     */
    /**
     * 回放http接口
     *
     * @param req    请求参数
     * @param writer printWriter
     */
    @Command("repeat")
    public void repeat(final Map<String, String> req, final PrintWriter writer) {
        try {
            String data = req.get(Constants.DATA_TRANSPORT_IDENTIFY);
            if (StringUtils.isEmpty(data)) {
                writer.write("invalid request, cause parameter {" + Constants.DATA_TRANSPORT_IDENTIFY + "} is required");
                return;
            }
            RepeatEvent event = new RepeatEvent();
            Map<String, String> requestParams = new HashMap<String, String>(16);
            for (Map.Entry<String, String> entry : req.entrySet()) {
                requestParams.put(entry.getKey(), entry.getValue());
            }
            event.setRequestParams(requestParams);
            EventBusInner.post(event);
            writer.write("submit success");
        } catch (Throwable e) {
            writer.write(e.getMessage());
        }
    }

    /**
     * 重新加载插件
     *
     * @param req    请求参数
     * @param writer printWriter
     */
    @Command("reload")
    public void reload(final Map<String, String> req, final PrintWriter writer) {
        try {
            if (initialized.compareAndSet(true,false)) {
                reload();
                initialized.compareAndSet(false, true);
            }
        } catch (Throwable throwable) {
            writer.write(throwable.getMessage());
            initialized.compareAndSet(false, true);
        }
    }

    private synchronized void reload() throws ModuleException {
        moduleController.frozen();
        // unwatch all plugin
        RepeaterResult<RepeaterConfig> result = configManager.pullConfig();
        if (!result.isSuccess()) {
            log.error("reload plugin failed, cause pull config not success");
            return;
        }
        for (InvokePlugin invokePlugin : invokePlugins) {
            if (invokePlugin.enable(result.getData())) {
                invokePlugin.unWatch(eventWatcher, invocationListener);
            }
        }
        // release classloader
        lifecycleManager.release();
        // reWatch
        initialize(result.getData());
        moduleController.active();
    }

    /**
     * 回放http接口(暴露JSON回放）
     *
     * @param req    请求参数
     * @param writer printWriter
     */
    @Command("repeatWithJson")
    public void repeatWithJson(final Map<String, String> req, final PrintWriter writer) {
        try {
            String data = req.get(Constants.DATA_TRANSPORT_IDENTIFY);
            if (StringUtils.isEmpty(data)) {
                writer.write("invalid request, cause parameter {" + Constants.DATA_TRANSPORT_IDENTIFY + "} is required");
                return;
            }
            RepeatMeta meta = SerializerProvider.instance().provide(Serializer.Type.JSON).deserialize(data, RepeatMeta.class);
            req.put(Constants.DATA_TRANSPORT_IDENTIFY, SerializerProvider.instance().provide(Serializer.Type.HESSIAN).serialize2String(meta));
            repeat(req, writer);
        } catch (Throwable e) {
            writer.write(e.getMessage());
        }
    }

    /**
     * 配置推送接口
     *
     * @param req    请求参数
     * @param writer printWriter
     */
    @Command("pushConfig")
    public void pushConfig(final Map<String, String> req, final PrintWriter writer) {
        String data = req.get(Constants.DATA_TRANSPORT_IDENTIFY);
        if (StringUtils.isEmpty(data)) {
            writer.write("invalid request, cause parameter {" + Constants.DATA_TRANSPORT_IDENTIFY + "} is required");
            return;
        }
        try {
            RepeaterConfig config = SerializerWrapper.hessianDeserialize(data, RepeaterConfig.class);
            ApplicationModel.instance().setConfig(config);
            noticeConfigChange(config);
            writer.write("config push success");
        } catch (SerializeException e) {
            writer.write("invalid request, cause deserialize config failed, reason = {" + e.getMessage() + "}");
        }
    }

    /**
     * 通知配置变更
     *
     * @param config 配置文件
     */
    private void noticeConfigChange(final RepeaterConfig config) {
        if (initialized.get()) {
            for (InvokePlugin invokePlugin : invokePlugins) {
                try {
                    if (invokePlugin.enable(config)) {
                        invokePlugin.onConfigChange(config);
                    }
                } catch (PluginLifeCycleException e) {
                    log.error("error occurred when notice config, plugin ={}", invokePlugin.getType().name(), e);
                }
            }
        }
    }
}
