package hu.bmepilots.portal.auth.infrastructure;

import hu.bmepilots.portal.user.application.UserService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.*;

@Configuration
public class BootstrapAdmin {
  @Bean
  ApplicationRunner bootstrap(
      UserService users,
      @Value("${portal.bootstrap.email}") String email,
      @Value("${portal.bootstrap.password}") String password) {
    return args -> {
      if (!email.isBlank() && !password.isBlank()) users.bootstrap(email, password);
    };
  }
}
