package hu.bmepilots.portal.user.api;

import hu.bmepilots.portal.common.security.CurrentUser;
import hu.bmepilots.portal.user.application.UserService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class UserController {
  private final UserService users;

  public UserController(UserService users) {
    this.users = users;
  }

  record Version(@Min(0) long version) {}

  record Role(@NotBlank String role, @Min(0) long version) {}

  @GetMapping("/users/me")
  Object me() {
    return users.get(CurrentUser.id());
  }

  @PutMapping("/users/me/password")
  void password(@Valid @RequestBody UserService.PasswordChange c) {
    users.changePassword(CurrentUser.id(), c);
  }

  @GetMapping("/admin/users")
  Object list(
      @RequestParam(required = false) String status, @RequestParam(defaultValue = "0") int page) {
    return users.list(status, page);
  }

  @GetMapping("/admin/registrations")
  Object registrations(@RequestParam(defaultValue = "0") int page) {
    return users.list("PENDING_APPROVAL", page);
  }

  @PostMapping("/admin/registrations/{id}/approve")
  Object approve(@PathVariable String id, @Valid @RequestBody Version v) {
    return users.transition(id, "ACTIVE", v.version());
  }

  @PostMapping("/admin/registrations/{id}/reject")
  Object reject(@PathVariable String id, @Valid @RequestBody Version v) {
    return users.transition(id, "REJECTED", v.version());
  }

  @PostMapping("/admin/users/{id}/suspend")
  Object suspend(@PathVariable String id, @Valid @RequestBody Version v) {
    return users.transition(id, "SUSPENDED", v.version());
  }

  @PostMapping("/admin/users/{id}/disable")
  Object disable(@PathVariable String id, @Valid @RequestBody Version v) {
    return users.transition(id, "DISABLED", v.version());
  }

  @PostMapping("/admin/users/{id}/activate")
  Object activate(@PathVariable String id, @Valid @RequestBody Version v) {
    return users.transition(id, "ACTIVE", v.version());
  }

  @PutMapping("/admin/users/{id}/roles")
  Object role(@PathVariable String id, @Valid @RequestBody Role r) {
    return users.role(id, r.role(), r.version());
  }
}
