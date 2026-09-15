package com.fluxpay.dto;

import com.fluxpay.beans.PayoutRoute;
import java.util.List;

/**
 * Recommendation result. {@code quotes} holds every evaluated active route in ranked order (best
 * first); {@code recommended} is the top-ranked route.
 */
public record RouteRecommendation(PayoutRoute recommended, List<RouteQuote> quotes) {}
