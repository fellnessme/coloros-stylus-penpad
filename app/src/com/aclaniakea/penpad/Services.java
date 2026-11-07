package com.aclaniakea.penpad;

/** 记录悬浮服务是否在运行（同一进程内共享）。 */
final class Services {
    static volatile boolean isRunning = false;
}
