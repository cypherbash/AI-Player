package net.shasankp000.FunctionCaller;

/** Missing search data is terminal: a recovery model must not invent its coordinates. */
final class MissingDependencyException extends IllegalArgumentException {
    MissingDependencyException(String message) { super(message); }
}
