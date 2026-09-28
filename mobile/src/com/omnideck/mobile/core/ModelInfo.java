package com.omnideck.mobile.core;

import org.json.JSONObject;

/** One model from /api/tags, optionally merged with its /api/ps entry. */
public final class ModelInfo {
    public final String name;
    public final long size;
    public final String parameterSize;
    public final String quantization;
    public final String family;
    public final String modifiedAt;

    // Filled from /api/ps when the model is resident.
    public boolean loaded;
    public long sizeVram;
    public int contextLength;
    public String expiresAt = "";

    public ModelInfo(String name, long size, String parameterSize, String quantization, String family,
                     String modifiedAt) {
        this.name = name;
        this.size = size;
        this.parameterSize = parameterSize == null ? "" : parameterSize;
        this.quantization = quantization == null ? "" : quantization;
        this.family = family == null ? "" : family;
        this.modifiedAt = modifiedAt == null ? "" : modifiedAt;
    }

    static ModelInfo fromTags(JSONObject o) {
        JSONObject d = o.optJSONObject("details");
        String name = o.optString("name", o.optString("model", ""));
        return new ModelInfo(name, o.optLong("size", 0),
                d == null ? "" : d.optString("parameter_size", ""),
                d == null ? "" : d.optString("quantization_level", ""),
                d == null ? "" : d.optString("family", ""),
                o.optString("modified_at", ""));
    }

    /** "3.2B · Q4_K_M · 2.0 GB" */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        if (parameterSize.length() > 0) sb.append(parameterSize);
        if (quantization.length() > 0) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(quantization);
        }
        if (size > 0) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(Fmt.bytes(size));
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return name;
    }
}
