package com.aisolutions;

import com.aisolutions.shared.tenancy.CompanyPoolManager;

import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;

@Path("/health")
public class HealthResource {

  @Inject
  CompanyPoolManager companyPoolManager;

  @GET
  public Uni<Response> health() {
    return companyPoolManager.defaultPool().query("SELECT 1")
        .execute()
        .onItem().transform(rs -> Response.ok("UP").build());
  }
}
