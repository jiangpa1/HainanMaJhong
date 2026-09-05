package hainanjong;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 海南麻将 Spring Boot 启动类。
 *
 * <p>启动后会自动：加载 Redis / MySQL 配置，注册 Service 与 Listener，
 * 并由 {@link hainanjong.runner.DemoGameRunner} 跑一局演示对局（含持久化）。</p>
 */
@SpringBootApplication
public class HainanMaJhongApplication {

    public static void main(String[] args) {
        SpringApplication.run(HainanMaJhongApplication.class, args);
    }
}
