package com.omnideck.mobile.core;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A small, forgiving Markdown parser for chat replies. It turns Markdown
 * into plain text plus style ranges (bold, italic, code, code blocks,
 * headings, quotes, links, lists, tables). The Android layer maps the
 * ranges to spans. Works on partial text while a reply is still streaming:
 * an unclosed code fence styles everything after it as code, and unclosed
 * inline markers stay literal until they're closed.
 */
public final class Markdown {
    public static final int BOLD = 1;
    public static final int ITALIC = 2;
    public static final int CODE = 3;
    public static final int CODE_BLOCK = 4;
    public static final int H1 = 5;
    public static final int H2 = 6;
    public static final int H3 = 7;
    public static final int QUOTE = 8;
    public static final int LINK = 9;
    public static final int STRIKE = 10;
    public static final int RULE = 11;
    public static final int TABLE = 12;
    public static final int BULLET = 13;

    public static final class Span {
        public final int type;
        public final int start;
        public final int end;
        public final String url;

        Span(int type, int start, int end, String url) {
            this.type = type;
            this.start = start;
            this.end = end;
            this.url = url;
        }

        @Override
        public String toString() {
            return type + "[" + start + "," + end + ")" + (url != null ? url : "");
        }
    }

    public static final class Result {
        public final String text;
        public final List<Span> spans;

        Result(String text, List<Span> spans) {
            this.text = text;
            this.spans = spans;
        }

