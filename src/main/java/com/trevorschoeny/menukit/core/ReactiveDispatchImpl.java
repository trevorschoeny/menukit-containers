package com.trevorschoeny.menukit.core;

import com.trevorschoeny.menukit.window.ReactEvent;
import com.trevorschoeny.menukit.window.ReactiveDispatch;
import com.trevorschoeny.menukit.window.ReactiveHook;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MKC's implementation of the {@link ReactiveDispatch} port — the server-thread
 * firing of authoritative reactions. MK has already resolved the hook and entered
 * the {@link com.trevorschoeny.menukit.window.ReactionGuard re-entrancy bound};
 * the firing seam (owed, per menu type) calls
 * {@link com.trevorschoeny.menukit.window.WindowReactions#fireInsert}/{@code fireTake}
 * (server tier) from inside the menu transaction on the server thread, so this
 * just runs the hook there.
 *
 * <p>Consumer hooks are isolated: an exception from one reaction is logged and
 * swallowed, never allowed to abort the surrounding menu mutation / packet sweep
 * (a misbehaving reaction must not desync or crash the container).
 */
public final class ReactiveDispatchImpl implements ReactiveDispatch {

    public static final ReactiveDispatchImpl INSTANCE = new ReactiveDispatchImpl();

    private static final Logger LOGGER = LoggerFactory.getLogger("MenuKit-Containers/Reactions");

    private ReactiveDispatchImpl() {}

    @Override
    public void fire(ReactiveHook hook, ReactEvent event) {
        try {
            hook.react(event);
        } catch (RuntimeException e) {
            // A consumer reaction threw. Isolate it: the menu transaction must
            // still commit cleanly. Log with the address so the consumer can find it.
            LOGGER.error("[Reactions] hook threw for {} (cause {}) — isolated, transaction continues",
                    event.address(), event.cause(), e);
        }
    }
}
