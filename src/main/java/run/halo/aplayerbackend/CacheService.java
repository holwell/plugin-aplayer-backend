package run.halo.aplayerbackend;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** 本地缓存：音频/封面/歌词字节 + 播放清单 manifest.json。 */
@Slf4j
@Service
public class CacheService {

    /** 插件上下文未提供 Jackson bean，这里自行持有实例。 */
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String AUDIO_DIR = "audio";
    private static final String COVER_DIR = "cover";
    private static final String LRC_DIR = "lrc";
    private static final String MANIFEST = "manifest.json";

    /** 单曲在 manifest 中的条目。 */
    public record Entry(
        String id,
        String name,
        List<String> artist,
        String album,
        String audio,
        String cover,
        String lrc,
        Integer br
    ) {}

    public record Manifest(
        String server,
        String type,
        String sourceId,
        String syncedAt,
        List<Entry> songs
    ) {}

    public Path cacheRoot(Path pluginDir) {
        return pluginDir.resolve("cache");
    }

    public Path manifestPath(Path pluginDir) {
        return cacheRoot(pluginDir).resolve(MANIFEST);
    }

    public void ensureDirs(Path pluginDir) throws IOException {
        Path root = cacheRoot(pluginDir);
        Files.createDirectories(root.resolve(AUDIO_DIR));
        Files.createDirectories(root.resolve(COVER_DIR));
        Files.createDirectories(root.resolve(LRC_DIR));
    }

    /** 读取现有 manifest，不存在时返回空。 */
    public Manifest readManifest(Path pluginDir) {
        Path p = manifestPath(pluginDir);
        if (!Files.exists(p)) {
            return new Manifest(null, null, null, null, List.of());
        }
        try {
            return objectMapper.readValue(p.toFile(), Manifest.class);
        } catch (Exception e) {
            log.warn("读取 manifest 失败，将重建: {}", e.getMessage());
            return new Manifest(null, null, null, null, List.of());
        }
    }

    /** 原子写入 manifest：先写临时文件再替换。 */
    public void writeManifest(Path pluginDir, Manifest manifest) throws IOException {
        Path p = manifestPath(pluginDir);
        Path tmp = p.resolveSibling(MANIFEST + ".tmp");
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), manifest);
        Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    public Path audioFile(Path pluginDir, String songId) {
        return cacheRoot(pluginDir).resolve(AUDIO_DIR).resolve(safeName(songId) + ".mp3");
    }

    public Path coverFile(Path pluginDir, String songId) {
        return cacheRoot(pluginDir).resolve(COVER_DIR).resolve(safeName(songId) + ".jpg");
    }

    public Path lrcFile(Path pluginDir, String songId) {
        return cacheRoot(pluginDir).resolve(LRC_DIR).resolve(safeName(songId) + ".lrc");
    }

    /** 检查某首歌的音频是否已缓存且非空。 */
    public boolean hasAudio(Path pluginDir, String songId) {
        Path f = audioFile(pluginDir, songId);
        return Files.exists(f) && f.toFile().length() > 0;
    }

    private static String safeName(String id) {
        if (id == null) return "unknown";
        return id.replaceAll("[^A-Za-z0-9_-]", "_");
    }

    /** 供 API 层使用的相对 URL 构造（由 Controller 提供文件路由）。 */
    public static String fileUrl(String kind, String fileName) {
        return "/apis/api.aplayer-backend/v1alpha1/files/" + kind + "/" + fileName;
    }

    /** 把字节安全写入目标文件（临时文件 + 替换）。 */
    public void writeBytesAtomic(Path target, byte[] bytes) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.write(tmp, bytes);
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
    }

    public void writeTextAtomic(Path target, String text) throws IOException {
        writeBytesAtomic(target, text.getBytes(StandardCharsets.UTF_8));
    }

    /** 生成一份初始 manifest 骨架。 */
    public Manifest newManifest(String server, String type, String sourceId) {
        return new Manifest(server, type, sourceId, Instant.now().toString(), new ArrayList<>());
    }
}
