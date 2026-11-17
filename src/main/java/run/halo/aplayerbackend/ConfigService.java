package run.halo.aplayerbackend;

import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.extension.Secret;
import run.halo.app.plugin.ReactiveSettingFetcher;

/** 读取插件配置；Cookie 从 Halo Secret 解析，配置中只保存 Secret 名称。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConfigService {

    private final ReactiveSettingFetcher settingFetcher;
    private final ReactiveExtensionClient extensionClient;

    public Mono<PluginConfig.All> getAll() {
        Mono<PluginConfig.Source> source = settingFetcher
            .fetch(PluginConfig.Source.GROUP, PluginConfig.Source.class)
            .defaultIfEmpty(new PluginConfig.Source(null, null, null, null));
        Mono<PluginConfig.Quality> quality = settingFetcher
            .fetch(PluginConfig.Quality.GROUP, PluginConfig.Quality.class)
            .defaultIfEmpty(new PluginConfig.Quality(null, null, null, null));
        Mono<PluginConfig.Sync> sync = settingFetcher
            .fetch(PluginConfig.Sync.GROUP, PluginConfig.Sync.class)
            .defaultIfEmpty(new PluginConfig.Sync(null, null, null, null));
        Mono<PluginConfig.Access> access = settingFetcher
            .fetch(PluginConfig.Access.GROUP, PluginConfig.Access.class)
            .defaultIfEmpty(new PluginConfig.Access(null, null));
        Mono<PluginConfig.Runtime> runtime = settingFetcher
            .fetch(PluginConfig.Runtime.GROUP, PluginConfig.Runtime.class)
            .defaultIfEmpty(new PluginConfig.Runtime(null));

        return Mono.zip(source, quality, sync, access, runtime)
            .map(t -> new PluginConfig.All(t.getT1(), t.getT2(), t.getT3(), t.getT4(), t.getT5()))
            .map(this::applyDefaults);
    }

    private PluginConfig.All applyDefaults(PluginConfig.All all) {
        PluginConfig.Source s = all.source();
        PluginConfig.Quality q = all.quality();
        PluginConfig.Sync sy = all.sync();
        PluginConfig.Access a = all.access();
        PluginConfig.Runtime r = all.runtime();

        String server = nvl(s.server(), "netease");
        String type = nvl(s.type(), "playlist");
        String id = nvl(s.id(), "");
        String bitrate = nvl(q.bitrate(), "320");
        Integer picSize = q.picSize() == null ? 300 : q.picSize();
        boolean downloadLyrics = q.downloadLyrics() == null || q.downloadLyrics();
        boolean downloadCover = q.downloadCover() == null || q.downloadCover();
        String syncMode = nvl(sy.syncMode(), "manual");
        String cron = nvl(sy.cron(), "0 30 3 * * *");
        boolean incremental = sy.incremental() == null || sy.incremental();
        boolean cleanupRemoved = sy.cleanupRemoved() == null || sy.cleanupRemoved();
        boolean publicRead = a.publicRead() == null || a.publicRead();
        String allowOrigins = nvl(a.allowOrigins(), "");
        String nodePath = nvl(r.nodePath(), "");

        return new PluginConfig.All(
            new PluginConfig.Source(server, type, id, s.cookieSecretName()),
            new PluginConfig.Quality(bitrate, picSize, downloadLyrics, downloadCover),
            new PluginConfig.Sync(syncMode, cron, incremental, cleanupRemoved),
            new PluginConfig.Access(publicRead, allowOrigins),
            new PluginConfig.Runtime(nodePath)
        );
    }

    /** 从 Secret 读取 cookie 明文，找不到时返回空串（即匿名解析）。 */
    public Mono<String> resolveCookie(PluginConfig.All config) {
        String secretName = config.source().cookieSecretName();
        if (secretName == null || secretName.isBlank()) {
            return Mono.just("");
        }
        return extensionClient.fetch(Secret.class, secretName.trim())
            .map(Secret::getStringData)
            .map(data -> data == null ? "" : data.getOrDefault("cookie", ""))
            .defaultIfEmpty("")
            .onErrorResume(e -> {
                log.warn("读取 Cookie Secret 失败，将按匿名解析继续: {}", e.getMessage());
                return Mono.just("");
            });
    }

    private static String nvl(String v, String def) {
        return (v == null || v.isBlank()) ? def : v;
    }
}
