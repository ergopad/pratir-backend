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
import org.ergoplatform.restapi.client.ErgoTransactionOutput;
import org.ergoplatform.appkit.ErgoClientException;

public class NodePoolDataSource extends NodeAndExplorerDataSourceImpl {

    public NodePoolDataSource(ApiClient nodeClient, @Nullable ExplorerApiClient explorerClient) {
        super(nodeClient, explorerClient);
    }

    @Override
    public List<InputBox> getUnconfirmedUnspentBoxesFor(Address address, int offset, int limit) {
        List<InputBox> inputBoxes = new ArrayList<>();
        String ergoTreeHex = address.getErgoAddress().script().bytesHex();
        Transactions transactions = executeCall(getNodeTransactionsApi().getUnconfirmedTransactionsByErgoTree(
            "\"" + ergoTreeHex + "\"", offset, limit));

        // now check if we have boxes on the address
        for (ErgoTransaction tx : transactions) {
            for (ErgoTransactionOutput output : tx.getOutputs()) {
                if (output.getErgoTree().equals(ergoTreeHex)) {
                    // we have an unconfirmed box - get info from node for it
                    try {
                        inputBoxes.add(new InputBoxImpl(output));
                    } catch (ErgoClientException e) {
                        // ignore error, no box to add
                    }
                }
            }
        }
        return inputBoxes;
    }
  
}
