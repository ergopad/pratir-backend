package util;

import com.google.common.base.Strings;

import org.ergoplatform.appkit.RestApiErgoClient;
import org.ergoplatform.appkit.NetworkType;
import org.ergoplatform.appkit.ErgoClient;
import org.ergoplatform.appkit.BlockchainContext;
import org.ergoplatform.appkit.BlockchainDataSource;

import org.ergoplatform.appkit.config.ErgoNodeConfig;
import org.ergoplatform.appkit.impl.BlockchainContextBuilderImpl;
import org.ergoplatform.explorer.client.ExplorerApiClient;
import org.ergoplatform.restapi.client.ApiClient;

import javax.annotation.Nullable;

import java.util.function.Function;

import okhttp3.OkHttpClient;

public class RestApiErgoClientWithNodePoolDataSource implements ErgoClient {

    private final NetworkType _networkType;
    private final NodePoolDataSource apiClient;

    public final static String defaultMainnetExplorerUrl = "https://api.ergoplatform.com";
    public final static String defaultTestnetExplorerUrl = "https://api-testnet.ergoplatform.com";

    RestApiErgoClientWithNodePoolDataSource(String nodeUrl, NetworkType networkType, String apiKey, String danaidesUrl) {
        _networkType = networkType;

        OkHttpClient.Builder httpClientBuilder = new OkHttpClient().newBuilder();

        ApiClient nodeClient = new ApiClient(nodeUrl, "ApiKeyAuth", apiKey);
        nodeClient.configureFromOkClientBuilder(httpClientBuilder);

        DanaidesAPIClient danaidesClient;
        if (!Strings.isNullOrEmpty(danaidesUrl)) {
            danaidesClient = new DanaidesAPIClient(danaidesUrl);
            danaidesClient.configureFromOkClientBuilder(httpClientBuilder);
        } else {
            danaidesClient = null;
        }
        apiClient = new NodePoolDataSource(nodeClient, danaidesClient);
    }

    @Override
    public <T> T execute(Function<BlockchainContext, T> action) {
        BlockchainContext ctx = new BlockchainContextBuilderImpl(apiClient, _networkType).build();
        T res = action.apply(ctx);
        return res;
    }

    public static ErgoClient create(String nodeUrl, NetworkType networkType, String apiKey, String explorerUrl) {
        return new RestApiErgoClientWithNodePoolDataSource(nodeUrl, networkType, apiKey, explorerUrl);
    }

    @Override
    public BlockchainDataSource getDataSource() {
        return apiClient;
    }
  
}
