package run.halo.aplayerbackend;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;

/** 同步编排：读配置 → 调 node 解析 → 下载字节缓存 → 写 manifest。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SyncService {

    private final ConfigService configService;
    private final NodeRuntimeService nodeRuntimeService;
    private final CacheService cacheService;

    /** 防止同一实例内任务重叠。 */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /** 同步结果摘要，供控制台/API 展示。 */
    public record SyncReport(
        boolean success,
        String message,
        int total,
        int downloaded,
        int skipped,
        int failed,
        int cleaned
    ) {}

    private final WebClient webClient = WebClient.builder()
        .codecs(c -> c.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
        .build();

    public Mono<SyncReport> sync() {
        if (!running.compareAndSet(false, true)) {
            return Mono.just(new SyncReport(false, "已有同步任务正在执行", 0, 0, 0, 0, 0));
        }
        return configService.getAll()
            .flatMap(config -> {
                String id = config.source().id();
                if (id == null || id.isBlank()) {
                    return Mono.just(new SyncReport(false, "未配置资源 ID", 0, 0, 0, 0, 0));
                }
                return configService.resolveCookie(config)
                    .flatMap(cookie -> {
                        String nodeBin;
                        try {
                            nodeBin = nodeRuntimeService.detectNode(config.runtime().nodePath());
                        } catch (IOException e) {
                            return Mono.just(new SyncReport(false, e.getMessage(), 0, 0, 0, 0, 0));
                        }
                        return nodeRuntimeService.parse(config, cookie, nodeBin)
                            .flatMap(result -> {
                                if (result.songs() == null || result.songs().isEmpty()) {
                                    // 解析为空时不清理、不覆盖 manifest，避免 Cookie 失效或接口异常时误删全部缓存
                                    String msg = "解析结果为空，已保留原有缓存（请检查歌单 ID 与 Cookie 是否有效）";
                                    log.warn(msg);
                                    return Mono.just(new SyncReport(false, msg, 0, 0, 0, 0, 0));
                                }
                                return persist(config, result.songs());
                            });
                    });
            })
            .onErrorResume(e -> {
                log.error("同步失败: {}", e.getMessage(), e);
                return Mono.just(new SyncReport(false, "同步失败: " + e.getMessage(), 0, 0, 0, 0, 0));
            })
            .doFinally(sig -> running.set(false));
    }

    private Mono<SyncReport> persist(PluginConfig.All config, List<NodeRuntimeService.ParsedSong> songs) {
        Path pluginDir = nodeRuntimeService.pluginDir();
        return Mono.fromCallable(() -> {
            cacheService.ensureDirs(pluginDir);
            return true;
        }).subscribeOn(Schedulers.boundedElastic()).flatMap(ignored -> {

            List<CacheService.Entry> entries = new ArrayList<>();
            int[] counts = {0, 0, 0, 0}; // downloaded, skipped, failed, cleaned

            return Flux.fromIterable(songs)
                .concatMap(song -> {
                    String songId = song.id();
                    boolean exists = config.sync().incremental() && cacheService.hasAudio(pluginDir, songId);

                    Mono<String> audioMono;
                    if (exists) {
                        counts[1]++;
                        audioMono = Mono.just(CacheService.fileUrl("audio", safeName(songId) + ".mp3"));
                    } else if (song.audioUrl() == null) {
                        counts[2]++;
                        audioMono = Mono.just("");
                    } else {
                        Path target = cacheService.audioFile(pluginDir, songId);
                        audioMono = downloadToFile(song.audioUrl(), target)
                            .doOnNext(v -> counts[0]++)
                            .onErrorResume(e -> {
                                log.warn("下载音频失败 {}: {}", song.name(), e.getMessage());
                                counts[2]++;
                                return Mono.empty();
                            })
                            .thenReturn(CacheService.fileUrl("audio", safeName(songId) + ".mp3"));
                    }

                    Mono<String> coverMono;
                    if (!config.quality().downloadCover() || song.coverUrl() == null) {
                        coverMono = Mono.just("");
                    } else {
                        Path target = cacheService.coverFile(pluginDir, songId);
                        coverMono = downloadToFile(song.coverUrl(), target)
                            .onErrorResume(e -> Mono.empty())
                            .thenReturn(CacheService.fileUrl("cover", safeName(songId) + ".jpg"));
                    }

                    Mono<String> lrcMono;
                    if (!config.quality().downloadLyrics() || song.lrc() == null) {
                        lrcMono = Mono.just("");
                    } else {
                        Path target = cacheService.lrcFile(pluginDir, songId);
                        String lrc = song.lrc();
                        lrcMono = Mono.fromCallable(() -> {
                            cacheService.writeTextAtomic(target, lrc);
                            return CacheService.fileUrl("lrc", safeName(songId) + ".lrc");
                        }).subscribeOn(Schedulers.boundedElastic())
                          .onErrorResume(e -> Mono.just(""));
                    }

                    return Mono.zip(audioMono, coverMono, lrcMono).map(tuple -> {
                        String audio = tuple.getT1().isEmpty() ? null : tuple.getT1();
                        String cover = tuple.getT2().isEmpty() ? null : tuple.getT2();
                        String lrc = tuple.getT3().isEmpty() ? null : tuple.getT3();
                        return new CacheService.Entry(song.id(), song.name(), song.artist(), song.album(),
                            audio, cover, lrc, song.br());
                    }).doOnNext(entries::add);
                })
                .then(Mono.fromCallable(() -> {
                    // 清理已不在歌单中的缓存文件，避免缓存随歌单变更持续膨胀
                    if (config.sync().cleanupRemoved()) {
                        java.util.Set<String> keepIds = new java.util.HashSet<>();
                        for (NodeRuntimeService.ParsedSong s : songs) {
                            keepIds.add(safeName(s.id()));
                        }
                        try {
                            counts[3] = cacheService.cleanupOrphanFiles(pluginDir, keepIds);
                        } catch (Exception e) {
                            log.warn("清理过期缓存失败（不影响本次同步）: {}", e.getMessage());
                        }
                    }

                    CacheService.Manifest manifest = new CacheService.Manifest(
                        config.source().server(), config.source().type(), config.source().id(),
                        java.time.Instant.now().toString(), entries);
                    cacheService.writeManifest(pluginDir, manifest);
                    String msg = String.format("同步完成：共 %d 首，下载 %d，跳过 %d，失败 %d，清理 %d",
                        songs.size(), counts[0], counts[1], counts[2], counts[3]);
                    log.info(msg);
                    return new SyncReport(true, msg, songs.size(), counts[0], counts[1], counts[2], counts[3]);
                }).subscribeOn(Schedulers.boundedElastic()));
        });
    }

    /** 用 WebClient 下载字节并原子写入文件。 */
    private Mono<byte[]> downloadToFile(String url, Path target) {
        return webClient.get()
            .uri(url)
            .header(HttpHeaders.USER_AGENT, "Mozilla/5.0")
            .retrieve()
            .bodyToMono(byte[].class)
            .filter(bytes -> bytes.length > 0)
            .retryWhen(Retry.backoff(2, Duration.ofMillis(500)))
            .flatMap(bytes -> Mono.fromCallable(() -> {
                cacheService.writeBytesAtomic(target, bytes);
                return bytes;
            }).subscribeOn(Schedulers.boundedElastic()));
    }

    private static String safeName(String id) {
        return id == null ? "unknown" : id.replaceAll("[^A-Za-z0-9_-]", "_");
    }
}
