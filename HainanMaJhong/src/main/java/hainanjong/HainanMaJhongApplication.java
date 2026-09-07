package hainanjong;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 海南麻将 Spring Boot 启动类。
 *
 * <p>启动后自动：加载 Redis / MySQL 配置，注册 Service、WebSocket 端点与 REST 接口，
 * 静态页面（登录 / 大厅 / 对局）由内嵌 Tomcat 提供。</p>
 */
@SpringBootApplication
public class HainanMaJhongApplication {

    public static void main(String[] args) {
        SpringApplication.run(HainanMaJhongApplication.class, args);
    }
}
