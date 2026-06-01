package ru.retail.service.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

@Configuration
public class OpenApiConfig implements WebMvcConfigurer {

    @Override
    public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
        configurer.setDefaultTimeout(-1);
    }

    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Price Aggregator API")
                        .version("1.0.0")
                        .description("""
                    API для агрегации цен на бу запчасти.
                    
                    Возможности:
                    - Сбор цен с Drom.ru через Apify
                    - Расчёт медианной, средней, минимальной, максимальной цены
                    - Рекомендация оптимальной цены
                    """)
                        .contact(new Contact()
                                .name("Владислав")
                                .email("your-email@example.com"))
                        .license(new License()
                                .name("Apache 2.0")
                                .url("https://www.apache.org/licenses/LICENSE-2.0")))
                .servers(List.of(
                        new Server()
                                .url("http://localhost:8080")
                                .description("Локальный сервер"),
                        new Server()
                                .url("http://localhost:8081")
                                .description("Локальный сервер (запасной порт)")
                ));
    }
}
