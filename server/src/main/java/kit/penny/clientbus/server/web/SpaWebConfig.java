package kit.penny.clientbus.server.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class SpaWebConfig implements WebMvcConfigurer {

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {

        registry.addViewController("/inbox")
                .setViewName("forward:/index.html");

        registry.addViewController("/clients")
                .setViewName("forward:/index.html");

        registry.addViewController("/channels")
                .setViewName("forward:/index.html");

        registry.addViewController("/employees")
                .setViewName("forward:/index.html");

        registry.addViewController("/workspaces")
                .setViewName("forward:/index.html");

        registry.addViewController("/settings")
                .setViewName("forward:/index.html");
    }
}