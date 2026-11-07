package com.aclaniakea.penpad;

import java.util.ArrayList;
import java.util.List;

/** 具体操作实现。 */
public final class Ops {

    private Ops() {}

    /** 新增一条笔画 */
    public static class AddStroke implements Op {
        final Stroke stroke;
        int index = -1;

        public AddStroke(Stroke s) { this.stroke = s; }

        @Override public void undo(Scene scene) {
            index = scene.indexOf(stroke);
            scene.remove(stroke);
        }

        @Override public void redo(Scene scene) {
            scene.addAt(index < 0 ? Integer.MAX_VALUE : index, stroke);
        }

        @Override public String label() { return "add"; }
    }

    /** 删除若干条笔画（胶带橡皮 / 套索删除 / 清空） */
    public static class RemoveStrokes implements Op {
        final List<Stroke> removed = new ArrayList<>();
        final List<Integer> indices = new ArrayList<>();

        public RemoveStrokes() {}

        public void capture(Scene scene, Stroke s) {
            int i = scene.indexOf(s);
            if (i < 0) return;
            removed.add(s);
            indices.add(i);
            scene.remove(s);
        }

        public boolean isEmpty() { return removed.isEmpty(); }

        @Override public void undo(Scene scene) {
            for (int i = removed.size() - 1; i >= 0; i--) {
                scene.addAt(indices.get(i), removed.get(i));
            }
        }

        @Override public void redo(Scene scene) {
            for (int i = 0; i < removed.size(); i++) scene.remove(removed.get(i));
        }

        @Override public String label() { return "remove"; }
    }

    /** 像素擦除：一条原笔画被切成若干片段（一个手势里可能有多条） */
    public static class SplitStrokes implements Op {
        static class Split {
            final Stroke original;
            final int index;
            final List<Stroke> fragments;
            Split(Stroke o, int i, List<Stroke> f) { original = o; index = i; fragments = f; }
        }

        final List<Split> splits = new ArrayList<>();

        public void add(Stroke original, int index, List<Stroke> fragments) {
            splits.add(new Split(original, index, fragments));
        }

        public boolean isEmpty() { return splits.isEmpty(); }

        @Override public void undo(Scene scene) {
            for (int i = splits.size() - 1; i >= 0; i--) {
                Split sp = splits.get(i);
                for (int k = 0; k < sp.fragments.size(); k++) scene.remove(sp.fragments.get(k));
                scene.addAt(sp.index, sp.original);
            }
        }

        @Override public void redo(Scene scene) {
            for (int i = 0; i < splits.size(); i++) {
                Split sp = splits.get(i);
                scene.remove(sp.original);
                for (int k = 0; k < sp.fragments.size(); k++) scene.addAt(sp.index + k, sp.fragments.get(k));
            }
        }

        @Override public String label() { return "split"; }
    }

    /** 整体平移（套索移动） */
    public static class MoveStrokes implements Op {
        final List<Stroke> strokes;
        final float dx, dy;

        public MoveStrokes(List<Stroke> s, float dx, float dy) {
            this.strokes = new ArrayList<>(s);
            this.dx = dx;
            this.dy = dy;
        }

        @Override public void undo(Scene scene) { translate(-dx, -dy); }

        @Override public void redo(Scene scene) { translate(dx, dy); }

        private void translate(float mx, float my) {
            for (int i = 0; i < strokes.size(); i++) strokes.get(i).translate(mx, my);
        }

        @Override public String label() { return "move"; }
    }

    /** 改色（套索改色） */
    public static class RecolorStrokes implements Op {
        final List<Stroke> strokes;
        final int[] oldColors;
        final int newColor;

        public RecolorStrokes(List<Stroke> s, int newColor) {
            this.strokes = new ArrayList<>(s);
            this.newColor = newColor;
            this.oldColors = new int[s.size()];
            for (int i = 0; i < s.size(); i++) oldColors[i] = s.get(i).color;
        }

        @Override public void undo(Scene scene) {
            for (int i = 0; i < strokes.size(); i++) strokes.get(i).color = oldColors[i];
        }

        @Override public void redo(Scene scene) {
            for (int i = 0; i < strokes.size(); i++) strokes.get(i).color = newColor;
        }

        @Override public String label() { return "recolor"; }
    }

    /** 复制（套索复制） */
    public static class DuplicateStrokes implements Op {
        final List<Stroke> copies = new ArrayList<>();

        public void build(List<Stroke> selected, float offX, float offY) {
            for (int i = 0; i < selected.size(); i++) {
                Stroke c = selected.get(i).copy();
                c.id = 0;
                c.translate(offX, offY);
                c.tStart = c.tEnd = System.currentTimeMillis();
                copies.add(c);
            }
        }

        public List<Stroke> copies() { return copies; }

        @Override public void undo(Scene scene) {
            for (int i = 0; i < copies.size(); i++) scene.remove(copies.get(i));
        }

        @Override public void redo(Scene scene) {
            for (int i = 0; i < copies.size(); i++) scene.add(copies.get(i));
        }

        @Override public String label() { return "duplicate"; }
    }

    /** 一次手势批量改色（供套索使用，保留旧色） */
    public static class Noop implements Op {
        @Override public void undo(Scene scene) {}
        @Override public void redo(Scene scene) {}
        @Override public String label() { return "noop"; }
    }

    /** 清空画布 */
    public static class ClearAll implements Op {
        final List<Stroke> all = new ArrayList<>();

        public ClearAll(Scene scene) { all.addAll(scene.strokes); }

        @Override public void undo(Scene scene) {
            for (int i = 0; i < all.size(); i++) scene.addAt(Integer.MAX_VALUE, all.get(i));
        }

        @Override public void redo(Scene scene) { scene.strokes.clear(); }

        @Override public String label() { return "clear"; }
    }
}
