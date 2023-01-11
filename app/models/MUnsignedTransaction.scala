package models

import org.ergoplatform.appkit.UnsignedTransaction
import scala.collection.JavaConverters._
import scala.collection.mutable.HashMap
import org.ergoplatform.appkit.impl.InputBoxImpl
import org.ergoplatform.appkit.impl.UnsignedTransactionImpl

final case class MUnsignedTransaction(
    inputs: Array[MInput],
    dataInputs: Array[MInput],
    outputs: Array[MOutput]
)

object MUnsignedTransaction {
    def apply(unsigned: UnsignedTransaction): MUnsignedTransaction = {
        val inputs = unsigned.getInputs().asScala.map(inp => 
            MInput(
                inp.asInstanceOf[InputBoxImpl].getExtension().values.map(kv => (kv._1.toString(),kv._2.toString())),
                inp.getId().toString(),
                inp.getValue().toString(),
                inp.getErgoTree().bytesHex,
                inp.getTokens().asScala.map(token => MToken(token.getId().toString(),token.getValue().toString())).toArray,
                inp.getRegisters().asScala.zipWithIndex.map(kv => ("R"+(kv._2+4).toString(),kv._1.toHex())).toMap,
                inp.getCreationHeight(),
                inp.asInstanceOf[InputBoxImpl].getErgoBox().transactionId.toString(),
                inp.asInstanceOf[InputBoxImpl].getErgoBox().index
            )   
        ).toArray.asInstanceOf[Array[MInput]]
        val dataInputs = unsigned.getDataInputs().asScala.map(inp => 
            MInput(
                inp.asInstanceOf[InputBoxImpl].getExtension().values.map(kv => (kv._1.toString(),kv._2.toString())),
                inp.getId().toString(),
                inp.getValue().toString(),
                inp.getErgoTree().bytesHex,
                inp.getTokens().asScala.map(token => MToken(token.getId().toString(),token.getValue().toString())).toArray,
                inp.getRegisters().asScala.zipWithIndex.map(kv => ("R"+(kv._2+4).toString(),kv._1.toHex())).toMap,
                inp.getCreationHeight(),
                inp.asInstanceOf[InputBoxImpl].getErgoBox().transactionId.toString(),
                inp.asInstanceOf[InputBoxImpl].getErgoBox().index
            )   
        ).toArray.asInstanceOf[Array[MInput]]
        val outputs = unsigned.getOutputs().asScala.map(outp =>
            MOutput(
                outp.getValue().toString(),
                outp.getErgoTree().bytesHex,
                outp.getTokens().asScala.map(token => MToken(token.getId().toString(),token.getValue().toString())).toArray,
                outp.getRegisters().asScala.zipWithIndex.map(kv => ("R"+(kv._2+4).toString(),kv._1.toHex())).toMap,
                outp.getCreationHeight()
            )    
        ).toArray.asInstanceOf[Array[MOutput]]
        MUnsignedTransaction(inputs,dataInputs,outputs)
    }
}

// def unsignedTxToJson(unsignedTx: UnsignedTransactionImpl): String = {
//         inputs = []
//         for i in unsignedTx.getInputs():
//             j = json.loads(i.toJson(False))
//             j["extension"] = {}
//             j["value"] = str(j["value"])
//             for ass in j["assets"]:
//                 ass["amount"] = str(ass["amount"])
//             inputs.append(j)
//         dataInputs = []
//         for di in unsignedTx.getDataInputs():
//             j = json.loads(di.toJson(False))
//             j["extension"] = {}
//             j["value"] = str(j["value"])
//             for ass in j["assets"]:
//                 ass["amount"] = str(ass["amount"])
//             dataInputs.append(j)
//         outputs = []
//         for o in unsignedTx.getOutputs():
//             assets = []
//             for t in o.getTokens():
//                 assets.append({'tokenId': str(t.getId().toString()), 'amount': str(t.getValue())})  
//             additionalRegisters = {}
//             r = 4
//             for additionalRegister in o.getRegisters():
//                 additionalRegisters[f'R{r}']=additionalRegister.toHex()
//                 r+=1
//             outputs.append({
//                 'value': str(o.getValue()),
//                 'ergoTree': o.getErgoTree().bytesHex(),
//                 'assets': assets,
//                 'additionalRegisters': additionalRegisters,
//                 'creationHeight': o.getCreationHeight()
//             })

//         return {
//             'inputs': inputs,
//             'dataInputs': dataInputs,
//             'outputs': outputs
//         }
//     }