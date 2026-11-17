package run.halo.aplayerbackend;

/** 配置分组常量与值对象。 */
public final class PluginConfig {

    private PluginConfig() {}

    public record Source(
        String server,
        String type,
        String id,
        String cookieSecretName
    ) {
        public static final String GROUP = "source";
    }

    public record Quality(
        String bitrate,
        Integer picSize,
        Boolean downloadLyrics,
        Boolean downloadCover
    ) {
        public static final String GROUP = "quality";
    }

    public record Sync(
        String syncMode,
        String cron,
        Boolean incremental
    ) {
        public static final String GROUP = "sync";
    }

    public record Access(
        Boolean publicRead,
        String allowOrigins
    ) {
        public static final String GROUP = "access";
    }

    public record Runtime(
        String nodePath
    ) {
        public static final String GROUP = "runtime";
    }

    /** 聚合配置，便于各服务一次性拿到全部参数。 */
    public record All(Source source, Quality quality, Sync sync, Access access, Runtime runtime) {}
}
