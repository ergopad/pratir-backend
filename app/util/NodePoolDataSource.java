package util;

import org.ergoplatform.appkit.impl.NodeDataSourceImpl;
import org.ergoplatform.appkit.impl.InputBoxImpl;
import org.ergoplatform.appkit.Transaction;
import org.ergoplatform.appkit.OutBox;
import org.ergoplatform.restapi.client.ApiClient;
import org.ergoplatform.explorer.client.ExplorerApiClient;
import org.ergoplatform.appkit.Address;
import java.util.List;
import java.util.ArrayList;
import org.ergoplatform.appkit.InputBox;
import org.ergoplatform.restapi.client.ErgoTransaction;
import org.ergoplatform.restapi.client.ErgoTransactionInput;
import org.ergoplatform.restapi.client.ErgoTransactionOutput;
import org.ergoplatform.explorer.client.model.Items;
import org.ergoplatform.appkit.ErgoClientException;
import retrofit2.Response;
import retrofit2.Call;
import retrofit2.Retrofit;
import okhttp3.OkHttpClient;
import com.google.common.base.Preconditions;
import org.javatuples.Triplet;

public class NodePoolDataSource {

	public static Triplet<List<String>, List<InputBox>, List<Transaction>> getMempoolBoxes(NodeDataSourceImpl ds) {
		List<String> spentBoxes = new ArrayList<>();
		List<InputBox> outputBoxes = new ArrayList<>();
		List<Transaction> allTransactions = new ArrayList<>();

		int offset = 0;
		int limit = 100;
		boolean finished = false;

		while (!finished) {
			List<Transaction> transactions = ds.getUnconfirmedTransactions(offset, limit);

			// now check if we have boxes on the address
			for (Transaction tx : transactions) {
				for (String input : tx.getInputBoxesIds()) {
					spentBoxes.add(input);
				}
				Short i = 0;
				for (OutBox output : tx.getOutputs()) {
					outputBoxes.add(output.convertToInputWith(tx.getId(), i));
					i++;
				}
				allTransactions.add(tx);
			}
			if (transactions.size() < limit) {
				finished = true;
			}
			offset += limit;
		}

		return Triplet.with(spentBoxes, outputBoxes, allTransactions);
	}

	public static Triplet<List<String>, List<InputBox>, List<Transaction>> getMempoolBoxesFor(Address address,
			int offset, int limit, NodeDataSourceImpl ds) {
		List<String> spentBoxes = new ArrayList<>();
		List<InputBox> outputBoxes = new ArrayList<>();
		String ergoTreeHex = address.getErgoAddress().script().bytesHex();
		List<Transaction> transactions = ds.getUnconfirmedTransactions(offset, limit);

		// now check if we have boxes on the address
		for (Transaction tx : transactions) {
			for (String input : tx.getInputBoxesIds()) {
				spentBoxes.add(input);
			}
			Short i = 0;
			for (OutBox output : tx.getOutputs()) {
				if (output.getErgoTree().bytesHex().equals(ergoTreeHex)) {
					// we have an unconfirmed box - get info from node for it
					try {
						outputBoxes.add(output.convertToInputWith(tx.getId(), i));
					} catch (ErgoClientException e) {
						// ignore error, no box to add
					}
				}
				i++;
			}
		}
		return Triplet.with(spentBoxes, outputBoxes, transactions);
	}

	public static List<InputBox> getAllUnspentBoxesFor(Address address, NodeDataSourceImpl ds) {
		return NodePoolDataSource.getAllUnspentBoxesFor(address, ds, null, true);
	}

	public static List<InputBox> getAllUnspentBoxesFor(Address address, NodeDataSourceImpl ds,
			Triplet<List<String>, List<InputBox>, List<Transaction>> mempoolState) {
		return NodePoolDataSource.getAllUnspentBoxesFor(address, ds, mempoolState, true);
	}

	public static List<InputBox> getAllUnspentBoxesFor(Address address, NodeDataSourceImpl ds,
			Triplet<List<String>, List<InputBox>, List<Transaction>> mempoolState, Boolean includeMempool) {

		List<InputBox> confirmed = new ArrayList<>();
		boolean foundAll = false;
		int offset = 0;

		while (!foundAll) {
			List<InputBox> confirmedPartial = ds.getUnspentBoxesFor(address, offset, 1000);
			confirmed.addAll(confirmedPartial);
			if (confirmedPartial.size() == 1000) {
				offset += 1000;
			} else {
				foundAll = true;
			}
		}

		if (includeMempool) {
			List<InputBox> unconfirmed = new ArrayList<>();
			String ergoTreeHex = address.getErgoAddress().script().bytesHex();

			Triplet<List<String>, List<InputBox>, List<Transaction>> mempool = null;
			if (mempoolState == null) {
				mempool = NodePoolDataSource.getMempoolBoxes(ds);
			} else {
				mempool = mempoolState;
			}
			unconfirmed.addAll(mempool.getValue1());
			unconfirmed.removeIf(ib -> !ib.getErgoTree().bytesHex().equals(ergoTreeHex));
			List<String> spent = mempool.getValue0();

			confirmed.addAll(unconfirmed);
			confirmed.removeIf(ib -> spent.contains(ib.getId().toString()));
		}

		return confirmed;
	}

	public static Integer getUnconfirmedTransactionState(String txId, NodeDataSourceImpl ds) {
		Call<ErgoTransaction> apiCall = ds.getNodeTransactionsApi().getUnconfirmedTransactionById(txId);
		try {
			Response<ErgoTransaction> response = apiCall.execute();
			return response.code();
		} catch (Exception e) {
			throw new ErgoClientException(
					String.format("Error executing API request to %s: %s", apiCall.request().url(), e.getMessage()), e);
		}
	}

}
