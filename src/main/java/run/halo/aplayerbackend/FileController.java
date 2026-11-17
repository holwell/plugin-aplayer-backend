package run.halo.aplayerbackend;

import java.nio.file.Path;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** 提供缓存文件的只读访问（音频支持 Range 以便拖动进度条）。 */
@RestController
@RequestMapping("/apis/api.aplayer-backend/v1alpha1/files")
@RequiredArgsConstructor
public class FileController {

    private final NodeRuntimeService nodeRuntimeService;
    private final CacheService cacheService;

    @GetMapping("/{kind}/{fileName}")
    public Mono<ResponseEntity<Resource>> serve(
        @PathVariable("kind") String kind,
        @PathVariable("fileName") String fileName
    ) {
        return Mono.fromCallable(() -> resolve(kind, fileName))
            .subscribeOn(Schedulers.boundedElastic())
            .map(path -> {
                if (path == null || !java.nio.file.Files.exists(path)) {
                    return ResponseEntity.notFound().build();
                }
                MediaType type = switch (kind) {
                    case "audio" -> MediaType.parseMediaType("audio/mpeg");
                    case "cover" -> MediaType.IMAGE_JPEG;
                    case "lrc" -> MediaType.parseMediaType("text/plain;charset=UTF-8");
                    default -> MediaType.APPLICATION_OCTET_STREAM;
                };
                return ResponseEntity.ok()
                    .contentType(type)
                    .cacheControl(CacheControl.maxAge(java.time.Duration.ofDays(7)).cachePublic())
                    .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                    .body((Resource) new FileSystemResource(path));
            });
    }

    private Path resolve(String kind, String fileName) {
        // 严格校验文件名，防止路径穿越
        if (!fileName.matches("[A-Za-z0-9_-]+\\.(mp3|jpg|lrc)")) {
            return null;
        }
        Path root = nodeRuntimeService.pluginDir();
        return switch (kind) {
            case "audio" -> cacheService.audioFile(root, fileName.replaceFirst("\\.mp3$", ""));
            case "cover" -> cacheService.coverFile(root, fileName.replaceFirst("\\.jpg$", ""));
            case "lrc" -> cacheService.lrcFile(root, fileName.replaceFirst("\\.lrc$", ""));
            default -> null;
        };
    }
}
