package org.example.msg;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public final class Json {
    public static final Gson GSON = new GsonBuilder().serializeNulls().create();

    public static JsonObject parseObject(String text) {
        return JsonParser.parseString(text).getAsJsonObject();
    }

    public static String toJson(Object o) {
        return GSON.toJson(o);
    }

    private Json() {
    }
}
