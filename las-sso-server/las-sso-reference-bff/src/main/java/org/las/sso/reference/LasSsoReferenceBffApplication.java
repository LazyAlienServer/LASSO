package org.las.sso.reference;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class LasSsoReferenceBffApplication {

    public static void main(String[] args) {
        if (java.util.Arrays.asList(args).contains("--revocation-maintenance")) {
            org.las.sso.reference.revocation.RevocationMaintenance.run(args);
            return;
        }
        SpringApplication.run(LasSsoReferenceBffApplication.class, args);
    }
}
