package com.hanyahunya.discovery.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class SecurityConfig {
    private final EurekaSecurityProperties properties;

    public SecurityConfig(EurekaSecurityProperties properties) {
        this.properties = properties;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
                .authorizeHttpRequests(auth -> auth
                        .anyRequest().authenticated()
                )
                .formLogin(AbstractHttpConfigurer::disable)
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(Customizer.withDefaults())
                .build();
    }

    @Bean
    public UserDetailsService userDetailsService(PasswordEncoder encoder) {

        UserDetails admin = User.builder()
                .username(properties.admin().username())
                .password(encoder.encode(properties.admin().password()))
                .roles("ADMIN")
                .build();

        UserDetails gateway = User.builder()
                .username(properties.gateway().username())
                .password(encoder.encode(properties.gateway().password()))
                .roles("SYSTEM")
                .build();

        UserDetails invoker = User.builder()
                .username(properties.invoker().username())
                .password(encoder.encode(properties.invoker().password()))
                .roles("SYSTEM")
                .build();

        UserDetails register = User.builder()
                .username(properties.register().username())
                .password(encoder.encode(properties.register().password()))
                .roles("SYSTEM")
                .build();

        UserDetails provisioner = User.builder()
                .username(properties.provisioner().username())
                .password(encoder.encode(properties.provisioner().password()))
                .roles("SYSTEM")
                .build();

        return new InMemoryUserDetailsManager(admin, gateway, invoker, register, provisioner);
    }
}
