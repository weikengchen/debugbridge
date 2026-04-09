package com.debugbridge.core.lua;

import java.util.concurrent.Callable;

/**
 * A ThreadDispatcher that executes directly on the calling thread.
 * Used for testing outside of Minecraft where there's no game thread.
 */
public class DirectDispatcher implements ThreadDispatcher {
    @Override
    public <T> T executeOnGameThread(Callable<T> task, long timeout) throws Exception {
        return task.call();
    }
}
