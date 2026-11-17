package run.halo.aplayerbackend;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** 手动同步入口，供控制台或运维调用。 */
@RestController
@RequestMapping("/apis/api.aplayer-backend/v1alpha1")
@RequiredArgsConstructor
public class SyncController {

    private final SyncService syncService;

    @PostMapping("/sync")
    public Mono<ResponseEntity<SyncService.SyncReport>> sync() {
        return syncService.sync().map(report -> {
            if (!report.success()) {
                return ResponseEntity.status(409).body(report);
            }
            return ResponseEntity.ok(report);
        });
    }
}
