CREATE TABLE spans (
    -- Primary identifiers (OpenTelemetry standard)
    trace_id VARCHAR(32) NOT NULL,
    span_id VARCHAR(16) NOT NULL,
    parent_span_id VARCHAR(16),

    -- Span metadata
    name VARCHAR(255) NOT NULL,

    -- Timing (both nanoseconds for precision)
    start_time BIGINT NOT NULL,
    end_time BIGINT NOT NULL,
    duration DECIMAL(10,2),

    -- Status
    status_code VARCHAR(20), -- OK, ERROR, UNSET
    status_message TEXT,

    attributes JSONB,

    PRIMARY KEY (trace_id, span_id)
);
CREATE INDEX idx_spans_trace_id ON spans (trace_id);
CREATE INDEX idx_spans_parent_span_id ON spans (parent_span_id);
CREATE INDEX idx_spans_start_time ON spans (start_time);
