package com.lazarus.npcs.api;

import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Outcome of an API call. Normal rejections are reported here, never thrown.
 *
 * @param status  what happened
 * @param message short English explanation for logs
 * @param value   numeric result when meaningful (new reputation, new progress), otherwise 0
 * @param id      instance created or affected (task instance), otherwise null
 */
public record ApiResult(Status status, String message, int value, @Nullable UUID id) {
    public enum Status {
        OK,
        /** The entity is not an Apocalypse Now human NPC handled by Lazarus NPCs. */
        UNSUPPORTED_NPC,
        /** The NPC or player is taken (contract, surrender, another encounter, another active task). */
        BUSY,
        /** The encounter, offer or instance is past its deadline or already finished. */
        EXPIRED,
        /** Wrong dimension, dead entity, out-of-range value or missing target. */
        INVALID_CONTEXT,
        /** No dialogue or task is registered under that id. */
        MISSING_DEFINITION,
        /** A definition with that id is already registered, or the grant was already paid. */
        DUPLICATE,
        /** The request exceeds a fixed limit (reputation per result, options per page). */
        LIMIT,
        /** World-changing calls must run on the logical server thread. */
        WRONG_THREAD
    }

    public boolean ok() { return status == Status.OK; }

    public static ApiResult ok(String message) { return new ApiResult(Status.OK, message, 0, null); }

    public static ApiResult ok(String message, int value, @Nullable UUID id) { return new ApiResult(Status.OK, message, value, id); }

    public static ApiResult fail(Status status, String message) { return new ApiResult(status, message, 0, null); }
}
