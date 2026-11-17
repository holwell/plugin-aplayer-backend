package run.halo.aplayerbackend;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.stereotype.Component;
import run.halo.app.plugin.BasePlugin;
import run.halo.app.plugin.PluginContext;

/** 插件入口：启用调度，并在启动时把内置 Node 解析资源落地。 */
@Slf4j
@Component
@EnableScheduling
public class AplayerBackendPlugin extends BasePlugin {

    private final NodeRuntimeService nodeRuntimeService;

    public AplayerBackendPlugin(PluginContext pluginContext, NodeRuntimeService nodeRuntimeService) {
        super(pluginContext);
        this.nodeRuntimeService = nodeRuntimeService;
    }

    @Override
    public void start() {
        try {
            nodeRuntimeService.prepareResources();
            log.info("APlayer 音乐后端已启动，工作目录: {}", nodeRuntimeService.pluginDir());
        } catch (Exception e) {
            log.error("APlayer 音乐后端启动失败（资源解压）: {}", e.getMessage(), e);
        }
    }

    @Override
    public void stop() {
        log.info("APlayer 音乐后端已停止");
    }
}
