package com.aclaniakea.penpad;

import java.util.ArrayList;
import java.util.List;

/** 一张批注画布：只存笔画对象，不存位图。位图缓存由 DrawView 维护。 */
public class Scene {

    public final List<Stroke> strokes = new ArrayList<>();
    public long firstT, lastT;
    private long nextId = 1;

    public long newId() { return nextId++; }

    public void add(Stroke s) {
        if (s.id == 0) s.id = newId();
        strokes.add(s);
        touch(s);
    }

    public void addAt(int index, Stroke s) {
        if (s.id == 0) s.id = newId();
        int i = Math.max(0, Math.min(index, strokes.size()));
        strokes.add(i, s);
        touch(s);
    }

    public int indexOf(Stroke s) { return strokes.indexOf(s); }

    public boolean remove(Stroke s) { return strokes.remove(s); }

    public void clear() {
        strokes.clear();
        firstT = lastT = 0;
    }

    public boolean isEmpty() { return strokes.isEmpty(); }

    private void touch(Stroke s) {
        if (firstT == 0 || s.tStart < firstT) firstT = s.tStart;
        if (s.tEnd > lastT) lastT = s.tEnd;
        if (lastT < s.tStart) lastT = s.tStart;
    }

    public void recomputeTime() {
        firstT = 0;
        lastT = 0;
        for (int i = 0; i < strokes.size(); i++) touch(strokes.get(i));
    }

    /** 时间回溯过滤：t<=0 表示不过滤 */
    public List<Stroke> view(long t) {
        if (t <= 0) return strokes;
        List<Stroke> out = new ArrayList<>(strokes.size());
        for (int i = 0; i < strokes.size(); i++) {
            Stroke s = strokes.get(i);
            if (s.tStart <= t) out.add(s);
        }
        return out;
    }
}
