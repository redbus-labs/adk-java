CREATE TABLE spans (
                       trace_id VARCHAR(32) NOT NULL,
                       span_id VARCHAR(16) NOT NULL,
                       parent_span_id VARCHAR(16),
                       name VARCHAR(255) NOT NULL,
                       start_time TIMESTAMPTZ NOT NULL,
                       end_time TIMESTAMPTZ NOT NULL,
                       status_code VARCHAR(20),
                       status_message TEXT,
                       attributes JSONB,
                       PRIMARY KEY (trace_id, span_id)
);

CREATE INDEX idx_spans_trace_id ON spans (trace_id);
CREATE INDEX idx_spans_parent_span_id ON spans (parent_span_id);
CREATE INDEX idx_spans_start_time ON spans (start_time);
