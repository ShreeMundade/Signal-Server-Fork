/*
 * Copyright 2013 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package org.whispersystems.textsecuregcm.controllers;

import static com.codahale.metrics.MetricRegistry.name;

import com.google.common.net.HttpHeaders;
import io.dropwizard.auth.Auth;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Tags;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import javax.validation.Valid;
import javax.validation.constraints.NotNull;
import javax.ws.rs.Consumes;
import javax.ws.rs.ForbiddenException;
import javax.ws.rs.GET;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import org.signal.libsignal.protocol.IdentityKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.whispersystems.textsecuregcm.auth.Anonymous;
import org.whispersystems.textsecuregcm.auth.AuthenticatedAccount;
import org.whispersystems.textsecuregcm.auth.ChangesDeviceEnabledState;
import org.whispersystems.textsecuregcm.auth.DisabledPermittedAuthenticatedAccount;
import org.whispersystems.textsecuregcm.auth.OptionalAccess;
import org.whispersystems.textsecuregcm.entities.ECPreKey;
import org.whispersystems.textsecuregcm.entities.ECSignedPreKey;
import org.whispersystems.textsecuregcm.entities.KEMSignedPreKey;
import org.whispersystems.textsecuregcm.entities.PreKeyCount;
import org.whispersystems.textsecuregcm.entities.PreKeyResponse;
import org.whispersystems.textsecuregcm.entities.PreKeyResponseItem;
import org.whispersystems.textsecuregcm.entities.PreKeyState;
import org.whispersystems.textsecuregcm.experiment.Experiment;
import org.whispersystems.textsecuregcm.identity.ServiceIdentifier;
import org.whispersystems.textsecuregcm.limits.RateLimiters;
import org.whispersystems.textsecuregcm.metrics.UserAgentTagUtil;
import org.whispersystems.textsecuregcm.storage.Account;
import org.whispersystems.textsecuregcm.storage.AccountsManager;
import org.whispersystems.textsecuregcm.storage.Device;
import org.whispersystems.textsecuregcm.storage.KeysManager;

@SuppressWarnings("OptionalUsedAsFieldOrParameterType")
@Path("/v2/keys")
@Tag(name = "Keys")
public class KeysController {

  private final RateLimiters rateLimiters;
  private final KeysManager keys;
  private final AccountsManager accounts;
  private final Experiment compareSignedEcPreKeysExperiment = new Experiment("compareSignedEcPreKeys");

  private static final String IDENTITY_KEY_CHANGE_COUNTER_NAME = name(KeysController.class, "identityKeyChange");
  private static final String IDENTITY_KEY_CHANGE_FORBIDDEN_COUNTER_NAME = name(KeysController.class, "identityKeyChangeForbidden");

  private static final String IDENTITY_TYPE_TAG_NAME = "identityType";
  private static final String HAS_IDENTITY_KEY_TAG_NAME = "hasIdentityKey";

  private static final Logger logger = LoggerFactory.getLogger(KeysController.class);

  public KeysController(RateLimiters rateLimiters, KeysManager keys, AccountsManager accounts) {
    this.rateLimiters = rateLimiters;
    this.keys = keys;
    this.accounts = accounts;
  }

  @GET
  @Produces(MediaType.APPLICATION_JSON)
  @Operation(summary = "Get prekey count",
      description = "Gets the number of one-time prekeys uploaded for this device and still available")
  @ApiResponse(responseCode = "200", description = "Body contains the number of available one-time prekeys for the device.", useReturnTypeSchema = true)
  @ApiResponse(responseCode = "401", description = "Account authentication check failed.")
  public PreKeyCount getStatus(@Auth final AuthenticatedAccount auth,
      @QueryParam("identity") final Optional<String> identityType) {

    final CompletableFuture<Integer> ecCountFuture =
        keys.getEcCount(getIdentifier(auth.getAccount(), identityType), auth.getAuthenticatedDevice().getId());

    final CompletableFuture<Integer> pqCountFuture =
        keys.getPqCount(getIdentifier(auth.getAccount(), identityType), auth.getAuthenticatedDevice().getId());

    return new PreKeyCount(ecCountFuture.join(), pqCountFuture.join());
  }

  @PUT
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  @ChangesDeviceEnabledState
  @Operation(summary = "Upload new prekeys",
      description = """
          Upload new prekeys for this device. Can also be used, from the primary device only, to set the account's identity
          key, but this is deprecated now that accounts can be created atomically.
      """)
  @ApiResponse(responseCode = "200", description = "Indicates that new keys were successfully stored.")
  @ApiResponse(responseCode = "401", description = "Account authentication check failed.")
  @ApiResponse(responseCode = "403", description = "Attempt to change identity key from a non-primary device.")
  @ApiResponse(responseCode = "422", description = "Invalid request format.")
  public void setKeys(@Auth final DisabledPermittedAuthenticatedAccount disabledPermittedAuth,
      @RequestBody @NotNull @Valid final PreKeyState preKeys,

      @Parameter(allowEmptyValue=true)
      @Schema(
          allowableValues={"aci", "pni"},
          defaultValue="aci",
          description="whether this operation applies to the account (aci) or phone-number (pni) identity")
      @QueryParam("identity") final Optional<String> identityType,

      @HeaderParam(HttpHeaders.USER_AGENT) String userAgent) {
    Account account = disabledPermittedAuth.getAccount();
    Device device = disabledPermittedAuth.getAuthenticatedDevice();
    boolean updateAccount = false;

    final boolean usePhoneNumberIdentity = usePhoneNumberIdentity(identityType);

    if (preKeys.getSignedPreKey() != null &&
        !preKeys.getSignedPreKey().equals(usePhoneNumberIdentity ? device.getPhoneNumberIdentitySignedPreKey() : device.getSignedPreKey())) {
      updateAccount = true;
    }

    final IdentityKey oldIdentityKey = usePhoneNumberIdentity ? account.getPhoneNumberIdentityKey() : account.getIdentityKey();
    if (!Objects.equals(preKeys.getIdentityKey(), oldIdentityKey)) {
      updateAccount = true;

      final boolean hasIdentityKey = oldIdentityKey != null;
      final Tags tags = Tags.of(UserAgentTagUtil.getPlatformTag(userAgent))
          .and(HAS_IDENTITY_KEY_TAG_NAME, String.valueOf(hasIdentityKey))
          .and(IDENTITY_TYPE_TAG_NAME, usePhoneNumberIdentity ? "pni" : "aci");

      if (!device.isMaster()) {
        Metrics.counter(IDENTITY_KEY_CHANGE_FORBIDDEN_COUNTER_NAME, tags).increment();

        throw new ForbiddenException();
      }
      Metrics.counter(IDENTITY_KEY_CHANGE_COUNTER_NAME, tags).increment();

      if (hasIdentityKey) {
        logger.warn("Existing {} identity key changed; account age is {} days",
            identityType.orElse("aci"),
            Duration.between(Instant.ofEpochMilli(device.getCreated()), Instant.now()).toDays());
      }
    }

    if (updateAccount) {
      account = accounts.update(account, a -> {
        if (preKeys.getSignedPreKey() != null) {
          a.getDevice(device.getId()).ifPresent(d -> {
            if (usePhoneNumberIdentity) {
              d.setPhoneNumberIdentitySignedPreKey(preKeys.getSignedPreKey());
            } else {
              d.setSignedPreKey(preKeys.getSignedPreKey());
            }
          });
        }

        if (usePhoneNumberIdentity) {
          a.setPhoneNumberIdentityKey(preKeys.getIdentityKey());
        } else {
          a.setIdentityKey(preKeys.getIdentityKey());
        }
      });
    }

    keys.store(
            getIdentifier(account, identityType), device.getId(),
            preKeys.getPreKeys(), preKeys.getPqPreKeys(), preKeys.getSignedPreKey(), preKeys.getPqLastResortPreKey())
        .join();
  }

  @GET
  @Path("/{identifier}/{device_id}")
  @Produces(MediaType.APPLICATION_JSON)
  @Operation(summary = "Fetch public keys for another user",
      description = "Retrieves the public identity key and available device prekeys for a specified account or phone-number identity")
  @ApiResponse(responseCode = "200", description = "Indicates at least one prekey was available for at least one requested device.", useReturnTypeSchema = true)
  @ApiResponse(responseCode = "401", description = "Account authentication check failed and unidentified-access key was not supplied or invalid.")
  @ApiResponse(responseCode = "404", description = "Requested identity or device does not exist, is not active, or has no available prekeys.")
  @ApiResponse(responseCode = "429", description = "Rate limit exceeded.", headers = @Header(
      name = "Retry-After",
      description = "If present, a positive integer indicating the number of seconds before a subsequent attempt could succeed"))
  public PreKeyResponse getDeviceKeys(@Auth Optional<AuthenticatedAccount> auth,
      @HeaderParam(OptionalAccess.UNIDENTIFIED) Optional<Anonymous> accessKey,

      @Parameter(description="the account or phone-number identifier to retrieve keys for")
      @PathParam("identifier") ServiceIdentifier targetIdentifier,

      @Parameter(description="the device id of a single device to retrieve prekeys for, or `*` for all enabled devices")
      @PathParam("device_id") String deviceId,

      @Parameter(allowEmptyValue=true, description="whether to retrieve post-quantum prekeys")
      @Schema(defaultValue="false")
      @QueryParam("pq") boolean returnPqKey,

      @HeaderParam(HttpHeaders.USER_AGENT) String userAgent)
      throws RateLimitExceededException {

    if (auth.isEmpty() && accessKey.isEmpty()) {
      throw new WebApplicationException(Response.Status.UNAUTHORIZED);
    }

    final Optional<Account> account = auth.map(AuthenticatedAccount::getAccount);

    final Account target;
    {
      final Optional<Account> maybeTarget = accounts.getByServiceIdentifier(targetIdentifier);

      OptionalAccess.verify(account, accessKey, maybeTarget, deviceId);

      target = maybeTarget.orElseThrow();
    }

    if (account.isPresent()) {
      rateLimiters.getPreKeysLimiter().validate(
          account.get().getUuid() + "." + auth.get().getAuthenticatedDevice().getId() + "__" + targetIdentifier.uuid()
              + "." + deviceId);
    }

    List<Device> devices = parseDeviceId(deviceId, target);
    List<PreKeyResponseItem> responseItems = new ArrayList<>(devices.size());

    for (Device device : devices) {
      ECSignedPreKey signedECPreKey = switch (targetIdentifier.identityType()) {
        case ACI -> device.getSignedPreKey();
        case PNI -> device.getPhoneNumberIdentitySignedPreKey();
      };

      ECPreKey unsignedECPreKey = keys.takeEC(targetIdentifier.uuid(), device.getId()).join().orElse(null);
      KEMSignedPreKey pqPreKey = returnPqKey ? keys.takePQ(targetIdentifier.uuid(), device.getId()).join().orElse(null) : null;

      compareSignedEcPreKeysExperiment.compareFutureResult(Optional.ofNullable(signedECPreKey),
          keys.getEcSignedPreKey(targetIdentifier.uuid(), device.getId()));

      if (signedECPreKey != null || unsignedECPreKey != null || pqPreKey != null) {
        final int registrationId = switch (targetIdentifier.identityType()) {
          case ACI -> device.getRegistrationId();
          case PNI -> device.getPhoneNumberIdentityRegistrationId().orElse(device.getRegistrationId());
        };

        responseItems.add(new PreKeyResponseItem(device.getId(), registrationId, signedECPreKey, unsignedECPreKey, pqPreKey));
      }
    }

    final IdentityKey identityKey = switch (targetIdentifier.identityType()) {
      case ACI -> target.getIdentityKey();
      case PNI -> target.getPhoneNumberIdentityKey();
    };

    if (responseItems.isEmpty()) {
      throw new WebApplicationException(Response.Status.NOT_FOUND);
    }
    return new PreKeyResponse(identityKey, responseItems);
  }

  @PUT
  @Path("/signed")
  @Consumes(MediaType.APPLICATION_JSON)
  @ChangesDeviceEnabledState
  @Operation(summary = "Upload a new signed prekey",
      description = """
          Upload a new signed elliptic-curve prekey for this device. Deprecated; use PUT /v2/keys with instead.
      """)
  @ApiResponse(responseCode = "200", description = "Indicates that new prekey was successfully stored.")
  @ApiResponse(responseCode = "401", description = "Account authentication check failed.")
  @ApiResponse(responseCode = "422", description = "Invalid request format.")
  public void setSignedKey(@Auth final AuthenticatedAccount auth,
      @Valid final ECSignedPreKey signedPreKey,
      @QueryParam("identity") final Optional<String> identityType) {

    Device device = auth.getAuthenticatedDevice();

    accounts.updateDevice(auth.getAccount(), device.getId(), d -> {
      if (usePhoneNumberIdentity(identityType)) {
        d.setPhoneNumberIdentitySignedPreKey(signedPreKey);
      } else {
        d.setSignedPreKey(signedPreKey);
      }
    });

    keys.storeEcSignedPreKeys(getIdentifier(auth.getAccount(), identityType), Map.of(device.getId(), signedPreKey)).join();
  }

  private static boolean usePhoneNumberIdentity(final Optional<String> identityType) {
    return "pni".equals(identityType.map(String::toLowerCase).orElse("aci"));
  }

  private static UUID getIdentifier(final Account account, final Optional<String> identityType) {
    return usePhoneNumberIdentity(identityType) ?
        account.getPhoneNumberIdentifier() :
        account.getUuid();
  }

  private List<Device> parseDeviceId(String deviceId, Account account) {
    if (deviceId.equals("*")) {
      return account.getDevices().stream().filter(Device::isEnabled).toList();
    }
    try {
      long id = Long.parseLong(deviceId);
      return account.getDevice(id).filter(Device::isEnabled).map(List::of).orElse(List.of());
    } catch (NumberFormatException e) {
      throw new WebApplicationException(Response.status(422).build());
    }
  }
}
