package ticketya.web;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import ticketya.config.NotificacionesProperties;
import tools.jackson.databind.json.JsonMapper;

@Configuration
public class WebConfig {

    @Bean
    FilterRegistrationBean<AutorizacionDeServicioFilter> autorizacionDeServicio(NotificacionesProperties propiedades,
                                                                                JsonMapper json) {
        var registro = new FilterRegistrationBean<>(
                new AutorizacionDeServicioFilter(propiedades.seguridad(), json));
        registro.addUrlPatterns(AutorizacionDeServicioFilter.PREFIJO_INTERNO + "*");
        registro.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return registro;
    }
}
