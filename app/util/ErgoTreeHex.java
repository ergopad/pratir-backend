package util;

import java.util.Objects;
import java.util.Arrays;
import com.google.gson.TypeAdapter;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.annotations.SerializedName;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import io.swagger.v3.oas.annotations.media.Schema;
import java.io.IOException;

class ErgoTreeHex {
    
    @SerializedName("ergoTree")
    private String ergoTree = null;

    public ErgoTreeHex ergoTree(String ergoTree){
        this.ergoTree = ergoTree;
        return this;
    }
}
