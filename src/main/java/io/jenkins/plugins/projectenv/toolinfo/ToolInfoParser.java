package io.jenkins.plugins.projectenv.toolinfo;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;

public final class ToolInfoParser {

    private static final Type TOOL_INFOS_TYPE = new TypeToken<Map<String, List<ToolInfo>>>() {
    }.getType();

    private ToolInfoParser() {
        // noop
    }

    public static Map<String, List<ToolInfo>> fromJson(String rawToolInfos) {
        return createGson().fromJson(rawToolInfos, TOOL_INFOS_TYPE);
    }

    public static Gson createGson() {
        return new GsonBuilder().create();
    }

}
