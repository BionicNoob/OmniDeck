package com.omnideck.mobile.core;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Saves conversations as one JSON file each (written atomically). */
public final class ConversationStore {
    public static final class Entry {
        public final String id;
        public final String title;
        public final long updated;
        public final int count;

        Entry(String id, String title, long updated, int count) {
            this.id = id;
            this.title = title;
            this.updated = updated;
            this.count = count;
        }
    }

    private final File dir;

    public ConversationStore(File dir) {
        this.dir = dir;
    }

    private File fileFor(String id) {
        return new File(dir, id.replaceAll("[^A-Za-z0-9_-]", "_") + ".json");
    }

    public synchronized void save(Conversation c) throws IOException {
        String json;
        try {
            json = c.toJson().toString();
        } catch (JSONException e) {
            throw new IOException(e.getMessage());
        }
        write(c.id, json);
    }

    /**
     * Writes an already-serialized conversation. Lets the caller serialize on
     * the thread that owns the conversation and do the disk I/O elsewhere.
     */
    public synchronized void write(String id, String json) throws IOException {
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Can't create " + dir);
        File tmp = new File(dir, fileFor(id).getName() + ".tmp");
        FileOutputStream out = new FileOutputStream(tmp);
        try {
            out.write(json.getBytes(Http.UTF8));
            out.getFD().sync();
        } finally {
            out.close();
        }
        File dst = fileFor(id);
        if (!tmp.renameTo(dst)) {
            dst.delete();
            if (!tmp.renameTo(dst)) throw new IOException("Can't write " + dst);
        }
    }

    public synchronized Conversation load(String id) {
        File f = fileFor(id);
        if (!f.isFile()) return null;
        try {
            FileInputStream in = new FileInputStream(f);
            try {
                return Conversation.fromJson(new JSONObject(Http.readAll(in, 64 * 1024 * 1024)));
            } finally {
                in.close();
            }
        } catch (IOException e) {
            return null;
        } catch (JSONException e) {
            return null;
        }
    }

    public synchronized boolean delete(String id) {
        return fileFor(id).delete();
    }

    /** Saved conversations, newest first. */
    public synchronized List<Entry> list() {
        List<Entry> out = new ArrayList<Entry>();
        File[] files = dir.listFiles();
        if (files == null) return out;
        for (File f : files) {
            if (!f.getName().endsWith(".json")) continue;
            String id = f.getName().substring(0, f.getName().length() - 5);
            Conversation c = load(id);
            if (c == null || c.isEmpty()) continue;
            c.autoTitle();
            int count = 0;
            for (ChatMessage m : c.messages) {
                if (!m.isNotice()) count++;
            }
            out.add(new Entry(c.id, c.title.length() > 0 ? c.title : "Untitled chat", c.updated, count));
        }
        Collections.sort(out, new Comparator<Entry>() {
            @Override
            public int compare(Entry a, Entry b) {
                return a.updated == b.updated ? 0 : (a.updated < b.updated ? 1 : -1);
            }
        });
        return out;
    }
}
