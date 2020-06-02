package com.alibaba.jvm.sandbox.repeater.plugin.core.impl.spi;

import com.alibaba.jvm.sandbox.repeater.plugin.Constants;
import com.alibaba.jvm.sandbox.repeater.plugin.core.StandaloneSwitch;
import com.alibaba.jvm.sandbox.repeater.plugin.core.eventbus.EventBusInner;
import com.alibaba.jvm.sandbox.repeater.plugin.core.eventbus.RepeatEvent;
import com.alibaba.jvm.sandbox.repeater.plugin.core.impl.api.DefaultFlowDispatcher;
import com.alibaba.jvm.sandbox.repeater.plugin.core.serialize.SerializeException;
import com.alibaba.jvm.sandbox.repeater.plugin.core.wrapper.SerializerWrapper;
import com.alibaba.jvm.sandbox.repeater.plugin.domain.RecordModel;
import com.alibaba.jvm.sandbox.repeater.plugin.domain.RepeatMeta;
import com.alibaba.jvm.sandbox.repeater.plugin.domain.RepeaterResult;
import com.alibaba.jvm.sandbox.repeater.plugin.spi.SubscribeSupporter;
import com.google.common.eventbus.AllowConcurrentEvents;
import com.google.common.eventbus.Subscribe;
import org.apache.commons.lang3.StringUtils;
import org.kohsuke.MetaInfServices;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

//zwl: repeat subscribe 的地方
/**
 * <p>
 *
 * @author zhaoyb1990
 */
@MetaInfServices(SubscribeSupporter.class)
public class RepeatSubscribeSupporter implements SubscribeSupporter<RepeatEvent> {

    private final static Logger log = LoggerFactory.getLogger(RepeatSubscribeSupporter.class);

    @Override
    public void register() {
        EventBusInner.register(this, type());
    }

    @Override
    public void unRegister() {
        EventBusInner.unregister(this, type());
    }

    @Override
    public String type() {
        return "repeat-register";
    }


    /*
        zwl: 处理  RepeatEvent 的事件捕获器, 处理replay/repeat事件
     */
    @AllowConcurrentEvents
    @Subscribe
    @Override
    public void onSubscribe(RepeatEvent repeatEvent) {
        Map<String, String> req = repeatEvent.getRequestParams();
        try {
            final String data = req.get(Constants.DATA_TRANSPORT_IDENTIFY);
            if (StringUtils.isEmpty(data)) {
                log.info("invalid request cause meta is null, params={}", req);
                return;
            }
            log.info("subscribe success params={}", req);
            final RepeatMeta meta = SerializerWrapper.hessianDeserialize(data, RepeatMeta.class);
            // zwl: Broadcaster 是干什么的, pullRecord是从console拉record吗?
            RepeaterResult<RecordModel> pr = StandaloneSwitch.instance().getBroadcaster().pullRecord(meta);
            if (pr.isSuccess()){
                // zwl: DefaultFlowDispatcher 是做什么用的? 处理repeat的地方??
                DefaultFlowDispatcher.instance().dispatch(meta, pr.getData());
            } else {
                log.error("subscribe replay event failed, cause ={}", pr.getMessage());
            }
        } catch (SerializeException e) {
            log.error("serialize failed, req={}", req, e);
        } catch (Exception e) {
            log.error("[Error-0000]-uncaught exception occurred when register repeat event, req={}", req, e);
        }
    }
}
