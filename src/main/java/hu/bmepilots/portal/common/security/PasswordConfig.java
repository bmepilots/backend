package hu.bmepilots.portal.common.security;

import java.util.Map;
import org.springframework.context.annotation.*;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.*;

@Configuration
public class PasswordConfig {
  @Bean
  org.springframework.security.core.userdetails.UserDetailsService noDefaultLogin() {
    return username -> {
      throw new org.springframework.security.core.userdetails.UsernameNotFoundException(
          "Use the portal login endpoint");
    };
  }

  @Bean
  PasswordEncoder passwordEncoder() {
    return new DelegatingPasswordEncoder(
        "argon2", Map.of("argon2", new Argon2PasswordEncoder(16, 32, 1, 19456, 2)));
  }
}
