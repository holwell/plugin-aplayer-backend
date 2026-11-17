package run.halo.aplayerbackend;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/** 定时同步：轮询配置，按 cron 表达式判断是否跨越了执行点，防止重复触发。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SyncScheduler {

    private final ConfigService configService;
    private final SyncService syncService;

    /** 记录上一次已触发的计划时间点，避免同一分钟重复执行。 */
    private Instant lastFiredScheduledTime = null;

    @Scheduled(fixedDelay = 30_000L, initialDelay = 60_000L)
    public void tick() {
        configService.getAll()
            .flatMap(config -> {
                if (!"cron".equalsIgnoreCase(config.sync().syncMode())) {
                    return Mono.empty();
                }
                String cronText = config.sync().cron();
                CronExpression cron;
                try {
                    cron = CronExpression.parse(cronText);
                } catch (IllegalArgumentException e) {
                    log.warn("cron 表达式无效，跳过定时同步: {}", cronText);
                    return Mono.empty();
                }

                ZoneId zone = ZoneId.systemDefault();
                Instant now = Instant.now();
                // 找最近一个 <= now 的计划执行时间点
                LocalDateTime nowLocal = LocalDateTime.ofInstant(now, zone);
                LocalDateTime scheduled = cron.next(nowLocal.minusMinutes(2));
                if (scheduled == null) return Mono.empty();
                LocalDateTime after = cron.next(scheduled);
                while (after != null && !after.isAfter(nowLocal)) {
                    scheduled = after;
                    after = cron.next(scheduled);
                }
                Instant scheduledInstant = scheduled.atZone(zone).toInstant();

                boolean fresh = Duration.between(scheduledInstant, now).abs().compareTo(Duration.ofMinutes(2)) < 0;
                boolean notFired = lastFiredScheduledTime == null
                    || !lastFiredScheduledTime.equals(scheduledInstant);
                if (!fresh || !notFired) {
                    return Mono.empty();
                }

                lastFiredScheduledTime = scheduledInstant;
                log.info("定时同步触发，计划时间: {}", scheduledInstant);
                return syncService.sync().doOnNext(report ->
                    log.info("定时同步结果: {}", report.message()));
            })
            .onErrorResume(e -> {
                log.warn("定时同步检查异常: {}", e.getMessage());
                return Mono.empty();
            })
            .subscribe();
    }
}
