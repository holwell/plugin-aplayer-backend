package run.halo.aplayerbackend;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import run.halo.app.plugin.ReactiveSettingFetcher;

/** 对外暴露符合 APlayer 格式的播放列表：{@code { "audio": [ ... ] }}。 */
@RestController
@RequestMapping("/apis/api.aplayer-backend/v1alpha1")
@RequiredArgsConstructor
public class PlaylistController {

    private final NodeRuntimeService nodeRuntimeService;
    private final CacheService cacheService;
    private final ReactiveSettingFetcher settingFetcher;

    /** APlayer 需要的字段：name / artist / url / cover / lrc。 */
    public record Audio(
        String name,
        String artist,
        String url,
        String cover,
        String lrc
    ) {}

    public record PlaylistResponse(
        String title,
        String author,
        String url,
        String cover,
        String lrc,
        List<Audio> audio,
        String syncedAt
    ) {}

    @GetMapping("/playlist")
    public Mono<ResponseEntity<PlaylistResponse>> playlist() {
        return settingFetcher.fetch(PluginConfig.Access.GROUP, PluginConfig.Access.class)
            .defaultIfEmpty(new PluginConfig.Access(true, ""))
            .flatMap(access -> {
                // publicRead=false 时拒绝匿名（本 v1 以配置开关表达，鉴权由 Halo 全局链处理）
                boolean publicRead = access.publicRead() == null || access.publicRead();
                if (!publicRead) {
                    return Mono.just(ResponseEntity.status(403).<PlaylistResponse>build());
                }
                return Mono.fromCallable(() -> {
                    CacheService.Manifest manifest = cacheService.readManifest(nodeRuntimeService.pluginDir());
                    List<Audio> audio = new ArrayList<>();
                    if (manifest != null && manifest.songs() != null) {
                        for (CacheService.Entry e : manifest.songs()) {
                            if (e.audio() == null || e.audio().isBlank()) continue;
                            String artist = (e.artist() == null) ? "" : String.join(", ", e.artist());
                            audio.add(new Audio(e.name(), artist, e.audio(), e.cover(), e.lrc()));
                        }
                    }
                    PlaylistResponse body = new PlaylistResponse(
                        "APlayer Backend", "aplayer-backend", "", "", "", audio,
                        manifest == null ? null : manifest.syncedAt()
                    );
                    return ResponseEntity.ok(body);
                }).subscribeOn(Schedulers.boundedElastic());
            });
    }

    /** 同步状态摘要接口，便于主题侧或运维查看。 */
    @GetMapping("/status")
    public Mono<ResponseEntity<Map<String, Object>>> status() {
        return Mono.fromCallable(() -> {
            CacheService.Manifest manifest = cacheService.readManifest(nodeRuntimeService.pluginDir());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("server", manifest == null ? null : manifest.server());
            m.put("type", manifest == null ? null : manifest.type());
            m.put("sourceId", manifest == null ? null : manifest.sourceId());
            m.put("syncedAt", manifest == null ? null : manifest.syncedAt());
            m.put("count", manifest == null || manifest.songs() == null ? 0 : manifest.songs().size());
            return ResponseEntity.ok(m);
        }).subscribeOn(Schedulers.boundedElastic());
    }
}
