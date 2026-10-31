package io.cb.strava.oauth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.NoSuchElementException;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Unit tests for the OAuth token resolution/refresh logic. All collaborators are mocked and a fixed
 * clock makes expiry deterministic.
 */
class StravaOAuthServiceTest {

  // Fixed "now" = 2026-01-01T00:00:00Z
  private static final long NOW_EPOCH = Instant.parse("2026-01-01T00:00:00Z").getEpochSecond();

  private AthleteTokenStore tokenStore;
  private ClientCredentialsProvider credentialsProvider;
  private StravaTokenClient tokenClient;
  private StravaOAuthService service;

  @BeforeEach
  void setUp() {
    tokenStore = mock(AthleteTokenStore.class);
    credentialsProvider = mock(ClientCredentialsProvider.class);
    tokenClient = mock(StravaTokenClient.class);
    Clock fixedClock = Clock.fixed(Instant.ofEpochSecond(NOW_EPOCH), ZoneOffset.UTC);
    service = new StravaOAuthService(tokenStore, credentialsProvider, tokenClient, fixedClock);
  }

  @Test
  void returnsStoredTokenWhenStillValid() {
    AthleteToken stored = token(134815L, "valid-access", "refresh-1", NOW_EPOCH + 3600);
    when(tokenStore.findByAthleteId(134815L)).thenReturn(Optional.of(stored));

    String accessToken = service.getValidAccessToken(134815L);

    assertEquals("valid-access", accessToken);
    verify(tokenClient, never()).refresh(any());
    verify(tokenStore, never()).save(any());
  }

  @Test
  void refreshesAndPersistsWhenExpired() {
    // expiresAt is in the past -> expired
    AthleteToken stored = token(134815L, "old-access", "refresh-1", NOW_EPOCH - 10);
    when(tokenStore.findByAthleteId(134815L)).thenReturn(Optional.of(stored));
    when(credentialsProvider.get())
        .thenReturn(new StravaClientCredentials("client-123", "secret-xyz"));
    when(tokenClient.refresh(any()))
        .thenReturn(
            new StravaTokenResponse(
                "new-access", "refresh-2", NOW_EPOCH + 21600, 21600L, "Bearer"));

    String accessToken = service.getValidAccessToken(134815L);

    assertEquals("new-access", accessToken);

    // The rotated refresh token and new expiry must be persisted.
    ArgumentCaptor<AthleteToken> saved = ArgumentCaptor.forClass(AthleteToken.class);
    verify(tokenStore).save(saved.capture());
    assertEquals("new-access", saved.getValue().getAccessToken());
    assertEquals("refresh-2", saved.getValue().getRefreshToken());
    assertEquals(NOW_EPOCH + 21600, saved.getValue().getExpiresAt());

    // The refresh call used the stored (old) refresh token and our creds.
    ArgumentCaptor<StravaTokenRefreshRequest> req =
        ArgumentCaptor.forClass(StravaTokenRefreshRequest.class);
    verify(tokenClient).refresh(req.capture());
    assertEquals("refresh-1", req.getValue().refreshToken());
    assertEquals("refresh_token", req.getValue().grantType());
    assertEquals("client-123", req.getValue().clientId());
  }

  @Test
  void refreshesWhenWithinExpiryBuffer() {
    // Within the 60s safety buffer -> treated as expired.
    AthleteToken stored = token(134815L, "soon-expiring", "refresh-1", NOW_EPOCH + 30);
    when(tokenStore.findByAthleteId(134815L)).thenReturn(Optional.of(stored));
    when(credentialsProvider.get())
        .thenReturn(new StravaClientCredentials("client-123", "secret-xyz"));
    when(tokenClient.refresh(any()))
        .thenReturn(
            new StravaTokenResponse(
                "fresh-access", "refresh-2", NOW_EPOCH + 21600, 21600L, "Bearer"));

    String accessToken = service.getValidAccessToken(134815L);

    assertEquals("fresh-access", accessToken);
    verify(tokenClient).refresh(any());
  }

  @Test
  void throwsWhenNoStoredTokens() {
    when(tokenStore.findByAthleteId(999L)).thenReturn(Optional.empty());

    assertThrows(NoSuchElementException.class, () -> service.getValidAccessToken(999L));
  }

  private static AthleteToken token(long athleteId, String access, String refresh, long expiresAt) {
    AthleteToken t = new AthleteToken();
    t.setAthleteId(athleteId);
    t.setAccessToken(access);
    t.setRefreshToken(refresh);
    t.setExpiresAt(expiresAt);
    return t;
  }
}
