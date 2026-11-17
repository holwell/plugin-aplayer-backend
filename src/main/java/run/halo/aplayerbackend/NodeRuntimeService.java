package run.halo.aplayerbackend;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import run.halo.app.plugin.PluginContext;
import run.halo.app.plugin.PluginsRootGetter;

/** 负责把内置 Node 资源落地到工作目录、探测 node、并以子进程方式执行 parse.js。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NodeRuntimeService {

    private final PluginContext pluginContext;
    private final PluginsRootGetter pluginsRootGetter;

    /** 解析结果中的单曲资源信息（与 parse.js 输出对应）。 */
    public record ParsedSong(
        String id,
        String name,
        List<String> artist,
        String album,
        String audioUrl,
        String coverUrl,
        String lrc,
        Integer br
    ) {}

    public record ParseResult(List<ParsedSong> songs) {}

    /** 插件专属工作目录：<插件根>/<plugin.name>/。 */
    public Path pluginDir() {
        return pluginsRootGetter.get().resolve(pluginContext.getName());
    }

    /** 将 jar 内的 node/ 资源解压到工作目录（若目标已存在则覆盖，保证升级生效）。 */
    public void prepareResources() throws IOException {
        Path nodeDir = pluginDir().resolve("node");
        Files.createDirectories(nodeDir.resolve("meting"));
        copyResource("node/parse.js", nodeDir.resolve("parse.js"));
        copyResource("node/meting/meting.esm.js", nodeDir.resolve("meting/meting.esm.js"));
        // LICENSE 不是运行必需，忽略复制失败
        try {
            copyResource("node/meting/LICENSE", nodeDir.resolve("meting/LICENSE"));
        } catch (IOException ignored) {
        }
    }

    private void copyResource(String resource, Path target) throws IOException {
        InputStream in = getClass().getClassLoader().getResourceAsStream(resource);
        if (in == null) {
            throw new IOException("插件资源缺失: " + resource);
        }
        try (in) {
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            Files.copy(in, tmp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** 探测 node 可执行文件：优先配置值，其次 PATH，再重试常见 nvm/mise 目录。 */
    public String detectNode(String configuredPath) throws IOException {
        if (configuredPath != null && !configuredPath.isBlank()) {
            Path p = Path.of(configuredPath);
            if (Files.isExecutable(p)) return p.toString();
            throw new IOException("配置的 node 路径不可执行: " + configuredPath);
        }
        // 让 shell 帮忙在 PATH 里找
        String found = runCommand(List.of("/bin/sh", "-c", "command -v node"), null, 5000).stdout().trim();
        if (!found.isBlank()) return found;

        List<String> candidates = new ArrayList<>();
        String home = System.getProperty("user.home");
        // 动态列举 nvm / mise 下的版本目录
        enumerateNodeBins(Path.of(home, ".nvm", "versions", "node"), candidates);
        enumerateNodeBins(Path.of(home, ".local", "share", "mise", "installs", "node"), candidates);
        candidates.add("/usr/bin/node");
        candidates.add("/usr/local/bin/node");
        for (String c : candidates) {
            if (Files.isExecutable(Path.of(c))) return c;
        }
        throw new IOException("未找到 Node.js。请在插件设置中填写 node 可执行文件路径，或先在服务器上安装 Node.js。");
    }

    private void enumerateNodeBins(Path versionsRoot, List<String> out) {
        if (!Files.isDirectory(versionsRoot)) return;
        try (var stream = Files.list(versionsRoot)) {
            stream.filter(Files::isDirectory)
                .sorted(java.util.Comparator.reverseOrder())
                .forEach(v -> {
                    String p = v.resolve("bin").resolve("node").toString();
                    if (Files.isExecutable(Path.of(p))) out.add(p);
                });
        } catch (IOException ignored) {
        }
    }

    private record CmdResult(int code, String stdout, String stderr) {}

    private CmdResult runCommand(List<String> command, Map<String, String> env, long timeoutMs)
        throws IOException {
        ProcessBuilder pb = new ProcessBuilder(command);
        if (env != null) {
            pb.environment().putAll(env);
        }
        pb.redirectErrorStream(false);
        Process process = pb.start();
        try {
            String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String err = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!process.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new IOException("Node 解析超时（" + timeoutMs + "ms）");
            }
            return new CmdResult(process.exitValue(), out, err);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("等待 Node 进程被中断", e);
        }
    }

    /** 同步调用 node parse.js；放在有界弹性调度器上执行，避免阻塞事件循环。 */
    public Mono<ParseResult> parse(PluginConfig.All config, String cookie, String nodeBin) {
        return Mono.fromCallable(() -> {
            prepareResources();
            Path script = pluginDir().resolve("node").resolve("parse.js");

            Map<String, String> env = Map.of(
                "HS_SERVER", config.source().server(),
                "HS_TYPE", config.source().type(),
                "HS_ID", config.source().id(),
                "HS_COOKIE", cookie == null ? "" : cookie,
                "HS_BITRATE", String.valueOf(config.quality().bitrate()),
                "HS_PIC_SIZE", String.valueOf(config.quality().picSize()),
                "HS_DOWNLOAD_LRC", String.valueOf(config.quality().downloadLyrics()),
                "HS_DOWNLOAD_COVER", String.valueOf(config.quality().downloadCover())
            );

            CmdResult r = runCommand(List.of(nodeBin, script.toString()), env, 180_000);
            String out = r.stdout() == null ? "" : r.stdout().trim();
            if (r.code() != 0 || out.isEmpty()) {
                String msg = out.isEmpty() ? r.stderr() : out;
                throw new IOException("Node 解析失败 (exit=" + r.code() + "): "
                    + (msg == null ? "" : msg.substring(0, Math.min(500, msg.length()))));
            }

            com.fasterxml.jackson.databind.ObjectMapper mapper =
                new com.fasterxml.jackson.databind.ObjectMapper();
            var json = mapper.readTree(out);
            if (!json.path("ok").asBoolean(false)) {
                throw new IOException("Node 解析返回错误: " + json.path("error").asText(""));
            }
            List<ParsedSong> songs = new ArrayList<>();
            var arr = json.path("songs");
            if (arr.isArray()) {
                for (var n : arr) {
                    List<String> artists = new ArrayList<>();
                    var artistNode = n.path("artist");
                    if (artistNode.isArray()) {
                        artistNode.forEach(a -> artists.add(a.asText()));
                    }
                    songs.add(new ParsedSong(
                        n.path("id").asText(""),
                        n.path("name").asText(""),
                        artists,
                        n.path("album").asText(""),
                        nullIfBlank(n.path("audioUrl").asText("")),
                        nullIfBlank(n.path("coverUrl").asText("")),
                        nullIfBlank(n.path("lrc").asText("")),
                        n.path("br").isNumber() ? n.path("br").intValue() : null
                    ));
                }
            }
            return new ParseResult(songs);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private static String nullIfBlank(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}
