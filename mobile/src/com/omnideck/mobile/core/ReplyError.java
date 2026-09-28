package com.omnideck.mobile.core;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a raw failure — an Ollama error string, an HTTP status, a network
 * exception message — into a plain-language cause plus what to do about it.
 * The raw text is kept for diagnostics: a failed reply's footer (its stats)
 * is {@code message + " · " + raw}, see {@link #stats()}. Plain Java.
 */
public final class ReplyError {
    public static final String MODEL_MISSING = "model_missing";
    public static final String NO_VISION = "no_vision";
    public static final String OUT_OF_MEMORY = "out_of_memory";
    public static final String CRASHED = "crashed";
    public static final String TIMEOUT = "timeout";
    public static final String DROPPED = "dropped";
    public static final String UNREACHABLE = "unreachable";
    public static final String UNAUTHORIZED = "unauthorized";
    public static final String SERVER = "server";
    public static final String NOT_IN_LIBRARY = "not_in_library";
    public static final String DISK_FULL = "disk_full";
    public static final String OTHER = "other";

    private static final Pattern MODEL_NAME = Pattern.compile("model\\s+[\"'“]([^\"'”]+)[\"'”]\\s+not found",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern HTTP_CODE = Pattern.compile("\\(HTTP (\\d{3})\\)");
    private static final Pattern OOM = Pattern.compile("\\boom\\b");
    private static final Pattern EOF = Pattern.compile("\\beof\\b");

    /** One of the kind constants above. */
    public final String kind;
    /** What went wrong and what to do, in plain words. */
    public final String message;
    /** The failure as it was reported. */
    public final String raw;

    private ReplyError(String kind, String message, String raw) {
        this.kind = kind;
        this.message = message;
        this.raw = raw;
    }

    /**
     * Explains a failed chat reply. {@code model} is the model the request
     * went to; {@code hadImages} whether the request carried images (a
     * rejected request with images almost always means a text-only model).
     */
    public static ReplyError explain(String raw, String model, boolean hadImages) {
        String r = raw == null ? "" : raw.trim();
        String l = r.toLowerCase(Locale.US);
        int code = httpCode(r);
        String m = model == null || model.length() == 0 ? "this model" : model;
        if (r.length() == 0) return new ReplyError(OTHER, "The reply failed for an unknown reason. Try again.", "");
        if (l.contains("not found") && (l.contains("model") || l.contains("pull"))) {
            Matcher mm = MODEL_NAME.matcher(r);
            String name = mm.find() ? mm.group(1) : m;
            return new ReplyError(MODEL_MISSING, "**" + name + "** isn't installed on the PC. Download it with `/pull "
                    + name + "`, or pick another model.", r);
        }
        if ((l.contains("image") && (l.contains("missing data") || l.contains("not support")
                || l.contains("doesn't support") || l.contains("unsupported") || l.contains("vision")
                || l.contains("multimodal") || l.contains("projector")))
                || (hadImages && (code == 400 || l.contains("image")))) {
            return new ReplyError(NO_VISION, "**" + m + "** can't see images. Switch to a vision model (llava, "
                    + "gemma3, qwen2.5vl…) or send the message again without the image.", r);
        }
        if (l.contains("out of memory") || l.contains("more system memory") || l.contains("insufficient memory")
                || l.contains("not enough memory") || l.contains("failed to allocate") || l.contains("cudamalloc")
                || l.contains("unable to allocate") || OOM.matcher(l).find()) {
            return new ReplyError(OUT_OF_MEMORY, "The PC ran out of memory for **" + m + "**. Try a smaller model or a "
                    + "smaller context size (Settings), or close other programs on the PC.", r);
        }
        if (l.contains("runner process has terminated") || l.contains("runner process no longer running")
                || l.contains("exit status") || l.contains("segmentation fault") || l.contains("core dumped")) {
            return new ReplyError(CRASHED, "The model crashed on the PC. Try again; if it keeps happening, use a "
                    + "smaller model or context size.", r);
        }
        if (code == 401 || code == 403 || l.contains("unauthorized") || l.contains("forbidden")) {
            return new ReplyError(UNAUTHORIZED, "The AI server refused the request (not authorized). Check the API "
                    + "key in Settings › Connection.", r);
        }
        if (l.contains("timed out") || l.contains("timeout") || l.contains("deadline exceeded")) {
            return new ReplyError(TIMEOUT, "The PC stopped answering (timed out). A large model may still be "
                    + "loading — try again in a moment.", r);
        }
        if (l.contains("closed before") || l.contains("connection reset") || l.contains("broken pipe")
                || l.contains("unexpected end of stream") || l.contains("connection abort")
                || l.contains("stream was reset") || EOF.matcher(l).find()) {
            return new ReplyError(DROPPED, "The connection to the PC dropped in the middle of the reply. Check the "
                    + "Wi-Fi and try again.", r);
        }
        if (l.contains("can't connect") || l.contains("connection refused") || l.contains("no route to")
                || l.contains("unknown host") || l.contains("not connected") || l.contains("unreachable")
                || l.contains("failed to connect")) {
            return new ReplyError(UNREACHABLE, "Couldn't reach the AI on the PC. Check that Ollama is running and "
                    + "that the phone is on the same network.", r);
        }
        if (code >= 500) {
            return new ReplyError(SERVER, "The AI server hit an internal error. Try again.", r);
        }
        return new ReplyError(OTHER, r, r);
    }

    /** Explains a failed model download ({@code name} is the requested model). */
    public static ReplyError explainPull(String raw, String name) {
        String r = raw == null ? "" : raw.trim();
        String l = r.toLowerCase(Locale.US);
        String n = name == null ? "" : name.trim();
        if (l.contains("file does not exist") || l.contains("manifest unknown") || l.contains("not found")
                || l.contains("no such model")) {
            return new ReplyError(NOT_IN_LIBRARY, "There's no model called **" + n + "** in the Ollama library. "
                    + "Check the exact name at ollama.com/library.", r);
        }
        if (l.contains("no space left") || l.contains("disk full") || l.contains("not enough space")) {
            return new ReplyError(DISK_FULL, "The PC's disk is full. Free some space (or delete a model you don't use) "
                    + "and try again.", r);
        }
        ReplyError e = explain(r, n, false);
        return e.kind.equals(MODEL_MISSING) ? new ReplyError(NOT_IN_LIBRARY, "There's no model called **" + n
                + "** in the Ollama library. Check the exact name at ollama.com/library.", r) : e;
    }

    /** For a failed reply's footer: the plain message, then " · " and the raw error for diagnostics. */
    public String stats() {
        if (kind.equals(OTHER) || raw.length() == 0) return Fmt.ellipsize(message, 240);
        return plain(message) + " · " + Fmt.ellipsize(raw, 160);
    }

    /** The message without Markdown marks (footers and labels are plain text). */
    public static String plain(String s) {
        return s.replace("**", "").replace("`", "");
    }

    private static int httpCode(String raw) {
        Matcher m = HTTP_CODE.matcher(raw);
        int code = -1;
        while (m.find()) code = Integer.parseInt(m.group(1));
        return code;
    }
}
