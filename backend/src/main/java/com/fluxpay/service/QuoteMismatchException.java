package com.fluxpay.service;

/** Thrown when the requested route does not match the active quote; mapped to a 409. */
public class QuoteMismatchException extends RuntimeException {
  public QuoteMismatchException(String message) {
    super(message);
  }
}
