package ru.itmo.hhprocess;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class HHProcessApplication {

    public static void main(String[] args) {
        SpringApplication.run(HHProcessApplication.class, args);
    }
}
