package com.algotrader;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableCaching
@EnableAsync
@EnableScheduling
public class AlgotraderApplication {

    public static void main(String[] args) {
        SpringApplication.run(AlgotraderApplication.class, args);
    }
}
