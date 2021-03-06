package nl.juraji.pinterestdownloader.io;

import nl.juraji.pinterestdownloader.configuration.AppData;
import nl.juraji.pinterestdownloader.configuration.UIConfig;
import nl.juraji.pinterestdownloader.model.ApiLimits;
import nl.juraji.pinterestdownloader.model.ModelMarshaller;
import nl.juraji.pinterestdownloader.model.pinterest.objects.Board;
import nl.juraji.pinterestdownloader.model.pinterest.objects.Pin;
import nl.juraji.pinterestdownloader.model.pinterest.objects.PinterestApiError;
import nl.juraji.pinterestdownloader.model.pinterest.responses.ApiResponse;
import nl.juraji.pinterestdownloader.model.pinterest.responses.BoardsResponse;
import nl.juraji.pinterestdownloader.model.pinterest.responses.OkResponse;
import nl.juraji.pinterestdownloader.model.pinterest.responses.PinsResponse;
import nl.juraji.pinterestdownloader.utils.DeadJettyLogger;
import nl.juraji.pinterestdownloader.utils.SslTlsContextFactory;
import nl.juraji.pinterestdownloader.utils.UriUtils;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.util.log.Log;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import static org.eclipse.jetty.http.HttpMethod.DELETE;
import static org.eclipse.jetty.http.HttpMethod.GET;

public final class ApiHandler {
  private static ApiHandler instance;
  private final UIConfig config = UIConfig.getInstance();
  private final HttpClient httpClient;
  private final ApiLimits apiLimits;

  private ApiHandler() {
    Log.setLog(new DeadJettyLogger());
    httpClient = new HttpClient(new SslTlsContextFactory());

    try {
      httpClient.start();
    } catch (Exception e) {
      e.printStackTrace();
    }

    apiLimits = new ApiLimits();
  }

  public static ApiHandler getInstance() {
    if (instance == null) instance = new ApiHandler();
    return instance;
  }

  public ApiLimits probeLimits() {
    if (apiLimits.getRemainingCount() == -1) doGet(endpointToUri("/me"), null);
    return apiLimits;
  }

  public List<Board> getMyBoards() {
    return fetchPaged(endpointToUri("/me/boards", Board.getFields()), BoardsResponse.class);
  }

  public List<Pin> getPinsForBoard(Board board) {
    String endpoint = "/boards/" + board.getBoardUri() + "/pins/";
    return fetchPaged(endpointToUri(endpoint, Pin.getFields()), PinsResponse.class);
  }

  public boolean deletePin(Pin pin) {
    OkResponse okResponse = doDelete(endpointToUri("/pins/" + pin.getId() + "/"), OkResponse.class);
    return okResponse != null;
  }

  private <T> List<T> fetchPaged(String requestUri, Class<? extends ApiResponse<T>> expectedResponseType) {
    List<T> list = new ArrayList<>();
    ApiResponse<T> response = doGet(requestUri, expectedResponseType);

    while (response != null) {
      list.addAll(response.getData());

      if (response.getPage() != null && response.getPage().getNext() != null) {
        response = doGet(response.getPage().getNext(), expectedResponseType);
      } else {
        response = null;
      }
    }

    return list;
  }

  private <T extends ApiResponse> T doGet(String requestUri, Class<T> expectedResponseType) {
    return doRequest(requestUri, expectedResponseType, GET);
  }

  private <T extends ApiResponse> T doDelete(String requestUri, Class<T> expectedResponseType) {
    return doRequest(requestUri, expectedResponseType, DELETE);
  }

  private <T extends ApiResponse> T doRequest(String requestUri, Class<T> expectedResponseType, HttpMethod method) {
    if (StringUtils.isEmpty(config.getApiAccessKey())) return null;

    try {
      Request request = httpClient.newRequest(requestUri);
      request.method(method);
      ContentResponse get = request.send();
      String content = get.getContentAsString();
      String mediaType = get.getMediaType();

      try {
        int limit = Integer.parseInt(get.getHeaders().get("X-Ratelimit-Limit"));
        int remaining = Integer.parseInt(get.getHeaders().get("X-Ratelimit-Remaining"));
        apiLimits.setTotalLimit(limit);
        apiLimits.setCallCount(limit - remaining);
        apiLimits.setRemainingCount(remaining);
      } catch (NumberFormatException e) {
        apiLimits.setTotalLimit(0);
        apiLimits.setCallCount(apiLimits.getCallCount() + 1);
        apiLimits.setRemainingCount(0);
      }

      if (expectedResponseType != null) {
        if (get.getStatus() == 200) {
          return new ModelMarshaller<>(expectedResponseType).unMarshal(content, mediaType);
        } else {
          PinterestApiError error = new ModelMarshaller<>(PinterestApiError.class).unMarshal(content, mediaType);
          System.err.println("The Api returned an error: " + error.getMessage() + " (" + error.getType() + ")");
        }
      }
    } catch (Exception e) {
      e.printStackTrace();
    }

    return null;
  }

  private String endpointToUri(String endpoint, String... fields) {
    String apiBaseUri = AppData.get("ApiBaseUri");
    HashMap<String, Object> query = new HashMap<>();

    query.put("access_token", config.getApiAccessKey());
    query.put("limit", 100);
    if (fields.length > 0) query.put("fields", String.join(",", fields));

    return UriUtils.appendQueryString(apiBaseUri + endpoint, query);
  }
}
