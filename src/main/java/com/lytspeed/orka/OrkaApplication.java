package com.lytspeed.orka;

import com.lytspeed.orka.entity.*;
import com.lytspeed.orka.entity.enums.AccessRole;
import com.lytspeed.orka.entity.enums.EmployeeRole;
import com.lytspeed.orka.entity.enums.RequestStatus;
import com.lytspeed.orka.entity.enums.RequestType;
import com.lytspeed.orka.repository.*;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.time.LocalDateTime;

@SpringBootApplication
public class OrkaApplication {

	public static void main(String[] args) {
		SpringApplication.run(OrkaApplication.class, args);
	}

}
