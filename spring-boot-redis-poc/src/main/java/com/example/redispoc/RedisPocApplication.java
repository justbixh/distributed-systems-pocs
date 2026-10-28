package com.example.redispoc;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;

@SpringBootApplication
@EnableCaching
public class RedisPocApplication {

    public static void main(String[] args) {
        SpringApplication.run(RedisPocApplication.class, args);
    }
}
