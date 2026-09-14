package com.aisolutions.jobtaskmanagement.service.auth;

import com.aisolutions.shared.identity.IdentityClaimsExtractor;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * The module's central place to resolve "who is the caller" from the
 * verified JWT.
 *
 * Signature verification, expiry checks, and JWKS rotation are handled by
 * quarkus-smallrye-jwt at the request boundary; {@link IdentityClaimsExtractor}
 * only maps the already-verified claims onto {@code IdentityClaims}.
 */
@ApplicationScoped
public class AccessControlService {

  @Inject
  IdentityClaimsExtractor identityClaimsExtractor;

  /**
   * The current caller's staffId from the verified JWT, or empty string
   * when no identity is present.
   */
  public String getCurrentStaffId() {
    return identityClaimsExtractor.extract().staffId();
  }

  /** The current caller's resolved group authority (e.g. "SUPERADMIN"), or empty string. */
  public String getCurrentGroupAuthority() {
    return identityClaimsExtractor.extract().groupAuthority();
  }

  /**
   * The company routing claim for the current request, blank when absent.
   * {@code CompanyPoolManager} treats a blank value as "route to the
   * default database".
   */
  public String getCurrentCompanyId() {
    return identityClaimsExtractor.extract().companyId();
  }
}
