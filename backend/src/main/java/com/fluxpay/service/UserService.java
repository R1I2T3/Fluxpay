package com.fluxpay.service;

import com.fluxpay.beans.KycCase;
import com.fluxpay.beans.User;
import com.fluxpay.common.enums.KycStatus;
import com.fluxpay.dto.UpdateProfileRequest;
import com.fluxpay.dto.UserResponse;
import com.fluxpay.repository.KycCaseRepository;
import com.fluxpay.repository.UserRepository;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {
  private final UserRepository users;
  private final KycCaseRepository kycCases;

  public UserService(UserRepository users, KycCaseRepository kycCases) {
    this.users = users;
    this.kycCases = kycCases;
  }

  @Transactional(readOnly = true)
  public UserResponse getProfile(UUID userId) {
    return toResponse(findUser(userId));
  }

  @Transactional
  public UserResponse updateProfile(UUID userId, UpdateProfileRequest request) {
    User user = findUser(userId);
    user.setFullName(request.fullName().trim());
    return toResponse(user);
  }

  private User findUser(UUID userId) {
    return users.findById(userId).orElseThrow(() -> new NoSuchElementException("user not found"));
  }

  private UserResponse toResponse(User user) {
    KycStatus kycStatus =
        kycCases.findByUserId(user.getId()).map(KycCase::getStatus).orElse(KycStatus.NONE);
    return new UserResponse(
        user.getId(), user.getEmail(), user.getFullName(), user.getRole(), kycStatus);
  }
}
