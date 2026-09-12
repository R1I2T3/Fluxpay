package com.fluxpay.controller;

import com.fluxpay.common.api.ApiResponse;
import com.fluxpay.common.security.CurrentUser;
import com.fluxpay.dto.UpdateProfileRequest;
import com.fluxpay.dto.UserResponse;
import com.fluxpay.service.UserService;
import jakarta.validation.Valid;
import org.slf4j.MDC;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
public class UserController {
  private final UserService userService;

  public UserController(UserService userService) {
    this.userService = userService;
  }

  @GetMapping("/me")
  public ApiResponse<UserResponse> getProfile(@AuthenticationPrincipal CurrentUser currentUser) {
    return envelope(userService.getProfile(currentUser.userId()));
  }

  @PutMapping("/me")
  public ApiResponse<UserResponse> updateProfile(
      @AuthenticationPrincipal CurrentUser currentUser,
      @Valid @RequestBody UpdateProfileRequest request) {
    return envelope(userService.updateProfile(currentUser.userId(), request));
  }

  private ApiResponse<UserResponse> envelope(UserResponse response) {
    String correlationId = MDC.get("correlationId");
    return new ApiResponse<>(correlationId == null ? "none" : correlationId, response);
  }
}
