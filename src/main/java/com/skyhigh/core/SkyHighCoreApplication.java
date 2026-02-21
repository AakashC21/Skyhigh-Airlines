package com.skyhigh.core;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class SkyHighCoreApplication {

	public static void main(String[] args) {
		SpringApplication.run(SkyHighCoreApplication.class, args);
	}

}
