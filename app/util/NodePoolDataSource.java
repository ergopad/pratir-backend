package util;

import org.ergoplatform.appkit.impl.NodeAndExplorerDataSourceImpl;
import org.ergoplatform.appkit.impl.InputBoxImpl;
import org.ergoplatform.restapi.client.ApiClient;
import org.ergoplatform.explorer.client.ExplorerApiClient;
import org.ergoplatform.appkit.Address;
import jline.internal.Nullable;
import java.util.List;
import java.util.ArrayList;
import org.ergoplatform.appkit.InputBox;
import org.ergoplatform.restapi.client.Transactions;
import org.ergoplatform.restapi.client.ErgoTransaction;
import org.ergoplatform.restapi.client.ErgoTransactionInput;
import org.ergoplatform.restapi.client.ErgoTransactionOutput;
import org.ergoplatform.appkit.ErgoClientException;
import retrofit2.Response;
import retrofit2.Call;
import retrofit2.Retrofit;
import okhttp3.OkHttpClient;
import com.google.common.base.Preconditions;
import org.javatuples.Triplet;  

public class NodePoolDataSource extends NodeAndExplorerDataSourceImpl {

    private final DanaidesAPI danaidesApi;

    public NodePoolDataSource(ApiClient nodeClient, DanaidesAPIClient danaidesClient) {
        super(nodeClient, null);

        if (danaidesClient != null) {
            OkHttpClient okDanaides = danaidesClient.getOkBuilder().build();
            Retrofit retrofitDanaides = danaidesClient.getAdapterBuilder()
                .client(okDanaides)
                .build();
            danaidesApi = retrofitDanaides.create(DanaidesAPI.class);
        } else
            danaidesApi = null;
    }

    @Override
    public List<InputBox> getUnconfirmedUnspentBoxesFor(Address address, int offset, int limit) {
        return getMempoolBoxesFor(address,offset,limit).getValue1();
    }

    public Triplet<List<String>,List<InputBox>,Transactions> getMempoolBoxesFor(Address address, int offset, int limit) {
        List<String> spentBoxes = new ArrayList<>();
        List<InputBox> outputBoxes = new ArrayList<>();
        String ergoTreeHex = address.getErgoAddress().script().bytesHex();
        Transactions transactions = executeCall(getNodeTransactionsApi().getUnconfirmedTransactions(
             limit, offset));

        // now check if we have boxes on the address
        for (ErgoTransaction tx : transactions) {
            for (ErgoTransactionInput input : tx.getInputs()) {
                spentBoxes.add(input.getBoxId());
            }
            for (ErgoTransactionOutput output : tx.getOutputs()) {
                if (output.getErgoTree().equals(ergoTreeHex)) {
                    // we have an unconfirmed box - get info from node for it
                    try {
                        outputBoxes.add(new InputBoxImpl(output));
                    } catch (ErgoClientException e) {
                        // ignore error, no box to add
                    }
                }
            }
        }
        return Triplet.with(spentBoxes, outputBoxes, transactions);
    }

    public List<InputBox> getAllUnspentBoxesFor(Address address) {

        List<InputBox> confirmed = new ArrayList<>();
        boolean foundAll = false;
        int offset = 0;

        while (!foundAll) {
            List<InputBox> confirmedPartial = getUnspentBoxesFor(address,offset,500);
            confirmed.addAll(confirmedPartial);
            if (confirmedPartial.size() == 500) {
                offset += 500;
            } else {
                foundAll = true;
            }
        }
        
        List<InputBox> unconfirmed = new ArrayList<>();
        List<String> spent = new ArrayList<>();

        offset = 0;
        foundAll = false;

        while (!foundAll) {
            Triplet<List<String>,List<InputBox>,Transactions> partialMempool = getMempoolBoxesFor(address,offset,100);
            unconfirmed.addAll(partialMempool.getValue1());
            spent.addAll(partialMempool.getValue0());
            if (partialMempool.getValue2().size() >= 99) {
                offset += 100;
            } else {
                foundAll = true;
            }
        }

        confirmed.removeIf(ib -> spent.contains(ib.getId().toString()));
        unconfirmed.removeIf(ib -> spent.contains(ib.getId().toString()));

        confirmed.addAll(unconfirmed);

        return confirmed;
    }

    @Override
    public List<InputBox> getUnspentBoxesFor(Address address, int offset, int limit) {
        List<InputBox> inputBoxes = new ArrayList<>();
        List<ErgoTransactionOutput> boxes = executeCall(danaidesApi.getUTXOByErgoTree(new ErgoTreeHex().ergoTree(address.getErgoAddress().script().bytesHex()), limit, offset));

        for (ErgoTransactionOutput output : boxes) {
            inputBoxes.add(new InputBoxImpl(output));
        }
        return inputBoxes;
    }

    public Integer getUnconfirmedTransactionState(String txId) {
        Call<ErgoTransaction> apiCall = getNodeTransactionsApi().getUnconfirmedTransactionById(txId);
        try {
            Response<ErgoTransaction> response = apiCall.execute();
            return response.code();
        } catch (Exception e) {
            throw new ErgoClientException(
                String.format("Error executing API request to %s: %s", apiCall.request().url(), e.getMessage()), e);
        }
    }
  
}
