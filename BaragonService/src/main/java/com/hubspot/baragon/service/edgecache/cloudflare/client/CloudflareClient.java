package com.hubspot.baragon.service.edgecache.cloudflare.client;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import com.google.common.base.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.google.inject.name.Named;
import com.hubspot.baragon.service.BaragonServiceModule;
import com.hubspot.baragon.service.config.EdgeCacheConfiguration;
import com.hubspot.horizon.HttpRequest.Method;
import com.ning.http.client.AsyncHttpClient;
import com.ning.http.client.AsyncHttpClient.BoundRequestBuilder;
import com.ning.http.client.Response;

@Singleton
public class CloudflareClient {
  private static final int MAX_ZONES_PER_PAGE = 50;
  private static final int MAX_DNS_RECORDS_PER_PAGE = 100;

  private final AsyncHttpClient httpClient;
  private final ObjectMapper objectMapper;
  private final EdgeCacheConfiguration edgeCacheConfiguration;

  @Inject
  public CloudflareClient(Optional<EdgeCacheConfiguration> edgeCacheConfiguration,
                          @Named(BaragonServiceModule.BARAGON_SERVICE_HTTP_CLIENT) AsyncHttpClient httpClient,
                          ObjectMapper objectMapper) {
    this.httpClient = httpClient;
    this.objectMapper = objectMapper;
    this.edgeCacheConfiguration = edgeCacheConfiguration.get();
  }

  public boolean purgeCache(String zoneId, List<String> cacheTags) throws CloudflareClientException {
    CloudflarePurgeRequest purgeRequest = new CloudflarePurgeRequest(Collections.emptyList(), cacheTags);
    Response response = requestWith(Method.DELETE, String.format("zones/%s/purge_cache", zoneId), purgeRequest);
    return isSuccess(response);
  }

  private Response requestWith(Method method, String path, Object body) throws CloudflareClientException {
    return request(method, path, Optional.of(body), Optional.absent(), Optional.absent(), Optional.absent(), Optional.absent());
  }

  private Response request(Method method, String path, Optional<Object> body, Optional<Integer> page, Optional<Integer> perPage, Optional<String> order, Optional<String> direction) throws CloudflareClientException {
    BoundRequestBuilder builder;

    switch (method) {
      case DELETE:
        builder = httpClient.prepareDelete(edgeCacheConfiguration.getApiBase() + path);
        break;
      case GET:
      default:
        builder = httpClient.prepareGet(edgeCacheConfiguration.getApiBase() + path);
    }

    builder
        .addHeader("X-Auth-Email", edgeCacheConfiguration.getApiEmail())
        .addHeader("X-Auth-Key", edgeCacheConfiguration.getApiKey());

    body.asSet().forEach(b -> builder.setBody(b.toString()));

    page.asSet().forEach(p -> builder.addQueryParameter("page", page.get().toString()));
    perPage.asSet().forEach(p -> builder.addQueryParameter("per_page", perPage.get().toString()));
    order.asSet().forEach(o -> builder.addQueryParameter("order", order.get()));
    direction.asSet().forEach(d -> builder.addQueryParameter("direction", direction.get()));

    try {
      return builder.execute().get();
    } catch (Exception e) {
      throw new CloudflareClientException("Unexpected error during Cloudflare API call", e);
    }
  }

  private boolean isSuccess(Response response) {
    return response.getStatusCode() >= 200 && response.getStatusCode() < 300;
  }

  public List<CloudflareZone> getAllZones() throws CloudflareClientException {
    CloudflareListZonesResponse cloudflareResponse = listZonesPaged(1);

    List<CloudflareZone> zones = new ArrayList<>();
    zones.addAll(cloudflareResponse.getResult());

    CloudflareResultInfo paginationInfo = cloudflareResponse.getResultInfo();
    for (int i = 2; i <= paginationInfo.getTotalCount(); i++) {
      CloudflareListZonesResponse cloudflarePageResponse = listZonesPaged(i);
      zones.addAll(cloudflarePageResponse.getResult());
    }
    return zones;
  }

  private CloudflareListZonesResponse listZonesPaged(Integer page) throws CloudflareClientException {
    Response response = pagedRequest(Method.GET, "zones", page, MAX_ZONES_PER_PAGE);
    if (!isSuccess(response)) {
      try {
        CloudflareResponse parsedResponse = objectMapper.readValue(response.getResponseBody(), CloudflareListZonesResponse.class);
        throw new CloudflareClientException("Failed to get zones, " + parsedResponse);
      } catch (IOException e) {
        throw new CloudflareClientException("Failed to get zones; unable to parse error response");
      }
    }

    try {
      return objectMapper.readValue(response.getResponseBody(), CloudflareListZonesResponse.class);
    } catch (IOException e) {
      throw new CloudflareClientException("Unable to parse Cloudflare List Zones response", e);
    }
  }

  public List<CloudflareDnsRecord> listDnsRecords(String zoneId) throws CloudflareClientException {
    CloudflareListDnsRecordsResponse cloudflareResponse = listDnsRecordsPaged(zoneId, 1);

    List<CloudflareDnsRecord> dnsRecords = cloudflareResponse.getResult();

    CloudflareResultInfo paginationInfo = cloudflareResponse.getResultInfo();
    for (int i = 2; i <= paginationInfo.getTotalCount(); i++) {
      CloudflareListDnsRecordsResponse cloudflarePageResponse = listDnsRecordsPaged(zoneId, i);
      dnsRecords.addAll(cloudflarePageResponse.getResult());
    }
    return dnsRecords;
  }

  private CloudflareListDnsRecordsResponse listDnsRecordsPaged(String zoneId, Integer page) throws CloudflareClientException {
    Response response = pagedRequest(Method.GET, String.format("zones/%s/dns_records", zoneId), page, MAX_DNS_RECORDS_PER_PAGE);
    if (!isSuccess(response)) {
      try {
        CloudflareResponse parsedResponse = objectMapper.readValue(response.getResponseBody(), CloudflareListDnsRecordsResponse.class);
        throw new CloudflareClientException("Failed to get DNS records, " + parsedResponse);
      } catch (IOException e) {
        throw new CloudflareClientException("Failed to get DNS records; unable to parse error response");
      }
    }

    try {
      return objectMapper.readValue(response.getResponseBody(), CloudflareListDnsRecordsResponse.class);
    } catch (IOException e) {
      throw new CloudflareClientException("Unable to parse Cloudflare List DNS Records response", e);
    }
  }

  private Response pagedRequest(Method method, String path, Integer page, Integer perPage) throws CloudflareClientException {
    return request(method, path, Optional.absent(), Optional.of(page), Optional.of(perPage), Optional.absent(), Optional.absent());
  }
}
