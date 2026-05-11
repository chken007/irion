package com.irion.ingestion;

/**
 * Thrown when a data ingestion operation fails due to file I/O errors,
 * format detection issues, or DuckDB conversion failures.
 *
 * <p>Caught by {@link DataIngestionService} and translated into a
 * {@link com.irion.domain.IngestionResult} with {@code FAILED} status.</p>
 */
public class IngestionException extends RuntimeException {

    /**
     * Creates an ingestion exception with a descriptive message.
     *
     * @param message human-readable description of the failure
     */
    public IngestionException(String message) {
        super(message);
    }

    /**
     * Creates an ingestion exception with a message and root cause.
     *
     * @param message human-readable description of the failure
     * @param cause   the underlying exception that triggered this failure
     */
    public IngestionException(String message, Throwable cause) {
        super(message, cause);
    }
}
