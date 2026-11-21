package ru.hardcode.dashboard.configuration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class HealthSecurityConfiguration {

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http,
			CoordinatorAuthorizationManager coordinatorAccess, CoordinatorSecurityErrors errors) throws Exception {
		return http
				.authorizeHttpRequests(authorize -> authorize
						.requestMatchers(HttpMethod.GET, "/health").permitAll()
						.requestMatchers("/api/**").access(coordinatorAccess)
						.anyRequest().authenticated())
				.exceptionHandling(exceptions -> exceptions
						.defaultAuthenticationEntryPointFor(errors.authenticationEntryPoint(),
								request -> request.getRequestURI().startsWith(request.getContextPath() + "/api/"))
						.accessDeniedHandler(errors.accessDeniedHandler()))
				.httpBasic(Customizer.withDefaults())
				.formLogin(Customizer.withDefaults())
				.build();
	}
}
