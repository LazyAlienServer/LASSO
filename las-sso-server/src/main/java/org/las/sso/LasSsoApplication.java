package org.las.sso;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class LasSsoApplication {

    public static void main(String[] args) {
        SpringApplication.run(LasSsoApplication.class, args);
    }
}
