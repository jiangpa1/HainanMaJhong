package hainanMahjong.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import javax.validation.Valid;
import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.DecimalMax;
import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;
import java.util.List;

/** Per-process admission budgets, bound and validated once at startup. */
@Component
@Validated
@ConfigurationProperties(prefix = "traffic-protection")
@Getter
@Setter
public class TrafficProtectionProperties {
    private boolean enabled = true;
    @Min(64) @Max(100000) private int maxKeys = 10000;
    @Min(60) @Max(86400) private int idleSeconds = 600;
    @Min(1) @Max(60) private int busyRetrySeconds = 2;
    // Only these actual TCP peers may supply a forwarded client address.
    @NotNull private List<String> trustedProxies = List.of("127.0.0.1", "::1");

    // IP 层必须低于同一组的 global：否则单个来源可以把整份全局额度吃光，该层形同未配。
    // 取值 = global 的 60%：既约束单来源，又容得下同 IP 的合理 NAT 人群（约 60 个玩家同时用满每人额度）。
    @Valid @NotNull private Policy login = new Policy(1, new Rate(3, 3), new Rate(0.1, 3), new Rate(1, 10));
    @Valid @NotNull private Policy records = new Policy(48, new Rate(800, 100), new Rate(8, 16), new Rate(480, 60));
    @Valid @NotNull private Policy legacy = new Policy(8, new Rate(20, 4), new Rate(0.5, 2), new Rate(12, 3));

    @Getter @Setter
    public static class Policy {
        @Min(1) @Max(128) private int concurrency;
        @Valid @NotNull private Rate global;
        // For login this key is the normalized account; for reads it is the authenticated user id.
        @Valid @NotNull private Rate principal;
        @Valid @NotNull private Rate ip;

        public Policy() {}
        public Policy(int concurrency, Rate global, Rate principal, Rate ip) {
            this.concurrency = concurrency;
            this.global = global;
            this.principal = principal;
            this.ip = ip;
        }
    }

    @Getter @Setter
    public static class Rate {
        @DecimalMin("0.001") @DecimalMax("100000") private double perSecond;
        @Min(1) @Max(10000) private int burst;
        public Rate() {}
        public Rate(double perSecond, int burst) { this.perSecond = perSecond; this.burst = burst; }
    }
}
