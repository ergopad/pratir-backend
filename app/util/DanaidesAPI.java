package util;

import org.ergoplatform.restapi.client.CollectionFormats.*;

import retrofit2.Call;
import retrofit2.http.*;

import okhttp3.RequestBody;
import okhttp3.ResponseBody;

import org.ergoplatform.restapi.client.ApiError;
import org.ergoplatform.restapi.client.ErgoTransactionOutput;
import org.ergoplatform.restapi.client.FeeHistogram;
import org.ergoplatform.restapi.client.Transactions;

import java.util.List;


public interface DanaidesAPI {
  /**
   * Checks an Ergo transaction without sending it over the network. Checks that transaction is valid and its inputs are in the UTXO set. Returns transaction identifier if the transaction is passing the checks.
   * 
   * @param body  (required)
   * @return Call&lt;String&gt;
   */
  @Headers({
    "Content-Type:application/json"
  })
  @POST("api/utxo/byErgoTree")
  Call<List<ErgoTransactionOutput>> getUTXOByErgoTree(
                    @retrofit2.http.Body ErgoTreeHex body ,
                    @retrofit2.http.Query("limit") Integer limit                ,     
                    @retrofit2.http.Query("offset") Integer offset   
  );

}
