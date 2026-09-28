package com.omnideck.mobile.core;

import org.json.JSONObject;

/** Timing numbers from the final ("done": true) line of an Ollama reply. */
public final class ChatStats {
    public long totalMs;
    public long loadMs;
    public int promptTokens;
    public long promptMs;
    public int evalTokens;
    public long evalMs;
    public String doneReason = "";

    static ChatStats fromFinal(JSONObject o) {
        ChatStats s = new ChatStats();
        s.totalMs = o.optLong("total_duration", 0) / 1000000L;
        s.loadMs = o.optLong("load_duration", 0) / 1000000L;
        s.promptTokens = o.optInt("prompt_eval_count", 0);
        s.promptMs = o.optLong("prompt_eval_duration", 0) / 1000000L;
        s.evalTokens = o.optInt("eval_count", 0);
        s.evalMs = o.optLong("eval_duration", 0) / 1000000L;
        s.doneReason = o.optString("done_reason", "");
        return s;
    }

    public double tokensPerSecond() {
        return evalMs > 0 ? evalTokens * 1000.0 / evalMs : 0;
    }

    public double promptTokensPerSecond() {
        return promptMs > 0 ? promptTokens * 1000.0 / promptMs : 0;
    }

    /** True when Ollama had to (re)load the weights for this reply. */
    public boolean reloaded() {
        return loadMs >= 1000;
    }

    /** "212 tok · 38.4 tok/s · 5.5s" (+ " · load 7.2s" when the model was loaded). */
    public String summary() {
        StringBuilder sb = new StringBuilder();
        sb.append(evalTokens).append(" tok");
        if (evalMs > 0) sb.append(" · ").append(Fmt.oneDecimal(tokensPerSecond())).append(" tok/s");
        if (totalMs > 0) sb.append(" · ").append(Fmt.seconds(totalMs));
        if (reloaded()) sb.append(" · load ").append(Fmt.seconds(loadMs));
        return sb.toString();
    }
}