        public boolean has(int type) {
            for (Span s : spans) {
                if (s.type == type) return true;
            }
            return false;
        }
    }

    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.*?)\\s*#*\\s*$");
    private static final Pattern RULE_LINE = Pattern.compile("^\\s*([-*_])(\\s*\\1){2,}\\s*$");
    private static final Pattern BULLET_LINE = Pattern.compile("^(\\s*)[-*+]\\s+(.*)$");
    private static final Pattern TASK = Pattern.compile("^\\[([ xX])\\]\\s+(.*)$");
    private static final Pattern NUMBERED = Pattern.compile("^(\\s*)(\\d{1,4})[.)]\\s+(.*)$");
    private static final Pattern TABLE_SEPARATOR = Pattern.compile("^\\s*\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?\\s*$");
    private static final Pattern URL = Pattern.compile("https?://[^\\s<>()\\[\\]{}\"'`]+");

    private Markdown() {}

    public static Result parse(String src) {
        StringBuilder out = new StringBuilder(src == null ? 0 : src.length());
        List<Span> spans = new ArrayList<Span>();
        if (src == null || src.length() == 0) return new Result("", spans);
        String[] lines = src.replace("\r\n", "\n").split("\n", -1);
        boolean inFence = false;
        int fenceStart = 0;
        String fenceLang = "";
        int blankRun = 0;
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                if (!inFence) {
                    inFence = true;
                    fenceStart = out.length();
                    fenceLang = trimmed.substring(3).trim();
                    int sp = fenceLang.indexOf(' ');
                    if (sp > 0) fenceLang = fenceLang.substring(0, sp);
                } else {
                    inFence = false;
                    int end = out.length();
                    if (end > fenceStart && out.charAt(end - 1) == '\n') end--;
                    // A code block's url holds its fence language ("" when none).
                    if (end > fenceStart) spans.add(new Span(CODE_BLOCK, fenceStart, end, fenceLang));
                }
                blankRun = 0;
                continue;
            }
            if (inFence) {
                out.append(line).append('\n');
                continue;
            }
            if (trimmed.length() == 0) {
                // Keep paragraph breaks, but never more than one blank line.
                if (++blankRun > 1) continue;
                out.append('\n');
                continue;
            }
            blankRun = 0;
            Matcher m;
            int start = out.length();
            if ((m = HEADING.matcher(trimmed)).matches()) {
                int level = m.group(1).length();
                inline(m.group(2), out, spans, 0);
                spans.add(new Span(level == 1 ? H1 : level == 2 ? H2 : H3, start, out.length(), null));
            } else if (RULE_LINE.matcher(trimmed).matches()) {
                out.append("────────────");
                spans.add(new Span(RULE, start, out.length(), null));
            } else if (trimmed.startsWith(">")) {
                String q = trimmed.substring(1);
                while (q.startsWith(">")) q = q.substring(1);
                inline(q.trim(), out, spans, 0);
                spans.add(new Span(QUOTE, start, out.length(), null));
            } else if ((m = BULLET_LINE.matcher(line)).matches()) {
                indent(out, m.group(1));
                String item = m.group(2);
                Matcher t = TASK.matcher(item);
                if (t.matches()) {
                    out.append(t.group(1).trim().length() > 0 ? "☑ " : "☐ ");
                    item = t.group(2);
                } else {
                    int b = out.length();
                    out.append("• ");
                    spans.add(new Span(BULLET, b, b + 1, null));
                }
                inline(item, out, spans, 0);
            } else if ((m = NUMBERED.matcher(line)).matches()) {
                indent(out, m.group(1));
                int b = out.length();
                out.append(m.group(2)).append(". ");
                spans.add(new Span(BULLET, b, out.length() - 1, null));
                inline(m.group(3), out, spans, 0);
            } else if (trimmed.startsWith("|") && trimmed.length() > 1) {
                if (TABLE_SEPARATOR.matcher(trimmed).matches()) {
                    out.append(trimmed.replaceAll("[-:]", "─"));
                } else {
                    inline(trimmed, out, spans, 0);
                }
                spans.add(new Span(TABLE, start, out.length(), null));
            } else {
                inline(line, out, spans, 0);
            }
            out.append('\n');
        }
        if (inFence) {
            int end = out.length();
            if (end > fenceStart && out.charAt(end - 1) == '\n') end--;
            if (end > fenceStart) spans.add(new Span(CODE_BLOCK, fenceStart, end, fenceLang));
        }
        // Trim trailing newlines, clamping spans to the new length.
        int len = out.length();
        while (len > 0 && out.charAt(len - 1) == '\n') len--;
        out.setLength(len);
        List<Span> clamped = new ArrayList<Span>(spans.size());
        for (Span s : spans) {
            int e = Math.min(s.end, len);
            if (e > s.start) clamped.add(new Span(s.type, s.start, e, s.url));
        }
        autoLinks(out, clamped);
        return new Result(out.toString(), clamped);
    }

    private static void indent(StringBuilder out, String ws) {
        int n = 0;
        for (int i = 0; i < ws.length(); i++) n += ws.charAt(i) == '\t' ? 4 : 1;
        for (int i = 0; i < Math.min(n / 2, 8); i++) out.append("  ");
    }

    private static boolean isWord(char c) {
        return Character.isLetterOrDigit(c);
    }

    /** Parses inline markup of {@code s} into {@code out}. */
    static void inline(String s, StringBuilder out, List<Span> spans, int depth) {
        int n = s.length();
        int i = 0;
        while (i < n) {
            char c = s.charAt(i);
            // Backslash escapes.
            if (c == '\\' && i + 1 < n && "\\`*_{}[]()#+-.!|~>".indexOf(s.charAt(i + 1)) >= 0) {
                out.append(s.charAt(i + 1));
                i += 2;
                continue;
            }
            // `code` / ``code``
            if (c == '`') {
                int j = i;
                while (j < n && s.charAt(j) == '`') j++;
                int ticks = j - i;
                int close = s.indexOf(repeat('`', ticks), j);
                if (close >= 0) {
                    String code = s.substring(j, close);
                    if (code.length() > 1 && code.startsWith(" ") && code.endsWith(" ")) {
                        code = code.substring(1, code.length() - 1);
                    }
                    int st = out.length();
                    out.append(code);
                    if (out.length() > st) spans.add(new Span(CODE, st, out.length(), null));
                    i = close + ticks;
                } else {
                    out.append(s, i, j);
                    i = j;
                }
                continue;
            }
            if (depth < 4) {
                // **bold** / __bold__
                if ((c == '*' || c == '_') && i + 1 < n && s.charAt(i + 1) == c
                        && !(c == '_' && i > 0 && isWord(s.charAt(i - 1)))) {
                    String marker = c == '*' ? "**" : "__";
                    int close = s.indexOf(marker, i + 2);
                    if (close > i + 2 && !Character.isWhitespace(s.charAt(i + 2))
                            && !Character.isWhitespace(s.charAt(close - 1))) {
                        int st = out.length();
                        inline(s.substring(i + 2, close), out, spans, depth + 1);
                        spans.add(new Span(BOLD, st, out.length(), null));
                        i = close + 2;
                        continue;
                    }
                }
                // ~~strike~~
                if (c == '~' && i + 1 < n && s.charAt(i + 1) == '~') {
                    int close = s.indexOf("~~", i + 2);
                    if (close > i + 2) {
                        int st = out.length();
                        inline(s.substring(i + 2, close), out, spans, depth + 1);
                        spans.add(new Span(STRIKE, st, out.length(), null));
                        i = close + 2;
                        continue;
                    }
                }
                // *italic* / _italic_
                if ((c == '*' || c == '_') && i + 1 < n && !Character.isWhitespace(s.charAt(i + 1))
                        && s.charAt(i + 1) != c && !(c == '_' && i > 0 && isWord(s.charAt(i - 1)))) {
                    int close = findItalicClose(s, c, i + 1);
                    if (close > 0) {
                        int st = out.length();
                        inline(s.substring(i + 1, close), out, spans, depth + 1);
                        spans.add(new Span(ITALIC, st, out.length(), null));
                        i = close + 1;
                        continue;
                    }
                }
                // [text](url)
                if (c == '[') {
                    int mid = s.indexOf("](", i + 1);
                    if (mid > i) {
                        int close = s.indexOf(')', mid + 2);
                        if (close > mid + 2) {
                            String url = s.substring(mid + 2, close).trim();
                            if (url.length() > 0 && url.indexOf(' ') < 0 && s.substring(i + 1, mid).indexOf('[') < 0) {
                                int st = out.length();
                                inline(s.substring(i + 1, mid), out, spans, depth + 1);
                                if (out.length() > st) spans.add(new Span(LINK, st, out.length(), url));
                                i = close + 1;
                                continue;
                            }
                        }
                    }
                }
            }
            out.append(c);
            i++;
        }
    }

    private static int findItalicClose(String s, char c, int from) {
        int n = s.length();
        for (int k = from + 1; k < n; k++) {
            if (s.charAt(k) != c) continue;
            if (Character.isWhitespace(s.charAt(k - 1))) continue;
            if (k + 1 < n && s.charAt(k + 1) == c) {
                k++; // part of a bold marker; skip it
                continue;
            }
            if (c == '_' && k + 1 < n && isWord(s.charAt(k + 1))) continue;
            return k;
        }
        return -1;
    }

    private static void autoLinks(StringBuilder out, List<Span> spans) {
        Matcher m = URL.matcher(out);
        List<Span> add = new ArrayList<Span>();
        while (m.find()) {
            int st = m.start(), en = m.end();
            while (en > st && ".,;:!?".indexOf(out.charAt(en - 1)) >= 0) en--;
            if (en <= st) continue;
            boolean overlaps = false;
            for (Span s : spans) {
                if ((s.type == LINK || s.type == CODE || s.type == CODE_BLOCK) && st < s.end && en > s.start) {
                    overlaps = true;
                    break;
                }
            }
            if (!overlaps) add.add(new Span(LINK, st, en, out.substring(st, en)));
        }
        spans.addAll(add);
    }

    private static String repeat(char c, int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) sb.append(c);
        return sb.toString();
    }
}
