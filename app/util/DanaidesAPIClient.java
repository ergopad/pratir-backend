package util;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;

import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.RequestBody;
import okhttp3.ResponseBody;
import java.time.format.DateTimeFormatter;
import retrofit2.Converter;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;
import retrofit2.converter.scalars.ScalarsConverterFactory;
import org.ergoplatform.restapi.client.auth.HttpBasicAuth;
import org.ergoplatform.restapi.client.auth.ApiKeyAuth;
import org.ergoplatform.restapi.client.JSON;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.text.DateFormat;
import java.util.LinkedHashMap;
import java.util.Map;

public class DanaidesAPIClient {

  private String _hostUrl;
  private OkHttpClient.Builder okBuilder;
  private Retrofit.Builder adapterBuilder;
  private JSON json;

  public Gson getGson() { return json.getGson(); }

  public DanaidesAPIClient(String hostUrl) {
    _hostUrl = hostUrl;
    createDefaultAdapter();
  }

  public void createDefaultAdapter() {
    json = new JSON();
    okBuilder = new OkHttpClient.Builder();

    if (!_hostUrl.endsWith("/"))
      _hostUrl = _hostUrl + "/";

    adapterBuilder = new Retrofit
      .Builder()
      .baseUrl(_hostUrl)
      .addConverterFactory(ScalarsConverterFactory.create())
      .addConverterFactory(GsonCustomConverterFactory.create(json.getGson()));;
  }

  public <S> S createService(Class<S> serviceClass) {
    return adapterBuilder
      .client(okBuilder.build())
      .build()
      .create(serviceClass);
  }

  public DanaidesAPIClient setDateFormat(DateFormat dateFormat) {
    this.json.setDateFormat(dateFormat);
    return this;
  }

  public DanaidesAPIClient setSqlDateFormat(DateFormat dateFormat) {
    this.json.setSqlDateFormat(dateFormat);
    return this;
  }

  public DanaidesAPIClient setOffsetDateTimeFormat(DateTimeFormatter dateFormat) {
    this.json.setOffsetDateTimeFormat(dateFormat);
    return this;
  }

  public DanaidesAPIClient setLocalDateFormat(DateTimeFormatter dateFormat) {
    this.json.setLocalDateFormat(dateFormat);
    return this;
  }


  public Retrofit.Builder getAdapterBuilder() {
    return adapterBuilder;
  }

  public DanaidesAPIClient setAdapterBuilder(Retrofit.Builder adapterBuilder) {
    this.adapterBuilder = adapterBuilder;
    return this;
  }

  public OkHttpClient.Builder getOkBuilder() {
    return okBuilder;
  }

  /**
   * Clones the okBuilder given in parameter, adds the auth interceptors and uses it to configure the Retrofit
   * @param okClient An instance of OK HTTP client
   */
  public void configureFromOkclient(OkHttpClient okClient) {
    this.okBuilder = okClient.newBuilder();
  }

    /**
     * Uses the okBuilder given in parameter, adds the auth interceptors and uses it to configure the Retrofit
     * @param okClientBuilder An instance of OK HTTP client builder
     */
    public void configureFromOkClientBuilder(OkHttpClient.Builder okClientBuilder) {
        this.okBuilder = okClientBuilder;
    }

}

/**
 * This wrapper is to take care of this case:
 * when the deserialization fails due to JsonParseException and the
 * expected type is String, then just return the body string.
 */
class GsonResponseBodyConverterToString<T> implements Converter<ResponseBody, T> {
  private final Gson gson;
  private final Type type;

  GsonResponseBodyConverterToString(Gson gson, Type type) {
    this.gson = gson;
    this.type = type;
  }

  @Override public T convert(ResponseBody value) throws IOException {
    String returned = value.string();
    try {
      return gson.fromJson(returned, type);
    }
    catch (JsonParseException e) {
      return (T) returned;
    }
  }
}

class GsonCustomConverterFactory extends Converter.Factory
{
  private final Gson gson;
  private final GsonConverterFactory gsonConverterFactory;

  public static GsonCustomConverterFactory create(Gson gson) {
    return new GsonCustomConverterFactory(gson);
  }

  private GsonCustomConverterFactory(Gson gson) {
    if (gson == null)
      throw new NullPointerException("gson == null");
    this.gson = gson;
    this.gsonConverterFactory = GsonConverterFactory.create(gson);
  }

  @Override
  public Converter<ResponseBody, ?> responseBodyConverter(Type type, Annotation[] annotations, Retrofit retrofit) {
    if (type.equals(String.class))
      return new GsonResponseBodyConverterToString<Object>(gson, type);
    else
      return gsonConverterFactory.responseBodyConverter(type, annotations, retrofit);
  }

  @Override
  public Converter<?, RequestBody> requestBodyConverter(Type type, Annotation[] parameterAnnotations, Annotation[] methodAnnotations, Retrofit retrofit) {
    return gsonConverterFactory.requestBodyConverter(type, parameterAnnotations, methodAnnotations, retrofit);
  }
}