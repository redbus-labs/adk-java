/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.adk.web.controller.examples.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import javax.annotation.Nullable;

/**
 * Domain-specific request DTO for search SSE endpoints.
 *
 * <p>This is an example of how to create domain-specific request DTOs that can be used with the
 * generic SSE infrastructure. Applications should create their own DTOs based on their specific
 * requirements.
 *
 * <p><b>Example Request:</b>
 *
 * <pre>{@code
 * {
 *   "mriClientId": "client123",
 *   "mriSessionId": "session456",
 *   "userQuery": "Find buses from Mumbai to Delhi",
 *   "appName": "search-app",
 *   "pageContext": {
 *     "sourceCityId": 1,
 *     "destinationCityId": 2,
 *     "dateOfJourney": "2026-06-25"
 *   }
 * }
 * }</pre>
 *
 * @author Sandeep Belgavi
 * @since January 24, 2026
 */
public class SearchRequest {

  @JsonProperty("mriClientId")
  private String mriClientId;

  @JsonProperty("mriSessionId")
  private String mriSessionId;

  @JsonProperty("userQuery")
  private String userQuery;

  @JsonProperty("appName")
  @Nullable
  private String appName;

  @JsonProperty("pageContext")
  @Nullable
  private PageContext pageContext;

  /** Default constructor for Jackson deserialization */
  public SearchRequest() {}

  /**
   * Creates a new SearchRequest.
   *
   * @param mriClientId the client ID
   * @param mriSessionId the session ID
   * @param userQuery the user query
   */
  public SearchRequest(String mriClientId, String mriSessionId, String userQuery) {
    this.mriClientId = mriClientId;
    this.mriSessionId = mriSessionId;
    this.userQuery = userQuery;
  }

  public String getMriClientId() {
    return mriClientId;
  }

  public void setMriClientId(String mriClientId) {
    this.mriClientId = mriClientId;
  }

  public String getMriSessionId() {
    return mriSessionId;
  }

  public void setMriSessionId(String mriSessionId) {
    this.mriSessionId = mriSessionId;
  }

  public String getUserQuery() {
    return userQuery;
  }

  public void setUserQuery(String userQuery) {
    this.userQuery = userQuery;
  }

  @Nullable
  public String getAppName() {
    return appName;
  }

  public void setAppName(@Nullable String appName) {
    this.appName = appName;
  }

  @Nullable
  public PageContext getPageContext() {
    return pageContext;
  }

  public void setPageContext(@Nullable PageContext pageContext) {
    this.pageContext = pageContext;
  }

  /**
   * Page context containing search parameters.
   *
   * <p>This nested class represents the context in which the search is being performed, such as
   * source/destination cities and travel date.
   */
  public static class PageContext {

    @JsonProperty("sourceCityId")
    @Nullable
    private Integer sourceCityId;

    @JsonProperty("destinationCityId")
    @Nullable
    private Integer destinationCityId;

    @JsonProperty("dateOfJourney")
    @Nullable
    private String dateOfJourney;

    /** Default constructor */
    public PageContext() {}

    /**
     * Creates a new PageContext.
     *
     * @param sourceCityId the source city ID
     * @param destinationCityId the destination city ID
     * @param dateOfJourney the date of journey (YYYY-MM-DD format)
     */
    public PageContext(
        @Nullable Integer sourceCityId,
        @Nullable Integer destinationCityId,
        @Nullable String dateOfJourney) {
      this.sourceCityId = sourceCityId;
      this.destinationCityId = destinationCityId;
      this.dateOfJourney = dateOfJourney;
    }

    @Nullable
    public Integer getSourceCityId() {
      return sourceCityId;
    }

    public void setSourceCityId(@Nullable Integer sourceCityId) {
      this.sourceCityId = sourceCityId;
    }

    @Nullable
    public Integer getDestinationCityId() {
      return destinationCityId;
    }

    public void setDestinationCityId(@Nullable Integer destinationCityId) {
      this.destinationCityId = destinationCityId;
    }

    @Nullable
    public String getDateOfJourney() {
      return dateOfJourney;
    }

    public void setDateOfJourney(@Nullable String dateOfJourney) {
      this.dateOfJourney = dateOfJourney;
    }
  }
}
