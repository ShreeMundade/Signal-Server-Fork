/*
 * Copyright 2013 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.whispersystems.textsecuregcm.controllers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.refEq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableSet;
import io.dropwizard.auth.PolymorphicAuthValueFactoryProvider;
import io.dropwizard.testing.junit5.DropwizardExtensionsSupport;
import io.dropwizard.testing.junit5.ResourceExtension;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.stream.Stream;
import javax.ws.rs.client.Entity;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedHashMap;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;
import org.assertj.core.api.Condition;
import org.glassfish.jersey.server.ServerProperties;
import org.glassfish.jersey.test.grizzly.GrizzlyWebTestContainerFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.signal.libsignal.protocol.IdentityKey;
import org.signal.libsignal.protocol.ServiceId;
import org.signal.libsignal.protocol.ecc.Curve;
import org.signal.libsignal.zkgroup.InvalidInputException;
import org.signal.libsignal.zkgroup.ServerPublicParams;
import org.signal.libsignal.zkgroup.ServerSecretParams;
import org.signal.libsignal.zkgroup.VerificationFailedException;
import org.signal.libsignal.zkgroup.profiles.ClientZkProfileOperations;
import org.signal.libsignal.zkgroup.profiles.ExpiringProfileKeyCredentialResponse;
import org.signal.libsignal.zkgroup.profiles.ProfileKey;
import org.signal.libsignal.zkgroup.profiles.ProfileKeyCommitment;
import org.signal.libsignal.zkgroup.profiles.ProfileKeyCredentialRequest;
import org.signal.libsignal.zkgroup.profiles.ProfileKeyCredentialRequestContext;
import org.signal.libsignal.zkgroup.profiles.ServerZkProfileOperations;
import org.whispersystems.textsecuregcm.auth.AuthenticatedAccount;
import org.whispersystems.textsecuregcm.auth.DisabledPermittedAuthenticatedAccount;
import org.whispersystems.textsecuregcm.auth.OptionalAccess;
import org.whispersystems.textsecuregcm.configuration.BadgeConfiguration;
import org.whispersystems.textsecuregcm.configuration.BadgesConfiguration;
import org.whispersystems.textsecuregcm.configuration.dynamic.DynamicConfiguration;
import org.whispersystems.textsecuregcm.configuration.dynamic.DynamicPaymentsConfiguration;
import org.whispersystems.textsecuregcm.entities.Badge;
import org.whispersystems.textsecuregcm.entities.BadgeSvg;
import org.whispersystems.textsecuregcm.entities.BaseProfileResponse;
import org.whispersystems.textsecuregcm.entities.BatchIdentityCheckRequest;
import org.whispersystems.textsecuregcm.entities.BatchIdentityCheckResponse;
import org.whispersystems.textsecuregcm.entities.CreateProfileRequest;
import org.whispersystems.textsecuregcm.entities.ExpiringProfileKeyCredentialProfileResponse;
import org.whispersystems.textsecuregcm.entities.ProfileAvatarUploadAttributes;
import org.whispersystems.textsecuregcm.entities.VersionedProfileResponse;
import org.whispersystems.textsecuregcm.identity.AciServiceIdentifier;
import org.whispersystems.textsecuregcm.identity.IdentityType;
import org.whispersystems.textsecuregcm.identity.PniServiceIdentifier;
import org.whispersystems.textsecuregcm.identity.ServiceIdentifier;
import org.whispersystems.textsecuregcm.limits.RateLimiter;
import org.whispersystems.textsecuregcm.limits.RateLimiters;
import org.whispersystems.textsecuregcm.mappers.RateLimitExceededExceptionMapper;
import org.whispersystems.textsecuregcm.s3.PolicySigner;
import org.whispersystems.textsecuregcm.s3.PostPolicyGenerator;
import org.whispersystems.textsecuregcm.storage.Account;
import org.whispersystems.textsecuregcm.storage.AccountBadge;
import org.whispersystems.textsecuregcm.storage.AccountsManager;
import org.whispersystems.textsecuregcm.storage.DynamicConfigurationManager;
import org.whispersystems.textsecuregcm.storage.ProfilesManager;
import org.whispersystems.textsecuregcm.storage.VersionedProfile;
import org.whispersystems.textsecuregcm.tests.util.AccountsHelper;
import org.whispersystems.textsecuregcm.tests.util.AuthHelper;
import org.whispersystems.textsecuregcm.tests.util.ProfileHelper;
import org.whispersystems.textsecuregcm.util.SystemMapper;
import org.whispersystems.textsecuregcm.util.TestClock;
import org.whispersystems.textsecuregcm.util.Util;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;

@ExtendWith(DropwizardExtensionsSupport.class)
class ProfileControllerTest {

  private static final Clock clock = TestClock.pinned(Instant.ofEpochSecond(42));
  private static final AccountsManager accountsManager = mock(AccountsManager.class);
  private static final ProfilesManager profilesManager = mock(ProfilesManager.class);
  private static final RateLimiters rateLimiters = mock(RateLimiters.class);
  private static final RateLimiter rateLimiter = mock(RateLimiter.class);
  private static final RateLimiter usernameRateLimiter = mock(RateLimiter.class);

  private static final S3Client s3client = mock(S3Client.class);
  private static final PostPolicyGenerator postPolicyGenerator = new PostPolicyGenerator("us-west-1", "profile-bucket",
      "accessKey");
  private static final PolicySigner policySigner = new PolicySigner("accessSecret", "us-west-1");
  private static final ServerZkProfileOperations zkProfileOperations = mock(ServerZkProfileOperations.class);

  private static final byte[] UNIDENTIFIED_ACCESS_KEY = "test-uak".getBytes(StandardCharsets.UTF_8);
  private static final IdentityKey ACCOUNT_IDENTITY_KEY = new IdentityKey(Curve.generateKeyPair().getPublicKey());
  private static final IdentityKey ACCOUNT_PHONE_NUMBER_IDENTITY_KEY = new IdentityKey(Curve.generateKeyPair().getPublicKey());
  private static final IdentityKey ACCOUNT_TWO_IDENTITY_KEY = new IdentityKey(Curve.generateKeyPair().getPublicKey());
  private static final IdentityKey ACCOUNT_TWO_PHONE_NUMBER_IDENTITY_KEY = new IdentityKey(Curve.generateKeyPair().getPublicKey());
  private static final String BASE_64_URL_USERNAME_HASH = "9p6Tip7BFefFOJzv4kv4GyXEYsBVfk_WbjNejdlOvQE";
  private static final byte[] USERNAME_HASH = Base64.getUrlDecoder().decode(BASE_64_URL_USERNAME_HASH);
  @SuppressWarnings("unchecked")
  private static final DynamicConfigurationManager<DynamicConfiguration> dynamicConfigurationManager = mock(
      DynamicConfigurationManager.class);

  private DynamicPaymentsConfiguration dynamicPaymentsConfiguration;
  private Account profileAccount;

  private static final ResourceExtension resources = ResourceExtension.builder()
      .addProperty(ServerProperties.UNWRAP_COMPLETION_STAGE_IN_WRITER_ENABLE, Boolean.TRUE)
      .addProvider(AuthHelper.getAuthFilter())
      .addProvider(new PolymorphicAuthValueFactoryProvider.Binder<>(
          ImmutableSet.of(AuthenticatedAccount.class, DisabledPermittedAuthenticatedAccount.class)))
      .addProvider(new RateLimitExceededExceptionMapper())
      .setMapper(SystemMapper.jsonMapper())
      .setTestContainerFactory(new GrizzlyWebTestContainerFactory())
      .addResource(new ProfileController(
          clock,
          rateLimiters,
          accountsManager,
          profilesManager,
          dynamicConfigurationManager,
          (acceptableLanguages, accountBadges, isSelf) -> List.of(new Badge("TEST", "other", "Test Badge",
              "This badge is in unit tests.", List.of("l", "m", "h", "x", "xx", "xxx"), "SVG", List.of(new BadgeSvg("sl", "sd"), new BadgeSvg("ml", "md"), new BadgeSvg("ll", "ld")))
          ),
          new BadgesConfiguration(List.of(
              new BadgeConfiguration("TEST", "other", List.of("l", "m", "h", "x", "xx", "xxx"), "SVG", List.of(new BadgeSvg("sl", "sd"), new BadgeSvg("ml", "md"), new BadgeSvg("ll", "ld"))),
              new BadgeConfiguration("TEST1", "testing", List.of("l", "m", "h", "x", "xx", "xxx"), "SVG", List.of(new BadgeSvg("sl", "sd"), new BadgeSvg("ml", "md"), new BadgeSvg("ll", "ld"))),
              new BadgeConfiguration("TEST2", "testing", List.of("l", "m", "h", "x", "xx", "xxx"), "SVG", List.of(new BadgeSvg("sl", "sd"), new BadgeSvg("ml", "md"), new BadgeSvg("ll", "ld"))),
              new BadgeConfiguration("TEST3", "testing", List.of("l", "m", "h", "x", "xx", "xxx"), "SVG", List.of(new BadgeSvg("sl", "sd"), new BadgeSvg("ml", "md"), new BadgeSvg("ll", "ld")))
          ), List.of("TEST1"), Map.of(1L, "TEST1", 2L, "TEST2", 3L, "TEST3")),
          s3client,
          postPolicyGenerator,
          policySigner,
          "profilesBucket",
          zkProfileOperations,
          Executors.newSingleThreadExecutor()))
      .build();

  @BeforeEach
  void setup() {
    reset(s3client);

    AccountsHelper.setupMockUpdate(accountsManager);

    dynamicPaymentsConfiguration = mock(DynamicPaymentsConfiguration.class);
    final DynamicConfiguration dynamicConfiguration = mock(DynamicConfiguration.class);

    when(dynamicConfigurationManager.getConfiguration()).thenReturn(dynamicConfiguration);
    when(dynamicConfiguration.getPaymentsConfiguration()).thenReturn(dynamicPaymentsConfiguration);
    when(dynamicPaymentsConfiguration.getDisallowedPrefixes()).thenReturn(Collections.emptyList());

    when(rateLimiters.getProfileLimiter()).thenReturn(rateLimiter);
    when(rateLimiters.getUsernameLookupLimiter()).thenReturn(usernameRateLimiter);

    profileAccount = mock(Account.class);

    when(profileAccount.getIdentityKey()).thenReturn(ACCOUNT_TWO_IDENTITY_KEY);
    when(profileAccount.getIdentityKey(IdentityType.ACI)).thenReturn(ACCOUNT_TWO_IDENTITY_KEY);
    when(profileAccount.getPhoneNumberIdentityKey()).thenReturn(ACCOUNT_TWO_PHONE_NUMBER_IDENTITY_KEY);
    when(profileAccount.getIdentityKey(IdentityType.PNI)).thenReturn(ACCOUNT_TWO_PHONE_NUMBER_IDENTITY_KEY);
    when(profileAccount.getUuid()).thenReturn(AuthHelper.VALID_UUID_TWO);
    when(profileAccount.getPhoneNumberIdentifier()).thenReturn(AuthHelper.VALID_PNI_TWO);
    when(profileAccount.isEnabled()).thenReturn(true);
    when(profileAccount.getCurrentProfileVersion()).thenReturn(Optional.empty());
    when(profileAccount.getUsernameHash()).thenReturn(Optional.of(USERNAME_HASH));
    when(profileAccount.getUnidentifiedAccessKey()).thenReturn(Optional.of("1337".getBytes()));

    Account capabilitiesAccount = mock(Account.class);

    when(capabilitiesAccount.getUuid()).thenReturn(AuthHelper.VALID_UUID);
    when(capabilitiesAccount.getIdentityKey()).thenReturn(ACCOUNT_IDENTITY_KEY);
    when(capabilitiesAccount.getIdentityKey(IdentityType.ACI)).thenReturn(ACCOUNT_IDENTITY_KEY);
    when(capabilitiesAccount.getPhoneNumberIdentityKey()).thenReturn(ACCOUNT_PHONE_NUMBER_IDENTITY_KEY);
    when(capabilitiesAccount.getIdentityKey(IdentityType.PNI)).thenReturn(ACCOUNT_PHONE_NUMBER_IDENTITY_KEY);
    when(capabilitiesAccount.isEnabled()).thenReturn(true);

    when(accountsManager.getByServiceIdentifier(any())).thenReturn(Optional.empty());

    when(accountsManager.getByE164(AuthHelper.VALID_NUMBER_TWO)).thenReturn(Optional.of(profileAccount));
    when(accountsManager.getByAccountIdentifier(AuthHelper.VALID_UUID_TWO)).thenReturn(Optional.of(profileAccount));
    when(accountsManager.getByPhoneNumberIdentifier(AuthHelper.VALID_PNI_TWO)).thenReturn(Optional.of(profileAccount));
    when(accountsManager.getByServiceIdentifier(new AciServiceIdentifier(AuthHelper.VALID_UUID_TWO))).thenReturn(Optional.of(profileAccount));
    when(accountsManager.getByServiceIdentifier(new PniServiceIdentifier(AuthHelper.VALID_PNI_TWO))).thenReturn(Optional.of(profileAccount));
    when(accountsManager.getByUsernameHash(USERNAME_HASH)).thenReturn(Optional.of(profileAccount));

    when(accountsManager.getByE164(AuthHelper.VALID_NUMBER)).thenReturn(Optional.of(capabilitiesAccount));
    when(accountsManager.getByAccountIdentifier(AuthHelper.VALID_UUID)).thenReturn(Optional.of(capabilitiesAccount));
    when(accountsManager.getByServiceIdentifier(new AciServiceIdentifier(AuthHelper.VALID_UUID))).thenReturn(Optional.of(capabilitiesAccount));

    final byte[] name = ProfileHelper.generateRandomByteArray(81);
    final byte[] emoji = ProfileHelper.generateRandomByteArray(60);
    final byte[] about = ProfileHelper.generateRandomByteArray(156);

    when(profilesManager.get(eq(AuthHelper.VALID_UUID), eq("someversion"))).thenReturn(Optional.empty());
    when(profilesManager.get(eq(AuthHelper.VALID_UUID_TWO), eq("validversion"))).thenReturn(Optional.of(new VersionedProfile(
        "validversion", name, "profiles/validavatar", emoji, about, null, "validcommitmnet".getBytes())));

    clearInvocations(rateLimiter);
    clearInvocations(accountsManager);
    clearInvocations(usernameRateLimiter);
    clearInvocations(profilesManager);
    clearInvocations(zkProfileOperations);
  }

  @AfterEach
  void teardown() {
    reset(accountsManager);
    reset(rateLimiter);
  }

  @Test
  void testProfileGetByAci() throws RateLimitExceededException {
    final BaseProfileResponse profile = resources.getJerseyTest()
                              .target("/v1/profile/" + AuthHelper.VALID_UUID_TWO)
                              .request()
                              .header("Authorization", AuthHelper.getAuthHeader(AuthHelper.VALID_UUID, AuthHelper.VALID_PASSWORD))
                              .get(BaseProfileResponse.class);

    assertThat(profile.getIdentityKey()).isEqualTo(ACCOUNT_TWO_IDENTITY_KEY);
    assertThat(profile.getBadges()).hasSize(1).element(0).has(new Condition<>(
        badge -> "Test Badge".equals(badge.getName()), "has badge with expected name"));

    verify(accountsManager).getByAccountIdentifier(AuthHelper.VALID_UUID_TWO);
    verify(rateLimiter, times(1)).validate(AuthHelper.VALID_UUID);
  }

  @Test
  void testProfileGetByAciRateLimited() throws RateLimitExceededException {
    doThrow(new RateLimitExceededException(Duration.ofSeconds(13), true)).when(rateLimiter)
        .validate(AuthHelper.VALID_UUID);

    final Response response = resources.getJerseyTest()
        .target("/v1/profile/" + AuthHelper.VALID_UUID_TWO)
        .request()
        .header("Authorization", AuthHelper.getAuthHeader(AuthHelper.VALID_UUID, AuthHelper.VALID_PASSWORD))
        .get();

    assertThat(response.getStatus()).isEqualTo(413);
    assertThat(response.getHeaderString("Retry-After")).isEqualTo(String.valueOf(Duration.ofSeconds(13).toSeconds()));
  }

  @Test
  void testProfileGetByAciUnidentified() throws RateLimitExceededException {
    final BaseProfileResponse profile = resources.getJerseyTest()
        .target("/v1/profile/" + AuthHelper.VALID_UUID_TWO)
        .request()
        .header(OptionalAccess.UNIDENTIFIED, AuthHelper.getUnidentifiedAccessHeader("1337".getBytes()))
        .get(BaseProfileResponse.class);

    assertThat(profile.getIdentityKey()).isEqualTo(ACCOUNT_TWO_IDENTITY_KEY);
    assertThat(profile.getBadges()).hasSize(1).element(0).has(new Condition<>(
        badge -> "Test Badge".equals(badge.getName()), "has badge with expected name"));

    verify(accountsManager).getByAccountIdentifier(AuthHelper.VALID_UUID_TWO);
    verify(rateLimiter, never()).validate(AuthHelper.VALID_UUID);
  }

  @Test
  void testProfileGetByAciUnidentifiedBadKey() {
    final Response response = resources.getJerseyTest()
        .target("/v1/profile/" + AuthHelper.VALID_UUID_TWO)
        .request()
        .header(OptionalAccess.UNIDENTIFIED, AuthHelper.getUnidentifiedAccessHeader("incorrect".getBytes()))
        .get();

    assertThat(response.getStatus()).isEqualTo(401);
  }

  @Test
  void testProfileGetByAciUnidentifiedAccountNotFound() {
    final Response response = resources.getJerseyTest()
        .target("/v1/profile/" + UUID.randomUUID())
        .request()
        .header(OptionalAccess.UNIDENTIFIED, AuthHelper.getUnidentifiedAccessHeader("1337".getBytes()))
        .get();

    assertThat(response.getStatus()).isEqualTo(401);
  }

  @Test
  void testProfileGetByPni() throws RateLimitExceededException {
    final BaseProfileResponse profile = resources.getJerseyTest()
        .target("/v1/profile/PNI:" + AuthHelper.VALID_PNI_TWO)
        .request()
        .header("Authorization", AuthHelper.getAuthHeader(AuthHelper.VALID_UUID, AuthHelper.VALID_PASSWORD))
        .get(BaseProfileResponse.class);

    assertThat(profile.getIdentityKey()).isEqualTo(ACCOUNT_TWO_PHONE_NUMBER_IDENTITY_KEY);
    assertThat(profile.getBadges()).isEmpty();
    assertThat(profile.getUuid()).isEqualTo(new PniServiceIdentifier(AuthHelper.VALID_PNI_TWO));
    assertThat(profile.getCapabilities()).isNotNull();
    assertThat(profile.isUnrestrictedUnidentifiedAccess()).isFalse();
    assertThat(profile.getUnidentifiedAccess()).isNull();

    verify(accountsManager).getByPhoneNumberIdentifier(AuthHelper.VALID_PNI_TWO);
    verify(rateLimiter, times(1)).validate(AuthHelper.VALID_UUID);
  }

  @Test
  void testProfileGetByPniRateLimited() throws RateLimitExceededException {
    doThrow(new RateLimitExceededException(Duration.ofSeconds(13), true)).when(rateLimiter)
        .validate(AuthHelper.VALID_UUID);

    final Response response = resources.getJerseyTest()
        .target("/v1/profile/" + AuthHelper.VALID_PNI_TWO)
        .request()
        .header("Authorization", AuthHelper.getAuthHeader(AuthHelper.VALID_UUID, AuthHelper.VALID_PASSWORD))
        .get();

    assertThat(response.getStatus()).isEqualTo(413);
    assertThat(response.getHeaderString("Retry-After")).isEqualTo(String.valueOf(Duration.ofSeconds(13).toSeconds()));
  }

  @Test
  void testProfileGetByPniUnidentified() throws RateLimitExceededException {
    final Response response = resources.getJerseyTest()
        .target("/v1/profile/PNI:" + AuthHelper.VALID_PNI_TWO)
        .request()
        .header(OptionalAccess.UNIDENTIFIED, AuthHelper.getUnidentifiedAccessHeader("1337".getBytes()))
        .get();

    assertThat(response.getStatus()).isEqualTo(401);

    verify(accountsManager).getByPhoneNumberIdentifier(AuthHelper.VALID_PNI_TWO);
    verify(rateLimiter, never()).validate(AuthHelper.VALID_UUID);
  }

  @Test
  void testProfileGetByPniUnidentifiedBadKey() {
    final Response response = resources.getJerseyTest()
        .target("/v1/profile/" + AuthHelper.VALID_PNI_TWO)
        .request()
        .header(OptionalAccess.UNIDENTIFIED, AuthHelper.getUnidentifiedAccessHeader("incorrect".getBytes()))
        .get();

    assertThat(response.getStatus()).isEqualTo(401);
  }

  @Test
  void testProfileGetUnauthorized() {
    final Response response = resources.getJerseyTest()
        .target("/v1/profile/" + AuthHelper.VALID_UUID_TWO)
        .request()
        .get();

    assertThat(response.getStatus()).isEqualTo(401);
  }


  @Test
  void testProfileGetDisabled() {
    final Response response = resources.getJerseyTest()
        .target("/v1/profile/" + AuthHelper.VALID_UUID_TWO)
        .request()
        .header("Authorization", AuthHelper.getAuthHeader(AuthHelper.DISABLED_UUID, AuthHelper.DISABLED_PASSWORD))
        .get();

    assertThat(response.getStatus()).isEqualTo(401);
  }

  @Test
  void testProfileCapabilities() {
    final BaseProfileResponse profile = resources.getJerseyTest()
        .target("/v1/profile/" + AuthHelper.VALID_UUID)
        .request()
        .header("Authorization", AuthHelper.getAuthHeader(AuthHelper.VALID_UUID, AuthHelper.VALID_PASSWORD))
        .get(BaseProfileResponse.class);

    assertThat(profile.getCapabilities().gv1Migration()).isTrue();
    assertThat(profile.getCapabilities().senderKey()).isTrue();
    assertThat(profile.getCapabilities().announcementGroup()).isTrue();
  }

  @Test
  void testSetProfileWantAvatarUpload() throws InvalidInputException {
    final ProfileKeyCommitment commitment = new ProfileKey(new byte[32]).getCommitment(new ServiceId.Aci(AuthHelper.VALID_UUID));
    final byte[] name = ProfileHelper.generateRandomByteArray(81);

    final ProfileAvatarUploadAttributes uploadAttributes = resources.getJerseyTest()
        .target("/v1/profile/")
        .request()
        .header("Authorization", AuthHelper.getAuthHeader(AuthHelper.VALID_UUID, AuthHelper.VALID_PASSWORD))
        .put(Entity.entity(new CreateProfileRequest(commitment, "someversion",
            ProfileHelper.encodeToBase64(name), null, null,
            null, true, false, List.of()), MediaType.APPLICATION_JSON_TYPE), ProfileAvatarUploadAttributes.class);

    final ArgumentCaptor<VersionedProfile> profileArgumentCaptor = ArgumentCaptor.forClass(VersionedProfile.class);

    verify(profilesManager, times(1)).get(eq(AuthHelper.VALID_UUID), eq("someversion"));
    verify(profilesManager, times(1)).set(eq(AuthHelper.VALID_UUID), profileArgumentCaptor.capture());

    verifyNoMoreInteractions(s3client);

    assertThat(profileArgumentCaptor.getValue().commitment()).isEqualTo(commitment.serialize());
    assertThat(profileArgumentCaptor.getValue().avatar()).isEqualTo(uploadAttributes.getKey());
    assertThat(profileArgumentCaptor.getValue().version()).isEqualTo("someversion");
    assertThat(profileArgumentCaptor.getValue().name()).isEqualTo(name);
    assertThat(profileArgumentCaptor.getValue().aboutEmoji()).isNull();
    assertThat(profileArgumentCaptor.getValue().about()).isNull();
  }

  @Test
  void testSetProfileWantAvatarUploadWithBadProfileSize() throws InvalidInputException {
    final ProfileKeyCommitment commitment = new ProfileKey(new byte[32]).getCommitment(new ServiceId.Aci(AuthHelper.VALID_UUID));
    final String name = ProfileHelper.generateRandomBase64FromByteArray(82);

    try (final Response response = resources.getJerseyTest()
        .target("/v1/profile/")
        .request()
        .header("Authorization", AuthHelper.getAuthHeader(AuthHelper.VALID_UUID, AuthHelper.VALID_PASSWORD))
        .put(Entity.entity(new CreateProfileRequest(commitment, "someversion", name,
            null, null, null, true, false, List.of()), MediaType.APPLICATION_JSON_TYPE))) {

      assertThat(response.getStatus()).isEqualTo(422);
    }
  }

  @Test
  void testSetProfileWithoutAvatarUpload() throws InvalidInputException {
    final ProfileKeyCommitment commitment = new ProfileKey(new byte[32]).getCommitment(new ServiceId.Aci(AuthHelper.VALID_UUID));
    final byte[] name = ProfileHelper.generateRandomByteArray(81);

    clearInvocations(AuthHelper.VALID_ACCOUNT_TWO);

    try (final Response response = resources.getJerseyTest()
        .target("/v1/profile/")
        .request()
        .header("Authorization", AuthHelper.getAuthHeader(AuthHelper.VALID_UUID_TWO, AuthHelper.VALID_PASSWORD_TWO))
        .put(Entity.entity(new CreateProfileRequest(commitment, "anotherversion", ProfileHelper.encodeToBase64(name), null, null,
            null, false, false, List.of()), MediaType.APPLICATION_JSON_TYPE))) {

      assertThat(response.getStatus()).isEqualTo(200);
      assertThat(response.hasEntity()).isFalse();

      final ArgumentCaptor<VersionedProfile> profileArgumentCaptor = ArgumentCaptor.forClass(VersionedProfile.class);

      verify(profilesManager, times(1)).get(eq(AuthHelper.VALID_UUID_TWO), eq("anotherversion"));
      verify(profilesManager, times(1)).set(eq(AuthHelper.VALID_UUID_TWO), profileArgumentCaptor.capture());

      verifyNoMoreInteractions(s3client);

      assertThat(profileArgumentCaptor.getValue().commitment()).isEqualTo(commitment.serialize());
      assertThat(profileArgumentCaptor.getValue().avatar()).isNull();
      assertThat(profileArgumentCaptor.getValue().version()).isEqualTo("anotherversion");
      assertThat(profileArgumentCaptor.getValue().name()).isEqualTo(name);
      assertThat(profileArgumentCaptor.getValue().aboutEmoji()).isNull();
      assertThat(profileArgumentCaptor.getValue().about()).isNull();
    }
  }

  @Test
  void testSetProfileWithAvatarUploadAndPreviousAvatar() throws InvalidInputException {
    final ProfileKeyCommitment commitment = new ProfileKey(new byte[32]).getCommitment(new ServiceId.Aci(AuthHelper.VALID_UUID_TWO));
    final byte[] name = ProfileHelper.generateRandomByteArray(81);

    resources.getJerseyTest()
        .target("/v1/profile/")
        .request()
        .header("Authorization", AuthHelper.getAuthHeader(AuthHelper.VALID_UUID_TWO, AuthHelper.VALID_PASSWORD_TWO))
        .put(Entity.entity(new CreateProfileRequest(commitment, "validversion",
            ProfileHelper.encodeToBase64(name), null, null,
            null, true, false, List.of()), MediaType.APPLICATION_JSON_TYPE), ProfileAvatarUploadAttributes.class);

    final ArgumentCaptor<VersionedProfile> profileArgumentCaptor = ArgumentCaptor.forClass(VersionedProfile.class);

    verify(profilesManager, times(1)).get(eq(AuthHelper.VALID_UUID_TWO), eq("validversion"));
    verify(profilesManager, times(1)).set(eq(AuthHelper.VALID_UUID_TWO), profileArgumentCaptor.capture());
    verify(s3client, times(1)).deleteObject(eq(DeleteObjectRequest.builder().bucket("profilesBucket").key("profiles/validavatar").build()));

    assertThat(profileArgumentCaptor.getValue().commitment()).isEqualTo(commitment.serialize());
    assertThat(profileArgumentCaptor.getValue().avatar()).startsWith("profiles/");
    assertThat(profileArgumentCaptor.getValue().version()).isEqualTo("validversion");
    assertThat(profileArgumentCaptor.getValue().name()).isEqualTo(name);
    assertThat(profileArgumentCaptor.getValue().aboutEmoji()).isNull();
    assertThat(profileArgumentCaptor.getValue().about()).isNull();
  }

  @Test
  void testSetProfileClearPreviousAvatar() throws InvalidInputException {
    final ProfileKeyCommitment commitment = new ProfileKey(new byte[32]).getCommitment(new ServiceId.Aci(AuthHelper.VALID_UUID_TWO));
    final byte[] name = ProfileHelper.generateRandomByteArray(81);

    try (final Response response = resources.getJerseyTest()
        .target("/v1/profile/")
        .request()
        .header("Authorization", AuthHelper.getAuthHeader(AuthHelper.VALID_UUID_TWO, AuthHelper.VALID_PASSWORD_TWO))
        .put(Entity.entity(new CreateProfileRequest(commitment, "validversion", ProfileHelper.encodeToBase64(name),
            null, null, null, false, false, List.of()), MediaType.APPLICATION_JSON_TYPE))) {

      assertThat(response.getStatus()).isEqualTo(200);
      assertThat(response.hasEntity()).isFalse();

      final ArgumentCaptor<VersionedProfile> profileArgumentCaptor = ArgumentCaptor.forClass(VersionedProfile.class);

      verify(profilesManager, times(1)).get(eq(AuthHelper.VALID_UUID_TWO), eq("validversion"));
      verify(profilesManager, times(1)).set(eq(AuthHelper.VALID_UUID_TWO), profileArgumentCaptor.capture());
      verify(s3client, times(1)).deleteObject(eq(DeleteObjectRequest.builder().bucket("profilesBucket").key("profiles/validavatar").build()));

      assertThat(profileArgumentCaptor.getValue().commitment()).isEqualTo(commitment.serialize());
      assertThat(profileArgumentCaptor.getValue().avatar()).isNull();
      assertThat(profileArgumentCaptor.getValue().version()).isEqualTo("validversion");
      assertThat(profileArgumentCaptor.getValue().name()).isEqualTo(name);
      assertThat(profileArgumentCaptor.getValue().aboutEmoji()).isNull();
      assertThat(profileArgumentCaptor.getValue().about()).isNull();
    }
  }

  @Test
  void testSetProfileWithSameAvatar() throws InvalidInputException {
    final ProfileKeyCommitment commitment = new ProfileKey(new byte[32]).getCommitment(new ServiceId.Aci(AuthHelper.VALID_UUID_TWO));
    final byte[] name = ProfileHelper.generateRandomByteArray(81);

    try (final Response response = resources.getJerseyTest()
        .target("/v1/profile/")
        .request()
        .header("Authorization", AuthHelper.getAuthHeader(AuthHelper.VALID_UUID_TWO, AuthHelper.VALID_PASSWORD_TWO))
        .put(Entity.entity(new CreateProfileRequest(commitment, "validversion", ProfileHelper.encodeToBase64(name),
            null, null, null, true, true, List.of()), MediaType.APPLICATION_JSON_TYPE))) {

      assertThat(response.getStatus()).isEqualTo(200);
      assertThat(response.hasEntity()).isFalse();

      final ArgumentCaptor<VersionedProfile> profileArgumentCaptor = ArgumentCaptor.forClass(VersionedProfile.class);

      verify(profilesManager, times(1)).get(eq(AuthHelper.VALID_UUID_TWO), eq("validversion"));
      verify(profilesManager, times(1)).set(eq(AuthHelper.VALID_UUID_TWO), profileArgumentCaptor.capture());
      verify(s3client, never()).deleteObject(any(DeleteObjectRequest.class));

      assertThat(profileArgumentCaptor.getValue().commitment()).isEqualTo(commitment.serialize());
      assertThat(profileArgumentCaptor.getValue().avatar()).isEqualTo("profiles/validavatar");
      assertThat(profileArgumentCaptor.getValue().version()).isEqualTo("validversion");
      assertThat(profileArgumentCaptor.getValue().name()).isEqualTo(name);
      assertThat(profileArgumentCaptor.getValue().aboutEmoji()).isNull();
      assertThat(profileArgumentCaptor.getValue().about()).isNull();
    }
  }

  @Test
  void testSetProfileClearPreviousAvatarDespiteSameAvatarFlagSet() throws InvalidInputException {
    final ProfileKeyCommitment commitment = new ProfileKey(new byte[32]).getCommitment(new ServiceId.Aci(AuthHelper.VALID_UUID_TWO));
    final byte[] name = ProfileHelper.generateRandomByteArray(81);

    try (final Response ignored = resources.getJerseyTest()
        .target("/v1/profile/")
        .request()
        .header("Authorization", AuthHelper.getAuthHeader(AuthHelper.VALID_UUID_TWO, AuthHelper.VALID_PASSWORD_TWO))
        .put(Entity.entity(new CreateProfileRequest(commitment, "validversion", ProfileHelper.encodeToBase64(name),
            null, null,
            null, false, true, List.of()), MediaType.APPLICATION_JSON_TYPE))) {

      final ArgumentCaptor<VersionedProfile> profileArgumentCaptor = ArgumentCaptor.forClass(VersionedProfile.class);

      verify(profilesManager, times(1)).get(eq(AuthHelper.VALID_UUID_TWO), eq("validversion"));
      verify(profilesManager, times(1)).set(eq(AuthHelper.VALID_UUID_TWO), profileArgumentCaptor.capture());
      verify(s3client, times(1)).deleteObject(eq(DeleteObjectRequest.builder().bucket("profilesBucket").key("profiles/validavatar").build()));

      assertThat(profileArgumentCaptor.getValue().commitment()).isEqualTo(commitment.serialize());
      assertThat(profileArgumentCaptor.getValue().avatar()).isNull();
      assertThat(profileArgumentCaptor.getValue().version()).isEqualTo("validversion");
      assertThat(profileArgumentCaptor.getValue().name()).isEqualTo(name);
      assertThat(profileArgumentCaptor.getValue().aboutEmoji()).isNull();
      assertThat(profileArgumentCaptor.getValue().about()).isNull();
    }
  }

  @Test
  void testSetProfileWithSameAvatarDespiteNoPreviousAvatar() throws InvalidInputException {
    final ProfileKeyCommitment commitment = new ProfileKey(new byte[32]).getCommitment(new ServiceId.Aci(AuthHelper.VALID_UUID));
    final byte[] name = ProfileHelper.generateRandomByteArray(81);

    try (final Response response = resources.getJerseyTest()
        .target("/v1/profile/")
        .request()
        .header("Authorization", AuthHelper.getAuthHeader(AuthHelper.VALID_UUID, AuthHelper.VALID_PASSWORD))
        .put(Entity.entity(new CreateProfileRequest(commitment, "validversion", ProfileHelper.encodeToBase64(name),
            null, null, null, true, true, List.of()), MediaType.APPLICATION_JSON_TYPE))) {

      assertThat(response.getStatus()).isEqualTo(200);
      assertThat(response.hasEntity()).isFalse();

      final ArgumentCaptor<VersionedProfile> profileArgumentCaptor = ArgumentCaptor.forClass(VersionedProfile.class);

      verify(profilesManager, times(1)).get(eq(AuthHelper.VALID_UUID), eq("validversion"));
      verify(profilesManager, times(1)).set(eq(AuthHelper.VALID_UUID), profileArgumentCaptor.capture());
      verify(s3client, never()).deleteObject(any(DeleteObjectRequest.class));

      assertThat(profileArgumentCaptor.getValue().commitment()).isEqualTo(commitment.serialize());
      assertThat(profileArgumentCaptor.getValue().avatar()).isNull();
      assertThat(profileArgumentCaptor.getValue().version()).isEqualTo("validversion");
      assertThat(profileArgumentCaptor.getValue().name()).isEqualTo(name);
      assertThat(profileArgumentCaptor.getValue().aboutEmoji()).isNull();
      assertThat(profileArgumentCaptor.getValue().about()).isNull();
    }
  }

  @Test
  void testSetProfileExtendedName() throws InvalidInputException {
    final ProfileKeyCommitment commitment = new ProfileKey(new byte[32]).getCommitment(new ServiceId.Aci(AuthHelper.VALID_UUID_TWO));

    final byte[] name = ProfileHelper.generateRandomByteArray(285);

    resources.getJerseyTest()
        .target("/v1/profile/")
        .request()
        .header("Authorization", AuthHelper.getAuthHeader(AuthHelper.VALID_UUID_TWO, AuthHelper.VALID_PASSWORD_TWO))
        .put(Entity.entity(
            new CreateProfileRequest(commitment, "validversion", ProfileHelper.encodeToBase64(name),
                null, null, null, true, false, List.of()),
            MediaType.APPLICATION_JSON_TYPE), ProfileAvatarUploadAttributes.class);

    final ArgumentCaptor<VersionedProfile> profileArgumentCaptor = ArgumentCaptor.forClass(VersionedProfile.class);

    verify(profilesManager, times(1)).get(eq(AuthHelper.VALID_UUID_TWO), eq("validversion"));
    verify(profilesManager, times(1)).set(eq(AuthHelper.VALID_UUID_TWO), profileArgumentCaptor.capture());
    verify(s3client, times(1)).deleteObject(eq(DeleteObjectRequest.builder().bucket("profilesBucket").key("profiles/validavatar").build()));

    assertThat(profileArgumentCaptor.getValue().commitment()).isEqualTo(commitment.serialize());
    assertThat(profileArgumentCaptor.getValue().avatar()).startsWith("profiles/");
    assertThat(profileArgumentCaptor.getValue().version()).isEqualTo("validversion");
    assertThat(profileArgumentCaptor.getValue().name()).isEqualTo(name);
    assertThat(profileArgumentCaptor.getValue().aboutEmoji()).isNull();
    assertThat(profileArgumentCaptor.getValue().about()).isNull();
  }

  @Test
  void testSetProfileEmojiAndBioText() throws InvalidInputException {
    final ProfileKeyCommitment commitment = new ProfileKey(new byte[32]).getCommitment(new ServiceId.Aci(AuthHelper.VALID_UUID));

    clearInvocations(AuthHelper.VALID_ACCOUNT_TWO);

    final byte[] name = ProfileHelper.generateRandomByteArray(81);
    final byte[] emoji = ProfileHelper.generateRandomByteArray(60);
    final byte[] about = ProfileHelper.generateRandomByteArray(156);

    try (final Response response = resources.getJerseyTest()
        .target("/v1/profile/")
        .request()
        .header("Authorization", AuthHelper.getAuthHeader(AuthHelper.VALID_UUID_TWO, AuthHelper.VALID_PASSWORD_TWO))
        .put(Entity.entity(
            new CreateProfileRequest(commitment, "anotherversion", ProfileHelper.encodeToBase64(name),
                ProfileHelper.encodeToBase64(emoji), ProfileHelper.encodeToBase64(about), null, false, false, List.of()),
            MediaType.APPLICATION_JSON_TYPE))) {

      assertThat(response.getStatus()).isEqualTo(200);
      assertThat(response.hasEntity()).isFalse();

      final ArgumentCaptor<VersionedProfile> profileArgumentCaptor = ArgumentCaptor.forClass(VersionedProfile.class);

      verify(profilesManager, times(1)).get(eq(AuthHelper.VALID_UUID_TWO), eq("anotherversion"));
      verify(profilesManager, times(1)).set(eq(AuthHelper.VALID_UUID_TWO), profileArgumentCaptor.capture());

      verifyNoMoreInteractions(s3client);

      final VersionedProfile profile = profileArgumentCaptor.getValue();
      assertThat(profile.commitment()).isEqualTo(commitment.serialize());
      assertThat(profile.avatar()).isNull();
      assertThat(profile.version()).isEqualTo("anotherversion");
      assertThat(profile.name()).isEqualTo(name);
      assertThat(profile.aboutEmoji()).isEqualTo(emoji);
      assertThat(profile.about()).isEqualTo(about);
      assertThat(profile.paymentAddress()).isNull();
    }
  }

  @Test
  void testSetProfilePaymentAddress() throws InvalidInputException {
    final ProfileKeyCommitment commitment = new ProfileKey(new byte[32]).getCommitment(new ServiceId.Aci(AuthHelper.VALID_UUID));

    clearInvocations(AuthHelper.VALID_ACCOUNT_TWO);

    final byte[] name = ProfileHelper.generateRandomByteArray(81);
    final byte[] paymentAddress = ProfileHelper.generateRandomByteArray(582);

    try (final Response response = resources.getJerseyTest()
        .target("/v1/profile")
        .request()
        .header("Authorization", AuthHelper.getAuthHeader(AuthHelper.VALID_UUID_TWO, AuthHelper.VALID_PASSWORD_TWO))
        .put(Entity.entity(
            new CreateProfileRequest(commitment, "yetanotherversion", ProfileHelper.encodeToBase64(name),
                null, null, ProfileHelper.encodeToBase64(paymentAddress), false, false,
                List.of()), MediaType.APPLICATION_JSON_TYPE))) {

      assertThat(response.getStatus()).isEqualTo(200);
      assertThat(response.hasEntity()).isFalse();

      final ArgumentCaptor<VersionedProfile> profileArgumentCaptor = ArgumentCaptor.forClass(VersionedProfile.class);

      verify(profilesManager).get(eq(AuthHelper.VALID_UUID_TWO), eq("yetanotherversion"));
      verify(profilesManager).set(eq(AuthHelper.VALID_UUID_TWO), profileArgumentCaptor.capture());

      verifyNoMoreInteractions(s3client);

      final VersionedProfile profile = profileArgumentCaptor.getValue();
      assertThat(profile.commitment()).isEqualTo(commitment.serialize());
      assertThat(profile.avatar()).isNull();
      assertThat(profile.version()).isEqualTo("yetanotherversion");
      assertThat(profile.name()).isEqualTo(name);
      assertThat(profile.aboutEmoji()).isNull();
      assertThat(profile.about()).isNull();
      assertThat(profile.paymentAddress()).isEqualTo(paymentAddress);
    }
  }

  @Test
  void testSetProfilePaymentAddressCountryNotAllowed() throws InvalidInputException {
    when(dynamicPaymentsConfiguration.getDisallowedPrefixes())
        .thenReturn(List.of(AuthHelper.VALID_NUMBER_TWO.substring(0, 3)));

    final ProfileKeyCommitment commitment = new ProfileKey(new byte[32]).getCommitment(new ServiceId.Aci(AuthHelper.VALID_UUID));

    clearInvocations(AuthHelper.VALID_ACCOUNT_TWO);

    final String name = ProfileHelper.generateRandomBase64FromByteArray(81);
    final String paymentAddress = ProfileHelper.generateRandomBase64FromByteArray(582);

    try (final Response response = resources.getJerseyTest()
        .target("/v1/profile")
        .request()
        .header("Authorization", AuthHelper.getAuthHeader(AuthHelper.VALID_UUID_TWO, AuthHelper.VALID_PASSWORD_TWO))
        .put(Entity.entity(
            new CreateProfileRequest(commitment, "yetanotherversion", name,
                null, null, paymentAddress, false, false,
                List.of()), MediaType.APPLICATION_JSON_TYPE))) {

      assertThat(response.getStatus()).isEqualTo(403);
      assertThat(response.hasEntity()).isFalse();

      verify(profilesManager, never()).set(any(), any());
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void testSetProfilePaymentAddressCountryNotAllowedExistingPaymentAddress(
      final boolean existingPaymentAddressOnProfile) throws InvalidInputException {
    when(dynamicPaymentsConfiguration.getDisallowedPrefixes())
        .thenReturn(List.of(AuthHelper.VALID_NUMBER_TWO.substring(0, 3)));

    final ProfileKeyCommitment commitment = new ProfileKey(new byte[32]).getCommitment(new ServiceId.Aci(AuthHelper.VALID_UUID));
    final byte[] name = ProfileHelper.generateRandomByteArray(81);
    final byte[] paymentAddress = ProfileHelper.generateRandomByteArray(582);

    clearInvocations(AuthHelper.VALID_ACCOUNT_TWO);

    when(profilesManager.get(eq(AuthHelper.VALID_UUID_TWO), any()))
        .thenReturn(Optional.of(
            new VersionedProfile("1", name, null, null, null,
                existingPaymentAddressOnProfile ? ProfileHelper.generateRandomByteArray(582) : null,
                commitment.serialize())));


    try (final Response response = resources.getJerseyTest()
        .target("/v1/profile")
        .request()
        .header("Authorization", AuthHelper.getAuthHeader(AuthHelper.VALID_UUID_TWO, AuthHelper.VALID_PASSWORD_TWO))
        .put(Entity.entity(
            new CreateProfileRequest(commitment, "yetanotherversion", ProfileHelper.encodeToBase64(name),
                null, null, ProfileHelper.encodeToBase64(paymentAddress), false, false,
                List.of()), MediaType.APPLICATION_JSON_TYPE))) {

      if (existingPaymentAddressOnProfile) {
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.hasEntity()).isFalse();

        final ArgumentCaptor<VersionedProfile> profileArgumentCaptor = ArgumentCaptor.forClass(VersionedProfile.class);

        verify(profilesManager).get(eq(AuthHelper.VALID_UUID_TWO), eq("yetanotherversion"));
        verify(profilesManager).set(eq(AuthHelper.VALID_UUID_TWO), profileArgumentCaptor.capture());

        verifyNoMoreInteractions(s3client);

        final VersionedProfile profile = profileArgumentCaptor.getValue();
        assertThat(profile.commitment()).isEqualTo(commitment.serialize());
        assertThat(profile.avatar()).isNull();
        assertThat(profile.version()).isEqualTo("yetanotherversion");
        assertThat(profile.name()).isEqualTo(name);
        assertThat(profile.aboutEmoji()).isNull();
        assertThat(profile.about()).isNull();
        assertThat(profile.paymentAddress()).isEqualTo(paymentAddress);
      } else {
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.hasEntity()).isFalse();

        verify(profilesManager, never()).set(any(), any());
      }
    }
  }

  @Test
  void testGetProfileByVersion() throws RateLimitExceededException {
    final byte[] name = ProfileHelper.generateRandomByteArray(81);
    final byte[] emoji = ProfileHelper.generateRandomByteArray(60);
    final byte[] about = ProfileHelper.generateRandomByteArray(156);

    when(profilesManager.get(eq(AuthHelper.VALID_UUID_TWO), eq("validversion"))).thenReturn(Optional.of(new VersionedProfile(
        "validversion", name, "profiles/validavatar", emoji, about, null, "validcommitmnet".getBytes())));

    final VersionedProfileResponse profile = resources.getJerseyTest()
        .target("/v1/profile/" + AuthHelper.VALID_UUID_TWO + "/validversion")
        .request()
        .header("Authorization", AuthHelper.getAuthHeader(AuthHelper.VALID_UUID, AuthHelper.VALID_PASSWORD))
        .get(VersionedProfileResponse.class);

    assertThat(profile.getBaseProfileResponse().getIdentityKey()).isEqualTo(ACCOUNT_TWO_IDENTITY_KEY);
    assertThat(profile.getName()).isEqualTo(ProfileHelper.encodeToBase64(name));
    assertThat(profile.getAbout()).isEqualTo(ProfileHelper.encodeToBase64(about));
    assertThat(profile.getAboutEmoji()).isEqualTo(ProfileHelper.encodeToBase64(emoji));
    assertThat(profile.getAvatar()).isEqualTo("profiles/validavatar");
    assertThat(profile.getBaseProfileResponse().getCapabilities().gv1Migration()).isTrue();
    assertThat(profile.getBaseProfileResponse().getUuid()).isEqualTo(new AciServiceIdentifier(AuthHelper.VALID_UUID_TWO));
    assertThat(profile.getBaseProfileResponse().getBadges()).hasSize(1).element(0).has(new Condition<>(
        badge -> "Test Badge".equals(badge.getName()), "has badge with expected name"));

    verify(accountsManager, times(1)).getByAccountIdentifier(eq(AuthHelper.VALID_UUID_TWO));
    verify(profilesManager, times(1)).get(eq(AuthHelper.VALID_UUID_TWO), eq("validversion"));

    verify(rateLimiter, times(1)).validate(AuthHelper.VALID_UUID);
  }

  @Test
  void testSetProfileUpdatesAccountCurrentVersion() throws InvalidInputException {
    final ProfileKeyCommitment commitment = new ProfileKey(new byte[32]).getCommitment(new ServiceId.Aci(AuthHelper.VALID_UUID_TWO));

    clearInvocations(AuthHelper.VALID_ACCOUNT_TWO);

    final String name = ProfileHelper.generateRandomBase64FromByteArray(81);
    final String paymentAddress = ProfileHelper.generateRandomBase64FromByteArray(582);

    try (final Response response = resources.getJerseyTest()
        .target("/v1/profile")
        .request()
        .header("Authorization", AuthHelper.getAuthHeader(AuthHelper.VALID_UUID_TWO, AuthHelper.VALID_PASSWORD_TWO))
        .put(Entity.entity(
            new CreateProfileRequest(commitment, "someversion", name, null, null, paymentAddress, false, false,
                List.of()), MediaType.APPLICATION_JSON_TYPE))) {

      assertThat(response.getStatus()).isEqualTo(200);
      assertThat(response.hasEntity()).isFalse();

      verify(AuthHelper.VALID_ACCOUNT_TWO).setCurrentProfileVersion("someversion");
    }
  }

  @Test
  void testGetProfileReturnsNoPaymentAddressIfCurrentVersionMismatch() {
    final byte[] paymentAddress = ProfileHelper.generateRandomByteArray(582);
    when(profilesManager.get(AuthHelper.VALID_UUID_TWO, "validversion")).thenReturn(
        Optional.of(new VersionedProfile(null, null, null, null, null, paymentAddress, null)));

    {
      final VersionedProfileResponse profile = resources.getJerseyTest()
          .target("/v1/profile/" + AuthHelper.VALID_UUID_TWO + "/validversion")
          .request()
          .header("Authorization", AuthHelper.getAuthHeader(AuthHelper.VALID_UUID, AuthHelper.VALID_PASSWORD))
          .get(VersionedProfileResponse.class);

      assertThat(profile.getPaymentAddress()).isEqualTo(ProfileHelper.encodeToBase64(paymentAddress));
    }

    when(profileAccount.getCurrentProfileVersion()).thenReturn(Optional.of("validversion"));

    {
      final VersionedProfileResponse profile = resources.getJerseyTest()
          .target("/v1/profile/" + AuthHelper.VALID_UUID_TWO + "/validversion")
          .request()
          .header("Authorization", AuthHelper.getAuthHeader(AuthHelper.VALID_UUID, AuthHelper.VALID_PASSWORD))
          .get(VersionedProfileResponse.class);

      assertThat(profile.getPaymentAddress()).isEqualTo(ProfileHelper.encodeToBase64(paymentAddress));
    }

    when(profileAccount.getCurrentProfileVersion()).thenReturn(Optional.of("someotherversion"));

    {
      final VersionedProfileResponse profile = resources.getJerseyTest()
          .target("/v1/profile/" + AuthHelper.VALID_UUID_TWO + "/validversion")
          .request()
          .header("Authorization", AuthHelper.getAuthHeader(AuthHelper.VALID_UUID, AuthHelper.VALID_PASSWORD))
          .get(VersionedProfileResponse.class);

      assertThat(profile.getPaymentAddress()).isNull();
    }
  }

  @Test
  void testGetProfileWithExpiringProfileKeyCredentialVersionNotFound() throws VerificationFailedException {
    final Account account = mock(Account.class);
    when(account.getUuid()).thenReturn(AuthHelper.VALID_UUID);
    when(account.getCurrentProfileVersion()).thenReturn(Optional.of("version"));
    when(account.isEnabled()).thenReturn(true);

    when(accountsManager.getByAccountIdentifier(AuthHelper.VALID_UUID)).thenReturn(Optional.of(account));
    when(profilesManager.get(any(), any())).thenReturn(Optional.empty());

    final ExpiringProfileKeyCredentialProfileResponse profile = resources.getJerseyTest()
        .target(String.format("/v1/profile/%s/%s/%s", AuthHelper.VALID_UUID, "version-that-does-not-exist", "credential-request"))
        .queryParam("credentialType", "expiringProfileKey")
        .request()
        .header("Authorization", AuthHelper.getAuthHeader(AuthHelper.VALID_UUID, AuthHelper.VALID_PASSWORD))
        .get(ExpiringProfileKeyCredentialProfileResponse.class);

    assertThat(profile.getVersionedProfileResponse().getBaseProfileResponse().getUuid())
        .isEqualTo(new AciServiceIdentifier(AuthHelper.VALID_UUID));

    assertThat(profile.getCredential()).isNull();

    verify(zkProfileOperations, never()).issueExpiringProfileKeyCredential(any(), any(), any(), any());
  }

  @Test
  void testSetProfileBadges() throws InvalidInputException {
    final ProfileKeyCommitment commitment = new ProfileKey(new byte[32]).getCommitment(new ServiceId.Aci(AuthHelper.VALID_UUID));

    clearInvocations(AuthHelper.VALID_ACCOUNT_TWO);

    final String name = ProfileHelper.generateRandomBase64FromByteArray(81);
    final String emoji = ProfileHelper.generateRandomBase64FromByteArray(60);
    final String about = ProfileHelper.generateRandomBase64FromByteArray(156);

    try (final Response response = resources.getJerseyTest()
        .target("/v1/profile/")
        .request()
        .header("Authorization", AuthHelper.getAuthHeader(AuthHelper.VALID_UUID_TWO, AuthHelper.VALID_PASSWORD_TWO))
        .put(Entity.entity(new CreateProfileRequest(commitment, "anotherversion", name, emoji, about, null, false, false,
            List.of("TEST2")), MediaType.APPLICATION_JSON_TYPE))) {

      assertThat(response.getStatus()).isEqualTo(200);
      assertThat(response.hasEntity()).isFalse();

      @SuppressWarnings("unchecked")
      final ArgumentCaptor<List<AccountBadge>> badgeCaptor = ArgumentCaptor.forClass(List.class);
      verify(AuthHelper.VALID_ACCOUNT_TWO).setBadges(refEq(clock), badgeCaptor.capture());

      final List<AccountBadge> badges = badgeCaptor.getValue();
      assertThat(badges).isNotNull().hasSize(1).containsOnly(new AccountBadge("TEST2", Instant.ofEpochSecond(42 + 86400), true));

      clearInvocations(AuthHelper.VALID_ACCOUNT_TWO);
      when(AuthHelper.VALID_ACCOUNT_TWO.getBadges()).thenReturn(List.of(
          new AccountBadge("TEST2", Instant.ofEpochSecond(42 + 86400), true)
      ));
    }

    try (final Response response = resources.getJerseyTest()
        .target("/v1/profile/")
        .request()
        .header("Authorization", AuthHelper.getAuthHeader(AuthHelper.VALID_UUID_TWO, AuthHelper.VALID_PASSWORD_TWO))
        .put(Entity.entity(new CreateProfileRequest(commitment, "anotherversion", name, emoji, about, null, false, false,
            List.of("TEST3", "TEST2")), MediaType.APPLICATION_JSON_TYPE))) {

      assertThat(response.getStatus()).isEqualTo(200);
      assertThat(response.hasEntity()).isFalse();

      //noinspection unchecked
      final ArgumentCaptor<List<AccountBadge>> badgeCaptor = ArgumentCaptor.forClass(List.class);
      verify(AuthHelper.VALID_ACCOUNT_TWO).setBadges(refEq(clock), badgeCaptor.capture());

      final List<AccountBadge> badges = badgeCaptor.getValue();
      assertThat(badges).isNotNull().hasSize(2).containsOnly(
          new AccountBadge("TEST3", Instant.ofEpochSecond(42 + 86400), true),
          new AccountBadge("TEST2", Instant.ofEpochSecond(42 + 86400), true));

      clearInvocations(AuthHelper.VALID_ACCOUNT_TWO);
      when(AuthHelper.VALID_ACCOUNT_TWO.getBadges()).thenReturn(List.of(
          new AccountBadge("TEST3", Instant.ofEpochSecond(42 + 86400), true),
          new AccountBadge("TEST2", Instant.ofEpochSecond(42 + 86400), true)
      ));
    }

    try (final Response response = resources.getJerseyTest()
        .target("/v1/profile/")
        .request()
        .header("Authorization", AuthHelper.getAuthHeader(AuthHelper.VALID_UUID_TWO, AuthHelper.VALID_PASSWORD_TWO))
        .put(Entity.entity(new CreateProfileRequest(commitment, "anotherversion", name, emoji, about, null, false, false,
            List.of("TEST2", "TEST3")), MediaType.APPLICATION_JSON_TYPE))) {

      assertThat(response.getStatus()).isEqualTo(200);
      assertThat(response.hasEntity()).isFalse();

      //noinspection unchecked
      final ArgumentCaptor<List<AccountBadge>> badgeCaptor = ArgumentCaptor.forClass(List.class);
      verify(AuthHelper.VALID_ACCOUNT_TWO).setBadges(refEq(clock), badgeCaptor.capture());

      final List<AccountBadge> badges = badgeCaptor.getValue();
      assertThat(badges).isNotNull().hasSize(2).containsOnly(
          new AccountBadge("TEST2", Instant.ofEpochSecond(42 + 86400), true),
          new AccountBadge("TEST3", Instant.ofEpochSecond(42 + 86400), true));

      clearInvocations(AuthHelper.VALID_ACCOUNT_TWO);
      when(AuthHelper.VALID_ACCOUNT_TWO.getBadges()).thenReturn(List.of(
          new AccountBadge("TEST2", Instant.ofEpochSecond(42 + 86400), true),
          new AccountBadge("TEST3", Instant.ofEpochSecond(42 + 86400), true)
      ));
    }

    try (final Response response = resources.getJerseyTest()
        .target("/v1/profile/")
        .request()
        .header("Authorization", AuthHelper.getAuthHeader(AuthHelper.VALID_UUID_TWO, AuthHelper.VALID_PASSWORD_TWO))
        .put(Entity.entity(new CreateProfileRequest(commitment, "anotherversion", name, emoji, about, null, false, false,
            List.of("TEST1")), MediaType.APPLICATION_JSON_TYPE))) {

      assertThat(response.getStatus()).isEqualTo(200);
      assertThat(response.hasEntity()).isFalse();

      //noinspection unchecked
      final ArgumentCaptor<List<AccountBadge>> badgeCaptor = ArgumentCaptor.forClass(List.class);
      verify(AuthHelper.VALID_ACCOUNT_TWO).setBadges(refEq(clock), badgeCaptor.capture());

      final List<AccountBadge> badges = badgeCaptor.getValue();
      assertThat(badges).isNotNull().hasSize(3).containsOnly(
          new AccountBadge("TEST1", Instant.ofEpochSecond(42 + 86400), true),
          new AccountBadge("TEST2", Instant.ofEpochSecond(42 + 86400), false),
          new AccountBadge("TEST3", Instant.ofEpochSecond(42 + 86400), false));
    }
  }

  @ParameterizedTest
  @MethodSource
  void testGetProfileWithExpiringProfileKeyCredential(final MultivaluedMap<String, Object> authHeaders)
      throws VerificationFailedException, InvalidInputException {
    final String version = "version";
    final byte[] unidentifiedAccessKey = "test-uak".getBytes(StandardCharsets.UTF_8);

    final ServerSecretParams serverSecretParams = ServerSecretParams.generate();
    final ServerPublicParams serverPublicParams = serverSecretParams.getPublicParams();

    final ServerZkProfileOperations serverZkProfile = new ServerZkProfileOperations(serverSecretParams);
    final ClientZkProfileOperations clientZkProfile = new ClientZkProfileOperations(serverPublicParams);

    final byte[] profileKeyBytes = new byte[32];
    new SecureRandom().nextBytes(profileKeyBytes);

    final ProfileKey profileKey = new ProfileKey(profileKeyBytes);
    final ProfileKeyCommitment profileKeyCommitment = profileKey.getCommitment(new ServiceId.Aci(AuthHelper.VALID_UUID));

    final VersionedProfile versionedProfile = mock(VersionedProfile.class);
    when(versionedProfile.commitment()).thenReturn(profileKeyCommitment.serialize());

    final ProfileKeyCredentialRequestContext profileKeyCredentialRequestContext =
        clientZkProfile.createProfileKeyCredentialRequestContext(new ServiceId.Aci(AuthHelper.VALID_UUID), profileKey);

    final ProfileKeyCredentialRequest credentialRequest = profileKeyCredentialRequestContext.getRequest();

    final Account account = mock(Account.class);
    when(account.getUuid()).thenReturn(AuthHelper.VALID_UUID);
    when(account.getCurrentProfileVersion()).thenReturn(Optional.of(version));
    when(account.isEnabled()).thenReturn(true);
    when(account.getUnidentifiedAccessKey()).thenReturn(Optional.of(unidentifiedAccessKey));

    final Instant expiration = Instant.now().plus(ProfileController.EXPIRING_PROFILE_KEY_CREDENTIAL_EXPIRATION)
        .truncatedTo(ChronoUnit.DAYS);

    final ExpiringProfileKeyCredentialResponse credentialResponse =
        serverZkProfile.issueExpiringProfileKeyCredential(credentialRequest, new ServiceId.Aci(AuthHelper.VALID_UUID), profileKeyCommitment, expiration);

    when(accountsManager.getByAccountIdentifier(AuthHelper.VALID_UUID)).thenReturn(Optional.of(account));
    when(profilesManager.get(AuthHelper.VALID_UUID, version)).thenReturn(Optional.of(versionedProfile));
    when(zkProfileOperations.issueExpiringProfileKeyCredential(credentialRequest, new ServiceId.Aci(AuthHelper.VALID_UUID), profileKeyCommitment, expiration))
        .thenReturn(credentialResponse);

    final ExpiringProfileKeyCredentialProfileResponse profile = resources.getJerseyTest()
        .target(String.format("/v1/profile/%s/%s/%s", AuthHelper.VALID_UUID, version,
            HexFormat.of().formatHex(credentialRequest.serialize())))
        .queryParam("credentialType", "expiringProfileKey")
        .request()
        .headers(authHeaders)
        .get(ExpiringProfileKeyCredentialProfileResponse.class);

    assertThat(profile.getVersionedProfileResponse().getBaseProfileResponse().getUuid())
        .isEqualTo(new AciServiceIdentifier(AuthHelper.VALID_UUID));
    assertThat(profile.getCredential()).isEqualTo(credentialResponse);

    verify(zkProfileOperations).issueExpiringProfileKeyCredential(credentialRequest, new ServiceId.Aci(AuthHelper.VALID_UUID), profileKeyCommitment, expiration);

    final ClientZkProfileOperations clientZkProfileCipher = new ClientZkProfileOperations(serverPublicParams);
    assertThatNoException().isThrownBy(() ->
        clientZkProfileCipher.receiveExpiringProfileKeyCredential(profileKeyCredentialRequestContext, profile.getCredential()));
  }

  private static Stream<Arguments> testGetProfileWithExpiringProfileKeyCredential() {
    return Stream.of(
        Arguments.of(new MultivaluedHashMap<>(Map.of(OptionalAccess.UNIDENTIFIED, Base64.getEncoder().encodeToString(UNIDENTIFIED_ACCESS_KEY)))),
        Arguments.of(new MultivaluedHashMap<>(Map.of("Authorization", AuthHelper.getAuthHeader(AuthHelper.VALID_UUID, AuthHelper.VALID_PASSWORD)))),
        Arguments.of(new MultivaluedHashMap<>(Map.of("Authorization", AuthHelper.getAuthHeader(AuthHelper.VALID_UUID_TWO, AuthHelper.VALID_PASSWORD_TWO))))
    );
  }

  @Test
  void testSetProfileBadgesMissingFromRequest() throws InvalidInputException {
    final ProfileKeyCommitment commitment = new ProfileKey(new byte[32]).getCommitment(new ServiceId.Aci(AuthHelper.VALID_UUID));

    clearInvocations(AuthHelper.VALID_ACCOUNT_TWO);

    final String name = ProfileHelper.generateRandomBase64FromByteArray(81);
    final String emoji = ProfileHelper.generateRandomBase64FromByteArray(60);
    final String text = ProfileHelper.generateRandomBase64FromByteArray(156);

    when(AuthHelper.VALID_ACCOUNT_TWO.getBadges()).thenReturn(List.of(
        new AccountBadge("TEST", Instant.ofEpochSecond(42 + 86400), true)
    ));

    // Older clients may not include badges in their requests
    final String requestJson = String.format("""
        {
          "commitment": "%s",
          "version": "version",
          "name": "%s",
          "avatar": false,
          "aboutEmoji": "%s",
          "about": "%s"
        }
        """,
        Base64.getEncoder().encodeToString(commitment.serialize()), name, emoji, text);

    try (final Response response = resources.getJerseyTest()
        .target("/v1/profile/")
        .request()
        .header("Authorization", AuthHelper.getAuthHeader(AuthHelper.VALID_UUID_TWO, AuthHelper.VALID_PASSWORD_TWO))
        .put(Entity.json(requestJson))) {

      assertThat(response.getStatus()).isEqualTo(200);
      assertThat(response.hasEntity()).isFalse();

      verify(AuthHelper.VALID_ACCOUNT_TWO).setBadges(refEq(clock), eq(List.of(new AccountBadge("TEST", Instant.ofEpochSecond(42 + 86400), true))));
    }
  }

  @Test
  void testBatchIdentityCheck() {
    try (final Response response = resources.getJerseyTest().target("/v1/profile/identity_check/batch").request()
        .post(Entity.json(new BatchIdentityCheckRequest(List.of(
            new BatchIdentityCheckRequest.Element(new AciServiceIdentifier(AuthHelper.VALID_UUID), null,
                convertKeyToFingerprint(ACCOUNT_IDENTITY_KEY)),
            new BatchIdentityCheckRequest.Element(new PniServiceIdentifier(AuthHelper.VALID_PNI_TWO), null,
                convertKeyToFingerprint(ACCOUNT_TWO_PHONE_NUMBER_IDENTITY_KEY)),
            new BatchIdentityCheckRequest.Element(null, new AciServiceIdentifier(AuthHelper.VALID_UUID_TWO),
                convertKeyToFingerprint(ACCOUNT_TWO_IDENTITY_KEY)),
            new BatchIdentityCheckRequest.Element(new AciServiceIdentifier(AuthHelper.INVALID_UUID), null,
                convertKeyToFingerprint(ACCOUNT_TWO_PHONE_NUMBER_IDENTITY_KEY))
        ))))) {
      assertThat(response).isNotNull();
      assertThat(response.getStatus()).isEqualTo(200);
      BatchIdentityCheckResponse identityCheckResponse = response.readEntity(BatchIdentityCheckResponse.class);
      assertThat(identityCheckResponse).isNotNull();
      assertThat(identityCheckResponse.elements()).isNotNull().isEmpty();
    }

    final Map<ServiceIdentifier, IdentityKey> expectedIdentityKeys = Map.of(
        new AciServiceIdentifier(AuthHelper.VALID_UUID), ACCOUNT_IDENTITY_KEY,
        new PniServiceIdentifier(AuthHelper.VALID_PNI_TWO), ACCOUNT_TWO_PHONE_NUMBER_IDENTITY_KEY,
        new AciServiceIdentifier(AuthHelper.VALID_UUID_TWO), ACCOUNT_TWO_IDENTITY_KEY);

    final Condition<BatchIdentityCheckResponse.Element> isAnExpectedUuid =
        new Condition<>(element -> element.identityKey()
            .equals(expectedIdentityKeys.get(Objects.requireNonNullElse(element.uuid(), element.aci()))),
            "is an expected UUID with the correct identity key");

    final IdentityKey validAciIdentityKey = new IdentityKey(Curve.generateKeyPair().getPublicKey());
    final IdentityKey secondValidPniIdentityKey = new IdentityKey(Curve.generateKeyPair().getPublicKey());
    final IdentityKey secondValidAciIdentityKey = new IdentityKey(Curve.generateKeyPair().getPublicKey());
    final IdentityKey invalidAciIdentityKey = new IdentityKey(Curve.generateKeyPair().getPublicKey());

    try (final Response response = resources.getJerseyTest().target("/v1/profile/identity_check/batch").request()
        .post(Entity.json(new BatchIdentityCheckRequest(List.of(
            new BatchIdentityCheckRequest.Element(new AciServiceIdentifier(AuthHelper.VALID_UUID), null,
                convertKeyToFingerprint(validAciIdentityKey)),
            new BatchIdentityCheckRequest.Element(new PniServiceIdentifier(AuthHelper.VALID_PNI_TWO), null,
                convertKeyToFingerprint(secondValidPniIdentityKey)),
            new BatchIdentityCheckRequest.Element(null, new AciServiceIdentifier(AuthHelper.VALID_UUID_TWO),
                convertKeyToFingerprint(secondValidAciIdentityKey)),
            new BatchIdentityCheckRequest.Element(new AciServiceIdentifier(AuthHelper.INVALID_UUID), null,
                convertKeyToFingerprint(invalidAciIdentityKey))
        ))))) {
      assertThat(response).isNotNull();
      assertThat(response.getStatus()).isEqualTo(200);
      BatchIdentityCheckResponse identityCheckResponse = response.readEntity(BatchIdentityCheckResponse.class);
      assertThat(identityCheckResponse).isNotNull();
      assertThat(identityCheckResponse.elements()).isNotNull().hasSize(3);
      assertThat(identityCheckResponse.elements()).element(0).isNotNull().is(isAnExpectedUuid);
      assertThat(identityCheckResponse.elements()).element(1).isNotNull().is(isAnExpectedUuid);
      assertThat(identityCheckResponse.elements()).element(2).isNotNull().is(isAnExpectedUuid);
    }

    final List<BatchIdentityCheckRequest.Element> largeElementList = new ArrayList<>(List.of(
        new BatchIdentityCheckRequest.Element(new AciServiceIdentifier(AuthHelper.VALID_UUID), null,
            convertKeyToFingerprint(validAciIdentityKey)),
        new BatchIdentityCheckRequest.Element(new PniServiceIdentifier(AuthHelper.VALID_PNI_TWO), null,
            convertKeyToFingerprint(secondValidPniIdentityKey)),
        new BatchIdentityCheckRequest.Element(new AciServiceIdentifier(AuthHelper.INVALID_UUID), null,
            convertKeyToFingerprint(invalidAciIdentityKey))));

    for (int i = 0; i < 900; i++) {
      largeElementList.add(
          new BatchIdentityCheckRequest.Element(new AciServiceIdentifier(UUID.randomUUID()), null,
              convertKeyToFingerprint(new IdentityKey(Curve.generateKeyPair().getPublicKey()))));
    }

    try (final Response response = resources.getJerseyTest().target("/v1/profile/identity_check/batch").request()
        .post(Entity.json(new BatchIdentityCheckRequest(largeElementList)))) {
      assertThat(response).isNotNull();
      assertThat(response.getStatus()).isEqualTo(200);
      BatchIdentityCheckResponse identityCheckResponse = response.readEntity(BatchIdentityCheckResponse.class);
      assertThat(identityCheckResponse).isNotNull();
      assertThat(identityCheckResponse.elements()).isNotNull().hasSize(2);
      assertThat(identityCheckResponse.elements()).element(0).isNotNull().is(isAnExpectedUuid);
      assertThat(identityCheckResponse.elements()).element(1).isNotNull().is(isAnExpectedUuid);
    }
  }

  @Test
  void testBatchIdentityCheckDeserialization() throws Exception {

    final Map<ServiceIdentifier, IdentityKey> expectedIdentityKeys = Map.of(
        new AciServiceIdentifier(AuthHelper.VALID_UUID), ACCOUNT_IDENTITY_KEY,
        new PniServiceIdentifier(AuthHelper.VALID_PNI_TWO), ACCOUNT_TWO_PHONE_NUMBER_IDENTITY_KEY);

    final Condition<BatchIdentityCheckResponse.Element> isAnExpectedUuid =
        new Condition<>(element -> element.identityKey().equals(expectedIdentityKeys.get(element.uuid())),
            "is an expected UUID with the correct identity key");

    // null properties are ok to omit
    final String json = String.format("""
            {
              "elements": [
                { "uuid": "%s", "fingerprint": "%s" },
                { "uuid": "%s", "fingerprint": "%s" },
                { "uuid": "%s", "fingerprint": "%s" }
              ]
            }
            """, AuthHelper.VALID_UUID, Base64.getEncoder().encodeToString(convertKeyToFingerprint(new IdentityKey(Curve.generateKeyPair().getPublicKey()))),
        "PNI:" + AuthHelper.VALID_PNI_TWO, Base64.getEncoder().encodeToString(convertKeyToFingerprint(new IdentityKey(Curve.generateKeyPair().getPublicKey()))),
        AuthHelper.INVALID_UUID, Base64.getEncoder().encodeToString(convertKeyToFingerprint(new IdentityKey(Curve.generateKeyPair().getPublicKey()))));

    try (final Response response = resources.getJerseyTest().target("/v1/profile/identity_check/batch").request()
        .post(Entity.entity(json, "application/json"))) {
      assertThat(response).isNotNull();
      assertThat(response.getStatus()).isEqualTo(200);
      String responseJson = response.readEntity(String.class);

      // `null` properties should be omitted from the response
      assertThat(responseJson).doesNotContain("null");

      final BatchIdentityCheckResponse identityCheckResponse =
          SystemMapper.jsonMapper().readValue(responseJson, BatchIdentityCheckResponse.class);

      assertThat(identityCheckResponse).isNotNull();
      assertThat(identityCheckResponse.elements()).isNotNull().hasSize(2);
      assertThat(identityCheckResponse.elements()).element(0).isNotNull().is(isAnExpectedUuid);
      assertThat(identityCheckResponse.elements()).element(1).isNotNull().is(isAnExpectedUuid);
    }
  }

  @ParameterizedTest
  @MethodSource
  void testBatchIdentityCheckDeserializationBadRequest(final String json, final int expectedStatus) {
    try (final Response response = resources.getJerseyTest().target("/v1/profile/identity_check/batch").request()
        .post(Entity.entity(json, "application/json"))) {
      assertThat(response).isNotNull();
      assertThat(response.getStatus()).isEqualTo(expectedStatus);
    }
  }

  static Stream<Arguments> testBatchIdentityCheckDeserializationBadRequest() {
    return Stream.of(
        Arguments.of( // aci and uuid cannot both be null
            String.format("""
                {
                  "elements": [
                    { "uuid": null, "fingerprint": "%s" }
                  ]
                }
                """, Base64.getEncoder().encodeToString(convertKeyToFingerprint(new IdentityKey(Curve.generateKeyPair().getPublicKey())))),
            400),
        Arguments.of( // a blank string is invalid
            String.format("""
                {
                  "elements": [
                    { "uuid": " ", "fingerprint": "%s" }
                  ]
                }
                """, Base64.getEncoder().encodeToString(convertKeyToFingerprint(new IdentityKey(Curve.generateKeyPair().getPublicKey())))),
            400)
    );
  }

  private static byte[] convertKeyToFingerprint(final IdentityKey publicKey) {
    try {
      return Util.truncate(MessageDigest.getInstance("SHA-256").digest(publicKey.serialize()), 4);
    } catch (final NoSuchAlgorithmException e) {
      throw new AssertionError("All Java implementations must support SHA-256 MessageDigest algorithm", e);
    }
  }
}
