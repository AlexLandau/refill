package net.semlang.refill;

/**
 * (TODO: document)
 *
 * Note: For implementation reasons, the timestamp may be -1 for the initial value when the listener is added,
 * regardless of its actual timestamp at the time. Future versions of Refill may change the implementation such
 * that timestamps are not meaningful for comparing across different nodes.
 */
/*
 * Note: This is implemented in Java instead of Kotlin because doing so allows Kotlin lambda expressions to implement
 * this.
 */
@FunctionalInterface
public interface RefillEventListener<T> {
    void receive(RefillEvent<T> event);
}
