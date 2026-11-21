package ru.hardcode.dashboard;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@Import(TestcontainersConfiguration.class)
@ActiveProfiles("work")
@SpringBootTest
class DashboardApplicationTests {

	@Test
	void contextLoads() {
	}

}
