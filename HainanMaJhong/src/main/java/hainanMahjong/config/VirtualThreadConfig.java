package hainanMahjong.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Boot 2.7 has no spring.threads.virtual.enabled switch; configure Tomcat explicitly. */
@Configuration(proxyBeanMethods = false)
public class VirtualThreadConfig {
    @Bean(destroyMethod = "shutdown")
    public ExecutorService httpVirtualExecutor() {
        return Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("mahjong-http-", 0).factory());
    }

    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> virtualTomcat(
            @Qualifier("httpVirtualExecutor") ExecutorService executor) {
        return factory -> factory.addConnectorCustomizers(connector ->
                connector.getProtocolHandler().setExecutor(executor));
    }
}
