package com.fluxpay;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.kafka.annotation.EnableKafka;
@SpringBootApplication @EnableKafka
public class FluxPayApplication { public static void main(String[] a){ SpringApplication.run(FluxPayApplication.class,a);} }
