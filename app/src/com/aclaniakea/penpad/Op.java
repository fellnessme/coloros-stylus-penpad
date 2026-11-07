package com.aclaniakea.penpad;

import java.util.ArrayList;
import java.util.List;

/** 操作栈模型（不是快照模型）。所有 Op 都只操作 Scene 的笔画列表。 */
public interface Op {
    void undo(Scene scene);
    void redo(Scene scene);
    String label();
}
